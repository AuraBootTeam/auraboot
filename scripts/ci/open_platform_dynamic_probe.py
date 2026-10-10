"""Dynamic publication business probe for the existing exact-image release gate.

Uses production administration and machine HTTP routes only. Importing never
contacts or provisions a target. A passed report requires every fixed case.
"""
import json
import subprocess
from pathlib import Path

from open_platform_http import ProtocolError

CASES = (
    "publication-snapshot", "resource-no-grant", "query-no-grant",
    "resource-row-field-projection", "hidden-record", "outside-create",
    "query-source-row-projection", "create", "idempotent-replay",
    "idempotent-conflict", "update-version", "stale-write",
    "prospective-row-denial", "delete", "grant-revocation", "public-openapi",
)


def verify_report(report):
    if not isinstance(report, dict) or report.get("schemaVersion") != 1:
        raise ProtocolError("dynamic probe report schema invalid")
    rows = report.get("cases")
    if not isinstance(rows, list) or len(rows) != len(CASES):
        raise ProtocolError("dynamic probe denominator missing or expanded")
    if [row.get("id") for row in rows if isinstance(row, dict)] != list(CASES):
        raise ProtocolError("dynamic probe case identity/order invalid")
    if any(row.get("status") != "passed" for row in rows):
        raise ProtocolError("dynamic probe has failed or unexecuted cases")
    if report.get("executed") != len(CASES) or report.get("passed") != len(CASES):
        raise ProtocolError("dynamic probe totals do not match fixed cases")
    return report


def verify_sdk_report(report):
    profile = json.loads((Path(__file__).resolve().parents[1] /
                          "fixtures/open-platform-consumer/real-backend.profile.json").read_text())
    if not isinstance(report, dict) or report.get("schemaVersion") != 1 or report.get("profile") != profile["id"]:
        raise ProtocolError("SDK real backend report identity invalid")
    rows = report.get("cases")
    if not isinstance(rows, list) or len(rows) != len(profile["cases"]):
        raise ProtocolError("SDK real backend denominator invalid")
    if [row.get("id") for row in rows if isinstance(row, dict)] != profile["cases"]:
        raise ProtocolError("SDK real backend case identity/order invalid")
    if any(row.get("status") != "passed" for row in rows):
        raise ProtocolError("SDK real backend cases failed or unexecuted")
    if report.get("executed") != len(rows) or report.get("passed") != len(rows):
        raise ProtocolError("SDK real backend totals invalid")
    if report.get("requests") != 17 or report.get("tokenExchanges") != 1:
        raise ProtocolError("SDK real transport/cache budget invalid")
    return report


def run_sdk_consumer(fixture, command, *, base_url, evidence_path):
    """Run the installed SDK with private stdin; never echo child diagnostics."""
    try:
        result = subprocess.run(command, input=json.dumps(dict(fixture, baseUrl=base_url)),
                                text=True, capture_output=True, timeout=90, check=False)
        report = json.loads(result.stdout)
    except (OSError, subprocess.TimeoutExpired, json.JSONDecodeError) as error:
        raise ProtocolError("SDK consumer transport failed") from None
    # Preserve only fixed, safe ledger fields, including a failed execution.
    profile = json.loads((Path(__file__).resolve().parents[1] /
                          "fixtures/open-platform-consumer/real-backend.profile.json").read_text())
    rows = report.get("cases") if isinstance(report, dict) else None
    statuses = {"passed", "failed", "did_not_run"}
    if (not isinstance(rows, list) or len(rows) != len(profile["cases"])
            or [row.get("id") for row in rows if isinstance(row, dict)] != profile["cases"]
            or any(row.get("status") not in statuses for row in rows)):
        raise ProtocolError("SDK consumer ledger malformed")
    safe = {key: report.get(key) for key in
            ["schemaVersion", "profile", "executed", "passed", "requests", "tokenExchanges"]}
    if (safe["schemaVersion"] != 1 or safe["profile"] != profile["id"]
            or any(type(safe[key]) is not int for key in ["executed", "passed", "requests", "tokenExchanges"])):
        raise ProtocolError("SDK consumer ledger metadata malformed")
    safe["cases"] = [{"id": row["id"], "status": row["status"]} for row in rows]
    Path(evidence_path).write_text(json.dumps(safe, indent=2) + "\n", encoding="utf-8")
    if result.returncode != 0:
        raise ProtocolError("SDK consumer failed")
    return verify_sdk_report(safe)


def verify_release_report(report):
    if not isinstance(report, dict):
        raise ProtocolError("release protocol report invalid")
    verify_report(report.get("dynamic"))
    verify_sdk_report(report.get("sdk"))
    return report


def run_dynamic_probe(request, admin, *, evidence_path=None, consumer=None):
    rows = [{"id": case, "status": "did_not_run"} for case in CASES]

    def record(case, condition=True):
        row = next(row for row in rows if row["id"] == case)
        if row["status"] != "did_not_run":
            raise ProtocolError("dynamic probe repeated case")
        row["status"] = "passed" if condition else "failed"
        if not condition:
            raise ProtocolError("dynamic probe assertion failed: " + case)

    def human(path, method="GET", body=None):
        _, result, _ = request(path, method=method, body=body, headers=admin)
        if not isinstance(result, dict) or str(result.get("code")) != "0":
            raise ProtocolError("dynamic probe administration failed")
        return result.get("data")

    def header(headers, name):
        return next((value for key, value in headers.items() if key.lower() == name.lower()), None)

    def field(source, writable=False, required=False):
        return {"source": source, "type": "string", "readable": True, "writable": writable,
                "required": required, "nullable": not required, "operators": [], "sortable": False,
                "referenceResource": None, "properties": {}, "items": None}

    resource = "release.dynamic.parts"
    query = "release.dynamic.by-zone"
    model_code = "release_dynamic_parts"
    named_code = "release_dynamic_by_zone"
    operations = ["list", "get", "create", "update", "delete"]
    scopes = {operation: "release.dynamic:" + operation for operation in operations}
    query_scope = "release.dynamic:query"
    report = {"schemaVersion": 1, "cases": rows, "executed": 0, "passed": 0}
    try:
        model = human("/api/meta/models", "POST", {
            "code": model_code, "tableName": model_code, "displayName": "Release dynamic parts",
            "modelType": "entity", "sourceType": "physical",
            "capabilities": {key: True for key in ["list", "detail", "create", "update", "delete", "filter", "sort", "paginate"]},
            "fields": [
                {"code": "rel_name", "columnName": "rel_name", "displayName": "Name", "dataType": "string", "required": True, "editable": True},
                {"code": "rel_zone", "columnName": "rel_zone", "displayName": "Zone", "dataType": "string", "required": True, "editable": True, "filterable": True},
                {"code": "rel_private", "columnName": "rel_private", "displayName": "Private note", "dataType": "string", "editable": True},
            ],
        })
        human(f"/api/meta/models/{model['pid']}/publish", "POST", {"impactAcknowledged": True})
        a = human(f"/api/dynamic/{model_code}/create", "POST", {"rel_name": "Allowed A", "rel_zone": "A", "rel_private": "Private A"})
        b = human(f"/api/dynamic/{model_code}/create", "POST", {"rel_name": "Hidden B", "rel_zone": "B", "rel_private": "Private B"})
        definition = {"code": resource, "modelCode": model_code, "schemaVersion": 1,
            "operations": operations, "scopes": scopes, "dataPolicy": "installation_filter",
            "fields": {"pid": dict(field("pid"), type="pid", nullable=False),
                "name": field("rel_name", True, True), "zone": dict(field("rel_zone", True, True), operators=["EQ"]),
                "secret": field("rel_private")},
            "maxPageSize": 50, "maxConditions": 8, "timeoutMs": 5000, "events": []}
        human(f"/api/open-platform/publications/{resource}/draft", "PUT", {"expectedRevision": 0, "definition": definition})
        published = human(f"/api/open-platform/publications/{resource}/publish", "POST", {"expectedRevision": 1})
        record("publication-snapshot", published.get("activeVersion") == 1)
        named = human("/api/meta/named-queries", "POST", {"code": named_code, "title": "Release zone query",
            "resourceCode": model_code, "actionCode": "read", "fromSql": f"{model['tableName']} a WHERE a.rel_zone = #{{params.zone}}",
            "defaultOrder": [{"field": "pid", "direction": "asc"}], "fields": [
                {"fieldCode": "pid", "columnExpr": "a.pid", "dataType": "string", "displayName": "PID", "sortable": True, "searchable": False},
                {"fieldCode": "partName", "columnExpr": "a.rel_name", "dataType": "string", "displayName": "Name", "sortable": False, "searchable": False}]})
        for status in ["testing", "published"]:
            human(f"/api/meta/named-queries/{named['pid']}/status", "PUT", {"status": status})
        source = human(f"/api/open-platform/query-sources/{named_code}")
        query_definition = {"code": query, "queryCode": named_code, "schemaVersion": 1,
            "sourceFingerprint": source["fingerprint"], "sourceResources": {model_code: resource}, "scopes": {"execute": query_scope},
            "parameters": {"zone": {"type": "string", "required": True, "nullable": False, "items": None}},
            "fields": {"pid": dict(field("pid"), type="pid", nullable=False), "name": field("partName")},
            "maxPageSize": 20, "maxRows": 100, "timeoutMs": 5000, "maxConcurrent": 2}
        human(f"/api/open-platform/query-publications/{query}/draft", "PUT", {"expectedRevision": 0, "definition": query_definition})
        human(f"/api/open-platform/query-publications/{query}/publish", "POST", {"expectedRevision": 1})
        application = human("/api/open-platform/applications", "POST", {"name": "Dynamic release consumer", "description": "Exact-image CI only"})
        installation = human(f"/api/open-platform/applications/{application['pid']}/installations", "POST",
            {"environment": "staging", "scopes": list(scopes.values()) + [query_scope], "rateLimitPerMinute": 600})
        credentials = human(f"/api/open-platform/installations/{installation['pid']}/credentials", "POST", {})
        _, token, _ = request("/oauth2/token", "POST", form={"grant_type": "client_credentials",
            "client_id": credentials["clientId"], "client_secret": credentials["clientSecret"]})
        machine = {"Authorization": "Bearer " + token["access_token"]}
        path = "/api/open/v1/resources/" + resource
        query_path = "/api/open/v1/queries/" + query + ":execute"
        request(path, headers=machine, expected=(403,)); record("resource-no-grant")
        request(query_path, "POST", body={"parameters": {"zone": "A"}, "limit": 10}, headers=machine, expected=(403,)); record("query-no-grant")
        grant_path = f"/api/open-platform/installations/{installation['pid']}/resources/{resource}"
        human(grant_path, "PUT", {"operations": operations, "readableFields": ["pid", "name", "zone"],
            "writableFields": ["name", "zone"], "rowConstraints": [{"field": "zone", "values": ["A"]}]})
        _, page, _ = request(path, headers=machine)
        record("resource-row-field-projection", page.get("items") == [{"pid": a["pid"], "name": "Allowed A", "zone": "A"}])
        request(path + "/" + b["pid"], headers=machine, expected=(404,)); record("hidden-record")
        request(path, "POST", body={"name": "Outside", "zone": "B"}, headers=dict(machine, **{"Idempotency-Key": "dynamic-outside-create"}), expected=(403,)); record("outside-create")
        query_grant_path = f"/api/open-platform/installations/{installation['pid']}/queries/{query}"
        human(query_grant_path, "PUT", {"readableFields": ["pid", "name"]})
        _, query_a, _ = request(query_path, "POST", body={"parameters": {"zone": "A"}, "limit": 10}, headers=machine)
        _, query_b, _ = request(query_path, "POST", body={"parameters": {"zone": "B"}, "limit": 10}, headers=machine)
        record("query-source-row-projection", query_a.get("items") == [{"pid": a["pid"], "name": "Allowed A"}] and query_b.get("items") == [])
        if consumer is not None:
            consumer({"clientId": credentials["clientId"], "clientSecret": credentials["clientSecret"],
                      "scopes": list(scopes.values()) + [query_scope], "resourceCode": resource,
                      "queryCode": query, "allowedPid": a["pid"], "hiddenPid": b["pid"]})
        intent = {"name": "Machine created", "zone": "A"}
        create_headers = dict(machine, **{"Idempotency-Key": "dynamic-release-create"})
        _, created, created_headers = request(path, "POST", body=intent, headers=create_headers, expected=(201,))
        etag = header(created_headers, "ETag")
        record("create", isinstance(created.get("pid"), str) and created == dict(intent, pid=created["pid"]) and isinstance(etag, str) and etag.startswith('"'))
        _, replay, replay_headers = request(path, "POST", body=intent, headers=create_headers, expected=(201,))
        record("idempotent-replay", replay == created and header(replay_headers, "Idempotent-Replay") == "true")
        request(path, "POST", body=dict(intent, name="Conflicting intent"), headers=create_headers, expected=(409,)); record("idempotent-conflict")
        row_path = path + "/" + created["pid"]
        _, updated, updated_headers = request(row_path, "PATCH", body={"name": "Machine updated"},
            headers=dict(machine, **{"Idempotency-Key": "dynamic-release-update", "If-Match": etag}))
        next_etag = header(updated_headers, "ETag")
        record("update-version", updated == dict(intent, pid=created["pid"], name="Machine updated") and isinstance(next_etag, str) and next_etag != etag)
        request(row_path, "PATCH", body={"name": "Stale"}, headers=dict(machine, **{"Idempotency-Key": "dynamic-release-stale", "If-Match": etag}), expected=(412,))
        _, persisted, persisted_headers = request(row_path, headers=machine)
        record("stale-write", persisted == updated and header(persisted_headers, "ETag") == next_etag)
        request(row_path, "PATCH", body={"zone": "B"}, headers=dict(machine, **{"Idempotency-Key": "dynamic-release-zone", "If-Match": next_etag}), expected=(403,))
        _, persisted, persisted_headers = request(row_path, headers=machine)
        record("prospective-row-denial", persisted == updated and header(persisted_headers, "ETag") == next_etag)
        request(row_path, "DELETE", headers=dict(machine, **{"Idempotency-Key": "dynamic-release-delete", "If-Match": next_etag}), expected=(204,))
        request(row_path, headers=machine, expected=(404,)); record("delete")
        human(query_grant_path, "DELETE")
        request(query_path, "POST", body={"parameters": {"zone": "A"}, "limit": 10}, headers=machine, expected=(403,))
        human(grant_path, "DELETE")
        request(path, headers=machine, expected=(403,)); record("grant-revocation")
        _, document, _ = request(f"/api/open-platform/publications/{resource}/openapi", headers=admin)
        record("public-openapi", document.get("openapi") == "3.1.0" and path in document.get("paths", {})
               and model_code not in json.dumps(document) and "rel_private" not in json.dumps(document))
    finally:
        report["executed"] = sum(row["status"] != "did_not_run" for row in rows)
        report["passed"] = sum(row["status"] == "passed" for row in rows)
        if evidence_path is not None:
            Path(evidence_path).write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    return verify_report(report)


if __name__ == "__main__":
    import sys
    if len(sys.argv) != 3 or sys.argv[1] != "--verify-release-report":
        raise SystemExit("usage: open_platform_dynamic_probe.py --verify-release-report REPORT")
    verify_release_report(json.loads(Path(sys.argv[2]).read_text(encoding="utf-8")))
