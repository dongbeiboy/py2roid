"""
py2roid LogConnect — 远程日志接收服务器

用法:
  python tools\LogConnect\server.py
  → 浏览器打开 http://localhost:8765

App 端连入:
  adb shell am start -n com.xz.py2roid/.MainActivity \
    --es log_web http://192.168.1.100:8765
"""

import os
import json
import time
import urllib.parse
from http.server import HTTPServer, BaseHTTPRequestHandler
from datetime import datetime

STATIC_DIR = os.path.join(os.path.dirname(__file__), "static")
log_history = []
buffer = []

MIME_MAP = {
    ".html": "text/html; charset=utf-8",
    ".css":  "text/css; charset=utf-8",
    ".js":   "application/javascript; charset=utf-8",
    ".png":  "image/png",
    ".ico":  "image/x-icon",
}


class Handler(BaseHTTPRequestHandler):

    def do_GET(self):
        path = urllib.parse.urlparse(self.path).path

        # --- API ---
        if path == "/poll":
            self.send_response(200)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            global buffer
            lines = "\n".join(buffer)
            buffer = []
            self.wfile.write(lines.encode("utf-8"))
            return

        if path == "/log":
            self.send_response(200)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write("\n".join(log_history[-300:]).encode("utf-8"))
            return

        if path.startswith("/register"):
            qs = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
            source = qs.get("source", ["unknown"])[0]
            ip = self.client_address[0]
            now = datetime.now().strftime("%H:%M:%S")
            line = f"[{now}] ← {source} connected from {ip}"
            log_history.append(line)
            buffer.append(line)
            self.send_response(200)
            self.end_headers()
            return

        # --- Static files ---
        if path == "/" or path == "":
            path = "/index.html"

        file_path = os.path.normpath(os.path.join(STATIC_DIR, path.lstrip("/")))
        if not file_path.startswith(os.path.normpath(STATIC_DIR)):
            self.send_response(403)
            self.end_headers()
            return

        if os.path.isfile(file_path):
            ext = os.path.splitext(file_path)[1].lower()
            ctype = MIME_MAP.get(ext, "application/octet-stream")
            self.send_response(200)
            self.send_header("Content-Type", ctype)
            self.send_header("Cache-Control", "no-cache")
            self.end_headers()
            with open(file_path, "rb") as f:
                self.wfile.write(f.read())
        else:
            self.send_response(404)
            self.end_headers()

    def do_POST(self):
        if self.path.startswith("/log"):
            length = int(self.headers.get("Content-Length", 0))
            body = self.rfile.read(length).decode("utf-8")
            params = urllib.parse.parse_qs(body)
            raw_lines = params.get("lines", [""])[0]
            if raw_lines:
                now = datetime.now().strftime("%H:%M:%S")
                for l in raw_lines.split("\n"):
                    if l.strip():
                        tagged = f"[{now}] {l.strip()}"
                        log_history.append(tagged)
                        buffer.append(tagged)
                if len(log_history) > 5000:
                    log_history[:1000] = []
            self.send_response(200)
            self.end_headers()
        else:
            self.send_response(404)
            self.end_headers()

    def log_message(self, fmt, *args):
        pass


if __name__ == "__main__":
    port = 8765
    print(f"╔══════════════════════════════════════╗")
    print(f"║  py2roid LogConnect                  ║")
    print(f"║  浏览器: http://localhost:{port}       ║")
    print(f"║                                      ║")
    print(f"║  ADB 连入:                           ║")
    print(f"║    adb shell am start                ║")
    print(f"║      -n com.xz.py2roid/.MainActivity  ║")
    print(f"║      --es log_web http://IP:{port}    ║")
    print(f"║                                      ║")
    print(f"║  按 Ctrl+C 停止                      ║")
    print(f"╚══════════════════════════════════════╝")
    server = HTTPServer(("0.0.0.0", port), Handler)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n停止")
