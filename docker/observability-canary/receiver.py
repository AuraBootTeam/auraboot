import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


EVENTS = Path("/data/events.jsonl")


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/health":
            self._reply(200, {"status": "ok"})
            return
        if self.path == "/events":
            events = []
            if EVENTS.exists():
                events = [json.loads(line) for line in EVENTS.read_text().splitlines() if line]
            self._reply(200, {"events": events})
            return
        self._reply(404, {"error": "not found"})

    def do_POST(self):
        if self.path != "/alerts":
            self._reply(404, {"error": "not found"})
            return
        length = int(self.headers.get("Content-Length", "0"))
        payload = json.loads(self.rfile.read(length) or b"{}")
        EVENTS.parent.mkdir(parents=True, exist_ok=True)
        with EVENTS.open("a", encoding="utf-8") as stream:
            stream.write(json.dumps(payload, separators=(",", ":")) + "\n")
        self._reply(200, {"status": "accepted"})

    def log_message(self, _format, *_args):
        return

    def _reply(self, status, payload):
        body = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


ThreadingHTTPServer(("0.0.0.0", 8080), Handler).serve_forever()
