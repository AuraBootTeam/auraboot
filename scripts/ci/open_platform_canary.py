#!/usr/bin/env python3
"""Deployed-target protocol slice. Never seeds or installs plugins at import/run.

The full twelve-contract denominator is retained. Optional event and webhook
drivers collect real evidence; target identity and complete request-ID propagation
remain independently required. This slice always exits nonzero.
"""

import argparse
from datetime import datetime, timezone
import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import stat
import sys
import time
import urllib.parse

from open_platform_http import Client, ProtocolError, data
from open_platform_webhook_evidence import read_delivery, verify_delivery

CONTRACTS = tuple('CANARY-' + name for name in (
    'TOKEN', 'SCOPE', 'TENANT', 'READ', 'CURSOR', 'ETAG', 'IDEMPOTENCY',
    'CREDENTIAL', 'INSTALLATION', 'EVENT', 'WEBHOOK', 'TRACE'))
SCOPES = ('assets.read', 'assets.manage', 'inventory.stock-ins.read',
          'inventory.stock-ins.manage', 'openapi.events.read',
          'openapi.profile.read', 'automation.events.write')
ASSET_FIELDS = {'pid', 'assetCode', 'name', 'serialNumber', 'status',
                'assignedTo', 'location', 'updatedAt'}


def need(condition, message):
    if not condition:
        raise ProtocolError(message)


def private_read(path):
    path = Path(path)
    info = path.lstat()
    need(stat.S_ISREG(info.st_mode) and not info.st_mode & 0o077,
         'fixture and password inputs must be private regular files')
    return path.read_text(encoding='utf-8')


def validate_fixture(value):
    need(value.get('schemaVersion') == 1, 'fixture schema version required')
    run_id = value.get('runId', '')
    need(re.fullmatch(r'[a-z][a-z0-9-]{7,47}', run_id), 'invalid fresh run ID')
    origin = value.get('origin', '')
    Client(origin)
    need(origin.startswith('https://'), 'deployed canary requires TLS')
    auth = value.get('auth', {})
    foreign = value.get('foreign', {})
    need(auth.get('identifier') and auth.get('passwordFile') and auth.get('tenantId'),
         'real account and explicit tenant required')
    need(str(auth['tenantId']) != str(foreign.get('tenantId')),
         'foreign fixture must belong to another tenant')
    need(foreign.get('identifier') and foreign.get('passwordFile') and foreign.get('tenantId'),
         'another real account/tenant is required to create the foreign fixture')
    need(value.get('targetIdentityEvidence'), 'target identity evidence path required')
    need(isinstance(value.get('includeExternalEvent', False), bool), 'includeExternalEvent must be boolean')
    need(isinstance(value.get('includeWebhook', False), bool), 'includeWebhook must be boolean')
    if value.get('includeWebhook', False):
        receivers = value.get('receivers', {})
        need(set(receivers) == {'accept', 'retry', 'replay'}, 'three independent receiver policies required')
        for name, receiver in receivers.items():
            parsed = urllib.parse.urlsplit(receiver.get('url', ''))
            Client(f'{parsed.scheme}://{parsed.netloc}')
            need(parsed.scheme == 'https' and parsed.path == f'/webhooks/{run_id}-{name}'
                 and not parsed.query and not parsed.fragment and receiver.get('secretFile') and receiver.get('store'),
                 'receiver must use its exact run-scoped HTTPS path and private evidence inputs')
    return value


def redact(value, secrets=()):
    if isinstance(value, dict):
        return {key: ('[redacted]' if key.lower() in {
            'jwt', 'password', 'clientsecret', 'client_secret', 'access_token',
            'authorization', 'secret', 'refresh_token'} else redact(item, secrets))
                for key, item in value.items()}
    if isinstance(value, list):
        return [redact(item, secrets) for item in value]
    if isinstance(value, str):
        for secret in secrets:
            if secret:
                value = value.replace(secret, '[redacted]')
    return value


class Ledger:
    def __init__(self):
        self.requests = []
        self.rows = {key: {'id': key, 'verdict': 'untested', 'evidence': []}
                     for key in CONTRACTS}

    def passed(self, key, first_request):
        need(key in self.rows and first_request < len(self.requests),
             'claim requires actual request evidence')
        self.rows[key] = {'id': key, 'verdict': 'pass',
                          'evidence': list(range(first_request, len(self.requests)))}

    def receipt(self):
        # Protocol execution does not itself attest the deployed image/source.
        return {'schemaVersion': 1, 'claimLevel': 'protocol-slice', 'status': 'PARTIAL',
                'targetIdentityVerifiedByRunner': False,
                'contracts': list(self.rows.values()), 'requests': self.requests,
                'allowedClaim': 'only executed protocol rows; no full canary or release qualification'}


def external_outcome(log, event, automation_pid, tenant_id, run_id):
    need(log.get('status') == 'success' and log.get('automationId') == automation_pid
         and str(log.get('tenantId')) == str(tenant_id)
         and log.get('triggerType') == 'external_event' and log.get('triggerRecordPid') == event['id'],
         'external event consumer log identity/status mismatch')
    payload = log.get('triggerPayload', {})
    need(payload.get('eventId') == event['id'] and payload.get('sourceCode') == run_id
         and payload.get('eventType') == f"external.{run_id}.{event['type']}.v1"
         and payload.get('data') == event['data']
         and payload.get('requestId') == event['data']['originatingRequestId'],
         'consumer payload/trace does not match fresh ingress')
    results = log.get('actionResults', [])
    need(len(results) == 1 and results[0].get('status') == 'success'
         and results[0].get('actionType') == 'create_record', 'consumer action did not succeed')
    result = results[0].get('result', {})
    record = result.get('record', {})
    need(result.get('success') is True and result.get('modelCode') == 'tasset_asset'
         and record.get('pid') and record.get('tasset_as_code') == run_id + '-consumed',
         'consumer did not create the fresh downstream artifact')
    return record['pid']


class ProtocolCanary:
    def __init__(self, fixture, ledger):
        self.fixture = validate_fixture(fixture)
        self.ledger = ledger
        self.client = Client(fixture['origin'], max_requests=250, timeout=30)
        self.secrets = []
        self.run_id = fixture['runId']
        self.admin = None
        self.machine = None

    def call(self, path, *, headers=None, **kwargs):
        request_id = f'{self.run_id}-{len(self.ledger.requests):03d}'
        headers = dict(headers or {}, **{'X-Request-Id': request_id})
        row = {'path': path, 'method': kwargs.get('method', 'GET'),
               'requestId': request_id, 'body': redact(kwargs.get('body'), self.secrets),
               'form': redact(kwargs.get('form'), self.secrets)}
        self.ledger.requests.append(row)
        try:
            status, body, response_headers = self.client.request(path, headers=headers, **kwargs)
        except ProtocolError:
            row['transportError'] = 'ProtocolError'
            raise
        if isinstance(body, dict):
            for key in ('jwt', 'access_token', 'clientSecret'):
                if isinstance(body.get(key), str):
                    self.secrets.append(body[key])
            nested = data(body)
            if isinstance(nested, dict):
                for key in ('jwt', 'clientSecret'):
                    if isinstance(nested.get(key), str):
                        self.secrets.append(nested[key])
        row.update(status=status, response=redact(body, self.secrets),
                   responseHeaders={k: v for k, v in response_headers.items()
                                    if k.lower() in {'etag', 'x-request-id', 'content-type'}})
        if isinstance(body, dict) and 'code' in body and path.startswith('/api/') and status == 200:
            need(str(body['code']) == '0', 'management API returned a non-success envelope')
        return body, {k.lower(): v for k, v in response_headers.items()}

    def admin_call(self, path, **kwargs):
        return data(self.call(path, headers=self.admin, **kwargs)[0])

    def token(self, credential, scopes=SCOPES, expected=(200,)):
        body, _ = self.call('/oauth2/token', method='POST', form={
            'grant_type': 'client_credentials', 'client_id': credential['clientId'],
            'client_secret': credential['clientSecret'], 'scope': ' '.join(scopes)}, expected=expected)
        if expected == (200,):
            need(body.get('token_type') == 'Bearer' and body.get('expires_in', 0) > 0,
                 'invalid token lifetime/type')
            need(set(body.get('scope', '').split()) == set(scopes), 'token scope mismatch')
            need(body.get('access_token'), 'empty access token')
            return {'Authorization': 'Bearer ' + body['access_token']}
        return body

    def external_event(self, installation_pid):
        start = len(self.ledger.requests)
        event_type = f'external.{self.run_id}.asset.created.v1'
        automation = self.admin_call('/api/automations', method='POST', body={
            'name': self.run_id + ' external consumer', 'modelCode': self.run_id,
            'triggerType': 'external_event', 'triggerConfig': {'eventTypes': [event_type]},
            'enabled': True, 'actions': [{'type': 'create_record', 'sequence': 0,
                'continueOnError': False, 'config': {'modelCode': 'tasset_asset', 'fields': {
                    'tasset_as_code': self.run_id + '-consumed', 'tasset_as_name': self.run_id,
                    'tasset_as_status': 'available'}}}]})
        need(automation.get('pid') and automation.get('enabled') is True, 'consumer automation was not enabled')
        request_id = f'{self.run_id}-{len(self.ledger.requests):03d}'
        event = {'id': self.run_id + '-external-event', 'type': 'asset.created', 'schemaVersion': 1,
                 'occurredAt': datetime.now(timezone.utc).isoformat().replace('+00:00', 'Z'),
                 'subject': {'type': 'asset', 'pid': self.run_id + '-external-subject'},
                 'sequence': 1, 'data': {'runId': self.run_id, 'originatingRequestId': request_id}}
        route = f'/api/open/v1/event-sources/{self.run_id}/events'
        event_headers = dict(self.machine, **{'Idempotency-Key': self.run_id + '-external-key'})
        accepted = self.call(route, method='POST', headers=event_headers, body=event, expected=(202,))[0]
        need(accepted == {'eventId': event['id'], 'duplicate': False}, 'fresh event was not accepted')
        duplicate = self.call(route, method='POST', headers=event_headers, body=event, expected=(202,))[0]
        need(duplicate == {'eventId': event['id'], 'duplicate': True}, 'event replay was not deduplicated')
        self.call(route, method='POST', headers=event_headers,
                  body=dict(event, data={'runId': self.run_id, 'conflict': True}), expected=(409,))
        log = None
        for attempt in range(15):
            logs = self.admin_call(f"/api/automations/{automation['pid']}/logs?limit=50")
            matching = [item for item in logs if item.get('triggerRecordPid') == event['id']]
            need(len(matching) <= 1, 'event duplicate produced another automation execution')
            if matching and matching[0].get('status') not in {'pending', 'running'}:
                log = matching[0]
                break
            if attempt < 14:
                time.sleep(1)
        need(log is not None, 'bounded event consumer observation expired')
        artifact_pid = external_outcome(log, event, automation['pid'], self.fixture['auth']['tenantId'], self.run_id)
        artifact = self.call('/api/open/v1/resources/assets/' + artifact_pid, headers=self.machine)[0]
        need(artifact.get('assetCode') == self.run_id + '-consumed' and artifact.get('status') == 'available',
             'consumer artifact is not persisted through the public facade')
        audit = self.admin_call(f'/api/open-platform/installations/{installation_pid}/audits?requestId={request_id}&limit=50')
        need(any(item.get('requestId') == request_id and item.get('status') == 202
                 and item.get('path') == route for item in audit), 'fresh ingress request has no installation audit')
        self.event_trace = {'requestId': request_id, 'eventId': event['id'],
                            'expectedDurableEventId': f"ext:{installation_pid}:{event['id']}",
                            'automationPid': automation['pid'], 'automationLogPid': log['pid'],
                            'artifactPid': artifact_pid}
        self.ledger.passed('CANARY-EVENT', start)

    def webhooks(self, installation_pid):
        start = len(self.ledger.requests)
        configs = {}
        for name, source in self.fixture['receivers'].items():
            secret = private_read(source['secretFile']).rstrip('\r\n').encode()
            need(secret, 'empty receiver secret')
            self.secrets.append(secret.decode())
            config = dict(source, secret=secret, run_id=self.run_id + '-' + name,
                          mode='accept' if name == 'accept' else 'fail-first', failures=3 if name == 'replay' else 1)
            # Check the independent store/policy before creating subscriptions.
            read_delivery(config['store'], 'not-yet-created', secret=secret,
                          run_id=config['run_id'], mode=config['mode'], failures=config['failures'])
            configs[name] = config
        names = {name: self.run_id + '-' + name for name in configs}
        for name, config in configs.items():
            subscription = self.admin_call('/api/webhooks', method='POST', body={
                'name': names[name], 'installationPid': installation_pid, 'targetUrl': config['url'],
                'eventType': 'assets.assignment.changed', 'eventVersion': 1,
                'secret': config['secret'].decode(), 'maxRetries': 3, 'timeoutMs': 10000, 'enabled': True})
            need(subscription.get('pid'), 'webhook subscription identity missing')
        asset = self.admin_call('/api/dynamic/tasset_asset/create', method='POST', body={
            'tasset_as_code': self.run_id + '-webhook', 'tasset_as_name': self.run_id,
            'tasset_as_status': 'available'})
        _, headers = self.call('/api/open/v1/resources/assets/' + asset['pid'], headers=self.machine)
        request_id = f'{self.run_id}-{len(self.ledger.requests):03d}'
        command = '/api/open/v1/commands/assets.assign:execute'
        assigned = self.call(command, method='POST', body={'targetPid': asset['pid'], 'input': {'assignee': self.run_id}},
            headers=dict(self.machine, **{'Idempotency-Key': self.run_id + '-webhook-assign', 'If-Match': headers['etag']}))[0]
        need(assigned.get('idempotentReplay') is False and assigned['resource']['status'] == 'in_use',
             'webhook source command did not transition fresh fixture')
        route = f'/api/open-platform/installations/{installation_pid}/webhook-deliveries?limit=50'

        def observe(expected):
            for attempt in range(45):
                rows = self.admin_call(route)
                deliveries = {}
                for name, subscription_name in names.items():
                    matches = [row for row in rows if row.get('subscriptionName') == subscription_name]
                    need(len(matches) <= 1, 'one fresh event produced duplicate queue deliveries')
                    if matches:
                        deliveries[name] = matches[0]
                if len(deliveries) == 3 and all(deliveries[name].get('status') == status for name, status in expected.items()):
                    return deliveries
                if attempt < 44:
                    time.sleep(1)
            raise ProtocolError('bounded webhook queue observation expired')

        deliveries = observe({'accept': 'success', 'retry': 'success', 'replay': 'dead_letter'})
        event_ids = {row.get('eventId') for row in deliveries.values()}
        need(len(event_ids) == 1 and None not in event_ids, 'webhook subscriptions do not share the fresh event')
        event_id = next(iter(event_ids))
        proofs = {}

        def verify(name, statuses):
            config, delivery = configs[name], deliveries[name]
            rows = read_delivery(config['store'], delivery['pid'], secret=config['secret'],
                                 run_id=config['run_id'], mode=config['mode'], failures=config['failures'])
            return verify_delivery(rows, secret=config['secret'], delivery_pid=delivery['pid'],
                                   event_id=event_id, subject_pid=asset['pid'], statuses=statuses, originating_request_id=request_id)

        need(deliveries['retry'].get('retryCount') == 1 and deliveries['replay'].get('retryCount') == 3
             and deliveries['replay'].get('replayable') is True, 'retry/DLQ attempt or replayability mismatch')
        proofs['accept'] = verify('accept', [200])
        proofs['retry'] = verify('retry', [503, 200])
        proofs['deadLetterBeforeReplay'] = verify('replay', [503, 503, 503])
        self.admin_call(f"/api/open-platform/installations/{installation_pid}/webhook-deliveries/{deliveries['replay']['pid']}/replay",
                        method='POST', body={})
        deliveries = observe({'accept': 'success', 'retry': 'success', 'replay': 'success'})
        need(deliveries['replay'].get('replayCount') == 1 and deliveries['replay'].get('retryCount') == 0,
             'real DLQ replay state mismatch')
        proofs['replayed'] = verify('replay', [503, 503, 503, 200])
        # Independently drive receiver deduplication and signature/time denial
        # using the original bytes. These are explicitly receiver-path checks.
        accept = configs['accept']
        raw_rows = read_delivery(accept['store'], deliveries['accept']['pid'], secret=accept['secret'],
                                run_id=accept['run_id'], mode=accept['mode'], failures=accept['failures'])
        raw = raw_rows[0]['raw_body']
        parsed = urllib.parse.urlsplit(accept['url'])
        receiver_client = Client(f'{parsed.scheme}://{parsed.netloc}', max_requests=3, timeout=15)
        for label, timestamp, bad_signature, expected in (
                ('dedup', str(int(time.time())), False, 200),
                ('invalid-signature', str(int(time.time())), True, 401),
                ('expired', str(int(time.time()) - 301), False, 401)):
            signature = 'sha256=' + hmac.new(accept['secret'], timestamp.encode() + b'.' + raw, hashlib.sha256).hexdigest()
            if bad_signature:
                signature = 'sha256=' + '0' * 64
            delivery_pid = self.run_id + '-' + label
            status, result, _ = receiver_client.request(parsed.path, method='POST', raw=raw, expected=(expected,),
                headers={'X-Webhook-Timestamp': timestamp, 'X-Webhook-Signature': signature,
                         'X-Webhook-Delivery': delivery_pid})
            self.ledger.requests.append({'origin': receiver_client.base_url, 'path': parsed.path, 'method': 'POST',
                                         'receiverCase': label, 'status': status, 'response': result,
                                         'rawBodySha256': hashlib.sha256(raw).hexdigest()})
            need(result.get('runId') == accept['run_id'] and result.get('accepted') is (expected == 200),
                 'independent receiver result mismatch')
            if label == 'dedup':
                need(result.get('duplicate') is True, 'independent receiver did not deduplicate original event')
        self.webhook_trace = {'requestId': request_id, 'command': command, 'assetPid': asset['pid'],
                              'eventId': event_id, 'deliveries': deliveries, 'receiverProofs': proofs,
                              'receiverRequestCount': receiver_client.request_count,
                              'receiverNegativeChecks': ['invalid-signature', 'expired']}
        self.ledger.passed('CANARY-WEBHOOK', start)

    def trace(self, installation_pid):
        start = len(self.ledger.requests)
        need(hasattr(self, 'event_trace') and hasattr(self, 'webhook_trace'), 'both real chains required for trace')
        trace = self.webhook_trace
        audits = self.admin_call(f"/api/open-platform/installations/{installation_pid}/audits?requestId={trace['requestId']}&limit=50")
        need(any(row.get('requestId') == trace['requestId'] and row.get('status') == 200
                 and row.get('path') == trace['command'] for row in audits), 'command requestId is not auditable')
        resource = self.call('/api/open/v1/resources/assets/' + trace['assetPid'], headers=self.machine)[0]
        need(resource.get('assetCode') == self.run_id + '-webhook' and resource.get('status') == 'in_use',
             'request/audit/event/delivery/receiver join does not match fresh persisted resource')
        need(all(row.get('requestId') == trace['requestId'] for row in trace['deliveries'].values()),
             'durable queue requestId does not match the original command')
        need(all(item.get('requestId') == trace['requestId']
                 for proof in trace['receiverProofs'].values() for item in proof['rawEvidence']),
             'receiver did not observe the original command requestId on every attempt')
        self.trace_observation = {'requestId': trace['requestId'],
                                  'assetPid': trace['assetPid'], 'eventId': trace['eventId'],
                                  'correlationVerified': True,
                                  'requestIdPropagationVerified': True,
                                  'eventIngressRequestId': self.event_trace['requestId']}
        self.ledger.passed('CANARY-TRACE', start)

    def execute(self):
        auth = self.fixture['auth']
        password = private_read(auth['passwordFile']).strip()
        need(password, 'empty account password')
        self.secrets.append(password)
        login = data(self.call('/api/auth/login', method='POST',
                              body={'identifier': auth['identifier'], 'password': password})[0])
        need(str(login.get('tenantId')) == str(auth['tenantId']), 'account tenant mismatch')
        need(login.get('jwt') and not login.get('mustChangePassword'), 'account is not ready')
        self.admin = {'Authorization': 'Bearer ' + login['jwt'], 'X-Tenant-Id': str(auth['tenantId'])}
        application = self.admin_call('/api/open-platform/applications', method='POST',
                                      body={'name': self.run_id, 'description': 'retained test canary fixture'})
        installation = self.admin_call(f"/api/open-platform/applications/{application['pid']}/installations",
                                       method='POST', body={'environment': 'staging', 'scopes': list(SCOPES),
                                                            'rateLimitPerMinute': 200})
        prefix = f"/api/open-platform/installations/{installation['pid']}"
        credential = self.admin_call(prefix + '/credentials', method='POST', body={})
        start = len(self.ledger.requests)
        self.machine = self.token(credential)
        principal = self.call('/api/open/v1/whoami', headers=self.machine)[0]
        need(principal.get('applicationPid') == application['pid']
             and principal.get('installationPid') == installation['pid']
             and principal.get('environment') == 'staging'
             and set(principal.get('scopes', [])) == set(SCOPES), 'machine principal mismatch')
        self.token(dict(credential, clientSecret='invalid-' + self.run_id), expected=(401,))
        self.ledger.passed('CANARY-TOKEN', start)
        start = len(self.ledger.requests)
        narrow = self.token(credential, ('openapi.profile.read',))
        narrow_principal = self.call('/api/open/v1/whoami', headers=narrow)[0]
        need(set(narrow_principal.get('scopes', [])) == {'openapi.profile.read'}, 'narrow principal scope mismatch')
        self.call('/api/open/v1/resources/assets', headers=narrow, expected=(403,))
        scope_start = start
        assets = []
        for index in range(2):
            fields = {'tasset_as_code': f'{self.run_id}-{index}',
                      'tasset_as_name': f'{self.run_id} asset {index}', 'tasset_as_status': 'available'}
            assets.append(self.admin_call('/api/dynamic/tasset_asset/create', method='POST', body=fields))
        product = self.admin_call('/api/dynamic/tinv_product/create', method='POST',
                                  body={'tinv_pd_code': self.run_id, 'tinv_pd_name': self.run_id})
        warehouse = self.admin_call('/api/dynamic/tinv_warehouse/create', method='POST',
                                    body={'tinv_wh_code': self.run_id, 'tinv_wh_name': self.run_id})
        stock = self.admin_call('/api/dynamic/tinv_stock_in/create', method='POST', body={
            'tinv_si_code': self.run_id, 'tinv_si_product_id': product['pid'],
            'tinv_si_warehouse_id': warehouse['pid'], 'tinv_si_quantity': 3,
            'tinv_si_unit_cost': 12.5, 'tinv_si_supplier': self.run_id})
        asset_path = '/api/open/v1/resources/assets/' + assets[0]['pid']
        start = len(self.ledger.requests)
        asset, headers = self.call(asset_path, headers=self.machine)
        need(set(asset) == ASSET_FIELDS and asset['assetCode'] == f'{self.run_id}-0'
             and asset['status'] == 'available', 'fresh asset projection mismatch')
        inventory, inventory_headers = self.call('/api/open/v1/resources/inventory.stock-ins/' + stock['pid'], headers=self.machine)
        need(set(inventory) == {'pid', 'receiptCode', 'productPid', 'warehousePid', 'quantity',
                               'unitCost', 'status', 'supplier', 'receiptDate', 'updatedAt'}
             and inventory['receiptCode'] == self.run_id and inventory['quantity'] == 3
             and inventory['productPid'] == product['pid'] and inventory['warehousePid'] == warehouse['pid'],
             'fresh inventory projection mismatch')
        self.ledger.passed('CANARY-READ', start)
        self.ledger.passed('CANARY-SCOPE', scope_start)
        start = len(self.ledger.requests)
        foreign = self.fixture['foreign']
        foreign_password = private_read(foreign['passwordFile']).strip()
        need(foreign_password, 'empty foreign password')
        self.secrets.append(foreign_password)
        foreign_login = data(self.call('/api/auth/login', method='POST', body={
            'identifier': foreign['identifier'], 'password': foreign_password})[0])
        need(str(foreign_login.get('tenantId')) == str(foreign['tenantId'])
             and foreign_login.get('jwt') and not foreign_login.get('mustChangePassword'),
             'foreign account tenant mismatch')
        foreign_admin = {'Authorization': 'Bearer ' + foreign_login['jwt'],
                         'X-Tenant-Id': str(foreign['tenantId'])}
        foreign_asset = data(self.call('/api/dynamic/tasset_asset/create', method='POST',
            headers=foreign_admin, body={'tasset_as_code': self.run_id + '-foreign',
                                        'tasset_as_name': self.run_id, 'tasset_as_status': 'available'})[0])
        spoof = dict(self.machine, **{'X-Tenant-Id': str(foreign['tenantId'])})
        actual = self.call(asset_path + '?tenantId=' + urllib.parse.quote(str(foreign['tenantId'])), headers=spoof)[0]
        need(actual == asset, 'spoof altered tenant-bound resource')
        self.call('/api/open/v1/resources/assets/' + foreign_asset['pid'], headers=self.machine, expected=(404,))
        self.ledger.passed('CANARY-TENANT', start)
        start = len(self.ledger.requests)
        page = self.call('/api/open/v1/resources/assets?limit=1', headers=self.machine)[0]
        need(len(page.get('items', [])) == 1 and page.get('hasMore') is True
             and page.get('nextCursor'), 'continuation fixture missing')
        cursor = page['nextCursor']
        next_page = self.call('/api/open/v1/resources/assets?limit=1&cursor=' + urllib.parse.quote(cursor, safe=''), headers=self.machine)[0]
        need(len(next_page.get('items', [])) == 1 and page['items'][0]['pid'] != next_page['items'][0]['pid'], 'cursor repeated a record')
        seen = {page['items'][0]['pid'], next_page['items'][0]['pid']}
        # Walk the same real continuation, bounded by the global request budget.
        # Fresh fixtures must be encountered; historical rows alone cannot pass.
        while next_page.get('hasMore'):
            need(next_page.get('nextCursor'), 'hasMore without continuation')
            next_page = self.call('/api/open/v1/resources/assets?limit=100&cursor=' + urllib.parse.quote(next_page['nextCursor'], safe=''), headers=self.machine)[0]
            ids = [item['pid'] for item in next_page['items']]
            need(len(ids) == len(set(ids)) and not seen.intersection(ids), 'cursor duplicated a record')
            seen.update(ids)
        need({item['pid'] for item in assets}.issubset(seen), 'cursor traversal omitted fresh fixtures')
        self.call('/api/open/v1/resources/assets?limit=1&cursor=' + urllib.parse.quote(cursor + 'x', safe=''), headers=self.machine, expected=(400,))
        self.ledger.passed('CANARY-CURSOR', start)
        start = len(self.ledger.requests)
        etag = headers.get('etag', '')
        need(etag.startswith('"') and etag.endswith('"') and not etag.startswith('W/'), 'strong ETag missing')
        body = {'targetPid': assets[0]['pid'], 'input': {'assignee': self.run_id}}
        command = '/api/open/v1/commands/assets.assign:execute'
        command_headers = dict(self.machine, **{'Idempotency-Key': self.run_id + '-assign', 'If-Match': etag})
        self.call(command, method='POST', body=body,
                  headers=dict(self.machine, **{'Idempotency-Key': self.run_id + '-no-match'}), expected=(412,))
        assigned = self.call(command, method='POST', body=body, headers=command_headers)[0]
        need(assigned.get('idempotentReplay') is False and assigned['resource']['status'] == 'in_use'
             and assigned['resource']['assignedTo'] == self.run_id and assigned['etag'] != etag,
             'conditional command did not apply fresh side effect')
        current, current_headers = self.call(asset_path, headers=self.machine)
        need(current == assigned['resource'] and current_headers.get('etag') == assigned['etag'], 'command did not persist')
        inventory_etag = inventory_headers.get('etag', '')
        need(inventory_etag.startswith('"') and inventory_etag.endswith('"'), 'inventory ETag missing')
        confirmed = self.call('/api/open/v1/commands/inventory.stock-ins.confirm:execute', method='POST',
            body={'targetPid': stock['pid'], 'input': {}}, headers=dict(self.machine, **{
                'Idempotency-Key': self.run_id + '-confirm', 'If-Match': inventory_etag}))[0]
        need(confirmed.get('idempotentReplay') is False and confirmed['resource']['status'] == 'confirmed',
             'inventory conditional write did not transition fresh fixture')
        persisted_inventory = self.call('/api/open/v1/resources/inventory.stock-ins/' + stock['pid'], headers=self.machine)[0]
        need(persisted_inventory == confirmed['resource'], 'inventory transition did not persist')
        self.call(command, method='POST', body=body, headers=dict(command_headers, **{'Idempotency-Key': self.run_id + '-stale'}), expected=(412,))
        self.ledger.passed('CANARY-ETAG', start)
        start = len(self.ledger.requests)
        replay = self.call(command, method='POST', body=body, headers=command_headers)[0]
        need(replay.get('idempotentReplay') is True and replay['resource'] == current
             and replay['etag'] == assigned['etag'], 'idempotent replay altered state')
        self.call(command, method='POST', body={'targetPid': assets[0]['pid'], 'input': {'assignee': 'conflict-' + self.run_id}},
                  headers=command_headers, expected=(409,))
        after, after_headers = self.call(asset_path, headers=self.machine)
        need(after == current and after_headers.get('etag') == assigned['etag'], 'conflict changed resource')
        self.ledger.passed('CANARY-IDEMPOTENCY', start)
        if self.fixture.get('includeExternalEvent', False):
            self.external_event(installation['pid'])
        if self.fixture.get('includeWebhook', False):
            self.webhooks(installation['pid'])
        if self.fixture.get('includeExternalEvent', False) and self.fixture.get('includeWebhook', False):
            self.trace(installation['pid'])
        start = len(self.ledger.requests)
        rotated = self.admin_call(prefix + '/credentials/' + credential['credentialPid'] + '/rotate', method='POST', body={'graceMinutes': 5})
        need(rotated['replacedCredentialPid'] == credential['credentialPid']
             and rotated.get('replacedCredentialExpiresAt'), 'rotation grace metadata missing')
        new_machine = self.token(rotated['credential'])
        self.token(credential)
        self.admin_call(prefix + '/credentials/' + credential['credentialPid'], method='DELETE')
        self.token(credential, expected=(401,))
        self.call('/api/open/v1/whoami', headers=self.machine, expected=(401,))
        self.call('/api/open/v1/whoami', headers=new_machine)
        self.ledger.passed('CANARY-CREDENTIAL', start)
        start = len(self.ledger.requests)
        self.admin_call(prefix, method='DELETE')
        self.call('/api/open/v1/whoami', headers=new_machine, expected=(401,))
        self.token(rotated['credential'], expected=(401,))
        self.ledger.passed('CANARY-INSTALLATION', start)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--fixture', required=True)
    parser.add_argument('--out', required=True)
    args = parser.parse_args()
    fixture_bytes = private_read(args.fixture)
    fixture = validate_fixture(json.loads(fixture_bytes))
    identity = Path(fixture['targetIdentityEvidence']).read_bytes()
    ledger = Ledger()
    runner = ProtocolCanary(fixture, ledger)
    # Reserve before any target writes, so an existing output cannot cause a
    # second run to create fixtures and then fail to record its evidence.
    with os.fdopen(os.open(args.out, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'w', encoding='utf-8') as output:
        error = None
        try:
            runner.execute()
        except (ProtocolError, OSError, KeyError, ValueError, TypeError) as failure:
            error = type(failure).__name__
        receipt = ledger.receipt()
        receipt.update(runId=fixture['runId'], origin=fixture['origin'],
                   fixtureSha256=hashlib.sha256(fixture_bytes.encode()).hexdigest(),
                   targetIdentityEvidenceSha256=hashlib.sha256(identity).hexdigest(),
                   runnerSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                   requestBudget=250, requestCount=runner.client.request_count,
                       retry=0, error=error, browserExecuted=0)
        receipt['eventTrace'] = getattr(runner, 'event_trace', None)
        receipt['webhookTrace'] = getattr(runner, 'webhook_trace', None)
        receipt['traceObservation'] = getattr(runner, 'trace_observation', None)
        json.dump(receipt, output, ensure_ascii=False, indent=2)
        output.write('\n')
        output.flush()
        os.fsync(output.fileno())
    print(json.dumps({'status': receipt['status'], 'error': error,
                      'passed': sum(row['verdict'] == 'pass' for row in receipt['contracts']),
                      'total': len(CONTRACTS)}))
    return 1 if error else 2


if __name__ == '__main__':
    sys.exit(main())
