"""Shared HTTP transport for release probes and deployed-target canaries.

Importing this module never provisions fixtures, logs in, or contacts a server.
"""

import json
import urllib.error
import urllib.parse
import urllib.request


class ProtocolError(RuntimeError):
    """A safe diagnostic that never includes response bodies or credentials."""


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class Client:
    def __init__(self, base_url, *, timeout=90, max_requests=250, opener=None):
        parsed = urllib.parse.urlsplit(base_url)
        if (parsed.scheme not in {"http", "https"} or not parsed.hostname
                or parsed.username is not None or parsed.password is not None
                or parsed.query or parsed.fragment or parsed.path not in {"", "/"}):
            raise ValueError("base URL must be an HTTP(S) origin without credentials")
        if timeout <= 0 or max_requests <= 0:
            raise ValueError("timeout and request budget must be positive")
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self.max_requests = max_requests
        self.request_count = 0
        self.stopped = False
        self.opener = opener or urllib.request.build_opener(NoRedirect())

    def request(self, path, method="GET", body=None, headers=None, form=None, expected=(200,)):
        if not path.startswith("/") or path.startswith("//") or "#" in path:
            raise ValueError("request path must be origin-relative")
        if self.stopped or self.request_count >= self.max_requests:
            raise ProtocolError("request budget exhausted or transport stopped")
        if body is not None and form is not None:
            raise ValueError("JSON and form bodies are mutually exclusive")
        payload = None
        merged = dict(headers or {})
        if form is not None:
            payload = urllib.parse.urlencode(form).encode()
            merged["Content-Type"] = "application/x-www-form-urlencoded"
        elif body is not None:
            payload = json.dumps(body).encode()
            merged["Content-Type"] = "application/json"
        req = urllib.request.Request(self.base_url + path, data=payload, headers=merged, method=method)
        self.request_count += 1
        try:
            response = self.opener.open(req, timeout=self.timeout)
        except urllib.error.HTTPError as error:
            response = error
        except (urllib.error.URLError, OSError) as error:
            raise ProtocolError(f"{method}: transport failure (no retry)") from None
        with response:
            status = response.code
            raw = response.read(4 * 1024 * 1024 + 1)
            response_headers = dict(response.headers)
        if status == 429:
            self.stopped = True
            raise ProtocolError("HTTP 429: transport stopped; no retry")
        if status not in expected:
            raise ProtocolError(f"{method}: unexpected HTTP {status}")
        if len(raw) > 4 * 1024 * 1024:
            raise ProtocolError("response exceeds byte budget")
        try:
            parsed = json.loads(raw) if raw else None
        except (ValueError, UnicodeError):
            raise ProtocolError(f"{method}: HTTP {status} response is not JSON") from None
        return status, parsed, response_headers


def data(body):
    if isinstance(body, dict) and str(body.get("code")) == "0":
        return body.get("data")
    return body
