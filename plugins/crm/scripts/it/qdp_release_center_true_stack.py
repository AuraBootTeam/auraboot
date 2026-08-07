#!/usr/bin/env python3
"""Host-first true-stack acceptance for the APP-01 QDP Release Center slice.

This driver uses the real HTTP command, dynamic-data and file endpoints against an
isolated Spring Boot/PostgreSQL/Redis stack. PostgreSQL is used for physical
postconditions and two controlled fixtures only: a second tenant with equivalent
admin grants, and a temporary file-runtime outage. No handler or HTTP dependency is
mocked.
"""

from __future__ import annotations

import hashlib
import json
import random
import re
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import Any

from qdp_release_true_stack import (
    BE,
    EMAIL,
    EVIDENCE_DIR,
    PASSWORD,
    PID_RE,
    command,
    command_was_replayed,
    dynamic_get,
    find_value,
    http,
    is_ok,
    psql,
    record_pid,
    release_result,
    require_denied,
    require_ok,
    sql_literal,
    upload,
)


PREPARE = "crm:prepare_qdp_draft"
COMPILE = "crm:compile_qdp_revision"
REVIEW = "crm:submit_qdp_review"
RELEASE = "crm:publish_qdp_revision"
LEGACY_RELEASE = "crm:release_qdp"


def row_version(record: dict[str, Any], label: str) -> int:
    value = int(record.get("row_version") or record.get("rowVersion") or 0)
    assert value > 0, f"{label} has no positive row_version: {record}"
    return value


def lifecycle_result(result: Any, label: str) -> dict[str, Any]:
    require_ok(result, label)
    data = release_result(result)
    assert isinstance(data, dict), f"{label} returned no lifecycle data: {result.body}"
    return data


def lifecycle_payload(
    request_pid: str,
    sidecar_pid: str,
    file_pid: str,
    tag: str,
    revision: int,
    *,
    approved_exception: bool = False,
) -> dict[str, Any]:
    pack_hash = hashlib.sha256(f"pack-{tag}-{revision}".encode()).hexdigest()
    return {
        "crm_qdp_customer_request_id": request_pid,
        "crm_qdp_pcba_rfq_id": sidecar_pid,
        "crm_qdp_primary_file_id": file_pid,
        "crm_qdp_file_manifest": [{"filePid": file_pid, "purpose": "customer_release"}],
        "crm_qdp_requirement_version": f"RV-{tag}-{revision}",
        "crm_qdp_customer_confirmation_ref": f"PORTAL-{tag}-{revision}",
        "crm_qdp_customer_confirmed_by": "customer.approver@example.test",
        "crm_qdp_customer_confirmed_at": "2026-08-06T15:30:00+08:00",
        "crm_qdp_pack_set": [
            {"packCode": "PCBA-MFG", "version": f"{revision}.0", "contentHash": pack_hash}
        ],
        "crm_qdp_downstream_impact": [
            {
                "objectType": "crm_customer_request_pcba_rfq",
                "objectPid": sidecar_pid,
                "impact": f"consume released QDP revision {revision}",
                "owner": "pcba-program-owner",
                "disposition": "accepted",
            }
        ],
        "crm_qdp_assumptions": ["Customer approval covers the exact uploaded package bytes"],
        "crm_qdp_approved_exceptions": ([{
            "code": f"APPROVED-{tag}-{revision}",
            "reason": "Customer-approved tolerance pending downstream acknowledgement",
        }] if approved_exception else []),
        "crm_qdp_release_note": f"QDP Release Center true-stack revision {revision}",
    }


def create_request_and_sidecar(jwt: str, tag: str) -> tuple[str, str]:
    created_request = command(
        "crm:create_customer_request",
        jwt,
        payload={
            "crm_cr_title": f"QDP Release Center {tag}",
            "crm_cr_type": "rfq",
            "crm_cr_priority": "high",
            "crm_cr_summary": "Requirement Version and File Package confirmation true-stack",
        },
        client_request_id=f"qdp-center-request-{tag}",
    )
    require_ok(created_request, "create Customer Request")
    request_pid = record_pid(created_request, "create Customer Request")
    require_ok(command("crm:submit_customer_request", jwt, target=request_pid), "submit Customer Request")

    created_sidecar = command(
        "pe:create_customer_request_pcba_rfq",
        jwt,
        payload={
            "crm_customer_request_id": request_pid,
            "crm_crq_product_model": "AMOS-QDP-CENTER",
            "crm_crq_assembly_type": "smt",
            "crm_crq_quality_class": "industrial",
            "crm_crq_quality_grade": "standard",
            "crm_crq_trace_level": "lot",
            "crm_crq_supply_mode": "turnkey",
            "crm_crq_board_count": 1,
            "crm_crq_board_layer": 4,
            "crm_crq_pcba_qty": 100,
        },
        client_request_id=f"qdp-center-sidecar-{tag}",
    )
    require_ok(created_sidecar, "create PCBA RFQ sidecar")
    sidecar_pid = record_pid(created_sidecar, "create PCBA RFQ sidecar")

    require_ok(
        http(
            "PUT",
            f"/api/dynamic/crm_customer_request_common/{request_pid}",
            {
                "crm_cr_route_status": "routed",
                "crm_cr_routed_package": "pcba-crm",
                "crm_cr_routed_object_type": "crm_customer_request_pcba_rfq",
                "crm_cr_routed_object_id": sidecar_pid,
            },
            jwt,
        ),
        "seed current route tuple",
    )
    require_ok(command("crm:start_customer_request", jwt, target=request_pid), "start Customer Request")
    return request_pid, sidecar_pid


def prepare(
    jwt: str,
    request_pid: str,
    expected: int,
    key: str,
    payload: dict[str, Any],
):
    return command(
        PREPARE,
        jwt,
        target=request_pid,
        expected_version=expected,
        client_request_id=key,
        payload=payload,
    )


def review(jwt: str, qdp_pid: str, request_pid: str, expected: int):
    return command(
        REVIEW,
        jwt,
        target=qdp_pid,
        expected_version=expected,
        payload={
            "crm_qdp_revision_id": qdp_pid,
            "crm_qdp_customer_request_id": request_pid,
        },
    )


def compile_qdp(jwt: str, qdp_pid: str, request_pid: str, expected: int):
    return command(
        COMPILE,
        jwt,
        target=qdp_pid,
        expected_version=expected,
        payload={
            "crm_qdp_revision_id": qdp_pid,
            "crm_qdp_customer_request_id": request_pid,
        },
    )


def async_task_code(result: Any, label: str) -> str:
    require_ok(result, label)
    task_code = str(find_value(result.body.get("data"), ("taskCode",)) or "")
    assert re.fullmatch(r"[A-Za-z0-9._:-]{4,128}", task_code), (
        f"{label} returned no safe async task code: {result.body}"
    )
    return task_code


def wait_async_task(jwt: str, task_code: str, qdp_pid: str) -> tuple[dict[str, Any], list[str], list[str]]:
    task_statuses: list[str] = []
    lifecycle_states: list[str] = []
    for _ in range(600):
        response = http("GET", f"/api/async-tasks/{task_code}", jwt=jwt)
        body = require_ok(response, f"poll async task {task_code}")
        task = body.get("data") or {}
        assert isinstance(task, dict), f"async task {task_code} returned no task object: {body}"
        status = str(task.get("status") or "").lower()
        if status and (not task_statuses or task_statuses[-1] != status):
            task_statuses.append(status)
        qdp = dynamic_get("crm_qdp_revision_common", qdp_pid, jwt)
        lifecycle = str(qdp.get("crm_qdp_status") or "")
        if lifecycle and (not lifecycle_states or lifecycle_states[-1] != lifecycle):
            lifecycle_states.append(lifecycle)
        if status in {"completed", "failed", "cancelled"}:
            return task, task_statuses, lifecycle_states
        time.sleep(0.1)
    raise AssertionError(f"async task {task_code} did not reach a terminal state")


def release(jwt: str, qdp_pid: str, request_pid: str, expected: int, note: str):
    return command(
        RELEASE,
        jwt,
        target=qdp_pid,
        expected_version=expected,
        payload={
            "crm_qdp_revision_id": qdp_pid,
            "crm_qdp_customer_request_id": request_pid,
            "crm_qdp_release_note": note,
        },
    )


def bump_request(jwt: str, request_pid: str, value: str) -> int:
    require_ok(
        http(
            "PUT",
            f"/api/dynamic/crm_customer_request_common/{request_pid}",
            {"crm_cr_summary": value},
            jwt,
        ),
        "update Customer Request source",
    )
    return row_version(dynamic_get("crm_customer_request_common", request_pid, jwt), "Customer Request")


def provision_no_permission_user(admin_jwt: str, tag: str) -> str:
    email = f"qdp-no-permission-{tag}@example.test"
    created = http(
        "POST",
        "/api/admin/users",
        {
            "email": email,
            "displayName": f"QDP no permission {tag}",
            "initialPassword": PASSWORD,
            "roleCodes": ["tenant_member"],
            "sendInviteEmail": False,
        },
        admin_jwt,
    )
    require_ok(created, "create no-permission user")
    login = http("POST", "/api/auth/login", {"email": email, "password": PASSWORD})
    body = require_ok(login, "login no-permission user")
    jwt = find_value(body.get("data"), ("jwt",))
    assert jwt, f"no-permission login returned no jwt: {body}"
    return str(jwt)


def provision_release_manager(admin_jwt: str, tag: str) -> tuple[str, str, str]:
    email = f"qdp-release-manager-{tag}@example.test"
    created = http(
        "POST",
        "/api/admin/users",
        {
            "email": email,
            "displayName": f"QDP release manager {tag}",
            "initialPassword": PASSWORD,
            "roleCodes": ["crm_admin", "pe_qdp_release_manager"],
            "sendInviteEmail": False,
        },
        admin_jwt,
    )
    require_ok(created, "create explicit QDP release manager")
    login = http("POST", "/api/auth/login", {"email": email, "password": PASSWORD})
    body = require_ok(login, "login explicit QDP release manager")
    data = body.get("data") or {}
    jwt = str(data.get("jwt") or "")
    actor_id = str(data.get("userId") or "")
    tenant_id = str(data.get("tenantId") or "")
    assert jwt and actor_id.isdigit() and tenant_id.isdigit(), (
        f"release-manager login omitted trusted context: {body}"
    )
    return jwt, actor_id, tenant_id


def seed_equivalent_second_tenant(admin_user_id: str, donor_tenant_id: str, tag: str) -> str:
    base = int(time.time() * 1_000_000) + random.randint(100, 900)
    tenant_id = str(base)
    member_id = str(base + 1)
    role_id = str(base + 2)
    user_role_id = str(base + 3)
    suffix = hashlib.sha256(tag.encode()).hexdigest()[:20].upper()
    tenant_pid = "01TENB" + suffix
    member_pid = "01TMB" + suffix + "0"
    role_pid = "01ROLB" + suffix
    user_role_pid = "01URB" + suffix + "0"
    assert all(PID_RE.fullmatch(value) for value in [tenant_pid, member_pid, role_pid, user_role_pid])
    for value in [admin_user_id, donor_tenant_id, tenant_id, member_id, role_id, user_role_id]:
        assert value.isdigit(), f"unsafe numeric tenant fixture value: {value!r}"

    psql(
        "INSERT INTO ab_tenant (id,pid,name,display_name,status,deleted_flag,created_at,updated_at) "
        f"VALUES ({tenant_id},{sql_literal(tenant_pid)},{sql_literal('qdp-b-' + tag)},"
        f"{sql_literal('QDP-tenant-B-' + tag)},'active',false,now(),now())"
    )
    psql(
        "INSERT INTO ab_tenant_member (id,pid,tenant_id,user_id,status,deleted_flag,created_at,updated_at) "
        f"VALUES ({member_id},{sql_literal(member_pid)},{tenant_id},{admin_user_id},'active',false,now(),now())"
    )
    donor_role = psql(
        "SELECT id FROM ab_role WHERE tenant_id=" + donor_tenant_id
        + " AND code='tenant_admin' AND deleted_flag=false LIMIT 1"
    )
    assert donor_role.isdigit(), f"donor tenant_admin role missing: {donor_role!r}"
    psql(
        "INSERT INTO ab_role (id,pid,tenant_id,code,name,status,deleted_flag,created_at,updated_at) "
        f"VALUES ({role_id},{sql_literal(role_pid)},{tenant_id},'tenant_admin','Tenant Admin','active',false,now(),now())"
    )
    psql(
        "INSERT INTO ab_permission (pid,tenant_id,code,name,description,category,resource_type,"
        "resource_code,action,source,source_ref,parent_id,path,level,data_scope_type,data_scope_config,deleted_flag) "
        f"SELECT substr(md5(random()::text||clock_timestamp()::text||code),1,26),{tenant_id},"
        "code,name,description,category,resource_type,resource_code,action,source,source_ref,NULL,path,level,"
        f"data_scope_type,data_scope_config,false FROM ab_permission WHERE tenant_id={donor_tenant_id} "
        "AND deleted_flag=false"
    )
    psql(
        "INSERT INTO ab_role_permission (pid,tenant_id,role_id,permission_id,grant_type,status,deleted_flag) "
        f"SELECT substr(md5(random()::text||clock_timestamp()::text||newp.code),1,26),{tenant_id},{role_id},"
        "newp.id,rp.grant_type,rp.status,false FROM ab_role_permission rp "
        "JOIN ab_permission oldp ON oldp.id=rp.permission_id "
        f"JOIN ab_permission newp ON newp.tenant_id={tenant_id} AND newp.code=oldp.code "
        f"WHERE rp.role_id={donor_role} AND rp.deleted_flag=false"
    )
    psql(
        "INSERT INTO ab_user_role (id,pid,member_id,tenant_id,role_id,assign_type,status,deleted_flag,created_at,updated_at) "
        f"VALUES ({user_role_id},{sql_literal(user_role_pid)},{member_id},{tenant_id},{role_id},"
        "'direct','active',false,now(),now())"
    )
    return tenant_id


def main() -> int:
    tag = time.strftime("%Y%m%d-%H%M%S")
    checks: list[dict[str, Any]] = []

    login = http("POST", "/api/auth/login", {"email": EMAIL, "password": PASSWORD})
    login_body = require_ok(login, "admin login")
    login_data = login_body.get("data") or {}
    admin_jwt = str(login_data.get("jwt") or "")
    admin_actor_id = str(login_data.get("userId") or "")
    admin_tenant_id = str(login_data.get("tenantId") or "")
    assert admin_jwt and admin_actor_id.isdigit() and admin_tenant_id.isdigit(), (
        f"admin login omitted trusted context: {login_body}"
    )
    jwt, actor_id, tenant_id = provision_release_manager(admin_jwt, tag)
    assert tenant_id == admin_tenant_id
    checks.append({
        "id": "AUTH-EXPLICIT-RELEASE-DUTY",
        "result": "pass",
        "tenantId": tenant_id,
        "actorId": actor_id,
    })

    request_pid, sidecar_pid = create_request_and_sidecar(admin_jwt, tag)
    source_v1 = f"QDP Release Center v1\nrequest={request_pid}\ntag={tag}\n".encode()
    file_v1 = upload(jwt, f"qdp-release-center-{tag}-v1.txt", source_v1, "text/plain")
    request_version = row_version(dynamic_get("crm_customer_request_common", request_pid, jwt), "Customer Request")
    payload_v1 = lifecycle_payload(
        request_pid, sidecar_pid, file_v1, tag, 1, approved_exception=True)

    unqualified = prepare(jwt, request_pid, request_version, f"qdp-unqualified-{tag}", payload_v1)
    require_denied(unqualified, "unqualified prepare", "qualification", "passed", "conditional")
    checks.append({"id": "NEG-QUALIFICATION", "result": "pass", "httpStatus": unqualified.status})
    require_ok(command("pe:request_dfm_pcba_rfq", admin_jwt, target=sidecar_pid), "start PCBA DFM")
    require_ok(command("pe:pass_dfm_pcba_rfq", admin_jwt, target=sidecar_pid), "pass PCBA DFM")

    direct_create = http(
        "POST",
        "/api/dynamic/crm_qdp_revision_common/create",
        {"crm_qdp_code": "FORGED"},
        admin_jwt,
    )
    require_denied(direct_create, "direct QDP create", "command", "create")
    missing_version = prepare(jwt, request_pid, 0, f"qdp-no-version-{tag}", payload_v1)
    require_denied(missing_version, "missing expected version", "version", "expected")
    stale_version = prepare(jwt, request_pid, request_version + 1, f"qdp-stale-version-{tag}", payload_v1)
    require_denied(stale_version, "stale prepare", "stale", "version", "optimistic")
    missing_file_payload = dict(payload_v1)
    missing_file_payload["crm_qdp_primary_file_id"] = "missing-file-pid"
    missing_file_payload["crm_qdp_file_manifest"] = [{"filePid": "missing-file-pid", "purpose": "customer_release"}]
    missing_file = prepare(jwt, request_pid, request_version, f"qdp-missing-file-{tag}", missing_file_payload)
    require_denied(missing_file, "file runtime failure", "file", "metadata", "not found")
    wrong_target_payload = dict(payload_v1)
    wrong_target_payload["crm_qdp_customer_request_id"] = "other-request-pid"
    wrong_target = prepare(jwt, request_pid, request_version, f"qdp-wrong-target-{tag}", wrong_target_payload)
    require_denied(wrong_target, "target mismatch", "match", "target")
    checks.extend([
        {"id": "WRITER-DIRECT-CREATE", "result": "pass", "httpStatus": direct_create.status},
        {"id": "NEG-MISSING-VERSION", "result": "pass", "httpStatus": missing_version.status},
        {"id": "NEG-STALE-VERSION", "result": "pass", "httpStatus": stale_version.status},
        {"id": "NEG-EXTERNAL-FILE", "result": "pass", "httpStatus": missing_file.status},
        {"id": "NEG-TARGET-MISMATCH", "result": "pass", "httpStatus": wrong_target.status},
    ])

    no_permission_jwt = provision_no_permission_user(admin_jwt, tag)
    no_permission = prepare(
        no_permission_jwt,
        request_pid,
        request_version,
        f"qdp-no-permission-command-{tag}",
        payload_v1,
    )
    require_denied(no_permission, "no-permission prepare", "permission", "forbidden", "denied")
    checks.append({"id": "NEG-NO-PERMISSION", "result": "pass", "httpStatus": no_permission.status})

    prepare_key = f"qdp-concurrent-prepare-{tag}"
    with ThreadPoolExecutor(max_workers=2) as pool:
        concurrent_prepare = list(pool.map(
            lambda _: prepare(jwt, request_pid, request_version, prepare_key, payload_v1),
            range(2),
        ))
    concurrent_success = [result for result in concurrent_prepare if is_ok(result)]
    concurrent_busy = [result for result in concurrent_prepare if not is_ok(result)]
    assert len(concurrent_success) in (1, 2), [result.body for result in concurrent_prepare]
    assert len(concurrent_success) + len(concurrent_busy) == 2
    for busy in concurrent_busy:
        require_denied(busy, "concurrent prepare lock", "lock", "in progress", "busy")
    replay_after_lock = prepare(jwt, request_pid, request_version, prepare_key, payload_v1)
    require_ok(replay_after_lock, "prepare replay after aggregate lock")
    prepare_results = [release_result(result) for result in concurrent_success]
    prepare_results.append(release_result(replay_after_lock))
    qdp1_pids = {str(result.get("qdpRevisionId") or "") for result in prepare_results}
    assert len(qdp1_pids) == 1 and PID_RE.fullmatch(next(iter(qdp1_pids))), prepare_results
    qdp1 = next(iter(qdp1_pids))
    assert psql(
        "SELECT count(*) FROM mt_crm_qdp_revision_common WHERE tenant_id=" + tenant_id
        + " AND crm_qdp_client_request_id=" + sql_literal(prepare_key)
    ) == "1", "concurrent prepare created duplicate QDP rows"
    assert command_was_replayed(replay_after_lock) or release_result(replay_after_lock).get("idempotent") is True, (
        "post-lock replay was not identified"
    )
    changed_intent = dict(payload_v1)
    changed_intent["crm_qdp_release_note"] = "changed under the same request identity"
    conflict = prepare(jwt, request_pid, request_version, prepare_key, changed_intent)
    require_denied(conflict, "changed-intent replay", "idempotency", "conflict", "intent")
    checks.append({"id": "CONCURRENCY-PREPARE-REPLAY", "result": "pass", "qdpPid": qdp1})

    qdp1_row = dynamic_get("crm_qdp_revision_common", qdp1, jwt)
    assert qdp1_row.get("crm_qdp_status") == "draft", qdp1_row
    package_hash = str(qdp1_row.get("crm_qdp_file_package_hash") or "")
    assert re.fullmatch(r"[0-9a-f]{64}", package_hash), qdp1_row
    assert package_hash == qdp1_row.get("crm_qdp_customer_confirmed_hash")
    file_package = dynamic_get("crm_file_package_common", str(qdp1_row["crm_qdp_file_package_id"]), jwt)
    requirement = dynamic_get("crm_requirement_version_common", str(qdp1_row["crm_qdp_requirement_version_id"]), jwt)
    confirmation = dynamic_get("crm_customer_confirmation_common", str(qdp1_row["crm_qdp_customer_confirmation_id"]), jwt)
    assert file_package.get("crm_fp_package_hash") == package_hash
    assert requirement.get("crm_reqv_file_package_hash") == package_hash
    assert requirement.get("crm_reqv_file_package_id") == qdp1_row.get("crm_qdp_file_package_id")
    assert confirmation.get("crm_cc_file_package_hash") == package_hash
    assert confirmation.get("crm_cc_requirement_version_id") == qdp1_row.get("crm_qdp_requirement_version_id")
    checks.append({"id": "BINDING-REQUIREMENT-CONFIRMATION-HASH", "result": "pass", "hash": package_hash})

    direct_content = http(
        "PUT",
        f"/api/dynamic/crm_qdp_revision_common/{qdp1}",
        {"crm_qdp_requirement_version": "FORGED"},
        admin_jwt,
    )
    require_denied(direct_content, "direct QDP content mutation", "immutable", "writer", "command")
    direct_status = http(
        "PUT",
        f"/api/dynamic/crm_qdp_revision_common/{qdp1}",
        {"crm_qdp_status": "released"},
        admin_jwt,
    )
    require_denied(direct_status, "direct QDP lifecycle mutation", "writer", "command")
    checks.append({"id": "WRITER-EXACT-UPDATE", "result": "pass"})

    missing_qdp_version = compile_qdp(jwt, qdp1, request_pid, 0)
    require_denied(missing_qdp_version, "compile missing version", "version", "expected")
    qdp1_version = row_version(qdp1_row, "QDP draft")
    stale_qdp_version = compile_qdp(jwt, qdp1, request_pid, qdp1_version + 1)
    require_denied(stale_qdp_version, "compile stale version", "stale", "version", "optimistic")
    no_compile_permission = compile_qdp(
        no_permission_jwt, qdp1, request_pid, qdp1_version)
    require_denied(
        no_compile_permission, "compile without permission", "permission", "forbidden", "denied")
    compile_dispatch = compile_qdp(jwt, qdp1, request_pid, qdp1_version)
    compile_task_code = async_task_code(compile_dispatch, "dispatch first QDP compilation")
    compile_task, task_statuses, lifecycle_states = wait_async_task(jwt, compile_task_code, qdp1)
    assert str(compile_task.get("status") or "").lower() == "completed", compile_task
    qdp1_review = dynamic_get("crm_qdp_revision_common", qdp1, jwt)
    assert qdp1_review.get("crm_qdp_status") == "ready_for_review", qdp1_review
    assert qdp1_review.get("crm_qdp_gate_verdict") == "ready_with_approved_exception", qdp1_review
    assert qdp1_review.get("crm_qdp_compilation_outcome") == "partial_success", qdp1_review
    assert qdp1_review.get("crm_qdp_compilation_progress") == 100, qdp1_review
    assert "approved exception" in str(qdp1_review.get("crm_qdp_compilation_summary") or "").lower()
    checks.append({
        "id": "ASYNC-COMPILE-PARTIAL-SUCCESS",
        "result": "pass",
        "taskCode": compile_task_code,
        "taskStatuses": task_statuses,
        "lifecycleStatesObserved": lifecycle_states,
        "outcome": qdp1_review.get("crm_qdp_compilation_outcome"),
    })
    checks.append({
        "id": "NEG-COMPILE-PERMISSION-STALE",
        "result": "pass",
        "noPermissionStatus": no_compile_permission.status,
        "staleStatus": stale_qdp_version.status,
    })

    qdp1_content_hash = qdp1_review.get("crm_qdp_content_hash")
    release_version = row_version(qdp1_review, "QDP in review")
    with ThreadPoolExecutor(max_workers=2) as pool:
        concurrent_release = list(pool.map(
            lambda index: release(jwt, qdp1, request_pid, release_version, f"concurrent release {index}"),
            range(2),
        ))
    successful = [result for result in concurrent_release if is_ok(result)]
    denied = [result for result in concurrent_release if not is_ok(result)]
    assert len(successful) == 1 and len(denied) == 1, [result.body for result in concurrent_release]
    require_denied(denied[0], "concurrent second releaser", "lock", "in progress", "busy")
    stale_after_lock = release(
        jwt, qdp1, request_pid, release_version, "retry with pre-release version"
    )
    require_denied(stale_after_lock, "post-lock second releaser", "stale", "version", "state")
    qdp1_released = dynamic_get("crm_qdp_revision_common", qdp1, jwt)
    assert qdp1_released.get("crm_qdp_status") == "released", qdp1_released
    assert qdp1_released.get("crm_qdp_content_hash") == qdp1_content_hash
    checks.append({"id": "CONCURRENCY-RELEASE-CAS", "result": "pass"})

    request_version = bump_request(
        admin_jwt, request_pid, f"source changed after release {tag}")
    qdp2_data = lifecycle_result(
        prepare(jwt, request_pid, request_version, f"qdp-stale-source-{tag}",
                lifecycle_payload(request_pid, sidecar_pid, file_v1, tag, 2)),
        "prepare stale-source QDP",
    )
    qdp2 = str(qdp2_data.get("qdpRevisionId") or "")
    qdp2_row = dynamic_get("crm_qdp_revision_common", qdp2, jwt)
    qdp2_confirmation = str(qdp2_row.get("crm_qdp_customer_confirmation_id") or "")
    qdp2_package_hash = str(qdp2_row.get("crm_qdp_file_package_hash") or "")
    assert PID_RE.fullmatch(qdp2_confirmation) and re.fullmatch(r"[0-9a-f]{64}", qdp2_package_hash)
    psql(
        "UPDATE mt_crm_customer_confirmation_common SET crm_cc_file_package_hash="
        + "'" + ("f" * 64) + "'"
        + " WHERE tenant_id=" + tenant_id
        + " AND pid=" + sql_literal(qdp2_confirmation)
    )
    failed_dispatch = compile_qdp(
        jwt, qdp2, request_pid, row_version(qdp2_row, "QDP 2 draft"))
    failed_task_code = async_task_code(failed_dispatch, "dispatch failing QDP compilation")
    failed_task, failed_statuses, failed_lifecycle = wait_async_task(jwt, failed_task_code, qdp2)
    assert str(failed_task.get("status") or "").lower() == "failed", failed_task
    qdp2_failed = dynamic_get("crm_qdp_revision_common", qdp2, jwt)
    assert qdp2_failed.get("crm_qdp_status") == "validation_failed", qdp2_failed
    assert qdp2_failed.get("crm_qdp_compilation_outcome") == "validation_failed", qdp2_failed
    assert "confirmation" in str(qdp2_failed.get("crm_qdp_validation_failure_summary") or "").lower()
    psql(
        "UPDATE mt_crm_customer_confirmation_common SET crm_cc_file_package_hash="
        + "'" + qdp2_package_hash + "'"
        + " WHERE tenant_id=" + tenant_id
        + " AND pid=" + sql_literal(qdp2_confirmation)
    )
    retry_dispatch = compile_qdp(
        jwt, qdp2, request_pid, row_version(qdp2_failed, "QDP 2 validation failure"))
    retry_task_code = async_task_code(retry_dispatch, "dispatch corrected QDP compilation")
    retry_task, retry_statuses, retry_lifecycle = wait_async_task(jwt, retry_task_code, qdp2)
    assert str(retry_task.get("status") or "").lower() == "completed", retry_task
    qdp2_ready = dynamic_get("crm_qdp_revision_common", qdp2, jwt)
    assert qdp2_ready.get("crm_qdp_status") == "ready_for_review", qdp2_ready
    assert qdp2_ready.get("crm_qdp_compilation_outcome") == "success", qdp2_ready
    assert not str(qdp2_ready.get("crm_qdp_validation_failure_summary") or "").strip()
    checks.append({
        "id": "ASYNC-VALIDATION-FAILED-RECOVERY",
        "result": "pass",
        "failedTaskCode": failed_task_code,
        "failedTaskStatuses": failed_statuses,
        "failedLifecycleStatesObserved": failed_lifecycle,
        "retryTaskCode": retry_task_code,
        "retryTaskStatuses": retry_statuses,
        "retryLifecycleStatesObserved": retry_lifecycle,
    })
    bump_request(admin_jwt, request_pid, f"source changed after QDP 2 review {tag}")
    qdp2_review = dynamic_get("crm_qdp_revision_common", qdp2, jwt)
    stale_source = release(jwt, qdp2, request_pid, row_version(qdp2_review, "QDP 2 review"), "must fail stale")
    require_denied(stale_source, "stale source release", "source", "stale", "new revision")
    assert dynamic_get("crm_qdp_revision_common", qdp2, jwt).get("crm_qdp_status") == "ready_for_review"
    checks.append({"id": "NEG-STALE-SOURCE", "result": "pass", "qdpPid": qdp2})

    request_version = row_version(dynamic_get("crm_customer_request_common", request_pid, jwt), "Customer Request")
    source_v3 = f"QDP Release Center v3\nrequest={request_pid}\ntag={tag}\n".encode()
    file_v3 = upload(jwt, f"qdp-release-center-{tag}-v3.txt", source_v3, "text/plain")
    qdp3_data = lifecycle_result(
        prepare(jwt, request_pid, request_version, f"qdp-release-v3-{tag}",
                lifecycle_payload(request_pid, sidecar_pid, file_v3, tag, 3)),
        "prepare QDP 3",
    )
    qdp3 = str(qdp3_data.get("qdpRevisionId") or "")
    qdp3_row = dynamic_get("crm_qdp_revision_common", qdp3, jwt)
    lifecycle_result(review(jwt, qdp3, request_pid, row_version(qdp3_row, "QDP 3 draft")), "review QDP 3")
    qdp3_review = dynamic_get("crm_qdp_revision_common", qdp3, jwt)

    psql(
        "UPDATE ab_file SET status='failed',updated_time=now() WHERE tenant_id=" + tenant_id
        + " AND pid=" + sql_literal(file_v3)
    )
    external_failure = release(
        jwt, qdp3, request_pid, row_version(qdp3_review, "QDP 3 review"), "external failure probe"
    )
    require_denied(external_failure, "release during file outage", "file", "status", "finalized", "retention")
    assert dynamic_get("crm_qdp_revision_common", qdp3, jwt).get("crm_qdp_status") == "ready_for_review"
    psql(
        "UPDATE ab_file SET status='success',updated_time=now() WHERE tenant_id=" + tenant_id
        + " AND pid=" + sql_literal(file_v3)
    )
    qdp3_review = dynamic_get("crm_qdp_revision_common", qdp3, jwt)
    release3 = lifecycle_result(
        release(jwt, qdp3, request_pid, row_version(qdp3_review, "QDP 3 recovered review"), "formal release"),
        "release QDP 3",
    )
    assert release3.get("supersededRevisionId") == qdp1, release3
    qdp1_final = dynamic_get("crm_qdp_revision_common", qdp1, jwt)
    qdp3_final = dynamic_get("crm_qdp_revision_common", qdp3, jwt)
    assert qdp1_final.get("crm_qdp_status") == "superseded", qdp1_final
    assert qdp1_final.get("crm_qdp_superseded_by_revision_id") == qdp3
    assert qdp1_final.get("crm_qdp_content_hash") == qdp1_content_hash
    assert qdp3_final.get("crm_qdp_status") == "released", qdp3_final
    assert "Requirement Version" in str(qdp3_final.get("crm_qdp_version_diff_summary"))
    assert "File Package Hash" in str(qdp3_final.get("crm_qdp_version_diff_summary"))
    assert "Pack Set" in str(qdp3_final.get("crm_qdp_version_diff_summary"))
    assert dynamic_get("crm_customer_request_pcba_rfq", sidecar_pid, jwt).get("crm_crq_qdp_revision_id") == qdp3
    checks.append({"id": "EXTERNAL-FAIL-CLOSED-RECOVERY", "result": "pass"})
    checks.append({"id": "LIFECYCLE-RELEASE-SUPERSEDE", "result": "pass", "released": qdp3, "superseded": qdp1})

    legacy_request_pid, legacy_sidecar_pid = create_request_and_sidecar(
        admin_jwt, f"{tag}-legacy")
    require_ok(command("pe:request_dfm_pcba_rfq", admin_jwt, target=legacy_sidecar_pid),
               "start legacy PCBA DFM")
    require_ok(command("pe:pass_dfm_pcba_rfq", admin_jwt, target=legacy_sidecar_pid),
               "pass legacy PCBA DFM")
    legacy_bytes = f"Legacy QDP release compatibility\nrequest={legacy_request_pid}\ntag={tag}\n".encode()
    legacy_file_pid = upload(
        jwt, f"qdp-release-center-{tag}-legacy.txt", legacy_bytes, "text/plain")
    legacy_request_version = row_version(
        dynamic_get("crm_customer_request_common", legacy_request_pid, jwt),
        "legacy Customer Request",
    )
    legacy_release = command(
        LEGACY_RELEASE,
        jwt,
        target=legacy_request_pid,
        expected_version=legacy_request_version,
        client_request_id=f"qdp-legacy-release-{tag}",
        payload={
            "crm_qdp_customer_request_id": legacy_request_pid,
            "crm_qdp_pcba_rfq_id": legacy_sidecar_pid,
            "crm_qdp_primary_file_id": legacy_file_pid,
            "crm_qdp_file_manifest": [
                {"filePid": legacy_file_pid, "purpose": "customer_release"}
            ],
            "crm_qdp_release_note": "ce55 direct-release compatibility probe",
        },
    )
    require_ok(legacy_release, "legacy direct QDP release")
    legacy_release_data = release_result(legacy_release)
    legacy_qdp_pid = str(legacy_release_data.get("qdpRevisionId") or "")
    assert PID_RE.fullmatch(legacy_qdp_pid), legacy_release_data
    legacy_qdp = dynamic_get("crm_qdp_revision_common", legacy_qdp_pid, jwt)
    assert legacy_qdp.get("crm_qdp_status") == "released", legacy_qdp
    assert legacy_qdp.get("crm_qdp_customer_request_id") == legacy_request_pid, legacy_qdp
    assert dynamic_get("crm_customer_request_pcba_rfq", legacy_sidecar_pid, jwt).get(
        "crm_crq_qdp_revision_id") == legacy_qdp_pid
    checks.append({
        "id": "LEGACY-DIRECT-RELEASE-COMPATIBILITY",
        "result": "pass",
        "qdpPid": legacy_qdp_pid,
    })

    tenant_b = seed_equivalent_second_tenant(actor_id, tenant_id, tag)
    selected = http(
        "POST",
        "/api/tenant-selection/process",
        {"action": "select", "tenantId": tenant_b},
        jwt,
    )
    selected_body = require_ok(selected, "select tenant B")
    tenant_b_jwt = str(find_value(selected_body.get("data"), ("jwt",)) or "")
    assert tenant_b_jwt, selected_body
    cross_tenant = http("GET", f"/api/dynamic/crm_qdp_revision_common/{qdp3}", jwt=tenant_b_jwt)
    require_denied(cross_tenant, "cross-tenant QDP read", "not found", "permission", "model")
    assert psql(
        "SELECT count(*) FROM mt_crm_qdp_revision_common WHERE tenant_id=" + tenant_id
        + " AND pid=" + sql_literal(qdp3)
    ) == "1"
    assert psql(
        "SELECT count(*) FROM mt_crm_qdp_revision_common WHERE tenant_id=" + tenant_b
        + " AND pid=" + sql_literal(qdp3)
    ) == "0"
    checks.append({"id": "NEG-CROSS-TENANT", "result": "pass", "tenantB": tenant_b})

    legacy_writer_count = psql(
        "SELECT count(*) FROM ab_meta_field WHERE code IN ("
        + ",".join(sql_literal(code) for code in [
            "crm_qdp_code", "crm_qdp_customer_request_id", "crm_qdp_revision_no",
            "crm_qdp_schema_version", "crm_qdp_expected_request_version", "crm_qdp_source_revision",
            "crm_qdp_qualification_verdict", "crm_qdp_qualification_evidence_refs",
            "crm_qdp_content_hash", "crm_qdp_request_snapshot", "crm_qdp_file_manifest",
            "crm_qdp_primary_file_id", "crm_qdp_primary_filename", "crm_qdp_file_names",
            "crm_qdp_client_request_id", "crm_qdp_owner_scope", "crm_qdp_status",
            "crm_qdp_release_note", "crm_qdp_released_at", "crm_qdp_released_by",
        ])
        + ") AND extension->'extension'->'allowedWriterCommands' ? 'crm:release_qdp'"
    )
    assert legacy_writer_count == "20", f"legacy writer compatibility count is {legacy_writer_count!r}"
    audit_count = int(psql(
        "SELECT count(*) FROM ab_command_audit_log WHERE tenant_id=" + tenant_id
        + " AND command_code IN ('crm:prepare_qdp_draft','crm:compile_qdp_revision','crm:submit_qdp_review',"
        + "'crm:publish_qdp_revision','crm:release_qdp')"
    ) or 0)
    assert audit_count >= 9, f"lifecycle command audit rows missing: {audit_count}"
    checks.append({"id": "AUDIT-AND-METADATA", "result": "pass", "auditRows": audit_count,
                   "legacyWriterFields": int(legacy_writer_count)})

    evidence = {
        "schemaVersion": 1,
        "runId": tag,
        "backend": BE,
        "tenantId": tenant_id,
        "actorId": actor_id,
        "customerRequestPid": request_pid,
        "pcbaRfqPid": sidecar_pid,
        "filePids": [file_v1, file_v3],
        "sourceSha256": [hashlib.sha256(source_v1).hexdigest(), hashlib.sha256(source_v3).hexdigest()],
        "qdpRevisionPids": [qdp1, qdp2, qdp3],
        "staleReviewQdpPid": qdp2,
        "releasedQdpPid": qdp3,
        "supersededQdpPid": qdp1,
        "legacyQdpPid": legacy_qdp_pid,
        "checks": checks,
        "verdict": "pass",
    }
    if EVIDENCE_DIR:
        output_dir = Path(EVIDENCE_DIR)
        output_dir.mkdir(parents=True, exist_ok=True)
        output = output_dir / f"qdp-release-center-true-stack-{tag}.json"
        output.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + "\n")
        print(f"evidence: {output}")
    print(json.dumps(evidence, ensure_ascii=False, indent=2))
    print("PASS: QDP Release Center async compilation, lifecycle, binding, replay, concurrency, permission, tenant and external-failure checks")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except AssertionError as error:
        print(f"FAIL: {error}", file=sys.stderr)
        sys.exit(1)
