#!/usr/bin/env python3
"""LAN video server for completed BitTorrent data.

It scans a directory for video files, uses nearby .torrent metadata when
available for grouping, and exposes an HTTP Range endpoint suitable for
Android Media3/ExoPlayer.
"""

from __future__ import annotations

import argparse
import hashlib
import ipaddress
import json
import mimetypes
import os
import re
import socket
import subprocess
import sys
import threading
import time
from dataclasses import dataclass
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import unquote, urlencode, urlparse
from urllib.request import Request, urlopen

VIDEO_EXTENSIONS = {
    ".mp4", ".mkv", ".webm", ".avi", ".mov", ".m4v", ".ts", ".m2ts",
    ".flv", ".wmv", ".mpeg", ".mpg",
}
RANGE_RE = re.compile(r"bytes=(\d*)-(\d*)$")


class BencodeError(ValueError):
    pass


def bdecode(data: bytes) -> Any:
    """Small strict bencode decoder; enough to inspect .torrent files."""
    index = 0

    def parse() -> Any:
        nonlocal index
        if index >= len(data):
            raise BencodeError("unexpected end")
        marker = data[index:index + 1]
        if marker == b"i":
            end = data.find(b"e", index)
            if end < 0:
                raise BencodeError("unterminated integer")
            raw = data[index + 1:end]
            index = end + 1
            return int(raw)
        if marker == b"l":
            index += 1
            result = []
            while data[index:index + 1] != b"e":
                result.append(parse())
            index += 1
            return result
        if marker == b"d":
            index += 1
            result = {}
            while data[index:index + 1] != b"e":
                key = parse()
                if not isinstance(key, bytes):
                    raise BencodeError("dictionary key is not bytes")
                result[key] = parse()
            index += 1
            return result
        if marker.isdigit():
            colon = data.find(b":", index)
            if colon < 0:
                raise BencodeError("invalid byte string")
            length = int(data[index:colon])
            index = colon + 1
            result = data[index:index + length]
            if len(result) != length:
                raise BencodeError("truncated byte string")
            index += length
            return result
        raise BencodeError(f"invalid marker at {index}")

    value = parse()
    if index != len(data):
        raise BencodeError("trailing data")
    return value


def bencode(value: Any) -> bytes:
    if isinstance(value, int):
        return b"i" + str(value).encode("ascii") + b"e"
    if isinstance(value, bytes):
        return str(len(value)).encode("ascii") + b":" + value
    if isinstance(value, list):
        return b"l" + b"".join(bencode(item) for item in value) + b"e"
    if isinstance(value, dict):
        return b"d" + b"".join(
            bencode(key) + bencode(value[key]) for key in sorted(value)
        ) + b"e"
    raise BencodeError(f"cannot encode {type(value).__name__}")


def text(value: bytes | None) -> str:
    return (value or b"").decode("utf-8", errors="replace")


@dataclass(frozen=True)
class Video:
    id: str
    name: str
    torrent: str
    path: Path | None
    size: int
    mime: str
    torrent_hash: str | None = None
    torrent_path: str | None = None

    def public(self, engine_online: bool) -> dict[str, Any]:
        streamable = self.path is not None or (
            engine_online and self.torrent_hash is not None and self.torrent_path is not None
        )
        return {
            "id": self.id,
            "name": self.name,
            "torrent": self.torrent,
            "size": self.size,
            "mime": self.mime,
            "available": streamable,
            "stream_url": f"/stream/{self.id}" if streamable else None,
        }


class TorrentEngine:
    def __init__(self, executable: Path, data_dir: Path, port: int = 8790):
        self.executable = executable
        self.data_dir = data_dir
        self.port = port
        self.base_url = f"http://127.0.0.1:{port}"
        self.process: subprocess.Popen | None = None
        self.log_file = None
        self.online = False

    def start(self) -> bool:
        if not self.executable.is_file():
            print(f"按需下载引擎未找到: {self.executable}")
            return False
        log_path = self.executable.parent / "torrent-engine.log"
        self.log_file = log_path.open("ab")
        flags = subprocess.CREATE_NO_WINDOW if sys.platform == "win32" else 0
        self.process = subprocess.Popen(
            [
                str(self.executable),
                "-addr", f"127.0.0.1:{self.port}",
                "-fileDir", str(self.data_dir),
                "-seed",
                "-unlimitedCache",
                "-torrentGrace", "24h",
            ],
            cwd=self.executable.parent,
            stdout=self.log_file,
            stderr=subprocess.STDOUT,
            creationflags=flags,
        )
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            if self.process.poll() is not None:
                break
            try:
                with urlopen(f"{self.base_url}/status", timeout=1):
                    self.online = True
                    print("按需 BitTorrent 流媒体引擎: 已启动")
                    return True
            except (OSError, URLError):
                time.sleep(0.4)
        print(f"按需下载引擎启动失败，请查看 {log_path}")
        self.stop()
        return False

    def stop(self) -> None:
        self.online = False
        if self.process and self.process.poll() is None:
            self.process.terminate()
            try:
                self.process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self.process.kill()
        if self.log_file:
            self.log_file.close()
            self.log_file = None

    def register(self, torrent_file: Path) -> bool:
        if not self.online:
            return False
        try:
            request = Request(
                f"{self.base_url}/metainfo",
                data=torrent_file.read_bytes(),
                method="POST",
                headers={"Content-Type": "application/x-bittorrent"},
            )
            with urlopen(request, timeout=10) as response:
                response.read()
            return True
        except (OSError, HTTPError, URLError) as error:
            print(f"加载种子失败 {torrent_file.name}: {error}")
            return False

    def open_stream(self, video: Video, method: str, range_header: str | None):
        query = urlencode({"ih": video.torrent_hash, "path": video.torrent_path})
        headers = {}
        if range_header:
            headers["Range"] = range_header
        request = Request(
            f"{self.base_url}/data?{query}",
            method=method,
            headers=headers,
        )
        return urlopen(request, timeout=300)


class Library:
    def __init__(self, root: Path, engine: TorrentEngine | None = None):
        self.root = root.resolve()
        self.engine = engine
        self._lock = threading.Lock()
        self._videos: dict[str, Video] = {}
        self._last_scan = 0.0

    def videos(self) -> list[Video]:
        if time.monotonic() - self._last_scan > 10:
            self.scan()
        with self._lock:
            return sorted(self._videos.values(), key=lambda item: (item.torrent, item.name))

    def get(self, video_id: str) -> Video | None:
        self.videos()
        with self._lock:
            return self._videos.get(video_id)

    def scan(self) -> None:
        grouped_paths: dict[str, tuple[str, int, Path, str, str]] = {}
        for torrent_file in self.root.rglob("*.torrent"):
            try:
                metadata = bdecode(torrent_file.read_bytes())
                info = metadata[b"info"]
                info_hash = hashlib.sha1(bencode(info)).hexdigest()
                torrent_name = text(info.get(b"name.utf-8") or info.get(b"name")) or torrent_file.stem
                if self.engine:
                    self.engine.register(torrent_file)
                if b"files" in info:
                    for entry in info[b"files"]:
                        parts = entry.get(b"path.utf-8") or entry.get(b"path") or []
                        relative = Path(torrent_name, *(text(part) for part in parts))
                        display_path = "/".join(text(part) for part in parts)
                        grouped_paths[relative.as_posix().casefold()] = (
                            torrent_name,
                            int(entry.get(b"length", 0)),
                            relative,
                            info_hash,
                            display_path,
                        )
                else:
                    name = text(info.get(b"name.utf-8") or info.get(b"name"))
                    grouped_paths[Path(name).as_posix().casefold()] = (
                        torrent_name,
                        int(info.get(b"length", 0)),
                        Path(name),
                        info_hash,
                        name,
                    )
            except (OSError, KeyError, TypeError, ValueError, BencodeError):
                continue

        found: dict[str, Video] = {}
        for _, (
            torrent_name,
            declared_size,
            relative,
            info_hash,
            display_path,
        ) in grouped_paths.items():
            if relative.suffix.casefold() not in VIDEO_EXTENSIONS:
                continue
            digest = hashlib.sha256(relative.as_posix().encode("utf-8")).hexdigest()[:24]
            candidate = (self.root / relative).resolve()
            available_path = None
            try:
                candidate.relative_to(self.root)
                if candidate.is_file():
                    available_path = candidate
            except (OSError, ValueError):
                pass
            actual_size = available_path.stat().st_size if available_path else declared_size
            mime = mimetypes.guess_type(relative.name)[0] or "application/octet-stream"
            found[digest] = Video(
                digest,
                relative.name,
                torrent_name,
                available_path,
                actual_size,
                mime,
                info_hash,
                display_path,
            )

        for path in self.root.rglob("*"):
            try:
                if not path.is_file() or path.suffix.casefold() not in VIDEO_EXTENSIONS:
                    continue
                resolved = path.resolve()
                resolved.relative_to(self.root)
                relative = resolved.relative_to(self.root).as_posix()
                torrent = self._match_group(relative, grouped_paths)
                digest = hashlib.sha256(relative.encode("utf-8")).hexdigest()[:24]
                mime = mimetypes.guess_type(path.name)[0] or "application/octet-stream"
                found[digest] = Video(digest, path.name, torrent, resolved, path.stat().st_size, mime)
            except (OSError, ValueError):
                continue
        with self._lock:
            self._videos = found
            self._last_scan = time.monotonic()

    @staticmethod
    def _match_group(
        relative: str,
        groups: dict[str, tuple[str, int, Path, str, str]],
    ) -> str:
        folded = Path(relative).as_posix().casefold()
        if folded in groups:
            return groups[folded][0]
        for torrent_path, (name, _, _, _, _) in groups.items():
            if folded.endswith("/" + torrent_path) or torrent_path.endswith("/" + folded):
                return name
        parts = Path(relative).parts
        return parts[0] if len(parts) > 1 else "未归组视频"


class Server(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(
        self,
        address: tuple[str, int],
        library: Library,
        token: str,
        allow_public: bool,
        engine: TorrentEngine | None = None,
    ):
        super().__init__(address, Handler)
        self.library = library
        self.token = token
        self.allow_public = allow_public
        self.engine = engine


class Handler(BaseHTTPRequestHandler):
    server: Server
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt: str, *args: Any) -> None:
        print(f"{self.client_address[0]} [{self.log_date_time_string()}] {fmt % args}")

    def do_GET(self) -> None:
        if not self._authorized():
            self._json({"error": "unauthorized"}, HTTPStatus.UNAUTHORIZED)
            return
        path = unquote(urlparse(self.path).path)
        if path == "/api/health":
            self._json({
                "ok": True,
                "name": "LAN Torrent Video",
                "torrent_streaming": bool(self.server.engine and self.server.engine.online),
            })
        elif path == "/api/videos":
            engine_online = bool(self.server.engine and self.server.engine.online)
            self._json([item.public(engine_online) for item in self.server.library.videos()])
        elif path.startswith("/stream/"):
            self._stream(path.removeprefix("/stream/"))
        else:
            self._json({"error": "not found"}, HTTPStatus.NOT_FOUND)

    def do_HEAD(self) -> None:
        if not self._authorized():
            self.send_error(HTTPStatus.UNAUTHORIZED)
            return
        path = unquote(urlparse(self.path).path)
        if path.startswith("/stream/"):
            self._stream(path.removeprefix("/stream/"), head_only=True)
        else:
            self.send_error(HTTPStatus.NOT_FOUND)

    def _authorized(self) -> bool:
        try:
            address = ipaddress.ip_address(self.client_address[0])
            if not self.server.allow_public and not (address.is_private or address.is_loopback):
                return False
        except ValueError:
            return False
        expected = self.server.token
        return not expected or self.headers.get("Authorization") == f"Bearer {expected}"

    def _json(self, payload: Any, status: HTTPStatus = HTTPStatus.OK) -> None:
        encoded = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(encoded)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(encoded)

    def _stream(self, video_id: str, head_only: bool = False) -> None:
        video = self.server.library.get(video_id)
        if video is None:
            self.send_error(HTTPStatus.NOT_FOUND)
            return
        if video.path is None:
            self._torrent_stream(video, head_only)
            return
        size = video.size
        start, end = 0, size - 1
        partial = False
        header = self.headers.get("Range")
        if header:
            match = RANGE_RE.fullmatch(header.strip())
            if not match:
                self.send_error(HTTPStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                return
            first, last = match.groups()
            if first:
                start = int(first)
                end = min(int(last), size - 1) if last else size - 1
            elif last:
                length = min(int(last), size)
                start = size - length
            if start >= size or end < start:
                self.send_response(HTTPStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                self.send_header("Content-Range", f"bytes */{size}")
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            partial = True
        length = end - start + 1
        self.send_response(HTTPStatus.PARTIAL_CONTENT if partial else HTTPStatus.OK)
        self.send_header("Content-Type", video.mime)
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(length))
        if partial:
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        self.end_headers()
        if head_only:
            return
        try:
            with video.path.open("rb") as source:
                source.seek(start)
                remaining = length
                while remaining:
                    chunk = source.read(min(1024 * 1024, remaining))
                    if not chunk:
                        break
                    self.wfile.write(chunk)
                    remaining -= len(chunk)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def _torrent_stream(self, video: Video, head_only: bool) -> None:
        engine = self.server.engine
        if (
            engine is None
            or not engine.online
            or video.torrent_hash is None
            or video.torrent_path is None
        ):
            self.send_error(HTTPStatus.SERVICE_UNAVAILABLE, "BitTorrent engine unavailable")
            return
        try:
            upstream = engine.open_stream(
                video,
                "HEAD" if head_only else "GET",
                self.headers.get("Range"),
            )
        except HTTPError as error:
            self.send_response(error.code)
            for name in ("Content-Type", "Content-Range", "Content-Length", "Accept-Ranges"):
                value = error.headers.get(name)
                if value:
                    self.send_header(name, value)
            self.end_headers()
            return
        except (OSError, URLError):
            self.send_error(HTTPStatus.GATEWAY_TIMEOUT, "Waiting for torrent data failed")
            return
        with upstream:
            self.send_response(upstream.status)
            for name in ("Content-Type", "Content-Range", "Content-Length", "Accept-Ranges"):
                value = upstream.headers.get(name)
                if value:
                    self.send_header(name, value)
            self.send_header("X-Content-Source", "bittorrent")
            self.end_headers()
            if head_only:
                return
            try:
                while True:
                    chunk = upstream.read(1024 * 1024)
                    if not chunk:
                        break
                    self.wfile.write(chunk)
            except (BrokenPipeError, ConnectionResetError):
                pass


def advertise(port: int):
    try:
        from zeroconf import ServiceInfo, Zeroconf
        hostname = socket.gethostname()
        addresses = []
        for info in socket.getaddrinfo(hostname, None, socket.AF_INET):
            raw = socket.inet_aton(info[4][0])
            if raw not in addresses:
                addresses.append(raw)
        service = ServiceInfo(
            "_lantorrent._tcp.local.",
            f"LAN Torrent Video._lantorrent._tcp.local.",
            addresses=addresses,
            port=port,
            properties={"version": "1"},
            server=f"{hostname}.local.",
        )
        zeroconf = Zeroconf()
        zeroconf.register_service(service)
        return zeroconf, service
    except Exception as error:
        print(f"自动发现未启用（可手动输入地址）: {error}")
        return None


def main() -> None:
    parser = argparse.ArgumentParser(description="Serve completed torrent video data over your LAN")
    parser.add_argument("root", type=Path, help="Directory containing .torrent files and video data")
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8787)
    parser.add_argument("--token", default=os.environ.get("LAN_TORRENT_TOKEN", ""))
    parser.add_argument("--allow-public", action="store_true", help="Allow non-private client IPs")
    parser.add_argument(
        "--torrent-engine",
        type=Path,
        default=Path(__file__).with_name("lan-torrent-engine.exe"),
        help="Path to the bundled on-demand BitTorrent engine",
    )
    parser.add_argument("--torrent-engine-port", type=int, default=8790)
    args = parser.parse_args()
    if not args.root.is_dir():
        parser.error(f"directory does not exist: {args.root}")
    engine = TorrentEngine(args.torrent_engine.resolve(), args.root.resolve(), args.torrent_engine_port)
    if not engine.start():
        engine = None
    library = Library(args.root, engine)
    library.scan()
    server = Server((args.host, args.port), library, args.token, args.allow_public, engine)
    ad = advertise(args.port)
    print(f"共享目录: {library.root}")
    print(f"发现视频: {len(library.videos())}")
    print(f"服务地址: http://0.0.0.0:{args.port}")
    print("按 Ctrl+C 停止")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
        if engine:
            engine.stop()
        if ad:
            zeroconf, service = ad
            zeroconf.unregister_service(service)
            zeroconf.close()


if __name__ == "__main__":
    main()
