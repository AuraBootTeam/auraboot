"""Hermetic result-verifier tests; business execution requires a real backend."""
import copy
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from open_platform_dynamic_probe import CASES, run_dynamic_probe, verify_report
from open_platform_http import ProtocolError


def passed():
    return {"schemaVersion": 1, "cases": [{"id": case, "status": "passed"} for case in CASES],
            "executed": len(CASES), "passed": len(CASES)}


class DynamicProbeTests(unittest.TestCase):
    def test_fixed_complete_report(self):
        self.assertEqual(verify_report(passed()), passed())

    def test_missing_case(self):
        report = passed(); report["cases"].pop()
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


if __name__ == "__main__":
    unittest.main()
