"""Hermetic gate-verifier tests; these do not qualify a Linux release image."""
import base64
import copy
from datetime import datetime, timezone
import hashlib
import hmac
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from open_platform_http import ProtocolError
from open_platform_model_event_probe import CASES, Receivers, read_secret, run_model_event_probe, run_source_binding_replay, verify_report, load_metrics
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
            "occurredAt": "2023-11-14T22:13:20Z", "subject": {"type": "release.model-event.parts", "pid": "pid_" + source},
            "recordVersion": 1, "correlationId": origin, "data": {"pid": "pid_" + source, "name": name, "zone": "A"}}
        raw = json.dumps(payload).encode()
        evidence = [{"sequence": index + 1, "receivedAt": 1700000000.125, "timestamp": "1700000000",
            "occurredAt": payload["occurredAt"], "eventAgeAtReceiptMs": 125.0,
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



def generic_replay_passed():
    originals = passed()["deliveryProofs"]
    proofs = [copy.deepcopy(originals[1]), copy.deepcopy(originals[2])]
    for proof in proofs:
        proof.update(deliveryPid="generic_delivery", eventId="generic_event", subjectPid="generic_subject")
        for row in proof["rawEvidence"]:
            payload = json.loads(base64.b64decode(row["rawBodyBase64"]))
            payload.update(id="generic_event", subject={"type": "release.model-event.parts", "pid": "generic_subject"},
                correlationId="release-model-generic-replay", data={"pid": "generic_subject", "name": "Generic replay boundary", "zone": "A"})
            raw = json.dumps(payload).encode()
            row.update(requestId="release-model-generic-replay", rawBodyBase64=base64.b64encode(raw).decode(),
                bodySha256=hashlib.sha256(raw).hexdigest(),
                signature="sha256="+hmac.new(SECRET,row["timestamp"].encode()+b"."+raw,hashlib.sha256).hexdigest())
    route="/api/webhooks/generic_sub/deliveries/generic_delivery/replay"
    denials={}
    for key, status in [("anonymous",401),("machine",401),("limitedHuman",403),("wrongParent",200),("repeated",200)]:
        denials[key]={"path":route if key!="wrongParent" else route.replace("generic_sub","other_sub"),
            "httpStatus":status,"code":"500" if status==200 else str(status),"beforeDigest":"a"*64,"afterDigest":"a"*64,
            "attemptsBefore":4 if key=="repeated" else 3,"attemptsAfter":4 if key=="repeated" else 3}
    return {"status":"complete","subscriptionPid":"generic_sub","wrongSubscriptionPid":"other_sub","denials":denials,
        "correctResponse":{"httpStatus":200,"code":"0","path":route},
        "finalState":{"deliveryStatus":"success","retryCount":0,"replayCount":1,"responseStatus":200},
        "counts":{"events":1,"receipts":1,"deliveries":1},"failedProof":proofs[0],"acceptedProof":proofs[1]}


def load_passed(count=32):
    measurement = {"profile": "burst-32x4", "authority": "recorded", "sloVerdict": "unqualified", "status": "complete",
        "planned": count, "concurrency": 4, "startedAt": 1700000000.0, "producedAt": 1700000000.1,
        "finishedAt": 1700000000.5, "rows": [], "queueSamples": []}
    pending, final = [], []
    for index in range(count):
        proof = copy.deepcopy(passed()["deliveryProofs"][2])
        proof.update(deliveryPid="load_delivery_" + str(index), eventId="load_event_" + str(index), subjectPid="load_pid_" + str(index))
        row = proof["rawEvidence"][0]
        payload = json.loads(base64.b64decode(row["rawBodyBase64"]))
        origin = "release-model-load-" + str(index)
        payload.update(id=proof["eventId"], subject={"type": "release.model-event.parts", "pid": proof["subjectPid"]},
                       correlationId=origin, data={"pid": proof["subjectPid"], "name": "Notification load " + str(index), "zone": "A"})
        raw = json.dumps(payload).encode()
        row.update(sequence=index+100, requestId=origin, rawBodyBase64=base64.b64encode(raw).decode(),
                   bodySha256=hashlib.sha256(raw).hexdigest(),
                   signature="sha256=" + hmac.new(SECRET, row["timestamp"].encode() + b"." + raw, hashlib.sha256).hexdigest())
        measurement["rows"].append({"index": index, "createStartedAt": 1700000000.001 + (index // 4) * .01,
                                   "createCompletedAt": 1700000000.009 + (index // 4) * .01, "proof": proof})
        state = {"subject": proof["subjectPid"], "eventId": proof["eventId"], "status": "delivered", "sequence": 1,
                 "receipts": 1, "deliveries": 1, "deliveryPid": proof["deliveryPid"], "deliveryStatus": "success",
                 "retryCount": 0, "responseStatus": 200}
        final.append(state)
        pending.append(dict(state, status="pending", receipts=0, deliveries=0, deliveryPid=None,
                            deliveryStatus=None, retryCount=None, responseStatus=None))
    measurement["queueSamples"] = [{"observedAt": 1700000000.11, "rows": pending}, {"observedAt": 1700000000.2, "rows": final}]
    measurement["metrics"] = load_metrics(measurement)
    return measurement


def sustained_passed():
    m = load_passed(300)
    m.update(profile='paced-60s-5rps', durationSeconds=60, ratePerSecond=5,
             producedAt=m['startedAt']+60, finishedAt=m['startedAt']+60.5)
    for row in m['rows']:
        slot = m['startedAt'] + row['index']/5
        row.update(scheduledAt=slot, createStartedAt=slot+.001, createCompletedAt=slot+.01)
        raw = row['proof']['rawEvidence'][0]
        payload = json.loads(base64.b64decode(raw['rawBodyBase64']))
        origin = 'release-model-sustained-' + str(row['index'])
        payload.update(correlationId=origin, occurredAt=datetime.fromtimestamp(slot+.002, timezone.utc).isoformat())
        body = json.dumps(payload).encode()
        received, timestamp = slot+.125, str(int(slot))
        raw.update(requestId=origin, occurredAt=payload['occurredAt'], receivedAt=received, timestamp=timestamp,
            eventAgeAtReceiptMs=round((received-datetime.fromisoformat(payload['occurredAt']).timestamp())*1000,3),
            rawBodyBase64=base64.b64encode(body).decode(), bodySha256=hashlib.sha256(body).hexdigest(),
            signature='sha256='+hmac.new(SECRET,timestamp.encode()+b'.'+body,hashlib.sha256).hexdigest())
    states = m['queueSamples'][-1]['rows']
    m['queueSamples'] = [{'observedAt':m['startedAt']+.11, 'subjects':[states[0]['subject']],
                         'rows':[dict(states[0],status='pending',receipts=0,deliveries=0,deliveryPid=None,
                                      deliveryStatus=None,retryCount=None,responseStatus=None)]},
        {'observedAt':m['startedAt']+30.5,'subjects':[x['subject'] for x in states[:151]],'rows':states[:151]},
        {'observedAt':m['startedAt']+60.2,'subjects':[x['subject'] for x in states],'rows':states}]
    m['metrics'] = load_metrics(m)
    return m


def isolation_passed():
    t=1700000000.0; subs=['slow-0','slow-1','slow-2'];fast='healthy';rows=[];states=[];intervals=[]
    created=[{'index':i,'subjectPid':'isolation_subject_'+str(i),'createStartedAt':t+(0 if i==0 else 2),
              'createCompletedAt':t+(0.1 if i==0 else 2.1)} for i in range(2)]
    for n,sub in enumerate(subs+[fast]):
        for i in range(2):
            proof=copy.deepcopy(load_passed(2)['rows'][i]['proof']);proof.update(subjectPid=created[i]['subjectPid'],
                eventId='isolation_event_'+str(i),deliveryPid=sub+'_'+str(i))
            row=proof['rawEvidence'][0];payload=json.loads(base64.b64decode(row['rawBodyBase64']))
            origin='release-model-isolation-'+str(i);pid=created[i]['subjectPid']
            payload.update(id=proof['eventId'],subject={'type':'release.model-event.parts','pid':pid},correlationId=origin,
                data={'pid':pid,'name':'Notification isolation '+str(i),'zone':'A'})
            raw=json.dumps(payload).encode();received=t+(3 if sub==fast else 1 if n==0 and i==0 else 6+n*2+i)
            row.update(receivedAt=received,timestamp=str(int(received)),requestId=origin,sequence=n*2+i+1,
                eventAgeAtReceiptMs=round((received-t)*1000,3),rawBodyBase64=base64.b64encode(raw).decode(),
                bodySha256=hashlib.sha256(raw).hexdigest(),signature='sha256='+hmac.new(SECRET,str(int(received)).encode()+b'.'+raw,hashlib.sha256).hexdigest())
            rows.append({'index':i,'subscriptionPid':sub,'proof':proof})
            states.append({'deliveryPid':proof['deliveryPid'],'eventId':proof['eventId'],'subjectPid':pid,
                'subscriptionPid':sub,'deliveryStatus':'success','retryCount':0,'responseStatus':200})
            if sub!=fast: intervals.append({'deliveryPid':proof['deliveryPid'],'startedAt':received,'endedAt':t+6 if n==0 and i==0 else received+.1})
    held=copy.deepcopy(states)
    for index,r in enumerate(held):
        if r['subscriptionPid']!=fast:r.update(deliveryStatus='processing' if index==0 else 'pending',responseStatus=None)
    return {'status':'complete','profile':'receiver-origin-isolation-v1','slowSubscriptions':subs,'fastSubscription':fast,
        'blockedAt':t+1.1,'observedWhileHeldAt':t+4,'releasedAt':t+5,'finishedAt':t+15,
        'created':created,'rows':rows,'slowIntervals':intervals,'heldState':{'deliveries':held},'finalState':{'deliveries':states}}


class ModelEventTests(unittest.TestCase):
    def test_source_binding_proof_is_mandatory_and_linked_to_signed_restoration(self):
        qualified=passed();qualified["genericReplay"]=generic_replay_passed()
        self.assertEqual(verify_report(qualified,secret=SECRET),qualified)
        for change in [lambda g:g.pop("denials"), lambda g:g["denials"].pop("limitedHuman"),
            lambda g:g["denials"]["wrongParent"].update(code="0"),
            lambda g:g["denials"]["machine"].update(afterDigest="b"*64),
            lambda g:g["denials"]["anonymous"].update(attemptsAfter=4),
            lambda g:g["denials"]["limitedHuman"].update(httpStatus=200),
            lambda g:g["finalState"].update(replayCount=2),
            lambda g:g["acceptedProof"]["rawEvidence"][0].update(signature="sha256="+"0"*64)]:
            altered=copy.deepcopy(qualified);change(altered["genericReplay"])
            with self.assertRaises(ProtocolError):verify_report(altered,secret=SECRET)
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

        report=passed(); report['receiverIsolation']=isolation_passed()
        self.assertEqual(verify_report(report,secret=SECRET)['receiverIsolation']['status'],'complete')
        for change in [lambda m:m['rows'].pop(),lambda m:m['slowSubscriptions'].pop(),
            lambda m:m.update(releasedAt=m['blockedAt']),lambda m:m['slowIntervals'][1].update(startedAt=m['blockedAt']),
            lambda m:m['heldState']['deliveries'][1].update(deliveryStatus='processing'),
            lambda m:m['finalState']['deliveries'][0].update(retryCount=1),
            lambda m:m['rows'][0]['proof']['rawEvidence'][0].update(signature='sha256='+'0'*64),
            lambda m:m['rows'][0]['proof']['rawEvidence'][0].update(eventAgeAtReceiptMs=0)]:
            mutated=passed();mutated['receiverIsolation']=isolation_passed();change(mutated['receiverIsolation'])
            with self.assertRaises(ProtocolError):verify_report(mutated,secret=SECRET)

        report = passed(); report["loadMeasurement"] = load_passed()
        self.assertEqual(verify_report(report, secret=SECRET)["loadMeasurement"]["metrics"]["accepted"], 32)
        changes = [lambda m: m.update(planned=0), lambda m: m.update(concurrency=True),
            lambda m: m.update(sloVerdict="passed"), lambda m: m.update(authority="blocking-release"),
            lambda m: m.update(status="started"), lambda m: m.update(startedAt=float("nan")),
            lambda m: m.update(producedAt=m["startedAt"]), lambda m: m.update(finishedAt=m["producedAt"]),
            lambda m: m["rows"].pop(), lambda m: m["rows"][1].update(index=0),
            lambda m: m["rows"][0].update(createStartedAt=0),
            lambda m: m["rows"][0]["proof"].update(eventId=m["rows"][1]["proof"]["eventId"]),
            lambda m: m["rows"][0]["proof"].update(statuses=[503]),
            lambda m: m["rows"][0]["proof"]["rawEvidence"][0].update(signature="sha256="+"0"*64),
            lambda m: m["rows"][0]["proof"]["rawEvidence"][0].update(eventAgeAtReceiptMs=0),
            lambda m: m.update(queueSamples=[]), lambda m: m["queueSamples"][0].update(observedAt=0),
            lambda m: m["queueSamples"][-1]["rows"][0].update(receipts=2),
            lambda m: m["queueSamples"][-1]["rows"][0].update(deliveries=True),
            lambda m: m["queueSamples"][-1]["rows"][0].update(status="pending"),
            lambda m: m["queueSamples"][-1]["rows"][0].update(deliveryStatus="dead_letter"),
            lambda m: m["queueSamples"][-1]["rows"][0].update(retryCount=1),
            lambda m: m["metrics"].update(accepted=0), lambda m: m["metrics"].update(receiptAgeP95Ms=0)]
        for key in report["loadMeasurement"]["metrics"]:
            changes.append(lambda m, key=key: m["metrics"].update({key: float("inf")}))
        for change in changes:
            with self.subTest(change=changes.index(change)):
                altered = passed(); altered["loadMeasurement"] = load_passed(); change(altered["loadMeasurement"])
                with self.assertRaises(ProtocolError): verify_report(altered, secret=SECRET)

        report = passed(); report['sustainedMeasurement'] = sustained_passed()
        self.assertEqual(verify_report(report, secret=SECRET)['sustainedMeasurement']['metrics']['accepted'], 300)
        sustained_changes = [lambda m:m.update(durationSeconds=1), lambda m:m.update(ratePerSecond=True),
            lambda m:m.update(producedAt=m['startedAt']+1), lambda m:m['rows'][0].update(scheduledAt=0),
            lambda m:m['rows'][0].update(createStartedAt=m['startedAt']-1),
            lambda m:m['queueSamples'].__delitem__(slice(0,2)),
            lambda m:m['queueSamples'][1].update(subjects=[]),
            lambda m:m['queueSamples'][-1]['rows'].pop(),
            lambda m:m['rows'][0]['proof']['rawEvidence'][0].update(eventAgeAtReceiptMs=0),
            lambda m:m['metrics'].update(successfulReceiptRatePerSecond=999),
            lambda m:m.update(sloVerdict='passed')]
        for change in sustained_changes:
            with self.subTest(sustained_fault=sustained_changes.index(change)):
                altered = passed(); altered['sustainedMeasurement'] = sustained_passed(); change(altered['sustainedMeasurement'])
                with self.assertRaises(ProtocolError): verify_report(altered, secret=SECRET)

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

        for occurrence in ["", "not-a-time", "2023-11-14T22:13:20", "2099-01-01T00:00:00Z"]:
            report = passed()
            for row in report["deliveryProofs"][0]["rawEvidence"]:
                body = json.loads(base64.b64decode(row["rawBodyBase64"])); body["occurredAt"] = occurrence
                raw = json.dumps(body).encode()
                row.update(rawBodyBase64=base64.b64encode(raw).decode(), bodySha256=hashlib.sha256(raw).hexdigest(),
                           signature="sha256=" + hmac.new(SECRET, row["timestamp"].encode() + b"." + raw, hashlib.sha256).hexdigest())
            with self.subTest(occurrence=occurrence), self.assertRaises(ProtocolError): verify_report(report, secret=SECRET)

        for value in [None, True, float("nan"), float("inf"), -1, 1]:
            report = passed(); report["deliveryProofs"][0]["rawEvidence"][0]["eventAgeAtReceiptMs"] = value
            with self.subTest(age=value), self.assertRaises(ProtocolError): verify_report(report, secret=SECRET)
        report = passed(); del report["deliveryProofs"][0]["rawEvidence"][0]["occurredAt"]
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
            for subjects in [[], ["same"] * 32, ["good_"+str(i) for i in range(31)] + ["bad' OR 1=1"]]:
                with self.assertRaises(ProtocolError): adapter.load_state(1, "event", subjects, "subscription")
            with self.assertRaises(ProtocolError): adapter.sql("DELETE FROM ab_outbox")

    def test_nonlinux_constructor_cannot_use_local_containers(self):
        with patch("platform.system", return_value="Darwin"), patch("subprocess.run", side_effect=AssertionError("local Docker")):
            with self.assertRaises(ProtocolError): DockerInfrastructure(app="a", postgres="p", network="n", job_id="j",
                source_ref="a"*40, image_digest="sha256:"+"b"*64, base_url="http://127.0.0.1:1", receiver_host="10.247.1.1")


if __name__ == "__main__":
    unittest.main()
