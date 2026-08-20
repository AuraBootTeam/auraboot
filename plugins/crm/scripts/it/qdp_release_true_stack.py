#!/usr/bin/env python3
"""Shared true-stack QDP HTTP/PG helpers and retired-command boundary test.

Surface: journey/api
Dependencies: real-stack (Spring Boot + PostgreSQL + Redis + imported PF4J plugins)
Driver: HTTP; PostgreSQL is used only for physical postcondition/readback evidence.
Authority: blocking-release for the guarded QDP slice.

The current QDP lifecycle journey lives in qdp_release_center_true_stack.py. Running this
module directly proves that the development-stage one-step crm:release_qdp compatibility
command remains absent; it must not silently return while the multi-step lifecycle evolves.

Usage:
  BACKEND_URL=http://127.0.0.1:6454 PG_HOST=127.0.0.1 PG_PORT=5432 \
    PG_USER=auraboot PG_PASSWORD=auraboot PG_DB=auraboot_54 \
    python3 plugins/crm/scripts/it/qdp_release_true_stack.py

Set PG_CONTAINER only when a caller explicitly needs the Docker psql helper.
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Any


BE = os.environ.get("BACKEND_URL", f"http://127.0.0.1:{os.environ.get('BE_PORT', '6484')}")
PG_CONTAINER = os.environ.get("PG_CONTAINER")
PG_HOST = os.environ.get("PG_HOST", "127.0.0.1")
PG_PORT = os.environ.get("PG_PORT", "5432")
PG_USER = os.environ.get("PG_USER", "auraboot")
PG_PASSWORD = os.environ.get("PG_PASSWORD", "auraboot")
PG_DB = os.environ.get("PG_DB", "aura_boot")
EMAIL = os.environ.get("ADMIN_EMAIL", "admin@auraboot.com")
PASSWORD = os.environ.get("ADMIN_PW", "Test2026x")
EVIDENCE_DIR = os.environ.get("EVIDENCE_DIR")
PID_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")


@dataclass
class HttpResult:
    status: int
    body: dict[str, Any]
    raw: bytes
    headers: dict[str, str]


def http(
    method: str,
    path: str,
    body: dict[str, Any] | None = None,
    jwt: str | None = None,
    *,
    raw_body: bytes | None = None,
    content_type: str = "application/json",
) -> HttpResult:
    url = BE + path
    data = raw_body
    if data is None and body is not None:
        data = json.dumps(body, separators=(",", ":")).encode()
    request = urllib.request.Request(url, data=data, method=method)
    if data is not None:
        request.add_header("Content-Type", content_type)
    if jwt:
        request.add_header("Authorization", "Bearer " + jwt)
    try:
        with urllib.request.urlopen(request, timeout=90) as response:
            raw = response.read()
            return HttpResult(
                response.status,
                decode_json(raw),
                raw,
                {key.lower(): value for key, value in response.headers.items()},
            )
    except urllib.error.HTTPError as error:
        raw = error.read()
        return HttpResult(
            error.code,
            decode_json(raw),
            raw,
            {key.lower(): value for key, value in error.headers.items()},
        )


def decode_json(raw: bytes) -> dict[str, Any]:
    if not raw:
        return {}
    try:
        decoded = json.loads(raw.decode())
        return decoded if isinstance(decoded, dict) else {"data": decoded}
    except (UnicodeDecodeError, json.JSONDecodeError):
        return {"_raw": raw.decode(errors="replace")[:1_000]}


def is_ok(result: HttpResult) -> bool:
    return 200 <= result.status < 300 and str(result.body.get("code")) == "0"


def require_ok(result: HttpResult, label: str) -> dict[str, Any]:
    assert is_ok(result), f"{label} failed: HTTP {result.status} {result.body}"
    return result.body


def require_denied(result: HttpResult, label: str, *message_fragments: str) -> None:
    assert not is_ok(result), f"{label} unexpectedly succeeded: {result.body}"
    if message_fragments:
        haystack = json.dumps(result.body, ensure_ascii=False).lower()
        assert any(fragment.lower() in haystack for fragment in message_fragments), (
            f"{label} failed for an unexpected reason: HTTP {result.status} {result.body}"
        )


def command(
    code: str,
    jwt: str,
    *,
    payload: dict[str, Any] | None = None,
    target: str | None = None,
    expected_version: int | None = None,
    client_request_id: str | None = None,
    dry_run: bool = False,
) -> HttpResult:
    request: dict[str, Any] = {"payload": payload or {}}
    if target is not None:
        request["targetRecordPid"] = target
    if expected_version is not None:
        request["expectedVersion"] = expected_version
    if client_request_id is not None:
        request["clientRequestId"] = client_request_id
    if dry_run:
        request["dryRun"] = True
    return http(
        "POST",
        "/api/meta/commands/execute/" + urllib.parse.quote(code, safe=":"),
        request,
        jwt,
    )


def find_value(value: Any, keys: tuple[str, ...]) -> Any:
    if isinstance(value, dict):
        for key in keys:
            if value.get(key) not in (None, ""):
                return value[key]
        for child in value.values():
            found = find_value(child, keys)
            if found not in (None, ""):
                return found
    elif isinstance(value, list):
        for child in value:
            found = find_value(child, keys)
            if found not in (None, ""):
                return found
    return None


def record_pid(result: HttpResult, label: str) -> str:
    pid = find_value(result.body.get("data"), ("recordPid", "recordId", "pid"))
    assert pid is not None, f"{label} response has no public record pid: {result.body}"
    pid = str(pid)
    assert PID_RE.fullmatch(pid), f"{label} returned malformed pid: {pid!r}"
    return pid


def dynamic_get(model: str, pid: str, jwt: str) -> dict[str, Any]:
    body = require_ok(http("GET", f"/api/dynamic/{model}/{pid}", jwt=jwt), f"read {model}")
    data = body.get("data")
    assert isinstance(data, dict), f"read {model} returned no record: {body}"
    return data


def upload(jwt: str, filename: str, content: bytes, mime: str) -> str:
    boundary = "----AuraQdpBoundary" + hashlib.sha256(content).hexdigest()[:24]
    head = (
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="file"; filename="{filename}"\r\n'
        f"Content-Type: {mime}\r\n\r\n"
    ).encode()
    payload = head + content + f"\r\n--{boundary}--\r\n".encode()
    result = http(
        "POST",
        "/api/file/upload",
        jwt=jwt,
        raw_body=payload,
        content_type=f"multipart/form-data; boundary={boundary}",
    )
    require_ok(result, "multipart upload")
    file_pid = find_value(result.body.get("data"), ("fileId", "pid"))
    assert file_pid and PID_RE.fullmatch(str(file_pid)), f"upload returned no public file pid: {result.body}"
    return str(file_pid)


def download(jwt: str, file_pid: str) -> bytes:
    result = http("GET", f"/api/file/download/{urllib.parse.quote(file_pid)}", jwt=jwt)
    assert result.status == 200, f"download failed: HTTP {result.status} {result.body}"
    return result.raw


def psql(sql: str) -> str:
    if PG_CONTAINER:
        command = [
            "docker",
            "exec",
            PG_CONTAINER,
            "sh",
            "-lc",
            'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atc "$1"',
            "qdp-it",
            sql,
        ]
        env = None
    else:
        command = [
            "psql",
            "-h",
            PG_HOST,
            "-p",
            PG_PORT,
            "-U",
            PG_USER,
            "-d",
            PG_DB,
            "-Atc",
            sql,
        ]
        env = {**os.environ, "PGPASSWORD": PG_PASSWORD}
    completed = subprocess.run(
        command,
        capture_output=True,
        text=True,
        timeout=60,
        check=False,
        env=env,
    )
    assert completed.returncode == 0, f"psql failed: {completed.stderr.strip()}"
    return completed.stdout.strip()


def sql_literal(value: str) -> str:
    assert PID_RE.fullmatch(value), f"unsafe SQL test identifier: {value!r}"
    return "'" + value.replace("'", "''") + "'"


def qdp_payload(request_pid: str, sidecar_pid: str, file_pid: str, note: str) -> dict[str, Any]:
    return {
        "crm_qdp_customer_request_id": request_pid,
        "crm_qdp_pcba_rfq_id": sidecar_pid,
        "crm_qdp_primary_file_id": file_pid,
        "crm_qdp_file_manifest": [{"filePid": file_pid, "purpose": "bom"}],
        "crm_qdp_release_note": note,
    }


def release_result(result: HttpResult) -> dict[str, Any]:
    data = result.body.get("data")
    assert isinstance(data, dict), f"release response has no data: {result.body}"
    nested = data.get("data")
    if isinstance(nested, dict):
        data = nested
    handler_result = data.get("result")
    if isinstance(handler_result, dict):
        return handler_result
    return data


def command_was_replayed(result: HttpResult) -> bool:
    data = result.body.get("data")
    return isinstance(data, dict) and data.get("idempotentReplay") is True


def main() -> int:
    """Prove the development-stage one-step compatibility command stays retired."""
    tag = time.strftime("%Y%m%d-%H%M%S")
    login = http("POST", "/api/auth/login", {"email": EMAIL, "password": PASSWORD})
    login_body = require_ok(login, "login")
    jwt = str(find_value(login_body.get("data"), ("jwt",)) or "")
    assert jwt, f"login omitted jwt: {login_body}"

    retired = command("crm:release_qdp", jwt)
    require_denied(retired, "retired one-step QDP release", "command", "not found", "unknown")
    evidence = {
        "schemaVersion": 1,
        "runId": tag,
        "backend": BE,
        "checks": [{"id": "RETIRED-ONE-STEP-RELEASE", "result": "pass"}],
        "verdict": "pass",
    }
    if EVIDENCE_DIR:
        output_dir = Path(EVIDENCE_DIR)
        output_dir.mkdir(parents=True, exist_ok=True)
        output = output_dir / f"qdp-retired-command-true-stack-{tag}.json"
        output.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + "\n")
        print(f"evidence: {output}")
    print(json.dumps(evidence, ensure_ascii=False, indent=2))
    print("PASS: retired one-step QDP release command is not executable")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except AssertionError as error:
        print(f"FAIL: {error}", file=sys.stderr)
        sys.exit(1)
