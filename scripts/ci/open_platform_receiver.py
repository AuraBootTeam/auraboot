#!/usr/bin/env python3
"""Independent, run-scoped webhook receiver; put TLS in front of loopback HTTP.

This receiver supplies raw delivery evidence, not an Open Platform PASS receipt.
"""

import argparse
import hashlib
import hmac
import json
import math
import os
from pathlib import Path
import re
import sqlite3
import time
from http.server import BaseHTTPRequestHandler, HTTPServer


MAX_BODY = 1024 * 1024


class Receiver:
    def __init__(self, store, secret, run_id, *, mode="accept", failures=1, clock=time.time):
        if not re.fullmatch(r"[a-zA-Z0-9_-]{1,80}", run_id):
            raise ValueError("invalid run ID")
        if not secret or mode not in {"accept", "fail-first", "fail-all"} or failures < 1:
            raise ValueError("invalid receiver configuration")
        self.secret, self.run_id, self.mode = secret, run_id, mode
        self.failures, self.clock = failures, clock
        path = Path(store)
        path.parent.mkdir(parents=True, exist_ok=True)
        fd = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600) if not path.exists() else None
        if fd is not None:
            os.close(fd)
        if path.is_symlink() or path.stat().st_mode & 0o077:
            raise ValueError("receiver store must be private and not a symlink")
        self.db = sqlite3.connect(path)
        if fd is None:
            columns = {row[1] for row in self.db.execute('PRAGMA table_info(deliveries)')}
            if not {'timestamp_text', 'signature'}.issubset(columns):
                self.db.close()
                raise ValueError('legacy receiver store lacks raw signature evidence; retain it and use a fresh run store')
        self.db.executescript("""
            CREATE TABLE IF NOT EXISTS config (identity TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS events (id TEXT PRIMARY KEY, body_sha TEXT NOT NULL,
                attempts INTEGER NOT NULL, accepted INTEGER NOT NULL);
            CREATE TABLE IF NOT EXISTS deliveries (sequence INTEGER PRIMARY KEY,
                received_at REAL NOT NULL, delivery_id TEXT, event_id TEXT, body_sha TEXT NOT NULL,
                raw_body BLOB, signature_valid INTEGER NOT NULL, response_status INTEGER NOT NULL,
                duplicate INTEGER NOT NULL, request_id TEXT,
                timestamp_text TEXT, signature TEXT);
        """)
        identity = json.dumps({"runId": run_id, "mode": mode, "failures": failures,
                               "secretSha256": hashlib.sha256(secret).hexdigest()}, sort_keys=True)
        stored = self.db.execute("SELECT identity FROM config").fetchone()
        if stored and stored[0] != identity:
            self.db.close()
            raise ValueError("store belongs to a different run/configuration")
        if not stored:
            self.db.execute("INSERT INTO config VALUES (?)", (identity,))
            self.db.commit()

    def receive(self, raw, headers):
        now = self.clock()
        headers = {key.lower(): value for key, value in headers.items()}
        timestamp = headers.get("x-webhook-timestamp", "")
        signature = headers.get("x-webhook-signature", "")
        try:
            seconds = float(timestamp)
            fresh = math.isfinite(seconds) and abs(now - seconds) <= 300
        except ValueError:
            fresh = False
        expected = "sha256=" + hmac.new(self.secret, timestamp.encode() + b"." + raw,
                                        hashlib.sha256).hexdigest()
        valid = (fresh and len(raw) <= MAX_BODY and signature.isascii()
                 and hmac.compare_digest(signature, expected))
        body_sha = hashlib.sha256(raw).hexdigest()
        event_id, duplicate, status = None, False, 401
        if valid:
            try:
                body = json.loads(raw)
                event_id = body["id"]
                if not isinstance(event_id, str) or not event_id or len(event_id) > 160:
                    raise ValueError("invalid event ID")
            except (ValueError, TypeError, KeyError, UnicodeError):
                valid, event_id, status = False, None, 400
        with self.db:
            if valid:
                row = self.db.execute("SELECT body_sha, attempts, accepted FROM events WHERE id=?",
                                      (event_id,)).fetchone()
                if row and row[0] != body_sha:
                    status = 409
                else:
                    attempts = (row[1] if row else 0) + 1
                    duplicate = bool(row and row[2])
                    should_fail = self.mode == "fail-all" or (self.mode == "fail-first" and attempts <= self.failures)
                    status = 200 if duplicate or not should_fail else 503
                    self.db.execute("""INSERT INTO events VALUES (?, ?, ?, ?)
                        ON CONFLICT(id) DO UPDATE SET attempts=excluded.attempts,
                        accepted=MAX(events.accepted, excluded.accepted)""",
                        (event_id, body_sha, attempts, int(status == 200)))
            self.db.execute("""INSERT INTO deliveries(received_at, delivery_id, event_id, body_sha,
                raw_body, signature_valid, response_status, duplicate, request_id,
                timestamp_text, signature)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                (now, headers.get("x-webhook-delivery"), event_id, body_sha,
                 raw if valid else None, int(valid), status, int(duplicate), headers.get("x-request-id"),
                 timestamp if valid else None, signature if valid else None))
        return status, {"runId": self.run_id, "accepted": status == 200, "duplicate": duplicate}

    def close(self):
        self.db.close()


def handler(receiver):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def do_POST(self):
            if self.path != "/webhooks/" + receiver.run_id:
                self.send_error(404)
                return
            try:
                length = int(self.headers.get("Content-Length", "-1"))
            except ValueError:
                length = -1
            if self.headers.get("Transfer-Encoding") or not 0 <= length <= MAX_BODY:
                self.send_error(413)
                return
            if any(len(self.headers.get_all(key, [])) != 1 for key in
                   ("X-Webhook-Timestamp", "X-Webhook-Signature")):
                self.send_error(400)
                return
            self.connection.settimeout(10)
            raw = self.rfile.read(length)
            if len(raw) != length:
                self.send_error(400)
                return
            status, payload = receiver.receive(raw, dict(self.headers))
            encoded = json.dumps(payload).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(encoded)))
            self.end_headers()
            self.wfile.write(encoded)
    return Handler


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--secret-file", required=True)
    parser.add_argument("--store", required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--port", type=int, default=18765)
    parser.add_argument("--mode", choices=("accept", "fail-first", "fail-all"), default="accept")
    parser.add_argument("--failures", type=int, default=1)
    args = parser.parse_args()
    os.umask(0o077)
    secret_path = Path(args.secret_file)
    if secret_path.is_symlink() or secret_path.stat().st_mode & 0o077:
        parser.error("secret file must be private and not a symlink")
    secret = secret_path.read_bytes().rstrip(b"\r\n")
    receiver = Receiver(args.store, secret, args.run_id, mode=args.mode, failures=args.failures)
    server = HTTPServer(("127.0.0.1", args.port), handler(receiver))
    print(json.dumps({"ready": True, "host": "127.0.0.1", "port": server.server_port}), flush=True)
    try:
        server.serve_forever()
    finally:
        server.server_close()
        receiver.close()


if __name__ == "__main__":
    main()
