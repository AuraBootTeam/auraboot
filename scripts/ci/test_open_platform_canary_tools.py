"""Transport/receiver self-tests; these do not certify deployed business contracts."""

import hashlib
import hmac
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import selectors
import sys
import tempfile
import time
import unittest
from unittest.mock import patch
import urllib.error
import urllib.request

from open_platform_http import Client, ProtocolError, data
from open_platform_receiver import Receiver


class Response(io.BytesIO):
    code = 200
    headers = {"X-Request-Id": "test-request"}


class Opener:
    def __init__(self, response):
        self.response = response
        self.requests = []

    def open(self, request, timeout):
        self.requests.append(request)
        if isinstance(self.response, Exception):
            raise self.response
        return self.response


class TransportTests(unittest.TestCase):
    def test_import_does_not_seed_or_contact_target(self):
        with patch("urllib.request.OpenerDirector.open", side_effect=AssertionError("network at import")):
            spec = importlib.util.spec_from_file_location("probe", Path(__file__).with_name("open-platform-release-probe.py"))
            spec.loader.exec_module(importlib.util.module_from_spec(spec))

    def test_origin_validation(self):
        for base in ("file:///etc/passwd", "https://secret:password@test", "https://test/path", "https://test?token=x"):
            with self.subTest(base=base), self.assertRaises(ValueError):
                Client(base)

    def test_form_and_envelope(self):
        opener = Opener(Response(b'{"code":0,"data":{"pid":"p1"}}'))
        status, body, headers = Client("https://example.test", opener=opener).request(
            "/oauth2/token", "POST", form={"client_secret": "a+b"})
        self.assertEqual((status, data(body), headers["X-Request-Id"]), (200, {"pid": "p1"}, "test-request"))
        self.assertEqual(opener.requests[0].data, b"client_secret=a%2Bb")

    def test_errors_never_echo_secrets(self):
        opener = Opener(urllib.error.HTTPError("https://example.test", 500, "failure", {}, io.BytesIO(b'{"secret":"sensitive"}')))
        with self.assertRaisesRegex(ProtocolError, "unexpected HTTP 500") as error:
            Client("https://example.test", opener=opener).request("/api")
        self.assertNotIn("sensitive", str(error.exception))
        self.assertEqual(len(opener.requests), 1)

    def test_expected_denial_returns_json(self):
        opener = Opener(urllib.error.HTTPError("https://example.test", 403, "denied", {}, io.BytesIO(b'{"code":"scope_denied"}')))
        self.assertEqual(Client("https://example.test", opener=opener).request("/api", expected=(403,))[1]["code"], "scope_denied")

    def test_429_stops_even_when_expected(self):
        opener = Opener(urllib.error.HTTPError("https://example.test", 429, "limited", {}, io.BytesIO(b"{}")))
        client = Client("https://example.test", opener=opener)
        with self.assertRaisesRegex(ProtocolError, "429"):
            client.request("/api", expected=(429,))
        with self.assertRaises(ProtocolError):
            client.request("/api")
        self.assertEqual(len(opener.requests), 1)

    def test_budget_and_path_boundaries(self):
        opener = Opener(Response(b"{}"))
        client = Client("https://example.test", max_requests=1, opener=opener)
        with self.assertRaises(ValueError):
            client.request("//other.test/token")
        client.request("/api")
        with self.assertRaises(ProtocolError):
            client.request("/api")
        self.assertEqual(client.request_count, 1)

    def test_html_is_not_api_success(self):
        with self.assertRaisesRegex(ProtocolError, "not JSON"):
            Client("https://example.test", opener=Opener(Response(b"<html>fallback</html>"))).request("/health")

    def test_redirect_does_not_forward_bearer(self):
        from open_platform_http import NoRedirect
        request = urllib.request.Request("https://example.test", headers={"Authorization": "Bearer private"})
        self.assertIsNone(NoRedirect().redirect_request(request, None, 302, "redirect", {}, "https://other.test"))


class ReceiverTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.path = Path(self.directory.name) / "deliveries.sqlite"
        self.secret = b"self-test-only-secret"
        self.receiver = Receiver(self.path, self.secret, "self-test", clock=lambda: 1000)
        self.raw = b'{ "id": "evt-1", "data": {"value":1} }'

    def tearDown(self):
        self.receiver.close()
        self.directory.cleanup()

    def headers(self, raw=None, timestamp="1000"):
        raw = self.raw if raw is None else raw
        return {"X-Webhook-Timestamp": timestamp, "X-Webhook-Signature": "sha256=" + hmac.new(
            self.secret, timestamp.encode() + b"." + raw, hashlib.sha256).hexdigest(),
            "X-Webhook-Delivery": "delivery-1", "X-Request-Id": "request-1"}

    def test_raw_bytes_and_evidence(self):
        self.assertEqual(self.receiver.receive(self.raw, self.headers())[0], 200)
        record = self.receiver.db.execute("SELECT raw_body, signature_valid, request_id FROM deliveries").fetchone()
        self.assertEqual(record, (self.raw, 1, "request-1"))
        self.assertEqual(self.path.stat().st_mode & 0o077, 0)

    def test_tampered_signature_rejected(self):
        headers = self.headers()
        headers["X-Webhook-Signature"] = "sha256=" + "0" * 64
        self.assertEqual(self.receiver.receive(self.raw, headers)[0], 401)
        self.assertIsNone(self.receiver.db.execute("SELECT raw_body FROM deliveries").fetchone()[0])

    def test_parse_reserialize_does_not_match_signature(self):
        normalized = json.dumps(json.loads(self.raw)).encode()
        self.assertEqual(self.receiver.receive(normalized, self.headers())[0], 401)

    def test_timestamp_window_nan_and_infinity(self):
        for timestamp in ("699", "1301", "NaN", "inf", "bad"):
            with self.subTest(timestamp=timestamp):
                self.assertEqual(self.receiver.receive(self.raw, self.headers(timestamp=timestamp))[0], 401)
        for timestamp in ("700", "1300"):
            self.assertEqual(self.receiver.receive(self.raw, self.headers(timestamp=timestamp))[0], 200)

    def test_duplicate_survives_restart(self):
        self.receiver.receive(self.raw, self.headers())
        self.receiver.close()
        self.receiver = Receiver(self.path, self.secret, "self-test", clock=lambda: 1000)
        status, body = self.receiver.receive(self.raw, self.headers())
        self.assertEqual((status, body["duplicate"]), (200, True))
        self.assertEqual(self.receiver.db.execute("SELECT COUNT(*) FROM events").fetchone()[0], 1)

    def test_same_event_different_body_is_conflict(self):
        self.receiver.receive(self.raw, self.headers())
        other = self.raw.replace(b'"value":1', b'"value":2')
        self.assertEqual(self.receiver.receive(other, self.headers(other))[0], 409)

    def test_fail_first_then_accept_then_deduplicate(self):
        self.receiver.close()
        self.receiver = Receiver(Path(self.directory.name) / "retry.sqlite", self.secret, "retry",
                                 mode="fail-first", failures=2, clock=lambda: 1000)
        results = [self.receiver.receive(self.raw, self.headers()) for _ in range(4)]
        self.assertEqual([r[0] for r in results], [503, 503, 200, 200])
        self.assertTrue(results[-1][1]["duplicate"])

    def test_fail_all_never_accepts(self):
        self.receiver.close()
        self.receiver = Receiver(Path(self.directory.name) / "dlq.sqlite", self.secret, "dlq",
                                 mode="fail-all", clock=lambda: 1000)
        self.assertEqual([self.receiver.receive(self.raw, self.headers())[0] for _ in range(3)], [503] * 3)
        self.assertEqual(self.receiver.db.execute("SELECT accepted FROM events").fetchone()[0], 0)

    def test_run_configuration_cannot_change(self):
        with self.assertRaises(ValueError):
            Receiver(self.path, self.secret, "another-run")

    def test_signed_invalid_json_rejected(self):
        self.assertEqual(self.receiver.receive(b"[]", self.headers(b"[]"))[0], 400)

    def test_loopback_server_delivers_signed_raw_bytes(self):
        secret_file = Path(self.directory.name) / "secret"
        with open(secret_file, "xb") as handle:
            handle.write(self.secret)
        secret_file.chmod(0o600)
        store = Path(self.directory.name) / "http.sqlite"
        process = subprocess.Popen([sys.executable, str(Path(__file__).with_name("open_platform_receiver.py")),
                                    "--secret-file", str(secret_file), "--store", str(store),
                                    "--run-id", "http-test", "--port", "0"],
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        try:
            with selectors.DefaultSelector() as selector:
                selector.register(process.stdout, selectors.EVENT_READ)
                self.assertTrue(selector.select(5), "receiver did not become ready")
            ready = json.loads(process.stdout.readline())
            self.assertTrue(ready["ready"])
            headers = self.headers(timestamp=str(int(time.time())))
            request = urllib.request.Request(f"http://127.0.0.1:{ready['port']}/webhooks/http-test",
                                             data=self.raw, headers=headers, method="POST")
            with urllib.request.urlopen(request, timeout=3) as response:
                body = json.load(response)
                self.assertEqual(response.status, 200)
                self.assertTrue(body["accepted"])
                self.assertEqual(body["runId"], "http-test")
            import sqlite3
            with sqlite3.connect(store) as db:
                self.assertEqual(db.execute("SELECT raw_body, response_status FROM deliveries").fetchone(),
                                 (self.raw, 200))
        finally:
            process.terminate()
            process.communicate(timeout=5)


if __name__ == "__main__":
    if "--mutation-accept-invalid-signature" in sys.argv:
        sys.argv.remove("--mutation-accept-invalid-signature")
        with patch("open_platform_receiver.hmac.compare_digest", return_value=True):
            unittest.main()
    else:
        unittest.main()
