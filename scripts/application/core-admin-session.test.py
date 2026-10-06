"""Hermetic session-routing checks; production acceptance requires the actual Core runtime."""

import copy
import importlib.util
from pathlib import Path
import unittest

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


if __name__ == "__main__":
    unittest.main()
