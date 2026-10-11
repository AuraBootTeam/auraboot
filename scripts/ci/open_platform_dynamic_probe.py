"""Dynamic publication business probe for the existing exact-image release gate.

Uses production administration and machine HTTP routes only. Importing never
contacts or provisions a target. A passed report requires every fixed case.
"""
import json
import os
import uuid
import subprocess
import re
import time
from pathlib import Path

from open_platform_http import ProtocolError
from open_platform_model_event_probe import read_secret, verify_report as verify_model_event_report

CASES = (
    "plugin-resource-import", "publication-snapshot", "plugin-query-import", "resource-no-grant", "query-no-grant",
    "resource-row-field-projection", "hidden-record", "outside-create",
    "query-source-row-projection", "create", "idempotent-replay",
    "idempotent-conflict", "update-version", "stale-write",
    "prospective-row-denial", "delete", "grant-revocation", "public-openapi",
)
AUDIT_CASES = (
    ("resource-row-field-projection", "list", 200, None),
    ("query-source-row-projection", "execute", 200, None),
    ("create", "create", 201, False), ("idempotent-replay", "create", 201, True),
    ("idempotent-conflict", "create", 409, None), ("update-version", "update", 200, False),
    ("stale-write", "update", 412, None), ("prospective-row-denial", "update", 403, None),
    ("delete", "delete", 204, False),
)

# Fixed declaration semantics, authored with NamedQueryDefinitionFingerprint
# from the qualified JAR before importing. Fresh plugin sources have version 0;
# baseWhere is explicitly empty and fields have no operator whitelist.
PLUGIN_QUERY_SOURCE_FINGERPRINT = "b4e13ecf68aee621f9eb099595310b5841310b056748282bf506bcb459906d22"


def verify_named_query_absent(body, code):
    if (not isinstance(body, dict) or body.get("code") != "40000"
            or body.get("message") != "Business error"
            or body.get("context") != "Named query not found: " + code):
        raise ProtocolError("declared query source was not absent before import")


COMMAND_AUDIT_CASES = (
    ("asset-assign", "assets.assign", "assets", 200, False),
    ("asset-stale", "assets.assign", "assets", 412, None),
    ("inventory-confirm", "inventory.stock-ins.confirm", "inventory.stock-ins", 200, False),
)


def verify_command_audits(report):
    if not isinstance(report, dict): raise ProtocolError("command audit report missing")
    rows = report.get("auditProofs")
    if not isinstance(rows, list) or len(rows) != len(COMMAND_AUDIT_CASES):
        raise ProtocolError("command audit denominator invalid")
    if [row.get("id") for row in rows if isinstance(row, dict)] != [case[0] for case in COMMAND_AUDIT_CASES]:
        raise ProtocolError("command audit identity/order invalid")
    for key in ["applicationPid", "installationPid", "assetPid", "stockInPid"]:
        value = report.get(key)
        if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", value) or value.isdigit():
            raise ProtocolError("command audit public identity invalid")
    fields = {"id", "requestId", "method", "path", "status", "durationMs", "occurredAt", "actorKind",
              "applicationPid", "installationPid", "capability", "resource", "operation", "recordPid", "idempotentReplay"}
    for row, (case, command, resource, status, replay) in zip(rows, COMMAND_AUDIT_CASES):
        if (set(row) - fields or row.get("actorKind") != "machine"
                or any(row.get(key) != report[key] for key in ["applicationPid", "installationPid"])
                or row.get("requestId") != "release-command-" + case or row.get("capability") != command
                or row.get("resource") != resource or row.get("operation") != "execute"
                or row.get("recordPid") != report["assetPid" if resource == "assets" else "stockInPid"]
                or row.get("method") != "POST" or row.get("path") != "/api/open/v1/commands/" + command + ":execute"
                or type(row.get("status")) is not int or row["status"] != status
                or row.get("idempotentReplay") is not replay
                or type(row.get("durationMs")) is not int or row["durationMs"] < 0
                or not isinstance(row.get("occurredAt"), str) or not row["occurredAt"]):
            raise ProtocolError("command audit public identity/result invalid")
    return report


def audited_command(request, admin, report, case, path, *, body, headers, expected=(200,)):
    """Join an existing command request to its production installation audit."""
    request_id = "release-command-" + case
    result = request(path, method="POST", body=body, headers=dict(headers, **{"X-Request-Id": request_id}), expected=expected)
    deadline = time.monotonic() + 5
    while True:
        _, response, _ = request(f"/api/open-platform/installations/{report['installationPid']}/audits?requestId={request_id}", headers=admin)
        from open_platform_http import data
        audits = data(response)
        if audits or time.monotonic() >= deadline: break
        time.sleep(.1)
    if not isinstance(audits, list) or len(audits) != 1:
        raise ProtocolError("command business audit missing or duplicated")
    report["auditProofs"].append(dict(audits[0], id=case))
    return result


def verify_audits(report):
    proofs = report.get("auditProofs")
    if (not isinstance(proofs, list) or len(proofs) != len(AUDIT_CASES)
            or [row.get("id") for row in proofs if isinstance(row, dict)] != [row[0] for row in AUDIT_CASES]):
        raise ProtocolError("dynamic business audit denominator invalid")
    for key in ["applicationPid", "installationPid"]:
        value = report.get(key)
        if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", value) or value.isdigit():
            raise ProtocolError("dynamic business audit actor identity missing")
    created_pid = proofs[2].get("recordPid")
    if not isinstance(created_pid, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", created_pid) or created_pid.isdigit():
        raise ProtocolError("dynamic business audit created PID missing")
    fields = {"id", "requestId", "method", "path", "status", "durationMs", "occurredAt", "actorKind",
              "applicationPid", "installationPid", "capability", "resource", "operation", "recordPid", "idempotentReplay"}
    for proof, (case, operation, status, replay) in zip(proofs, AUDIT_CASES):
        resource = "release.dynamic.by-zone" if operation == "execute" else "release.dynamic.parts"
        pid = created_pid if case in ["create", "idempotent-replay", "update-version", "stale-write", "prospective-row-denial", "delete"] else None
        method = {"list": "GET", "execute": "POST", "create": "POST", "update": "PATCH", "delete": "DELETE"}[operation]
        path = "/api/open/v1/queries/" + resource + ":execute" if operation == "execute" else "/api/open/v1/resources/" + resource
        if operation in ["update", "delete"]: path += "/" + created_pid
        if (set(proof) - fields or proof.get("actorKind") != "machine"
                or any(proof.get(key) != report[key] for key in ["applicationPid", "installationPid"])
                or proof.get("requestId") != "release-dynamic-" + case or proof.get("resource") != resource
                or proof.get("operation") != operation or proof.get("recordPid") != pid
                or proof.get("capability") != ("query." if operation == "execute" else "resource.") + resource + "." + operation
                or proof.get("idempotentReplay") is not replay or type(proof.get("status")) is not int or proof["status"] != status
                or proof.get("method") != method or proof.get("path") != path
                or type(proof.get("durationMs")) is not int or proof["durationMs"] < 0
                or not isinstance(proof.get("occurredAt"), str) or not proof["occurredAt"]):
            raise ProtocolError("dynamic business audit identity/result invalid")


def verify_plugin_import(proof, plugin_id, resource_type):
    if (not isinstance(proof, dict) or proof.get("pluginId") != plugin_id
            or proof.get("resourceType") != resource_type or proof.get("success") is not True
            or type(proof.get("created")) is not int or proof["created"] != 1
            or any(not isinstance(proof.get(key), str)
                   or not re.fullmatch(r"[A-Za-z0-9_-]{1,100}", proof[key])
                   for key in ["importId", "pluginPid"])):
        raise ProtocolError("public plugin import proof invalid")
    if resource_type == "PUBLIC_QUERY" and (
            type(proof.get("namedQueryCreated")) is not int or proof["namedQueryCreated"] != 1
            or not isinstance(proof.get("namedQueryPid"), str)
            or not re.fullmatch(r"[A-Za-z0-9_-]{1,100}", proof["namedQueryPid"])
            or proof["namedQueryPid"].isdigit()):
        raise ProtocolError("public plugin new query creation proof invalid")


def import_public_plugin(request, admin, plugin_id, resource_type, definition, *, named_queries=None):
    """Drive production parse/execute; preserve returned import identity and counts."""
    from open_platform_http import data
    key = {"PUBLIC_RESOURCE": "publicResources", "PUBLIC_QUERY": "publicQueries"}[resource_type]
    manifest = {"pluginId": plugin_id, "namespace": plugin_id.replace("-", "_"),
                "displayName": "Release public contract", "version": "1.0.0", key: [definition]}
    if named_queries is not None:
        manifest["namedQueries"] = named_queries
    _, body, _ = request("/api/plugins/import/parse", method="POST", body=manifest, headers=admin)
    preview = data(body)
    if not isinstance(preview, dict) or preview.get("valid") is not True:
        raise ProtocolError("public plugin preview rejected")
    import_id = preview.get("importId")
    if not isinstance(import_id, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,100}", import_id):
        raise ProtocolError("public plugin import identity missing")
    _, body, _ = request(f"/api/plugins/import/{import_id}/execute", method="POST",
                         body={"conflictStrategy": "ERROR"}, headers=admin)
    result = data(body)
    if (not isinstance(result, dict) or result.get("success") is not True
            or result.get("importId") != import_id or result.get("pluginId") != plugin_id
            or result.get("resourceCounts", {}).get(resource_type) != {"CREATE": 1}):
        raise ProtocolError("public plugin execution identity or counts invalid")
    proof = {"pluginId": plugin_id, "importId": import_id, "pluginPid": result.get("pluginPid"),
             "resourceType": resource_type, "created": 1, "success": True}
    if resource_type == "PUBLIC_QUERY":
        created = result.get("createdResources", {}).get("NAMED_QUERY")
        if result.get("resourceCounts", {}).get("NAMED_QUERY") != {"CREATE": 1} or not isinstance(created, list) or len(created) != 1:
            raise ProtocolError("public plugin new query source missing")
        proof.update(namedQueryCreated=1, namedQueryPid=created[0])
    verify_plugin_import(proof, plugin_id, resource_type)
    return proof


def verify_plugin_query_source(proof, imported):
    keys = {"beforeStatus", "queryCode", "sourcePid", "sourceStatus", "sourceVersion", "fingerprint", "importId", "pluginPid"}
    if (not isinstance(proof, dict) or set(proof) != keys
            or type(proof.get("beforeStatus")) is not int or proof["beforeStatus"] != 400
            or proof.get("queryCode") != "release_dynamic_by_zone"
            or proof.get("sourcePid") != imported.get("namedQueryPid")
            or proof.get("sourceStatus") != "published"
            or type(proof.get("sourceVersion")) is not int or proof["sourceVersion"] != 0
            or proof.get("fingerprint") != PLUGIN_QUERY_SOURCE_FINGERPRINT
            or any(proof.get(key) != imported[key] for key in ["importId", "pluginPid"])):
        raise ProtocolError("public plugin new query source proof invalid")
    return proof



def verify_claimed_query_rollback(proof, imported, source):
    keys = {"queryCode", "importId", "pluginPid", "beforeOwnership", "ownershipType", "userModified",
            "rollbackStatus", "errorCode", "errorDetail", "activeVersion", "sourcePid", "fingerprint"}
    if (not isinstance(proof, dict) or set(proof) != keys
            or proof.get("queryCode") != "release.dynamic.by-zone"
            or any(proof.get(key) != imported[key] for key in ["importId", "pluginPid"])
            or proof.get("beforeOwnership") != "SHARED" or proof.get("ownershipType") != "USER_CLAIMED"
            or proof.get("userModified") is not True
            or type(proof.get("rollbackStatus")) is not int or proof["rollbackStatus"] != 422
            or proof.get("errorCode") != "40000"
            or proof.get("errorDetail") != "Rollback failed: Public publication is claimed or modified: release.dynamic.by-zone"
            or type(proof.get("activeVersion")) is not int or proof["activeVersion"] != 1
            or proof.get("sourcePid") != source["sourcePid"]
            or proof.get("fingerprint") != PLUGIN_QUERY_SOURCE_FINGERPRINT):
        raise ProtocolError("claimed public query rollback protection invalid")
    return proof


def run_claimed_query_rollback(request, admin, imported, source):
    from open_platform_http import data
    query = "release.dynamic.by-zone"
    suffix = "?resourceType=PUBLIC_QUERY&resourceCode=" + query
    def get(path):
        _, body, _ = request(path, headers=admin)
        return data(body)
    before = get("/api/plugins/resources/ownership" + suffix)
    if before.get("pluginPid") != imported["pluginPid"] or before.get("userModified") is not False:
        raise ProtocolError("query ownership baseline invalid")
    request("/api/plugins/resources/claim" + suffix, method="POST", headers=admin)
    status, error, _ = request("/api/plugins/import/" + imported["importId"] + "/rollback",
                               method="POST", headers=admin, expected=(422,))
    context = error.get("context") if isinstance(error, dict) else None
    detail = context.get("detail") if isinstance(context, dict) else context
    expected = "Rollback failed: Public publication is claimed or modified: " + query
    if detail != expected and (not isinstance(error, dict) or error.get("message") != expected):
        raise ProtocolError("claimed query rollback denial reason invalid")
    after = get("/api/plugins/resources/ownership" + suffix)
    views = [row for row in get("/api/open-platform/query-publications") if row.get("code") == query]
    named = get("/api/meta/named-queries/by-code/release_dynamic_by_zone")
    current_source = get("/api/open-platform/query-sources/release_dynamic_by_zone")
    if (after.get("pluginPid") != imported["pluginPid"] or len(views) != 1
            or views[0].get("status") != "published" or named.get("status") != "published"
            or named.get("currentVersion") != 0):
        raise ProtocolError("claimed query rollback changed source or publication")
    proof = {"queryCode": query, "importId": imported["importId"], "pluginPid": imported["pluginPid"],
             "beforeOwnership": before.get("ownershipType"), "ownershipType": after.get("ownershipType"),
             "userModified": after.get("userModified"), "rollbackStatus": status, "errorCode": error.get("code"),
             "errorDetail": expected, "activeVersion": views[0].get("activeVersion"), "sourcePid": named.get("pid"),
             "fingerprint": current_source.get("fingerprint")}
    return verify_claimed_query_rollback(proof, imported, source)


def verify_plugin_metadata_rollback(proof, imported):
    keys = {"pluginId", "pluginPid", "initialImportId", "upgradeImportId", "rollbackImportId",
            "before", "upgraded", "restored", "upgradedActiveVersion", "restoredActiveVersion"}
    before = {"pluginId": "release-public-resource", "namespace": "release_public_resource",
              "version": "1.0.0", "displayName": "Release public contract", "description": None}
    upgraded = dict(before, version="2.0.0", displayName="Release upgraded contract", description="Release upgrade description")
    if (not isinstance(proof, dict) or set(proof) != keys
            or proof.get("pluginId") != imported["pluginId"] or proof.get("pluginPid") != imported["pluginPid"]
            or proof.get("initialImportId") != imported["importId"]
            or not isinstance(proof.get("upgradeImportId"), str)
            or not re.fullmatch(r"[A-Za-z0-9_-]{1,100}", proof["upgradeImportId"])
            or proof["upgradeImportId"] == imported["importId"]
            or proof.get("rollbackImportId") != proof["upgradeImportId"]
            or proof.get("before") != before or proof.get("upgraded") != upgraded or proof.get("restored") != before
            or type(proof.get("upgradedActiveVersion")) is not int or proof["upgradedActiveVersion"] != 2
            or type(proof.get("restoredActiveVersion")) is not int or proof["restoredActiveVersion"] != 1):
        raise ProtocolError("public plugin metadata rollback proof invalid")
    return proof


def run_public_plugin_metadata_rollback(request, admin, imported, definition):
    """Upgrade and roll back through production APIs; read both persisted identities."""
    from open_platform_http import data
    def call(path, method="GET", body=None):
        _, response, _ = request(path, method=method, body=body, headers=admin)
        return data(response)
    def metadata():
        current = call("/api/plugins/" + imported["pluginPid"])
        if not isinstance(current, dict) or current.get("pid") != imported["pluginPid"]:
            raise ProtocolError("public plugin metadata read identity invalid")
        return {key: current.get(key) for key in ["pluginId", "namespace", "version", "displayName", "description"]}
    def active_version():
        catalog = call("/api/open-platform/publications")
        matches = [row for row in catalog if row.get("code") == definition["code"]]
        if len(matches) != 1 or matches[0].get("status") != "published":
            raise ProtocolError("public plugin rollback publication identity invalid")
        return matches[0].get("activeVersion")
    before = metadata()
    manifest = dict(before, version="2.0.0", displayName="Release upgraded contract",
                    description="Release upgrade description", publicResources=[dict(definition, schemaVersion=2)])
    preview = call("/api/plugins/import/parse", "POST", manifest)
    if not isinstance(preview, dict) or preview.get("valid") is not True:
        raise ProtocolError("public plugin upgrade preview rejected")
    import_id = preview.get("importId")
    if not isinstance(import_id, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,100}", import_id):
        raise ProtocolError("public plugin upgrade identity invalid")
    executed = call(f"/api/plugins/import/{import_id}/execute", "POST", {"conflictStrategy": "OVERWRITE"})
    if (not isinstance(executed, dict) or executed.get("success") is not True
            or executed.get("importId") != import_id or executed.get("pluginId") != imported["pluginId"]
            or executed.get("pluginPid") != imported["pluginPid"]
            or executed.get("resourceCounts", {}).get("PUBLIC_RESOURCE") != {"UPDATE": 1}):
        raise ProtocolError("public plugin upgrade execution invalid")
    upgraded = metadata(); upgraded_version = active_version()
    rolled_back = call(f"/api/plugins/import/{import_id}/rollback", "POST")
    if (not isinstance(rolled_back, dict) or rolled_back.get("success") is not True
            or rolled_back.get("importId") != import_id or rolled_back.get("pluginId") != imported["pluginId"]):
        raise ProtocolError("public plugin metadata rollback response invalid")
    proof = {"pluginId": imported["pluginId"], "pluginPid": imported["pluginPid"],
             "initialImportId": imported["importId"], "upgradeImportId": import_id,
             "rollbackImportId": rolled_back["importId"], "before": before, "upgraded": upgraded,
             "restored": metadata(), "upgradedActiveVersion": upgraded_version, "restoredActiveVersion": active_version()}
    return verify_plugin_metadata_rollback(proof, imported)


def verify_replay_authority(proof, report):
    keys = {"resource", "recordPid", "original", "originalEtag", "originalLocation", "outside", "restored", "requests"}
    if not isinstance(proof, dict) or set(proof) != keys or proof["resource"] != "release.dynamic.parts":
        raise ProtocolError("replay authority proof missing or malformed")
    pid = proof["recordPid"]
    if not isinstance(pid, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", pid) or pid.isdigit():
        raise ProtocolError("replay authority PID invalid")
    created = next(row for row in report["auditProofs"] if row["id"] == "create")
    expected = {"pid": pid, "name": "Machine created", "zone": "A"}
    path = "/api/open/v1/resources/" + proof["resource"]
    location = path + "/" + pid
    if created["recordPid"] != pid or proof["original"] != expected or proof["originalLocation"] != location or not isinstance(proof["originalEtag"], str) or not re.fullmatch(r'"[^"\s]+"', proof["originalEtag"]):
        raise ProtocolError("replay authority original response invalid")
    if proof["outside"] != dict(expected, zone="B") or proof["restored"] != expected:
        raise ProtocolError("replay authority persisted row transition invalid")
    contracts = [("fields-denied", 403), ("fields-restored", 201), ("row-denied", 403), ("row-restored", 201), ("current", 200)]
    rows = proof["requests"]
    if not isinstance(rows, list) or len(rows) != len(contracts):
        raise ProtocolError("replay authority request denominator invalid")
    for row, (identifier, status) in zip(rows, contracts):
        marker = "release-dynamic-replay-authority-" + identifier
        if not isinstance(row, dict) or set(row) != {"id", "status", "requestId", "body", "etag", "location", "replay", "audit"} or row["id"] != identifier or type(row["status"]) is not int or row["status"] != status or row["requestId"] != marker:
            raise ProtocolError("replay authority response contract invalid")
        if status == 403:
            body = row["body"]
            if not isinstance(body, dict) or set(body) != {"code", "message", "data", "context", "timestamp"} or body["code"] != "403" or body["message"] != "Access forbidden" or body["data"] is not None or body["context"] != "Current resource or query authorization required" or type(body["timestamp"]) is not int or body["timestamp"] <= 0 or any(row[key] is not None for key in ["etag", "location", "replay"]):
                raise ProtocolError("replay authority denial leaks or loses its public contract")
        elif row["body"] != expected:
            raise ProtocolError("replay authority response projection invalid")
        elif status == 201:
            if row["etag"] != proof["originalEtag"] or row["location"] != location or row["replay"] != "true":
                raise ProtocolError("replay authority original headers not preserved")
        elif not isinstance(row["etag"], str) or not re.fullmatch(r'"[^"\s]+"', row["etag"]) or row["etag"] == proof["originalEtag"] or row["location"] is not None or row["replay"] is not None:
            raise ProtocolError("replay authority current version invalid")
        operation = "get" if status == 200 else "create"
        audit = row["audit"]
        expected_audit = {"requestId": marker, "actorKind": "machine", "applicationPid": report["applicationPid"], "installationPid": report["installationPid"],
            "resource": proof["resource"], "operation": operation, "recordPid": None if status == 403 else pid,
            "idempotentReplay": True if status == 201 else None, "status": status, "method": "GET" if status == 200 else "POST",
            "path": location if status == 200 else path, "capability": "resource." + proof["resource"] + "." + operation}
        if not isinstance(audit, dict) or set(audit) != set(expected_audit) | {"durationMs", "occurredAt"} or any(audit.get(key) != value for key, value in expected_audit.items()) or type(audit.get("status")) is not int or audit.get("idempotentReplay") is not expected_audit["idempotentReplay"] or type(audit.get("durationMs")) is not int or audit["durationMs"] < 0 or not isinstance(audit.get("occurredAt"), str) or not audit["occurredAt"]:
            raise ProtocolError("replay authority machine audit invalid")
    return proof


def run_replay_authority_probe(request, human, report, machine, grant_path, operations, model_code, created, headers):
    resource = "release.dynamic.parts"
    path = "/api/open/v1/resources/" + resource
    pid = created["pid"]
    def header(values, key):
        return next((value for name, value in values.items() if name.lower() == key.lower()), None)
    policy = {"operations": operations, "readableFields": ["pid", "name", "zone"], "writableFields": ["name", "zone"], "rowConstraints": [{"field": "zone", "values": ["A"]}]}
    rows = []
    def replay(identifier, status):
        marker = "release-dynamic-replay-authority-" + identifier
        method, route = ("GET", path + "/" + pid) if status == 200 else ("POST", path)
        request_headers = dict(machine, **{"X-Request-Id": marker})
        if status != 200: request_headers["Idempotency-Key"] = "dynamic-release-create"
        actual, body, result_headers = request(route, method, body=None if status == 200 else {"name": "Machine created", "zone": "A"}, headers=request_headers, expected=(status,))
        audits = human(f"/api/open-platform/installations/{report['installationPid']}/audits?requestId={marker}")
        if not isinstance(audits, list) or len(audits) != 1: raise ProtocolError("replay authority audit missing or duplicated")
        rows.append({"id": identifier, "status": actual, "requestId": header(result_headers, "X-Request-Id"), "body": body,
            "etag": header(result_headers, "ETag"), "location": header(result_headers, "Location"), "replay": header(result_headers, "Idempotent-Replay"), "audit": audits[0]})
    def snapshot(row):
        return {"pid": row.get("pid"), "name": row.get("rel_name"), "zone": row.get("rel_zone")}
    try:
        human(grant_path, "PUT", dict(policy, readableFields=["pid", "zone"]))
        replay("fields-denied", 403)
        human(grant_path, "PUT", policy)
        replay("fields-restored", 201)
        human(f"/api/dynamic/{model_code}/{pid}", "PUT", {"rel_zone": "B"})
        outside = snapshot(human(f"/api/dynamic/{model_code}/{pid}"))
        replay("row-denied", 403)
        human(f"/api/dynamic/{model_code}/{pid}", "PUT", {"rel_zone": "A"})
        restored = snapshot(human(f"/api/dynamic/{model_code}/{pid}"))
        replay("row-restored", 201)
        replay("current", 200)
    finally:
        human(grant_path, "PUT", policy)
    proof = {"resource": resource, "recordPid": pid, "original": created, "originalEtag": header(headers, "ETag"), "originalLocation": header(headers, "Location"), "outside": outside, "restored": restored, "requests": rows}
    return verify_replay_authority(proof, report)


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
    proofs = report.get("pluginImports")
    if not isinstance(proofs, list) or len(proofs) != 2:
        raise ProtocolError("public plugin import denominator missing")
    for proof, plugin, kind in zip(proofs, ["release-public-resource", "release-public-query"], ["PUBLIC_RESOURCE", "PUBLIC_QUERY"]):
        verify_plugin_import(proof, plugin, kind)
    verify_plugin_metadata_rollback(report.get("pluginMetadataRollback"), proofs[0])
    verify_plugin_query_source(report.get("pluginQuerySource"), proofs[1])
    verify_claimed_query_rollback(report.get("queryRollbackProtection"), proofs[1], report["pluginQuerySource"])
    verify_audits(report)
    verify_replay_authority(report.get("replayAuthority"), report)
    verify_source_binding(report.get("resourceSourceBinding"))
    virtual = verify_virtual_resource(report.get("virtualResource"))
    isolation = virtual["installationIsolation"]
    if (isolation["primaryInstallationPid"] != report.get("installationPid")
            or isolation["secondaryApplicationPid"] == report.get("applicationPid")):
        raise ProtocolError("virtual installation proof actor binding invalid")
    if any(row["audit"]["applicationPid"] != report.get("applicationPid") for kind in ["deadline", "executionFailure", "identityFailure", "nullIdentity"] for row in virtual[kind]["requests"]):
        raise ProtocolError("virtual deadline application binding invalid")
    quota = report.get("queryRateLimit")
    resource = "release.dynamic.by-zone.quota"
    request_id = "release-dynamic-query-quota"
    if (not isinstance(quota, dict) or set(quota) != {"status", "code", "retryAfter", "requestId", "audit"}
            or type(quota.get("status")) is not int or quota["status"] != 429
            or quota.get("code") != "open_api_query_rate_limited" or quota.get("retryAfter") != "60"
            or quota.get("requestId") != request_id):
        raise ProtocolError("dynamic query quota response proof invalid")
    audit = quota["audit"]
    expected = {"requestId": request_id, "actorKind": "machine", "applicationPid": report["applicationPid"],
                "installationPid": report["installationPid"], "resource": resource, "operation": "execute",
                "recordPid": None, "idempotentReplay": None, "status": 429, "method": "POST",
                "path": "/api/open/v1/queries/" + resource + ":execute", "capability": "query." + resource + ".execute"}
    if (not isinstance(audit, dict) or set(audit) != set(expected) | {"durationMs", "occurredAt"}
            or any(audit.get(key) != value for key, value in expected.items())
            or type(audit.get("status")) is not int or type(audit.get("durationMs")) is not int
            or audit["durationMs"] < 0 or not isinstance(audit.get("occurredAt"), str) or not audit["occurredAt"]):
        raise ProtocolError("dynamic query quota audit proof invalid")
    return report


def verify_source_binding(proof):
    fields = {"status", "code", "error", "requestId", "baseline", "baselineEtag", "restoredStatus", "restored", "restoredEtag"}
    baseline = proof.get("baseline") if isinstance(proof, dict) else None
    if (not isinstance(proof, dict) or set(proof) != fields or type(proof.get("status")) is not int
            or proof["status"] != 409 or proof.get("code") != "open_api_source_changed"
            or proof.get("error") != {"success": False, "code": "open_api_source_changed",
                                     "message": "Published resource source binding is unavailable or changed"}
            or proof.get("requestId") != "release-dynamic-source-binding"
            or not isinstance(baseline, dict) or set(baseline) != {"pid", "name"}
            or not isinstance(baseline.get("pid"), str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", baseline["pid"])
            or baseline["pid"].isdigit() or baseline.get("name") != "Storage binding original"
            or type(proof.get("restoredStatus")) is not int or proof["restoredStatus"] != 200
            or proof.get("restored") != baseline or not isinstance(proof.get("baselineEtag"), str)
            or not proof["baselineEtag"].startswith('"') or not proof["baselineEtag"].endswith('"')
            or len(proof["baselineEtag"]) < 12 or proof.get("restoredEtag") != proof["baselineEtag"]):
        raise ProtocolError("dynamic resource source binding proof invalid")
    return proof


def verify_virtual_resource(proof):
    keys = {"resource", "sourceResources", "allowedPid", "hiddenPid", "extraPid", "items", "detail", "etag",
            "stableEtag", "ownHiddenStatus", "sourceHiddenStatus", "pages", "fieldDeniedStatus", "revokedStatus", "installationIsolation", "deadline", "executionFailure", "identityFailure", "nullIdentity"}
    if not isinstance(proof, dict) or set(proof) != keys:
        raise ProtocolError("virtual resource proof missing or malformed")
    identities = [proof.get(key) for key in ["allowedPid", "hiddenPid", "extraPid"]]
    if any(not isinstance(pid, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,100}", pid) for pid in identities) or len(set(identities)) != 3:
        raise ProtocolError("virtual resource fixture identities invalid")
    expected = {"pid": identities[0], "name": "Allowed A", "zone": "A"}
    if (proof["resource"] != "release.dynamic.virtual-parts"
            or proof["sourceResources"] != {"release_dynamic_parts": "release.dynamic.parts"}
            or proof["items"] != [expected] or proof["detail"] != expected
            or not isinstance(proof["etag"], str) or not re.fullmatch(r'"[a-f0-9]{64}"', proof["etag"])
            or proof["stableEtag"] != proof["etag"]):
        raise ProtocolError("virtual resource projection or ETag invalid")
    for key, expected_status in [("ownHiddenStatus", 404), ("sourceHiddenStatus", 404), ("fieldDeniedStatus", 403), ("revokedStatus", 403)]:
        if type(proof[key]) is not int or proof[key] != expected_status:
            raise ProtocolError("virtual resource denial invalid: " + key)
    pages = proof["pages"]
    if (not isinstance(pages, list) or len(pages) != 2
            or any(not isinstance(page, dict) or set(page) != {"items", "nextCursor"} for page in pages)
            or any(not isinstance(page["items"], list) or len(page["items"]) != 1 or not isinstance(page["items"][0], dict) for page in pages)
            or {page["items"][0].get("pid") for page in pages} != {identities[0], identities[2]}
            or not isinstance(pages[0]["nextCursor"], str) or not pages[0]["nextCursor"] or pages[1]["nextCursor"] is not None):
        raise ProtocolError("virtual resource keyset proof invalid")
    expected_rows = {identities[0]: expected, identities[2]: {"pid": identities[2], "name": "Virtual extra A", "zone": "A"}}
    if any(page["items"][0] != expected_rows[page["items"][0]["pid"]] for page in pages):
        raise ProtocolError("virtual resource keyset projection invalid")
    isolation = proof["installationIsolation"]
    isolation_keys = {"primaryInstallationPid", "secondaryInstallationPid", "secondaryApplicationPid",
                      "secondaryItems", "secondaryDetail", "firstReadsSecondStatus", "secondReadsFirstStatus", "cursorStatus"}
    if not isinstance(isolation, dict) or set(isolation) != isolation_keys:
        raise ProtocolError("virtual installation isolation proof missing or malformed")
    for key in ["primaryInstallationPid", "secondaryInstallationPid", "secondaryApplicationPid"]:
        if not isinstance(isolation[key], str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,100}", isolation[key]):
            raise ProtocolError("virtual installation identity invalid: " + key)
    if isolation["primaryInstallationPid"] == isolation["secondaryInstallationPid"]:
        raise ProtocolError("virtual installation identities must differ")
    secondary_row = {"pid": identities[1], "name": "Hidden B", "zone": "B"}
    if isolation["secondaryItems"] != [secondary_row] or isolation["secondaryDetail"] != secondary_row:
        raise ProtocolError("virtual secondary installation projection invalid")
    for key, status in [("firstReadsSecondStatus", 404), ("secondReadsFirstStatus", 404), ("cursorStatus", 400)]:
        if type(isolation[key]) is not int or isolation[key] != status:
            raise ProtocolError("virtual installation isolation denial invalid: " + key)
    verify_virtual_deadline(proof["deadline"], proof)
    verify_virtual_deadline(proof["executionFailure"], proof, failure_kind="executionFailure")
    verify_virtual_deadline(proof["identityFailure"], proof, failure_kind="identityFailure")
    verify_virtual_null_identity(proof["nullIdentity"], proof)
    return proof


def virtual_failure_contract(failure_kind):
    contracts = {
        "deadline": ("timeout", 504, "open_api_query_timeout", "Published query execution deadline exceeded"),
        "executionFailure": ("unavailable", 503, "open_api_query_unavailable", "Published query execution is unavailable"),
        "identityFailure": ("ambiguous", 503, "open_api_query_unavailable", "Published query execution is unavailable"),
    }
    if failure_kind not in contracts:
        raise ProtocolError("Unknown virtual failure contract")
    return contracts[failure_kind]


def verify_virtual_deadline(deadline, virtual, failure_kind="deadline"):
    suffix, expected_status, error_code, message = virtual_failure_contract(failure_kind)
    resource = "release.dynamic.virtual-" + suffix
    if not isinstance(deadline, dict) or set(deadline) != {"resource", "requests", "healthyItems"} or deadline["resource"] != resource:
        raise ProtocolError("virtual deadline proof missing or malformed")
    rows = deadline["requests"]
    if not isinstance(rows, list) or len(rows) != 2:
        raise ProtocolError("virtual deadline request denominator invalid")
    actor = virtual["installationIsolation"]["primaryInstallationPid"]
    for row, operation in zip(rows, ["list", "get"]):
        path = "/api/open/v1/resources/" + resource + ("/" + virtual["allowedPid"] if operation == "get" else "")
        marker = "release-virtual-" + suffix + "-" + operation
        query = "limit=1" if failure_kind == "identityFailure" and operation == "list" else ""
        if (not isinstance(row, dict) or set(row) != {"operation", "status", "requestId", "error", "audit", "query", "etag"}
                or row["query"] != query or row["etag"] is not None
                or row["operation"] != operation or type(row["status"]) is not int or row["status"] != expected_status
                or row["requestId"] != marker
                or row["error"] != {"success": False, "code": error_code, "message": message}):
            raise ProtocolError("virtual deadline response invalid")
        expected = {"requestId": marker, "actorKind": "machine", "installationPid": actor, "resource": resource,
                    "operation": operation, "recordPid": virtual["allowedPid"] if operation == "get" else None,
                    "idempotentReplay": None, "status": expected_status, "method": "GET", "path": path,
                    "capability": "resource." + resource + "." + operation}
        audit = row["audit"]
        if (not isinstance(audit, dict) or set(audit) != set(expected) | {"applicationPid", "durationMs", "occurredAt"}
                or any(audit.get(key) != value for key, value in expected.items())
                or type(audit.get("status")) is not int or type(audit.get("durationMs")) is not int or audit["durationMs"] < 0
                or not isinstance(audit.get("applicationPid"), str) or not audit["applicationPid"]
                or not isinstance(audit.get("occurredAt"), str) or not audit["occurredAt"]):
            raise ProtocolError("virtual deadline audit invalid")
    expected_rows = [{"pid": virtual["allowedPid"], "name": "Allowed A", "zone": "A"},
                     {"pid": virtual["extraPid"], "name": "Virtual extra A", "zone": "A"}]
    if (not isinstance(deadline["healthyItems"], list) or any(not isinstance(row, dict) for row in deadline["healthyItems"])
            or sorted(deadline["healthyItems"], key=lambda row: str(row.get("pid", ""))) != sorted(expected_rows, key=lambda row: row["pid"])):
        raise ProtocolError("virtual deadline healthy recovery invalid")
    return deadline


def run_virtual_deadline_probe(request, human, installation_pid, machine, definition, allowed, healthy_path, failure_kind="deadline"):
    """Require real SQL failure, persisted public audits and healthy reads on the same live target."""
    suffix, expected_status, _, _ = virtual_failure_contract(failure_kind)
    source = "release_dynamic_" + suffix + "_source"
    model_code = "release_dynamic_" + suffix + "_model"
    resource = "release.dynamic.virtual-" + suffix
    expression = {"deadline": "a.rel_name || pg_sleep(0.2)::text", "executionFailure": "a.rel_name || (1 / 0)::text",
                  "identityFailure": "a.rel_name"}[failure_kind]
    from_sql = "release_dynamic_parts a"
    if failure_kind == "identityFailure":
        from_sql += " JOIN release_dynamic_parts b ON a.rel_zone = b.rel_zone"
    named = human("/api/meta/named-queries", "POST", {"code": source, "title": "Release virtual SQL " + suffix,
        "resourceCode": "release_dynamic_parts", "actionCode": "read", "fromSql": from_sql,
        "policy": {"rateLimitPerMinute": 100000}, "baseWhere": [], "defaultOrder": [{"field": "pid", "direction": "asc"}],
        "fields": [{"fieldCode": alias, "columnExpr": expression, "dataType": "string", "displayName": alias,
                    "sortable": alias == "pid", "searchable": False}
                   for alias, expression in [("pid", "a.pid"), ("name", expression), ("zone", "a.rel_zone")]]})
    for status in ["testing", "published"]:
        human(f"/api/meta/named-queries/{named['pid']}/status", "PUT", {"status": status})
    model = human("/api/meta/models", "POST", {"code": model_code, "displayName": "Release virtual " + suffix,
        "modelType": "view", "sourceType": "namedQuery", "sourceRef": source, "primaryKey": "pid",
        "capabilities": {key: True for key in ["list", "detail", "filter", "sort", "paginate"]},
        "fields": [{"code": alias, "displayName": alias, "dataType": "string", "filterable": True} for alias in ["name", "zone"]]})
    human(f"/api/meta/models/{model['pid']}/publish", "POST", {"impactAcknowledged": True})
    slow = dict(definition, code=resource, modelCode=model_code, timeoutMs=30 if failure_kind == "deadline" else 5000)
    human(f"/api/open-platform/publications/{resource}/draft", "PUT", {"expectedRevision": 0, "definition": slow})
    human(f"/api/open-platform/publications/{resource}/publish", "POST", {"expectedRevision": 1})
    human(f"/api/open-platform/installations/{installation_pid}/resources/{resource}", "PUT",
          {"operations": ["list", "get"], "readableFields": ["pid", "name", "zone"], "writableFields": [],
           "rowConstraints": [{"field": "zone", "values": ["A"]}]})
    rows = []
    for operation in ["list", "get"]:
        path = "/api/open/v1/resources/" + resource + ("/" + allowed["pid"] if operation == "get" else "")
        marker = "release-virtual-" + suffix + "-" + operation
        query = "limit=1" if failure_kind == "identityFailure" and operation == "list" else ""
        status, error, headers = request(path + ("?" + query if query else ""),
                                         headers=dict(machine, **{"X-Request-Id": marker}), expected=(expected_status,))
        response_marker = next((value for key, value in headers.items() if key.lower() == "x-request-id"), None)
        audits = human(f"/api/open-platform/installations/{installation_pid}/audits?requestId={marker}")
        if not isinstance(audits, list) or len(audits) != 1:
            raise ProtocolError("virtual deadline audit missing or duplicated")
        rows.append({"operation": operation, "status": status, "requestId": response_marker, "error": error, "audit": audits[0],
                     "query": query, "etag": next((v for k, v in headers.items() if k.lower() == "etag"), None)})
    _, healthy, _ = request(healthy_path, headers=machine)
    return {"resource": resource, "requests": rows, "healthyItems": healthy.get("items")}


def verify_virtual_null_identity(proof, virtual):
    keys = {"resource", "sourceResource", "validPid", "secondValidPid", "nullSourcePid", "sourceRows", "requests", "healthyItems"}
    if not isinstance(proof, dict) or set(proof) != keys or proof["resource"] != "release.dynamic.virtual-null-identity" or proof["sourceResource"] != "release.dynamic.null-parts":
        raise ProtocolError("virtual null identity proof missing or malformed")
    identities = [proof[key] for key in ["validPid", "secondValidPid", "nullSourcePid"]]
    if any(not isinstance(pid, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,100}", pid) for pid in identities) or len(set(identities)) != 3:
        raise ProtocolError("virtual null identity fixture invalid")
    # The admin query serializer omits map entries whose SQL value is NULL.
    expected_source = [{"pid": identities[0], "name": "Null identity valid one", "zone": "A"},
                       {"pid": identities[1], "name": "Null identity valid two", "zone": "A"},
                       {"name": "Null identity candidate", "zone": "A"}]
    if not isinstance(proof["sourceRows"], list) or len(proof["sourceRows"]) != 3 or any(not isinstance(row, dict) for row in proof["sourceRows"]) or sorted(proof["sourceRows"], key=lambda row: str(row.get("name", ""))) != sorted(expected_source, key=lambda row: row["name"]):
        raise ProtocolError("virtual null identity source result invalid")
    requests = proof["requests"]
    contracts = [("list-small", "list", "limit=1", 503, None), ("list-wide", "list", "limit=50", 503, None),
                 ("get-valid", "get", "", 200, identities[0]), ("get-null", "get", "", 404, identities[2])]
    if not isinstance(requests, list) or len(requests) != len(contracts):
        raise ProtocolError("virtual null identity request denominator invalid")
    for row, (identifier, operation, query, status, record_pid) in zip(requests, contracts):
        marker = "release-virtual-null-identity-" + identifier
        path = "/api/open/v1/resources/" + proof["resource"] + ("/" + record_pid if operation == "get" else "")
        body = expected_source[0] if status == 200 else {"success": False, "code": "open_api_query_unavailable" if status == 503 else "not_found",
                "message": "Published query execution is unavailable" if status == 503 else "Resource record is unavailable"}
        if not isinstance(row, dict) or set(row) != {"id", "operation", "query", "status", "requestId", "body", "etag", "audit"} or row["id"] != identifier or row["operation"] != operation or row["query"] != query or type(row["status"]) is not int or row["status"] != status or row["requestId"] != marker or row["body"] != body:
            raise ProtocolError("virtual null identity response invalid")
        if (status != 200 and row["etag"] is not None) or (status == 200 and (not isinstance(row["etag"], str) or not re.fullmatch(r'"[a-f0-9]{64}"', row["etag"]))):
            raise ProtocolError("virtual null identity ETag invalid")
        expected_audit = {"requestId": marker, "actorKind": "machine", "installationPid": virtual["installationIsolation"]["primaryInstallationPid"],
            "resource": proof["resource"], "operation": operation, "recordPid": record_pid, "idempotentReplay": None,
            "status": status, "method": "GET", "path": path, "capability": "resource." + proof["resource"] + "." + operation}
        audit = row["audit"]
        if not isinstance(audit, dict) or set(audit) != set(expected_audit) | {"applicationPid", "durationMs", "occurredAt"} or any(audit.get(key) != value for key, value in expected_audit.items()) or type(audit.get("status")) is not int or type(audit.get("durationMs")) is not int or audit["durationMs"] < 0 or not isinstance(audit.get("applicationPid"), str) or not audit["applicationPid"] or not isinstance(audit.get("occurredAt"), str) or not audit["occurredAt"]:
            raise ProtocolError("virtual null identity audit invalid")
    healthy = [{"pid": virtual["allowedPid"], "name": "Allowed A", "zone": "A"}, {"pid": virtual["extraPid"], "name": "Virtual extra A", "zone": "A"}]
    if not isinstance(proof["healthyItems"], list) or any(not isinstance(row, dict) for row in proof["healthyItems"]) or sorted(proof["healthyItems"], key=lambda row: str(row.get("pid", ""))) != sorted(healthy, key=lambda row: row["pid"]):
        raise ProtocolError("virtual null identity healthy recovery invalid")
    return proof


def run_virtual_null_identity_probe(request, human, installation_pid, machine, physical, virtual, healthy_path):
    model_code, source_code = "release_dynamic_null_parts", "release.dynamic.null-parts"
    resource, query_code = "release.dynamic.virtual-null-identity", "release_dynamic_null_identity_source"
    model = human("/api/meta/models", "POST", {"code": model_code, "tableName": model_code, "displayName": "Release null identity source",
        "modelType": "entity", "sourceType": "physical",
        "capabilities": {key: True for key in ["list", "detail", "create", "update", "delete", "filter", "sort", "paginate"]},
        "fields": [{"code": code, "columnName": code, "displayName": code, "dataType": "string", "required": code != "rel_private", "editable": True, "filterable": code == "rel_zone"} for code in ["rel_name", "rel_zone", "rel_private"]]})
    human(f"/api/meta/models/{model['pid']}/publish", "POST", {"impactAcknowledged": True})
    if model.get("tableName") != model_code: raise ProtocolError("null identity physical source changed")
    created = [human(f"/api/dynamic/{model_code}/create", "POST", {"rel_name": name, "rel_zone": "A", "rel_private": "Private null identity fixture"})
               for name in ["Null identity valid one", "Null identity valid two", "Null identity candidate"]]
    pids = [row.get("pid") for row in created]
    if any(not isinstance(pid, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,100}", pid) for pid in pids) or len(set(pids)) != 3: raise ProtocolError("null identity created PID invalid")
    source_contract = dict(physical, code=source_code, modelCode=model_code)
    for code, contract in [(source_code, source_contract)]:
        human(f"/api/open-platform/publications/{code}/draft", "PUT", {"expectedRevision": 0, "definition": contract})
        human(f"/api/open-platform/publications/{code}/publish", "POST", {"expectedRevision": 1})
        human(f"/api/open-platform/installations/{installation_pid}/resources/{code}", "PUT", {"operations": ["list", "get"], "readableFields": ["pid", "name", "zone"], "writableFields": [], "rowConstraints": [{"field": "zone", "values": ["A"]}]})
    named = human("/api/meta/named-queries", "POST", {"code": query_code, "title": "Release null identity query", "resourceCode": model_code, "actionCode": "read", "fromSql": model_code + " a", "policy": {"rateLimitPerMinute": 100000}, "baseWhere": [], "defaultOrder": [{"field": "pid", "direction": "asc"}],
        "fields": [{"fieldCode": alias, "columnExpr": expr, "dataType": "string", "displayName": alias, "sortable": alias == "pid", "searchable": False}
                   for alias, expr in [("pid", "NULLIF(a.pid, '" + pids[2] + "')"), ("name", "a.rel_name"), ("zone", "a.rel_zone")]]})
    for status in ["testing", "published"]: human(f"/api/meta/named-queries/{named['pid']}/status", "PUT", {"status": status})
    source_result = human(f"/api/meta/named-queries/{query_code}/execute", "POST", {"page": 1, "size": 50})
    model = human("/api/meta/models", "POST", {"code": "release_dynamic_null_identity_virtual", "displayName": "Release null identity virtual resource", "modelType": "view", "sourceType": "namedQuery", "sourceRef": query_code, "primaryKey": "pid", "capabilities": {key: True for key in ["list", "detail", "filter", "sort", "paginate"]}, "fields": [{"code": alias, "displayName": alias, "dataType": "string", "filterable": True} for alias in ["name", "zone"]]})
    human(f"/api/meta/models/{model['pid']}/publish", "POST", {"impactAcknowledged": True})
    contract = dict(virtual, code=resource, modelCode=model['code'], sourceResources={model_code: source_code})
    human(f"/api/open-platform/publications/{resource}/draft", "PUT", {"expectedRevision": 0, "definition": contract})
    human(f"/api/open-platform/publications/{resource}/publish", "POST", {"expectedRevision": 1})
    human(f"/api/open-platform/installations/{installation_pid}/resources/{resource}", "PUT", {"operations": ["list", "get"], "readableFields": ["pid", "name", "zone"], "writableFields": [], "rowConstraints": [{"field": "zone", "values": ["A"]}]})
    rows = []
    for identifier, operation, query, status, pid in [("list-small", "list", "limit=1", 503, None), ("list-wide", "list", "limit=50", 503, None), ("get-valid", "get", "", 200, pids[0]), ("get-null", "get", "", 404, pids[2])]:
        route = "/api/open/v1/resources/" + resource + ("/" + pid if operation == "get" else "")
        marker = "release-virtual-null-identity-" + identifier
        actual, body, headers = request(route + ("?" + query if query else ""), headers=dict(machine, **{"X-Request-Id": marker}), expected=(status,))
        audits = human(f"/api/open-platform/installations/{installation_pid}/audits?requestId={marker}")
        if not isinstance(audits, list) or len(audits) != 1: raise ProtocolError("null identity audit missing or duplicated")
        rows.append({"id": identifier, "operation": operation, "query": query, "status": actual, "requestId": next((v for k,v in headers.items() if k.lower() == "x-request-id"), None), "body": body, "etag": next((v for k,v in headers.items() if k.lower() == "etag"), None), "audit": audits[0]})
    _, healthy, _ = request(healthy_path, headers=machine)
    return {"resource": resource, "sourceResource": source_code, "validPid": pids[0], "secondValidPid": pids[1], "nullSourcePid": pids[2], "sourceRows": source_result.get("records"), "requests": rows, "healthyItems": healthy.get("items")}


def run_virtual_resource_probe(request, human, installation_pid, machine, scopes, physical_definition, allowed, hidden):
    """Exercise the declared source grant and the virtual policy through production HTTP routes."""
    source_model = "release_dynamic_parts"
    code = "release.dynamic.virtual-parts"
    query_code = "release_dynamic_virtual_source"
    model_code = "release_dynamic_virtual_parts"
    named = human("/api/meta/named-queries", "POST", {"code": query_code, "title": "Release virtual parts source",
        "resourceCode": source_model, "actionCode": "read", "fromSql": source_model + " a",
        "policy": {"rateLimitPerMinute": 100000}, "baseWhere": [], "defaultOrder": [{"field": "pid", "direction": "asc"}],
        "fields": [{"fieldCode": alias, "columnExpr": "a." + column, "dataType": "string", "displayName": alias,
                    "sortable": alias == "pid", "searchable": False}
                   for alias, column in [("pid", "pid"), ("name", "rel_name"), ("zone", "rel_zone"), ("privateValue", "rel_private")]]})
    for status in ["testing", "published"]:
        human(f"/api/meta/named-queries/{named['pid']}/status", "PUT", {"status": status})
    model = human("/api/meta/models", "POST", {"code": model_code, "displayName": "Release virtual parts",
        "modelType": "view", "sourceType": "namedQuery", "sourceRef": query_code, "primaryKey": "pid",
        "capabilities": {key: True for key in ["list", "detail", "filter", "sort", "paginate"]},
        "fields": [{"code": alias, "displayName": alias, "dataType": "string", "filterable": True}
                   for alias in ["name", "zone"]]})
    human(f"/api/meta/models/{model['pid']}/publish", "POST", {"impactAcknowledged": True})
    fields = {alias: dict(physical_definition["fields"][alias], source=alias, writable=False, required=False, operators=["EQ"] if alias != "pid" else [])
              for alias in ["pid", "name", "zone"]}
    definition = dict(physical_definition, code=code, modelCode=model_code, operations=["list", "get"],
                      scopes={op: scopes[op] for op in ["list", "get"]}, fields=fields,
                      sourceResources={source_model: "release.dynamic.parts"})
    human(f"/api/open-platform/publications/{code}/draft", "PUT", {"expectedRevision": 0, "definition": definition})
    human(f"/api/open-platform/publications/{code}/publish", "POST", {"expectedRevision": 1})
    virtual_grant = f"/api/open-platform/installations/{installation_pid}/resources/{code}"
    source_grant = f"/api/open-platform/installations/{installation_pid}/resources/release.dynamic.parts"
    original = {"operations": physical_definition["operations"], "readableFields": ["pid", "name", "zone"],
                "writableFields": ["name", "zone"], "rowConstraints": [{"field": "zone", "values": ["A"]}]}
    policy = {"operations": ["list", "get"], "readableFields": ["pid", "name", "zone"], "writableFields": [],
              "rowConstraints": [{"field": "name", "values": ["Allowed A", "Hidden B"]}]}
    extra = human(f"/api/dynamic/{source_model}/create", "POST", {"rel_name": "Virtual extra A", "rel_zone": "A", "rel_private": "Private virtual sentinel"})
    path = "/api/open/v1/resources/" + code
    def etag(headers):
        return next((value for key, value in headers.items() if key.lower() == "etag"), None)
    secondary = None
    credentials = None
    try:
        application = human("/api/open-platform/applications", "POST",
                            {"name": "Virtual isolation consumer", "description": "Exact-image isolation proof"})
        secondary = human(f"/api/open-platform/applications/{application['pid']}/installations", "POST",
                          {"environment": "staging", "scopes": [scopes[op] for op in ["list", "get"]], "rateLimitPerMinute": 600})
        secondary_base = f"/api/open-platform/installations/{secondary['pid']}"
        credentials = human(secondary_base + "/credentials", "POST", {})
        _, token, _ = request("/oauth2/token", "POST", form={"grant_type": "client_credentials",
                            "client_id": credentials["clientId"], "client_secret": credentials["clientSecret"]})
        second_machine = {"Authorization": "Bearer " + token["access_token"]}
        second_policy = dict(policy, rowConstraints=[{"field": "zone", "values": ["B"]}])
        human(secondary_base + "/resources/release.dynamic.parts", "PUT", second_policy)
        human(secondary_base + "/resources/" + code, "PUT", second_policy)
        _, secondary_list, _ = request(path, headers=second_machine)
        _, secondary_detail, _ = request(path + "/" + hidden["pid"], headers=second_machine)
        second_denied, _, _ = request(path + "/" + allowed["pid"], headers=second_machine, expected=(404,))
        human(virtual_grant, "PUT", policy)
        _, listed, _ = request(path, headers=machine)
        _, detail, headers = request(path + "/" + allowed["pid"], headers=machine)
        _, _, repeated = request(path + "/" + allowed["pid"], headers=machine)
        own_status, _, _ = request(path + "/" + extra["pid"], headers=machine, expected=(404,))
        source_status, _, _ = request(path + "/" + hidden["pid"], headers=machine, expected=(404,))
        human(virtual_grant, "PUT", dict(policy, rowConstraints=[{"field": "zone", "values": ["A"]}]))
        _, first, _ = request(path + "?limit=1", headers=machine)
        cursor = first.get("nextCursor")
        if not isinstance(cursor, str) or not cursor: raise ProtocolError("virtual first page lacks continuation")
        from urllib.parse import quote
        _, second, _ = request(path + "?limit=1&cursor=" + quote(cursor, safe=""), headers=machine)
        cursor_denied, _, _ = request(path + "?limit=1&cursor=" + quote(cursor, safe=""),
                                      headers=second_machine, expected=(400,))
        isolation = {"primaryInstallationPid": installation_pid, "secondaryInstallationPid": secondary["pid"],
                     "secondaryApplicationPid": application["pid"], "secondaryItems": secondary_list.get("items"),
                     "secondaryDetail": secondary_detail, "firstReadsSecondStatus": source_status,
                     "secondReadsFirstStatus": second_denied, "cursorStatus": cursor_denied}
        deadline_proof = run_virtual_deadline_probe(request, human, installation_pid, machine, definition, allowed, path)
        failure_proof = run_virtual_deadline_probe(request, human, installation_pid, machine, definition, allowed, path, failure_kind="executionFailure")
        identity_proof = run_virtual_deadline_probe(request, human, installation_pid, machine, definition, allowed, path, failure_kind="identityFailure")
        null_proof = run_virtual_null_identity_probe(request, human, installation_pid, machine, physical_definition, definition, path)
        human(source_grant, "PUT", dict(original, readableFields=["pid", "zone"]))
        field_status, _, _ = request(path, headers=machine, expected=(403,))
        human(source_grant, "DELETE")
        revoked_status, _, _ = request(path + "/" + allowed["pid"], headers=machine, expected=(403,))
        proof = {"resource": code, "sourceResources": definition["sourceResources"], "allowedPid": allowed["pid"],
                 "hiddenPid": hidden["pid"], "extraPid": extra["pid"], "items": listed.get("items"), "detail": detail,
                 "etag": etag(headers), "stableEtag": etag(repeated), "ownHiddenStatus": own_status,
                 "sourceHiddenStatus": source_status, "pages": [{key: page.get(key) for key in ["items", "nextCursor"]} for page in [first, second]], "fieldDeniedStatus": field_status, "revokedStatus": revoked_status, "installationIsolation": isolation, "deadline": deadline_proof, "executionFailure": failure_proof, "identityFailure": identity_proof, "nullIdentity": null_proof}
        return verify_virtual_resource(proof)
    finally:
        try:
            if secondary is not None:
                # Disabling this owned installation revokes its access even if the probe fails early.
                human(f"/api/open-platform/installations/{secondary['pid']}", "DELETE")
        finally:
            try:
                human(source_grant, "PUT", original)
            finally:
                human(f"/api/dynamic/{source_model}/{extra['pid']}", "DELETE")


def run_source_binding_probe(request, human, installation_pid, machine, get_scope):
    """Keep the deliberate metadata mutation separate from CRUD and capacity fixtures."""
    model_code = "release_dynamic_binding"
    resource = "release.dynamic.source-binding"
    fields = [{"code": name, "columnName": name, "displayName": "Storage binding field", "dataType": "string"}
              for name in ["rel_binding_name", "rel_binding_private"]]
    model = human("/api/meta/models", "POST", {"code": model_code, "tableName": model_code,
        "displayName": "Release source binding", "modelType": "entity", "sourceType": "physical", "fields": fields})
    human(f"/api/meta/models/{model['pid']}/publish", "POST", {"impactAcknowledged": True})
    row = human(f"/api/dynamic/{model_code}/create", "POST",
                {"rel_binding_name": "Storage binding original", "rel_binding_private": "Private source binding sentinel"})
    def public_field(source, kind="string"):
        return {"source": source, "type": kind, "readable": True, "writable": False, "required": False,
                "nullable": False, "operators": [], "sortable": False, "referenceResource": None, "properties": {}, "items": None}
    definition = {"code": resource, "modelCode": model_code, "schemaVersion": 1, "operations": ["get"],
        "scopes": {"get": get_scope}, "dataPolicy": "tenant_wide",
        "fields": {"pid": public_field("pid", "pid"), "name": public_field("rel_binding_name")},
        "maxPageSize": 1, "maxConditions": 1, "timeoutMs": 5000, "events": []}
    human(f"/api/open-platform/publications/{resource}/draft", "PUT", {"expectedRevision": 0, "definition": definition})
    human(f"/api/open-platform/publications/{resource}/publish", "POST", {"expectedRevision": 1})
    human(f"/api/open-platform/installations/{installation_pid}/resources/{resource}", "PUT",
          {"operations": ["get"], "readableFields": ["pid", "name"], "writableFields": [], "rowConstraints": []})
    path = f"/api/open/v1/resources/{resource}/{row['pid']}"
    _, baseline, baseline_headers = request(path, headers=machine)
    rebound = [dict(field) for field in fields]
    rebound[0]["columnName"] = "rel_binding_private"
    model_path = f"/api/meta/models/{model['pid']}"
    try:
        human(model_path, "PUT", {"displayName": "Release source binding", "extension": {"fields": rebound}})
        status, error, headers = request(path, headers=dict(machine, **{"X-Request-Id": "release-dynamic-source-binding"}), expected=(409,))
    finally:
        human(model_path, "PUT", {"displayName": "Release source binding", "extension": {"fields": fields}})
    restored_status, restored, restored_headers = request(path, headers=machine)
    def header(values, name):
        return next((value for key, value in values.items() if key.lower() == name.lower()), None)
    proof = {"status": status, "code": error.get("code") if isinstance(error, dict) else None,
        "error": error,
        "requestId": header(headers, "X-Request-Id"), "baseline": baseline, "baselineEtag": header(baseline_headers, "ETag"),
        "restoredStatus": restored_status, "restored": restored, "restoredEtag": header(restored_headers, "ETag")}
    return verify_source_binding(proof)


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


def verify_release_report(report, *, model_event_secret=None):
    if not isinstance(report, dict):
        raise ProtocolError("release protocol report invalid")
    verify_report(report.get("dynamic"))
    verify_sdk_report(report.get("sdk"))
    verify_model_event_report(report.get("modelEvents"), secret=model_event_secret)
    if "loadMeasurement" not in report["modelEvents"]:
        raise ProtocolError("release notification burst measurement missing")
    verify_command_audits(report.get("commandAudits"))
    return report



def prepare_slo_fixture(request, admin, report, consumer_fixture, *, fixture_path):
    """Grant an independent load installation on the existing verified physical model."""
    verify_report(report)
    if (consumer_fixture.get("resourceCode") != "release.dynamic.parts"
            or consumer_fixture.get("queryCode") != "release.dynamic.by-zone"):
        raise ProtocolError("SLO source fixture identity invalid")
    for key in ["allowedPid", "hiddenPid"]:
        value = consumer_fixture.get(key)
        if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", value) or value.isdigit():
            raise ProtocolError("SLO reference PID invalid")
    path = Path(fixture_path)
    if path.exists() or path.is_symlink(): raise ProtocolError("SLO private fixture already exists")

    def human(route, method="GET", body=None):
        from open_platform_http import data
        _, response, _ = request(route, method=method, body=body, headers=admin)
        if not isinstance(response, dict) or str(response.get("code")) != "0":
            raise ProtocolError("SLO administration failed")
        return data(response)

    views = human(f"/api/open-platform/installations/{report['installationPid']}/queries")
    matches = [view for view in views if view.get("code") == consumer_fixture["queryCode"]]
    if len(matches) != 1 or matches[0].get("contract", {}).get("maxConcurrent") != 2:
        raise ProtocolError("SLO active source query contract missing")
    query = json.loads(json.dumps(matches[0]["contract"]))
    source = human("/api/meta/named-queries/by-code/" + query["queryCode"])
    if source.get("policy", {}).get("rateLimitPerMinute") != 100000:
        raise ProtocolError("SLO source query rate budget missing")
    query.update(code="release.slo.by-zone", scopes={"execute": "release.slo:query"}, maxConcurrent=20)
    human("/api/open-platform/query-publications/release.slo.by-zone/draft", "PUT", {"expectedRevision": 0, "definition": query})
    human("/api/open-platform/query-publications/release.slo.by-zone/publish", "POST", {"expectedRevision": 1})
    scopes = ["release.dynamic:" + operation for operation in ["list", "get", "create", "update", "delete"]]
    scopes += ["release.slo:query", "openapi.profile.read"]
    application = human("/api/open-platform/applications", "POST",
                        {"name": "Dynamic SLO consumer", "description": "Exact-image business capacity qualification only"})
    installation = human(f"/api/open-platform/applications/{application['pid']}/installations", "POST",
                         {"environment": "staging", "scopes": scopes, "rateLimitPerMinute": 100000})
    prefix = f"/api/open-platform/installations/{installation['pid']}"
    human(prefix + "/resources/release.dynamic.parts", "PUT", {"operations": ["list", "get", "create", "update", "delete"],
          "readableFields": ["pid", "name", "zone"], "writableFields": ["name", "zone"],
          "rowConstraints": [{"field": "zone", "values": ["A"]}]})
    human(prefix + "/queries/release.slo.by-zone", "PUT", {"readableFields": ["pid", "name"]})
    credential = human(prefix + "/credentials", "POST", {})
    fixture = {"schemaVersion": 1, "profile": "open-platform-dynamic-slo-v1", "runId": "slo-" + uuid.uuid4().hex[:20],
        "resourceCode": consumer_fixture["resourceCode"], "queryCode": query["code"],
        "allowedPid": consumer_fixture["allowedPid"], "hiddenPid": consumer_fixture["hiddenPid"],
        "clientId": credential["clientId"], "clientSecret": credential["clientSecret"]}
    descriptor = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
        json.dump(fixture, handle)
    return {"profile": fixture["profile"], "applicationPid": application["pid"], "installationPid": installation["pid"],
            "resourceCode": fixture["resourceCode"], "queryCode": fixture["queryCode"], "maxConcurrent": 20,
            "sourceQueryMaxConcurrent": 2, "sourceQueryRateLimitPerMinute": source["policy"]["rateLimitPerMinute"],
            "rateLimitPerMinute": 100000}


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
    report = {"schemaVersion": 1, "cases": rows, "executed": 0, "passed": 0, "auditProofs": []}
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
        report["pluginImports"] = [import_public_plugin(request, admin, "release-public-resource", "PUBLIC_RESOURCE", definition)]
        report["pluginMetadataRollback"] = run_public_plugin_metadata_rollback(request, admin, report["pluginImports"][0], definition)
        record("plugin-resource-import", True)
        published = [row for row in human("/api/open-platform/publications") if row.get("code") == resource]
        record("publication-snapshot", len(published) == 1 and published[0].get("activeVersion") == 1
               and published[0].get("status") == "published")
        named_definition = {"code": named_code, "title": "Release zone query",
            "resourceCode": model_code, "actionCode": "read", "fromSql": f"{model['tableName']} a WHERE a.rel_zone = #{{params.zone}}",
            "policy": {"rateLimitPerMinute": 100000}, "baseWhere": [],
            "defaultOrder": [{"field": "pid", "direction": "asc"}], "fields": [
                {"fieldCode": "pid", "columnExpr": "a.pid", "dataType": "string", "displayName": "PID", "sortable": True, "searchable": False},
                {"fieldCode": "partName", "columnExpr": "a.rel_name", "dataType": "string", "displayName": "Name", "sortable": False, "searchable": False}]}
        if model["tableName"] != model_code:
            raise ProtocolError("declared query fixture physical source changed")
        before_status, missing, _ = request("/api/meta/named-queries/by-code/" + named_code, headers=admin, expected=(400,))
        verify_named_query_absent(missing, named_code)
        named_definition["status"] = "published"
        query_definition = {"code": query, "queryCode": named_code, "schemaVersion": 1,
            "sourceFingerprint": PLUGIN_QUERY_SOURCE_FINGERPRINT, "sourceResources": {model_code: resource}, "scopes": {"execute": query_scope},
            "parameters": {"zone": {"type": "string", "required": True, "nullable": False, "items": None}},
            "fields": {"pid": dict(field("pid"), type="pid", nullable=False), "name": field("partName")},
            "maxPageSize": 20, "maxRows": 100, "timeoutMs": 5000, "maxConcurrent": 2}
        report["pluginImports"].append(import_public_plugin(request, admin, "release-public-query", "PUBLIC_QUERY", query_definition,
                                                           named_queries=[named_definition]))
        named = human("/api/meta/named-queries/by-code/" + named_code)
        source = human(f"/api/open-platform/query-sources/{named_code}")
        report["pluginQuerySource"] = {"beforeStatus": before_status, "queryCode": named["code"], "sourcePid": named["pid"],
            "sourceStatus": named["status"], "sourceVersion": named["currentVersion"], "fingerprint": source["fingerprint"],
            "importId": report["pluginImports"][1]["importId"], "pluginPid": report["pluginImports"][1]["pluginPid"]}
        verify_plugin_query_source(report["pluginQuerySource"], report["pluginImports"][1])
        report["queryRollbackProtection"] = run_claimed_query_rollback(request, admin,
                report["pluginImports"][1], report["pluginQuerySource"])
        published_query = [row for row in human("/api/open-platform/query-publications") if row.get("code") == query]
        record("plugin-query-import", len(published_query) == 1 and published_query[0].get("activeVersion") == 1
               and published_query[0].get("status") == "published")
        application = human("/api/open-platform/applications", "POST", {"name": "Dynamic release consumer", "description": "Exact-image CI only"})
        installation = human(f"/api/open-platform/applications/{application['pid']}/installations", "POST",
            {"environment": "staging", "scopes": list(scopes.values()) + [query_scope], "rateLimitPerMinute": 600})
        report.update(applicationPid=application["pid"], installationPid=installation["pid"])

        def audited(case, *args, **kwargs):
            headers = dict(kwargs.pop("headers", {}), **{"X-Request-Id": "release-dynamic-" + case})
            result = request(*args, headers=headers, **kwargs)
            deadline = time.monotonic() + 5
            while True:
                audits = human(f"/api/open-platform/installations/{installation['pid']}/audits?requestId=release-dynamic-{case}")
                if audits or time.monotonic() >= deadline: break
                time.sleep(.1)
            if not isinstance(audits, list) or len(audits) != 1:
                raise ProtocolError("dynamic business audit missing or duplicated")
            report["auditProofs"].append(dict(audits[0], id=case))
            return result

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
        _, page, _ = audited("resource-row-field-projection", path, headers=machine)
        record("resource-row-field-projection", page.get("items") == [{"pid": a["pid"], "name": "Allowed A", "zone": "A"}])
        request(path + "/" + b["pid"], headers=machine, expected=(404,)); record("hidden-record")
        request(path, "POST", body={"name": "Outside", "zone": "B"}, headers=dict(machine, **{"Idempotency-Key": "dynamic-outside-create"}), expected=(403,)); record("outside-create")
        query_grant_path = f"/api/open-platform/installations/{installation['pid']}/queries/{query}"
        human(query_grant_path, "PUT", {"readableFields": ["pid", "name"]})
        _, query_a, _ = audited("query-source-row-projection", query_path, "POST", body={"parameters": {"zone": "A"}, "limit": 10}, headers=machine)
        _, query_b, _ = request(query_path, "POST", body={"parameters": {"zone": "B"}, "limit": 10}, headers=machine)
        quota_named_code = named_code + "_quota"
        quota_query = query + ".quota"
        quota_named = human("/api/meta/named-queries", "POST", dict(named_definition,
            code=quota_named_code, status="draft", policy={"rateLimitPerMinute": 1}))
        for status in ["testing", "published"]:
            human(f"/api/meta/named-queries/{quota_named['pid']}/status", "PUT", {"status": status})
        quota_source = human(f"/api/open-platform/query-sources/{quota_named_code}")
        quota_definition = dict(query_definition, code=quota_query, queryCode=quota_named_code,
                                sourceFingerprint=quota_source["fingerprint"])
        human(f"/api/open-platform/query-publications/{quota_query}/draft", "PUT", {"expectedRevision": 0, "definition": quota_definition})
        human(f"/api/open-platform/query-publications/{quota_query}/publish", "POST", {"expectedRevision": 1})
        human(f"/api/open-platform/installations/{installation['pid']}/queries/{quota_query}", "PUT", {"readableFields": ["pid", "name"]})
        quota_path = "/api/open/v1/queries/" + quota_query + ":execute"
        _, quota_first, _ = request(quota_path, "POST", body={"parameters": {"zone": "A"}, "limit": 10}, headers=machine)
        quota_request_id = "release-dynamic-query-quota"
        quota_status, quota_body, quota_headers = request(quota_path, "POST", body={"parameters": {"zone": "A"}, "limit": 10},
            headers=dict(machine, **{"X-Request-Id": quota_request_id}), expected=(429,))
        deadline = time.monotonic() + 5
        while True:
            quota_audits = human(f"/api/open-platform/installations/{installation['pid']}/audits?requestId={quota_request_id}")
            if quota_audits or time.monotonic() >= deadline: break
            time.sleep(.1)
        if not isinstance(quota_audits, list) or len(quota_audits) != 1:
            raise ProtocolError("dynamic query quota audit missing or duplicated")
        if (set(quota_body) != {"success", "code", "message"} or quota_body.get("success") is not False
                or quota_body.get("message") != "Published query execution rate budget exhausted"):
            raise ProtocolError("dynamic query quota public error invalid")
        report["queryRateLimit"] = {"status": quota_status, "code": quota_body.get("code"),
            "retryAfter": header(quota_headers, "Retry-After"), "requestId": header(quota_headers, "X-Request-Id"), "audit": quota_audits[0]}
        record("query-source-row-projection", query_a.get("items") == [{"pid": a["pid"], "name": "Allowed A"}]
               and query_b.get("items") == [] and quota_first.get("items") == query_a.get("items"))
        report["virtualResource"] = run_virtual_resource_probe(request, human, installation["pid"], machine, scopes, definition, a, b)
        if consumer is not None:
            consumer({"clientId": credentials["clientId"], "clientSecret": credentials["clientSecret"],
                      "scopes": list(scopes.values()) + [query_scope], "resourceCode": resource,
                      "queryCode": query, "allowedPid": a["pid"], "hiddenPid": b["pid"]})
        intent = {"name": "Machine created", "zone": "A"}
        create_headers = dict(machine, **{"Idempotency-Key": "dynamic-release-create"})
        _, created, created_headers = audited("create", path, "POST", body=intent, headers=create_headers, expected=(201,))
        etag = header(created_headers, "ETag")
        record("create", isinstance(created.get("pid"), str) and created == dict(intent, pid=created["pid"]) and isinstance(etag, str) and etag.startswith('"'))
        _, replay, replay_headers = audited("idempotent-replay", path, "POST", body=intent, headers=create_headers, expected=(201,))
        record("idempotent-replay", replay == created and header(replay_headers, "Idempotent-Replay") == "true")
        report["replayAuthority"] = run_replay_authority_probe(request, human, report, machine, grant_path, operations, model_code, created, created_headers)
        etag = report["replayAuthority"]["requests"][-1]["etag"]
        audited("idempotent-conflict", path, "POST", body=dict(intent, name="Conflicting intent"), headers=create_headers, expected=(409,)); record("idempotent-conflict")
        row_path = path + "/" + created["pid"]
        _, updated, updated_headers = audited("update-version", row_path, "PATCH", body={"name": "Machine updated"},
            headers=dict(machine, **{"Idempotency-Key": "dynamic-release-update", "If-Match": etag}))
        next_etag = header(updated_headers, "ETag")
        record("update-version", updated == dict(intent, pid=created["pid"], name="Machine updated") and isinstance(next_etag, str) and next_etag != etag)
        audited("stale-write", row_path, "PATCH", body={"name": "Stale"}, headers=dict(machine, **{"Idempotency-Key": "dynamic-release-stale", "If-Match": etag}), expected=(412,))
        _, persisted, persisted_headers = request(row_path, headers=machine)
        record("stale-write", persisted == updated and header(persisted_headers, "ETag") == next_etag)
        audited("prospective-row-denial", row_path, "PATCH", body={"zone": "B"}, headers=dict(machine, **{"Idempotency-Key": "dynamic-release-zone", "If-Match": next_etag}), expected=(403,))
        _, persisted, persisted_headers = request(row_path, headers=machine)
        record("prospective-row-denial", persisted == updated and header(persisted_headers, "ETag") == next_etag)
        audited("delete", row_path, "DELETE", headers=dict(machine, **{"Idempotency-Key": "dynamic-release-delete", "If-Match": next_etag}), expected=(204,))
        request(row_path, headers=machine, expected=(404,)); record("delete")
        human(query_grant_path, "DELETE")
        request(query_path, "POST", body={"parameters": {"zone": "A"}, "limit": 10}, headers=machine, expected=(403,))
        human(grant_path, "DELETE")
        request(path, headers=machine, expected=(403,)); record("grant-revocation")
        _, document, _ = request(f"/api/open-platform/publications/{resource}/openapi", headers=admin)
        record("public-openapi", document.get("openapi") == "3.1.0" and path in document.get("paths", {})
               and model_code not in json.dumps(document) and "rel_private" not in json.dumps(document))
        report["resourceSourceBinding"] = run_source_binding_probe(request, human, installation["pid"], machine, scopes["get"])
    finally:
        report["executed"] = sum(row["status"] != "did_not_run" for row in rows)
        report["passed"] = sum(row["status"] == "passed" for row in rows)
        if evidence_path is not None:
            Path(evidence_path).write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    return verify_report(report)


if __name__ == "__main__":
    import sys
    if len(sys.argv) != 5 or sys.argv[1] != "--verify-release-report" or sys.argv[3] != "--model-event-secret-file":
        raise SystemExit("usage: open_platform_dynamic_probe.py --verify-release-report REPORT --model-event-secret-file PRIVATE_KEY")
    verify_release_report(json.loads(Path(sys.argv[2]).read_text(encoding="utf-8")), model_event_secret=read_secret(sys.argv[4]))
