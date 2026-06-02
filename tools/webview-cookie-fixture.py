import json
import io
import sys
import zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


PORT = int(sys.argv[1])
LOG_PATH = Path(sys.argv[2])


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        cookie = self.headers.get("Cookie", "")
        LOG_PATH.parent.mkdir(parents=True, exist_ok=True)
        with LOG_PATH.open("a", encoding="utf-8") as output:
            output.write(json.dumps({"path": self.path, "cookie": cookie}) + "\n")

        if self.path.startswith("/login-plugin.zip"):
            self.send_plugin("000 COOKIE LOGIN FIXTURE", "/login")
            return

        if self.path.startswith("/private-plugin.zip"):
            self.send_plugin("001 COOKIE PRIVATE FIXTURE", "/private")
            return

        if self.path.startswith("/login"):
            self.send_response(200)
            self.send_header("Set-Cookie", "sid=browser-session; Path=/; SameSite=Lax")
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.end_headers()
            self.wfile.write(b"<html><head><title>Cookie login fixture</title></head><body>LOGIN COOKIE SET <a href='/private'>Private</a></body></html>")
            return

        if self.path.startswith("/private"):
            authorized = "sid=browser-session" in cookie
            self.send_response(200 if authorized else 401)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.end_headers()
            body = "PRIVATE COOKIE OK" if authorized else "COOKIE MISSING"
            self.wfile.write(f"<html><head><title>{body}</title></head><body>{body}</body></html>".encode("utf-8"))
            return

        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.end_headers()
        self.wfile.write(b"<html><head><title>Cookie fixture</title></head><body>COOKIE FIXTURE</body></html>")

    def send_plugin(self, name, source_path):
        source = f"http://127.0.0.1:{PORT}{source_path}"
        plugin = {
            "metadata": {
                "name": name,
                "author": "Codex",
                "version": 1,
                "source": source,
                "description": "Embedded browser cookie smoke fixture",
            },
            "script": {"home": "src/home.js"},
        }
        output = io.BytesIO()
        with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("plugin.json", json.dumps(plugin))
            archive.writestr("src/home.js", "function execute(){ return Response.success([]); }")
        body = output.getvalue()
        self.send_response(200)
        self.send_header("Content-Type", "application/zip")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *_):
        pass


ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
