#!/usr/bin/env python3
"""True-stack QDP release journey and writer-boundary test.

Surface: journey/api
Dependencies: real-stack (Spring Boot + PostgreSQL + Redis + imported PF4J plugins)
Driver: HTTP; PostgreSQL is used only for physical postcondition/readback evidence.
Authority: blocking-release for the guarded QDP slice.

The current product does not yet expose a dedicated PCBA route command. Test setup therefore
uses the public DynamicController once to write the route tuple after creating both records via
their product commands. Every release action and every protected-writer assertion crosses the
real HTTP command/dynamic/file endpoints.

Usage:
  BACKEND_URL=http://127.0.0.1:6454 PG_HOST=127.0.0.1 PG_PORT=5432 \
    PG_USER=auraboot PG_PASSWORD=auraboot PG_DB=auraboot_54 \
    python3 plugins/crm/scripts/it/qdp_release_true_stack.py

Set PG_CONTAINER to use the legacy Docker exec path instead of host psql.
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
    tag = time.strftime("%Y%m%d-%H%M%S")
    checks: list[dict[str, Any]] = []

    login = http("POST", "/api/auth/login", {"email": EMAIL, "password": PASSWORD})
    login_body = require_ok(login, "login")
    login_data = login_body.get("data") or {}
    jwt = login_data.get("jwt")
    actor_id = str(login_data.get("userId") or "")
    tenant_id = str(login_data.get("tenantId") or "")
    assert jwt and actor_id and tenant_id, f"login omitted trusted context: {login_body}"
    checks.append({"id": "AUTH-01", "result": "pass", "httpStatus": login.status})

    created_request = command(
        "crm:create_customer_request",
        jwt,
        payload={
            "crm_cr_title": f"QDP true-stack {tag}",
            "crm_cr_type": "rfq",
            "crm_cr_priority": "high",
            "crm_cr_summary": "True-stack QDP writer and replay evidence",
        },
        client_request_id=f"qdp-fixture-request-{tag}",
    )
    require_ok(created_request, "create Customer Request")
    request_pid = record_pid(created_request, "create Customer Request")
    require_ok(
        command("crm:submit_customer_request", jwt, target=request_pid),
        "submit Customer Request",
    )

    created_sidecar = command(
        "pe:create_customer_request_pcba_rfq",
        jwt,
        payload={
            "crm_customer_request_id": request_pid,
            "crm_crq_product_model": "AMOS-QDP-CTRL",
            "crm_crq_assembly_type": "smt",
            "crm_crq_quality_class": "industrial",
            "crm_crq_quality_grade": "standard",
            "crm_crq_trace_level": "lot",
            "crm_crq_supply_mode": "turnkey",
            "crm_crq_board_count": 1,
            "crm_crq_board_layer": 4,
            "crm_crq_pcba_qty": 100,
        },
        client_request_id=f"qdp-fixture-sidecar-{tag}",
    )
    require_ok(created_sidecar, "create PCBA RFQ sidecar")
    sidecar_pid = record_pid(created_sidecar, "create PCBA RFQ sidecar")

    route_result = http(
        "PUT",
        f"/api/dynamic/crm_customer_request_common/{request_pid}",
        {
            "crm_cr_route_status": "routed",
            "crm_cr_routed_package": "pcba-crm",
            "crm_cr_routed_object_type": "crm_customer_request_pcba_rfq",
            "crm_cr_routed_object_id": sidecar_pid,
        },
        jwt,
    )
    require_ok(route_result, "seed current route tuple")
    require_ok(
        command("crm:start_customer_request", jwt, target=request_pid),
        "start Customer Request",
    )

    source_bytes = (
        "AMOS QDP true-stack fixture\n"
        f"request={request_pid}\nsidecar={sidecar_pid}\ntag={tag}\n"
    ).encode()
    file_pid = upload(jwt, f"amos-qdp-{tag}.txt", source_bytes, "text/plain")
    assert download(jwt, file_pid) == source_bytes, "downloaded bytes differ from uploaded bytes"
    checks.append({"id": "FILE-01", "result": "pass", "filePid": file_pid})

    request_row = dynamic_get("crm_customer_request_common", request_pid, jwt)
    expected_version = int(request_row.get("row_version") or request_row.get("rowVersion") or 0)
    assert expected_version > 0, f"Customer Request has no positive row_version: {request_row}"
    payload = qdp_payload(request_pid, sidecar_pid, file_pid, "initial qualified release")

    unqualified = command(
        "crm:release_qdp",
        jwt,
        target=request_pid,
        expected_version=expected_version,
        client_request_id=f"qdp-unqualified-{tag}",
        payload=payload,
    )
    require_denied(unqualified, "unqualified release", "qualification", "passed", "conditional")
    checks.append({"id": "NEG-QUALIFICATION", "result": "pass", "httpStatus": unqualified.status})

    require_ok(
        command("pe:request_dfm_pcba_rfq", jwt, target=sidecar_pid),
        "start PCBA DFM",
    )
    require_ok(
        command("pe:pass_dfm_pcba_rfq", jwt, target=sidecar_pid),
        "pass PCBA DFM",
    )

    direct_create = http(
        "POST",
        "/api/dynamic/crm_qdp_revision_common/create",
        {"crm_qdp_code": f"FORGED-{tag}"},
        jwt,
    )
    require_denied(direct_create, "direct QDP create", "command", "create")
    checks.append({"id": "WRITER-CREATE", "result": "pass", "httpStatus": direct_create.status})

    direct_sidecar_write = http(
        "PUT",
        f"/api/dynamic/crm_customer_request_pcba_rfq/{sidecar_pid}",
        {"crm_crq_qdp_revision_id": "FORGED-QDP-PID"},
        jwt,
    )
    require_denied(direct_sidecar_write, "direct sidecar QDP reference update", "writer", "command")
    assert dynamic_get("crm_customer_request_pcba_rfq", sidecar_pid, jwt).get(
        "crm_crq_qdp_revision_id"
    ) in (None, ""), "denied direct sidecar write changed the row"
    checks.append({"id": "WRITER-SIDECAR", "result": "pass", "httpStatus": direct_sidecar_write.status})

    missing_version = command(
        "crm:release_qdp",
        jwt,
        target=request_pid,
        client_request_id=f"qdp-no-version-{tag}",
        payload=payload,
    )
    require_denied(missing_version, "missing expectedVersion", "version", "expected")
    checks.append({"id": "NEG-MISSING-VERSION", "result": "pass", "httpStatus": missing_version.status})

    stale_version = command(
        "crm:release_qdp",
        jwt,
        target=request_pid,
        expected_version=expected_version + 1,
        client_request_id=f"qdp-stale-{tag}",
        payload=payload,
    )
    require_denied(stale_version, "stale expectedVersion", "stale", "version", "optimistic")
    checks.append({"id": "NEG-STALE-VERSION", "result": "pass", "httpStatus": stale_version.status})

    missing_file_payload = qdp_payload(request_pid, sidecar_pid, "missing-file-pid", "missing file")
    missing_file = command(
        "crm:release_qdp",
        jwt,
        target=request_pid,
        expected_version=expected_version,
        client_request_id=f"qdp-missing-file-{tag}",
        payload=missing_file_payload,
    )
    require_denied(missing_file, "missing source file", "file", "metadata", "not found")
    checks.append({"id": "NEG-MISSING-FILE", "result": "pass", "httpStatus": missing_file.status})

    wrong_target_payload = dict(payload)
    wrong_target_payload["crm_qdp_customer_request_id"] = "other-request-pid"
    wrong_target = command(
        "crm:release_qdp",
        jwt,
        target=request_pid,
        expected_version=expected_version,
        client_request_id=f"qdp-wrong-target-{tag}",
        payload=wrong_target_payload,
    )
    require_denied(wrong_target, "payload/target mismatch", "match", "target")
    checks.append({"id": "NEG-TARGET-MISMATCH", "result": "pass", "httpStatus": wrong_target.status})

    release_key = f"qdp-release-{tag}"
    released = command(
        "crm:release_qdp",
        jwt,
        target=request_pid,
        expected_version=expected_version,
        client_request_id=release_key,
        payload=payload,
    )
    require_ok(released, "release QDP")
    first = release_result(released)
    qdp_pid = str(first.get("qdpRevisionId") or first.get("recordPid") or "")
    assert PID_RE.fullmatch(qdp_pid), f"release result has no QDP pid: {released.body}"
    assert first.get("idempotent") is False, f"first release was reported as replay: {first}"
    assert int(first.get("revision") or 0) == 1, f"first release revision is not 1: {first}"
    content_hash = str(first.get("contentHash") or "")
    assert re.fullmatch(r"[0-9a-f]{64}", content_hash), f"invalid release hash: {first}"
    checks.append({"id": "RELEASE-01", "result": "pass", "qdpPid": qdp_pid})

    qdp_row = dynamic_get("crm_qdp_revision_common", qdp_pid, jwt)
    assert qdp_row.get("crm_qdp_status") == "released", f"QDP status mismatch: {qdp_row}"
    assert qdp_row.get("crm_qdp_customer_request_id") == request_pid, f"QDP owner mismatch: {qdp_row}"
    assert qdp_row.get("crm_qdp_primary_file_id") == file_pid, f"QDP primary file mismatch: {qdp_row}"
    assert qdp_row.get("crm_qdp_content_hash") == content_hash, f"QDP hash mismatch: {qdp_row}"
    assert str(qdp_row.get("crm_qdp_released_by")) == actor_id, f"QDP actor mismatch: {qdp_row}"
    sidecar_row = dynamic_get("crm_customer_request_pcba_rfq", sidecar_pid, jwt)
    assert sidecar_row.get("crm_crq_qdp_revision_id") == qdp_pid, f"sidecar link mismatch: {sidecar_row}"

    physical = psql(
        "SELECT count(*) FROM ab_meta_field "
        "WHERE code IN ('crm_crq_qdp_revision_id','crm_qdp_code','crm_qdp_customer_request_id',"
        "'crm_qdp_revision_no','crm_qdp_schema_version','crm_qdp_expected_request_version',"
        "'crm_qdp_source_revision','crm_qdp_qualification_verdict',"
        "'crm_qdp_qualification_evidence_refs','crm_qdp_content_hash','crm_qdp_request_snapshot',"
        "'crm_qdp_file_manifest','crm_qdp_primary_file_id','crm_qdp_primary_filename',"
        "'crm_qdp_file_names','crm_qdp_client_request_id','crm_qdp_owner_scope','crm_qdp_status',"
        "'crm_qdp_release_note','crm_qdp_released_at','crm_qdp_released_by') "
        "AND extension->'extension'->'allowedWriterCommands' = '[\"crm:release_qdp\"]'::jsonb"
    )
    assert physical == "21", f"expected 21 hydrated exact-writer fields, got {physical!r}"
    checks.append({"id": "WRITER-METADATA", "result": "pass", "protectedFieldCount": 21})

    replayed = command(
        "crm:release_qdp",
        jwt,
        target=request_pid,
        expected_version=expected_version,
        client_request_id=release_key,
        payload=payload,
    )
    require_ok(replayed, "same-intent QDP replay")
    replay = release_result(replayed)
    assert replay.get("qdpRevisionId") == qdp_pid, f"replay changed QDP pid: {replay}"
    assert replay.get("contentHash") == content_hash, f"replay changed content hash: {replay}"
    assert command_was_replayed(replayed), f"platform did not mark cached command replay: {replayed.body}"
    checks.append({"id": "REPLAY-SAME", "result": "pass"})

    changed_payload = dict(payload)
    changed_payload["crm_qdp_release_note"] = "changed intent under same key"
    conflict = command(
        "crm:release_qdp",
        jwt,
        target=request_pid,
        expected_version=expected_version,
        client_request_id=release_key,
        payload=changed_payload,
    )
    require_denied(conflict, "same-key changed-intent replay", "idempotency", "conflict", "intent")
    checks.append({"id": "REPLAY-CONFLICT", "result": "pass", "httpStatus": conflict.status})

    current_request = dynamic_get("crm_customer_request_common", request_pid, jwt)
    next_expected_version = int(
        current_request.get("row_version") or current_request.get("rowVersion") or 0
    )
    assert next_expected_version > 0, f"released Customer Request lost row_version: {current_request}"

    second = command(
        "crm:release_qdp",
        jwt,
        target=request_pid,
        expected_version=next_expected_version,
        client_request_id=f"{release_key}-r2",
        payload=changed_payload,
    )
    require_ok(second, "second QDP revision")
    second_result = release_result(second)
    qdp_pid_2 = str(second_result.get("qdpRevisionId") or "")
    assert PID_RE.fullmatch(qdp_pid_2) and qdp_pid_2 != qdp_pid, f"second release pid invalid: {second_result}"
    assert int(second_result.get("revision") or 0) == 2, f"second release revision is not 2: {second_result}"
    assert dynamic_get("crm_customer_request_pcba_rfq", sidecar_pid, jwt).get(
        "crm_crq_qdp_revision_id"
    ) == qdp_pid_2, "sidecar did not advance to QDP revision 2"
    checks.append({"id": "RELEASE-02", "result": "pass", "qdpPid": qdp_pid_2})

    qdp_count = psql(
        "SELECT count(*) FROM mt_crm_qdp_revision_common WHERE crm_qdp_customer_request_id="
        + sql_literal(request_pid)
    )
    assert qdp_count == "2", f"expected exactly two durable QDP revisions, got {qdp_count!r}"
    audit_count = psql(
        "SELECT count(*) FROM ab_command_audit_log WHERE command_code='crm:release_qdp' "
        "AND tenant_id=" + tenant_id
    )
    assert int(audit_count or 0) >= 2, f"release audit rows were not persisted: {audit_count!r}"
    checks.append({"id": "AUDIT-01", "result": "pass", "auditRows": int(audit_count)})

    evidence = {
        "schemaVersion": 1,
        "runId": tag,
        "backend": BE,
        "postgres": (
            {"mode": "docker", "container": PG_CONTAINER}
            if PG_CONTAINER
            else {"mode": "host", "host": PG_HOST, "port": PG_PORT, "database": PG_DB}
        ),
        "tenantId": tenant_id,
        "actorId": actor_id,
        "customerRequestPid": request_pid,
        "pcbaRfqPid": sidecar_pid,
        "filePid": file_pid,
        "sourceSha256": hashlib.sha256(source_bytes).hexdigest(),
        "qdpRevisionPids": [qdp_pid, qdp_pid_2],
        "checks": checks,
        "verdict": "pass",
    }
    if EVIDENCE_DIR:
        output_dir = Path(EVIDENCE_DIR)
        output_dir.mkdir(parents=True, exist_ok=True)
        output = output_dir / f"qdp-release-true-stack-{tag}.json"
        output.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + "\n")
        print(f"evidence: {output}")
    print(json.dumps(evidence, ensure_ascii=False, indent=2))
    print("PASS: QDP true-stack release, exact-writer denials, replay and durable readback")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except AssertionError as error:
        print(f"FAIL: {error}", file=sys.stderr)
        sys.exit(1)
