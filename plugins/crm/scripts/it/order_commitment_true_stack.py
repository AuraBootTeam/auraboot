#!/usr/bin/env python3
"""True-stack acceptance for released-QDP to Quote Summary order commitment.

The driver consumes the evidence emitted by qdp_release_center_true_stack.py so
the order commitment is proven against the exact released revision, Customer
Request, Requirement Version, File Package and Customer Confirmation created by
the preceding journey. All business operations use the real HTTP command
pipeline; PostgreSQL is used only for audit/readback evidence.
"""

from __future__ import annotations

import json
import os
import sys
import time
import urllib.parse
from pathlib import Path
from typing import Any

from qdp_release_center_true_stack import (
    provision_no_permission_user,
    provision_release_manager,
)
from qdp_release_true_stack import (
    BE,
    EMAIL,
    EVIDENCE_DIR,
    PASSWORD,
    PID_RE,
    command,
    dynamic_get,
    find_value,
    http,
    psql,
    record_pid,
    release_result,
    require_denied,
    require_ok,
    sql_literal,
)


RECORD_COMMITMENT = "crm:record_order_commitment"
QUOTE_MODEL = "crm_quote_summary_common"
QDP_MODEL = "crm_qdp_revision_common"


def row_version(record: dict[str, Any], label: str) -> int:
    value = int(record.get("row_version") or record.get("rowVersion") or 0)
    assert value > 0, f"{label} has no positive row_version: {record}"
    return value


def load_qdp_evidence() -> tuple[Path, dict[str, Any]]:
    configured = os.environ.get("QDP_EVIDENCE_FILE")
    assert configured, "QDP_EVIDENCE_FILE must point to a passing QDP true-stack evidence file"
    path = Path(configured).expanduser().resolve()
    assert path.is_file(), f"QDP evidence file does not exist: {path}"
    evidence = json.loads(path.read_text())
    assert evidence.get("verdict") == "pass", f"QDP evidence is not passing: {evidence}"
    for key in ("customerRequestPid", "releasedQdpPid", "staleReviewQdpPid"):
        value = str(evidence.get(key) or "")
        assert PID_RE.fullmatch(value), f"QDP evidence has no safe {key}: {value!r}"
    return path, evidence


def create_accepted_quote(jwt: str, request_pid: str, tag: str, suffix: str) -> str:
    created = command(
        "crm:create_quote_summary",
        jwt,
        payload={
            "crm_qs_customer_request_id": request_pid,
            "crm_qs_source_quote_type": "pcba_quote",
            "crm_qs_source_quote_id": f"PCBA-{tag}-{suffix}",
            "crm_qs_status": "accepted",
            "crm_qs_quote_amount": "880000.00",
            "crm_qs_currency": "CNY",
            "crm_qs_valid_until": "2026-12-31",
            "crm_qs_approval_status": "approved",
            "crm_qs_customer_feedback_status": "accepted",
            "crm_qs_won_lost_result": "won",
            "crm_qs_summary": f"Released-QDP order commitment true-stack {tag} {suffix}",
        },
        client_request_id=f"order-commitment-quote-{tag}-{suffix}",
    )
    require_ok(created, f"create accepted Quote Summary {suffix}")
    return record_pid(created, f"create accepted Quote Summary {suffix}")


def commitment(
    jwt: str,
    quote_pid: str,
    qdp_pid: str,
    expected: int,
    *,
    payload_quote_pid: str | None = None,
    dry_run: bool = False,
):
    return command(
        RECORD_COMMITMENT,
        jwt,
        target=quote_pid,
        expected_version=expected,
        dry_run=dry_run,
        payload={
            "crm_quote_summary_id": payload_quote_pid or quote_pid,
            "crm_qdp_revision_id": qdp_pid,
        },
    )


def list_quotes_for_qdp(jwt: str, qdp_pid: str) -> list[dict[str, Any]]:
    filters = json.dumps([{
        "fieldName": "crm_qs_committed_qdp_revision_id",
        "operator": "EQ",
        "value": qdp_pid,
    }], separators=(",", ":"))
    query = urllib.parse.urlencode({"pageNum": 1, "pageSize": 100, "filters": filters})
    body = require_ok(
        http("GET", f"/api/dynamic/{QUOTE_MODEL}/list?{query}", jwt=jwt),
        "reverse-list Quote Summaries by committed QDP",
    )
    data = body.get("data") or {}
    rows = data.get("records") if isinstance(data, dict) else data
    assert isinstance(rows, list), f"reverse-list returned no records: {body}"
    return [row for row in rows if isinstance(row, dict)]


def main() -> int:
    tag = time.strftime("%Y%m%d-%H%M%S")
    qdp_evidence_path, qdp_evidence = load_qdp_evidence()
    request_pid = str(qdp_evidence["customerRequestPid"])
    released_qdp_pid = str(qdp_evidence["releasedQdpPid"])
    unreleased_qdp_pid = str(qdp_evidence["staleReviewQdpPid"])
    checks: list[dict[str, Any]] = []

    login_body = require_ok(
        http("POST", "/api/auth/login", {"email": EMAIL, "password": PASSWORD}),
        "admin login",
    )
    admin_jwt = str(find_value(login_body.get("data"), ("jwt",)) or "")
    assert admin_jwt, f"admin login omitted jwt: {login_body}"

    release_jwt, release_actor_id, tenant_id = provision_release_manager(
        admin_jwt, f"order-{tag}")
    release_actor_pid = psql(
        "SELECT pid FROM ab_user WHERE id=" + release_actor_id + " AND deleted_flag=false"
    )
    assert PID_RE.fullmatch(release_actor_pid), (
        f"formal release manager has no user pid: {release_actor_pid!r}"
    )
    no_permission_jwt = provision_no_permission_user(admin_jwt, f"order-{tag}")
    checks.append({
        "id": "AUTH-FORMAL-COMPOSITE-DUTY",
        "result": "pass",
        "tenantId": tenant_id,
        "actorId": release_actor_id,
        "actorPid": release_actor_pid,
    })

    released_qdp = dynamic_get(QDP_MODEL, released_qdp_pid, release_jwt)
    assert released_qdp.get("crm_qdp_status") == "released", released_qdp
    assert released_qdp.get("crm_qdp_customer_request_id") == request_pid, released_qdp
    confirmation_pid = str(released_qdp.get("crm_qdp_customer_confirmation_id") or "")
    requirement_version_pid = str(released_qdp.get("crm_qdp_requirement_version_id") or "")
    file_package_pid = str(released_qdp.get("crm_qdp_file_package_id") or "")
    package_hash = str(released_qdp.get("crm_qdp_file_package_hash") or "")
    assert all(PID_RE.fullmatch(value) for value in (
        confirmation_pid, requirement_version_pid, file_package_pid
    )), released_qdp
    assert len(package_hash) == 64, released_qdp
    checks.append({
        "id": "INPUT-RELEASED-QDP-EVIDENCE",
        "result": "pass",
        "qdpPid": released_qdp_pid,
        "confirmationPid": confirmation_pid,
        "requirementVersionPid": requirement_version_pid,
        "filePackagePid": file_package_pid,
        "filePackageHash": package_hash,
    })

    quote_pid = create_accepted_quote(release_jwt, request_pid, tag, "positive")
    quote = dynamic_get(QUOTE_MODEL, quote_pid, release_jwt)
    quote_version = row_version(quote, "accepted Quote Summary")

    dry_run = commitment(
        release_jwt, quote_pid, released_qdp_pid, quote_version, dry_run=True)
    dry_data = release_result(dry_run)
    require_ok(dry_run, "dry-run order commitment")
    assert dry_data.get("dryRun") is True and dry_data.get("status") == "order_commitment_validated", dry_data
    assert dynamic_get(QUOTE_MODEL, quote_pid, release_jwt).get("crm_qs_status") == "accepted"
    checks.append({"id": "DRY-RUN-NO-MUTATION", "result": "pass"})

    wrong_target = commitment(
        release_jwt,
        quote_pid,
        released_qdp_pid,
        quote_version,
        payload_quote_pid="different-quote-pid",
    )
    require_denied(wrong_target, "substituted quote target", "does not match", "target")
    stale = commitment(release_jwt, quote_pid, released_qdp_pid, quote_version + 1)
    require_denied(stale, "stale Quote Summary version", "stale", "version", "optimistic")
    no_permission = commitment(
        no_permission_jwt, quote_pid, released_qdp_pid, quote_version)
    require_denied(no_permission, "order commitment without duty", "permission", "forbidden", "denied")
    checks.append({
        "id": "NEG-TARGET-VERSION-PERMISSION",
        "result": "pass",
        "targetStatus": wrong_target.status,
        "staleStatus": stale.status,
        "noPermissionStatus": no_permission.status,
    })

    negative_quote_pid = create_accepted_quote(
        release_jwt, request_pid, tag, "unreleased-qdp")
    negative_quote = dynamic_get(QUOTE_MODEL, negative_quote_pid, release_jwt)
    unreleased = commitment(
        release_jwt,
        negative_quote_pid,
        unreleased_qdp_pid,
        row_version(negative_quote, "unreleased-QDP negative Quote Summary"),
    )
    require_denied(unreleased, "unreleased QDP commitment", "released", "state")
    assert dynamic_get(QUOTE_MODEL, negative_quote_pid, release_jwt).get("crm_qs_status") == "accepted"
    checks.append({
        "id": "NEG-UNRELEASED-QDP-NO-MUTATION",
        "result": "pass",
        "httpStatus": unreleased.status,
    })

    committed = commitment(
        release_jwt, quote_pid, released_qdp_pid, quote_version)
    committed_data = release_result(committed)
    require_ok(committed, "record formal order commitment")
    quote = dynamic_get(QUOTE_MODEL, quote_pid, release_jwt)
    assert quote.get("crm_qs_status") == "ordered", quote
    assert quote.get("crm_qs_committed_qdp_revision_id") == released_qdp_pid, quote
    assert quote.get("crm_qs_customer_confirmation_id") == confirmation_pid, quote
    assert quote.get("crm_qs_order_committed_by") == release_actor_id, quote
    assert str(quote.get("crm_qs_order_committed_at") or "").strip(), quote
    assert committed_data.get("status") == "ordered", committed_data
    checks.append({
        "id": "COMMIT-EXACT-REFERENCES-AND-AUDIT",
        "result": "pass",
        "quotePid": quote_pid,
        "committedAt": quote.get("crm_qs_order_committed_at"),
        "committedBy": quote.get("crm_qs_order_committed_by"),
    })

    replay = commitment(
        release_jwt,
        quote_pid,
        released_qdp_pid,
        row_version(quote, "ordered Quote Summary"),
    )
    require_denied(replay, "repeated order commitment", "accepted", "already", "commitment")
    direct_mutation = http(
        "PUT",
        f"/api/dynamic/{QUOTE_MODEL}/{quote_pid}",
        {"crm_qs_committed_qdp_revision_id": unreleased_qdp_pid},
        admin_jwt,
    )
    require_denied(direct_mutation, "direct commitment reference mutation", "immutable", "writer", "command")
    persisted = dynamic_get(QUOTE_MODEL, quote_pid, release_jwt)
    assert persisted.get("crm_qs_committed_qdp_revision_id") == released_qdp_pid, persisted
    checks.append({
        "id": "NEG-REPEAT-AND-DIRECT-WRITER",
        "result": "pass",
        "repeatStatus": replay.status,
        "directMutationStatus": direct_mutation.status,
    })

    reverse_rows = list_quotes_for_qdp(release_jwt, released_qdp_pid)
    reverse_pids = {str(row.get("pid") or "") for row in reverse_rows}
    assert quote_pid in reverse_pids, reverse_rows
    physical_count = int(psql(
        "SELECT count(*) FROM mt_crm_quote_summary_common WHERE tenant_id=" + tenant_id
        + " AND pid=" + sql_literal(quote_pid)
        + " AND crm_qs_status='ordered'"
        + " AND crm_qs_committed_qdp_revision_id=" + sql_literal(released_qdp_pid)
        + " AND crm_qs_customer_confirmation_id=" + sql_literal(confirmation_pid)
    ) or 0)
    assert physical_count == 1, f"physical commitment row mismatch: {physical_count}"
    audit_count = int(psql(
        "SELECT count(*) FROM ab_command_audit_log WHERE tenant_id=" + tenant_id
        + " AND command_code='crm:record_order_commitment'"
    ) or 0)
    assert audit_count >= 1, f"order commitment audit row missing: {audit_count}"
    checks.append({
        "id": "REVERSE-DRILLDOWN-PHYSICAL-AUDIT",
        "result": "pass",
        "reverseMatchCount": len(reverse_pids),
        "physicalRows": physical_count,
        "auditRows": audit_count,
    })

    evidence = {
        "schemaVersion": 1,
        "runId": tag,
        "backend": BE,
        "qdpEvidenceFile": str(qdp_evidence_path),
        "tenantId": tenant_id,
        "actorId": release_actor_id,
        "actorPid": release_actor_pid,
        "customerRequestPid": request_pid,
        "releasedQdpPid": released_qdp_pid,
        "customerConfirmationPid": confirmation_pid,
        "requirementVersionPid": requirement_version_pid,
        "filePackagePid": file_package_pid,
        "filePackageHash": package_hash,
        "quoteSummaryPid": quote_pid,
        "negativeQuoteSummaryPid": negative_quote_pid,
        "checks": checks,
        "verdict": "pass",
    }
    if EVIDENCE_DIR:
        output_dir = Path(EVIDENCE_DIR)
        output_dir.mkdir(parents=True, exist_ok=True)
        output = output_dir / f"order-commitment-true-stack-{tag}.json"
        output.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + "\n")
        print(f"evidence: {output}")
    print(json.dumps(evidence, ensure_ascii=False, indent=2))
    print("PASS: released QDP to authoritative Quote Summary order commitment true-stack journey")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except AssertionError as error:
        print(f"FAIL: {error}", file=sys.stderr)
        sys.exit(1)
