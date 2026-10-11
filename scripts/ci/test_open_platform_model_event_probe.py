"""Hermetic gate-verifier tests; these do not qualify a Linux release image."""
import base64
import copy
import hashlib
import hmac
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from open_platform_http import ProtocolError
from open_platform_model_event_probe import CASES, Receivers, read_secret, run_model_event_probe, run_source_binding_replay, verify_report
from open_platform_model_event_runtime import DockerInfrastructure

SECRET = b"model-event-verifier-test-secret-32-bytes"


def passed():
    proofs = []
    for group, (statuses, name, origin) in enumerate(zip(
            [[503, 200], [503, 503, 503], [200]],
            ["Pending source before restart", "Bounded delivery failure", "Bounded delivery failure"],
            ["release-model-pending-source", "release-model-dead-letter", "release-model-dead-letter"])):
        source = "pending" if group == 0 else "dead"
        payload = {"id": "evt_" + source, "type": "open.resource.release.model-event.parts.created", "schemaVersion": 1,
            "occurredAt": "2026-10-11T00:00:00Z", "subject": {"type": "release.model-event.parts", "pid": "pid_" + source},
            "recordVersion": 1, "correlationId": origin, "data": {"pid": "pid_" + source, "name": name, "zone": "A"}}
        raw = json.dumps(payload).encode()
        evidence = [{"sequence": index + 1, "receivedAt": 1700000000., "timestamp": "1700000000",
            "signature": "sha256=" + hmac.new(SECRET, b"1700000000." + raw, hashlib.sha256).hexdigest(),
            "responseStatus": status, "requestId": origin, "duplicate": False,
            "rawBodyBase64": base64.b64encode(raw).decode(), "bodySha256": hashlib.sha256(raw).hexdigest()}
            for index, status in enumerate(statuses)]
        proofs.append({"deliveryPid": "delivery_" + source, "eventId": payload["id"], "subjectPid": "pid_" + source,
                       "attempts": len(statuses), "statuses": statuses, "rawEvidence": evidence})
    before = {"runtimeId": "owned-runtime", "artifactDigest": "sha256:" + "a" * 64, "generation": "old"}
    return {"schemaVersion": 1, "registered": 18, "cases": [{"id": case, "status": "passed"} for case in CASES],
        "executed": 18, "passed": 18, "sourceProof": {"events": 1, "receipts": 1, "deliveries": 1},
        "deadLetterProof": {"events": 1, "receipts": 1, "deliveries": 1}, "beforeRestart": before,
        "afterRestart": dict(before, generation="new"), "deliveryProofs": proofs,
        "sourceBindingProof": {"eventId": "evt_dead", "deliveryPid": "delivery_dead", "subjectPid": "pid_dead",
            "blockedStatus": "dead_letter", "blockedReplayCount": 1, "blockedRetryCount": 1,
            "receiverAttemptsBefore": 0, "receiverAttemptsAfter": 0, "restoredStatus": "success", "restoredReplayCount": 2}}


class ModelEventTests(unittest.TestCase):
    def test_source_binding_proof_is_mandatory_and_linked_to_signed_restoration(self):
        for key in passed()["sourceBindingProof"]:
            report = passed(); del report["sourceBindingProof"][key]
            with self.assertRaises(ProtocolError): verify_report(report, secret=SECRET)
        for change in [lambda r: r.pop("sourceBindingProof"),
                       lambda r: r["sourceBindingProof"].update(eventId="other"),
                       lambda r: r["sourceBindingProof"].update(blockedStatus="success"),
                       lambda r: r["sourceBindingProof"].update(blockedRetryCount=0),
                       lambda r: r["sourceBindingProof"].update(receiverAttemptsAfter=1),
                       lambda r: r["sourceBindingProof"].update(receiverAttemptsBefore=False),
                       lambda r: r["sourceBindingProof"].update(restoredReplayCount=1)]:
            report = passed(); change(report)
            with self.assertRaises(ProtocolError): verify_report(report, secret=SECRET)

    def test_source_binding_replay_restores_management_metadata_on_success_and_failure(self):
        model = {"displayName": "Model", "fields": None, "extension": {"sourceRef": "fixture",
                 "fields": [{"code": "rel_name", "columnName": "rel_name"},
                            {"code": "rel_private", "columnName": "rel_private"}]}}
        dead = {"pid": "delivery_dead", "eventId": "evt_dead"}
        for fail in [False, True]:
            calls = []
            def human(path, method="GET", body=None):
                calls.append((path, method, copy.deepcopy(body)))
                return copy.deepcopy(model) if method == "GET" else {}
            def wait():
                if fail: raise ProtocolError("observation failed")
                return dict(dead, deliveryStatus="dead_letter", replayCount=1, retryCount=1)
            if fail:
                with self.assertRaises(ProtocolError):
                    run_source_binding_replay(human, "model_pid", "installation_pid", dead, wait, lambda: 0)
            else:
                proof = run_source_binding_replay(human, "model_pid", "installation_pid", dead, wait, lambda: 0)
                self.assertEqual(proof["receiverAttemptsAfter"], 0)
            self.assertEqual(calls[1][2]["extension"]["fields"][0]["columnName"], "rel_private")
            self.assertEqual(calls[-1], ("/api/meta/models/model_pid", "PUT", {
                "displayName": "Model", "extension": model["extension"]}))
            self.assertEqual(model["extension"]["fields"][0]["columnName"], "rel_name")

    def test_missing_management_extension_fields_refuses_before_metadata_mutation(self):
        for fields in [None, "invalid", [None]]:
            calls = []
            def human(path, method="GET", body=None):
                calls.append((path, method))
                return {"displayName": "Model", "fields": [{"code": "rel_name"}], "extension": {"fields": fields}}
            with self.assertRaises(ProtocolError):
                run_source_binding_replay(human, "model", "installation", {"pid": "delivery"}, lambda: {}, lambda: 0)
            self.assertEqual(calls, [("/api/meta/models/model", "GET")])

    def test_complete_fixed_report_recomputes_all_six_signatures(self):
        report = passed()
        self.assertEqual(verify_report(report, secret=SECRET), report)

    def test_denominator_omission_duplication_order_and_expansion(self):
        for mutate in [lambda r: r["cases"].pop(), lambda r: r["cases"].append(r["cases"][0]),
                       lambda r: r["cases"].reverse(), lambda r: r["cases"].__setitem__(1, r["cases"][0])]:
            report = passed(); mutate(report)
            with self.assertRaises(ProtocolError): verify_report(report, secret=SECRET)

    def test_each_failed_or_unexecuted_case_blocks(self):
        for case in range(len(CASES)):
            for status in ["failed", "did_not_run", "skipped", "registered"]:
                report = passed(); report["cases"][case]["status"] = status
                with self.assertRaises(ProtocolError): verify_report(report, secret=SECRET)

    def test_fake_totals_duplicate_durable_rows_and_bool_counts_block(self):
        for change in [lambda r: r.update(executed=0), lambda r: r.update(passed=True),
                       lambda r: r.update(schemaVersion=True),
                       lambda r: r["sourceProof"].update(events=2), lambda r: r["deadLetterProof"].update(receipts=True)]:
            report = passed(); change(report)
            with self.assertRaises(ProtocolError): verify_report(report, secret=SECRET)

    def test_wrong_runtime_image_or_unchanged_process_blocks(self):
        for key in ["runtimeId", "artifactDigest", "generation"]:
            report = passed(); report["afterRestart"][key] = report["beforeRestart"][key] if key == "generation" else "foreign"
            with self.assertRaises(ProtocolError): verify_report(report, secret=SECRET)

    def test_raw_attempt_missing_duplicate_or_status_mismatch_blocks(self):
        for change in [lambda r: r["deliveryProofs"].pop(), lambda r: r["deliveryProofs"][0]["rawEvidence"].pop(),
                       lambda r: r["deliveryProofs"][1].update(eventId="another-source"),
                       lambda r: r["deliveryProofs"][0]["rawEvidence"][0].update(responseStatus=200)]:
            report = passed(); change(report)
            with self.assertRaises(ProtocolError): verify_report(report, secret=SECRET)

    def test_raw_body_signature_and_projection_corruption_block_despite_passed_cases(self):
        for change in [lambda row: row.update(signature="sha256=" + "0" * 64),
                       lambda row: row.update(bodySha256="0" * 64), lambda row: row.update(rawBodyBase64="invalid")]:
            report = passed(); change(report["deliveryProofs"][0]["rawEvidence"][0])
            with self.assertRaises(ProtocolError): verify_report(report, secret=SECRET)
        report = passed(); row = report["deliveryProofs"][0]["rawEvidence"][0]
        body = json.loads(base64.b64decode(row["rawBodyBase64"])); body["data"]["secret"] = "never-public"
        raw = json.dumps(body).encode()
        row.update(rawBodyBase64=base64.b64encode(raw).decode(), bodySha256=hashlib.sha256(raw).hexdigest(),
                   signature="sha256=" + hmac.new(SECRET, row["timestamp"].encode() + b"." + raw, hashlib.sha256).hexdigest())
        with self.assertRaises(ProtocolError): verify_report(report, secret=SECRET)

    def test_missing_or_wrong_private_signature_key_blocks(self):
        with self.assertRaises(ProtocolError): verify_report(passed())
        with self.assertRaises(ProtocolError): verify_report(passed(), secret=b"wrong-key")

    def test_fixture_failure_preserves_all_cases_and_redacts_credentials(self):
        with tempfile.TemporaryDirectory() as directory:
            evidence, key = Path(directory)/"result.json", Path(directory)/"private.key"
            with self.assertRaises(ProtocolError) as failure:
                run_model_event_probe(lambda *a, **k: (200, {"code": 500, "message": "private-sentinel"}, {}),
                    {"Authorization": "private-sentinel"}, None, evidence_path=evidence,
                    receiver_host="127.0.0.1", secret_file=key)
            report = json.loads(evidence.read_text())
            self.assertEqual([r["id"] for r in report["cases"]], list(CASES))
            self.assertEqual(report["executed"], 0)
            self.assertNotIn("sentinel", str(failure.exception) + evidence.read_text())
            self.assertEqual(key.stat().st_mode & 0o077, 0)

    def test_private_key_rejects_symlink_and_world_readable_file(self):
        with tempfile.TemporaryDirectory() as directory:
            key = Path(directory)/"key"; key.write_bytes(SECRET); key.chmod(0o600)
            self.assertEqual(read_secret(key), SECRET)
            link = Path(directory)/"link"; link.symlink_to(key)
            with self.assertRaises(ProtocolError): read_secret(link)
            key.chmod(0o644)
            with self.assertRaises(ProtocolError): read_secret(key)

    def test_receiver_refuses_public_wildcard_and_ipv6_bind(self):
        for host in ["0.0.0.0", "1.1.1.1", "::1", "224.0.0.1"]:
            with self.assertRaises(ProtocolError): Receivers("unused", host, SECRET)

    def test_import_never_contacts_a_runtime(self):
        import importlib
        import open_platform_model_event_runtime
        with patch("subprocess.run", side_effect=AssertionError("import container control")):
            importlib.reload(open_platform_model_event_runtime)


class RuntimeSafetyTests(unittest.TestCase):
    def fixture(self):
        adapter = object.__new__(DockerInfrastructure)
        adapter.app, adapter.postgres, adapter.network = "owned-app", "owned-pg", "owned-net"
        adapter.job_id, adapter.source_ref = "owned-job", "a" * 40
        adapter.image_digest, adapter.base_url, adapter.receiver_host = "sha256:" + "b" * 64, "http://127.0.0.1:1234", "10.247.4.1"
        adapter.crashed_id = None
        row = {"Name": "/owned-app", "Id": "c" * 64, "Image": adapter.image_digest,
            "Config": {"Labels": {"aura.job-id": adapter.job_id, "aura.source-ref": adapter.source_ref},
                       "Env": ["AURA_SSRF_ALLOWED_PRIVATE_HOSTS=10.247.4.1", "JWT_SECRET=private-sentinel"]},
            "State": {"Running": True, "StartedAt": "old", "ExitCode": 0},
            "NetworkSettings": {"Networks": {adapter.network: {}}, "Ports": {"6443/tcp": [{"HostIp": "127.0.0.1", "HostPort": "1234"}]}}}
        network = [{"Name": adapter.network, "Driver": "bridge", "IPAM": {"Config": [{"Gateway": adapter.receiver_host}]}}]
        return adapter, row, network

    def test_ownership_checks_block_wrong_labels_image_network_and_port_before_kill(self):
        for change in [lambda r: r["Config"]["Labels"].update({"aura.job-id": "foreign"}),
                       lambda r: r.update(Image="foreign"), lambda r: r["NetworkSettings"]["Networks"].update(foreign={}),
                       lambda r: r["NetworkSettings"]["Ports"].update({"6443/tcp": [{"HostIp": "0.0.0.0", "HostPort": "1234"}]})]:
            adapter, row, network = self.fixture(); change(row)
            with patch.object(adapter, "docker", return_value=json.dumps([row])) as docker:
                with self.assertRaises(ProtocolError) as failure: adapter.crash({})
                self.assertTrue(all(call.args[0] == "inspect" for call in docker.call_args_list))
                self.assertNotIn("sentinel", str(failure.exception))

    def test_correct_identity_returns_only_safe_fields(self):
        adapter, row, network = self.fixture()
        with patch.object(adapter, "docker", side_effect=[json.dumps([row]), json.dumps(network)]):
            identity = adapter.identity()
        self.assertEqual(set(identity), {"runtimeId", "artifactDigest", "generation"})
        self.assertNotIn("sentinel", json.dumps(identity))

    def test_crash_targets_exact_id_and_requires_sigkill_terminal_state(self):
        adapter, row, network = self.fixture()
        expected = {"runtimeId": row["Id"], "artifactDigest": row["Image"], "generation": "old"}
        stopped = copy.deepcopy(row); stopped["State"].update(Running=False, ExitCode=137)
        with patch.object(adapter, "docker", side_effect=[json.dumps([row]), json.dumps(network), "", json.dumps([stopped])]) as docker:
            adapter.crash(expected)
        self.assertEqual(docker.call_args_list[2].args, ("kill", "--signal=KILL", row["Id"]))
        adapter, row, network = self.fixture()
        with patch.object(adapter, "docker", side_effect=[json.dumps([row]), json.dumps(network)]) as docker:
            with self.assertRaises(ProtocolError): adapter.crash(dict(expected, generation="foreign"))
            self.assertFalse(any(call.args[0] == "kill" for call in docker.call_args_list))

    def test_observations_reject_injection_and_nonselect_before_control(self):
        adapter, row, network = self.fixture()
        with patch.object(adapter, "docker", side_effect=AssertionError("unexpected control")):
            for value in ["evt' OR 1=1", "evt; DELETE", ""]:
                with self.assertRaises(ProtocolError): adapter.counts(1, value)
            with self.assertRaises(ProtocolError): adapter.source(True, "event", "subject")
            with self.assertRaises(ProtocolError): adapter.sql("DELETE FROM ab_outbox")

    def test_nonlinux_constructor_cannot_use_local_containers(self):
        with patch("platform.system", return_value="Darwin"), patch("subprocess.run", side_effect=AssertionError("local Docker")):
            with self.assertRaises(ProtocolError): DockerInfrastructure(app="a", postgres="p", network="n", job_id="j",
                source_ref="a"*40, image_digest="sha256:"+"b"*64, base_url="http://127.0.0.1:1", receiver_host="10.247.1.1")


if __name__ == "__main__":
    unittest.main()
