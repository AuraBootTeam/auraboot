"""Hermetic session-routing checks; production acceptance requires the actual Core runtime."""

import copy
import importlib.util
from pathlib import Path
import unittest
import json
import os
import tempfile

spec = importlib.util.spec_from_file_location("core_admin", Path(__file__).with_name("core-admin-session.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class CoreAdminSessionTests(unittest.TestCase):
    def authenticate(self, spaces=None, selected=None, tenant=None, login=None):
        responses = [login or {"jwt": "login-token"}, spaces if spaces is not None else [
            {"spaceType": "platform", "tenantId": "1"},
            {"spaceType": "business", "tenantId": "9007199254740993"},
        ], selected if selected is not None else {"jwt": "selected-token", "tenantId": "9007199254740993"}]
        self.calls = []

        def request(path, **kwargs):
            self.calls.append((path, copy.deepcopy(kwargs)))
            return {"code": "0", "data": responses[len(self.calls) - 1]}

        return module.business_session(request, 'admin"@test', 'password"\\value', tenant)

    def test_selects_business_not_platform_and_preserves_string_id(self):
        self.assertEqual(self.authenticate(), "selected-token")
        self.assertEqual(self.calls[-1], ("/api/tenant-selection/process", {
            "method": "POST", "headers": {"Authorization": "Bearer login-token"},
            "body": {"action": "select", "tenantId": "9007199254740993"},
        }))
        self.assertEqual(self.calls[0][1]["body"]["password"], 'password"\\value')

    def test_multiple_business_spaces_require_explicit_selection(self):
        with self.assertRaisesRegex(RuntimeError, "AURA_ADMIN_TENANT_ID"):
            self.authenticate(spaces=[{"spaceType": "business", "tenantId": "2"},
                {"spaceType": "business", "tenantId": "3"}])
        self.assertEqual(len(self.calls), 2)

    def test_explicit_business_space_is_selected(self):
        self.assertEqual(self.authenticate(spaces=[{"spaceType": "business", "tenantId": "2"},
            {"spaceType": "business", "tenantId": "3"}], tenant="3",
            selected={"jwt": "selected-token", "tenantId": "3"}), "selected-token")
        self.assertEqual(self.calls[-1][1]["body"]["tenantId"], "3")

    def test_platform_space_cannot_be_selected_even_explicitly(self):
        with self.assertRaises(RuntimeError):
            self.authenticate(tenant="1")
        self.assertEqual(len(self.calls), 2)

    def test_missing_business_space_fails_closed(self):
        with self.assertRaises(RuntimeError):
            self.authenticate(spaces=[])

    def test_wrong_selected_tenant_is_rejected(self):
        with self.assertRaisesRegex(RuntimeError, "different tenant"):
            self.authenticate(selected={"jwt": "selected-token", "tenantId": "1"})

    def test_missing_selected_token_is_rejected(self):
        with self.assertRaisesRegex(RuntimeError, "no session"):
            self.authenticate(selected={"tenantId": "9007199254740993"})

    def test_missing_login_token_is_rejected(self):
        with self.assertRaisesRegex(RuntimeError, "no session"):
            self.authenticate(login={"tenantId": "2"})

    def test_unsuccessful_envelope_never_exposes_response_data(self):
        with self.assertRaisesRegex(RuntimeError, "unsuccessful API envelope") as failure:
            module.response_data({"code": "401", "data": {"jwt": "private-token"}}, "login")
        self.assertNotIn("private-token", str(failure.exception))



class CachedCoreAdminSessionTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name) / "admin-session.json"
        self.calls = []
        self.identity = {"user": {"id": "7", "tenantId": "9007199254740993", "email": "admin@example.test"}}

    def request(self, path, **kwargs):
        self.calls.append((path, kwargs))
        if path == "/api/auth/login":
            data = {"jwt": "login-token"}
        elif path == "/api/tenant-selection/my-spaces":
            data = [{"spaceType": "business", "tenantId": "9007199254740993"}]
        elif path == "/api/tenant-selection/process":
            data = {"jwt": "business-token", "tenantId": "9007199254740993"}
        elif path == "/api/auth/me":
            data = self.identity
        else:
            self.fail("Unexpected API request")
        return {"code": "0", "data": data}

    def session(self, request=None):
        return module.cached_business_session(request or self.request, "admin@example.test", "private-password",
                str(self.path), "http://127.0.0.1:6605")

    def test_repeated_import_reuses_private_session_and_verifies_live_identity_without_another_login(self):
        self.assertEqual(self.session(), "business-token")
        self.assertEqual(self.path.stat().st_mode & 0o777, 0o600)
        original = self.path.read_bytes()
        self.calls.clear()
        self.assertEqual(self.session(), "business-token")
        self.assertEqual([call[0] for call in self.calls], ["/api/auth/me"])
        self.assertEqual(self.path.read_bytes(), original)

    def test_foreign_runtime_cache_is_rejected_before_network_io(self):
        self.session()
        saved = json.loads(self.path.read_text()); saved["backendUrl"] = "http://127.0.0.1:6606"
        self.path.write_text(json.dumps(saved)); self.calls.clear()
        with self.assertRaisesRegex(RuntimeError, "does not match"):
            self.session()
        self.assertEqual(self.calls, [])

    def test_shared_or_symlink_cache_is_rejected_before_network_io(self):
        self.session()
        for state in ["shared", "symlink"]:
            if state == "shared":
                self.path.chmod(0o640)
            else:
                target = self.path.with_name("actual.json"); self.path.rename(target); self.path.symlink_to(target)
            self.calls.clear()
            with self.assertRaisesRegex(RuntimeError, "owned private regular"):
                self.session()
            self.assertEqual(self.calls, [])

    def test_revoked_session_fails_without_login_fallback_or_cache_rewrite(self):
        self.session(); original = self.path.read_bytes(); self.calls.clear()
        def revoked(path, **kwargs):
            self.calls.append((path, kwargs))
            return {"code": "401", "data": {"jwt": "must-not-be-printed"}}
        with self.assertRaisesRegex(RuntimeError, "unsuccessful API envelope") as failure:
            self.session(revoked)
        self.assertNotIn("must-not-be-printed", str(failure.exception))
        self.assertEqual([call[0] for call in self.calls], ["/api/auth/me"])
        self.assertEqual(self.path.read_bytes(), original)

    def test_changed_user_or_tenant_cannot_reuse_saved_session(self):
        self.session(); original = self.path.read_bytes()
        for key, value in [("id", "8"), ("tenantId", "99")]:
            previous = self.identity["user"][key]; self.identity["user"][key] = value
            with self.assertRaisesRegex(RuntimeError, "identity changed"):
                self.session()
            self.identity["user"][key] = previous
            self.assertEqual(self.path.read_bytes(), original)

    def test_wrong_account_cannot_create_session(self):
        self.identity["user"]["email"] = "other@example.test"
        with self.assertRaisesRegex(RuntimeError, "does not match"):
            self.session()
        self.assertFalse(self.path.exists())


if __name__ == "__main__":
    unittest.main()
