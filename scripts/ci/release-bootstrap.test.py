"""Hermetic bootstrap harness checks; real image acceptance remains the Linux suite."""

import copy
import importlib.util
from pathlib import Path
import unittest
import tempfile
import json

spec = importlib.util.spec_from_file_location("release_bootstrap", Path(__file__).with_name("release-bootstrap.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class BootstrapTests(unittest.TestCase):
    def test_replay_actor_dependencies_require_success_and_published_command(self):
        for fault in (None, "import", "envelope", "command"):
            calls = []
            def request(path, **kwargs):
                calls.append((path, kwargs))
                if "import-directory-sync" in path:
                    result = {"success": fault != "import"}
                    return 200, {"code": "0", "data": result} if fault == "envelope" else result, {}
                return 200, {"code": "0", "data": [] if fault == "command" else [{"code": "admin:create_member"}]}, {}
            if fault:
                with self.assertRaises(RuntimeError):
                    module.provision_replay_actor_commands(request, {"Authorization": "fixture"})
            else:
                module.provision_replay_actor_commands(request, {"Authorization": "fixture"})
                self.assertEqual([row[1]["body"]["path"] for row in calls[:3]],
                                 ["/plugins/core-meta", "/plugins/org-management", "/plugins/platform-admin"])
                self.assertTrue(all(row[1]["body"]["autoPublishCommands"] for row in calls[:3]))
                self.assertTrue(all(row[1]["body"]["validateReferences"] for row in calls[:3]))
                self.assertEqual(calls[-1][0], "/api/meta/commands?modelCode=tenant_member")

    def test_internal_document_capture_uses_the_bootstrap_session_and_excludes_it_from_output(self):
        admin = {"Authorization": "Bearer fixture-private-session"}
        document = {"openapi": "3.1.0", "paths": {"/api/read": {"get": {"responses": {"200": {"description": "OK"}}}}}}
        calls = []
        def request(path, **kwargs):
            calls.append((path, kwargs))
            return 200, document, {}
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "openapi.json"
            module.capture_internal_document(request, admin, output)
            self.assertEqual(calls, [("/v3/api-docs/internal-web-admin", {"headers": admin})])
            self.assertEqual(json.loads(output.read_text()), document)
            self.assertNotIn("fixture-private-session", output.read_text())
            with self.assertRaises(FileExistsError):
                module.capture_internal_document(request, admin, output)
            self.assertEqual(json.loads(output.read_text()), document)

    def test_internal_document_capture_refuses_missing_identity_or_error_documents(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "openapi.json"
            for admin, status, document in [
                ({}, 200, {}),
                ({"Authorization": "Bearer fixture"}, 401, {"code": "401"}),
                ({"Authorization": "Bearer fixture"}, 200, {"code": "0", "data": {}}),
                ({"Authorization": "Bearer fixture"}, 200, {"openapi": "3.1.0", "paths": {}}),
            ]:
                with self.subTest(admin=bool(admin), status=status, document=document):
                    with self.assertRaises(RuntimeError):
                        module.capture_internal_document(lambda *a, **kw: (status, document, {}), admin, output)
                    self.assertFalse(output.exists())

    def run_probe(self, changes=None):
        responses = [
            {"initialized": False, "inProgress": False},
            {"success": True, "tenantId": "9007199254740993"},
            {"initialized": True}, {"jwt": "login-session"},
            [{"spaceType": "platform", "tenantId": 0},
             {"spaceType": "business", "tenantId": "9007199254740993"}],
            {"jwt": "tenant-session"},
        ]
        for index, replacement in (changes or {}).items():
            responses[index] = replacement
        calls = []

        def request(path, **kwargs):
            calls.append((path, copy.deepcopy(kwargs)))
            return 200, {"code": "0", "data": responses[len(calls) - 1]}, {}

        result = module.bootstrap_admin(request)
        return result, calls

    def test_selects_exact_created_tenant_and_returns_scoped_session(self):
        result, calls = self.run_probe()
        self.assertEqual(result, {"Authorization": "Bearer tenant-session"})
        self.assertEqual(calls[-1][1]["body"], {"action": "select", "tenantId": "9007199254740993"})
        self.assertEqual(calls[-1][1]["headers"], {"Authorization": "Bearer login-session"})
        setup, login = calls[1][1]["body"], calls[3][1]["body"]
        self.assertEqual(login["password"], setup["adminPassword"])
        self.assertGreater(len(login["password"]), 40)
        self.assertFalse(any("/api/test/" in path for path, _ in calls))

    def test_refuses_preinitialized_database(self):
        with self.assertRaisesRegex(RuntimeError, "fresh"):
            self.run_probe({0: {"initialized": True, "inProgress": False}})

    def test_refuses_running_bootstrap(self):
        with self.assertRaisesRegex(RuntimeError, "fresh"):
            self.run_probe({0: {"initialized": False, "inProgress": True}})

    def test_refuses_unsuccessful_setup(self):
        with self.assertRaisesRegex(RuntimeError, "did not create"):
            self.run_probe({1: {"success": False, "tenantId": "9007199254740993"}})

    def test_requires_persisted_initialization(self):
        with self.assertRaisesRegex(RuntimeError, "persist"):
            self.run_probe({2: {"initialized": False}})

    def test_refuses_wrong_business_tenant(self):
        with self.assertRaisesRegex(RuntimeError, "not a member"):
            self.run_probe({4: [{"spaceType": "business", "tenantId": 12}]})

    def test_requires_selected_session(self):
        with self.assertRaisesRegex(RuntimeError, "no session"):
            self.run_probe({5: {}})

    def test_rejects_failure_envelope_without_exposing_payload(self):
        with self.assertRaisesRegex(RuntimeError, "unsuccessful API envelope") as caught:
            module.response_data({"code": "1", "data": {"secret": "never-print"}}, "login")
        self.assertNotIn("never-print", str(caught.exception))


if __name__ == "__main__":
    unittest.main()
