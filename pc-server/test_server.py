import json
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from pathlib import Path

from lan_torrent_server import Library, Server


def bencode(value):
    if isinstance(value, int):
        return b"i" + str(value).encode() + b"e"
    if isinstance(value, bytes):
        return str(len(value)).encode() + b":" + value
    if isinstance(value, list):
        return b"l" + b"".join(bencode(item) for item in value) + b"e"
    if isinstance(value, dict):
        return b"d" + b"".join(
            bencode(key) + bencode(value[key]) for key in sorted(value)
        ) + b"e"
    raise TypeError(type(value))


class ServerTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        root = Path(self.temp.name)
        media = root / "Demo"
        media.mkdir()
        self.payload = bytes(range(256)) * 16
        (media / "clip.mp4").write_bytes(self.payload)
        torrent = {
            b"announce": b"http://tracker.invalid",
            b"info": {
                b"name": b"Demo",
                b"piece length": 16384,
                b"pieces": b"x" * 20,
                b"files": [{b"length": len(self.payload), b"path": [b"clip.mp4"]}],
            },
        }
        (root / "demo.torrent").write_bytes(bencode(torrent))
        library = Library(root)
        library.scan()
        self.server = Server(("127.0.0.1", 0), library, "secret", False)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.base = f"http://127.0.0.1:{self.server.server_port}"

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)
        self.temp.cleanup()

    def request(self, path, headers=None):
        request = urllib.request.Request(
            self.base + path,
            headers={"Authorization": "Bearer secret", **(headers or {})},
        )
        return urllib.request.urlopen(request, timeout=2)

    def test_lists_torrent_video(self):
        with self.request("/api/videos") as response:
            videos = json.load(response)
        self.assertEqual(len(videos), 1)
        self.assertEqual(videos[0]["torrent"], "Demo")
        self.assertEqual(videos[0]["name"], "clip.mp4")

    def test_supports_byte_range(self):
        with self.request("/api/videos") as response:
            video_id = json.load(response)[0]["id"]
        with self.request(f"/stream/{video_id}", {"Range": "bytes=10-29"}) as response:
            self.assertEqual(response.status, 206)
            self.assertEqual(response.headers["Content-Range"], f"bytes 10-29/{len(self.payload)}")
            self.assertEqual(response.read(), self.payload[10:30])

    def test_rejects_bad_token(self):
        request = urllib.request.Request(self.base + "/api/videos")
        with self.assertRaises(urllib.error.HTTPError) as error:
            urllib.request.urlopen(request, timeout=2)
        self.assertEqual(error.exception.code, 401)
        error.exception.close()


if __name__ == "__main__":
    unittest.main()
