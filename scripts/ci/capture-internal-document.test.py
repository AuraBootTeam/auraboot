"""Hermetic capture checks; image qualification still requires the admitted Linux runner."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("capture_internal", Path(__file__).with_name("capture-internal-document.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
DOCUMENT = {"openapi": "3.1.0", "paths": {"/api/read": {"get": {"responses": {"200": {"description": "OK"}}}}}}


class CaptureTests(unittest.TestCase):
    def run_capture(self, fault=None):
        calls = []
        def request(path, **kwargs):
            calls.append((path, kwargs))
            if path == "/api/auth/login":
                return 200, {"code": "0", "data": {} if fault == "missing-session" else {"jwt": "fixture-private-session"}}, {}
            if path == "/v3/api-docs/internal-web-admin":
                return (401, None, {}) if fault == "capture" else (200, DOCUMENT, {})
            if path == "/api/user/sessions/current":
                return 200, {"code": "1" if fault == "revoke" else "0", "data": None}, {}
            if path == "/api/auth/me":
                return (200, {"code": "0"}, {}) if fault == "still-authorized" else (401, None, {})
            raise AssertionError("Unexpected request: " + path)
        with tempfile.TemporaryDirectory() as directory:
            output, receipt = Path(directory) / "openapi.json", Path(directory) / "receipt.json"
            if fault:
                with self.assertRaises(RuntimeError):
                    module.capture(request, "fixture@example.invalid", "fixture-password", output, receipt)
                self.assertFalse(receipt.exists())
            else:
                result = module.capture(request, "fixture@example.invalid", "fixture-password", output, receipt)
                self.assertEqual(json.loads(output.read_text()), DOCUMENT)
                self.assertTrue(result["sessionRevoked"])
                self.assertFalse(result["bootstrapExecuted"])
                self.assertNotIn("fixture-private-session", output.read_text() + receipt.read_text())
                with self.assertRaises(RuntimeError):
                    module.capture(request, "fixture@example.invalid", "fixture-password", output, receipt)
                self.assertEqual(len(calls), 4, "Receipt reuse must fail before issuing another login")
        return calls

    def test_captures_existing_image_and_revokes_only_its_issued_session(self):
        calls = self.run_capture()
        self.assertEqual([call[0] for call in calls], ["/api/auth/login", "/v3/api-docs/internal-web-admin", "/api/user/sessions/current", "/api/auth/me"])
        self.assertEqual(calls[1][1]["headers"], calls[2][1]["headers"])

    def test_failed_capture_still_revokes_and_never_creates_success_receipt(self):
        calls = self.run_capture("capture")
        self.assertEqual(calls[-2][0], "/api/user/sessions/current")
        self.assertEqual(calls[-1][0], "/api/auth/me")

    def test_failed_revocation_cannot_qualify_capture(self):
        self.run_capture("revoke")

    def test_missing_session_cannot_capture_or_claim_revocation(self):
        self.assertEqual(len(self.run_capture("missing-session")), 1)

    def test_successful_revoke_response_does_not_substitute_for_denied_identity(self):
        self.run_capture("still-authorized")

    def test_credentials_cannot_be_sent_to_remote_or_redirected_origins(self):
        for base in ["https://production.example", "http://owner:password@127.0.0.1", "http://127.0.0.1/path", "http://127.0.0.1?redirect=x"]:
            with self.subTest(base=base), self.assertRaises(RuntimeError):
                module.transport(base)
        self.assertIsNone(module.NoRedirect().redirect_request(None, None, 302, "redirect", {}, "https://remote.invalid"))


if __name__ == "__main__":
    unittest.main()
