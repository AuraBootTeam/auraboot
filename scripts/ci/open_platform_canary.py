#!/usr/bin/env python3
"""Deployed-target protocol slice. Never seeds or installs plugins at import/run.

The full twelve-contract denominator is retained. This slice deliberately cannot
certify the event, webhook or cross-system trace contracts and exits nonzero.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import sys
import urllib.parse

from open_platform_http import Client, ProtocolError, data

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


class ProtocolCanary:
    def __init__(self, fixture, ledger):
        self.fixture = validate_fixture(fixture)
        self.ledger = ledger
        self.client = Client(fixture['origin'], max_requests=150, timeout=30)
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
                   requestBudget=150, requestCount=runner.client.request_count,
                       retry=0, error=error, browserExecuted=0)
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
