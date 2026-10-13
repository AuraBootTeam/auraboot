"""Fixed model-notification journey for the existing release-image protocol gate.

The infrastructure adapter owns exact-identity process recovery and read-only
database observations. Fixture writes use production HTTP routes exclusively.
"""
import ipaddress
import hashlib
import re
import base64
import copy
import json
import math
import os
import secrets
import stat
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from http.server import HTTPServer, ThreadingHTTPServer
from pathlib import Path
from queue import Queue

from open_platform_http import ProtocolError
from open_platform_receiver import Receiver, handler
from open_platform_webhook_evidence import read_delivery, verify_delivery

CASES = (
    "publication", "installation-subscription", "normal-poll-barrier",
    "machine-create-and-replay", "source-pending-before-crash", "owned-process-crash",
    "source-persisted-without-consumer", "new-process-same-runtime",
    "source-consumed-after-restart", "retry-recovers", "independent-raw-retry-proof",
    "bounded-dead-letter", "independent-dead-letter-proof", "owner-replay-success",
    "same-source-identity-on-replay", "independent-replay-proof",
    "no-extra-source-or-consumer", "tampered-real-evidence-rejected",
)


def require(condition, message):
    if not condition:
        raise ProtocolError(message)


def read_secret(path):
    path = Path(path)
    info = path.lstat()
    require(stat.S_ISREG(info.st_mode) and not info.st_mode & 0o077 and 32 <= info.st_size <= 4096,
            "model event secret must be a private regular file")
    return path.read_bytes().rstrip(b"\r\n")


def verify_report(report, *, secret=None):
    require(isinstance(secret, bytes) and bool(secret), "independent model event signature key missing")
    require(isinstance(report, dict) and type(report.get("schemaVersion")) is int and report["schemaVersion"] == 1,
            "model event report schema invalid")
    rows = report.get("cases")
    require(type(report.get("registered")) is int and report["registered"] == len(CASES),
            "model event registration denominator invalid")
    require(isinstance(rows, list) and len(rows) == len(CASES)
            and [row.get("id") for row in rows if isinstance(row, dict)] == list(CASES),
            "model event case denominator invalid")
    require(all(row.get("status") == "passed" for row in rows)
            and type(report.get("executed")) is int and report["executed"] == len(CASES)
            and type(report.get("passed")) is int and report["passed"] == len(CASES),
            "model event cases failed or unexecuted")
    expected = {"events": 1, "receipts": 1, "deliveries": 1}
    require(report.get("sourceProof") == expected and report.get("deadLetterProof") == expected,
            "model event durable cardinality invalid")
    require(all(type(value) is int for name in ["sourceProof", "deadLetterProof"] for value in report[name].values()),
            "model event durable counts must be integers")
    before, after = report.get("beforeRestart"), report.get("afterRestart")
    require(isinstance(before, dict) and isinstance(after, dict)
            and all(isinstance(before.get(key), str) and before[key] for key in
                    ["runtimeId", "artifactDigest", "generation"])
            and before["runtimeId"] == after.get("runtimeId")
            and before["artifactDigest"] == after.get("artifactDigest")
            and isinstance(after.get("generation"), str) and bool(after["generation"])
            and before["generation"] != after["generation"], "model event process recovery identity invalid")
    proofs = report.get("deliveryProofs")
    require(isinstance(proofs, list) and len(proofs) == 3
            and [proof.get("statuses") for proof in proofs if isinstance(proof, dict)]
            == [[503, 200], [503, 503, 503], [200]], "model event raw attempt denominator invalid")
    require(proofs[1].get("eventId") == proofs[2].get("eventId")
            and proofs[1].get("deliveryPid") == proofs[2].get("deliveryPid")
            and proofs[0].get("eventId") != proofs[1].get("eventId"),
            "model event replay source identity invalid")
    verify_source_binding(report.get("sourceBindingProof"), proofs[2])
    names = ["Pending source before restart", "Bounded delivery failure", "Bounded delivery failure"]
    origins = ["release-model-pending-source", "release-model-dead-letter", "release-model-dead-letter"]
    for proof, statuses, name, origin in zip(proofs, [[503, 200], [503, 503, 503], [200]], names, origins):
        require(all(isinstance(proof.get(key), str) and bool(proof[key]) for key in ["deliveryPid", "eventId", "subjectPid"]),
                "model event raw source identity missing")
        require(type(proof.get("attempts")) is int and proof["attempts"] == len(statuses) and isinstance(proof.get("rawEvidence"), list)
                and len(proof["rawEvidence"]) == len(statuses)
                and [row.get("responseStatus") for row in proof["rawEvidence"] if isinstance(row, dict)] == statuses,
                "model event raw proof missing")
        actual = []
        for row in proof["rawEvidence"]:
            require(type(row.get("responseStatus")) is int and type(row.get("duplicate")) is bool
                    and type(row.get("sequence")) is int and row["sequence"] >= 1,
                    "model event raw receipt types invalid")
            try:
                raw = base64.b64decode(row.get("rawBodyBase64"), validate=True)
            except (ValueError, TypeError):
                raise ProtocolError("model event raw body encoding invalid") from None
            actual.append({"raw_body": raw, "timestamp_text": row.get("timestamp"), "received_at": row.get("receivedAt"),
                "signature": row.get("signature"), "signature_valid": 1, "delivery_id": proof.get("deliveryPid"),
                "event_id": proof.get("eventId"), "response_status": row.get("responseStatus"),
                "request_id": row.get("requestId"), "body_sha": row.get("bodySha256"),
                "sequence": row.get("sequence"), "duplicate": row.get("duplicate")})
        verified = verify_delivery(actual, secret=secret, delivery_pid=proof.get("deliveryPid"), event_id=proof.get("eventId"),
            subject_pid=proof.get("subjectPid"), statuses=statuses, originating_request_id=origin,
            event_type="open.resource.release.model-event.parts.created", subject_type="release.model-event.parts",
            schema_version=1, projections=[{"pid": proof.get("subjectPid"), "name": name, "zone": "A"}] * len(statuses))
        for reported, derived in zip(proof["rawEvidence"], verified["rawEvidence"]):
            require(reported.get("occurredAt") == derived["occurredAt"]
                    and type(reported.get("eventAgeAtReceiptMs")) in (int, float)
                    and math.isfinite(reported["eventAgeAtReceiptMs"])
                    and reported["eventAgeAtReceiptMs"] == derived["eventAgeAtReceiptMs"],
                    "model event receipt age does not match signed source and raw receipt")
        require(all(json.loads(row["raw_body"])["recordVersion"] == 1
                    and json.loads(row["raw_body"])["correlationId"] == origin for row in actual),
                "model event raw source metadata invalid")
    if "genericReplay" in report:
        verify_generic_replay(report["genericReplay"], secret=secret)
    if "receiverIsolation" in report:
        verify_receiver_isolation(report["receiverIsolation"], secret=secret)
    if "loadMeasurement" in report:
        verify_load_measurement(report["loadMeasurement"], secret=secret)
    if "sustainedMeasurement" in report:
        verify_sustained_measurement(report["sustainedMeasurement"], secret=secret)
    return report


def verify_source_binding(proof, restored):
    require(isinstance(proof, dict) and set(proof) == {
        "eventId", "deliveryPid", "subjectPid", "blockedStatus", "blockedReplayCount", "blockedRetryCount",
        "receiverAttemptsBefore", "receiverAttemptsAfter", "restoredStatus", "restoredReplayCount"},
        "model event source binding proof missing or malformed")
    require(all(proof.get(key) == restored.get(key) for key in ["eventId", "deliveryPid", "subjectPid"]),
        "model event source binding replay identity changed")
    expected = {"blockedReplayCount": 1, "blockedRetryCount": 1, "receiverAttemptsBefore": 0,
                "receiverAttemptsAfter": 0, "restoredReplayCount": 2}
    require(proof["blockedStatus"] == "dead_letter" and proof["restoredStatus"] == "success"
            and all(type(proof[key]) is int and proof[key] == value for key, value in expected.items()),
            "model event source binding did not block network or restore owner replay")


def run_source_binding_replay(human, model_pid, installation_pid, dead_log, wait_delivery, receiver_attempts):
    """Rebind through the management API, then restore even if replay or observation fails."""
    model_path = f"/api/meta/models/{model_pid}"
    model = human(model_path)
    original = copy.deepcopy(model.get("extension") or {})
    require(isinstance(original.get("fields"), list)
            and all(isinstance(field, dict) for field in original["fields"]),
            "model event source binding management fields missing")
    rebound = copy.deepcopy(original)
    fields = [field for field in rebound["fields"] if field.get("code") == "rel_name"]
    require(len(fields) == 1, "model event source binding fixture field missing")
    fields[0]["columnName"] = "rel_private"
    before = receiver_attempts()
    require(before == 0, "model event source binding receiver already used")
    try:
        human(model_path, "PUT", {"displayName": model["displayName"], "extension": rebound})
        human(f"/api/open-platform/installations/{installation_pid}/webhook-deliveries/{dead_log['pid']}/replay", "POST", {})
        blocked = wait_delivery()
        after = receiver_attempts()
        require(blocked.get("pid") == dead_log["pid"] and blocked.get("eventId") == dead_log["eventId"]
                and blocked.get("deliveryStatus") == "dead_letter" and blocked.get("replayCount") == 1
                and blocked.get("retryCount") == 1 and after == 0,
                "model event source binding allowed HTTP or changed durable identity")
    finally:
        human(model_path, "PUT", {"displayName": model["displayName"], "extension": original})
    return {"eventId": blocked["eventId"], "deliveryPid": blocked["pid"], "blockedStatus": blocked["deliveryStatus"],
        "blockedReplayCount": blocked["replayCount"], "blockedRetryCount": blocked["retryCount"],
        "receiverAttemptsBefore": before, "receiverAttemptsAfter": after}


class Receivers:
    """SQLite and its HTTP server run on the same owned thread."""
    def __init__(self, directory, host, secret):
        address = ipaddress.ip_address(host)
        require(address.version == 4 and address.is_private and not address.is_unspecified
                and not address.is_multicast, "model receiver bind must be a specific private IPv4 address")
        self.directory, self.host, self.secret, self.servers = Path(directory), host, secret, []

    def start(self, label, mode):
        store = self.directory / (label + ".sqlite")
        require(not store.exists() and not store.is_symlink(), "model receiver store already exists")
        run_id, ready = "release-model-" + label, Queue()

        def serve():
            sink = server = None
            try:
                sink = Receiver(store, self.secret, run_id, mode=mode, failures=1)
                server = HTTPServer((self.host, 0), handler(sink))
                ready.put(server)
                server.serve_forever()
            except (OSError, ValueError):
                ready.put(None)
            finally:
                if server is not None:
                    server.server_close()
                if sink is not None:
                    sink.close()

        thread = threading.Thread(target=serve, daemon=True)
        thread.start()
        server = ready.get(timeout=10)
        require(server is not None, "model receiver failed to bind")
        self.servers.append((server, thread))
        return {"store": store, "runId": run_id, "mode": mode,
                "url": f"http://{self.host}:{server.server_port}/webhooks/{run_id}"}

    def start_gate(self, label):
        store = self.directory / (label + '.sqlite')
        run_id = 'release-model-' + label
        require(not store.exists(), 'gate receiver store already exists')
        Receiver(store, self.secret, run_id, mode='accept', failures=1).close()
        gate, entered, lock, intervals = threading.Event(), threading.Event(), threading.Lock(), []
        secret = self.secret
        class ControlledReceiver:
            def __init__(self): self.run_id = run_id
            def receive(self, raw, headers):
                row = {'deliveryPid': next((v for k,v in headers.items() if k.lower()=='x-webhook-delivery'),None), 'startedAt': time.time()}
                with lock: intervals.append(row)
                with_receiver = Receiver(store, secret, run_id, mode='accept', failures=1)
                try: result = with_receiver.receive(raw, headers)
                finally: with_receiver.close()
                entered.set()
                require(gate.wait(45), 'owned slow receiver was not released')
                with lock: row['endedAt'] = time.time()
                return result
        server = ThreadingHTTPServer((self.host, 0), handler(ControlledReceiver()))
        thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
        self.servers.append((server, thread))
        return {'store':store,'runId':run_id,'mode':'accept','gate':gate,'entered':entered,
            'lock':lock,'intervals':intervals,'url':f'http://{self.host}:{server.server_port}/webhooks/{run_id}'}

    def close(self):
        for server, thread in self.servers:
            server.shutdown()
            thread.join(timeout=5)
            require(not thread.is_alive(), "model receiver thread did not stop")


def observe(callback, seconds=75):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        value = callback()
        if value:
            return value
        time.sleep(.25)
    raise ProtocolError("model event bounded observation expired")


def verify_generic_replay(group, *, secret):
    require(isinstance(group, dict) and group.get("status") == "complete", "generic replay proof incomplete")
    sub, other = group.get("subscriptionPid"), group.get("wrongSubscriptionPid")
    require(isinstance(sub, str) and sub and isinstance(other, str) and other and sub != other,
            "generic replay subscription identities invalid")
    failed, accepted = group.get("failedProof"), group.get("acceptedProof")
    require(isinstance(failed, dict) and isinstance(accepted, dict)
            and all(failed.get(k) == accepted.get(k) and isinstance(failed.get(k), str) and failed[k]
                    for k in ["deliveryPid", "eventId", "subjectPid"]), "generic replay source identity changed")
    route = f"/api/webhooks/{sub}/deliveries/{failed['deliveryPid']}/replay"
    denials = group.get("denials")
    require(isinstance(denials, dict) and set(denials) == {"anonymous", "machine", "limitedHuman", "wrongParent", "repeated"},
            "generic replay denial denominator invalid")
    for kind, row in denials.items():
        expected = (401, 403) if kind in {"anonymous", "machine"} else (403,) if kind == "limitedHuman" else (200,)
        path = route if kind != "wrongParent" else f"/api/webhooks/{other}/deliveries/{failed['deliveryPid']}/replay"
        require(isinstance(row, dict) and type(row.get("httpStatus")) is int and row["httpStatus"] in expected
                and ((row.get("code") is None and row["httpStatus"] != 200)
                     or (isinstance(row.get("code"), str) and row["code"] and row["code"] != "0")) and row.get("path") == path,
                "generic replay denial response invalid")
        require(isinstance(row.get("beforeDigest"), str) and re.fullmatch(r"[a-f0-9]{64}", row["beforeDigest"])
                and row.get("afterDigest") == row["beforeDigest"]
                and type(row.get("attemptsBefore")) is int and type(row.get("attemptsAfter")) is int
                and row["attemptsBefore"] == row["attemptsAfter"] == (4 if kind == "repeated" else 3),
                "generic replay denial changed durable state or sent HTTP")
    require(group.get("correctResponse") == {"httpStatus": 200, "code": "0", "path": route},
            "generic replay correct response invalid")
    require(group.get("finalState") == {"deliveryStatus": "success", "retryCount": 0, "replayCount": 1, "responseStatus": 200}
            and all(type(v) is int for k, v in group["finalState"].items() if k != "deliveryStatus"),
            "generic replay final state invalid")
    require(group.get("counts") == {"events": 1, "receipts": 1, "deliveries": 1}
            and all(type(v) is int for v in group["counts"].values()), "generic replay durable cardinality invalid")
    for proof, statuses in [(failed, [503, 503, 503]), (accepted, [200])]:
        require(proof.get("statuses") == statuses and type(proof.get("attempts")) is int
                and proof["attempts"] == len(statuses) and len(proof.get("rawEvidence", [])) == len(statuses),
                "generic replay raw attempt denominator invalid")
        actual = []
        for row in proof["rawEvidence"]:
            require(type(row.get("responseStatus")) is int and type(row.get("sequence")) is int
                    and row["sequence"] >= 1 and type(row.get("duplicate")) is bool, "generic replay raw receipt types invalid")
            try:
                raw = base64.b64decode(row.get("rawBodyBase64"), validate=True)
            except (ValueError, TypeError):
                raise ProtocolError("generic replay raw encoding invalid") from None
            actual.append({"raw_body": raw, "timestamp_text": row.get("timestamp"), "received_at": row.get("receivedAt"),
                "signature": row.get("signature"), "signature_valid": 1, "delivery_id": proof["deliveryPid"],
                "event_id": proof["eventId"], "response_status": row.get("responseStatus"), "request_id": row.get("requestId"),
                "body_sha": row.get("bodySha256"), "sequence": row.get("sequence"), "duplicate": row.get("duplicate")})
        derived = verify_delivery(actual, secret=secret, delivery_pid=proof["deliveryPid"], event_id=proof["eventId"],
            subject_pid=proof["subjectPid"], statuses=statuses, originating_request_id="release-model-generic-replay",
            event_type="open.resource.release.model-event.parts.created", subject_type="release.model-event.parts", schema_version=1,
            projections=[{"pid": proof["subjectPid"], "name": "Generic replay boundary", "zone": "A"}] * len(statuses))
        for row, checked in zip(proof["rawEvidence"], derived["rawEvidence"]):
            require(row.get("occurredAt") == checked["occurredAt"] and type(row.get("eventAgeAtReceiptMs")) in (int, float)
                    and math.isfinite(row["eventAgeAtReceiptMs"]) and row["eventAgeAtReceiptMs"] == checked["eventAgeAtReceiptMs"],
                    "generic replay source age invalid")
        require(all(json.loads(r["raw_body"]).get("recordVersion") == 1 for r in actual), "generic replay record version invalid")
    require(failed["rawEvidence"][0]["rawBodyBase64"] == accepted["rawEvidence"][0]["rawBodyBase64"],
            "generic replay source payload changed")
    return group


def collect_generic_replay(call, human, infrastructure, admin, machine, tenant, subscription, body, dead, replay, delivery, counts, secret):
    body["targetUrl"] = dead["url"]
    human("/api/webhooks/" + subscription["pid"], "PUT", body)
    _, created, _ = call("/api/open/v1/resources/release.model-event.parts", "POST",
        body={"name": "Generic replay boundary", "zone": "A"},
        headers=dict(machine, **{"Idempotency-Key": "release-model-generic-create", "X-Request-Id": "release-model-generic-replay"}), expected=(201,))
    log = delivery(created["pid"], "dead_letter")
    require(log["retryCount"] == 3 and log["replayCount"] == 0, "generic replay failure fixture invalid")
    other = human("/api/webhooks", "POST", {"name": "Unrelated replay parent", "eventType": "release.fixture.replay-parent",
        "targetUrl": "https://example.invalid/no-delivery", "enabled": False})
    require(re.fullmatch(r"[A-Za-z0-9_-]{1,64}", log["pid"]) and type(tenant) is int, "generic replay SQL identity invalid")
    def digest():
        value = infrastructure.sql(f"SELECT json_build_object('delivery',row_to_json(d),'lane',row_to_json(l)) FROM ab_webhook_delivery_log d JOIN ab_webhook_receiver_lane l ON l.tenant_id=d.tenant_id AND l.receiver_key=d.receiver_key WHERE d.tenant_id={tenant} AND d.pid='{log['pid']}'")
        require(isinstance(value, dict) and value.get("delivery") and value.get("lane"), "generic replay state observation missing")
        return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
    def attempts():
        return sum(len(read_delivery(sink["store"], log["pid"], secret=secret, run_id=sink["runId"],
                                    mode=sink["mode"], failures=1)) for sink in [dead, replay])
    route = f"/api/webhooks/{subscription['pid']}/deliveries/{log['pid']}/replay"
    group = {"status": "started", "subscriptionPid": subscription["pid"], "wrongSubscriptionPid": other["pid"], "denials": {}}
    def deny(kind, path, headers, expected):
        before, total = digest(), attempts()
        status, response, _ = call(path, "POST", body={}, headers=headers, expected=expected)
        require(isinstance(response, dict) and response.get("code") != "0", "generic replay denial returned success")
        row = {"path": path, "httpStatus": status, "code": response.get("code"), "beforeDigest": before,
               "afterDigest": digest(), "attemptsBefore": total, "attemptsAfter": attempts()}
        require(row["beforeDigest"] == row["afterDigest"] and row["attemptsBefore"] == row["attemptsAfter"],
                "generic replay denial mutated state or delivered HTTP")
        group["denials"][kind] = row
    deny("anonymous", route, {}, (401, 403))
    deny("machine", route, machine, (401, 403))
    account = {"email": "replay-denied-" + secrets.token_hex(8) + "@example.invalid", "password": "Replay2026x!" + secrets.token_hex(8)}
    human("/api/meta/commands/execute/admin:create_member", "POST", {"payload": dict(account, name="Replay denied actor")})
    _, auth, _ = call("/api/auth/login", "POST", body=account)
    require(isinstance(auth, dict) and str(auth.get("code")) == "0", "generic replay limited login failed")
    limited = {"Authorization": "Bearer " + auth["data"]["jwt"]}
    _, selected, _ = call("/api/tenant-selection/process", "POST", body={"action": "select", "tenantId": tenant}, headers=limited)
    require(isinstance(selected, dict) and str(selected.get("code")) == "0", "generic replay limited tenant selection failed")
    limited = {"Authorization": "Bearer " + selected["data"]["jwt"]}
    deny("limitedHuman", route, limited, (403,))
    human("/api/webhooks/" + other["pid"])
    deny("wrongParent", f"/api/webhooks/{other['pid']}/deliveries/{log['pid']}/replay", admin, (200,))
    def proof(sink, statuses):
        raw = read_delivery(sink["store"], log["pid"], secret=secret, run_id=sink["runId"], mode=sink["mode"], failures=1)
        return verify_delivery(raw, secret=secret, delivery_pid=log["pid"], event_id=log["eventId"], subject_pid=created["pid"],
            statuses=statuses, originating_request_id="release-model-generic-replay",
            event_type="open.resource.release.model-event.parts.created", subject_type="release.model-event.parts", schema_version=1,
            projections=[{"pid": created["pid"], "name": "Generic replay boundary", "zone": "A"}] * len(statuses))
    group["failedProof"] = proof(dead, [503, 503, 503])
    body["targetUrl"] = replay["url"]
    human("/api/webhooks/" + subscription["pid"], "PUT", body)
    status, response, _ = call(route, "POST", body={}, headers=admin)
    require(isinstance(response, dict) and str(response.get("code")) == "0", "generic replay correct path failed")
    group["correctResponse"] = {"httpStatus": status, "code": str(response["code"]), "path": route}
    done = delivery(created["pid"], "success")
    group["finalState"] = {k: done[k] for k in ["deliveryStatus", "retryCount", "replayCount", "responseStatus"]}
    group["acceptedProof"] = proof(replay, [200])
    group["counts"] = counts(log["eventId"])
    deny("repeated", route, admin, (200,))
    group["status"] = "complete"
    return verify_generic_replay(group, secret=secret)


def run_model_event_probe(request, admin, infrastructure, *, evidence_path, receiver_host, secret_file, measure_load=False, measure_sustained=False, measure_isolation=False, qualify_replay_scope=False):
    require(type(qualify_replay_scope) is bool, "generic replay qualification opt-in must be boolean")
    require(type(measure_isolation) is bool, "receiver isolation opt-in must be boolean")
    require(type(measure_sustained) is bool, "sustained measurement opt-in must be boolean")
    require(type(measure_load) is bool, "model load measurement opt-in must be boolean")
    evidence_path = Path(evidence_path)
    require(not evidence_path.exists() and not evidence_path.is_symlink(), "model event report already exists")
    evidence_path.parent.mkdir(parents=True, exist_ok=True)
    rows = [{"id": case, "status": "did_not_run"} for case in CASES]
    report = {"schemaVersion": 1, "registered": len(CASES), "cases": rows, "executed": 0, "passed": 0, "requests": 0}
    secret = secrets.token_urlsafe(32).encode()
    receivers = None

    request_lock = threading.Lock()

    def call(*args, **kwargs):
        with request_lock:
            report["requests"] += 1
            require(report["requests"] <= (500 if measure_sustained else 200), "model event request budget exhausted")
        return request(*args, **kwargs)

    def human(path, method="GET", body=None):
        _, response, _ = call(path, method, body=body, headers=admin)
        require(isinstance(response, dict) and str(response.get("code")) == "0",
                "model event administration failed")
        return response.get("data")

    def record(case, condition=True):
        row = next(row for row in rows if row["id"] == case)
        require(row["status"] == "did_not_run", "model event case repeated")
        row["status"] = "passed" if condition else "failed"
        require(condition, "model event assertion failed: " + case)

    def field(source, writable=False, required=False):
        return {"source": source, "type": "string", "readable": True, "writable": writable,
                "required": required, "nullable": not required, "operators": [], "sortable": False,
                "referenceResource": None, "properties": {}, "items": None}

    model, resource = "release_model_event_parts", "release.model-event.parts"
    event_type = "open.resource." + resource + ".created"
    operations = ["list", "get", "create", "update", "delete"]
    scopes = {operation: "release.model-event:" + operation for operation in operations}
    try:
        fd = os.open(secret_file, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "wb") as private:
            private.write(secret)
        receivers = Receivers(evidence_path.parent, receiver_host, secret)
        model_row = human("/api/meta/models", "POST", {
            "code": model, "tableName": model, "displayName": "Release model event parts",
            "modelType": "entity", "sourceType": "physical",
            "capabilities": {key: True for key in ["list", "detail", "create", "update", "delete", "filter", "sort", "paginate"]},
            "fields": [{"code": "rel_name", "columnName": "rel_name", "displayName": "Name", "dataType": "string", "required": True, "editable": True},
                       {"code": "rel_zone", "columnName": "rel_zone", "displayName": "Zone", "dataType": "string", "required": True, "editable": True, "filterable": True},
                       {"code": "rel_private", "columnName": "rel_private", "displayName": "Private", "dataType": "string", "editable": True}]})
        human(f"/api/meta/models/{model_row['pid']}/publish", "POST", {"impactAcknowledged": True})
        definition = {"code": resource, "modelCode": model, "schemaVersion": 1, "operations": operations,
            "scopes": scopes, "dataPolicy": "installation_filter", "fields": {
                "pid": dict(field("pid"), type="pid", nullable=False), "name": field("rel_name", True, True),
                "zone": dict(field("rel_zone", True, True), operators=["EQ"]), "secret": field("rel_private")},
            "maxPageSize": 50, "maxConditions": 8, "timeoutMs": 5000, "events": ["created"]}
        human(f"/api/open-platform/publications/{resource}/draft", "PUT", {"expectedRevision": 0, "definition": definition})
        published = human(f"/api/open-platform/publications/{resource}/publish", "POST", {"expectedRevision": 1})
        record("publication", published.get("activeVersion") == 1)
        application = human("/api/open-platform/applications", "POST", {"name": "Release model event consumer", "description": "Exact-image gate"})
        installation = human(f"/api/open-platform/applications/{application['pid']}/installations", "POST",
                             {"environment": "staging", "scopes": list(scopes.values()), "rateLimitPerMinute": 600})
        human(f"/api/open-platform/installations/{installation['pid']}/resources/{resource}", "PUT",
              {"operations": operations, "readableFields": ["pid", "name", "zone"], "writableFields": ["name", "zone"],
               "rowConstraints": [{"field": "zone", "values": ["A"]}]})
        credentials = human(f"/api/open-platform/installations/{installation['pid']}/credentials", "POST", {})
        _, token, _ = call("/oauth2/token", "POST", form={"grant_type": "client_credentials",
                          "client_id": credentials["clientId"], "client_secret": credentials["clientSecret"]})
        machine = {"Authorization": "Bearer " + token["access_token"]}
        retry = receivers.start("retry", "fail-first")
        dead = receivers.start("dead", "fail-all")
        replay = receivers.start("replay", "accept")
        subscription_body = {"name": "Signed model release recovery", "installationPid": installation['pid'],
            "targetUrl": retry["url"], "eventType": event_type, "eventVersion": 1, "secret": secret.decode(),
            "maxRetries": 3, "timeoutMs": 10000}
        subscription = human("/api/webhooks", "POST", subscription_body)
        record("installation-subscription", subscription.get("installationPid") == installation["pid"])
        tenant = subscription["tenantId"]

        def source(subject):
            return infrastructure.source(tenant, event_type, subject)

        def counts(event_id):
            return infrastructure.counts(tenant, event_id)

        def delivery(subject, status):
            def find():
                event_id = source(subject)["eventId"]
                matches = [item for item in human(f"/api/webhooks/{subscription['pid']}/deliveries")
                           if item.get("eventId") == event_id and item.get("deliveryStatus") == status]
                require(len(matches) <= 1, "model event duplicate delivery rows")
                return matches[0] if matches else None
            return observe(find)

        proofs = []
        report["deliveryProofs"] = proofs

        def verify(sink, log, subject, statuses, intent, origin):
            actual = read_delivery(sink["store"], log["pid"], secret=secret, run_id=sink["runId"], mode=sink["mode"], failures=1)
            proof = verify_delivery(actual, secret=secret, delivery_pid=log["pid"], event_id=log["eventId"],
                subject_pid=subject, statuses=statuses, originating_request_id=origin, event_type=event_type,
                subject_type=resource, schema_version=1, projections=[dict(intent, pid=subject)] * len(statuses))
            snapshot = source(subject)
            require(all(json.loads(row["raw_body"])["recordVersion"] == snapshot["sequence"]
                        and json.loads(row["raw_body"])["correlationId"] == origin for row in actual),
                    "model event source version/correlation mismatch")
            proofs.append(proof)
            return actual

        report["beforeRestart"] = infrastructure.identity()
        marker = infrastructure.poll_marker()
        observe(lambda: infrastructure.poll_marker() != marker, seconds=15)
        record("normal-poll-barrier")
        path, origin = "/api/open/v1/resources/" + resource, "release-model-pending-source"
        intent = {"name": "Pending source before restart", "zone": "A"}
        headers = dict(machine, **{"Idempotency-Key": "release-model-create-1", "X-Request-Id": origin})
        _, created, _ = call(path, "POST", body=intent, headers=headers, expected=(201,))
        _, repeated, _ = call(path, "POST", body=intent, headers=headers, expected=(201,))
        record("machine-create-and-replay", created == repeated)
        pending = source(created["pid"])
        record("source-pending-before-crash", pending["status"] == "pending"
               and counts(pending["eventId"]) == {"events": 1, "receipts": 0, "deliveries": 0})
        infrastructure.crash(report["beforeRestart"])
        record("owned-process-crash")
        record("source-persisted-without-consumer", source(created["pid"]) == pending
               and counts(pending["eventId"]) == {"events": 1, "receipts": 0, "deliveries": 0})
        infrastructure.resume()
        report["afterRestart"] = infrastructure.identity()
        before, after = report["beforeRestart"], report["afterRestart"]
        record("new-process-same-runtime", before["runtimeId"] == after["runtimeId"]
               and before["artifactDigest"] == after["artifactDigest"] and before["generation"] != after["generation"])
        recovered = delivery(created["pid"], "success")
        report["sourceProof"] = counts(pending["eventId"])
        record("source-consumed-after-restart", source(created["pid"])["status"] == "delivered"
               and report["sourceProof"] == {"events": 1, "receipts": 1, "deliveries": 1})
        record("retry-recovers", recovered["retryCount"] == 1 and recovered["responseStatus"] == 200)
        actual = verify(retry, recovered, created["pid"], [503, 200], intent, origin)
        record("independent-raw-retry-proof")
        subscription_body["targetUrl"] = dead["url"]
        human("/api/webhooks/" + subscription["pid"], "PUT", subscription_body)
        dead_intent, dead_origin = {"name": "Bounded delivery failure", "zone": "A"}, "release-model-dead-letter"
        _, dead_created, _ = call(path, "POST", body=dead_intent,
            headers=dict(machine, **{"Idempotency-Key": "release-model-create-2", "X-Request-Id": dead_origin}), expected=(201,))
        dead_log = delivery(dead_created["pid"], "dead_letter")
        record("bounded-dead-letter", dead_log["retryCount"] == 3 and dead_log["responseStatus"] == 503 and dead_log["replayCount"] == 0)
        dead_raw = verify(dead, dead_log, dead_created["pid"], [503, 503, 503], dead_intent, dead_origin)
        record("independent-dead-letter-proof")
        subscription_body["targetUrl"] = replay["url"]
        human("/api/webhooks/" + subscription["pid"], "PUT", subscription_body)
        report["sourceBindingProof"] = run_source_binding_replay(human, model_row["pid"], installation["pid"], dead_log,
            lambda: delivery(dead_created["pid"], "dead_letter"),
            lambda: len(read_delivery(replay["store"], dead_log["pid"], secret=secret,
                                     run_id=replay["runId"], mode=replay["mode"], failures=1)))
        human(f"/api/open-platform/installations/{installation['pid']}/webhook-deliveries/{dead_log['pid']}/replay", "POST", {})
        done = delivery(dead_created["pid"], "success")
        report["sourceBindingProof"].update(subjectPid=dead_created["pid"], restoredStatus=done["deliveryStatus"],
                                           restoredReplayCount=done["replayCount"])
        record("owner-replay-success", done["replayCount"] == 2 and done["retryCount"] == 0 and done["responseStatus"] == 200)
        record("same-source-identity-on-replay", done["pid"] == dead_log["pid"] and done["eventId"] == dead_log["eventId"])
        accepted = verify(replay, done, dead_created["pid"], [200], dead_intent, dead_origin)
        record("independent-replay-proof", accepted[0]["raw_body"] == dead_raw[0]["raw_body"])
        report["deadLetterProof"] = counts(dead_log["eventId"])
        record("no-extra-source-or-consumer", report["deadLetterProof"] == {"events": 1, "receipts": 1, "deliveries": 1}
               and counts(pending["eventId"]) == report["sourceProof"])
        corrupted = [dict(row) for row in actual]
        corrupted[0]["signature"] = "sha256=" + "0" * 64
        try:
            verify_delivery(corrupted, secret=secret, delivery_pid=recovered["pid"], event_id=recovered["eventId"],
                subject_pid=created["pid"], statuses=[503, 200], originating_request_id=origin, event_type=event_type,
                subject_type=resource, schema_version=1, projections=[dict(intent, pid=created["pid"])] * 2)
        except ProtocolError:
            record("tampered-real-evidence-rejected")
        else:
            record("tampered-real-evidence-rejected", False)
        if qualify_replay_scope:
            report["genericReplay"] = {"status": "started"}
            try:
                report["genericReplay"] = collect_generic_replay(call, human, infrastructure, admin, machine, tenant,
                    subscription, subscription_body, dead, replay, delivery, counts, secret)
            finally:
                if subscription_body["targetUrl"] != replay["url"]:
                    subscription_body["targetUrl"] = replay["url"]
                    human("/api/webhooks/" + subscription["pid"], "PUT", subscription_body)
        if measure_isolation:
            report['receiverIsolation'] = {'status': 'started'}
            report['receiverIsolation'] = collect_receiver_isolation(call, human, infrastructure, machine,
                tenant, installation['pid'], subscription['pid'], receivers, replay, secret)
        if measure_load:
            report["loadMeasurement"] = {"profile": "burst-32x4", "status": "started"}
            report["loadMeasurement"] = collect_load_measurement(call, infrastructure, machine, tenant,
                subscription["pid"], replay, secret)
        if measure_sustained:
            report["sustainedMeasurement"] = {"profile": "paced-60s-5rps", "status": "started"}
            report["sustainedMeasurement"] = collect_sustained_measurement(call, infrastructure, machine, tenant, subscription["pid"], replay, secret)
    finally:
        try:
            if receivers is not None:
                receivers.close()
        finally:
            report["executed"] = sum(row["status"] != "did_not_run" for row in rows)
            report["passed"] = sum(row["status"] == "passed" for row in rows)
            evidence_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    return verify_report(report, secret=secret)


LOAD_COUNT = 32
LOAD_CONCURRENCY = 4


def measurement_raw(proof):
    rows = []
    for row in proof['rawEvidence']:
        require(isinstance(row, dict) and type(row.get('sequence')) is int and row['sequence'] >= 1
                and type(row.get('duplicate')) is bool and type(row.get('responseStatus')) is int,
                'model load receipt types invalid')
        try:
            raw = base64.b64decode(row.get('rawBodyBase64'), validate=True)
        except (ValueError, TypeError):
            raise ProtocolError('model load raw receipt encoding invalid') from None
        rows.append({'raw_body': raw, 'timestamp_text': row.get('timestamp'), 'received_at': row.get('receivedAt'),
            'signature': row.get('signature'), 'signature_valid': 1, 'delivery_id': proof['deliveryPid'],
            'event_id': proof['eventId'], 'response_status': row['responseStatus'], 'request_id': row.get('requestId'),
            'body_sha': row.get('bodySha256'), 'sequence': row['sequence'], 'duplicate': row['duplicate']})
    return rows


def load_metrics(measurement):
    """Recompute raw observations, without inventing a notification SLO."""
    rows = measurement['rows']
    count = len(rows)
    ages = sorted(row['proof']['rawEvidence'][0]['eventAgeAtReceiptMs'] for row in rows)
    received = [row['proof']['rawEvidence'][0]['receivedAt'] for row in rows]
    times = sorted([(row['createStartedAt'], 1) for row in rows]
                   + [(row['createCompletedAt'], -1) for row in rows])
    active = peak = 0
    for _, change in times:
        active += change
        peak = max(peak, active)
    return {'accepted': len(rows), 'failedReceipts': 0, 'receiptErrorRate': 0.0,
        'producerConcurrencyPeak': peak,
        'producerRatePerSecond': round(count / (measurement['producedAt'] - measurement['startedAt']), 3),
        'successfulReceiptRatePerSecond': round(count / (max(received) - measurement['startedAt']), 3),
        'observedDrainRatePerSecond': round(count / (measurement['finishedAt'] - measurement['startedAt']), 3),
        'receiptAgeP50Ms': ages[math.ceil(count * .50) - 1],
        'receiptAgeP95Ms': ages[math.ceil(count * .95) - 1],
        'receiptAgeP99Ms': ages[math.ceil(count * .99) - 1], 'receiptAgeMaxMs': max(ages),
        'peakObservedPendingSources': max(sum(row['status'] != 'delivered' for row in sample['rows'])
                                        for sample in measurement['queueSamples']),
        'peakObservedPendingDeliveries': max(sum(row['deliveryStatus'] in ('pending', 'processing') for row in sample['rows'])
                                           for sample in measurement['queueSamples'])}


def verify_load_measurement(measurement, *, secret):
    return _verify_measurement(measurement, secret=secret, count=LOAD_COUNT,
        concurrency=LOAD_CONCURRENCY, profile='burst-32x4', origin_prefix='release-model-load-')


def verify_sustained_measurement(measurement, *, secret):
    require(isinstance(measurement, dict) and measurement.get('durationSeconds') == 60
            and type(measurement.get('durationSeconds')) is int
            and measurement.get('ratePerSecond') == 5 and type(measurement.get('ratePerSecond')) is int,
            'sustained recorded plan invalid')
    _verify_measurement(measurement, secret=secret, count=300, concurrency=4,
        profile='paced-60s-5rps', origin_prefix='release-model-sustained-', live=True)
    start, produced = measurement['startedAt'], measurement['producedAt']
    require(produced >= start + 60, 'sustained producer window incomplete')
    for row in measurement['rows']:
        slot = start + row['index'] / 5
        require(type(row.get('scheduledAt')) in (int, float) and row['scheduledAt'] == slot
                and slot <= row['createStartedAt'], 'sustained pacing trace invalid')
    samples = measurement['queueSamples']
    require(any(sample['observedAt'] < produced for sample in samples)
            and any(sample['observedAt'] >= start + 30 and sample['observedAt'] < produced for sample in samples),
            'sustained live queue observations missing')
    return measurement


def _verify_measurement(measurement, *, secret, count, concurrency, profile, origin_prefix, live=False):
    require(isinstance(measurement, dict) and measurement.get('profile') == profile
            and measurement.get('authority') == 'recorded' and measurement.get('sloVerdict') == 'unqualified'
            and measurement.get('status') == 'complete'
            and type(measurement.get('planned')) is int and measurement['planned'] == count
            and type(measurement.get('concurrency')) is int and measurement['concurrency'] == concurrency,
            'model load measurement profile invalid')
    start, produced, finished = [measurement.get(key) for key in ['startedAt', 'producedAt', 'finishedAt']]
    require(all(type(value) in (int, float) and math.isfinite(value) for value in [start, produced, finished])
            and start < produced <= finished, 'model load measurement window invalid')
    rows = measurement.get('rows')
    require(isinstance(rows, list) and len(rows) == count
            and all(isinstance(row, dict) and type(row.get('index')) is int for row in rows)
            and [row['index'] for row in rows] == list(range(count)), 'model load measurement denominator invalid')
    identities = {key: set() for key in ['subjectPid', 'eventId', 'deliveryPid']}
    for row in rows:
        proof = row.get('proof')
        require(isinstance(proof, dict) and all(isinstance(proof.get(key), str) and proof[key] for key in identities)
                and type(proof.get('attempts')) is int and proof['attempts'] == 1 and proof.get('statuses') == [200]
                and isinstance(proof.get('rawEvidence'), list) and len(proof['rawEvidence']) == 1,
                'model load signed receipt missing')
        for key, values in identities.items():
            require(proof[key] not in values, 'model load duplicate identity')
            values.add(proof[key])
        began, ended = row.get('createStartedAt'), row.get('createCompletedAt')
        require(all(type(value) in (int, float) and math.isfinite(value) for value in [began, ended])
                and start <= began < ended <= produced, 'model load producer trace invalid')
        actual = measurement_raw(proof)
        verified = verify_delivery(actual, secret=secret, delivery_pid=proof['deliveryPid'], event_id=proof['eventId'],
            subject_pid=proof['subjectPid'], statuses=[200], originating_request_id=origin_prefix + str(row['index']),
            event_type='open.resource.release.model-event.parts.created', subject_type='release.model-event.parts',
            schema_version=1, projections=[{'pid': proof['subjectPid'], 'name': 'Notification load ' + str(row['index']), 'zone': 'A'}])
        require(proof['rawEvidence'][0] == verified['rawEvidence'][0], 'model load signed receipt summary mismatch')
        require(start < verified['rawEvidence'][0]['receivedAt'] <= finished, 'model load receipt outside run')
        payload = json.loads(actual[0]['raw_body'])
        require(payload['recordVersion'] == 1 and payload['correlationId'] == origin_prefix + str(row['index']),
                'model load source metadata mismatch')
    samples = measurement.get('queueSamples')
    require(isinstance(samples, list) and bool(samples), 'model load queue observations missing')
    last_time = start if live else produced
    expected_subjects = identities['subjectPid']
    for sample in samples:
        require(isinstance(sample, dict) and type(sample.get('observedAt')) in (int, float)
                and math.isfinite(sample['observedAt']) and last_time <= sample['observedAt'] <= finished,
                'model load queue observation window invalid')
        last_time = sample['observedAt']
        states = sample.get('rows')
        selected = sample.get('subjects') if live else list(expected_subjects)
        require(isinstance(selected, list) and bool(selected) and len(set(selected)) == len(selected)
                and set(selected) <= expected_subjects, 'model load sampled subjects invalid')
        expected_events = {row['proof']['eventId'] for row in rows if row['proof']['subjectPid'] in selected}
        require(isinstance(states, list) and len(states) == len(selected)
                and all(isinstance(state, dict) for state in states)
                and {state.get('subject') for state in states} == set(selected)
                and {state.get('eventId') for state in states} == expected_events,
                'model load queue observation identities invalid')
        for state in states:
            require(state.get('status') in ('pending', 'processing', 'delivered')
                    and state.get('deliveryStatus') in (None, 'pending', 'processing', 'success')
                    and all(type(state.get(key)) is int and 0 <= state[key] <= 1 for key in ['receipts', 'deliveries'])
                    and type(state.get('sequence')) is int and state['sequence'] == 1,
                    'model load queue state invalid')
    final = {state['subject']: state for state in samples[-1]['rows']}
    require(set(final) == expected_subjects, 'model load final denominator missing')
    for row in rows:
        state, proof = final[row['proof']['subjectPid']], row['proof']
        require(state['eventId'] == proof['eventId'] and state.get('deliveryPid') == proof['deliveryPid']
                and state['status'] == 'delivered' and state['deliveryStatus'] == 'success'
                and state['receipts'] == state['deliveries'] == 1
                and type(state.get('responseStatus')) is int and state['responseStatus'] == 200
                and type(state.get('retryCount')) is int and state['retryCount'] == 0,
                'model load queue did not drain correctly')
    expected = load_metrics(measurement)
    require(isinstance(measurement.get('metrics'), dict) and set(measurement['metrics']) == set(expected)
            and all(type(measurement['metrics'][key]) in (int, float) and math.isfinite(measurement['metrics'][key])
                    and measurement['metrics'][key] == value for key, value in expected.items()),
            'model load reported metrics mismatch')
    require(expected['producerConcurrencyPeak'] <= concurrency, 'model load concurrency exceeds profile')
    return measurement


def collect_load_measurement(call, infrastructure, machine, tenant, subscription_pid, sink, secret):
    """Use the original accepting sink and published machine fixture for a burst."""
    result = {'profile': 'burst-32x4', 'authority': 'recorded', 'sloVerdict': 'unqualified', 'status': 'complete',
              'planned': LOAD_COUNT, 'concurrency': LOAD_CONCURRENCY, 'startedAt': time.time(), 'queueSamples': []}
    def create(index):
        began = time.time()
        _, body, _ = call('/api/open/v1/resources/release.model-event.parts', 'POST',
            body={'name': 'Notification load ' + str(index), 'zone': 'A'},
            headers=dict(machine, **{'Idempotency-Key': 'release-model-load-' + str(index),
                                      'X-Request-Id': 'release-model-load-' + str(index)}), expected=(201,))
        require(isinstance(body, dict) and isinstance(body.get('pid'), str) and body['pid'],
                'model load creation identity missing')
        return {'index': index, 'subjectPid': body['pid'], 'createStartedAt': began, 'createCompletedAt': time.time()}
    with ThreadPoolExecutor(max_workers=LOAD_CONCURRENCY) as pool:
        rows = list(pool.map(create, range(LOAD_COUNT)))
    subjects = [row['subjectPid'] for row in rows]
    require(len(set(subjects)) == LOAD_COUNT, 'model load duplicate created identity')
    result['producedAt'] = time.time()
    def drained():
        state = infrastructure.load_state(tenant, 'open.resource.release.model-event.parts.created', subjects, subscription_pid)
        require(isinstance(state, dict) and isinstance(state.get('sources'), list)
                and len(state['sources']) == LOAD_COUNT, 'model load source observations missing')
        result['queueSamples'].append({'observedAt': time.time(), 'rows': state['sources']})
        return state['sources'] if all(row['status'] == 'delivered' and row['deliveryStatus'] == 'success'
                                      for row in state['sources']) else None
    final = {row['subject']: row for row in observe(drained, seconds=60)}
    result['rows'] = rows
    for row in rows:
        state = final[row.pop('subjectPid')]
        actual = read_delivery(sink['store'], state['deliveryPid'], secret=secret,
                               run_id=sink['runId'], mode=sink['mode'], failures=1)
        row['proof'] = verify_delivery(actual, secret=secret, delivery_pid=state['deliveryPid'], event_id=state['eventId'],
            subject_pid=state['subject'], statuses=[200], originating_request_id='release-model-load-' + str(row['index']),
            event_type='open.resource.release.model-event.parts.created', subject_type='release.model-event.parts',
            schema_version=1, projections=[{'pid': state['subject'], 'name': 'Notification load ' + str(row['index']), 'zone': 'A'}])
    result['finishedAt'] = time.time()
    result['metrics'] = load_metrics(result)
    return verify_load_measurement(result, secret=secret)


def collect_sustained_measurement(call, infrastructure, machine, tenant, subscription_pid, sink, secret):
    """Record a paced minute with live queue observations; no acceptance thresholds."""
    result = {'profile': 'paced-60s-5rps', 'authority': 'recorded', 'sloVerdict': 'unqualified',
        'status': 'complete', 'planned': 300, 'concurrency': 4, 'durationSeconds': 60, 'ratePerSecond': 5,
        'startedAt': time.time(), 'queueSamples': []}
    completed, failures, lock, stop = [], Queue(), threading.Lock(), threading.Event()

    def sample():
        with lock:
            subjects = [row['subjectPid'] for row in completed]
        if not subjects:
            return None
        state = infrastructure.load_state(tenant, 'open.resource.release.model-event.parts.created',
            subjects, subscription_pid, expected_count=len(subjects))
        require(isinstance(state, dict) and isinstance(state.get('sources'), list)
                and len(state['sources']) == len(subjects), 'sustained source observations missing')
        result['queueSamples'].append({'observedAt': time.time(), 'subjects': subjects, 'rows': state['sources']})
        return state['sources']

    def monitor():
        try:
            while not stop.wait(1):
                sample()
        except BaseException as error:
            failures.put(error)

    def create(index):
        slot = result['startedAt'] + index / 5
        began = time.time()
        _, body, _ = call('/api/open/v1/resources/release.model-event.parts', 'POST',
            body={'name': 'Notification load ' + str(index), 'zone': 'A'},
            headers=dict(machine, **{'Idempotency-Key': 'release-model-sustained-' + str(index),
                                    'X-Request-Id': 'release-model-sustained-' + str(index)}), expected=(201,))
        require(isinstance(body, dict) and isinstance(body.get('pid'), str) and body['pid'],
                'sustained creation identity missing')
        row = {'index': index, 'subjectPid': body['pid'], 'scheduledAt': slot,
               'createStartedAt': began, 'createCompletedAt': time.time()}
        with lock:
            completed.append(row)
        return row

    thread = threading.Thread(target=monitor, name='owned-sustained-observer')
    thread.start()
    try:
        with ThreadPoolExecutor(max_workers=4) as pool:
            futures = []
            for index in range(300):
                if not failures.empty():
                    raise failures.get()
                remaining = result['startedAt'] + index / 5 - time.time()
                if remaining > 0:
                    time.sleep(remaining)
                futures.append(pool.submit(create, index))
            rows = [future.result() for future in futures]
        remaining = result['startedAt'] + 60 - time.time()
        if remaining > 0:
            time.sleep(remaining)
        result['producedAt'] = time.time()
    finally:
        stop.set()
        thread.join()
    if not failures.empty():
        raise failures.get()
    require(len({row['subjectPid'] for row in rows}) == 300, 'sustained duplicate creation identity')

    def drained():
        state = sample()
        return state if state and len(state) == 300 and all(row['status'] == 'delivered'
            and row['deliveryStatus'] == 'success' for row in state) else None

    final = {row['subject']: row for row in observe(drained, seconds=60)}
    result['rows'] = rows
    for row in rows:
        state = final[row.pop('subjectPid')]
        actual = read_delivery(sink['store'], state['deliveryPid'], secret=secret,
            run_id=sink['runId'], mode=sink['mode'], failures=1)
        row['proof'] = verify_delivery(actual, secret=secret, delivery_pid=state['deliveryPid'], event_id=state['eventId'],
            subject_pid=state['subject'], statuses=[200], originating_request_id='release-model-sustained-' + str(row['index']),
            event_type='open.resource.release.model-event.parts.created', subject_type='release.model-event.parts',
            schema_version=1, projections=[{'pid': state['subject'], 'name': 'Notification load ' + str(row['index']), 'zone': 'A'}])
    result['finishedAt'] = time.time()
    result['metrics'] = load_metrics(result)
    return verify_sustained_measurement(result, secret=secret)


def collect_receiver_isolation(call, human, infrastructure, machine, tenant, installation, fast_subscription, receivers, fast, secret):
    slow = receivers.start_gate('isolation')
    subscriptions, created = [], []
    result = {'status':'complete','profile':'receiver-origin-isolation-v1','rows':[]}
    try:
        for index in range(3):
            sub = human('/api/webhooks','POST',{'name':'Isolated slow endpoint '+str(index),
                'installationPid':installation,'eventType':'open.resource.release.model-event.parts.created',
                'eventVersion':1,'targetUrl':slow['url'] if index!=1 else slow['url'].replace('http://','http://gate-user@'),
                'secret':secret.decode(),'maxRetries':3,'timeoutMs':30000})
            subscriptions.append(sub['pid'])
        result.update(slowSubscriptions=subscriptions,fastSubscription=fast_subscription)
        for index in range(2):
            began=time.time(); origin='release-model-isolation-'+str(index)
            _,body,_=call('/api/open/v1/resources/release.model-event.parts','POST',
                body={'name':'Notification isolation '+str(index),'zone':'A'},
                headers=dict(machine,**{'Idempotency-Key':origin,'X-Request-Id':origin}),expected=(201,))
            created.append({'index':index,'subjectPid':body['pid'],'createStartedAt':began,'createCompletedAt':time.time()})
            if index==0:
                require(slow['entered'].wait(15),'slow receiver never entered the controlled wait')
                result['blockedAt']=time.time()
        result['created']=created
        subjects=[row['subjectPid'] for row in created]
        def fast_done():
            state=infrastructure.load_state(tenant,'open.resource.release.model-event.parts.created',subjects,
                fast_subscription,expected_count=2)
            return state['sources'] if len(state['sources'])==2 and all(row['deliveryStatus']=='success' for row in state['sources']) else None
        fast_states=observe(fast_done,seconds=12)
        result['observedWhileHeldAt']=time.time()
        with slow['lock']:
            require(len(slow['intervals'])==1 and 'endedAt' not in slow['intervals'][0],
                'slow endpoint monopolized slots or completed before the healthy receiver')
        result['heldState']=infrastructure.receiver_isolation_state(tenant,subjects,subscriptions+[fast_subscription])
        require(sum(row['deliveryStatus']=='processing' for row in result['heldState']['deliveries']
                    if row['subscriptionPid'] in subscriptions)==1,'same receiver claimed multiple concurrent attempts')
        result['releasedAt']=time.time();slow['gate'].set()
        def done():
            state=infrastructure.receiver_isolation_state(tenant,subjects,subscriptions+[fast_subscription])
            return state if len(state['deliveries'])==8 and all(row['deliveryStatus']=='success' for row in state['deliveries']) else None
        result['finalState']=observe(done,seconds=30)
        for state in result['finalState']['deliveries']:
            index=subjects.index(state['subjectPid']);sink=fast if state['subscriptionPid']==fast_subscription else slow
            actual=read_delivery(sink['store'],state['deliveryPid'],secret=secret,run_id=sink['runId'],mode=sink['mode'],failures=1)
            proof=verify_delivery(actual,secret=secret,delivery_pid=state['deliveryPid'],event_id=state['eventId'],
                subject_pid=subjects[index],statuses=[200],originating_request_id='release-model-isolation-'+str(index),
                event_type='open.resource.release.model-event.parts.created',subject_type='release.model-event.parts',schema_version=1,
                projections=[{'pid':subjects[index],'name':'Notification isolation '+str(index),'zone':'A'}])
            result['rows'].append({'subscriptionPid':state['subscriptionPid'],'index':index,'proof':proof})
        with slow['lock']:result['slowIntervals']=copy.deepcopy(slow['intervals'])
        result['finishedAt']=time.time()
        return verify_receiver_isolation(result,secret=secret)
    finally:
        slow['gate'].set()
        for sub in subscriptions:
            human('/api/webhooks/'+sub+'/disable','PUT',{})


def verify_receiver_isolation(result, *, secret):
    require(isinstance(result,dict) and result.get('profile')=='receiver-origin-isolation-v1'
            and result.get('status')=='complete','receiver isolation proof missing or incomplete')
    subs=result.get('slowSubscriptions');fast=result.get('fastSubscription')
    require(isinstance(subs,list) and len(subs)==3 and all(isinstance(x,str) and x for x in subs)
            and isinstance(fast,str) and bool(fast) and len(set(subs+[fast]))==4,'receiver isolation subscription denominator invalid')
    times=[result.get(k) for k in ['blockedAt','observedWhileHeldAt','releasedAt','finishedAt']]
    require(all(type(t) in (int,float) and math.isfinite(t) for t in times) and times==sorted(times)
            and times[0]<times[1]<times[2]<times[3],'receiver isolation ordering invalid')
    created=result.get('created');rows=result.get('rows')
    require(isinstance(created,list) and len(created)==2 and [r.get('index') for r in created]==[0,1]
            and all(type(r.get('index')) is int and isinstance(r.get('subjectPid'),str) for r in created)
            and len({r.get('subjectPid') for r in created})==2,'receiver isolation source denominator invalid')
    require(isinstance(rows,list) and len(rows)==8 and {(r.get('subscriptionPid'),r.get('index')) for r in rows}
            == {(sub,index) for sub in subs+[fast] for index in range(2)}
            and all(type(r.get('index')) is int for r in rows),'receiver isolation receipt denominator invalid')
    ids=set();events={};fast_received=[]
    for row in rows:
        index=row['index'];proof=row.get('proof');require(isinstance(proof,dict),'receiver isolation raw proof missing')
        require(type(proof.get('attempts')) is int and proof['attempts']==1,'receiver isolation attempts invalid')
        actual=measurement_raw(proof)
        verified=verify_delivery(actual,secret=secret,delivery_pid=proof['deliveryPid'],event_id=proof['eventId'],
            subject_pid=created[index]['subjectPid'],statuses=[200],originating_request_id='release-model-isolation-'+str(index),
            event_type='open.resource.release.model-event.parts.created',subject_type='release.model-event.parts',schema_version=1,
            projections=[{'pid':created[index]['subjectPid'],'name':'Notification isolation '+str(index),'zone':'A'}])
        require(proof==verified and proof['deliveryPid'] not in ids,'receiver isolation proof mismatch or duplicate')
        ids.add(proof['deliveryPid']);events.setdefault(index,proof['eventId'])
        require(events[index]==proof['eventId'],'receiver isolation source identity changed')
        if row['subscriptionPid']==fast and index==1:fast_received.append(actual[0]['received_at'])
    require(len(set(events.values()))==2,'receiver isolation duplicate source event')
    require(times[0]<fast_received[0]<times[1] and created[1]['createStartedAt']>=times[0],
            'healthy notification did not arrive during the controlled slow wait')
    intervals=result.get('slowIntervals');require(isinstance(intervals,list) and len(intervals)==6,
            'receiver isolation slow attempt denominator invalid')
    slow_ids={r['proof']['deliveryPid'] for r in rows if r['subscriptionPid'] in subs}
    require({r.get('deliveryPid') for r in intervals}==slow_ids,'receiver isolation interval identities invalid')
    intervals=sorted(intervals,key=lambda r:r['startedAt'])
    require(intervals[0]['startedAt']<=times[0] and intervals[0]['endedAt']>=times[2],
            'receiver isolation first slow attempt was not held')
    for index,r in enumerate(intervals):
        require(all(type(r.get(k)) in (int,float) and math.isfinite(r[k]) for k in ['startedAt','endedAt'])
                and r['startedAt']<r['endedAt']<=times[3]
                and (index==0 or intervals[index-1]['endedAt']<=r['startedAt']),
                'same receiver sent overlapping attempts')
    for key in ['heldState','finalState']:
        state=result.get(key);require(isinstance(state,dict) and isinstance(state.get('deliveries'),list)
            and len(state['deliveries'])==8 and {r.get('deliveryPid') for r in state['deliveries']}==ids,
            'receiver isolation database denominator invalid')
        for r in state['deliveries']:
            expected=next(x for x in rows if x['proof']['deliveryPid']==r['deliveryPid'])
            require(r['subscriptionPid']==expected['subscriptionPid'] and r['subjectPid']==created[expected['index']]['subjectPid']
                and r['eventId']==expected['proof']['eventId'] and type(r.get('retryCount')) is int and r['retryCount']==0,
                'receiver isolation database identity mismatch')
        if key=='finalState':
            require(all(r['deliveryStatus']=='success' and r['responseStatus']==200 for r in state['deliveries']),
                    'receiver isolation database did not drain')
        else:
            slow_rows=[r for r in state['deliveries'] if r['subscriptionPid'] in subs]
            require(sum(r['deliveryStatus']=='processing' for r in slow_rows)==1
                and sum(r['deliveryStatus']=='pending' for r in slow_rows)==5
                and all(r['deliveryStatus']=='success' for r in state['deliveries'] if r['subscriptionPid']==fast),
                'receiver isolation live database state invalid')
    return result
