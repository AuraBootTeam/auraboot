"""Hermetic runner safety tests; not deployed-target product acceptance."""

import importlib.util
import hashlib
import hmac
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import time
import sqlite3
import unittest
from unittest.mock import patch

from open_platform_canary import CONTRACTS, Ledger, ProtocolCanary, external_outcome, main, private_read, redact, validate_fixture
from open_platform_http import Client, ProtocolError
from open_platform_receiver import Receiver
from open_platform_webhook_evidence import read_delivery, verify_delivery


def fixture(root):
    root = Path(root)
    password = root / 'password'
    password.write_text('private-test-password')
    password.chmod(0o600)
    identity = root / 'identity.json'
    identity.write_text('{}')
    return {'schemaVersion': 1, 'runId': 'canary-selftest-001', 'origin': 'https://example.test',
            'auth': {'identifier': 'owner@test', 'passwordFile': str(password), 'tenantId': 1},
            'foreign': {'identifier': 'foreign@test', 'passwordFile': str(password), 'tenantId': 2},
            'targetIdentityEvidence': str(identity)}


class RunnerTests(unittest.TestCase):
    def test_signed_raw_transport_preserves_exact_bytes(self):
        class Response(io.BytesIO):
            code = 200
            headers = {}

        class Opener:
            def open(self, request, timeout):
                self.request = request
                return Response(b'{}')

        opener = Opener()
        raw = b'{ "id": "event-1", "data": {} }'
        client = Client('https://example.test', opener=opener)
        client.request('/webhooks/run', method='POST', raw=raw)
        self.assertEqual(opener.request.data, raw)
        with self.assertRaises(ValueError):
            client.request('/webhooks/run', raw=raw, body={})
        self.assertEqual(client.request_count, 1)

    def test_receiver_evidence_is_recomputed_not_trusted(self):
        with tempfile.TemporaryDirectory() as root:
            secret = b'private-receiver-secret'
            path = Path(root) / 'receiver.sqlite'
            receiver = Receiver(path, secret, 'fresh-run')
            raw = b'{"id":"event-1","type":"assets.assignment.changed","schemaVersion":1,"subject":{"type":"assets","pid":"fresh-asset"},"data":{}}'
            timestamp = str(int(time.time()))
            signature = 'sha256=' + hmac.new(secret, timestamp.encode() + b'.' + raw, hashlib.sha256).hexdigest()
            self.assertEqual(receiver.receive(raw, {'X-Webhook-Timestamp': timestamp,
                'X-Webhook-Signature': signature, 'X-Webhook-Delivery': 'delivery-1'})[0], 200)
            receiver.close()
            rows = read_delivery(path, 'delivery-1', secret=secret, run_id='fresh-run', mode='accept', failures=1)
            kwargs = {'secret': secret, 'delivery_pid': 'delivery-1', 'event_id': 'event-1',
                      'subject_pid': 'fresh-asset', 'statuses': [200]}
            proof = verify_delivery(rows, **kwargs)
            self.assertEqual(proof['attempts'], 1)
            with self.assertRaises(ProtocolError):
                verify_delivery(rows, **kwargs, originating_request_id='original-request')
            rows[0]['request_id'] = 'original-request'
            self.assertEqual(verify_delivery(rows, **kwargs, originating_request_id='original-request')['attempts'], 1)
            rows[0]['signature'] = 'sha256=' + '0' * 64
            self.assertEqual(rows[0]['signature_valid'], 1)
            with self.assertRaises(ProtocolError):
                verify_delivery(rows, **kwargs)

    def test_legacy_receiver_store_is_rejected_without_modification(self):
        with tempfile.TemporaryDirectory() as root:
            path = Path(root) / 'legacy.sqlite'
            with sqlite3.connect(path) as db:
                db.execute('CREATE TABLE deliveries(sequence INTEGER PRIMARY KEY, signature_valid INTEGER)')
            path.chmod(0o600)
            before = path.read_bytes()
            with self.assertRaises(ValueError):
                Receiver(path, b'secret', 'fresh-run')
            self.assertEqual(path.read_bytes(), before)

    def test_event_requires_exact_consumer_identity_and_downstream_artifact(self):
        event = {'id': 'fresh-event', 'type': 'asset.created', 'data': {'runId': 'canary-selftest-001', 'originatingRequestId': 'fresh-request'}}
        log = {'status': 'success', 'automationId': 'automation-1', 'tenantId': 1,
               'triggerType': 'external_event', 'triggerRecordPid': event['id'],
               'triggerPayload': {'eventId': event['id'], 'sourceCode': 'canary-selftest-001',
                                 'eventType': 'external.canary-selftest-001.asset.created.v1', 'data': event['data'], 'requestId': 'fresh-request'},
               'actionResults': [{'status': 'success', 'actionType': 'create_record',
                                  'result': {'success': True, 'modelCode': 'tasset_asset',
                                             'record': {'pid': 'fresh-output', 'tasset_as_code': 'canary-selftest-001-consumed'}}}]}
        self.assertEqual(external_outcome(log, event, 'automation-1', 1, 'canary-selftest-001'), 'fresh-output')
        for change in ({'status': 'failed'}, {'tenantId': 2}, {'triggerRecordPid': 'old-event'},
                       {'automationId': 'old-automation'}, {'actionResults': []}, {'triggerPayload': {}}):
            with self.subTest(change=change), self.assertRaises(ProtocolError):
                external_outcome(dict(log, **change), event, 'automation-1', 1, 'canary-selftest-001')
        wrong_output = json.loads(json.dumps(log))
        wrong_output['actionResults'][0]['result']['record']['tasset_as_code'] = 'historical-output'
        with self.assertRaises(ProtocolError):
            external_outcome(wrong_output, event, 'automation-1', 1, 'canary-selftest-001')

    def test_event_pass_cannot_close_webhook_trace_or_target_identity(self):
        ledger = Ledger()
        for key in (*CONTRACTS[:9], 'CANARY-EVENT'):
            start = len(ledger.requests)
            ledger.requests.append({'status': 200})
            ledger.passed(key, start)
        result = ledger.receipt()
        self.assertEqual(result['status'], 'PARTIAL')
        self.assertFalse(result['targetIdentityVerifiedByRunner'])
        self.assertEqual([row['id'] for row in result['contracts'] if row['verdict'] == 'untested'],
                         ['CANARY-WEBHOOK', 'CANARY-TRACE'])

    def test_import_has_no_target_effects(self):
        with patch('urllib.request.OpenerDirector.open', side_effect=AssertionError('network at import')):
            spec = importlib.util.spec_from_file_location('isolated_canary', Path(__file__).with_name('open_platform_canary.py'))
            spec.loader.exec_module(importlib.util.module_from_spec(spec))

    def test_tls_and_distinct_tenants_are_required(self):
        with tempfile.TemporaryDirectory() as root:
            value = fixture(root)
            self.assertEqual(validate_fixture(value), value)
            for change in ({'origin': 'http://example.test'}, {'foreign': value['auth']}, {'runId': '../outside'}):
                with self.subTest(change=change), self.assertRaises(ProtocolError):
                    validate_fixture(dict(value, **change))

    def test_input_permissions_and_symlinks_are_rejected(self):
        with tempfile.TemporaryDirectory() as root:
            path = Path(fixture(root)['auth']['passwordFile'])
            path.chmod(0o644)
            with self.assertRaises(ProtocolError):
                private_read(path)
            path.chmod(0o600)
            link = Path(root) / 'link'
            link.symlink_to(path)
            with self.assertRaises(ProtocolError):
                private_read(link)

    def test_redaction_is_recursive_and_value_aware(self):
        value = {'credential': {'clientSecret': 'credential-sensitive-123'}, 'access_token': 'token-sensitive-456',
                 'details': ['message contains password-sensitive-789'], 'jwt': 'jwt-sensitive-012'}
        encoded = json.dumps(redact(value, ['password-sensitive-789']))
        for secret in ('credential-sensitive-123', 'token-sensitive-456', 'password-sensitive-789', 'jwt-sensitive-012'):
            self.assertNotIn(secret, encoded)
        self.assertIn('[redacted]', encoded)

    def test_empty_evidence_cannot_pass_a_contract(self):
        with self.assertRaises(ProtocolError): Ledger().passed('CANARY-TOKEN', 0)

    def test_denominator_and_partial_verdict_cannot_be_shrunk(self):
        ledger = Ledger()
        self.assertEqual(len(CONTRACTS), 12)
        self.assertEqual(len(set(CONTRACTS)), 12)
        for key in CONTRACTS[:9]:
            start = len(ledger.requests)
            ledger.requests.append({'status': 200})
            ledger.passed(key, start)
        receipt = ledger.receipt()
        self.assertEqual(receipt['status'], 'PARTIAL')
        self.assertFalse(receipt['targetIdentityVerifiedByRunner'])
        self.assertEqual([r['id'] for r in receipt['contracts'] if r['verdict'] == 'untested'],
                         ['CANARY-EVENT', 'CANARY-WEBHOOK', 'CANARY-TRACE'])

    def test_existing_output_prevents_any_execution(self):
        with tempfile.TemporaryDirectory() as root:
            path = Path(root) / 'fixture.json'
            path.write_text(json.dumps(fixture(root)))
            path.chmod(0o600)
            output = Path(root) / 'receipt.json'
            output.write_text('retained')
            with patch.object(sys, 'argv', ['runner', '--fixture', str(path), '--out', str(output)]), \
                    patch.object(ProtocolCanary, 'execute') as execute, self.assertRaises(FileExistsError):
                main()
            execute.assert_not_called()
            self.assertEqual(output.read_text(), 'retained')

    def test_failure_keeps_all_contracts_and_never_echoes_secret(self):
        with tempfile.TemporaryDirectory() as root:
            path = Path(root) / 'fixture.json'
            path.write_text(json.dumps(fixture(root)))
            path.chmod(0o600)
            output = Path(root) / 'receipt.json'
            with patch.object(sys, 'argv', ['runner', '--fixture', str(path), '--out', str(output)]), \
                    patch.object(ProtocolCanary, 'execute', side_effect=ProtocolError('private-test-password')), \
                    patch('sys.stdout', new=io.StringIO()):
                self.assertEqual(main(), 1)
            receipt = json.loads(output.read_text())
            self.assertEqual(receipt['status'], 'PARTIAL')
            self.assertEqual(len(receipt['contracts']), 12)
            self.assertTrue(all(row['verdict'] == 'untested' for row in receipt['contracts']))
            self.assertNotIn('private-test-password', output.read_text())
            self.assertEqual(output.stat().st_mode & 0o777, 0o600)


if __name__ == '__main__':
    if '--mutation-claim-complete' in sys.argv:
        sys.argv.remove('--mutation-claim-complete')
        original = Ledger.receipt

        def false_completion(self):
            result = original(self)
            result['status'] = 'PASS'
            return result

        with patch.object(Ledger, 'receipt', false_completion):
            unittest.main()
    else:
        unittest.main()
