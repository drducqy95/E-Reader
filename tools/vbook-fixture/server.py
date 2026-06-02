from __future__ import annotations

import io
import json
import sys
import zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HOST = "127.0.0.1"
PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 18080
BASE_URL = f"http://{HOST}:{PORT}"


def plugin_zip() -> bytes:
    plugin = {
        "metadata": {
            "name": "VBOOK_FIXTURE",
            "author": "Codex",
            "version": 1,
            "source": BASE_URL,
            "description": "Deterministic VBook emulator fixture",
        },
        "script": {
            "home": "home.js",
            "gen": "gen.js",
            "search": "search.js",
            "detail": "detail.js",
            "toc": "toc.js",
            "chap": "chap.js",
        },
    }
    scripts = {
        "src/home.js": f'function execute(){{return Response.success([{{title:"Fixture",input:"{BASE_URL}/search",script:"gen.js"}}]);}}',
        "src/gen.js": "function execute(input,page){return Response.success(fetch(input).json());}",
        "src/search.js": f'function execute(key,page){{return Response.success(fetch("{BASE_URL}/search?keyword="+encodeURIComponent(key)).json());}}',
        "src/detail.js": "function execute(url){return Response.success(fetch(url).json());}",
        "src/toc.js": 'function execute(url){return Response.success(fetch(url+"/toc").json());}',
        "src/chap.js": "function execute(url){return Response.success(fetch(url).text());}",
    }
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("plugin.json", json.dumps(plugin, ensure_ascii=False))
        for path, source in scripts.items():
            archive.writestr(path, source)
    return output.getvalue()


def legado_source() -> dict[str, object]:
    return {
        "bookSourceName": "LEGADO_FIXTURE",
        "bookSourceUrl": BASE_URL,
        "searchUrl": f"{BASE_URL}/legado/search?key={{{{key}}}}",
        "ruleSearch": {
            "bookList": "div.book",
            "name": "a@text",
            "author": ".author@text",
            "bookUrl": "a@href",
        },
        "ruleBookInfo": {
            "name": "h1@text",
            "author": ".author@text",
            "intro": ".intro@text",
            "tocUrl": "a.toc@href",
        },
        "ruleToc": {
            "chapterList": "li.chapter",
            "chapterName": "a@text",
            "chapterUrl": "a@href",
        },
        "ruleContent": {"content": "div.content@html"},
    }


class FixtureHandler(BaseHTTPRequestHandler):
    def do_GET(self) -> None:
        if self.path == "/plugin.zip":
            self.reply(plugin_zip(), "application/zip")
        elif self.path == "/legado-source.json":
            self.json(legado_source())
        elif self.path.startswith("/legado/search"):
            self.reply(
                f'<div class="book"><a href="{BASE_URL}/legado/book/1">Legado Fixture</a>'
                '<span class="author">Fixture Author</span></div>'.encode(),
                "text/html; charset=utf-8",
            )
        elif self.path == "/legado/book/1":
            self.reply(
                f'<h1>Legado Fixture</h1><span class="author">Fixture Author</span>'
                f'<div class="intro">Online fixture</div><a class="toc" href="{BASE_URL}/legado/book/1/toc">TOC</a>'.encode(),
                "text/html; charset=utf-8",
            )
        elif self.path == "/legado/book/1/toc":
            self.reply(
                f'<li class="chapter"><a href="{BASE_URL}/legado/chapter/1">Chapter 1</a></li>'
                f'<li class="chapter"><a href="{BASE_URL}/legado/chapter/2">Chapter 2</a></li>'.encode(),
                "text/html; charset=utf-8",
            )
        elif self.path == "/legado/chapter/1":
            self.reply(b'<div class="content">Online chapter one.<br>Readable now.</div>', "text/html; charset=utf-8")
        elif self.path == "/legado/chapter/2":
            self.reply(b'<div class="content">Offline chapter two.<br>Downloaded now.</div>', "text/html; charset=utf-8")
        elif self.path.startswith("/search"):
            self.json([{"name": "Tam Quốc Fixture", "author": "La Quán Trung", "link": f"{BASE_URL}/book/1"}])
        elif self.path == "/book/1":
            self.json({"name": "Tam Quốc Fixture", "author": "La Quán Trung", "description": "Fixture online", "ongoing": False})
        elif self.path == "/book/1/toc":
            self.json([
                {"name": "Chương 1", "url": f"{BASE_URL}/chapter/1"},
                {"name": "Chương 2", "url": f"{BASE_URL}/chapter/2"},
            ])
        elif self.path == "/chapter/1":
            self.reply("我是第一章。<br>Đọc online hoạt động.".encode(), "text/html; charset=utf-8")
        elif self.path == "/chapter/2":
            self.reply("第二章。<br>Tải offline hoạt động.".encode(), "text/html; charset=utf-8")
        else:
            self.send_error(404)

    def do_POST(self) -> None:
        if self.path == "/v1/chat/completions":
            length = int(self.headers.get("Content-Length", "0"))
            if length:
                self.rfile.read(length)
            self.json({"choices": [{"message": {"content": "Bản dịch hybrid từ provider fixture."}}]})
        else:
            self.send_error(404)

    def json(self, value: object) -> None:
        self.reply(json.dumps(value, ensure_ascii=False).encode(), "application/json; charset=utf-8")

    def reply(self, body: bytes, content_type: str) -> None:
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt: str, *args: object) -> None:
        print(fmt % args, flush=True)


if __name__ == "__main__":
    print(f"VBook fixture listening at {BASE_URL}", flush=True)
    ThreadingHTTPServer((HOST, PORT), FixtureHandler).serve_forever()
