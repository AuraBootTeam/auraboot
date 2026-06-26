#!/usr/bin/env python3
"""Quote/BOM account organization/password smoke for a deployed runtime.

The script intentionally keeps one-time passwords and JWTs in memory only. The
JSON evidence records whether temporary passwords were returned and whether
login/reset checks passed, but never stores the secret values.
"""

from __future__ import annotations

import argparse
import json
import os
import urllib.parse
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:18080")
    parser.add_argument("--employee-json", required=True)
    parser.add_argument("--out-dir", required=True)
    return parser.parse_args()


def load_accounts(path: Path) -> list[dict[str, Any]]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    accounts = payload.get("response", {}).get("data", {}).get("accounts", [])
    if not isinstance(accounts, list):
        raise SystemExit("employee JSON does not contain response.data.accounts")
    return accounts


def pick_account(accounts: list[dict[str, Any]], role: str) -> dict[str, Any]:
    for account in accounts:
        if role in account.get("assignedRoles", []):
            return account
    raise SystemExit(f"missing account with role {role}")


def request_json(
    base_url: str,
    method: str,
    path: str,
    body: dict[str, Any] | None = None,
    token: str | None = None,
) -> dict[str, Any]:
    data = None if body is None else json.dumps(body).encode("utf-8")
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(base_url + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            text = resp.read().decode("utf-8")
            parsed = json.loads(text) if text else {}
            return {
                "status": resp.status,
                "code": parsed.get("code"),
                "message": parsed.get("message"),
                "data": parsed.get("data"),
            }
    except urllib.error.HTTPError as exc:
        text = exc.read().decode("utf-8", errors="replace")
        try:
            parsed = json.loads(text) if text else {}
        except json.JSONDecodeError:
            parsed = {"message": text[:500]}
        return {
            "status": exc.code,
            "code": parsed.get("code"),
            "message": parsed.get("message"),
            "data": parsed.get("data"),
        }


def login(base_url: str, identifier: str, password: str) -> dict[str, Any]:
    last: dict[str, Any] | None = None
    for attempt in range(3):
        resp = request_json(
            base_url,
            "POST",
            "/api/auth/login",
            {"identifier": identifier, "password": password},
        )
        data = resp.get("data") or {}
        result = {
            "ok": bool(data.get("jwt")),
            "token": data.get("jwt"),
            "status": resp.get("status"),
            "code": resp.get("code"),
            "message": resp.get("message"),
            "mustChangePassword": bool(data.get("mustChangePassword")),
        }
        if result["ok"] or attempt == 2:
            return result
        last = result
        time.sleep(0.2)
    return last or {"ok": False, "token": None, "status": None, "code": None, "message": None, "mustChangePassword": False}


def execute_command(
    base_url: str,
    command_code: str,
    payload: dict[str, Any],
    token: str,
    *,
    target_record_pid: str | None = None,
    operation_type: str | None = None,
) -> dict[str, Any]:
    body: dict[str, Any] = {
        "payload": payload,
        "clientRequestId": f"{command_code}-{int(time.time() * 1000)}-{time.time_ns()}",
    }
    if target_record_pid:
        body["targetRecordPid"] = target_record_pid
    if operation_type:
        body["operationType"] = operation_type
    return request_json(
        base_url,
        "POST",
        f"/api/meta/commands/execute/{urllib.parse.quote(command_code, safe='')}",
        body,
        token,
    )


def command_data(resp: dict[str, Any]) -> dict[str, Any]:
    data = resp.get("data") or {}
    if isinstance(data.get("data"), dict):
        return data["data"]
    return data if isinstance(data, dict) else {}


def record_pid(resp: dict[str, Any]) -> str | None:
    data = command_data(resp)
    outer = resp.get("data") if isinstance(resp.get("data"), dict) else {}
    return data.get("recordPid") or data.get("recordId") or outer.get("pid")


def command_failure(label: str, resp: dict[str, Any]) -> str:
    data = resp.get("data")
    data_keys = sorted(data.keys()) if isinstance(data, dict) else []
    inner = command_data(resp)
    inner_keys = sorted(inner.keys()) if isinstance(inner, dict) else []
    return (
        f"{label} failed status={resp.get('status')} code={resp.get('code')} "
        f"message={resp.get('message')} dataKeys={data_keys} innerKeys={inner_keys}"
    )


def denied(resp: dict[str, Any]) -> bool:
    return resp.get("status") in {401, 403} or str(resp.get("code")) in {"401", "403", "409"}


def main() -> None:
    args = parse_args()
    base_url = args.base_url.rstrip("/")
    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    accounts = load_accounts(Path(args.employee_json))
    admin = pick_account(accounts, "tenant_admin")
    sales = pick_account(accounts, "qo_sales")

    admin_login = login(base_url, admin["userName"], admin["initialPassword"])
    sales_login = login(base_url, sales["userName"], sales["initialPassword"])
    if not admin_login["token"] or not sales_login["token"]:
        raise SystemExit(
            "admin/sales login failed: "
            f"admin status={admin_login['status']} code={admin_login['code']}; "
            f"sales status={sales_login['status']} code={sales_login['code']}"
        )

    admin_token = admin_login["token"]
    sales_token = sales_login["token"]
    policy_admin = request_json(base_url, "GET", "/api/admin/account-security-policy", token=admin_token)
    policy_sales = request_json(base_url, "GET", "/api/admin/account-security-policy", token=sales_token)

    suffix = str(int(time.time() * 1000))[-8:]
    dept = execute_command(
        base_url,
        "org:create_department",
        {
            "org_dept_name": f"QB Smoke Dept {suffix}",
            "org_dept_code": f"QB-SMOKE-DEPT-{suffix}",
        },
        admin_token,
    )
    dept_pid = record_pid(dept)
    if not dept_pid:
        raise SystemExit(command_failure("create department", dept))

    pos = execute_command(
        base_url,
        "org:create_position",
        {
            "org_pos_name": f"QB Smoke Position {suffix}",
            "org_pos_code": f"QB-SMOKE-POS-{suffix}",
            "org_pos_level": "staff",
            "org_pos_dept_id": dept_pid,
            "org_pos_status": "active",
        },
        admin_token,
    )
    pos_pid = record_pid(pos)
    if not pos_pid:
        raise SystemExit(command_failure("create position", pos))

    employee_name = f"QB Smoke Employee {suffix}"
    employee_email = f"qb-smoke-{suffix}@example.test"
    emp = execute_command(
        base_url,
        "org:create_employee",
        {
            "org_emp_name": employee_name,
            "org_emp_email": employee_email,
            "org_emp_phone": f"139{suffix}",
            "org_emp_dept_id": dept_pid,
            "org_emp_position_id": pos_pid,
            "org_emp_type": "full_time",
        },
        admin_token,
    )
    emp_pid = record_pid(emp)
    if not emp_pid:
        raise SystemExit(command_failure("create employee", emp))

    sales_provision_denied = execute_command(
        base_url,
        "admin:provision_member_from_employee",
        {"employeePid": emp_pid},
        sales_token,
    )

    provision = execute_command(
        base_url,
        "admin:provision_member_from_employee",
        {"employeePid": emp_pid},
        admin_token,
    )
    provision_data = command_data(provision)
    provision_password = provision_data.get("tempPassword")
    if not provision_password:
        raise SystemExit(
            f"provision did not return temp password status={provision.get('status')} code={provision.get('code')}"
        )

    identifier = provision_data.get("userName") or provision_data.get("email") or employee_email
    first_login = login(base_url, identifier, provision_password)

    reset = execute_command(
        base_url,
        "admin:reset_member_password",
        {},
        admin_token,
        target_record_pid=provision_data.get("memberPid"),
        operation_type="update",
    )
    reset_data = command_data(reset)
    reset_password = reset_data.get("tempPassword")
    if not reset_password:
        raise SystemExit(f"reset did not return temp password status={reset.get('status')} code={reset.get('code')}")

    old_password_login = login(base_url, identifier, provision_password)
    reset_password_login = login(base_url, identifier, reset_password)
    quote_menu = (
        request_json(base_url, "GET", "/api/menu/user", token=reset_password_login["token"])
        if reset_password_login["token"]
        else {"status": None, "code": None, "message": "login failed"}
    )

    policy_data = policy_admin.get("data") or {}
    policy_passed = (
        policy_admin.get("status") == 200
        and policy_admin.get("code") == "0"
        and policy_data.get("publicRegistrationEnabled") is False
        and policy_data.get("adminManagedPasswordEnabled") is True
        and policy_data.get("mustChangePasswordAfterAdminReset") is False
        and denied(policy_sales)
    )
    provisioning_passed = (
        dept.get("status") == 200
        and bool(dept_pid)
        and pos.get("status") == 200
        and bool(pos_pid)
        and emp.get("status") == 200
        and bool(emp_pid)
        and provision.get("status") == 200
        and provision_data.get("action") == "provision_member_from_employee"
        and provision_data.get("adminManaged") is True
        and isinstance(provision_data.get("memberPid"), str)
        and isinstance(provision_data.get("userPid"), str)
        and len(provision_password) >= 8
        and first_login["ok"] is True
        and first_login["mustChangePassword"] is False
    )
    reset_passed = (
        reset.get("status") == 200
        and reset_data.get("action") == "reset_password"
        and reset_data.get("adminManaged") is True
        and len(reset_password) >= 8
        and old_password_login["ok"] is False
        and reset_password_login["ok"] is True
        and reset_password_login["mustChangePassword"] is False
        and quote_menu.get("status") == 200
    )
    negative_passed = denied(sales_provision_denied)

    evidence = {
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "baseUrl": base_url,
        "passed": policy_passed and provisioning_passed and reset_passed and negative_passed,
        "accountsUsed": {
            "admin": {"userName": admin["userName"], "roles": admin.get("assignedRoles", [])},
            "sales": {"userName": sales["userName"], "roles": sales.get("assignedRoles", [])},
        },
        "accountSecurityPolicy": {
            "passed": policy_passed,
            "admin": {
                "status": policy_admin.get("status"),
                "code": policy_admin.get("code"),
                "publicRegistrationEnabled": policy_data.get("publicRegistrationEnabled"),
                "selfServicePasswordEnabled": policy_data.get("selfServicePasswordEnabled"),
                "adminManagedPasswordEnabled": policy_data.get("adminManagedPasswordEnabled"),
                "mustChangePasswordAfterAdminReset": policy_data.get("mustChangePasswordAfterAdminReset"),
                "password": policy_data.get("password"),
                "lockout": policy_data.get("lockout"),
            },
            "salesDenied": {
                "status": policy_sales.get("status"),
                "code": policy_sales.get("code"),
                "message": policy_sales.get("message"),
            },
        },
        "controlledProvisioning": {
            "passed": provisioning_passed,
            "createdDepartment": {"status": dept.get("status"), "code": dept.get("code"), "recordPidPresent": bool(dept_pid)},
            "createdPosition": {"status": pos.get("status"), "code": pos.get("code"), "recordPidPresent": bool(pos_pid)},
            "createdEmployee": {
                "status": emp.get("status"),
                "code": emp.get("code"),
                "recordPidPresent": bool(emp_pid),
                "employeeName": employee_name,
                "employeeEmail": employee_email,
            },
            "provisionFromEmployee": {
                "status": provision.get("status"),
                "code": provision.get("code"),
                "action": provision_data.get("action"),
                "employeePid": provision_data.get("employeePid"),
                "userPid": provision_data.get("userPid"),
                "memberPid": provision_data.get("memberPid"),
                "email": provision_data.get("email"),
                "userName": provision_data.get("userName"),
                "displayName": provision_data.get("displayName"),
                "createdUser": provision_data.get("createdUser"),
                "createdMember": provision_data.get("createdMember"),
                "adminManaged": provision_data.get("adminManaged"),
                "assignedRoles": provision_data.get("assignedRoles"),
                "tempPasswordReturned": isinstance(provision_password, str),
                "tempPasswordLength": len(provision_password),
            },
            "firstLogin": {
                "status": first_login["status"],
                "code": first_login["code"],
                "ok": first_login["ok"],
                "mustChangePassword": first_login["mustChangePassword"],
            },
        },
        "adminResetPassword": {
            "passed": reset_passed,
            "reset": {
                "status": reset.get("status"),
                "code": reset.get("code"),
                "action": reset_data.get("action"),
                "adminManaged": reset_data.get("adminManaged"),
                "tempPasswordReturned": isinstance(reset_password, str),
                "tempPasswordLength": len(reset_password),
            },
            "oldPasswordLoginAfterReset": {
                "status": old_password_login["status"],
                "code": old_password_login["code"],
                "ok": old_password_login["ok"],
                "message": old_password_login["message"],
            },
            "resetPasswordLogin": {
                "status": reset_password_login["status"],
                "code": reset_password_login["code"],
                "ok": reset_password_login["ok"],
                "mustChangePassword": reset_password_login["mustChangePassword"],
            },
            "quoteMenuApiWithResetUser": {"status": quote_menu.get("status"), "code": quote_menu.get("code")},
        },
        "negativeControls": {
            "passed": negative_passed,
            "salesProvisionDenied": {
                "status": sales_provision_denied.get("status"),
                "code": sales_provision_denied.get("code"),
                "message": sales_provision_denied.get("message"),
            },
        },
    }

    out_file = out_dir / f"account-org-password-smoke-{datetime.now(timezone.utc).strftime('%Y%m%d%H%M%S')}.json"
    out_file.write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding="utf-8")
    os.chmod(out_file, 0o600)
    print(
        json.dumps(
            {
                "passed": evidence["passed"],
                "accountPolicy": policy_passed,
                "provisioning": provisioning_passed,
                "reset": reset_passed,
                "negative": negative_passed,
                "outputPath": str(out_file),
            },
            ensure_ascii=False,
            indent=2,
        )
    )


if __name__ == "__main__":
    main()
