"""Hermetic result-verifier tests; business execution requires a real backend."""
import copy
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from open_platform_dynamic_probe import CASES, run_dynamic_probe, run_sdk_consumer, verify_report, verify_release_report
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
        self.assertEqual(verify_release_report({"dynamic": passed(), "sdk": sdk, "modelEvents": model_passed()},
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
