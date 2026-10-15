"""Real HTTP qualification for explicitly adapted Controller endpoints; no URL forwarding."""
import hashlib
import json
import re
import uuid
from pathlib import Path
from open_platform_http import ProtocolError, data

DEFINITIONS = {
    'list': ('dynamic.list','resource.list','office.devices','GET',False),
    'get': ('dynamic.get','resource.get','office.devices','GET',True),
    'create': ('dynamic.create','resource.create','office.devices','POST',False),
    'update': ('dynamic.update','resource.update','office.devices','PATCH',True),
    'delete': ('dynamic.delete','resource.delete','office.devices','DELETE',True),
    'command': ('command.execute','action.execute','office.devices.assign','POST',False),
    'query': ('query.execute','query.execute','release.dynamic.by-zone','POST',False),
}
STATUSES = {'anonymous':401,'ungranted':403,'wrong-method':404,'fixed-list':200,'permanent-list':200,
    'create':201,'create-replay':201,'get':200,'update':200,'stale-update':412,'field-denial':400,'row-denial':403,
    'command':200,'command-replay':200,'query':200,'query-hidden':200,'underlying-revoked':403,'restored':200,
    'endpoint-revoked':403,'endpoint-restored':200,'delete':204,'deleted':404,'withdrawn':404,'withdrawn-document':404}


def require(condition, message):
    if not condition: raise ProtocolError('controller endpoints: '+message)


def verify_endpoint_report(report):
    require(isinstance(report,dict) and report.get('profile')=='controller-endpoints-http-v1','profile')
    require(report.get('observations')==STATUSES and all(type(value) is int for value in report['observations'].values()),'executed HTTP denominator')
    require(report.get('executed')==len(STATUSES) and report.get('failed')==report.get('skipped')==0,'terminal counts')
    rows=report.get('contracts');require(isinstance(rows,list) and len(rows)==len(DEFINITIONS),'contract denominator')
    for row,(name,definition) in zip(rows,DEFINITIONS.items()):
        candidate,adapter,binding,method,target=definition
        require(row.get('code')=='office.endpoint.'+name and row.get('candidateCode')==candidate and row.get('adapterCode')==adapter
            and row.get('bindingCode')==binding and row.get('method')==method and row.get('target') is target
            and re.fullmatch('[a-f0-9]{64}',row.get('fingerprint','')) is not None,'Controller and contract source binding')
        for key in ('openapiSha256','typesSha256'):require(re.fullmatch('[a-f0-9]{64}',row.get(key,'')) is not None,'parsed artifact identity')
    proof=report.get('resourceProof',{})
    require(isinstance(proof.get('pid'),str) and bool(proof['pid']) and proof.get('created')==proof.get('replayed')
        and proof.get('created',{}).get('pid')==proof['pid'] and proof['created'].get('location')=='HQ'
        and proof.get('updated',{}).get('name')=='Endpoint updated' and proof['updated'].get('pid')==proof['pid']
        and proof.get('assigned',{}).get('status')=='in_use' and proof['assigned'].get('assignedTo')=='Endpoint operator'
        and proof['assigned'].get('pid')==proof['pid'],'actual CRUD and command result')
    require(proof.get('createReplayHeader')==proof.get('commandReplayHeader')=='true'
        and isinstance(proof.get('createdEtag'),str) and proof['createdEtag'].startswith('"')
        and proof.get('updatedEtag')!=proof['createdEtag'] and proof.get('stalePersisted')==proof['updated'] and proof.get('deniedPersisted')==proof['updated'] and proof.get('fieldDenialCode')=='invalid_request','replay and optimistic concurrency')
    require(report.get('queryProjection') and all(set(row)=={'pid','name'} for row in report['queryProjection'])
        and report.get('hiddenQueryItems')==[] and report.get('unadaptedBlocked') is True,'row and projection authority')
    require(report.get('expirationModes')==['fixed','never'] and report.get('publishedVersion')==1,'token and publication identity')
    return report


def run_endpoint_probe(request, admin, *, evidence):
    evidence=Path(evidence);evidence.mkdir(parents=True,exist_ok=False)
    def human(path,method='GET',body=None,expected=(200,),headers=None):
        status,result,response=request(path,method=method,body=body,headers=headers or admin,expected=expected)
        if status==200: require(isinstance(result,dict) and str(result.get('code'))=='0','human envelope')
        return data(result),response
    def header(headers,name):return next((v for k,v in headers.items() if k.lower()==name.lower()),None)
    observed={}
    def call(case,path,method='GET',body=None,headers=None):
        status,result,response=request(path,method=method,body=body,headers=headers or {},expected=(STATUSES[case],))
        require(case not in observed,'case repeated');observed[case]=status
        return result,response
    candidates,_=human('/api/open-platform/endpoint-candidates')
    require(any(not row['available'] and row['reason']=='machine_adapter_required' for row in candidates),'unadapted routes stay blocked')
    human('/api/open-platform/endpoint-source?candidateCode=unregistered.route&bindingCode=office.devices',expected=(400,))
    contracts=[]
    for name,(candidate,adapter,binding,method,target) in DEFINITIONS.items():
        source,_=human('/api/open-platform/endpoint-source?candidateCode='+candidate+'&bindingCode='+binding)
        require(source['candidate']['available'] and source['candidate']['externalMethod']==method,'actual Controller candidate')
        code='office.endpoint.'+name
        contract=dict(code=code,candidateCode=candidate,adapterCode=adapter,bindingCode=binding,schemaVersion=1,
            sourceFingerprint=source['fingerprint'],scopes={'invoke':code+'.invoke'},timeoutMs=max(5000,source['minTimeoutMs']),maxConcurrent=2)
        base='/api/open-platform/endpoint-publications/'+code
        human(base+'/draft','PUT',{'expectedRevision':0,'definition':contract})
        human(base+'/draft','PUT',{'expectedRevision':0,'definition':contract},expected=(409,))
        human(base+'/publish','POST',{'expectedRevision':1})
        _,doc,_=request(base+'/openapi',headers=admin)
        path='/api/open/v1/endpoints/'+code+('/{recordPid}' if target else '')
        operation=doc.get('paths',{}).get(path,{}).get(method.lower(),{})
        require(doc.get('openapi')=='3.1.0' and doc.get('x-schema-version')==1 and code+'.invoke' in operation.get('x-required-scopes',[])
            and operation.get('x-bound-contract')==binding and '/api/dynamic/' not in json.dumps(doc),'exact external OpenAPI')
        _,types,_=request(base+'/types',headers=admin,response_type='text')
        require(isinstance(types,str) and 'export type EndpointRequest' in types and 'export type EndpointResult' in types,'endpoint type artifact')
        (evidence/(code+'.openapi.json')).write_text(json.dumps(doc,indent=2)+'\n');(evidence/(code+'.d.ts')).write_text(types)
        contracts.append(dict(code=code,candidateCode=candidate,adapterCode=adapter,bindingCode=binding,method=method,target=target,
            fingerprint=source['fingerprint'],openapiSha256=hashlib.sha256(json.dumps(doc,sort_keys=True).encode()).hexdigest(),typesSha256=hashlib.sha256(types.encode()).hexdigest()))
    app,_=human('/api/open-platform/applications','POST',{'name':'Controller endpoint qualification'})
    scopes=['office.devices.read','office.devices.write','office.devices.assign.execute','release.dynamic:list','release.dynamic:query']+[row['code']+'.invoke' for row in contracts]
    install,_=human('/api/open-platform/applications/'+app['pid']+'/installations','POST',{'environment':'development','scopes':scopes,'rateLimitPerMinute':10000})
    root='/api/open-platform/installations/'+install['pid']
    policy={'operations':['list','get','create','update','delete'],'readableFields':['pid','assetCode','name','status','location','serialNumber','assignedTo'],
            'writableFields':['assetCode','name','location','assignedTo'],'rowConstraints':[{'field':'location','values':['HQ']}]}
    human(root+'/resources/office.devices','PUT',policy)
    human(root+'/actions/office.devices.assign','PUT',{'execute':True,'effectFields':['assignedTo','status']})
    human(root+'/resources/release.dynamic.parts','PUT',{'operations':['list'],'readableFields':['pid','name','zone'],'writableFields':[], 'rowConstraints':[{'field':'zone','values':['A']}]})
    human(root+'/queries/release.dynamic.by-zone','PUT',{'readableFields':['pid','name']})
    tokens=[]
    for mode in ('fixed','never'):
        token,_=human(root+'/integration-tokens','POST',{'name':'Endpoint '+mode,'expirationMode':mode},headers=dict(admin,**{'Idempotency-Key':'endpoint-'+uuid.uuid4().hex}))
        require(token['metadata']['expirationMode']==mode and (token['metadata']['expiresAt'] is None)==(mode=='never'),'token mode')
        tokens.append({'Authorization':'Bearer '+token['token']})
    machine=tokens[1];path=lambda op:'/api/open/v1/endpoints/office.endpoint.'+op
    call('anonymous',path('list'));call('ungranted',path('list'),headers=machine)
    for row in contracts:human(root+'/endpoints/'+row['code'],'PUT',{'invoke':True})
    call('wrong-method',path('get'),method='POST',body={},headers=machine)
    for case,token in zip(('fixed-list','permanent-list'),tokens):
        listed,_=call(case,path('list')+'?limit=5',headers=token)
        require(isinstance(listed.get('items'),list) and all(row['location']=='HQ' for row in listed['items']),'token list projection')
    create_headers=dict(machine,**{'Idempotency-Key':'endpoint-create-'+uuid.uuid4().hex})
    intent={'assetCode':'ENDPOINT-'+uuid.uuid4().hex[:12],'name':'Endpoint device','location':'HQ'}
    created,created_headers=call('create',path('create'),'POST',intent,create_headers)
    replayed,replay_headers=call('create-replay',path('create'),'POST',intent,create_headers)
    pid=created['pid'];etag=header(created_headers,'ETag')
    fetched,_=call('get',path('get')+'/'+pid,headers=machine);require(fetched==created,'exact GET projection')
    changed,updated_headers=call('update',path('update')+'/'+pid,'PATCH',{'name':'Endpoint updated'},dict(machine,**{'Idempotency-Key':'endpoint-update-'+uuid.uuid4().hex,'If-Match':etag}))
    updated_etag=header(updated_headers,'ETag')
    call('stale-update',path('update')+'/'+pid,'PATCH',{'name':'Stale'},dict(machine,**{'Idempotency-Key':'endpoint-stale-'+uuid.uuid4().hex,'If-Match':etag}))
    _,persisted,_=request(path('get')+'/'+pid,headers=machine)
    field_denial_code=None
    for case,body in [('field-denial',{'serialNumber':'Restricted'}),('row-denial',{'location':'BRANCH'})]:
        denied,_=call(case,path('update')+'/'+pid,'PATCH',body,dict(machine,**{'Idempotency-Key':'endpoint-deny-'+uuid.uuid4().hex,'If-Match':updated_etag}))
        if case=='field-denial':
            field_denial_code=denied.get('code')
            require(field_denial_code=='invalid_request' and denied.get('message')=='Input exceeds installation field policy','exact installation field rejection')
    _,denied_persisted,_=request(path('get')+'/'+pid,headers=machine)
    require(denied_persisted==changed,'denied writes must not persist')
    command_intent={'targetPid':pid,'input':{'assignedTo':'Endpoint operator'}}
    command_headers=dict(machine,**{'Idempotency-Key':'endpoint-command-'+uuid.uuid4().hex,'If-Match':updated_etag})
    assigned,assigned_headers=call('command',path('command'),'POST',command_intent,command_headers)
    command_replay,command_replay_headers=call('command-replay',path('command'),'POST',command_intent,command_headers)
    require(assigned['resource']==command_replay['resource'],'command replay result')
    queried,_=call('query',path('query'),'POST',{'parameters':{'zone':'A'},'limit':10},machine)
    hidden,_=call('query-hidden',path('query'),'POST',{'parameters':{'zone':'B'},'limit':10},machine)
    human(root+'/resources/office.devices','DELETE');call('underlying-revoked',path('get')+'/'+pid,headers=machine)
    human(root+'/resources/office.devices','PUT',policy);call('restored',path('get')+'/'+pid,headers=machine)
    human(root+'/endpoints/office.endpoint.get','DELETE');call('endpoint-revoked',path('get')+'/'+pid,headers=machine)
    human(root+'/endpoints/office.endpoint.get','PUT',{'invoke':True});call('endpoint-restored',path('get')+'/'+pid,headers=machine)
    call('delete',path('delete')+'/'+pid,'DELETE',headers=dict(machine,**{'Idempotency-Key':'endpoint-delete-'+uuid.uuid4().hex,'If-Match':header(assigned_headers,'ETag')}))
    call('deleted',path('get')+'/'+pid,headers=machine)
    human('/api/open-platform/endpoint-publications/office.endpoint.get/withdraw','POST',{'expectedRevision':1})
    call('withdrawn',path('get')+'/'+pid,headers=machine)
    call('withdrawn-document','/api/open-platform/endpoint-publications/office.endpoint.get/openapi',headers=admin)
    report=dict(profile='controller-endpoints-http-v1',observations=observed,executed=len(observed),failed=0,skipped=0,contracts=contracts,
        resourceProof=dict(pid=pid,created=created,replayed=replayed,updated=changed,assigned=assigned['resource'],createdEtag=etag,updatedEtag=updated_etag,
            createReplayHeader=header(replay_headers,'Idempotent-Replay'),commandReplayHeader=header(command_replay_headers,'Idempotent-Replay'),stalePersisted=persisted,deniedPersisted=denied_persisted,fieldDenialCode=field_denial_code),
        queryProjection=queried['items'],hiddenQueryItems=hidden['items'],unadaptedBlocked=True,expirationModes=['fixed','never'],publishedVersion=1)
    (evidence/'types-consumer.ts').write_text("""import type { EndpointRequest as Create, EndpointResult as Created } from './office.endpoint.create.js';
import type { EndpointRequest as Patch } from './office.endpoint.update.js';
const create: Create = {assetCode:'EXT-100',name:'Office display',location:'HQ'};
const patch: Patch = {name:'Renamed'};
// @ts-expect-error public creation requires a name and asset code
const missing: Create = {};
// @ts-expect-error model storage names are not public inputs
const internal: Create = {assetCode:'EXT-100',name:'Office display',tasset_as_location:'HQ'};
// @ts-expect-error status is a command effect and cannot be patched directly
const readonly: Patch = {status:'in_use'};
const result: Created = {pid:'record-pid',name:'Office display'};
console.log(create,patch,result,missing,internal,readonly);
""")
    verify_endpoint_report(report);(evidence/'result.json').write_text(json.dumps(report,indent=2)+'\n');return report
