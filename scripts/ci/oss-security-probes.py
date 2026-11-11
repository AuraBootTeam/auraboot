"""Real HTTP security probes for the attempt-owned installed native candidate.

No forged JWTs, SQL fixture insertion, mock servers or shared runtime changes.
The caller bootstraps a separate MULTI database through the public setup API.
"""
import hashlib
import json
import secrets
import urllib.error
import urllib.parse
import urllib.request


def verify_sms_mobile_window(request, check, require, delivery):
    """Probe the shipped controller without requesting or sending an SMS code."""
    mobile = '199' + str(secrets.randbelow(100000000)).zfill(8)
    limited = []
    for index in range(11):
        value = request('/api/auth/login/sms', 'POST', {
            'mobile': mobile, 'code': 'invalid-never-issued',
            'email': f'sms-decoy-{secrets.token_hex(8)}@example.invalid',
        }, {'X-Forwarded-For': f'198.51.100.{index + 1}',
            'X-Real-IP': f'203.0.113.{index + 1}'})
        require(value.get('_httpStatus', 200) < 500 and str(value.get('code')) != '0'
                and not (value.get('data') or {}).get('jwt'),
                'invalid SMS login succeeded, issued JWT or errored')
        message = str(value.get('message', ''))
        is_limited = 'Too many login attempts' in message
        require(is_limited or 'Invalid or expired verification code' in message,
                'SMS positive control did not reach the real OTP verifier')
        limited.append(is_limited)
    require(limited == [False] * 10 + [True],
            'SMS mobile budget bypassed by changing email/forwarding headers')
    check('security-sms-mobile-window-' + delivery, attempts=11, decoyEmails=11,
          invalidOtpReachedVerifier=10, rejectedAttempt=11, issuedJwt=False,
          smsSendEndpointCalled=False)


def verify(base, request, login, admin, password, check, require, publish_tenant, web_base):
    def call(token, path, method='GET', body=None, extra=None):
        headers = dict(extra or {})
        if token:
            headers['Authorization'] = 'Bearer ' + token
        return request(path, method, body, headers)

    def ok(token, path, method='GET', body=None):
        value = call(token, path, method, body)
        require(value.get('_httpStatus', 200) == 200 and str(value.get('code')) == '0',
                f'security positive control failed: {method} {path}')
        return value.get('data')

    def denied(token, path, method='GET', body=None, statuses=(401, 403, 404), extra=None):
        value = call(token, path, method, body, extra)
        status = value.get('_httpStatus', 200)
        require(status in statuses, f'security denial missing: {method} {path} HTTP {status}')
        return status

    def raw(token, path, body=None, content_type=None):
        headers = {'Authorization': 'Bearer ' + token} if token else {}
        if content_type:
            headers['Content-Type'] = content_type
        req = urllib.request.Request(base + path, data=body, headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=60) as response:
                return response.status, response.read()
        except urllib.error.HTTPError as error:
            return error.code, error.read()

    def upload(token, name='acceptance.txt'):
        boundary = 'security-' + secrets.token_hex(12)
        content = ('Private acceptance material ' + secrets.token_hex(12)).encode()
        body = ('--' + boundary + '\r\nContent-Disposition: form-data; name="file"; filename="'
                + name + '"\r\nContent-Type: text/plain\r\n\r\n').encode()
        body += content + ('\r\n--' + boundary + '--\r\n').encode()
        status, response = raw(token, '/api/file/upload', body, 'multipart/form-data; boundary=' + boundary)
        value = json.loads(response)
        require(status == 200 and str(value.get('code')) == '0', 'security attachment upload failed')
        return value['data']['fileId'], content

    permissions = {}

    def collect(node):
        permissions[node['code']] = node['id']
        for child in node.get('children') or []:
            collect(child)

    for node in ok(admin, '/api/permissions/tree'):
        collect(node)
    model = 'org_department'
    path = '/api/dynamic/' + model
    writer_codes = ['model.' + model + '.' + action for action in ['read', 'create', 'update', 'delete']]
    writer_codes += ['org.hr.manage', 'meta.command.execute', 'sys.file.upload', 'sys.file.read', 'sys.file.delete']
    reader_codes = ['model.' + model + '.read', 'sys.file.upload', 'sys.file.read', 'sys.file.delete']
    for role_code, codes in [('security_writer', writer_codes), ('security_reader', reader_codes)]:
        require(all(code in permissions for code in codes), 'required security permission absent from shipped inventory')
        role = ok(admin, '/api/roles', 'POST', {'code': role_code, 'name': role_code,
            'type': 'custom', 'status': 'active', 'scopeType': 'tenant', 'defaultDataScopeType': 'all'})
        ok(admin, '/api/permissions/matrix/' + role['pid'] + '/batch', 'PUT',
           [{'permissionId': permissions[code], 'granted': True} for code in codes])

    actors = {}
    for name, role in [('alice', 'security_writer'), ('peer', 'security_writer'),
                       ('reader', 'security_reader'), ('founder', 'security_writer')]:
        email = 'security-' + name + '@auraboot.local'
        provisioned = ok(admin, '/api/admin/users', 'POST', {'email': email, 'displayName': name,
            'initialPassword': password, 'roleCodes': [role], 'sendInviteEmail': False})
        actors[name] = {'token': login(email, password), 'userId': str(provisioned['userId'])}
    alice, peer, reader, founder = (actors[name]['token'] for name in ['alice', 'peer', 'reader', 'founder'])
    a_info = ok(alice, '/api/auth/me')
    require({role['code'] for role in a_info['permissions']['roles']} == {'security_writer'},
            'tenant A negative actor unexpectedly has additional roles')
    require('*' not in a_info['permissions']['permissionCodes'], 'negative actor has wildcard permissions')

    b = ok(founder, '/api/tenant-selection/process', 'POST', {'action': 'create',
        'clientRequestId': 'security-tenant-' + secrets.token_hex(12),
        'tenantName': 'security-b-' + secrets.token_hex(6), 'displayName': 'Security tenant B'})
    bob = b['jwt']
    publish_tenant(bob)
    b_info = ok(bob, '/api/auth/me')
    require(str(a_info['user']['tenantId']) != str(b_info['user']['tenantId']), 'actors did not select distinct tenants')
    require(not any(role['code'] in {'super_admin', 'platform_admin'} for role in b_info['permissions']['roles']),
            'tenant B actor unexpectedly has platform administration')
    check('security-real-actor-contexts', tenantA=a_info['user']['tenantId'],
          tenantB=b_info['user']['tenantId'], tenantAWriterRoles=['security_writer'])

    def command(token, command_code, values, pid=None):
        body = {'payload': values, 'clientRequestId': 'security-' + secrets.token_hex(12),
                'operationType': 'UPDATE' if pid else 'CREATE'}
        if pid:
            body['targetRecordPid'] = pid
        return ok(token, '/api/meta/commands/execute/' + command_code, 'POST', body)

    def create(token, name):
        result = command(token, 'org:create_department', {'org_dept_name': name, 'org_dept_status': 'active'})
        require(result['phaseReached'] == 'completed', 'security create command did not complete')
        return result['data']['recordPid']

    name_a, name_b = 'Private-A-' + secrets.token_hex(8), 'Private-B-' + secrets.token_hex(8)
    pid_a, pid_b = create(alice, name_a), create(bob, name_b)
    for token, own_pid, own_name, foreign_pid, foreign_name in [
            (alice, pid_a, name_a, pid_b, name_b), (bob, pid_b, name_b, pid_a, name_a)]:
        require(ok(token, path + '/' + own_pid)['org_dept_name'] == own_name, 'own tenant record missing')
        ok(token, path + '/' + own_pid, 'PUT', {'org_dept_name': own_name})
        disposable = create(token, 'Own-delete-' + secrets.token_hex(8))
        ok(token, path + '/' + disposable, 'DELETE')
        removed = call(token, path + '/' + disposable)
        require(removed.get('_httpStatus') in (403, 404) or
                (str(removed.get('code')) == '0' and removed.get('data') is None), 'own delete control did not remove record')
        listing = ok(token, path + '/list?page=1&size=100')
        serialized = json.dumps(listing)
        require(own_pid in serialized and foreign_pid not in serialized and foreign_name not in serialized,
                'tenant list isolation failed')
        foreign = call(token, path + '/' + foreign_pid)
        require(foreign.get('_httpStatus') in (403, 404)
                or (str(foreign.get('code')) == '0' and foreign.get('data') is None),
                'foreign tenant record was readable or failed unexpectedly')
        for method, body in [('PUT', {'org_dept_name': 'Cross-tenant mutation'}), ('DELETE', None)]:
            value = call(token, path + '/' + foreign_pid, method, body)
            require(value.get('_httpStatus') in (400, 403, 404) or (
                value.get('_httpStatus', 200) == 200 and str(value.get('code')) != '0'),
                'cross-tenant write/delete did not fail closed')
        result = command(token, 'org:update_department', {'org_dept_name': own_name + '-updated'}, own_pid)
        require(result['phaseReached'] == 'completed', 'own update command failed')
        cross = call(token, '/api/meta/commands/execute/org:update_department', 'POST', {
            'payload': {'org_dept_name': 'Cross-command'}, 'operationType': 'UPDATE',
            'targetRecordPid': foreign_pid, 'clientRequestId': 'cross-' + secrets.token_hex(12)})
        require(cross.get('_httpStatus') in (400, 403, 404) or (
            cross.get('_httpStatus', 200) == 200 and (str(cross.get('code')) != '0'
            or cross.get('data', {}).get('phaseReached') != 'completed')), 'cross-tenant command completed')
        export = ok(token, path + '/export', 'POST', {'scope': 'filtered', 'format': 'csv',
            'fields': ['org_dept_name']})
        url = export['downloadUrl']
        status, content = raw(token, url)
        require(status == 200 and (own_name + '-updated').encode() in content
                and foreign_name.encode() not in content, 'export tenant scope differs')
        other = bob if token == alice else alice
        require(raw(other, url)[0] in (403, 404), 'foreign tenant export artifact readable')
        if token == alice:
            require(raw(peer, url)[0] in (403, 404), 'same-tenant foreign export artifact readable')
    require(ok(alice, path + '/' + pid_a)['org_dept_name'] == name_a + '-updated', 'tenant A changed by foreign write')
    require(ok(bob, path + '/' + pid_b)['org_dept_name'] == name_b + '-updated', 'tenant B changed by foreign write')
    check('security-cross-tenant-read-list-write-delete-command-export', directions=2,
          knownLiveRecords=2, ownReadAndCommandAndExportControls=True)

    ok(reader, path + '/' + pid_a)
    denied(reader, path + '/' + pid_a, 'PUT', {'org_dept_name': 'Forbidden'})
    denied(reader, path + '/' + pid_a, 'DELETE')
    denied(reader, '/api/meta/commands/execute/org:update_department', 'POST', {
        'payload': {'org_dept_name': 'Forbidden'}, 'targetRecordPid': pid_a, 'operationType': 'UPDATE'})
    for headers in [{'X-User-Role': 'super_admin'}, {'X-Tenant-Id': b_info['user']['tenantId']},
                    {'X-Forwarded-For': '127.0.0.1'}, {'X-Test-Spoof-User-Id': '1'}]:
        denied(reader, path + '/' + pid_a, 'PUT', {'org_dept_name': 'Spoofed'}, extra=headers)
    require(ok(alice, path + '/' + pid_a)['org_dept_name'] == name_a + '-updated', 'denied role changed record')
    check('security-readonly-and-header-spoof-denials', ownReadControl=True, writesPreserved=True)

    file_a, content_a = upload(alice)
    file_peer, content_peer = upload(peer)
    file_b, content_b = upload(bob)
    for token, file_id, content in [(alice, file_a, content_a), (peer, file_peer, content_peer), (bob, file_b, content_b)]:
        require(raw(token, '/api/file/download/' + file_id) == (200, content), 'own attachment bytes differ')
        ok(token, '/api/file/' + file_id)
    for token, file_id, expected in [(peer, file_a, (403,)), (alice, file_peer, (403,)),
                                      (bob, file_a, (403, 404)), (alice, file_b, (403, 404)),
                                      (None, file_a, (401, 403))]:
        denied(token, '/api/file/' + file_id, statuses=expected)
        require(raw(token, '/api/file/download/' + file_id)[0] in expected, 'attachment download ownership missing')
        deletion = call(token, '/api/file/' + file_id, 'DELETE')
        if deletion.get('_httpStatus') == 422 and token is not None:
            # FileServiceImpl deliberately raises BUSINESS_ERROR for owner
            # mismatch / tenant-scoped absence. Accept only that exact domain
            # refusal, not an arbitrary validation failure or server exception.
            context = deletion.get('context')
            reason = context.get('detail') if isinstance(context, dict) else context
            expected_reason = ("You don't have permission to delete this file"
                               if token in (alice, peer) and file_id in (file_a, file_peer)
                               else 'File not found: ' + file_id)
            require(str(deletion.get('code')) == '40000' and reason == expected_reason,
                    'attachment deletion returned an unrelated domain failure')
        else:
            require(deletion.get('_httpStatus', 200) in expected,
                    'attachment deletion ownership/anonymous denial missing')
    # Prove the deletion endpoint is usable for an owned object; a blanket
    # failure, broken storage transaction or wrong endpoint cannot satisfy this.
    disposable_file, disposable_content = upload(alice, 'own-delete-control.txt')
    require(raw(alice, '/api/file/download/' + disposable_file) == (200, disposable_content),
            'own delete control upload bytes differ')
    require(ok(alice, '/api/file/' + disposable_file, 'DELETE') is True,
            'own attachment deletion failed')
    require(raw(alice, '/api/file/download/' + disposable_file)[0] == 404,
            'owned deleted attachment remains downloadable')
    for token, file_id, content in [(alice, file_a, content_a), (peer, file_peer, content_peer), (bob, file_b, content_b)]:
        require(raw(token, '/api/file/download/' + file_id) == (200, content), 'denied attachment operation changed bytes')
    # Exercise path-like IDs against the public HTTP surface, not the storage helper alone.
    for suffix in ['%2e%2e%2f%2e%2e%2fetc%2fpasswd', '%2e%2e%5c%2e%2e%5cetc%5cpasswd']:
        status, content = raw(alice, '/api/file/download/' + suffix)
        require(status in (400, 403, 404) and b'root:x:' not in content, 'path traversal was not rejected')
    check('security-attachment-owner-tenant-anonymous-traversal', ownUploads=3, foreignBytesPreserved=True,
          contentSha256=hashlib.sha256(content_a).hexdigest())

    status = ok(None, '/api/bootstrap/status')
    require(status['initialized'] is True and status['mode'] == 'multi', 'public bootstrap status differs')
    setup = call(None, '/api/bootstrap/setup', 'POST', {'companyName': 'Overwrite',
        'adminEmail': 'attacker@example.invalid', 'adminPassword': password, 'systemMode': 'multi'})
    require(str(setup.get('code')) != '0', 'initialized bootstrap was overwritten anonymously')
    for endpoint in ['/api/auth/me', '/api/meta/models', '/api/roles', '/api/admin/users',
                     path + '/list', path + '/' + pid_a, '/api/file/list']:
        denied(None, endpoint)
    require(ok(alice, path + '/' + pid_a)['org_dept_name'] == name_a + '-updated', 'anonymous probes changed business state')
    # Exercise the maintained documentation adapter and its actual packaged assets.
    api_status, api_bytes = raw(admin, '/v3/api-docs/internal-web-admin')
    require(api_status == 200, 'authenticated OpenAPI endpoint is unavailable')
    api_document = json.loads(api_bytes)
    require(str(api_document.get('openapi', '')).startswith('3.')
            and '/api/auth/login' in api_document.get('paths', {}),
            'OpenAPI output lost the real authentication contract')
    ui_status, ui_bytes = raw(admin, '/swagger-ui/index.html')
    require(ui_status == 200 and b'swagger-ui-bundle.js' in ui_bytes,
            'packaged Swagger UI is unavailable or returned a non-UI response')
    swagger_assets = []
    for asset, marker in [('swagger-ui.css', b'.swagger-ui'), ('swagger-ui-bundle.js', b'SwaggerUIBundle')]:
        asset_status, asset_bytes = raw(admin, '/swagger-ui/' + asset)
        require(asset_status == 200 and marker in asset_bytes, 'packaged Swagger asset is unresolved')
        swagger_assets.append({'asset': asset, 'bytes': len(asset_bytes),
                               'sha256': hashlib.sha256(asset_bytes).hexdigest()})
    check('security-anonymous-bootstrap-and-protected-defaults', publicStatus=True, protectedEndpoints=7,
          openApiVersion=api_document['openapi'], swaggerAssets=swagger_assets)

    def login_attempt(identifier, index):
        value = call(None, '/api/auth/login', 'POST', {'email': identifier, 'password': 'Deliberately-Invalid-Only'},
                     {'X-Forwarded-For': f'198.51.100.{index % 250 + 1}',
                      'X-Real-IP': f'203.0.113.{index % 250 + 1}'})
        require(value.get('_httpStatus', 200) < 500 and str(value.get('code')) != '0'
                and not (value.get('data') or {}).get('jwt'), 'invalid login unexpectedly succeeded or errored')
        return 'Too many login attempts' in str(value.get('message'))

    class KeepRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, newurl):
            return None

    def bff_login():
        data = urllib.parse.urlencode({'identifier': 'security-alice@auraboot.local', 'password': password,
            'channelCode': 'email_password', 'redirectTo': '/home'}).encode()
        req = urllib.request.Request(web_base + '/login', data=data,
            headers={'Content-Type': 'application/x-www-form-urlencoded'})
        try:
            with urllib.request.build_opener(KeepRedirect()).open(req, timeout=60) as response:
                raise RuntimeError('BFF login did not return its defined redirect')
        except urllib.error.HTTPError as response:
            with response:
                return response.code, response.headers.get('Location', ''), bool(response.headers.get_all('Set-Cookie'))

    bff_before = bff_login()
    require(bff_before[0] == 302 and bff_before[2] and 'invalidCredentials' not in bff_before[1],
            'BFF correct-credential positive control failed')

    verify_sms_mobile_window(request, check, require, 'native')
    identifier = 'limit-' + secrets.token_hex(8) + '@example.invalid'
    attempts = [login_attempt(identifier if index % 2 else identifier.upper(), index) for index in range(11)]
    require(attempts == [False] * 10 + [True], 'real login identifier window/normalization differs')
    # The identifier changes each time, as do spoofed IP headers. The actual
    # servlet peer must still hit the shared client window within this burst.
    client_limits = [login_attempt('peer-limit-' + secrets.token_hex(8) + '@example.invalid', index)
                     for index in range(61)]
    require(any(client_limits) and client_limits[-1], 'spoofed forwarding headers bypassed client limit')
    bff_after = bff_login()
    require(bff_after[0] == 303 and 'invalidCredentials' in bff_after[1] and not bff_after[2],
            'BFF bypassed the backend login budget or issued a cookie after rejection')
    require(ok(alice, path + '/' + pid_a)['org_dept_name'] == name_a + '-updated',
            'login burst affected authenticated business operations')
    check('security-real-login-windows-and-forwarded-header-spoof', identifierAttempts=11,
          uniqueIdentifierClientAttempts=61, authenticatedBusinessContinues=True,
          correctCredentialBffControl=True, throttledBffIssuedNoCookie=True)
