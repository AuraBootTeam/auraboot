"""Shared HTTP transport for release probes and deployed-target canaries.

Importing this module never provisions fixtures, logs in, or contacts a server.
"""

import json
import re
import urllib.error
import urllib.parse
import urllib.request


class ProtocolError(RuntimeError):
    """A safe diagnostic that never includes response bodies or credentials."""


def bind_physical_fixture_fields(request, admin, definition, model):
    """Materialize physical fixture fields through the real metadata APIs.

    Inline model fields describe metadata; physical publication requires actual
    library bindings. Virtual models keep their inline projection definitions.
    """
    if definition.get("sourceType", "physical") != "physical":
        return
    fields = definition.get("fields", [])
    if not fields:
        return
    pid = model.get("pid") if isinstance(model, dict) else None
    if not isinstance(pid, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,100}", pid):
        raise ProtocolError("physical fixture model identity invalid")

    def human(path, method="GET", body=None):
        _, response, _ = request(path, method=method, body=body, headers=admin)
        if not isinstance(response, dict) or str(response.get("code")) != "0":
            raise ProtocolError("physical fixture field administration failed")
        return response.get("data")

    for field in fields:
        code, kind = field.get("code"), field.get("dataType")
        if (not isinstance(code, str) or not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]{0,99}", code)
                or not isinstance(kind, str) or not kind):
            raise ProtocolError("physical fixture field definition invalid")
        _, lookup, _ = request("/api/meta/fields/key/" + code, headers=admin)
        if not isinstance(lookup, dict):
            raise ProtocolError("physical fixture field lookup invalid")
        if str(lookup.get("code")) == "0":
            stored = lookup.get("data")
        elif str(lookup.get("code")) == "1" and lookup.get("data") is None:
            stored = human("/api/meta/fields", "POST", {
                "code": code, "dataType": kind, "autoPublish": True,
                "feature": {key: bool(field.get(key, False)) for key in ["filterable", "sortable"]},
            })
        else:
            raise ProtocolError("physical fixture field lookup refused")
        if (not isinstance(stored, dict) or stored.get("code") != code
                or str(stored.get("dataType", "")).lower() != kind.lower()
                or not isinstance(stored.get("pid"), str)
                or not re.fullmatch(r"[A-Za-z0-9_-]{1,100}", stored["pid"])):
            raise ProtocolError("physical fixture library field mismatch")
        human(f"/api/meta/models/{pid}/fields/bind", "POST", {
            "fieldPid": stored["pid"], "required": bool(field.get("required", False)),
            "nullable": not bool(field.get("required", False)), "visible": True,
            "editable": bool(field.get("editable", True)),
            "readonly": not bool(field.get("editable", True)),
        })
    bound = human(f"/api/meta/models/{pid}/fields")
    if not isinstance(bound, list):
        raise ProtocolError("physical fixture field readback invalid")
    for field in fields:
        matches = [row for row in bound if isinstance(row, dict) and row.get("code") == field["code"]]
        if len(matches) != 1 or str(matches[0].get("dataType", "")).lower() != field["dataType"].lower():
            raise ProtocolError("physical fixture field binding not persisted")


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class Client:
    def __init__(self, base_url, *, timeout=90, max_requests=250, opener=None, allow_expected_rate_limit=False):
        parsed = urllib.parse.urlsplit(base_url)
        if (parsed.scheme not in {"http", "https"} or not parsed.hostname
                or parsed.username is not None or parsed.password is not None
                or parsed.query or parsed.fragment or parsed.path not in {"", "/"}):
            raise ValueError("base URL must be an HTTP(S) origin without credentials")
        if timeout <= 0 or max_requests <= 0:
            raise ValueError("timeout and request budget must be positive")
        if type(allow_expected_rate_limit) is not bool:
            raise ValueError("expected rate-limit probe opt-in must be boolean")
        self.allow_expected_rate_limit = allow_expected_rate_limit
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self.max_requests = max_requests
        self.request_count = 0
        self.stopped = False
        self.opener = opener or urllib.request.build_opener(NoRedirect())

    def request(self, path, method="GET", body=None, headers=None, form=None, raw=None, expected=(200,)):
        if not path.startswith("/") or path.startswith("//") or "#" in path:
            raise ValueError("request path must be origin-relative")
        if self.stopped or self.request_count >= self.max_requests:
            raise ProtocolError("request budget exhausted or transport stopped")
        if sum(value is not None for value in (body, form, raw)) > 1:
            raise ValueError("JSON, form and raw bodies are mutually exclusive")
        payload = None
        merged = dict(headers or {})
        if raw is not None:
            if not isinstance(raw, bytes) or len(raw) > 1024 * 1024:
                raise ValueError("raw body must be bytes within the byte budget")
            payload = raw
            merged["Content-Type"] = "application/json"
        elif form is not None:
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
        if status == 429 and not (self.allow_expected_rate_limit and expected == (429,)):
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
