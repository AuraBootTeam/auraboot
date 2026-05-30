"""AuraBootHook — HTTP connection abstraction for AuraBoot APIs."""

from __future__ import annotations

import time
from typing import Any, Optional

import requests
from airflow.hooks.base import BaseHook

from airflow_provider_auraboot.webhooks.sign import sign_webhook


class AuraBootHook(BaseHook):
    """
    Airflow hook for AuraBoot.

    Connection extras (JSON):
      - ``auth_method``: ``'jwt'`` | ``'api_key'`` | ``'hmac'``
      - ``jwt_token``: JWT token string (required when auth_method='jwt')
      - ``api_key``: API key string (required when auth_method='api_key')
      - ``hmac_secret``: shared secret for outbound webhook signing
      - ``base_url``: override base URL (if not set in host field)

    The connection's ``host`` field is used as ``base_url`` when ``extra.base_url``
    is not provided.

    Example connection extras::

        {
            "auth_method": "jwt",
            "jwt_token": "eyJ...",
            "hmac_secret": "my-shared-secret"
        }
    """

    conn_name_attr = "auraboot_conn_id"
    default_conn_name = "auraboot_default"
    conn_type = "auraboot"
    hook_name = "AuraBoot"

    def __init__(self, auraboot_conn_id: str = "auraboot_default") -> None:
        super().__init__()
        self.auraboot_conn_id = auraboot_conn_id
        self._conn: Optional[Any] = None

    def get_conn(self) -> Any:
        """Return the Airflow Connection object for this hook."""
        if self._conn is None:
            self._conn = self.get_connection(self.auraboot_conn_id)
        return self._conn

    def _get_base_url(self) -> str:
        """Resolve the base URL from connection host or extras."""
        conn = self.get_conn()
        extras = conn.extra_dejson if conn.extra else {}
        if extras.get("base_url"):
            return extras["base_url"].rstrip("/")
        if conn.host:
            host = conn.host
            # If the host already contains a scheme, use it as-is.
            if host.startswith("http://") or host.startswith("https://"):
                return host.rstrip("/")
            # Default to https.
            return f"https://{host}".rstrip("/")
        raise ValueError(
            f"AuraBoot connection '{self.auraboot_conn_id}' has no host or base_url configured"
        )

    def _get_auth_headers(self) -> dict[str, str]:
        """Build Authorization headers from connection extras."""
        conn = self.get_conn()
        extras = conn.extra_dejson if conn.extra else {}
        auth_method = extras.get("auth_method", "jwt")

        if auth_method == "jwt":
            token = extras.get("jwt_token")
            if not token:
                raise ValueError(
                    f"AuraBoot connection '{self.auraboot_conn_id}' requires "
                    "'jwt_token' in extras when auth_method='jwt'"
                )
            return {"Authorization": f"Bearer {token}"}

        if auth_method == "api_key":
            key = extras.get("api_key")
            if not key:
                raise ValueError(
                    f"AuraBoot connection '{self.auraboot_conn_id}' requires "
                    "'api_key' in extras when auth_method='api_key'"
                )
            return {"X-AuraBoot-Api-Key": key}

        if auth_method == "hmac":
            # HMAC auth for outbound requests: sign each request body.
            # The actual signing is deferred to run() where the body is known.
            return {}

        raise ValueError(
            f"AuraBoot connection '{self.auraboot_conn_id}' has unknown "
            f"auth_method='{auth_method}'. Expected 'jwt', 'api_key', or 'hmac'."
        )

    def _get_hmac_secret(self) -> str:
        """Return the HMAC shared secret from connection extras."""
        conn = self.get_conn()
        extras = conn.extra_dejson if conn.extra else {}
        secret = extras.get("hmac_secret")
        if not secret:
            raise ValueError(
                f"AuraBoot connection '{self.auraboot_conn_id}' requires "
                "'hmac_secret' in extras for webhook signing"
            )
        return secret

    def run(
        self,
        method: str,
        path: str,
        body: Optional[dict] = None,
        idempotency_key: Optional[str] = None,
    ) -> dict:
        """
        Execute an HTTP request against the AuraBoot API.

        :param method: HTTP method (e.g. 'GET', 'POST').
        :param path: API path starting with '/' (e.g. '/api/commands/run').
        :param body: Optional request body dict (serialized as JSON).
        :param idempotency_key: Optional idempotency key sent as
            ``X-Idempotency-Key`` header.
        :return: Parsed JSON response dict.
        :raises requests.HTTPError: on non-2xx responses.
        """
        base_url = self._get_base_url()
        url = f"{base_url}{path}"
        headers = {
            "Content-Type": "application/json",
            "Accept": "application/json",
        }
        headers.update(self._get_auth_headers())

        if idempotency_key:
            headers["X-Idempotency-Key"] = idempotency_key

        response = requests.request(
            method=method.upper(),
            url=url,
            json=body,
            headers=headers,
            timeout=30,
        )
        response.raise_for_status()
        if response.content:
            return response.json()
        return {}

    def health(self) -> bool:
        """
        Check AuraBoot backend health via ``/actuator/health``.

        :return: ``True`` if the backend reports UP, ``False`` otherwise.
        """
        try:
            base_url = self._get_base_url()
            response = requests.get(
                f"{base_url}/actuator/health",
                timeout=10,
            )
            if response.status_code == 200:
                data = response.json()
                return data.get("status") == "UP"
            return False
        except Exception:  # noqa: BLE001 — health is a best-effort probe
            return False

    def sign_webhook_body(self, body: bytes, timestamp: Optional[int] = None) -> str:
        """
        Produce the ``X-AuraBoot-Signature`` header value for an outbound
        webhook payload.

        The signature format is ``t=<unix_ts>,v1=<hex(hmac-sha256(<ts>.<body>, secret))>``,
        byte-compatible with the Java ``AirflowWebhookService.parseSignature``.

        :param body: Raw request body bytes (must not be re-serialized).
        :param timestamp: Optional explicit Unix timestamp; auto-generated when omitted.
        :return: Value suitable for the ``X-AuraBoot-Signature`` header.
        :raises ValueError: when ``hmac_secret`` is absent from connection extras.
        """
        secret = self._get_hmac_secret()
        return sign_webhook(body=body, secret=secret, timestamp=timestamp)
