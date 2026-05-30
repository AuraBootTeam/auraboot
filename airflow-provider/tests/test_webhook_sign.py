"""Tests for sign_webhook() — byte-identical with Java AirflowWebhookService."""

from __future__ import annotations

import hashlib
import hmac
import re

import pytest

from airflow_provider_auraboot.webhooks.sign import sign_webhook

# ---------------------------------------------------------------------------
# Pre-computed expected values (verified by hand to match Java algorithm):
#   input  = f"{ts}.".encode("utf-8") + body
#   HMAC-SHA256(input, secret.encode("utf-8")).hexdigest()
# ---------------------------------------------------------------------------
_TS = 1700000000
_SECRET = "secret"
_BODY = b"test body"

# python3 -c "import hmac,hashlib; print(hmac.new(b'secret', b'1700000000.test body', hashlib.sha256).hexdigest())"
_EXPECTED_SIG = "197540e7d9617dc2c71642e865e5cfea1bbc1f1165144a695326fb4fdc1d6afd"

# Empty body
_EXPECTED_EMPTY = "4bc5f74d868b97888288889c5d9d65df02526f94c1592a79fdf4fe8b26e311e5"


# ---------------------------------------------------------------------------
# Format tests
# ---------------------------------------------------------------------------

class TestSignWebhookFormat:
    """Verify the returned string format."""

    def test_format_matches_pattern(self):
        result = sign_webhook(body=_BODY, secret=_SECRET, timestamp=_TS)
        # Must be "t=<digits>,v1=<64 lowercase hex chars>"
        assert re.fullmatch(r"t=\d+,v1=[0-9a-f]{64}", result), (
            f"Unexpected format: {result!r}"
        )

    def test_contains_expected_parts(self):
        result = sign_webhook(body=_BODY, secret=_SECRET, timestamp=_TS)
        assert result.startswith(f"t={_TS},")
        assert ",v1=" in result

    def test_v1_is_64_hex_chars(self):
        result = sign_webhook(body=_BODY, secret=_SECRET, timestamp=_TS)
        v1 = result.split(",v1=")[1]
        assert len(v1) == 64
        assert all(c in "0123456789abcdef" for c in v1)


# ---------------------------------------------------------------------------
# Determinism & Java byte-identity
# ---------------------------------------------------------------------------

class TestSignWebhookDeterminism:
    """Verify deterministic output and byte-identity with the Java algorithm."""

    def test_same_inputs_produce_same_output(self):
        r1 = sign_webhook(body=_BODY, secret=_SECRET, timestamp=_TS)
        r2 = sign_webhook(body=_BODY, secret=_SECRET, timestamp=_TS)
        assert r1 == r2

    def test_byte_identical_with_java_algorithm(self):
        """
        Verify against a pre-computed expected value that matches the Java
        AirflowWebhookService.computeHmac implementation:

          String input = parts.timestamp + "." + new String(rawBody, UTF_8);
          Mac mac = Mac.getInstance("HmacSHA256");
          mac.init(new SecretKeySpec(secret.getBytes(UTF_8), "HmacSHA256"));
          return mac.doFinal(input.getBytes(UTF_8));

        The Java hex output (via HexFormat.of().formatHex(bytes)) is lowercase.
        """
        result = sign_webhook(body=_BODY, secret=_SECRET, timestamp=_TS)
        _, v1 = result.split(",v1=")
        assert v1 == _EXPECTED_SIG, (
            f"HMAC mismatch — Python produced {v1!r}, "
            f"expected Java-compatible {_EXPECTED_SIG!r}"
        )

    def test_empty_body_produces_valid_signature(self):
        """Empty body must still produce a valid 64-char hex signature."""
        result = sign_webhook(body=b"", secret=_SECRET, timestamp=_TS)
        assert re.fullmatch(r"t=\d+,v1=[0-9a-f]{64}", result)
        _, v1 = result.split(",v1=")
        assert v1 == _EXPECTED_EMPTY, (
            f"Empty-body HMAC mismatch — Python produced {v1!r}, "
            f"expected {_EXPECTED_EMPTY!r}"
        )


# ---------------------------------------------------------------------------
# Timestamp behaviour
# ---------------------------------------------------------------------------

class TestSignWebhookTimestamp:
    """Verify explicit vs auto-generated timestamp handling."""

    def test_explicit_timestamp_is_embedded(self):
        result = sign_webhook(body=_BODY, secret=_SECRET, timestamp=_TS)
        assert result.startswith(f"t={_TS},")

    def test_auto_timestamp_is_generated(self):
        import time

        before = int(time.time())
        result = sign_webhook(body=_BODY, secret=_SECRET)
        after = int(time.time())

        ts_str = result.split(",")[0][2:]  # strip "t="
        ts = int(ts_str)
        assert before <= ts <= after + 1, (
            f"Auto-generated timestamp {ts} not in [{before}, {after}]"
        )

    def test_different_timestamps_produce_different_signatures(self):
        r1 = sign_webhook(body=_BODY, secret=_SECRET, timestamp=_TS)
        r2 = sign_webhook(body=_BODY, secret=_SECRET, timestamp=_TS + 1)
        assert r1 != r2


# ---------------------------------------------------------------------------
# Secret sensitivity
# ---------------------------------------------------------------------------

class TestSignWebhookSecret:
    """Different secrets must produce different signatures."""

    def test_different_secrets_produce_different_signatures(self):
        r1 = sign_webhook(body=_BODY, secret="secret-a", timestamp=_TS)
        r2 = sign_webhook(body=_BODY, secret="secret-b", timestamp=_TS)
        assert r1 != r2

    def test_empty_secret_raises_value_error(self):
        with pytest.raises(ValueError, match="secret"):
            sign_webhook(body=_BODY, secret="")

    def test_body_mutation_changes_signature(self):
        r1 = sign_webhook(body=b"original body", secret=_SECRET, timestamp=_TS)
        r2 = sign_webhook(body=b"modified body", secret=_SECRET, timestamp=_TS)
        assert r1 != r2
