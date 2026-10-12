"""Mandatory authentication-denial HTTP and owned-PostgreSQL evidence."""
import hashlib
from datetime import datetime
import json
import re
import subprocess
import uuid

from open_platform_http import ProtocolError

CASES = [("bearer-missing", 401, "invalid_token", False, False),
         ("bearer-unknown", 401, "invalid_token", False, False),
         ("bearer-scope", 403, "insufficient_scope", True, True),
         ("bearer-expired", 401, "invalid_token", True, True),
         ("oauth-unknown", 401, "invalid_client", False, False),
         ("oauth-wrong", 401, "invalid_client", False, False),
         ("oauth-basic", 401, "invalid_client", False, False),
         ("oauth-scope", 400, "invalid_scope", True, False)]


def require(value, message):
    if not value:
        raise ProtocolError(message)


class PsqlAuditReader:
    def __init__(self, command, database, *, allow_fixture_clock=False):
        require(isinstance(command, list) and command and all(isinstance(v, str) and v for v in command),
                "authentication SQL command missing")
        require(isinstance(database, str) and database, "authentication database identity missing")
        require(type(allow_fixture_clock) is bool, "fixture clock authorization invalid")
        self.command, self.database = command, database
        self.allow_fixture_clock = allow_fixture_clock

    def read(self, installation, credential, token, ids=()):
        require(isinstance(credential, dict) and isinstance(credential.get('clientSecret'), str) and credential['clientSecret']
                and isinstance(token, str) and token, 'authentication credential fixture missing')
        def literal(value):
            require(isinstance(value, str) and value, "authentication SQL value missing")
            return "'" + value.replace("'", "''") + "'"
        for value in [installation, credential['credentialPid'], *ids]:
            require(re.fullmatch(r'[A-Za-z0-9_.:-]{1,128}', value), "authentication identity invalid")
        token_hash = hashlib.sha256(token.encode()).hexdigest()
        wanted = ','.join(literal(value) for value in ids) or "NULL"
        secrets = [credential['clientSecret'], token]
        safe = ' AND '.join(f"strpos(to_jsonb(a)::text,{literal(value)})=0" for value in secrets)
        sql = f"""WITH i AS (SELECT * FROM ab_application_installation WHERE pid={literal(installation)})
        SELECT json_build_object('database',current_database(),'installationFound',(SELECT COUNT(*) FROM i),
          'tokenCount',(SELECT COUNT(*) FROM ab_application_access_token t JOIN i ON i.id=t.installation_id),
          'credentialLastUsed',(SELECT c.last_used_at FROM ab_application_credential c JOIN i ON i.id=c.installation_id WHERE c.pid={literal(credential['credentialPid'])}),
          'tokenLastUsed',(SELECT t.last_used_at FROM ab_application_access_token t JOIN i ON i.id=t.installation_id WHERE t.token_hash={literal(token_hash)}),
          'quotaCount',(SELECT COALESCE(SUM(w.request_count),0) FROM ab_open_api_rate_window w JOIN i ON i.id=w.installation_id),
          'audits',COALESCE((SELECT json_agg(json_build_object('requestId',a.request_id,'method',a.http_method,
            'path',a.request_path,'status',a.response_status,'durationMs',a.duration_ms,'occurredAt',a.occurred_at,
            'applicationPid',a.application_pid,'installationPid',a.installation_pid,'businessDetails',a.business_details,
            'tokenPresent',a.token_pid IS NOT NULL,'tenantMatches',COALESCE(a.tenant_id=(SELECT tenant_id FROM i),false),
            'anonymous',a.tenant_id IS NULL AND a.application_pid IS NULL AND a.installation_pid IS NULL AND a.token_pid IS NULL,
            'secretFree',({safe}))) FROM ab_open_api_call_audit a WHERE a.request_id IN ({wanted})), '[]'::json));"""
        return self.query(sql)

    def token_clock(self, installation, token, original=None):
        """Construct expiry only for an explicitly authorized, isolated token fixture."""
        require(self.allow_fixture_clock and (self.database == 'open_platform_ci'
                or re.fullmatch(r'auraboot_[0-9]{1,4}', self.database)), 'owned fixture clock authorization required')
        require(isinstance(installation, str) and re.fullmatch(r'[A-Za-z0-9_.:-]{1,128}', installation) and isinstance(token, str) and token,
                'fixture clock identity invalid')
        token_hash = hashlib.sha256(token.encode()).hexdigest()
        literal = lambda value: "'" + value.replace("'", "''") + "'"
        if original is None:
            predicate = 't.expires_at > NOW()'
            target = "NOW() - INTERVAL '1 minute'"
        else:
            require(isinstance(original, dict) and original.get('database') == self.database
                    and original.get('installationFound') == 1 and original.get('tokenFound') == 1,
                    'fixture clock restoration identity invalid')
            parse_time(original.get('originalExpiresAt')); parse_time(original.get('effectiveExpiresAt'))
            predicate = 't.expires_at = ' + literal(original['effectiveExpiresAt']) + '::timestamptz'
            target = literal(original['originalExpiresAt']) + '::timestamptz'
        sql = f"""WITH i AS (SELECT id FROM ab_application_installation WHERE pid={literal(installation)}),
        prior AS MATERIALIZED (SELECT t.id,t.expires_at FROM ab_application_access_token t JOIN i ON i.id=t.installation_id
          WHERE current_database()={literal(self.database)} AND t.token_hash={literal(token_hash)}
            AND t.revoked_at IS NULL AND {predicate} FOR UPDATE OF t),
        changed AS (UPDATE ab_application_access_token t SET expires_at={target} FROM prior WHERE t.id=prior.id AND (SELECT COUNT(*) FROM prior)=1
          RETURNING t.expires_at)
        SELECT json_build_object('database',current_database(),'installationFound',(SELECT COUNT(*) FROM i),
          'tokenFound',(SELECT COUNT(*) FROM changed),'originalExpiresAt',(SELECT expires_at FROM prior),
          'effectiveExpiresAt',(SELECT expires_at FROM changed),'observedAt',NOW());"""
        state = self.query(sql)
        require(state.get('tokenFound') == 1, 'fixture clock must change exactly the owned token')
        return state

    def query(self, sql):
        try:
            result = subprocess.run(self.command, input=sql, capture_output=True, text=True, timeout=20)
            require(result.returncode == 0 and len(result.stdout) < 65536, "authentication SQL read failed")
            state = json.loads(result.stdout)
        except (OSError, subprocess.TimeoutExpired, ValueError):
            raise ProtocolError("authentication SQL evidence unavailable") from None
        require(state.get('database') == self.database and state.get('installationFound') == 1,
                "authentication SQL runtime identity mismatch")
        return state



def parse_time(value):
    require(isinstance(value, str) and len(value) <= 64, 'fixture timestamp missing')
    try:
        result = datetime.fromisoformat(value.replace('Z', '+00:00'))
    except ValueError:
        raise ProtocolError('fixture timestamp invalid') from None
    require(result.tzinfo is not None, 'fixture timestamp timezone missing')
    return result


def verify_expiry(expiry, database, request_id):
    require(isinstance(expiry, dict) and set(expiry) == {'expired','restored','requestId'}
            and expiry.get('requestId') == request_id, 'token expiry proof missing')
    expired, restored = expiry.get('expired'), expiry.get('restored')
    for state in [expired, restored]:
        require(isinstance(state, dict) and set(state) == {'database','installationFound','tokenFound','originalExpiresAt','effectiveExpiresAt','observedAt'}
                and state.get('database') == database and type(state.get('installationFound')) is int and state['installationFound'] == 1
                and type(state.get('tokenFound')) is int and state['tokenFound'] == 1, 'token expiry fixture identity invalid')
    original = parse_time(expired['originalExpiresAt']); deadline = parse_time(expired['effectiveExpiresAt'])
    observed = parse_time(expired['observedAt']); recovery = parse_time(restored['observedAt'])
    require(deadline < observed < original and recovery >= observed and original > recovery
            and restored['originalExpiresAt'] == expired['effectiveExpiresAt']
            and restored['effectiveExpiresAt'] == expired['originalExpiresAt'], 'token expiry or exact restoration unproven')


def verify(proof, application, installation, resource_path):
    require(isinstance(proof, dict) and set(proof) == {'applicationPid','installationPid','before','afterDenied','afterAllowed','requests','normalStatus','tokenExpiry','resourcePath'} and proof.get('applicationPid') == application
            and proof.get('installationPid') == installation, "authentication proof identity missing")
    require(isinstance(resource_path, str) and re.fullmatch(r'/api/open/v1/resources/[a-z][a-z0-9.-]{1,99}', resource_path)
            and proof.get('resourcePath') == resource_path, 'authentication resource path missing')
    before, denied, allowed = [proof.get(k) for k in ['before', 'afterDenied', 'afterAllowed']]
    for state in [before, denied, allowed]:
        require(isinstance(state, dict) and set(state) == {'database','installationFound','tokenCount','credentialLastUsed','tokenLastUsed','quotaCount','audits'}
                and type(state.get('installationFound')) is int and state['installationFound'] == 1
                and isinstance(state.get('database'), str) and state['database']
                and type(state.get('tokenCount')) is int and state['tokenCount'] > 0
                and type(state.get('quotaCount')) is int and state['quotaCount'] >= 0,
                "authentication state missing")
    require(isinstance(before.get('credentialLastUsed'), str) and before['credentialLastUsed'], 'credential usage baseline missing')
    require(before['database'] == denied['database'] == allowed['database'], "authentication database drift")
    for key in ['tokenCount', 'credentialLastUsed', 'tokenLastUsed', 'quotaCount']:
        require(before.get(key) == denied.get(key), "authentication denial changed credential/token/quota")
    require(allowed['tokenCount'] == before['tokenCount']
            and allowed.get('credentialLastUsed') == before.get('credentialLastUsed')
            and allowed['quotaCount'] == before['quotaCount'] + 1
            and isinstance(allowed.get('tokenLastUsed'), str)
            and allowed['tokenLastUsed'] != denied.get('tokenLastUsed'), "normal call did not resume token/quota usage")
    rows = proof.get('requests')
    audits = denied.get('audits')
    require(isinstance(rows, list) and len(rows) == len(CASES)
            and isinstance(audits, list) and len(audits) == len(CASES), "authentication proof denominator invalid")
    require(all(isinstance(row, dict) for row in rows + audits), 'authentication evidence rows invalid')
    ids = [r.get('requestId') for r in rows]
    require(len(set(ids)) == len(CASES) and all(isinstance(v, str) and re.fullmatch(r'[A-Za-z0-9_.:-]{1,128}', v) for v in ids),
            "authentication request identity invalid")
    indexed = {a.get('requestId'): a for a in audits}
    require(set(indexed) == set(ids), "authentication audit missing or duplicated")
    for row, (name, status, code, known, token) in zip(rows, CASES):
        path = '/oauth2/token' if name.startswith('oauth') else resource_path if name == 'bearer-expired' else '/api/open/v1/whoami'
        method = 'POST' if name.startswith('oauth') else 'GET'
        expected_body = {'error': code} if method == 'POST' else {'success': False, 'code': code, 'message': code}
        require(set(row) == {'id','status','body','requestId','callerIdReplaced','visibleAudits'} and row.get('id') == name and row.get('status') == status and row.get('body') == expected_body
                and row.get('callerIdReplaced') is (method == 'POST'), "authentication response contract invalid")
        audit = indexed[row['requestId']]
        require(set(audit) == {'requestId','method','path','status','durationMs','occurredAt','applicationPid','installationPid','businessDetails','tokenPresent','tenantMatches','anonymous','secretFree'} and audit.get('method') == method and audit.get('path') == path and audit.get('status') == status
                and type(audit.get('durationMs')) is int and audit['durationMs'] >= 0
                and isinstance(audit.get('occurredAt'), str) and audit['occurredAt']
                and audit.get('secretFree') is True and audit.get('tokenPresent') is token
                and audit.get('tenantMatches') is known and audit.get('anonymous') is (not known)
                and audit.get('applicationPid') == (application if known else None)
                and audit.get('installationPid') == (installation if known else None)
                and audit.get('businessDetails') == {'actorKind': 'unauthenticated', 'phase': 'authentication', 'code': code},
                "authentication audit ownership/details invalid")
        visible = row.get('visibleAudits')
        require(isinstance(visible, list) and len(visible) == (1 if known else 0), "anonymous audit leaked into tenant installation")
        if known:
            view = visible[0]
            require(isinstance(view, dict) and view.get('requestId') == row['requestId'] and view.get('actorKind') == 'unauthenticated'
                    and view.get('applicationPid') == application and view.get('installationPid') == installation
                    and view.get('status') == status and view.get('method') == method and view.get('path') == path
                    and all(view.get(k) is None for k in ['capability', 'resource', 'operation', 'recordPid', 'idempotentReplay']),
                    "authentication management view forged business authority")
    expired_request = next(row for row in rows if row['id'] == 'bearer-expired')
    verify_expiry(proof.get('tokenExpiry'), before['database'], expired_request['requestId'])
    require(proof.get('normalStatus') == 200, "authentication normal recovery missing")
    return proof


def run(request, human, report, credential, token, path, reader):
    require(reader is not None, "owned authentication audit reader is required")
    installation = report['installationPid']
    proof = {'applicationPid': report['applicationPid'], 'installationPid': installation,
             'before': reader.read(installation, credential, token), 'requests': [], 'resourcePath': path}
    for name, status, code, known, token_present in CASES:
        oauth = name.startswith('oauth')
        supplied = credential['clientSecret'] if oauth else 'release-auth-' + uuid.uuid4().hex
        headers = {'X-Request-Id': supplied, 'X-Tenant-Id': '999999', 'X-Installation-Pid': installation}
        form = None
        if oauth:
            form = {'grant_type': 'client_credentials', 'client_id': credential['clientId'], 'client_secret': credential['clientSecret']}
            if name == 'oauth-unknown': form['client_id'] = 'unknown-' + uuid.uuid4().hex
            if name == 'oauth-wrong': form['client_secret'] = 'wrong-' + uuid.uuid4().hex
            if name == 'oauth-basic':
                headers['Authorization'] = 'Basic !invalid!'; form = {'grant_type': 'client_credentials'}
            if name == 'oauth-scope': form['scope'] = 'ungranted.' + uuid.uuid4().hex
        elif name == 'bearer-unknown': headers['Authorization'] = 'Bearer unknown-' + uuid.uuid4().hex
        elif name in ['bearer-scope', 'bearer-expired']: headers['Authorization'] = 'Bearer ' + token
        clock = reader.token_clock(installation, token) if name == 'bearer-expired' else None
        try:
            response_status, body, response_headers = request('/oauth2/token' if oauth else path if name == 'bearer-expired' else '/api/open/v1/whoami',
                    method='POST' if oauth else 'GET', form=form, headers=headers, expected=(status,))
        finally:
            if clock is not None:
                restored_clock = reader.token_clock(installation, token, clock)
        identifier = next((v for k,v in response_headers.items() if k.lower() == 'x-request-id'), None)
        require(isinstance(identifier, str) and credential['clientSecret'] not in identifier and token not in identifier
                and (not oauth or identifier != supplied), "authentication response request ID missing or contains credentials")
        if clock is not None:
            proof['tokenExpiry'] = {'expired': clock, 'restored': restored_clock, 'requestId': identifier}
        visible = human(f'/api/open-platform/installations/{installation}/audits?requestId={identifier}')
        proof['requests'].append({'id': name, 'status': response_status, 'body': body, 'requestId': identifier,
                                 'callerIdReplaced': identifier != supplied, 'visibleAudits': visible})
    ids = [r['requestId'] for r in proof['requests']]
    proof['afterDenied'] = reader.read(installation, credential, token, ids)
    proof['normalStatus'], _, _ = request(path, headers={'Authorization': 'Bearer ' + token})
    proof['afterAllowed'] = reader.read(installation, credential, token)
    return verify(proof, report['applicationPid'], installation, path)
