"""Direct-token lifecycle qualification through public production HTTP only."""

import json
import uuid
from open_platform_http import data, ProtocolError

CASES = (
    'fixed-default', 'permanent-explicit', 'secret-no-store', 'metadata-redacted',
    'duplicate-create-conflict', 'bearer-identity', 'live-installation-scope',
    'oauth-compatible', 'oauth-revoked-on-scope-edit', 'machine-management-denied',
    'rotation-cutoff', 'replacement-accepted', 'duplicate-rotation-conflict',
    'sibling-preserved', 'single-revocation', 'installation-disable',
)


def verify_integration_token_report(report):
    if (not isinstance(report, dict) or set(report) != {'profile', 'cases', 'executed', 'passed', 'failed', 'skipped'}
            or report.get('profile') != 'integration-token-http-v1'
            or report.get('cases') != list(CASES)
            or type(report.get('executed')) is not int or report['executed'] != len(CASES)
            or type(report.get('passed')) is not int or report['passed'] != len(CASES)
            or type(report.get('failed')) is not int or report['failed'] != 0
            or type(report.get('skipped')) is not int or report['skipped'] != 0):
        raise ProtocolError('integration-token HTTP qualification missing or incomplete')
    return report


def run_integration_token_probe(request, admin):
    proofs = []

    def require(condition, case):
        if not condition:
            raise ProtocolError('integration-token protocol failed: ' + case)
        proofs.append(case)

    def human(path, *, method='GET', body=None, key=None, expected=(200,)):
        headers = dict(admin)
        if key is not None:
            headers['Idempotency-Key'] = key
        status, result, headers = request(path, method=method, body=body, headers=headers, expected=expected)
        if status == 200 and (not isinstance(result, dict) or str(result.get('code')) != '0'):
            raise ProtocolError('integration-token administration envelope failed')
        return data(result), headers

    def identity(secret, expected=(200,)):
        return request('/api/open/v1/whoami', headers={'Authorization': 'Bearer ' + secret}, expected=expected)

    app, _ = human('/api/open-platform/applications', method='POST',
                   body={'name': 'Direct-token protocol qualification', 'description': 'Owned CI fixture'})
    installation, _ = human('/api/open-platform/applications/' + app['pid'] + '/installations', method='POST',
                            body={'environment': 'development', 'scopes': ['openapi.profile.read'], 'rateLimitPerMinute': 10000})
    root = '/api/open-platform/installations/' + installation['pid']
    path = root + '/integration-tokens'
    fixed_key = 'fixed-' + uuid.uuid4().hex
    fixed, headers = human(path, method='POST', key=fixed_key, body={'name': 'Expiring integration'})
    require(fixed['metadata']['expirationMode'] == 'fixed' and fixed['metadata']['expiresAt'] is not None, 'fixed-default')
    permanent, permanent_headers = human(path, method='POST', key='never-' + uuid.uuid4().hex,
                                        body={'name': 'Permanent integration', 'expirationMode': 'never'})
    require(permanent['metadata']['expirationMode'] == 'never' and permanent['metadata']['expiresAt'] is None, 'permanent-explicit')
    require(all(any(k.lower() == 'cache-control' and 'no-store' in v for k, v in h.items())
                for h in (headers, permanent_headers)), 'secret-no-store')
    rows, _ = human(path)
    serialized = json.dumps(rows)
    require(len(rows) == 2 and all(value['token'] not in serialized for value in (fixed, permanent))
            and 'tokenHash' not in serialized and 'token_hash' not in serialized, 'metadata-redacted')
    human(path, method='POST', key=fixed_key, body={'name': 'Expiring integration'}, expected=(409,))
    rows, _ = human(path)
    require(len(rows) == 2, 'duplicate-create-conflict')
    _, current, _ = identity(permanent['token'])
    require(current.get('applicationPid') == app['pid'] == permanent['appId'] == fixed['appId']
            and current.get('installationPid') == installation['pid'], 'bearer-identity')
    credential, _ = human(root + '/credentials', method='POST', body={})
    _, oauth, _ = request('/oauth2/token', method='POST', form={'grant_type': 'client_credentials',
        'client_id': credential['clientId'], 'client_secret': credential['clientSecret'], 'scope': 'openapi.profile.read'})
    _, old_oauth_identity, _ = identity(oauth['access_token'])
    human(root + '/scopes', method='PUT', body={'scopes': ['openapi.profile.read', 'openapi.events.read']})
    _, changed, _ = identity(permanent['token'])
    require(set(changed.get('scopes', [])) == {'openapi.profile.read', 'openapi.events.read'}, 'live-installation-scope')
    require(old_oauth_identity.get('installationPid') == installation['pid'], 'oauth-compatible')
    identity(oauth['access_token'], expected=(401,))
    proofs.append('oauth-revoked-on-scope-edit')
    request(path, headers={'Authorization': 'Bearer ' + permanent['token']}, expected=(401,))
    proofs.append('machine-management-denied')
    rotate_key = 'rotate-' + uuid.uuid4().hex
    rotation, headers = human(path + '/' + permanent['metadata']['pid'] + '/rotate', method='POST', key=rotate_key,
                             body={'expirationMode': 'never', 'graceMinutes': 0})
    identity(permanent['token'], expected=(401,))
    require(bool(rotation.get('replacedTokenAcceptUntil')), 'rotation-cutoff')
    replacement = rotation['credential']
    _, current, _ = identity(replacement['token'])
    require(current.get('applicationPid') == replacement['appId'] == app['pid']
            and any(k.lower() == 'cache-control' and 'no-store' in v for k, v in headers.items()), 'replacement-accepted')
    human(path + '/' + permanent['metadata']['pid'] + '/rotate', method='POST', key=rotate_key,
          body={'expirationMode': 'never', 'graceMinutes': 0}, expected=(409,))
    rows, _ = human(path)
    require(len(rows) == 3, 'duplicate-rotation-conflict')
    identity(fixed['token'])
    proofs.append('sibling-preserved')
    human(path + '/' + replacement['metadata']['pid'], method='DELETE')
    human(path + '/' + replacement['metadata']['pid'], method='DELETE')
    identity(replacement['token'], expected=(401,))
    identity(fixed['token'])
    proofs.append('single-revocation')
    human(root, method='DELETE')
    identity(fixed['token'], expected=(401,))
    proofs.append('installation-disable')
    return verify_integration_token_report({'profile': 'integration-token-http-v1', 'cases': proofs,
        'executed': len(proofs), 'passed': len(proofs), 'failed': 0, 'skipped': 0})
