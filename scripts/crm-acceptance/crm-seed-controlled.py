#!/usr/bin/env python3
"""Seed remaining CRM records; tolerant of already-exists failures."""
import json
import subprocess
import urllib.request
import urllib.error

urllib.request.install_opener(urllib.request.build_opener(urllib.request.ProxyHandler({})))
BE = "http://127.0.0.1:6420"

def post(path, payload, jwt=None):
    headers = {"Content-Type": "application/json"}
    if jwt:
        headers["Authorization"] = "Bearer " + jwt
    req = urllib.request.Request(BE + path, data=json.dumps(payload).encode(), headers=headers, method="POST")
    print("POST", req.full_url)
    try:
        d = json.loads(urllib.request.urlopen(req, timeout=30).read())
        print("  ->", d.get("code"), str(d.get("message"))[:80])
        return d
    except urllib.error.HTTPError as e:
        print("  -> HTTP", e.code, e.read().decode(errors="replace")[:400])
        return {"code": str(e.code)}

jwt = post("/api/auth/login", {"email": "admin@auraboot.com", "password": "Test2026x"})["data"]["jwt"]

out = subprocess.run(
    ["sudo", "-u", "postgres", "psql", "-d", "auraboot_105", "-Atc",
     "SELECT pid FROM mt_crm_account_common WHERE crm_acc_name='华东智造集团' LIMIT 1"],
    capture_output=True, text=True).stdout.strip()
acct_pid = out.split("\n")[0] if out else ""
print("acct_pid:", acct_pid)

post("/api/meta/commands/execute/crm:create_contact", {"payload": {
    "crm_ct_account_id": acct_pid, "crm_ct_name": "王建国", "crm_ct_title": "采购总监",
    "crm_ct_email": "wangjianguo@example.cn", "crm_ct_phone": "138-0000-1001"}}, jwt)

post("/api/meta/commands/execute/crm:create_complaint", {"payload": {
    "crm_cmp_code": "CMP-2026-001", "crm_cmp_account_id": acct_pid,
    "crm_cmp_date": "2026-09-08T00:00:00+08:00", "crm_cmp_type": "quality",
    "crm_cmp_severity": "high",
    "crm_cmp_description": "首批到货 3 件精密部件外包装破损,需质量部门复核并出具结论。"}}, jwt)

post("/api/meta/commands/execute/crm:create_opportunity", {"payload": {
    "crm_opp_code": "OPP-EAST-001", "crm_opp_name": "华东智造 MES 一期建设项目",
    "crm_opp_expected_amount": 1280000,
    "crm_opp_expected_close_date": "2026-12-31T23:59:59+08:00"}}, jwt)
