#!/usr/bin/env python3
"""Small LAN-only video library server with HTTP byte-range support."""

import argparse
import json
import mimetypes
import os
import re
import socket
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import quote, unquote, urlparse

VIDEO_EXTENSIONS = {".mp4", ".mkv", ".avi", ".mov", ".webm", ".m4v", ".ts", ".flv"}


def local_ips():
    found = {"127.0.0.1"}
    try:
        for item in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            found.add(item[4][0])
    except OSError:
        pass
    return sorted(found, key=lambda ip: ip.startswith("127."))


class VideoHandler(BaseHTTPRequestHandler):
    server_version = "LanVideo/1.0"

    def do_GET(self):
        parsed = urlparse(self.path)
        if parsed.path == "/api/videos":
            self.send_catalog()
        elif parsed.path.startswith("/video/"):
            self.send_video(unquote(parsed.path[len("/video/"):]))
        elif parsed.path == "/":
            self.send_text("局域网视频服务已启动。请在安卓 App 中填写本机地址。")
        else:
            self.send_error(404)

    def send_catalog(self):
        root = self.server.video_root
        videos = []
        for file in root.rglob("*"):
            if file.is_file() and file.suffix.lower() in VIDEO_EXTENSIONS:
                try:
                    relative = file.relative_to(root).as_posix()
                    stat = file.stat()
                    videos.append({
                        "name": file.name,
                        "path": relative,
                        "url": "/video/" + quote(relative, safe="/"),
                        "size": stat.st_size,
                        "modified": int(stat.st_mtime),
                    })
                except OSError:
                    continue
        videos.sort(key=lambda item: item["path"].lower())
        payload = json.dumps(videos, ensure_ascii=False).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def send_video(self, relative):
        root = self.server.video_root.resolve()
        target = (root / relative).resolve()
        try:
            target.relative_to(root)
        except ValueError:
            self.send_error(403)
            return
        if not target.is_file() or target.suffix.lower() not in VIDEO_EXTENSIONS:
            self.send_error(404)
            return

        size = target.stat().st_size
        start, end = 0, size - 1
        range_header = self.headers.get("Range")
        if range_header:
            match = re.fullmatch(r"bytes=(\d*)-(\d*)", range_header.strip())
            if not match:
                self.send_error(416)
                return
            if match.group(1):
                start = int(match.group(1))
                end = min(int(match.group(2)), size - 1) if match.group(2) else size - 1
            elif match.group(2):
                length = min(int(match.group(2)), size)
                start = size - length
            if start > end or start >= size:
                self.send_response(416)
                self.send_header("Content-Range", f"bytes */{size}")
                self.end_headers()
                return

        length = end - start + 1
        self.send_response(206 if range_header else 200)
        self.send_header("Content-Type", mimetypes.guess_type(target.name)[0] or "application/octet-stream")
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(length))
        if range_header:
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        self.end_headers()
        try:
            with target.open("rb") as stream:
                stream.seek(start)
                remaining = length
                while remaining:
                    chunk = stream.read(min(1024 * 1024, remaining))
                    if not chunk:
                        break
                    self.wfile.write(chunk)
                    remaining -= len(chunk)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def send_text(self, text):
        payload = text.encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, fmt, *args):
        print(f"[{self.client_address[0]}] {fmt % args}")


def main():
    parser = argparse.ArgumentParser(description="Share a video folder with the LanVideo Android app")
    parser.add_argument(
        "folder",
        type=Path,
        nargs="?",
        default=Path(r"D:\yingshiapp"),
        help=r"video folder to share (default: D:\yingshiapp)",
    )
    parser.add_argument("--port", type=int, default=8787)
    args = parser.parse_args()
    folder = args.folder.expanduser().resolve()
    if not folder.is_dir():
        parser.error(f"folder does not exist: {folder}")

    server = ThreadingHTTPServer(("0.0.0.0", args.port), VideoHandler)
    server.video_root = folder
    print(f"共享文件夹：{folder}")
    print("安卓 App 地址：")
    for ip in local_ips():
        if not ip.startswith("127."):
            print(f"  http://{ip}:{args.port}")
    print("按 Ctrl+C 停止。此服务仅应在可信任的家庭局域网中使用。")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n服务已停止。")
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
