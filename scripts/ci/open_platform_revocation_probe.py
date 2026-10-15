"""Original HTTP-probe proof of credential revocation across stored CRUD responses."""
import hashlib
import json
import re
import uuid
from copy import deepcopy

from open_platform_authentication_probe import require


def header(values, key):
    return next((v for k,v in values.items() if k.lower() == key.lower()), None)


def snapshot(reader, installation, credential, token, model, resource, pids, ids=()):
    for value in [model, resource, installation, credential['credentialPid'], *pids]:
        require(re.fullmatch(r'[A-Za-z0-9_.:-]{1,128}', value), 'revocation SQL fixture identity invalid')
    require(re.fullmatch(r'[a-z][a-z0-9_]{0,62}', model), 'revocation table identity invalid')
    def quoted(v): return "'" + v.replace("'", "''") + "'"
    wanted = ','.join(quoted(v) for v in pids)
    token_hash = hashlib.sha256(token.encode()).hexdigest()
    sql = f"""WITH i AS (SELECT * FROM ab_application_installation WHERE pid={quoted(installation)})
    SELECT json_build_object('database',current_database(),'installationFound',(SELECT COUNT(*) FROM i),
      'credentialStatus',(SELECT c.status FROM ab_application_credential c JOIN i ON i.id=c.installation_id WHERE c.pid={quoted(credential['credentialPid'])}),
      'tokenRevoked',(SELECT t.revoked_at IS NOT NULL FROM ab_application_access_token t JOIN i ON i.id=t.installation_id WHERE t.token_hash={quoted(token_hash)}),
      'records',COALESCE((SELECT json_agg(json_build_object('pid',pid,'name',rel_name,'zone',rel_zone,'version',row_version) ORDER BY pid)
        FROM {model} WHERE tenant_id=(SELECT tenant_id FROM i) AND pid IN ({wanted})), '[]'::json),
      'idempotency',COALESCE((SELECT json_agg(json_build_object('route',x.route_code,'key',x.idempotency_key,'status',x.status,
        'requestHash',x.request_hash,'statusCode',x.response_status,'body',x.response_body,'headers',x.response_headers,
        'recordPid',x.response_record_pid,'authorityHash',x.authority_hash) ORDER BY x.route_code)
        FROM ab_open_api_idempotency x JOIN i ON i.id=x.installation_id WHERE x.route_code LIKE {quoted('resource:'+resource+':%')}),'[]'::json),
      'events',COALESCE((SELECT json_agg(json_build_object('eventId',event_id,'type',event_type,'sequence',event_sequence,
        'subject',subject,'payload',payload) ORDER BY event_id) FROM ab_outbox
        WHERE tenant_id=(SELECT tenant_id FROM i) AND ordering_key LIKE {quoted('open.resource:'+resource+':%')}),'[]'::json));"""
    value = reader.query(sql)
    for event in value['events']:
        payload = event.pop('payload')
        event.update(resource=payload['resource'], operation=payload['operation'], recordVersion=payload['recordVersion'],
                     payloadSha256=hashlib.sha256(json.dumps(payload, sort_keys=True, separators=(',', ':')).encode()).hexdigest())
    value['authentication'] = reader.read(installation, credential, token, ids)
    return value


def verify(proof):
    require(isinstance(proof, dict) and set(proof) == {'resource','applicationPid','installationPid','writes','before','afterRevoked','afterDenied','denials'},
            'credential revocation proof missing')
    before, revoked, denied = [proof.get(k) for k in ['before','afterRevoked','afterDenied']]
    state_keys={'database','installationFound','credentialStatus','tokenRevoked','records','idempotency','events','authentication'}
    require(all(isinstance(v,dict) and set(v)==state_keys and isinstance(v.get('authentication'),dict) for v in [before,revoked,denied]), 'revocation state missing')
    require(before.get('installationFound')==1 and isinstance(before.get('database'),str) and before['database'], 'revocation runtime identity invalid')
    require(before.get('credentialStatus') == 'active' and before.get('tokenRevoked') is False
            and revoked.get('credentialStatus') == denied.get('credentialStatus') == 'revoked'
            and revoked.get('tokenRevoked') is denied.get('tokenRevoked') is True, 'credential/token revocation incomplete')
    for key in ['database','installationFound','records','idempotency','events']:
        require(before.get(key) == revoked.get(key) == denied.get(key), 'revocation changed immutable business facts')
    for key in ['tokenCount','credentialLastUsed','tokenLastUsed','quotaCount']:
        require(before['authentication'].get(key) == revoked['authentication'].get(key) == denied['authentication'].get(key),
                'revoked denial consumed token/credential/quota state')
    writes, denials = proof.get('writes'), proof.get('denials')
    require(isinstance(writes,list) and len(writes)==3 and isinstance(denials,list) and len(denials)==3, 'revocation three-write denominator invalid')
    require([w.get('operation') for w in writes] == ['create','update','delete'], 'revocation write identity invalid')
    records = before.get('records'); idempotency = before.get('idempotency'); events = before.get('events')
    require(isinstance(records,list) and len(records)==2 and isinstance(idempotency,list) and len(idempotency)==3
            and isinstance(events,list) and len(events)==3, 'revocation baseline must contain real data, three keys and events')
    require({e.get('operation') for e in events} == {'created','updated','deleted'}
            and len({e.get('eventId') for e in events})==3 and all(e.get('resource') == proof['resource']
                 and re.fullmatch(r'[a-f0-9]{64}', e.get('payloadSha256','')) for e in events), 'revocation source event baseline invalid')
    audits = denied['authentication'].get('audits')
    require(isinstance(audits,list) and len(audits)==3, 'revoked call audits missing or duplicated')
    indexed = {a.get('requestId'):a for a in audits}
    for write, rejection, status, method in zip(writes,denials,[201,200,204],['POST','PATCH','DELETE']):
        operation = write['operation']; detail = operation!='create'
        base = '/api/open/v1/resources/' + proof['resource']
        require(write.get('status') == status and write.get('replayStatus') == status and write.get('replayMarker') == 'true'
                and write.get('body') == write.get('replayBody') and write.get('etag') == write.get('replayEtag')
                and write.get('location') == write.get('replayLocation'), 'normal stored write replay not proven')
        pid = write.get('pid'); require(isinstance(pid,str) and pid and not pid.isdigit(), 'revocation record PID invalid')
        rows = [r for r in records if r.get('pid')==pid]
        expected_name = {'create':'Revocation create','update':'Revocation updated','delete':None}[operation]
        require((len(rows)==1 and rows[0].get('name')==expected_name and rows[0].get('zone')=='A'
                 and rows[0].get('version')==(1 if operation=='create' else 2)) if operation!='delete' else not rows,
                'revocation data/version baseline invalid')
        source_events=[e for e in events if e.get('subject')==pid]
        event_operation={'create':'created','update':'updated','delete':'deleted'}[operation]
        version=1 if operation=='create' else 2
        require(len(source_events)==1 and source_events[0].get('operation')==event_operation
                and source_events[0].get('type')=='open.resource.'+proof['resource']+'.'+event_operation
                and source_events[0].get('recordVersion')==source_events[0].get('sequence')==version, 'revocation event/version baseline invalid')
        saved = [r for r in idempotency if r.get('route')=='resource:'+proof['resource']+':'+operation]
        require(len(saved)==1 and saved[0].get('key')==write.get('key') and saved[0].get('status')=='completed'
                and saved[0].get('statusCode')==status and saved[0].get('body')==write.get('body'), 'stored idempotency baseline invalid')
        require(rejection.get('operation')==operation and rejection.get('status')==401
                and rejection.get('body')=={'success':False,'code':'invalid_token','message':'invalid_token'}
                and all(rejection.get(k) is None for k in ['etag','location','replay']), 'revoked request restored old response')
        audit = indexed.get(rejection.get('requestId'))
        require(isinstance(audit,dict) and audit.get('method')==method
                and audit.get('path')==('/api/open/v1/resources/{resource}/{pid}' if detail else '/api/open/v1/resources/{resource}')
                and audit.get('status')==401 and audit.get('applicationPid')==proof['applicationPid']
                and audit.get('installationPid')==proof['installationPid'] and audit.get('tenantMatches') is True
                and audit.get('tokenPresent') is True and audit.get('anonymous') is False and audit.get('secretFree') is True
                and audit.get('businessDetails')=={'actorKind':'unauthenticated','phase':'authentication','code':'invalid_token'},
                'revoked bearer audit identity invalid')
    return proof


def run(request,human,reader,model,definition):
    require(reader is not None, 'owned credential revocation SQL reader required')
    code=definition['code']+'.revocation'; contract=deepcopy(definition); contract.update(code=code,events=['created','updated','deleted'])
    update=human('/api/dynamic/'+model+'/create','POST',{'rel_name':'Revocation update','rel_zone':'A','rel_private':'Owned fixture'})
    delete=human('/api/dynamic/'+model+'/create','POST',{'rel_name':'Revocation delete','rel_zone':'A','rel_private':'Owned fixture'})
    application=human('/api/open-platform/applications','POST',{'name':'Revocation HTTP proof','description':'Owned original probe'})
    installation=human('/api/open-platform/applications/'+application['pid']+'/installations','POST',
            {'environment':'staging','scopes':list(contract['scopes'].values()),'rateLimitPerMinute':600})
    published=False; created=None; delete_completed=False
    try:
        human('/api/open-platform/publications/'+code+'/draft','PUT',{'expectedRevision':0,'definition':contract})
        human('/api/open-platform/publications/'+code+'/publish','POST',{'expectedRevision':1});published=True
        human('/api/open-platform/installations/'+installation['pid']+'/resources/'+code,'PUT',
                {'operations':contract['operations'],'readableFields':['pid','name','zone'],'writableFields':['name','zone'],'rowConstraints':[{'field':'zone','values':['A']}]})
        credential=human('/api/open-platform/installations/'+installation['pid']+'/credentials','POST',{})
        _,token,_=request('/oauth2/token','POST',form={'grant_type':'client_credentials','client_id':credential['clientId'],'client_secret':credential['clientSecret']})
        raw=token['access_token'];machine={'Authorization':'Bearer '+raw};base='/api/open/v1/resources/'+code
        _,_,uh=request(base+'/'+update['pid'],headers=machine);_,_,dh=request(base+'/'+delete['pid'],headers=machine)
        rows=[];calls=[]
        for operation,method,path,body,match,status in [('create','POST',base,{'name':'Revocation create','zone':'A'},None,201),
                ('update','PATCH',base+'/'+update['pid'],{'name':'Revocation updated'},header(uh,'ETag'),200),
                ('delete','DELETE',base+'/'+delete['pid'],None,header(dh,'ETag'),204)]:
            key='revocation-'+operation+'-'+uuid.uuid4().hex;headers=dict(machine,**{'Idempotency-Key':key})
            if match:headers['If-Match']=match
            actual,value,h=request(path,method,body=body,headers=headers,expected=(status,))
            if operation=='create':created=value
            if operation=='delete':delete_completed=True
            replay,replayed,rh=request(path,method,body=body,headers=headers,expected=(status,))
            pid=value['pid'] if operation=='create' else (update['pid'] if operation=='update' else delete['pid'])
            rows.append({'operation':operation,'pid':pid,'key':key,'status':actual,'body':value,'etag':header(h,'ETag'),'location':header(h,'Location'),
                'replayStatus':replay,'replayBody':replayed,'replayEtag':header(rh,'ETag'),'replayLocation':header(rh,'Location'),'replayMarker':header(rh,'Idempotent-Replay')})
            calls.append((operation,method,path,body,headers))
        pids=[r['pid'] for r in rows]
        proof={'resource':code,'applicationPid':application['pid'],'installationPid':installation['pid'],'writes':rows,'denials':[]}
        state=lambda ids=():snapshot(reader,installation['pid'],credential,raw,model,code,pids,ids)
        proof['before']=state()
        human('/api/open-platform/installations/'+installation['pid']+'/credentials/'+credential['credentialPid'],'DELETE')
        proof['afterRevoked']=state()
        for operation,method,path,body,headers in calls:
            marker='revoked-'+operation+'-'+uuid.uuid4().hex
            status,value,h=request(path,method,body=body,headers=dict(headers,**{'X-Request-Id':marker}),expected=(401,))
            proof['denials'].append({'operation':operation,'requestId':header(h,'X-Request-Id'),'status':status,'body':value,
                                    'etag':header(h,'ETag'),'location':header(h,'Location'),'replay':header(h,'Idempotent-Replay')})
        proof['afterDenied']=state([r['requestId'] for r in proof['denials']])
        return verify(proof)
    finally:
        human('/api/open-platform/installations/'+installation['pid'],'DELETE')
        if published:human('/api/open-platform/publications/'+code+'/withdraw','POST',{'expectedRevision':1})
        for row in [created,update]+([] if delete_completed else [delete]):
            if row is not None:human('/api/dynamic/'+model+'/'+row['pid'],'DELETE')
