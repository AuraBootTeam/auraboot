#!/usr/bin/env python3
"""True-stack acceptance for governed external Customer Request intake.

Surface: service/api
Dependencies: real Spring Boot + PostgreSQL + imported CRM hybrid plugin
Driver: HTTP command pipeline; PostgreSQL is read only and supplies physical/audit proof.
Authority: blocking-release for AMOS-P0-B01.
"""

from __future__ import annotations

import json
import os
import re
import sys
import time
from pathlib import Path
from typing import Any

from qdp_release_true_stack import (
    BE,
    EMAIL,
    EVIDENCE_DIR,
    PASSWORD,
    command,
    command_was_replayed,
    dynamic_get,
    find_value,
    http,
    psql,
    release_result,
    require_denied,
    require_ok,
    sql_literal,
)


COMMAND = "crm:intake_customer_request"
MODEL = "crm_customer_request_common"
HASH_RE = re.compile(r"^[a-f0-9]{64}$")
EXPECTED_CHECKS = [
    "AUTHENTICATED-COMMAND-BOUNDARY",
    "DRY-RUN-NO-MUTATION",
    "CREATE-ONE-AUTHORITATIVE-REQUEST",
    "EXACT-BUSINESS-REPLAY",
    "PLATFORM-IDEMPOTENCY-REPLAY-AND-CONFLICT",
    "BUSINESS-KEY-CHANGED-CONTENT-FAIL-CLOSED",
    "MISSING-EVIDENCE-FAIL-CLOSED",
    "DIRECT-SOURCE-MUTATION-DENIED",
    "PHYSICAL-UNIQUENESS-AND-AUDIT",
]


def evidence(field: str, locator: str, tag: str) -> dict[str, str]:
    return {
        "field": field,
        "locator": locator,
        "evidenceRef": f"archive://amos-intake/{tag}",
    }


def payload(tag: str, title: str) -> dict[str, Any]:
    return {
        "crm_cr_source_channel": "email",
        "crm_cr_source_system": "AMOS.MAILBOX",
        "crm_cr_source_message_ref": f"message-{tag}",
        "crm_cr_source_received_at": "2026-08-13T09:02:03+08:00",
        "crm_cr_title": title,
        "crm_cr_summary": "Customer asks for 5,000 motor-control assemblies.",
        "crm_cr_field_evidence": [
            evidence("crm_cr_title", "headers.subject", tag),
            evidence("crm_cr_summary", "mime.text/plain[0]", tag),
        ],
    }


def request_pid(result: Any, label: str) -> str:
    value = find_value(result.body.get("data"),
                       ("customerRequestId", "recordPid", "recordId", "pid"))
    assert value, f"{label} returned no Customer Request pid: {result.body}"
    normalized = str(value)
    assert re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._:-]{0,127}", normalized), normalized
    return normalized


def add_check(checks: list[dict[str, Any]], check_id: str, **facts: Any) -> None:
    checks.append({"id": check_id, "result": "pass", **facts})


def json_value(value: Any, label: str) -> Any:
    if not isinstance(value, str):
        return value
    try:
        return json.loads(value)
    except json.JSONDecodeError as error:
        raise AssertionError(f"{label} is not valid JSON: {value!r}") from error


def main() -> int:
    tag = time.strftime("%Y%m%d-%H%M%S")
    checks: list[dict[str, Any]] = []

    login_body = require_ok(
        http("POST", "/api/auth/login", {"email": EMAIL, "password": PASSWORD}),
        "admin login",
    )
    jwt = str(find_value(login_body.get("data"), ("jwt",)) or "")
    assert jwt, f"admin login omitted jwt: {login_body}"
    add_check(checks, "AUTHENTICATED-COMMAND-BOUNDARY")

    dry_payload = payload(f"{tag}-dry", f"AMOS intake dry run {tag}")
    dry_run = command(
        COMMAND,
        jwt,
        payload=dry_payload,
        client_request_id=f"amos-intake-dry-{tag}",
        dry_run=True,
    )
    dry_data = release_result(dry_run)
    require_ok(dry_run, "dry-run Customer Request intake")
    assert dry_data.get("dryRun") is True, dry_data
    assert dry_data.get("status") == "intake_validated", dry_data
    dry_count = int(psql(
        "SELECT count(*) FROM mt_crm_customer_request_common WHERE "
        "crm_cr_source_message_ref=" + sql_literal(f"message-{tag}-dry")
    ) or 0)
    assert dry_count == 0, f"dry-run persisted {dry_count} Customer Requests"
    add_check(checks, "DRY-RUN-NO-MUTATION")

    source = payload(tag, f"AMOS governed intake {tag}")
    first_client_key = f"amos-intake-{tag}-first"
    created = command(COMMAND, jwt, payload=source, client_request_id=first_client_key)
    created_data = release_result(created)
    require_ok(created, "create governed Customer Request")
    pid = request_pid(created, "create governed Customer Request")
    record = dynamic_get(MODEL, pid, jwt)
    content_hash = str(record.get("crm_cr_source_content_hash") or "")
    business_key = str(record.get("crm_cr_source_business_key") or "")
    assert created_data.get("idempotent") is False, created_data
    assert record.get("crm_cr_title") == source["crm_cr_title"], record
    assert record.get("crm_cr_status") == "submitted", record
    assert record.get("crm_cr_route_status") == "unrouted", record
    assert record.get("crm_cr_source_channel") == "email", record
    assert record.get("crm_cr_source_system") == "amos.mailbox", record
    assert record.get("crm_cr_source_message_ref") == f"message-{tag}", record
    assert int(record.get("crm_cr_field_evidence_count") or 0) == 2, record
    assert HASH_RE.fullmatch(content_hash), content_hash
    assert HASH_RE.fullmatch(business_key), business_key
    snapshot = json_value(record.get("crm_cr_intake_snapshot"), "intake snapshot")
    provenance = json_value(record.get("crm_cr_source_provenance"), "source provenance")
    field_evidence = json_value(record.get("crm_cr_field_evidence"), "field evidence")
    assert isinstance(snapshot, dict) and snapshot.get("schemaVersion") == 1, snapshot
    assert isinstance(provenance, dict) and provenance.get("contentHash") == content_hash, provenance
    assert isinstance(field_evidence, list) and len(field_evidence) == 2, field_evidence
    assert all(HASH_RE.fullmatch(str(item.get("valueHash") or "")) for item in field_evidence)
    add_check(checks, "CREATE-ONE-AUTHORITATIVE-REQUEST", customerRequestPid=pid)

    reordered = dict(source)
    reordered["crm_cr_field_evidence"] = list(reversed(source["crm_cr_field_evidence"]))
    replay = command(
        COMMAND,
        jwt,
        payload=reordered,
        client_request_id=f"amos-intake-{tag}-business-replay",
    )
    replay_data = release_result(replay)
    require_ok(replay, "exact business-key replay")
    assert replay_data.get("idempotent") is True, replay_data
    assert request_pid(replay, "exact business-key replay") == pid, replay.body
    add_check(checks, "EXACT-BUSINESS-REPLAY")

    platform_replay = command(
        COMMAND, jwt, payload=source, client_request_id=first_client_key)
    require_ok(platform_replay, "platform clientRequestId replay")
    assert command_was_replayed(platform_replay), platform_replay.body
    changed_same_client = dict(source)
    changed_same_client["crm_cr_source_received_at"] = "2026-08-13T09:03:03+08:00"
    changed_same_client_result = command(
        COMMAND, jwt, payload=changed_same_client, client_request_id=first_client_key)
    require_denied(
        changed_same_client_result,
        "changed payload under one clientRequestId",
        "idempotency",
        "different",
        "conflict",
    )
    add_check(checks, "PLATFORM-IDEMPOTENCY-REPLAY-AND-CONFLICT")

    changed = payload(tag, f"Changed AMOS governed intake {tag}")
    business_conflict = command(
        COMMAND,
        jwt,
        payload=changed,
        client_request_id=f"amos-intake-{tag}-business-conflict",
    )
    require_denied(
        business_conflict,
        "changed content under one source business identity",
        "changed",
        "source business identity",
    )
    assert dynamic_get(MODEL, pid, jwt).get("crm_cr_title") == source["crm_cr_title"]
    add_check(checks, "BUSINESS-KEY-CHANGED-CONTENT-FAIL-CLOSED")

    incomplete = payload(f"{tag}-missing-evidence", f"AMOS incomplete intake {tag}")
    incomplete["crm_cr_field_evidence"] = [
        evidence("crm_cr_title", "headers.subject", tag)
    ]
    missing_evidence = command(
        COMMAND,
        jwt,
        payload=incomplete,
        client_request_id=f"amos-intake-{tag}-missing-evidence",
    )
    require_denied(missing_evidence, "missing field evidence", "requires evidence")
    add_check(checks, "MISSING-EVIDENCE-FAIL-CLOSED")

    direct_mutation = http(
        "PUT",
        f"/api/dynamic/{MODEL}/{pid}",
        {"crm_cr_source_message_ref": f"tampered-message-{tag}"},
        jwt,
    )
    require_denied(direct_mutation, "direct source mutation", "immutable", "writer")
    assert dynamic_get(MODEL, pid, jwt).get("crm_cr_source_message_ref") == f"message-{tag}"
    add_check(checks, "DIRECT-SOURCE-MUTATION-DENIED")

    tenant_and_count = psql(
        "SELECT tenant_id::text || '|' || count(*)::text "
        "FROM mt_crm_customer_request_common WHERE pid=" + sql_literal(pid)
        + " AND crm_cr_source_business_key='" + business_key + "' "
        "AND crm_cr_source_content_hash='" + content_hash + "' GROUP BY tenant_id"
    )
    tenant_text, physical_count_text = tenant_and_count.split("|", 1)
    assert int(physical_count_text) == 1, tenant_and_count
    audit_count = int(psql(
        "SELECT count(*) FROM ab_command_audit_log WHERE tenant_id=" + tenant_text
        + " AND command_code='crm:intake_customer_request'"
    ) or 0)
    assert audit_count >= 1, f"intake command audit is missing: {audit_count}"
    add_check(
        checks,
        "PHYSICAL-UNIQUENESS-AND-AUDIT",
        tenantId=tenant_text,
        physicalRows=1,
        auditRows=audit_count,
    )

    completed = [item["id"] for item in checks]
    assert completed == EXPECTED_CHECKS, (completed, EXPECTED_CHECKS)
    receipt = {
        "schemaVersion": 1,
        "runId": tag,
        "backend": BE,
        "customerRequestPid": pid,
        "contentHash": content_hash,
        "businessKey": business_key,
        "expectedChecks": EXPECTED_CHECKS,
        "checks": checks,
        "verdict": "pass",
    }
    if EVIDENCE_DIR:
        output_dir = Path(EVIDENCE_DIR)
        output_dir.mkdir(parents=True, exist_ok=True)
        output = output_dir / f"customer-request-intake-true-stack-{tag}.json"
        output.write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + "\n")
        print(f"evidence: {output}")
    print(json.dumps(receipt, ensure_ascii=False, indent=2))
    print("PASS: governed external Customer Request intake true-stack journey")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except AssertionError as error:
        print(f"FAIL: {error}", file=sys.stderr)
        sys.exit(1)
