"""Hermetic result-verifier tests; business execution requires a real backend."""
import copy
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from open_platform_dynamic_probe import CASES, run_dynamic_probe, run_sdk_consumer, verify_report, verify_release_report, verify_command_audits, prepare_slo_fixture, run_source_binding_probe, import_public_plugin, run_public_plugin_metadata_rollback
from open_platform_http import ProtocolError
from open_platform_dynamic_probe import PLUGIN_QUERY_SOURCE_FINGERPRINT, verify_named_query_absent, run_claimed_query_rollback, verify_virtual_resource


def passed():
    audits = []
    fixtures = [("resource-row-field-projection", "list", 200, None, None),
                ("query-source-row-projection", "execute", 200, None, None),
                ("create", "create", 201, "record-pid", False),
                ("idempotent-replay", "create", 201, "record-pid", True),
                ("idempotent-conflict", "create", 409, None, None),
                ("update-version", "update", 200, "record-pid", False),
                ("stale-write", "update", 412, "record-pid", None),
                ("prospective-row-denial", "update", 403, "record-pid", None),
                ("delete", "delete", 204, "record-pid", False)]
    for case, operation, status, pid, replay in fixtures:
        resource = "release.dynamic.by-zone" if operation == "execute" else "release.dynamic.parts"
        path = "/api/open/v1/queries/" + resource + ":execute" if operation == "execute" else "/api/open/v1/resources/" + resource
        if operation in ["update", "delete"]: path += "/record-pid"
        audits.append({"id": case, "requestId": "release-dynamic-" + case, "actorKind": "machine",
            "applicationPid": "app-pid", "installationPid": "install-pid", "resource": resource,
            "operation": operation, "recordPid": pid, "idempotentReplay": replay, "status": status,
            "capability": ("query." if operation == "execute" else "resource.") + resource + "." + operation,
            "method": {"list": "GET", "execute": "POST", "create": "POST", "update": "PATCH", "delete": "DELETE"}[operation],
            "path": path, "durationMs": 1, "occurredAt": "2026-10-11T00:00:00Z"})
    original = {"pid": "record-pid", "name": "Machine created", "zone": "A"}
    location = "/api/open/v1/resources/release.dynamic.parts/record-pid"
    authority_rows = []
    for identifier, status in [("fields-denied", 403), ("fields-restored", 201), ("row-denied", 403), ("row-restored", 201), ("current", 200)]:
        marker = "release-dynamic-replay-authority-" + identifier
        operation = "get" if status == 200 else "create"
        audit = {key: value for key, value in audits[2].items() if key != "id"}
        audit.update(requestId=marker, operation=operation, status=status,
                     recordPid=None if status == 403 else "record-pid",
                     idempotentReplay=True if status == 201 else None,
                     method="GET" if status == 200 else "POST",
                     path=location if status == 200 else location.rsplit("/", 1)[0],
                     capability="resource.release.dynamic.parts." + operation)
        body = {"code": "403", "message": "Access forbidden", "data": None,
                "context": "Current resource or query authorization required", "timestamp": 1791744000000} if status == 403 else dict(original)
        authority_rows.append({"id": identifier, "status": status, "requestId": marker,
            "body": body, "etag": None if status == 403 else ('"first-version"' if status == 201 else '"current-version"'),
            "location": location if status == 201 else None, "replay": "true" if status == 201 else None, "audit": audit})
    authority = {"resource": "release.dynamic.parts", "recordPid": "record-pid", "original": original,
        "originalEtag": '"first-version"', "originalLocation": location,
        "outside": dict(original, zone="B"), "restored": dict(original), "requests": authority_rows}
    quota_audit = dict(audits[1], requestId="release-dynamic-query-quota", status=429,
                       resource="release.dynamic.by-zone.quota",
                       path="/api/open/v1/queries/release.dynamic.by-zone.quota:execute",
                       capability="query.release.dynamic.by-zone.quota.execute")
    del quota_audit["id"]
    report = {"schemaVersion": 1, "cases": [{"id": case, "status": "passed"} for case in CASES],
            "executed": len(CASES), "passed": len(CASES), "auditProofs": audits, "replayAuthority": authority,
            "pluginImports": [{"pluginId": plugin, "importId": "import-pid", "pluginPid": "plugin-pid",
                               "resourceType": kind, "created": 1, "success": True}
                              | ({"namedQueryCreated": 1, "namedQueryPid": "new-query-pid"} if kind == "PUBLIC_QUERY" else {})
                              for plugin, kind in [("release-public-resource", "PUBLIC_RESOURCE"), ("release-public-query", "PUBLIC_QUERY")]],
            "pluginQuerySource": {"beforeStatus": 400, "queryCode": "release_dynamic_by_zone", "sourcePid": "new-query-pid",
                "sourceStatus": "published", "sourceVersion": 0, "fingerprint": PLUGIN_QUERY_SOURCE_FINGERPRINT,
                "importId": "import-pid", "pluginPid": "plugin-pid"},
            "queryRollbackProtection": {"queryCode": "release.dynamic.by-zone", "importId": "import-pid", "pluginPid": "plugin-pid",
                "beforeOwnership": "SHARED", "ownershipType": "USER_CLAIMED", "userModified": True,
                "rollbackStatus": 422, "errorCode": "40000",
                "errorDetail": "Rollback failed: Public publication is claimed or modified: release.dynamic.by-zone",
                "activeVersion": 1, "sourcePid": "new-query-pid", "fingerprint": PLUGIN_QUERY_SOURCE_FINGERPRINT},
            "pluginMetadataRollback": {"pluginId": "release-public-resource", "pluginPid": "plugin-pid",
                "initialImportId": "import-pid", "upgradeImportId": "upgrade-pid", "rollbackImportId": "upgrade-pid",
                "before": {"pluginId": "release-public-resource", "namespace": "release_public_resource", "version": "1.0.0", "displayName": "Release public contract", "description": None},
                "upgraded": {"pluginId": "release-public-resource", "namespace": "release_public_resource", "version": "2.0.0", "displayName": "Release upgraded contract", "description": "Release upgrade description"},
                "restored": {"pluginId": "release-public-resource", "namespace": "release_public_resource", "version": "1.0.0", "displayName": "Release public contract", "description": None},
                "upgradedActiveVersion": 2, "restoredActiveVersion": 1},
            "applicationPid": "app-pid", "installationPid": "install-pid",
            "resourceSourceBinding": {"status": 409, "code": "open_api_source_changed", "requestId": "release-dynamic-source-binding",
                "error": {"success": False, "code": "open_api_source_changed", "message": "Published resource source binding is unavailable or changed"},
                "baseline": {"pid": "source-pid", "name": "Storage binding original"}, "baselineEtag": '"opaque-etag-value"',
                "restoredStatus": 200, "restored": {"pid": "source-pid", "name": "Storage binding original"}, "restoredEtag": '"opaque-etag-value"'},
            "virtualResource": {"resource": "release.dynamic.virtual-parts", "sourceResources": {"release_dynamic_parts": "release.dynamic.parts"},
                "allowedPid": "allowed-pid", "hiddenPid": "hidden-pid", "extraPid": "extra-pid",
                "items": [{"pid": "allowed-pid", "name": "Allowed A", "zone": "A"}],
                "detail": {"pid": "allowed-pid", "name": "Allowed A", "zone": "A"},
                "etag": '"' + "a" * 64 + '"', "stableEtag": '"' + "a" * 64 + '"',
                "ownHiddenStatus": 404, "sourceHiddenStatus": 404, "fieldDeniedStatus": 403, "revokedStatus": 403,
                "installationIsolation": {"primaryInstallationPid": "install-pid", "secondaryInstallationPid": "second-install-pid",
                    "secondaryApplicationPid": "second-app-pid", "secondaryItems": [{"pid": "hidden-pid", "name": "Hidden B", "zone": "B"}],
                    "secondaryDetail": {"pid": "hidden-pid", "name": "Hidden B", "zone": "B"},
                    "firstReadsSecondStatus": 404, "secondReadsFirstStatus": 404, "cursorStatus": 400},
                "pages": [{"items": [{"pid": "allowed-pid", "name": "Allowed A", "zone": "A"}], "nextCursor": "opaque-cursor"},
                          {"items": [{"pid": "extra-pid", "name": "Virtual extra A", "zone": "A"}], "nextCursor": None}]},
            "queryRateLimit": {"status": 429, "code": "open_api_query_rate_limited", "retryAfter": "60",
                               "requestId": "release-dynamic-query-quota", "audit": quota_audit}}


    report["writeReplayAuthority"] = {}
    for write_operation, contracts in [
        ("update", [("fields-denied", 403), ("fields-restored", 200), ("row-denied", 403), ("row-restored", 200), ("operation-denied", 403), ("operation-restored", 200), ("current", 200)]),
        ("delete", [("fields-denied", 403), ("fields-restored", 204), ("operation-denied", 403), ("operation-restored", 204), ("absent", 404)])]:
        original_body = {"pid": "record-pid", "name": "Machine updated", "zone": "A"} if write_operation == "update" else None
        original_etag = '"update-version"' if write_operation == "update" else None
        requests = []
        for identifier, status in contracts:
            is_get = identifier in ["current", "absent"]
            is_replay = not is_get and status != 403
            marker = "release-dynamic-replay-" + write_operation + "-" + identifier
            operation = "get" if is_get else write_operation
            audit = {key: value for key, value in audits[5 if write_operation == "update" else 8].items() if key != "id"}
            audit.update(requestId=marker, operation=operation, status=status, idempotentReplay=True if is_replay else None,
                         method="GET" if is_get else "PATCH" if write_operation == "update" else "DELETE",
                         capability="resource.release.dynamic.parts." + operation)
            body = copy.deepcopy(authority_rows[0]["body"]) if status == 403 else {"success": False, "code": "not_found", "message": "Resource record is unavailable"} if status == 404 else copy.deepcopy(original_body)
            requests.append({"id": identifier, "status": status, "requestId": marker, "body": body,
                "etag": '"update-current-version"' if identifier == "current" else original_etag if is_replay else None,
                "location": None, "replay": "true" if is_replay else None, "audit": audit})
        report["writeReplayAuthority"][write_operation] = {"resource": "release.dynamic.parts", "recordPid": "record-pid",
            "original": original_body, "originalEtag": original_etag, "originalLocation": None,
            "originalIfMatch": '"current-version"' if write_operation == "update" else '"update-current-version"',
            "outside": dict(original_body, zone="B") if original_body else None,
            "restored": copy.deepcopy(original_body), "requests": requests}

    for kind, suffix, status, code, message in [
        ("deadline", "timeout", 504, "open_api_query_timeout", "Published query execution deadline exceeded"),
        ("executionFailure", "unavailable", 503, "open_api_query_unavailable", "Published query execution is unavailable"),
        ("identityFailure", "ambiguous", 503, "open_api_query_unavailable", "Published query execution is unavailable")]:
        deadline_rows = []
        for operation in ["list", "get"]:
            resource = "release.dynamic.virtual-" + suffix
            marker = "release-virtual-" + suffix + "-" + operation
            path = "/api/open/v1/resources/" + resource + ("/allowed-pid" if operation == "get" else "")
            audit = {"requestId": marker, "actorKind": "machine", "applicationPid": "app-pid", "installationPid": "install-pid",
                     "resource": resource, "operation": operation, "recordPid": "allowed-pid" if operation == "get" else None,
                     "idempotentReplay": None, "status": status, "method": "GET", "path": path,
                     "capability": "resource." + resource + "." + operation, "durationMs": 30, "occurredAt": "2026-10-11T00:00:00Z"}
            deadline_rows.append({"operation": operation, "status": status, "requestId": marker, "audit": audit,
                "query": "limit=1" if kind == "identityFailure" and operation == "list" else "", "etag": None,
                "error": {"success": False, "code": code, "message": message}})
        report["virtualResource"][kind] = {"resource": "release.dynamic.virtual-" + suffix, "requests": deadline_rows,
            "healthyItems": [{"pid": "allowed-pid", "name": "Allowed A", "zone": "A"}, {"pid": "extra-pid", "name": "Virtual extra A", "zone": "A"}]}
    virtual = report["virtualResource"]
    proof = {"resource": "release.dynamic.virtual-null-identity", "sourceResource": "release.dynamic.null-parts",
        "validPid": "null-valid-one", "secondValidPid": "null-valid-two", "nullSourcePid": "null-source-pid",
        "sourceRows": [{"pid": "null-valid-one", "name": "Null identity valid one", "zone": "A"},
                       {"pid": "null-valid-two", "name": "Null identity valid two", "zone": "A"},
                       {"name": "Null identity candidate", "zone": "A"}],
        "requests": [], "healthyItems": virtual["identityFailure"]["healthyItems"]}
    for identifier, operation, query, status, pid in [("list-small", "list", "limit=1", 503, None),
            ("list-wide", "list", "limit=50", 503, None), ("get-valid", "get", "", 200, proof["validPid"]),
            ("get-null", "get", "", 404, proof["nullSourcePid"])]:
        marker = "release-virtual-null-identity-" + identifier
        path = "/api/open/v1/resources/" + proof["resource"] + ("/"+pid if operation == "get" else "")
        audit = {"requestId": marker, "actorKind": "machine", "applicationPid": "app-pid", "installationPid": "install-pid",
                 "resource": proof["resource"], "operation": operation, "recordPid": pid, "idempotentReplay": None,
                 "status": status, "method": "GET", "path": path, "capability": "resource."+proof["resource"]+"."+operation,
                 "durationMs": 5, "occurredAt": "2026-10-12T00:00:00Z"}
        body = proof["sourceRows"][0] if status == 200 else {"success": False,
            "code": "open_api_query_unavailable" if status == 503 else "not_found",
            "message": "Published query execution is unavailable" if status == 503 else "Resource record is unavailable"}
        proof["requests"].append({"id": identifier, "operation": operation, "query": query, "status": status,
            "requestId": marker, "body": body, "etag": '"'+"c"*64+'"' if status == 200 else None, "audit": audit})
    virtual["nullIdentity"] = proof
    from open_platform_authentication_probe import CASES as AUTH_CASES
    before = {"database": "hermetic-fixture", "installationFound": 1, "tokenCount": 1,
              "credentialLastUsed": "2026-10-12T00:00:00Z", "tokenLastUsed": None, "quotaCount": 0, "audits": []}
    denied = copy.deepcopy(before)
    requests = []
    for index, (name, status, code, known, token) in enumerate(AUTH_CASES):
        method = "POST" if name.startswith("oauth") else "GET"
        path = "/oauth2/token" if method == "POST" else "/api/open/v1/resources/release.dynamic.parts" if name == "bearer-expired" else "/api/open/v1/whoami"
        audit_path = "/api/open/v1/resources/{resource}" if name == "bearer-expired" else path
        identifier = "fixture-auth-" + str(index)
        audit = {"requestId": identifier, "method": method, "path": audit_path, "status": status, "durationMs": 1,
                 "occurredAt": "2026-10-12T00:00:00Z", "applicationPid": "app-pid" if known else None,
                 "installationPid": "install-pid" if known else None, "businessDetails": {"actorKind": "unauthenticated", "phase": "authentication", "code": code},
                 "tokenPresent": token, "tenantMatches": known, "anonymous": not known, "secretFree": True}
        denied["audits"].append(audit)
        visible = [{"requestId": identifier, "method": method, "path": audit_path, "status": status,
                    "actorKind": "unauthenticated", "applicationPid": "app-pid", "installationPid": "install-pid",
                    "capability": None, "resource": None, "operation": None, "recordPid": None, "idempotentReplay": None}] if known else []
        requests.append({"id": name, "status": status, "body": {"error": code} if method == "POST" else {"success": False, "code": code, "message": code},
                         "requestId": identifier, "callerIdReplaced": method == "POST", "visibleAudits": visible, "method":method,"path":path})
    allowed = dict(before, tokenLastUsed="2026-10-12T00:00:01Z", quotaCount=1)
    report["authenticationFailures"] = {"applicationPid": "app-pid", "installationPid": "install-pid", "before": before,
                                         "afterDenied": denied, "afterAllowed": allowed, "requests": requests, "normalStatus": 200, "resourcePath":"/api/open/v1/resources/release.dynamic.parts"}
    auth = report["authenticationFailures"]
    expired_request = next(row for row in requests if row['id'] == 'bearer-expired')
    expired = {'database':'hermetic-fixture','installationFound':1,'tokenFound':1,
               'originalExpiresAt':'2026-10-12T00:10:00Z','effectiveExpiresAt':'2026-10-11T23:59:00Z','observedAt':'2026-10-12T00:00:00Z'}
    restored = dict(expired, originalExpiresAt=expired['effectiveExpiresAt'], effectiveExpiresAt=expired['originalExpiresAt'], observedAt='2026-10-12T00:00:01Z')
    auth['tokenExpiry'] = {'expired':expired,'restored':restored,'requestId':expired_request['requestId']}
    resource = "release.dynamic.parts.revocation"
    state = {"database":"hermetic-fixture","installationFound":1,"credentialStatus":"active","tokenRevoked":False,
             "records":[{"pid":"create-pid","name":"Revocation create","zone":"A","version":1},
                        {"pid":"update-pid","name":"Revocation updated","zone":"A","version":2}],
             "idempotency":[],"events":[],"authentication":copy.deepcopy(allowed)}
    writes=[];denials=[]
    for operation,status,method,pid,name,event in [("create",201,"POST","create-pid","Revocation create","created"),
            ("update",200,"PATCH","update-pid","Revocation updated","updated"),("delete",204,"DELETE","delete-pid",None,"deleted")]:
        body={"pid":pid,"name":name,"zone":"A"} if name else None
        etag='"fixture-version"' if name else None;location="/api/open/v1/resources/"+resource+"/"+pid if operation=="create" else None
        key="revocation-fixture-"+operation
        writes.append({"operation":operation,"pid":pid,"key":key,"status":status,"body":body,"etag":etag,"location":location,
                       "replayStatus":status,"replayBody":body,"replayEtag":etag,"replayLocation":location,"replayMarker":"true"})
        state["idempotency"].append({"route":"resource:"+resource+":"+operation,"key":key,"status":"completed","statusCode":status,
                                     "body":body,"headers":{},"requestHash":"a"*64,"recordPid":pid,"authorityHash":"b"*64})
        state["events"].append({"eventId":"fixture-event-"+operation,"type":"open.resource."+resource+"."+event,"sequence":1 if operation=="create" else 2,
                                "subject":pid,"resource":resource,"operation":event,"recordVersion":1 if operation=="create" else 2,"payloadSha256":"c"*64})
        denials.append({"operation":operation,"requestId":"fixture-revoked-"+operation,"status":401,
                       "body":{"success":False,"code":"invalid_token","message":"invalid_token"},"etag":None,"location":None,"replay":None})
    revoked=copy.deepcopy(state);revoked.update(credentialStatus="revoked",tokenRevoked=True)
    denied=copy.deepcopy(revoked)
    for rejection,method in zip(denials,["POST","PATCH","DELETE"]):
        denied["authentication"]["audits"].append({"requestId":rejection["requestId"],"method":method,
            "path":"/api/open/v1/resources/{resource}"+("/{pid}" if method!="POST" else ""),"status":401,
            "applicationPid":"revocation-app","installationPid":"revocation-install","tenantMatches":True,"tokenPresent":True,
            "anonymous":False,"secretFree":True,"businessDetails":{"actorKind":"unauthenticated","phase":"authentication","code":"invalid_token"}})
    report["credentialRevocation"]={"resource":resource,"applicationPid":"revocation-app","installationPid":"revocation-install",
                                  "writes":writes,"before":state,"afterRevoked":revoked,"afterDenied":denied,"denials":denials}
    def major_document(code, alias):
        return {"openapi": "3.1.0", "paths": {"/api/open/v1/resources/" + code: {}},
                "components": {"schemas": {"Resource": {"properties": {key: {} for key in ["pid", alias, "zone", "secret"]}}}}}
    def major_policy(alias):
        return {"operations": ["get"], "readableFields": sorted(["pid", alias, "zone"]),
                "writableFields": [], "rowConstraints": [{"field": "zone", "values": ["A"]}]}
    major_old = "release.dynamic.parts.major"
    old_snapshot = {"status": 200, "body": {"pid": "allowed-pid", "name": "Allowed A", "zone": "A"},
                    "etag": '"stable-old-etag"', "activeVersion": 1, "policy": major_policy("name"),
                    "openapi": major_document(major_old, "name")}
    denial = {"status": 403, "body": {"code": "403", "message": "Access forbidden", "data": None,
                "context": "Current resource or query authorization required", "timestamp": 1791744000000}, "etag": None}
    report["resourceMajorCompatibility"] = {"installationPid": "install-pid", "oldCode": major_old,
        "newCode": major_old + ".v2", "rejection": {"status": 400,
            "body": {"code": "400", "message": "Existing public field contract cannot change: name"}},
        "oldSnapshots": [copy.deepcopy(old_snapshot) for _ in range(4)],
        "newOpenapi": major_document(major_old + ".v2", "displayName"),
        "newPolicy": major_policy("displayName"), "beforeGrantPolicy": None, "afterRevokePolicy": None,
        "beforeGrant": copy.deepcopy(denial), "afterRevoke": copy.deepcopy(denial),
        "afterGrant": {"status": 200, "body": {"pid": "allowed-pid", "displayName": "Allowed A", "zone": "A"},
                       "etag": '"new-major-etag"'}}
    def query_major_document(code, alias):
        return {"openapi": "3.1.0", "paths": {"/api/open/v1/queries/" + code + ":execute": {}},
                "components": {"schemas": {"QueryItem": {"properties": {key: {} for key in ["pid", alias]}}}}}
    def query_major_page(alias, empty=False):
        return {"items": [] if empty else [{"pid": "allowed-pid", alias: "Allowed A"}],
                "total": 0 if empty else 1, "hasMore": False, "nextCursor": None, "schemaVersion": 1}
    query_old = "release.dynamic.by-zone.major"
    query_snapshot = {"status": 200, "body": query_major_page("name"), "activeVersion": 1,
                      "policy": {"readableFields": ["name", "pid"]}, "openapi": query_major_document(query_old, "name")}
    report["queryMajorCompatibility"] = {"installationPid": "install-pid", "oldCode": query_old, "newCode": query_old + ".v2",
        "sourceResources": {"release_dynamic_parts": "release.dynamic.parts"},
        "rejection": {"status": 400, "body": {"code": "400", "message": "Existing query output cannot change"}},
        "oldSnapshots": [copy.deepcopy(query_snapshot) for _ in range(5)],
        "newOpenapi": query_major_document(query_old + ".v2", "displayName"),
        "newPolicy": {"readableFields": ["displayName", "pid"]}, "beforeGrantPolicy": None, "afterRevokePolicy": None,
        "afterGrant": {"status": 200, "body": query_major_page("displayName")},
        "sourceRestored": {"status": 200, "body": query_major_page("displayName")},
        "hiddenZone": {"status": 200, "body": query_major_page("displayName", True)},
        **{key: {"status": denial["status"], "body": copy.deepcopy(denial["body"])}
           for key in ["beforeGrant", "afterRevoke", "sourceRevokedOld", "sourceRevokedNew"]}}
    return report


def command_passed():
    report = {"applicationPid": "app-pid", "installationPid": "install-pid", "assetPid": "asset-pid", "stockInPid": "stock-pid", "auditProofs": []}
    for case, command, resource, status, replay, pid in [
        ("asset-assign", "assets.assign", "assets", 200, False, "asset-pid"),
        ("asset-stale", "assets.assign", "assets", 412, None, "asset-pid"),
        ("inventory-confirm", "inventory.stock-ins.confirm", "inventory.stock-ins", 200, False, "stock-pid")]:
        report["auditProofs"].append({"id": case, "requestId": "release-command-" + case, "actorKind": "machine",
            "applicationPid": "app-pid", "installationPid": "install-pid", "capability": command,
            "resource": resource, "operation": "execute", "recordPid": pid, "idempotentReplay": replay,
            "status": status, "method": "POST", "path": "/api/open/v1/commands/" + command + ":execute",
            "durationMs": 1, "occurredAt": "2026-10-11T00:00:00Z"})
    return report


class DynamicProbeTests(unittest.TestCase):
    def test_claimed_query_rollback_proof_is_mandatory_and_strict(self):
        report = passed(); del report["queryRollbackProtection"]
        with self.assertRaises(ProtocolError): verify_report(report)
        for key, value in [("importId", "foreign"), ("pluginPid", "foreign"), ("beforeOwnership", "USER_CLAIMED"),
                           ("ownershipType", "SHARED"), ("userModified", False), ("rollbackStatus", 200),
                           ("errorCode", "0"), ("errorDetail", "unrelated rejection"), ("activeVersion", True),
                           ("sourcePid", "foreign"), ("fingerprint", "0" * 64)]:
            with self.subTest(key=key):
                report = passed(); report["queryRollbackProtection"][key] = value
                with self.assertRaises(ProtocolError): verify_report(report)

    def test_claimed_query_rollback_reads_persisted_owner_and_source_after_denial(self):
        report = passed(); calls = []
        def request(path, **kwargs):
            calls.append(path)
            if path.endswith("/rollback"):
                return 422, {"code": "40000", "context": {"detail": report["queryRollbackProtection"]["errorDetail"]}}, {}
            if "/claim?" in path: return 200, None, {}
            if "/ownership?" in path:
                claimed = sum("/ownership?" in p for p in calls) == 2
                return 200, {"pluginPid": "plugin-pid", "ownershipType": "USER_CLAIMED" if claimed else "SHARED", "userModified": claimed}, {}
            if path.endswith("query-publications"):
                return 200, [{"code": "release.dynamic.by-zone", "status": "published", "activeVersion": 1}], {}
            if "/by-code/" in path:
                return 200, {"pid": "new-query-pid", "status": "published", "currentVersion": 0}, {}
            if "/query-sources/" in path: return 200, {"fingerprint": PLUGIN_QUERY_SOURCE_FINGERPRINT}, {}
            raise AssertionError("Unexpected production route")
        proof = run_claimed_query_rollback(request, {}, report["pluginImports"][1], report["pluginQuerySource"])
        self.assertEqual(proof, report["queryRollbackProtection"])
        self.assertEqual(len(calls), 7)
        self.assertLess(next(i for i, p in enumerate(calls) if p.endswith("/rollback")),
                        next(i for i, p in enumerate(calls) if p.endswith("query-publications")))

    def test_named_query_absence_requires_business_error_context(self):
        valid = {"code": "40000", "message": "Business error", "context": "Named query not found: new-query"}
        verify_named_query_absent(valid, "new-query")
        for key, value in [("code", "0"), ("message", "Named query not found: new-query"),
                           ("context", None), ("context", "Named query not found: foreign-query")]:
            with self.subTest(key=key, value=value):
                bad = dict(valid); bad[key] = value
                with self.assertRaises(ProtocolError): verify_named_query_absent(bad, "new-query")

    def test_new_query_source_proof_is_mandatory_and_strict(self):
        report = passed(); del report["pluginQuerySource"]
        with self.assertRaises(ProtocolError): verify_report(report)
        for key, value in [("beforeStatus", 200), ("queryCode", "preexisting"), ("sourcePid", "foreign"),
                           ("sourceStatus", "draft"), ("sourceVersion", True), ("fingerprint", "0" * 64),
                           ("importId", "foreign"), ("pluginPid", "foreign")]:
            report = passed(); report["pluginQuerySource"][key] = value
            with self.subTest(key=key), self.assertRaises(ProtocolError): verify_report(report)
        for key, value in [("namedQueryCreated", True), ("namedQueryCreated", 0), ("namedQueryPid", "123")]:
            report = passed(); report["pluginImports"][1][key] = value
            with self.subTest(key=key), self.assertRaises(ProtocolError): verify_report(report)

    def test_joint_query_import_requires_new_source_count_and_pid(self):
        for mutation in [None, "count", "pid"]:
            calls = []
            def request(path, **kwargs):
                calls.append((path, kwargs))
                if path.endswith("/parse"): return 200, {"valid": True, "importId": "import-pid"}, {}
                result = {"success": True, "importId": "import-pid", "pluginId": "release-public-query", "pluginPid": "plugin-pid",
                          "resourceCounts": {"PUBLIC_QUERY": {"CREATE": 1}, "NAMED_QUERY": {"CREATE": 1}},
                          "createdResources": {"NAMED_QUERY": ["new-query-pid"]}}
                if mutation == "count": result["resourceCounts"]["NAMED_QUERY"] = {"SKIP": 1}
                if mutation == "pid": result["createdResources"]["NAMED_QUERY"] = []
                return 200, result, {}
            if mutation:
                with self.assertRaises(ProtocolError):
                    import_public_plugin(request, {}, "release-public-query", "PUBLIC_QUERY", {"code": "query"}, named_queries=[{"code": "source"}])
            else:
                proof = import_public_plugin(request, {}, "release-public-query", "PUBLIC_QUERY", {"code": "query"}, named_queries=[{"code": "source"}])
                self.assertEqual(proof["namedQueryPid"], "new-query-pid")
                self.assertEqual(calls[0][1]["body"]["namedQueries"], [{"code": "source"}])
                self.assertEqual(calls[0][1]["body"]["publicQueries"], [{"code": "query"}])

    def test_public_plugin_parse_execute_rejects_false_success_and_wrong_identity(self):
        for change in [None, "success", "importId", "pluginId", "resourceCounts"]:
            calls = []
            def request(path, **kwargs):
                calls.append((path, kwargs))
                if path.endswith("/parse"): return 200, {"valid": True, "importId": "import-pid"}, {}
                result = {"success": True, "importId": "import-pid", "pluginId": "release-public-resource",
                          "pluginPid": "plugin-pid", "resourceCounts": {"PUBLIC_RESOURCE": {"CREATE": 1}}}
                if change: result[change] = False if change == "success" else {} if change == "resourceCounts" else "foreign"
                return 200, result, {}
            with self.subTest(change=change):
                if change:
                    with self.assertRaises(ProtocolError): import_public_plugin(request, {}, "release-public-resource", "PUBLIC_RESOURCE", {"code": "resource"})
                else:
                    self.assertEqual(import_public_plugin(request, {}, "release-public-resource", "PUBLIC_RESOURCE", {"code": "resource"})["created"], 1)
                    self.assertEqual(calls[0][1]["body"]["publicResources"], [{"code": "resource"}])
                    self.assertEqual(calls[1][0], "/api/plugins/import/import-pid/execute")
                    self.assertEqual(calls[1][1]["body"], {"conflictStrategy": "ERROR"})

    def test_public_plugin_metadata_rollback_reads_real_route_results_and_rejects_stale_metadata(self):
        for stale in [False, True]:
            phase = "before"; calls = []
            proof = passed()["pluginMetadataRollback"]
            imported = passed()["pluginImports"][0]
            def request(path, **kwargs):
                nonlocal phase
                calls.append((path, kwargs))
                if path == "/api/plugins/plugin-pid":
                    value = proof["upgraded"] if phase == "upgraded" or (stale and phase == "restored") else proof["before"]
                    return 200, {"code": "0", "data": dict(value, pid="plugin-pid")}, {}
                if path == "/api/open-platform/publications":
                    return 200, {"code": "0", "data": [{"code": "release.dynamic.parts", "status": "published", "activeVersion": 2 if phase == "upgraded" else 1}]}, {}
                if path == "/api/plugins/import/parse":
                    self.assertEqual(kwargs["body"]["publicResources"][0]["schemaVersion"], 2)
                    return 200, {"valid": True, "importId": "upgrade-pid"}, {}
                if path == "/api/plugins/import/upgrade-pid/execute":
                    self.assertEqual(kwargs["body"], {"conflictStrategy": "OVERWRITE"})
                    phase = "upgraded"
                    return 200, {"success": True, "importId": "upgrade-pid", "pluginId": "release-public-resource", "pluginPid": "plugin-pid", "resourceCounts": {"PUBLIC_RESOURCE": {"UPDATE": 1}}}, {}
                self.assertEqual(path, "/api/plugins/import/upgrade-pid/rollback")
                self.assertEqual(kwargs["method"], "POST")
                phase = "restored"
                return 200, {"success": True, "importId": "upgrade-pid", "pluginId": "release-public-resource"}, {}
            with self.subTest(stale=stale):
                if stale:
                    with self.assertRaisesRegex(ProtocolError, "metadata rollback proof invalid"):
                        run_public_plugin_metadata_rollback(request, {}, imported, {"code": "release.dynamic.parts", "schemaVersion": 1})
                else:
                    self.assertEqual(run_public_plugin_metadata_rollback(request, {}, imported, {"code": "release.dynamic.parts", "schemaVersion": 1}), proof)
                self.assertEqual(sum(path.endswith("/rollback") for path, _ in calls), 1)
                self.assertEqual(sum(path == "/api/plugins/plugin-pid" for path, _ in calls), 3)

    def test_public_plugin_metadata_rollback_proof_is_mandatory_and_strict(self):
        report = passed(); del report["pluginMetadataRollback"]
        with self.assertRaises(ProtocolError): verify_report(report)
        for key, value in [("pluginPid", "foreign"), ("initialImportId", "foreign"), ("upgradeImportId", "import-pid"),
                           ("rollbackImportId", "foreign"), ("restoredActiveVersion", True), ("upgradedActiveVersion", 1),
                           ("restored", {}), ("before", {}), ("upgraded", {})]:
            report = passed(); report["pluginMetadataRollback"][key] = value
            with self.subTest(key=key), self.assertRaises(ProtocolError): verify_report(report)
        for key in ["version", "description", "namespace", "displayName"]:
            report = passed(); report["pluginMetadataRollback"]["restored"][key] = "unrestored"
            with self.subTest(key=key), self.assertRaises(ProtocolError): verify_report(report)

    def test_public_plugin_proof_is_mandatory_and_strict(self):
        report = passed(); del report["pluginImports"]
        with self.assertRaises(ProtocolError): verify_report(report)
        for key, value in [("pluginId", "foreign"), ("resourceType", "PUBLIC_QUERY"), ("created", True), ("success", False), ("pluginPid", None)]:
            report = passed(); report["pluginImports"][0][key] = value
            with self.subTest(key=key), self.assertRaises(ProtocolError): verify_report(report)

    def test_source_binding_fixture_restores_metadata_after_success_and_transport_failure(self):
        for fail in [False, True]:
            state = {"rebound": False, "restored": False}
            def human(path, method="GET", body=None):
                if path == "/api/meta/models": return {"pid": "model-pid"}
                if path.endswith("/create"): return {"pid": "source-pid"}
                if path == "/api/meta/models/model-pid" and method == "PUT":
                    state["rebound"] = body["extension"]["fields"][0]["columnName"] == "rel_binding_private"
                    state["restored"] = not state["rebound"]
                return {}
            def request(path, **kwargs):
                if state["rebound"]:
                    if fail: raise ProtocolError("transport failure")
                    return 409, {"success": False, "code": "open_api_source_changed", "message": "Published resource source binding is unavailable or changed"}, {"X-Request-Id": "release-dynamic-source-binding"}
                return 200, {"pid": "source-pid", "name": "Storage binding original"}, {"ETag": '"opaque-etag-value"'}
            with self.subTest(fail=fail):
                if fail:
                    with self.assertRaises(ProtocolError): run_source_binding_probe(request, human, "install-pid", {}, "resource.get")
                else:
                    self.assertEqual(run_source_binding_probe(request, human, "install-pid", {}, "resource.get")["status"], 409)
                self.assertTrue(state["restored"])
                self.assertFalse(state["rebound"])

    def test_query_major_proof_is_mandatory_and_rechecks_current_source_authority(self):
        report = passed(); del report["queryMajorCompatibility"]
        with self.assertRaises(ProtocolError): verify_report(report)
        mutations = [
            lambda p: p.update(installationPid="foreign"), lambda p: p.update(newCode=p["oldCode"]),
            lambda p: p.update(sourceResources={}), lambda p: p["rejection"].update(status=200),
            lambda p: p["rejection"]["body"].update(message="unrelated error"), lambda p: p["oldSnapshots"].pop(),
            lambda p: p["oldSnapshots"][1].update(activeVersion=2),
            lambda p: p["oldSnapshots"][2]["policy"].update(readableFields=["pid"]),
            lambda p: p["oldSnapshots"][3]["body"].update(total=2),
            lambda p: p["oldSnapshots"][4]["openapi"].update(openapi="3.0.0"),
            lambda p: p.update(newOpenapi=p["oldSnapshots"][0]["openapi"]),
            lambda p: p.update(newPolicy=p["oldSnapshots"][0]["policy"]),
            lambda p: p.update(beforeGrantPolicy={}), lambda p: p.update(afterRevokePolicy={}),
            lambda p: p["beforeGrant"].update(status=200), lambda p: p["afterRevoke"].update(status=200),
            lambda p: p["sourceRevokedOld"].update(status=200), lambda p: p["sourceRevokedNew"].update(status=200),
            lambda p: p["afterGrant"]["body"]["items"][0].update(secret="Private A"),
            lambda p: p["hiddenZone"]["body"].update(total=1), lambda p: p["sourceRestored"].update(status=403),
        ]
        for index, mutate in enumerate(mutations):
            report = passed(); mutate(report["queryMajorCompatibility"])
            with self.subTest(index=index), self.assertRaises(ProtocolError): verify_report(report)

    def test_resource_major_proof_is_mandatory_and_rejects_contract_or_grant_reuse(self):
        report = passed(); del report["resourceMajorCompatibility"]
        with self.assertRaises(ProtocolError): verify_report(report)
        mutations = [
            lambda p: p.update(installationPid="foreign-install"),
            lambda p: p.update(newCode=p["oldCode"]),
            lambda p: p["rejection"].update(status=200),
            lambda p: p["rejection"]["body"].update(message="unrelated rejection"),
            lambda p: p["oldSnapshots"].pop(),
            lambda p: p["oldSnapshots"][1].update(activeVersion=2),
            lambda p: p["oldSnapshots"][2].update(etag='"changed"'),
            lambda p: p["oldSnapshots"][3]["body"].update(name="Changed"),
            lambda p: p["oldSnapshots"][2]["policy"].update(readableFields=["pid"]),
            lambda p: p["oldSnapshots"][1]["openapi"].update(openapi="3.0.0"),
            lambda p: p.update(newOpenapi=p["oldSnapshots"][0]["openapi"]),
            lambda p: p.update(newPolicy=p["oldSnapshots"][0]["policy"]),
            lambda p: p.update(beforeGrantPolicy=p["newPolicy"]),
            lambda p: p.update(afterRevokePolicy=p["newPolicy"]),
            lambda p: p["beforeGrant"].update(status=200),
            lambda p: p["afterRevoke"].update(status=200),
            lambda p: p["afterGrant"]["body"].update(secret="Private A"),
            lambda p: p["afterGrant"]["body"].update(pid="hidden-pid"),
            lambda p: p["afterGrant"].update(etag=None),
        ]
        for index, mutate in enumerate(mutations):
            report = passed(); mutate(report["resourceMajorCompatibility"])
            with self.subTest(index=index), self.assertRaises(ProtocolError): verify_report(report)

    def test_source_binding_proof_is_mandatory_and_preserves_the_original_record_and_etag(self):
        report = passed(); del report["resourceSourceBinding"]
        with self.assertRaises(ProtocolError): verify_report(report)
        for key, value in [("status", 200), ("code", "access_denied"), ("requestId", "foreign-request"),
                           ("error", {"success": False, "code": "open_api_source_changed", "message": "Private source binding sentinel"}),
                           ("baselineEtag", None), ("restoredStatus", 403), ("restoredEtag", '"another-value"'),
                           ("restored", {"pid": "source-pid", "name": "Private source binding sentinel"})]:
            report = passed(); report["resourceSourceBinding"][key] = value
            with self.subTest(key=key), self.assertRaises(ProtocolError): verify_report(report)

    def test_virtual_resource_proof_is_mandatory_and_rejects_missing_boundaries(self):
        report = passed(); del report["virtualResource"]
        with self.assertRaises(ProtocolError): verify_report(report)
        for key, value in [("sourceResources", {}), ("items", []), ("detail", {"pid": "hidden-pid"}),
                           ("etag", None), ("stableEtag", '"' + "b" * 64 + '"'), ("ownHiddenStatus", 200),
                           ("sourceHiddenStatus", 200), ("fieldDeniedStatus", 200), ("revokedStatus", 200),
                           ("hiddenPid", "allowed-pid"), ("pages", []), ("pages", [{"items": None, "nextCursor": None}])]:
            proof = copy.deepcopy(passed()["virtualResource"]); proof[key] = value
            with self.subTest(key=key), self.assertRaises(ProtocolError): verify_virtual_resource(proof)
        proof = copy.deepcopy(passed()["virtualResource"])
        proof["pages"][1]["items"] = copy.deepcopy(proof["pages"][0]["items"])
        with self.assertRaises(ProtocolError): verify_virtual_resource(proof)
        proof = copy.deepcopy(passed()["virtualResource"]); proof["items"][0]["privateValue"] = "Private sentinel"
        with self.assertRaises(ProtocolError): verify_virtual_resource(proof)

        report = passed(); del report["virtualResource"]["installationIsolation"]
        with self.assertRaises(ProtocolError): verify_report(report)
        for key, value in [("primaryInstallationPid", "foreign-install"), ("secondaryInstallationPid", "install-pid"),
                           ("secondaryApplicationPid", "app-pid"), ("secondaryInstallationPid", ""),
                           ("secondaryItems", []), ("secondaryDetail", {"pid": "allowed-pid"}),
                           ("firstReadsSecondStatus", 200), ("secondReadsFirstStatus", 200), ("cursorStatus", 200),
                           ("cursorStatus", True)]:
            report = passed(); report["virtualResource"]["installationIsolation"][key] = value
            with self.subTest(isolation=key), self.assertRaises(ProtocolError): verify_report(report)
        for key in passed()["virtualResource"]["installationIsolation"]:
            report = passed(); del report["virtualResource"]["installationIsolation"][key]
            with self.subTest(missing=key), self.assertRaises(ProtocolError): verify_report(report)
        report = passed(); report["virtualResource"]["installationIsolation"]["secondaryItems"][0]["privateValue"] = "Private sentinel"
        with self.assertRaises(ProtocolError): verify_report(report)

        for kind, wrong_status in [("deadline", 503), ("executionFailure", 504), ("identityFailure", 504)]:
            for key in passed()["virtualResource"][kind]:
                report = passed(); del report["virtualResource"][kind][key]
                with self.subTest(deadline_missing=key), self.assertRaises(ProtocolError): verify_report(report)
            for index in [0, 1]:
                for key, value in [("status", wrong_status), ("status", True), ("requestId", "foreign"), ("query", "limit=999"), ("etag", '"unsafe"'),
                                   ("error", {"code": "40000", "context": "open_api_query_timeout"}), ("audit", None)]:
                    report = passed(); report["virtualResource"][kind]["requests"][index][key] = value
                    with self.subTest(deadline=index, key=key), self.assertRaises(ProtocolError): verify_report(report)
                for key, value in [("status", 200), ("status", True), ("installationPid", "foreign"),
                                   ("applicationPid", "foreign"), ("operation", "execute"), ("recordPid", "private"), ("durationMs", True)]:
                    report = passed(); report["virtualResource"][kind]["requests"][index]["audit"][key] = value
                    with self.subTest(deadline_audit=index, key=key), self.assertRaises(ProtocolError): verify_report(report)
            for key, value in [("requests", []), ("healthyItems", []), ("healthyItems", [{"pid": "allowed-pid", "name": "Private"}])]:
                report = passed(); report["virtualResource"][kind][key] = value
                with self.subTest(deadline_boundary=key), self.assertRaises(ProtocolError): verify_report(report)

        for key in passed()["virtualResource"]["nullIdentity"]:
            report = passed(); del report["virtualResource"]["nullIdentity"][key]
            with self.subTest(null_missing=key), self.assertRaises(ProtocolError): verify_report(report)
        for key, value in [("resource", "foreign"), ("sourceResource", "foreign"), ("requests", []),
                           ("sourceRows", []), ("healthyItems", []), ("validPid", "private-id"),
                           ("secondValidPid", "null-valid-one"), ("nullSourcePid", "null-valid-one")]:
            report = passed(); report["virtualResource"]["nullIdentity"][key] = value
            with self.subTest(null_field=key), self.assertRaises(ProtocolError): verify_report(report)
        for index in range(4):
            for key, value in [("status", True), ("status", 504), ("requestId", "foreign"),
                               ("body", {"success": False, "private": "SQL source"}), ("query", "limit=999"), ("etag", '"unsafe"')]:
                report = passed(); report["virtualResource"]["nullIdentity"]["requests"][index][key] = value
                with self.subTest(null_response=index, key=key), self.assertRaises(ProtocolError): verify_report(report)
            for key, value in [("installationPid", "foreign"), ("applicationPid", "foreign"), ("status", True),
                               ("operation", "execute"), ("recordPid", "foreign"), ("durationMs", True), ("path", "/foreign")]:
                report = passed(); report["virtualResource"]["nullIdentity"]["requests"][index]["audit"][key] = value
                with self.subTest(null_audit=index, key=key), self.assertRaises(ProtocolError): verify_report(report)

    def test_query_quota_requires_response_and_persisted_public_audit(self):
        self.assertEqual(verify_report(passed()), passed())
        report = passed(); del report["queryRateLimit"]
        with self.assertRaises(ProtocolError): verify_report(report)
        for key, value in [("status", 403), ("code", "access_denied"), ("retryAfter", None),
                           ("requestId", "wrong"), ("token", "private")]:
            report = passed(); report["queryRateLimit"][key] = value
            with self.subTest(response=key):
                with self.assertRaises(ProtocolError): verify_report(report)
        for key, value in [("status", 200), ("installationPid", "foreign-install"),
                           ("idempotentReplay", False), ("recordPid", "private-id"),
                           ("capability", "internal-query"), ("durationMs", True), ("tenantId", 42)]:
            report = passed(); report["queryRateLimit"]["audit"][key] = value
            with self.subTest(audit=key):
                with self.assertRaises(ProtocolError): verify_report(report)

    def test_command_audit_requires_exact_public_actor_target_and_denied_status(self):
        self.assertEqual(verify_command_audits(command_passed()), command_passed())
        for change in [lambda r: r["auditProofs"].pop(), lambda r: r["auditProofs"].reverse(),
                       lambda r: r.__setitem__("applicationPid", "foreign-app")]:
            report = command_passed(); change(report)
            with self.assertRaises(ProtocolError): verify_command_audits(report)
        for key, value in [("resource", None), ("recordPid", "wrong-pid"), ("status", 200),
                           ("idempotentReplay", False), ("capability", "internal-command"),
                           ("tenantId", 42), ("durationMs", True)]:
            report = command_passed(); report["auditProofs"][1][key] = value
            with self.subTest(key=key):
                with self.assertRaises(ProtocolError): verify_command_audits(report)

    def test_slo_fixture_uses_active_query_and_private_independent_load_installation(self):
        calls = []
        contract = {"code": "release.dynamic.by-zone", "queryCode": "release_dynamic_by_zone", "maxConcurrent": 2, "sourceFingerprint": "source-fingerprint", "scopes": {"execute": "release.dynamic:query"}}
        def request(path, *, method="GET", body=None, headers=None):
            calls.append((path, method, body))
            result = [{"code": "release.dynamic.by-zone", "contract": contract}] if path.endswith("/queries") else {}
            if path == "/api/open-platform/applications": result = {"pid": "load-app"}
            if path.startswith("/api/meta/named-queries/by-code/"): result = {"policy": {"rateLimitPerMinute": 100000}}
            if path.endswith("/installations"): result = {"pid": "load-install"}
            if path.endswith("/credentials"): result = {"clientId": "private-client", "clientSecret": "private-secret"}
            return 200, {"code": 0, "data": result}, {}
        consumer = {"resourceCode": "release.dynamic.parts", "queryCode": "release.dynamic.by-zone", "allowedPid": "allowed-pid", "hiddenPid": "hidden-pid"}
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "fixture.json"
            public = prepare_slo_fixture(request, {}, passed(), consumer, fixture_path=path)
            self.assertEqual(path.stat().st_mode & 0o777, 0o600)
            private = json.loads(path.read_text()); self.assertEqual(private["clientSecret"], "private-secret")
            self.assertEqual(private["queryCode"], "release.slo.by-zone")
            self.assertNotIn("private-secret", json.dumps(public)); self.assertNotIn("private-client", json.dumps(public))
            self.assertEqual(contract["maxConcurrent"], 2)
            self.assertEqual(calls[2][2]["definition"]["maxConcurrent"], 20)
            self.assertEqual(public["applicationPid"], "load-app")
            self.assertEqual(calls[5][0], "/api/open-platform/applications/load-app/installations")
            self.assertEqual(calls[5][2]["rateLimitPerMinute"], 100000)
            calls.clear()
            with self.assertRaises(ProtocolError): prepare_slo_fixture(request, {}, passed(), consumer, fixture_path=path)
            self.assertEqual(calls, [])

    def test_fixed_complete_report(self):
        self.assertEqual(verify_report(passed()), passed())

    def test_missing_case(self):
        report = passed(); report["cases"].pop()
        with self.assertRaises(ProtocolError): verify_report(report)

    def test_audit_proofs_are_mandatory_and_reject_wrong_identity_result_or_private_fields(self):
        from open_platform_authentication_probe import PsqlAuditReader
        for database, allowed in [('auraboot_188', False), ('production', True)]:
            reader = PsqlAuditReader(['psql'], database, allow_fixture_clock=allowed)
            with patch('open_platform_authentication_probe.subprocess.run') as sql:
                with self.assertRaises(ProtocolError): reader.token_clock('install-pid', 'owned-dummy-token')
                sql.assert_not_called()
        original=passed()
        absent=copy.deepcopy(original);absent.pop("credentialRevocation")
        with self.assertRaises(ProtocolError):verify_report(absent)
        for key in ["writes","before","afterRevoked","afterDenied","denials"]:
            invalid=copy.deepcopy(original);invalid["credentialRevocation"].pop(key)
            with self.subTest(revoked_missing=key), self.assertRaises(ProtocolError):verify_report(invalid)
        for index in range(3):
            invalid=copy.deepcopy(original);invalid["credentialRevocation"]["denials"][index]["status"]=200
            with self.subTest(revoked_case=index), self.assertRaises(ProtocolError):verify_report(invalid)
        for key in ["records","idempotency","events"]:
            invalid=copy.deepcopy(original);invalid["credentialRevocation"]["afterDenied"][key]=[]
            with self.subTest(revoked_state=key), self.assertRaises(ProtocolError):verify_report(invalid)

        baseline = passed()
        for key in ["authenticationFailures"]:
            report = copy.deepcopy(baseline); report.pop(key)
            with self.assertRaises(ProtocolError): verify_report(report)
        for key in ["before", "afterDenied", "afterAllowed", "requests", "normalStatus", "tokenExpiry", "resourcePath"]:
            report = copy.deepcopy(baseline); report["authenticationFailures"].pop(key)
            with self.subTest(auth_missing=key), self.assertRaises(ProtocolError): verify_report(report)
        for index in range(len(baseline["authenticationFailures"]["requests"])):
            for key in baseline["authenticationFailures"]["afterDenied"]["audits"][index]:
                report = copy.deepcopy(baseline); report["authenticationFailures"]["afterDenied"]["audits"][index].pop(key)
                with self.subTest(auth_audit=index,missing=key), self.assertRaises(ProtocolError): verify_report(report)
        expired_index = next(i for i, row in enumerate(baseline['authenticationFailures']['requests']) if row['id'] == 'bearer-expired')
        for changed in ['request-path','audit-path']:
            report = copy.deepcopy(baseline)
            if changed == 'request-path': report['authenticationFailures']['requests'][expired_index]['path']='/api/open/v1/whoami'
            else: report['authenticationFailures']['afterDenied']['audits'][expired_index]['path']='/api/open/v1/resources/release.dynamic.parts'
            with self.subTest(expired_path=changed), self.assertRaises(ProtocolError): verify_report(report)
        for stage in ['expired','restored']:
            for key in baseline['authenticationFailures']['tokenExpiry'][stage]:
                report = copy.deepcopy(baseline); report['authenticationFailures']['tokenExpiry'][stage].pop(key)
                with self.subTest(expiry_stage=stage,missing=key), self.assertRaises(ProtocolError): verify_report(report)
        for stage,key,value in [('expired','effectiveExpiresAt','2026-10-12T00:20:00Z'),('expired','tokenFound',0),
                                ('restored','effectiveExpiresAt','2026-10-12T00:20:00Z'),('restored','observedAt','2026-10-11T00:00:00Z')]:
            report = copy.deepcopy(baseline); report['authenticationFailures']['tokenExpiry'][stage][key]=value
            with self.subTest(expiry_stage=stage,changed=key), self.assertRaises(ProtocolError): verify_report(report)
        for field in ["tokenCount", "credentialLastUsed", "tokenLastUsed", "quotaCount"]:
            report = copy.deepcopy(baseline); report["authenticationFailures"]["afterDenied"][field] = "changed"
            with self.subTest(auth_state=field), self.assertRaises(ProtocolError): verify_report(report)

        report = passed(); del report["auditProofs"]
        with self.assertRaises(ProtocolError): verify_report(report)
        for change in [lambda r: r["auditProofs"].pop(),
                       lambda r: r["auditProofs"].reverse(),
                       lambda r: r["auditProofs"].__setitem__(1, r["auditProofs"][0]),
                       lambda r: r.__setitem__("installationPid", "other-install")]:
            report = passed(); change(report)
            with self.assertRaises(ProtocolError): verify_report(report)
        for key, value in [("actorKind", "human"), ("installationPid", "other-install"),
                           ("resource", "internal_model"), ("recordPid", "123"), ("operation", "update"),
                           ("requestId", "unrelated"), ("status", 500), ("durationMs", True),
                           ("idempotentReplay", 1), ("token", "private-sentinel"), ("tenantId", 42)]:
            report = passed(); report["auditProofs"][3][key] = value
            with self.subTest(key=key):
                with self.assertRaises(ProtocolError): verify_report(report)

        report = passed(); del report["replayAuthority"]
        with self.assertRaises(ProtocolError): verify_report(report)
        for key in passed()["replayAuthority"]:
            report = passed(); del report["replayAuthority"][key]
            with self.subTest(missing=key):
                with self.assertRaises(ProtocolError): verify_report(report)
        for key, value in [("resource", "internal-model"), ("recordPid", "different-pid"),
                           ("original", {}), ("originalEtag", "weak"), ("originalLocation", "/private"),
                           ("outside", {}), ("restored", {}), ("requests", [])]:
            report = passed(); report["replayAuthority"][key] = value
            with self.subTest(proof=key):
                with self.assertRaises(ProtocolError): verify_report(report)
        for index, row in enumerate(passed()["replayAuthority"]["requests"]):
            for key in row:
                report = passed(); del report["replayAuthority"]["requests"][index][key]
                with self.subTest(request=index, missing=key):
                    with self.assertRaises(ProtocolError): verify_report(report)
            for key, value in [("id", "wrong"), ("status", True), ("requestId", "wrong"),
                               ("body", {"private": "sentinel"}), ("etag", "weak"),
                               ("location", "/private"), ("replay", "false")]:
                report = passed(); report["replayAuthority"]["requests"][index][key] = value
                with self.subTest(request=index, changed=key):
                    with self.assertRaises(ProtocolError): verify_report(report)
            for key in row["audit"]:
                report = passed(); del report["replayAuthority"]["requests"][index]["audit"][key]
                with self.subTest(audit=index, missing=key):
                    with self.assertRaises(ProtocolError): verify_report(report)
            for key, value in [("actorKind", "human"), ("applicationPid", "other-app"),
                               ("installationPid", "other-install"), ("resource", "other-resource"),
                               ("operation", "delete"), ("recordPid", "other-pid"),
                               ("idempotentReplay", 1), ("status", True), ("method", "DELETE"),
                               ("path", "/private"), ("capability", "other"), ("requestId", "wrong"),
                               ("durationMs", True), ("occurredAt", ""), ("token", "private-sentinel")]:
                report = passed(); report["replayAuthority"]["requests"][index]["audit"][key] = value
                with self.subTest(audit=index, changed=key):
                    with self.assertRaises(ProtocolError): verify_report(report)

        report = passed(); del report["writeReplayAuthority"]
        with self.assertRaises(ProtocolError): verify_report(report)
        for operation in ["update", "delete"]:
            report = passed(); del report["writeReplayAuthority"][operation]
            with self.subTest(missing=operation):
                with self.assertRaises(ProtocolError): verify_report(report)
            proof = passed()["writeReplayAuthority"][operation]
            for key in proof:
                report = passed(); del report["writeReplayAuthority"][operation][key]
                with self.subTest(operation=operation, missing=key):
                    with self.assertRaises(ProtocolError): verify_report(report)
            for key, value in [("resource", "internal"), ("recordPid", "wrong-pid"), ("original", {}),
                               ("originalEtag", "weak"), ("originalLocation", "/private"), ("originalIfMatch", '"other-version"'),
                               ("outside", {}), ("restored", {}), ("requests", [])]:
                report = passed(); report["writeReplayAuthority"][operation][key] = value
                with self.subTest(operation=operation, changed=key):
                    with self.assertRaises(ProtocolError): verify_report(report)
            for index, row in enumerate(proof["requests"]):
                for key in row:
                    report = passed(); del report["writeReplayAuthority"][operation]["requests"][index][key]
                    with self.subTest(operation=operation, request=index, missing=key):
                        with self.assertRaises(ProtocolError): verify_report(report)
                for key, value in [("id", "wrong"), ("status", True), ("requestId", "wrong"),
                                   ("body", {"private": "sentinel"}), ("etag", "weak"), ("location", "/private"), ("replay", "false")]:
                    report = passed(); report["writeReplayAuthority"][operation]["requests"][index][key] = value
                    with self.subTest(operation=operation, request=index, changed=key):
                        with self.assertRaises(ProtocolError): verify_report(report)
                for key in row["audit"]:
                    report = passed(); del report["writeReplayAuthority"][operation]["requests"][index]["audit"][key]
                    with self.subTest(operation=operation, audit=index, missing=key):
                        with self.assertRaises(ProtocolError): verify_report(report)
                for key, value in [("actorKind", "human"), ("applicationPid", "other-app"), ("installationPid", "other-install"),
                                   ("resource", "other"), ("operation", "list"), ("recordPid", "different-pid"),
                                   ("idempotentReplay", 1), ("status", True), ("method", "POST"), ("path", "/private"),
                                   ("capability", "other"), ("requestId", "wrong"), ("durationMs", True),
                                   ("occurredAt", ""), ("token", "private-sentinel")]:
                    report = passed(); report["writeReplayAuthority"][operation]["requests"][index]["audit"][key] = value
                    with self.subTest(operation=operation, audit=index, changed=key):
                        with self.assertRaises(ProtocolError): verify_report(report)
        report = passed(); report["writeReplayAuthority"]["update"]["requests"][-1]["etag"] = report["writeReplayAuthority"]["update"]["originalEtag"]
        with self.assertRaises(ProtocolError): verify_report(report)
        report = passed(); report["writeReplayAuthority"]["delete"]["requests"][-1]["body"]["success"] = 0
        with self.assertRaises(ProtocolError): verify_report(report)

    def test_duplicate_case(self):
        report = passed(); report["cases"][1] = copy.deepcopy(report["cases"][0])
        with self.assertRaises(ProtocolError): verify_report(report)

    def test_reordered_cases(self):
        report = passed(); report["cases"].reverse()
        with self.assertRaises(ProtocolError): verify_report(report)

    def test_failed_case(self):
        for case in range(len(CASES)):
            with self.subTest(case=case):
                report = passed(); report["cases"][case]["status"] = "failed"
                with self.assertRaises(ProtocolError): verify_report(report)

    def test_unexecuted_or_skipped_case(self):
        for status in ["did_not_run", "skipped", "registered"]:
            with self.subTest(status=status):
                report = passed(); report["cases"][0]["status"] = status
                with self.assertRaises(ProtocolError): verify_report(report)

    def test_totals_cannot_replace_or_disagree_with_cases(self):
        for report in [{"schemaVersion": 1, "executed": 16, "passed": 16},
                       dict(passed(), executed=0), dict(passed(), passed=15), dict(passed(), schemaVersion=2)]:
            with self.subTest(report=report):
                with self.assertRaises(ProtocolError): verify_report(report)

    def test_fixture_failure_keeps_denominator_without_echoing_body(self):
        calls = []
        def request(*args, **kwargs):
            calls.append(args)
            return 200, {"code": 500, "message": "private-credential-sentinel"}, {}
        with tempfile.TemporaryDirectory() as directory:
            evidence = Path(directory) / "result.json"
            with self.assertRaises(ProtocolError) as failure:
                run_dynamic_probe(request, {"Authorization": "private-token-sentinel"}, evidence_path=evidence)
            report = json.loads(evidence.read_text())
            self.assertEqual([row["id"] for row in report["cases"]], list(CASES))
            self.assertEqual(report["executed"], 0)
            self.assertEqual(len(calls), 1)
            self.assertNotIn("sentinel", str(failure.exception) + evidence.read_text())
            with self.assertRaises(ProtocolError): verify_report(report)

    def test_import_has_no_network_or_provisioning(self):
        import importlib
        import open_platform_dynamic_probe
        with patch("urllib.request.OpenerDirector.open", side_effect=AssertionError("import network")):
            importlib.reload(open_platform_dynamic_probe)


class SdkReportTests(unittest.TestCase):
    def report(self):
        from open_platform_dynamic_probe import verify_sdk_report
        self.verify = verify_sdk_report
        profile = json.loads((Path(__file__).resolve().parents[1] /
                             "fixtures/open-platform-consumer/real-backend.profile.json").read_text())
        return {"schemaVersion": 1, "profile": profile["id"], "cases": [
            {"id": case, "status": "passed"} for case in profile["cases"]],
            "executed": 13, "passed": 13, "requests": 17, "tokenExchanges": 1}

    def test_actual_fixed_sdk_report(self):
        report = self.report(); self.assertEqual(self.verify(report), report)

    def test_missing_sdk_case(self):
        report = self.report(); report["cases"].pop()
        with self.assertRaises(ProtocolError): self.verify(report)

    def test_duplicate_sdk_case(self):
        report = self.report(); report["cases"][1] = report["cases"][0]
        with self.assertRaises(ProtocolError): self.verify(report)

    def test_failed_skipped_registered_sdk_cases(self):
        for status in ["failed", "skipped", "registered", "did_not_run"]:
            report = self.report(); report["cases"][0]["status"] = status
            with self.assertRaises(ProtocolError): self.verify(report)

    def test_fake_sdk_totals(self):
        for key, value in [("executed", 0), ("passed", 12), ("requests", 0), ("tokenExchanges", 0), ("tokenExchanges", 2)]:
            report = self.report(); report[key] = value
            with self.assertRaises(ProtocolError): self.verify(report)

    def test_wrong_sdk_profile(self):
        report = self.report(); report["profile"] = "loopback-fixture"
        with self.assertRaises(ProtocolError): self.verify(report)

    def test_release_requires_sdk_in_addition_to_dynamic_http(self):
        from test_open_platform_model_event_probe import passed as model_passed, load_passed, sustained_passed, isolation_passed, SECRET
        sdk = self.report()
        with self.assertRaises(ProtocolError): verify_release_report({"dynamic": passed()})
        with self.assertRaises(ProtocolError):
            verify_release_report({"dynamic": passed(), "sdk": sdk}, model_event_secret=SECRET)
        with self.assertRaises(ProtocolError):
            verify_release_report({"dynamic": passed(), "sdk": sdk, "modelEvents": model_passed()}, model_event_secret=SECRET)
        with self.assertRaises(ProtocolError):
            verify_release_report({"dynamic": passed(), "sdk": sdk, "modelEvents": model_passed(), "commandAudits": command_passed()},
                                  model_event_secret=SECRET)
        measured = model_passed(); measured["loadMeasurement"] = load_passed(); measured["sustainedMeasurement"] = sustained_passed(); measured["receiverIsolation"] = isolation_passed()
        self.assertEqual(verify_release_report({"dynamic": passed(), "sdk": sdk, "modelEvents": measured, "commandAudits": command_passed()},
                                              model_event_secret=SECRET)["sdk"], sdk)
        for field in ['loadMeasurement', 'sustainedMeasurement', 'receiverIsolation']:
            omitted = dict(measured); omitted.pop(field)
            with self.assertRaises(ProtocolError):
                verify_release_report({"dynamic": passed(), "sdk": sdk, "modelEvents": omitted,
                    "commandAudits": command_passed()}, model_event_secret=SECRET)
        measured["loadMeasurement"]["metrics"]["accepted"] = 0
        with self.assertRaises(ProtocolError):
            verify_release_report({"dynamic": passed(), "sdk": sdk, "modelEvents": measured, "commandAudits": command_passed()},
                                  model_event_secret=SECRET)

    def test_consumer_private_stdin_and_fixed_safe_ledger(self):
        import subprocess
        report = self.report()
        report["secret"] = "private-sentinel"
        with tempfile.TemporaryDirectory() as directory:
            evidence = Path(directory) / "sdk.json"
            with patch("subprocess.run", return_value=subprocess.CompletedProcess([], 0, json.dumps(report), "private-sentinel")) as child:
                actual = run_sdk_consumer({"clientSecret": "private-sentinel"}, ["node", "consumer"],
                                          base_url="http://real-backend", evidence_path=evidence)
            self.assertEqual(actual["passed"], 13)
            self.assertIn("private-sentinel", child.call_args.kwargs["input"])
            self.assertNotIn("private-sentinel", json.dumps(child.call_args.args))
            self.assertEqual(child.call_args.kwargs["timeout"], 90)
            self.assertNotIn("sentinel", evidence.read_text())

    def test_consumer_nonzero_cannot_pass_from_complete_stdout(self):
        import subprocess
        report = self.report()
        with tempfile.TemporaryDirectory() as directory:
            with patch("subprocess.run", return_value=subprocess.CompletedProcess([], 1, json.dumps(report), "private-sentinel")):
                with self.assertRaises(ProtocolError) as failure:
                    run_sdk_consumer({}, ["node"], base_url="http://backend", evidence_path=Path(directory)/"sdk.json")
                self.assertNotIn("sentinel", str(failure.exception))

    def test_consumer_timeout_bad_stdout_and_missing_command_are_redacted(self):
        import subprocess
        for problem in [subprocess.TimeoutExpired(["private-sentinel"], 90), OSError("private-sentinel")]:
            with patch("subprocess.run", side_effect=problem):
                with self.assertRaises(ProtocolError) as failure:
                    run_sdk_consumer({}, [], base_url="http://backend", evidence_path=Path("unused"))
                self.assertNotIn("sentinel", str(failure.exception))
        with patch("subprocess.run", return_value=subprocess.CompletedProcess([], 0, "private-sentinel", "")):
            with self.assertRaises(ProtocolError) as failure:
                run_sdk_consumer({}, [], base_url="http://backend", evidence_path=Path("unused"))
            self.assertNotIn("sentinel", str(failure.exception))

    def test_failed_consumer_keeps_safe_execution_denominator(self):
        import subprocess
        report = self.report()
        report["cases"][2]["status"] = "failed"
        for row in report["cases"][3:]: row["status"] = "did_not_run"
        report.update(executed=3, passed=2, requests=4)
        with tempfile.TemporaryDirectory() as directory:
            evidence = Path(directory)/"sdk.json"
            with patch("subprocess.run", return_value=subprocess.CompletedProcess([], 1, json.dumps(report), "")):
                with self.assertRaises(ProtocolError):
                    run_sdk_consumer({}, [], base_url="http://backend", evidence_path=evidence)
            self.assertEqual(json.loads(evidence.read_text())["cases"], report["cases"])


if __name__ == "__main__":
    unittest.main()
