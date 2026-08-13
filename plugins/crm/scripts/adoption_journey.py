#!/usr/bin/env python3
"""Timed first-use journey for the AuraBoot CRM adoption package.

Against a freshly bootstrapped and CRM-imported OSS stack, this driver creates a
formal Sales Manager user and completes the minimum useful business journey:

    Lead -> Contacted -> Qualified -> Converted Opportunity -> Next Activity

It uses only public HTTP APIs and emits parseable evidence. Set
ADOPTION_STARTED_AT_EPOCH before stack setup/import when the 30-minute clock must
include installation, otherwise the clock starts when this process starts.
"""

from __future__ import annotations

import json
import os
import sys
import time
from datetime import date, timedelta
from pathlib import Path
from typing import Any

from it.qdp_release_true_stack import (
    BE,
    EMAIL,
    PASSWORD,
    PID_RE,
    command,
    dynamic_get,
    find_value,
    http,
    record_pid,
    release_result,
    require_ok,
)


DEADLINE_SECONDS = int(os.environ.get("ADOPTION_DEADLINE_SECONDS", "1800"))
STARTED_AT = int(os.environ.get("ADOPTION_STARTED_AT_EPOCH", str(int(time.time()))))
EVIDENCE_DIR = os.environ.get("ADOPTION_EVIDENCE_DIR")


def walk(value: Any):
    if isinstance(value, dict):
        yield value
        for child in value.values():
            yield from walk(child)
    elif isinstance(value, list):
        for child in value:
            yield from walk(child)


def assert_crm_entrypoints(jwt: str) -> None:
    menu_body = require_ok(http("GET", "/api/menu/user", jwt=jwt), "load user menu")
    menu_nodes = list(walk(menu_body.get("data")))
    assert any(
        node.get("path") == "/p/c/crm_lead_desk_workbench"
        and node.get("pageKey") == "crm_lead_desk_workbench"
        for node in menu_nodes
    ), f"CRM Lead Desk menu missing: {menu_body}"
    page_body = require_ok(
        http("GET", "/api/pages/key/crm_lead_desk_workbench", jwt=jwt),
        "load CRM Lead Desk page schema",
    )
    assert page_body.get("data"), f"CRM Lead Desk page schema missing: {page_body}"


def provision_sales_manager(admin_jwt: str, tag: str) -> tuple[str, str, str]:
    email = f"crm-adopter-{tag}@example.test"
    created = http(
        "POST",
        "/api/admin/users",
        {
            "email": email,
            "displayName": f"CRM adopter {tag}",
            "initialPassword": PASSWORD,
            "roleCodes": ["crm_sales_manager"],
            "sendInviteEmail": False,
        },
        admin_jwt,
    )
    require_ok(created, "create CRM Sales Manager adopter")
    login_body = require_ok(
        http("POST", "/api/auth/login", {"email": email, "password": PASSWORD}),
        "login CRM Sales Manager adopter",
    )
    data = login_body.get("data") or {}
    jwt = str(data.get("jwt") or "")
    actor_pid = str(data.get("userPid") or "")
    assert jwt and PID_RE.fullmatch(actor_pid), f"adopter login omitted trusted identity: {login_body}"
    return jwt, actor_pid, email


def main() -> int:
    tag = time.strftime("%Y%m%d-%H%M%S")
    checkpoints: list[dict[str, Any]] = []

    admin_login = require_ok(
        http("POST", "/api/auth/login", {"email": EMAIL, "password": PASSWORD}),
        "admin login",
    )
    admin_jwt = str(find_value(admin_login.get("data"), ("jwt",)) or "")
    assert admin_jwt, f"admin login omitted jwt: {admin_login}"
    sales_jwt, actor_pid, actor_email = provision_sales_manager(admin_jwt, tag)
    assert_crm_entrypoints(sales_jwt)
    checkpoints.append({"id": "INSTALLED-ENTRYPOINTS-AND-ROLE", "result": "pass"})

    lead_created = command(
        "crm:create_lead",
        sales_jwt,
        payload={
            "crm_lead_company": f"Adoption Customer {tag}",
            "crm_lead_contact_name": "First User",
            "crm_lead_contact_phone": "+86-138-0000-0000",
            "crm_lead_contact_email": f"buyer-{tag}@example.test",
            "crm_lead_source": "website",
            "crm_lead_industry": "technology",
            "crm_lead_score": 92,
            "crm_lead_requirement": "First-use adoption request\nCreate a qualified opportunity and next activity.",
        },
        client_request_id=f"crm-adoption-lead-{tag}",
    )
    require_ok(lead_created, "create adoption Lead")
    lead_pid = record_pid(lead_created, "create adoption Lead")
    checkpoints.append({"id": "CREATE-LEAD", "result": "pass", "leadPid": lead_pid})

    require_ok(command("crm:contact_lead", sales_jwt, target=lead_pid), "contact adoption Lead")
    contacted = dynamic_get("crm_lead_common", lead_pid, sales_jwt)
    assert contacted.get("crm_lead_status") == "contacted", contacted
    require_ok(command("crm:qualify_lead", sales_jwt, target=lead_pid), "qualify adoption Lead")
    qualified = dynamic_get("crm_lead_common", lead_pid, sales_jwt)
    assert qualified.get("crm_lead_status") == "qualified", qualified
    checkpoints.append({"id": "CONTACT-AND-QUALIFY", "result": "pass"})

    converted = command("crm:convert_lead", sales_jwt, target=lead_pid)
    require_ok(converted, "convert adoption Lead")
    graph = release_result(converted)
    opportunity_pid = str(graph.get("opportunityId") or "")
    account_pid = str(graph.get("accountId") or "")
    contact_pid = str(graph.get("contactId") or "")
    request_pid = str(graph.get("customerRequestId") or "")
    assert all(PID_RE.fullmatch(value) for value in (
        opportunity_pid, account_pid, contact_pid, request_pid
    )), graph
    converted_lead = dynamic_get("crm_lead_common", lead_pid, sales_jwt)
    opportunity = dynamic_get("crm_opportunity_common", opportunity_pid, sales_jwt)
    assert converted_lead.get("crm_lead_status") == "converted", converted_lead
    assert converted_lead.get("crm_lead_converted_opportunity_id") == opportunity_pid, converted_lead
    assert opportunity.get("crm_opp_stage") == "qualification", opportunity
    assert opportunity.get("crm_opp_account_id") == account_pid, opportunity
    checkpoints.append({
        "id": "CONVERT-RELATIONSHIP-GRAPH",
        "result": "pass",
        "accountPid": account_pid,
        "contactPid": contact_pid,
        "opportunityPid": opportunity_pid,
        "customerRequestPid": request_pid,
    })

    activity_created = command(
        "crm:create_activity",
        sales_jwt,
        payload={
            "crm_act_type": "task",
            "crm_act_subject": f"Confirm next step for {tag}",
            "crm_act_content": "Call the customer and confirm qualification follow-up.",
            "crm_act_source": "manual",
            "crm_act_status": "open",
            "crm_act_priority": "high",
            "crm_act_due_date": (date.today() + timedelta(days=1)).isoformat(),
            "crm_act_assignee": actor_pid,
            "crm_act_related_model": "crm_opportunity_common",
            "crm_act_related_id": opportunity_pid,
            "crm_act_parent_id": opportunity_pid,
        },
        client_request_id=f"crm-adoption-activity-{tag}",
    )
    require_ok(activity_created, "create next Opportunity activity")
    activity_pid = record_pid(activity_created, "create next Opportunity activity")
    activity = dynamic_get("crm_activity_common", activity_pid, sales_jwt)
    assert activity.get("crm_act_status") == "open", activity
    assert activity.get("crm_act_related_model") == "crm_opportunity_common", activity
    assert activity.get("crm_act_related_id") == opportunity_pid, activity
    assert activity.get("crm_act_assignee") == actor_pid, activity
    checkpoints.append({
        "id": "CREATE-NEXT-ACTIVITY",
        "result": "pass",
        "activityPid": activity_pid,
    })

    finished_at = int(time.time())
    elapsed_seconds = finished_at - STARTED_AT
    assert elapsed_seconds <= DEADLINE_SECONDS, (
        f"adoption journey exceeded {DEADLINE_SECONDS}s: {elapsed_seconds}s"
    )
    checkpoints.append({
        "id": "UNDER-30-MINUTES",
        "result": "pass",
        "elapsedSeconds": elapsed_seconds,
        "deadlineSeconds": DEADLINE_SECONDS,
    })

    evidence = {
        "schemaVersion": 1,
        "runId": tag,
        "backend": BE,
        "startedAtEpoch": STARTED_AT,
        "finishedAtEpoch": finished_at,
        "elapsedSeconds": elapsed_seconds,
        "deadlineSeconds": DEADLINE_SECONDS,
        "actorEmail": actor_email,
        "actorPid": actor_pid,
        "leadPid": lead_pid,
        "accountPid": account_pid,
        "contactPid": contact_pid,
        "opportunityPid": opportunity_pid,
        "customerRequestPid": request_pid,
        "activityPid": activity_pid,
        "checkpoints": checkpoints,
        "verdict": "pass",
    }
    if EVIDENCE_DIR:
        output_dir = Path(EVIDENCE_DIR)
        output_dir.mkdir(parents=True, exist_ok=True)
        output = output_dir / f"crm-adoption-journey-{tag}.json"
        output.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + "\n")
        print(f"evidence: {output}")
    print(json.dumps(evidence, ensure_ascii=False, indent=2))
    print("PASS: CRM Lead -> Opportunity -> Next Activity adoption journey")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except AssertionError as error:
        print(f"FAIL: {error}", file=sys.stderr)
        sys.exit(1)
