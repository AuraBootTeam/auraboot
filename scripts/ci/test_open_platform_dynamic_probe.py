"""Hermetic result-verifier tests; business execution requires a real backend."""
import copy
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from open_platform_dynamic_probe import CASES, run_dynamic_probe, run_sdk_consumer, verify_report, verify_release_report, verify_command_audits, prepare_slo_fixture, run_source_binding_probe, import_public_plugin, run_public_plugin_metadata_rollback
from open_platform_http import ProtocolError
from open_platform_dynamic_probe import PLUGIN_QUERY_SOURCE_FINGERPRINT


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
    quota_audit = dict(audits[1], requestId="release-dynamic-query-quota", status=429,
                       resource="release.dynamic.by-zone.quota",
                       path="/api/open/v1/queries/release.dynamic.by-zone.quota:execute",
                       capability="query.release.dynamic.by-zone.quota.execute")
    del quota_audit["id"]
    return {"schemaVersion": 1, "cases": [{"id": case, "status": "passed"} for case in CASES],
            "executed": len(CASES), "passed": len(CASES), "auditProofs": audits,
            "pluginImports": [{"pluginId": plugin, "importId": "import-pid", "pluginPid": "plugin-pid",
                               "resourceType": kind, "created": 1, "success": True}
                              | ({"namedQueryCreated": 1, "namedQueryPid": "new-query-pid"} if kind == "PUBLIC_QUERY" else {})
                              for plugin, kind in [("release-public-resource", "PUBLIC_RESOURCE"), ("release-public-query", "PUBLIC_QUERY")]],
            "pluginQuerySource": {"beforeStatus": 400, "queryCode": "release_dynamic_by_zone", "sourcePid": "new-query-pid",
                "sourceStatus": "published", "sourceVersion": 0, "fingerprint": PLUGIN_QUERY_SOURCE_FINGERPRINT,
                "importId": "import-pid", "pluginPid": "plugin-pid"},
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
            "queryRateLimit": {"status": 429, "code": "open_api_query_rate_limited", "retryAfter": "60",
                               "requestId": "release-dynamic-query-quota", "audit": quota_audit}}


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

    def test_source_binding_proof_is_mandatory_and_preserves_the_original_record_and_etag(self):
        report = passed(); del report["resourceSourceBinding"]
        with self.assertRaises(ProtocolError): verify_report(report)
        for key, value in [("status", 200), ("code", "access_denied"), ("requestId", "foreign-request"),
                           ("error", {"success": False, "code": "open_api_source_changed", "message": "Private source binding sentinel"}),
                           ("baselineEtag", None), ("restoredStatus", 403), ("restoredEtag", '"another-value"'),
                           ("restored", {"pid": "source-pid", "name": "Private source binding sentinel"})]:
            report = passed(); report["resourceSourceBinding"][key] = value
            with self.subTest(key=key), self.assertRaises(ProtocolError): verify_report(report)

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
        from test_open_platform_model_event_probe import passed as model_passed, SECRET
        sdk = self.report()
        with self.assertRaises(ProtocolError): verify_release_report({"dynamic": passed()})
        with self.assertRaises(ProtocolError):
            verify_release_report({"dynamic": passed(), "sdk": sdk}, model_event_secret=SECRET)
        with self.assertRaises(ProtocolError):
            verify_release_report({"dynamic": passed(), "sdk": sdk, "modelEvents": model_passed()}, model_event_secret=SECRET)
        self.assertEqual(verify_release_report({"dynamic": passed(), "sdk": sdk, "modelEvents": model_passed(), "commandAudits": command_passed()},
                                              model_event_secret=SECRET)["sdk"], sdk)

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
