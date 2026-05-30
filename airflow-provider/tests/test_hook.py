"""Tests for AuraBootHook."""

from __future__ import annotations

import json
from unittest.mock import MagicMock, patch

import pytest
import responses as responses_lib

from airflow_provider_auraboot.hooks.auraboot import AuraBootHook


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _make_conn(
    host="https://auraboot.example.com",
    extra=None,
):
    """Return a mock Airflow Connection object."""
    conn = MagicMock()
    conn.host = host
    raw_extra = extra or {"auth_method": "jwt", "jwt_token": "test-token"}
    conn.extra = json.dumps(raw_extra)
    conn.extra_dejson = raw_extra
    return conn


# ---------------------------------------------------------------------------
# Connection parsing tests
# ---------------------------------------------------------------------------

class TestAuraBootHookConnection:
    """Verify Connection parsing and URL resolution."""

    def test_base_url_from_host(self):
        hook = AuraBootHook("auraboot_test")
        conn = _make_conn(host="https://auraboot.example.com")
        with patch.object(hook, "get_connection", return_value=conn):
            assert hook._get_base_url() == "https://auraboot.example.com"

    def test_base_url_strips_trailing_slash(self):
        hook = AuraBootHook("auraboot_test")
        conn = _make_conn(host="https://auraboot.example.com/")
        with patch.object(hook, "get_connection", return_value=conn):
            assert hook._get_base_url() == "https://auraboot.example.com"

    def test_base_url_from_extra_overrides_host(self):
        hook = AuraBootHook("auraboot_test")
        conn = _make_conn(
            host="https://auraboot.example.com",
            extra={"auth_method": "jwt", "jwt_token": "tok", "base_url": "https://override.example.com"},
        )
        with patch.object(hook, "get_connection", return_value=conn):
            assert hook._get_base_url() == "https://override.example.com"

    def test_jwt_auth_headers(self):
        hook = AuraBootHook("auraboot_test")
        conn = _make_conn(extra={"auth_method": "jwt", "jwt_token": "my-jwt"})
        with patch.object(hook, "get_connection", return_value=conn):
            headers = hook._get_auth_headers()
        assert headers == {"Authorization": "Bearer my-jwt"}

    def test_api_key_auth_headers(self):
        hook = AuraBootHook("auraboot_test")
        conn = _make_conn(extra={"auth_method": "api_key", "api_key": "my-key"})
        with patch.object(hook, "get_connection", return_value=conn):
            headers = hook._get_auth_headers()
        assert headers == {"X-AuraBoot-Api-Key": "my-key"}

    def test_missing_jwt_token_raises(self):
        hook = AuraBootHook("auraboot_test")
        conn = _make_conn(extra={"auth_method": "jwt"})
        with patch.object(hook, "get_connection", return_value=conn):
            with pytest.raises(ValueError, match="jwt_token"):
                hook._get_auth_headers()

    def test_missing_api_key_raises(self):
        hook = AuraBootHook("auraboot_test")
        conn = _make_conn(extra={"auth_method": "api_key"})
        with patch.object(hook, "get_connection", return_value=conn):
            with pytest.raises(ValueError, match="api_key"):
                hook._get_auth_headers()

    def test_unknown_auth_method_raises(self):
        hook = AuraBootHook("auraboot_test")
        conn = _make_conn(extra={"auth_method": "magic"})
        with patch.object(hook, "get_connection", return_value=conn):
            with pytest.raises(ValueError, match="auth_method"):
                hook._get_auth_headers()


# ---------------------------------------------------------------------------
# run() tests with mocked HTTP
# ---------------------------------------------------------------------------

class TestAuraBootHookRun:
    """Verify run() sends the correct HTTP request and parses responses."""

    @responses_lib.activate
    def test_run_post_happy_path(self):
        hook = AuraBootHook("auraboot_test")
        conn = _make_conn()
        responses_lib.add(
            responses_lib.POST,
            "https://auraboot.example.com/api/commands/run",
            json={"commandRunPid": "pid-123", "status": "RUNNING"},
            status=200,
        )
        with patch.object(hook, "get_connection", return_value=conn):
            result = hook.run("POST", "/api/commands/run", {"code": "foo", "params": {}})
        assert result["commandRunPid"] == "pid-123"
        assert result["status"] == "RUNNING"

    @responses_lib.activate
    def test_run_get_happy_path(self):
        hook = AuraBootHook("auraboot_test")
        conn = _make_conn()
        responses_lib.add(
            responses_lib.GET,
            "https://auraboot.example.com/api/connector/sync-runs/run-456",
            json={"syncRunPid": "run-456", "status": "SUCCESS"},
            status=200,
        )
        with patch.object(hook, "get_connection", return_value=conn):
            result = hook.run("GET", "/api/connector/sync-runs/run-456")
        assert result["status"] == "SUCCESS"

    @responses_lib.activate
    def test_run_raises_on_5xx(self):
        import requests

        hook = AuraBootHook("auraboot_test")
        conn = _make_conn()
        responses_lib.add(
            responses_lib.POST,
            "https://auraboot.example.com/api/commands/run",
            json={"error": "internal"},
            status=500,
        )
        with patch.object(hook, "get_connection", return_value=conn):
            with pytest.raises(requests.HTTPError):
                hook.run("POST", "/api/commands/run", {})

    @responses_lib.activate
    def test_run_sends_idempotency_key(self):
        hook = AuraBootHook("auraboot_test")
        conn = _make_conn()
        responses_lib.add(
            responses_lib.POST,
            "https://auraboot.example.com/api/commands/run",
            json={"commandRunPid": "pid-999", "status": "RUNNING"},
            status=200,
        )
        with patch.object(hook, "get_connection", return_value=conn):
            hook.run("POST", "/api/commands/run", {}, idempotency_key="idem-key-1")

        assert responses_lib.calls[0].request.headers.get("X-Idempotency-Key") == "idem-key-1"

    @responses_lib.activate
    def test_run_empty_response_body(self):
        hook = AuraBootHook("auraboot_test")
        conn = _make_conn()
        responses_lib.add(
            responses_lib.POST,
            "https://auraboot.example.com/api/connector/sync-runs",
            body=b"",
            status=202,
        )
        with patch.object(hook, "get_connection", return_value=conn):
            result = hook.run("POST", "/api/connector/sync-runs", {})
        assert result == {}


# ---------------------------------------------------------------------------
# health() tests
# ---------------------------------------------------------------------------

class TestAuraBootHookHealth:
    """Verify health() probes /actuator/health correctly."""

    @responses_lib.activate
    def test_health_returns_true_when_up(self):
        hook = AuraBootHook("auraboot_test")
        conn = _make_conn()
        responses_lib.add(
            responses_lib.GET,
            "https://auraboot.example.com/actuator/health",
            json={"status": "UP"},
            status=200,
        )
        with patch.object(hook, "get_connection", return_value=conn):
            assert hook.health() is True

    @responses_lib.activate
    def test_health_returns_false_when_down(self):
        hook = AuraBootHook("auraboot_test")
        conn = _make_conn()
        responses_lib.add(
            responses_lib.GET,
            "https://auraboot.example.com/actuator/health",
            json={"status": "DOWN"},
            status=503,
        )
        with patch.object(hook, "get_connection", return_value=conn):
            assert hook.health() is False

    def test_health_returns_false_on_connection_error(self):
        hook = AuraBootHook("auraboot_test")
        conn = _make_conn()
        # No responses registered — any network call raises ConnectionError
        with responses_lib.RequestsMock(assert_all_requests_are_fired=False):
            with patch.object(hook, "get_connection", return_value=conn):
                assert hook.health() is False


# ---------------------------------------------------------------------------
# sign_webhook_body() tests
# ---------------------------------------------------------------------------

class TestAuraBootHookSignWebhookBody:
    """Verify sign_webhook_body() delegates to sign_webhook() correctly."""

    def test_sign_returns_correct_format(self):
        hook = AuraBootHook("auraboot_test")
        conn = _make_conn(extra={"auth_method": "jwt", "jwt_token": "t", "hmac_secret": "secret"})
        with patch.object(hook, "get_connection", return_value=conn):
            sig = hook.sign_webhook_body(b"test body", timestamp=1700000000)
        assert sig == "t=1700000000,v1=197540e7d9617dc2c71642e865e5cfea1bbc1f1165144a695326fb4fdc1d6afd"

    def test_sign_raises_when_hmac_secret_missing(self):
        hook = AuraBootHook("auraboot_test")
        conn = _make_conn(extra={"auth_method": "jwt", "jwt_token": "t"})
        with patch.object(hook, "get_connection", return_value=conn):
            with pytest.raises(ValueError, match="hmac_secret"):
                hook.sign_webhook_body(b"body")
