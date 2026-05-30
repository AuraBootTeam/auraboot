"""Outbound HMAC-SHA256 webhook signer for AuraBoot.

Produces the ``X-AuraBoot-Signature`` header value in the format expected by
the Java ``AirflowWebhookService.parseSignature`` implementation::

    t=<unix_ts>,v1=<hex(hmac-sha256(<unix_ts>.<raw_body_utf8>, secret_utf8))>

Algorithm details (byte-identical with Java side):
  - Input to HMAC: UTF-8 bytes of ``str(unix_ts) + "." + raw_body_decoded_as_utf8``.
    This matches the Java ``computeHmac(parts.timestamp + "." + new String(rawBody, UTF_8))``.
  - Secret encoding: UTF-8 bytes of the secret string.
  - Output encoding: lowercase hex (``hexdigest()``).
"""

from __future__ import annotations

import hashlib
import hmac
import time
from typing import Optional


def sign_webhook(body: bytes, secret: str, timestamp: Optional[int] = None) -> str:
    """
    Produce the ``X-AuraBoot-Signature`` header value.

    The signing input is:
    ``<unix_ts_str> + "." + body_decoded_as_utf8``
    keyed with the UTF-8 bytes of *secret*.

    This is byte-identical with the Java ``AirflowWebhookService.computeHmac``::

        String input = parts.timestamp + "." + new String(rawBody, StandardCharsets.UTF_8);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return mac.doFinal(input.getBytes(StandardCharsets.UTF_8));

    :param body: Raw request body bytes (must not be re-serialized).
    :param secret: Shared HMAC secret string.
    :param timestamp: Explicit Unix epoch timestamp (seconds).  Auto-generated
        via ``int(time.time())`` when ``None``.
    :return: Header value of the form ``t=<ts>,v1=<64-char-hex>``.
    :raises ValueError: when *secret* is empty.
    """
    if not secret:
        raise ValueError("HMAC secret must not be empty")

    ts: int = timestamp if timestamp is not None else int(time.time())

    # Build the signing payload exactly as Java does:
    # String input = parts.timestamp + "." + new String(rawBody, UTF_8);
    # mac.doFinal(input.getBytes(UTF_8))
    # => UTF-8 bytes of (str(ts) + "." + body_as_utf8_string)
    # which is identical to: f"{ts}.".encode("utf-8") + body  (when body is valid UTF-8)
    payload: bytes = f"{ts}.".encode("utf-8") + body

    sig: str = hmac.new(
        secret.encode("utf-8"),
        payload,
        hashlib.sha256,
    ).hexdigest()

    return f"t={ts},v1={sig}"
