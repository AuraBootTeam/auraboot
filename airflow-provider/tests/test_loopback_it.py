"""Real-loopback integration coverage for the AuraBoot provider.

Every case drives the production hook / sensor / operator over a real
TCP socket against a live local HTTP server — no ``responses`` mocks, no
patched requests. The server records exactly what crossed the wire
(method, path, headers, raw body bytes) and answers with AuraBoot-shaped
JSON, so authentication headers, HMAC signatures and idempotency keys are
verified against real bytes.
"""

from __future__ import annotations

import hashlib
import hmac
import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest

from airflow_provider_auraboot.hooks.auraboot import AuraBootHook
from airflow_provider_auraboot.operators.command import AuraBootCommandOperator
from airflow_provider_auraboot.sensors.command_status import AuraBootCommandStatusSensor
from airflow_provider_auraboot.webhooks.sign import sign_webhook

HMAC_SECRET = "it-shared-secret"


class AuraBootStubServer:
    """Minimal AuraBoot-shaped HTTP server recording every wire request."""

    def __init__(self) -> None:
        self.requests: list[dict] = []
        self.routes: dict[tuple[str, str], tuple[int, dict]] = {}
        started = threading.Event()

        outer = self

        class Handler(BaseHTTPRequestHandler):
            def _respond(self) -> None:
                length = int(self.headers.get("Content-Length") or 0)
                body = self.rfile.read(length) if length else b""
                outer.requests.append({
                    "method": self.command,
                    "path": self.path,
                    # HTTP headers are case-insensitive; requests canonicalizes
                    # capitalization on the wire, so record lowercase keys.
                    "headers": {k.lower(): v for k, v in self.headers.items()},
                    "body": body,
                })
                key = (self.command, self.path.split("?")[0])
                if key in outer.routes:
                    status, payload = outer.routes[key]
                    self.send_response(status)
                    self.send_header("Content-Type", "application/json")
                    self.end_headers()
                    self.wfile.write(json.dumps(payload).encode())
                    return
                self.send_response(404)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(b'{"error": "not found"}')

            do_GET = do_POST = do_PUT = do_DELETE = _respond

            def log_message(self, *args) -> None:  # silence test output
                pass

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.port = self.server.server_address[1]
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        started.set()

    def route(self, method: str, path: str, status: int, payload: dict) -> None:
        self.routes[(method, path)] = (status, payload)

    def stop(self) -> None:
        self.server.shutdown()
        self.server.server_close()

    @property
    def base_url(self) -> str:
        return f"http://127.0.0.1:{self.port}"


@pytest.fixture()
def server():
    stub = AuraBootStubServer()
    yield stub
    stub.stop()


@pytest.fixture()
def hook(server, monkeypatch) -> AuraBootHook:
    connection = {
        "conn_type": "auraboot",
        "host": server.base_url,
        "extra": {"auth_method": "jwt", "jwt_token": "it-jwt-token"},
    }
    monkeypatch.setenv("AIRFLOW_CONN_AURABOOT_DEFAULT", json.dumps(connection))
    return AuraBootHook("auraboot_default")


class TestHookOverRealSocket:
    def test_jwt_request_carries_bearer_header_and_parses_json(self, server, hook):
        server.route("POST", "/api/commands/run", 200, {"commandRunPid": "cr-1", "status": "RUNNING"})
        result = hook.run("POST", "/api/commands/run",
                          {"code": "sales.refresh", "params": {"day": "2026-10-04"}},
                          idempotency_key="it-key-1")
        assert result == {"commandRunPid": "cr-1", "status": "RUNNING"}
        wire = server.requests[-1]
        assert wire["method"] == "POST"
        assert wire["path"] == "/api/commands/run"
        assert wire["headers"]["authorization"] == "Bearer it-jwt-token"
        assert wire["headers"]["x-idempotency-key"] == "it-key-1"
        assert wire["headers"]["content-type"] == "application/json"
        assert json.loads(wire["body"])["code"] == "sales.refresh"

    def test_api_key_auth_uses_the_dedicated_header(self, server, hook, monkeypatch):
        connection = {
            "conn_type": "auraboot",
            "host": server.base_url,
            "extra": {"auth_method": "api_key", "api_key": "it-api-key"},
        }
        monkeypatch.setenv("AIRFLOW_CONN_AURABOOT_DEFAULT", json.dumps(connection))
        hook = AuraBootHook("auraboot_default")
        server.route("GET", "/api/things", 200, {"items": []})
        hook.run("GET", "/api/things")
        assert server.requests[-1]["headers"]["x-auraboot-api-key"] == "it-api-key"

    def test_hmac_signature_verifies_over_the_exact_wire_bytes(self, server, monkeypatch):
        connection = {
            "conn_type": "auraboot",
            "host": server.base_url,
            "extra": {"auth_method": "hmac", "hmac_secret": HMAC_SECRET},
        }
        monkeypatch.setenv("AIRFLOW_CONN_AURABOOT_DEFAULT", json.dumps(connection))
        hook = AuraBootHook("auraboot_default")
        server.route("POST", "/api/webhooks/deliver", 200, {"ok": True})
        unicode_body = {"text": "Unicode 用途 ✓"}
        hook.run("POST", "/api/webhooks/deliver", unicode_body)

        wire = server.requests[-1]
        signature = wire["headers"]["x-auraboot-signature"]
        timestamp, _, digest = signature.partition(",v1=")
        assert timestamp.startswith("t=")
        expected = hmac.new(HMAC_SECRET.encode(),
                            f"{timestamp[2:]}.".encode() + wire["body"], hashlib.sha256).hexdigest()
        assert digest == expected, "signature must match the exact bytes that crossed the wire"
        assert json.loads(wire["body"]) == unicode_body

    def test_health_true_on_up_and_false_on_outage(self, server, hook):
        server.route("GET", "/actuator/health", 200, {"status": "UP"})
        assert hook.health() is True
        server.route("GET", "/actuator/health", 503, {"status": "DOWN"})
        assert hook.health() is False

    def test_http_error_status_raises(self, server, hook):
        server.route("POST", "/api/commands/run", 500, {"error": "boom"})
        with pytest.raises(Exception) as raised:
            hook.run("POST", "/api/commands/run", {"code": "x"})
        assert "500" in str(raised.value)


class TestSensorAndOperatorOverRealSocket:
    def test_sensor_poke_follows_real_status_transitions(self, server, monkeypatch):
        connection = {
            "conn_type": "auraboot",
            "host": server.base_url,
            "extra": {"auth_method": "jwt", "jwt_token": "it-jwt-token"},
        }
        monkeypatch.setenv("AIRFLOW_CONN_AURABOOT_DEFAULT", json.dumps(connection))
        sensor = AuraBootCommandStatusSensor(task_id="sensor-it", command_run_pid="cr-42")
        server.route("GET", "/api/command-runs/cr-42", 200, {"status": "RUNNING"})
        assert sensor.poke({}) is False
        server.route("GET", "/api/command-runs/cr-42", 200, {"status": "SUCCESS"})
        assert sensor.poke({}) is True
        assert server.requests[-1]["path"] == "/api/command-runs/cr-42"

    def test_operator_execute_posts_the_command_and_returns_the_result(self, server, monkeypatch):
        connection = {
            "conn_type": "auraboot",
            "host": server.base_url,
            "extra": {"auth_method": "jwt", "jwt_token": "it-jwt-token"},
        }
        monkeypatch.setenv("AIRFLOW_CONN_AURABOOT_DEFAULT", json.dumps(connection))
        operator = AuraBootCommandOperator(task_id="operator-it", command_code="sales.refresh_pipeline_mart",
                                           params={"day": "2026-10-04"})
        # a real TaskInstance renders template_fields before execute; params arrives
        # as Airflow's ParamsDict until then, which is not JSON-serializable
        operator.render_template_fields({"ts": "2026-10-04T00:00:00+00:00"})
        server.route("POST", "/api/commands/run", 200,
                     {"commandRunPid": "cr-7", "status": "RUNNING", "result": {"rows": 3}})
        result = operator.execute({})
        assert result == {"commandRunPid": "cr-7", "status": "RUNNING", "result": {"rows": 3}}
        wire = server.requests[-1]
        assert json.loads(wire["body"]) == {"code": "sales.refresh_pipeline_mart",
                                            "params": {"day": "2026-10-04"}}


class TestProviderRegistry:
    def test_get_provider_info_is_the_real_registry_entry(self):
        import airflow_provider_auraboot

        info = airflow_provider_auraboot.get_provider_info()
        assert info["package-name"] == "airflow-provider-auraboot"
        assert info["connection-types"][0]["hook-class-name"].endswith("AuraBootHook")
        assert "0.1.0" in info["versions"]


class TestWebhookSignatureRoundTrip:
    def test_sign_and_verify_over_real_bytes(self):
        body = json.dumps({"event": "quote.created", "amount": 3000},
                          ensure_ascii=False).encode()
        signature = sign_webhook(body=body, secret=HMAC_SECRET, timestamp=1759560000)
        timestamp, _, digest = signature.partition(",v1=")
        expected = hmac.new(HMAC_SECRET.encode(),
                            f"{timestamp[2:]}.".encode() + body, hashlib.sha256).hexdigest()
        assert digest == expected
