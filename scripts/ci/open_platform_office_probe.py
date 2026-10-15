"""Execute the packaged office-device guide against real installation authority and signed events."""
import base64
import hashlib
import hmac
import importlib.util
import io
import json
import math
import os
from pathlib import Path
import sqlite3
import subprocess
import threading
import time
import uuid
from html.parser import HTMLParser
from http.server import HTTPServer

from open_platform_http import ProtocolError, data

CASES = ('real-asset-source', 'resource-and-business-publication', 'invalid-scope-rejected',
         'current-installation-grants', 'permanent-direct-token', 'packaged-guide-execution',
         'real-signed-office-events', 'durable-inbox-and-tombstone', 'tampered-proof-rejected')


def require(condition, message):
    if not condition:
        raise ProtocolError('office integration: ' + message)


class Examples(HTMLParser):
    def __init__(self):
        super().__init__()
        self.current, self.files = None, {}

    def handle_starttag(self, tag, attributes):
        if tag == 'code':
            self.current = dict(attributes).get('data-filename')
            if self.current:
                require(self.current in {'crud.sh', 'receiver.py'} and self.current not in self.files, 'example identity')
                self.files[self.current] = ''

    def handle_data(self, value):
        if self.current:
            self.files[self.current] += value

    def handle_endtag(self, tag):
        if tag == 'code':
            self.current = None


def verify_office_report(report, *, secret):
    require(isinstance(report, dict) and report.get('profile') == 'office-guide-http-v1', 'report profile')
    require(report.get('cases') == list(CASES) and all(type(report.get(k)) is int for k in ('executed','passed','failed','skipped'))
            and report['executed'] == report['passed'] == len(CASES) and report['failed'] == report['skipped'] == 0, 'case denominator')
    require(isinstance(secret, bytes) and bool(secret), 'independent signature key')
    guide = Path(__file__).resolve().parents[2] / 'web-admin/public/docs/open-platform-integration.html'
    parsed = Examples(); parsed.feed(guide.read_text())
    require(report.get('guideSha256') == hashlib.sha256(guide.read_bytes()).hexdigest(), 'frozen guide identity')
    examples = report.get('examples')
    require(isinstance(examples, dict) and set(examples) == {'crud.sh','receiver.py'}
            and all(isinstance(v,str) and len(v)==64 and all(c in '0123456789abcdef' for c in v) for v in examples.values()), 'example fingerprints')
    require(examples == {name:hashlib.sha256(content.encode()).hexdigest() for name,content in parsed.files.items()}, 'extracted example identity')
    require(report.get('sourceModel') == 'tasset_asset' and report.get('resource') == 'office.devices'
            and report.get('sourceKinds') == ['command','handler'], 'real source binding')
    rows = report.get('receipts')
    require(isinstance(rows,list) and len(rows)>=5, 'real notification receipts')
    types, records = set(), {}
    for row in rows:
        require(isinstance(row,dict) and type(row.get('status')) is int and row['status']==204, 'receiver status')
        try:
            raw = base64.b64decode(row.get('raw'),validate=True)
            stamp = row['timestamp']
            received = row['receivedAt']
            require(type(received) in (float,int) and math.isfinite(received) and stamp.isascii() and stamp.isdecimal()
                    and abs(received-int(stamp))<=300, 'notification timestamp window')
            expected = 'sha256='+hmac.new(secret,stamp.encode('ascii')+b'.'+raw,hashlib.sha256).hexdigest()
            require(hmac.compare_digest(row['signature'],expected), 'signed raw bytes')
            event = json.loads(raw)
            require(event['subject']['type']=='office.devices' and event['type'] in {
                'open.resource.office.devices.created','open.resource.office.devices.updated','open.resource.office.devices.deleted'}, 'event identity')
            require(row['eventType']==event['type'] and row['eventVersion']=='1' and type(event['schemaVersion']) is int
                    and event['schemaVersion']==1 and event['data']['pid']==event['subject']['pid']
                    and type(event['recordVersion']) is int and event['recordVersion']>=1, 'event schema')
            require(set(event['data']) <= {'pid','assetCode','name','status','location','serialNumber','assignedTo'}, 'projected fields')
            types.add(event['type'].rsplit('.',1)[1])
            pid = event['subject']['pid']
            if pid not in records or records[pid]['recordVersion']<event['recordVersion']:
                records[pid] = event
        except (KeyError,ValueError,TypeError,UnicodeError):
            raise ProtocolError('office integration: malformed raw notification proof') from None
    require(types=={'created','updated','deleted'} and len(records)==2, 'CRUD notification coverage')
    projected = report.get('projection')
    expected = sorted([{'pid':pid,'version':event['recordVersion'],'deleted':int(event['type'].endswith('.deleted')),
                        'data':event['data']} for pid,event in records.items()],key=lambda row:row['pid'])
    require(projected==expected and sorted(row['deleted'] for row in projected)==[0,1], 'inbox projection derived from signed events')
    active = next(row for row in projected if row['deleted']==0)
    require(active['data'].get('status')=='in_use' and active['data'].get('assignedTo')=='External integration operator', 'native assignment result')
    return report


def run_office_probe(request, admin, *, base_url, guide, evidence, receiver_host, secret):
    evidence = Path(evidence)
    evidence.mkdir(parents=True,exist_ok=False)
    proofs = []

    def record(case, condition):
        require(condition, case)
        proofs.append(case)

    def human(path, method='GET', body=None, expected=(200,), headers=None):
        status,result,response = request(path,method=method,body=body,headers=headers or admin,expected=expected)
        if status==200:
            require(isinstance(result,dict) and str(result.get('code'))=='0', 'administration envelope')
        return data(result),response

    def field(source, *, writable=True, required=False, operators=(), sortable=False, kind='string'):
        return {'source':source,'type':kind,'readable':True,'writable':writable,'required':required,'nullable':not required,
                'operators':list(operators),'sortable':sortable,'referenceResource':None,'properties':{},'items':None}

    source,_ = human('/api/open-platform/publication-sources/tasset_asset')
    record('real-asset-source',set(source['operations'])=={'list','get','create','update','delete'}
           and any(row['code']=='tasset_as_location' and row['filterable'] for row in source['fields']))
    contract = {'code':'office.devices','modelCode':'tasset_asset','schemaVersion':1,'operations':['list','get','create','update','delete'],
        'scopes':{op:'office.devices.'+('read' if op in ('list','get') else 'write') for op in ('list','get','create','update','delete')},
        'dataPolicy':'installation_filter','maxPageSize':50,'maxConditions':8,'timeoutMs':5000,'events':['created','updated','deleted'],
        'fields':{'pid':dict(field('pid',writable=False,kind='pid'),nullable=False),
                  'assetCode':field('tasset_as_code',required=True,operators=['EQ'],sortable=True),
                  'name':field('tasset_as_name',required=True,operators=['EQ','LIKE'],sortable=True),
                  'status':field('tasset_as_status',writable=False,operators=['EQ']),
                  'location':field('tasset_as_location',operators=['EQ']), 'serialNumber':field('tasset_as_serial'),
                  'assignedTo':field('tasset_as_assigned_to')}}
    human('/api/open-platform/publications/office.devices/draft','PUT',{'expectedRevision':0,'definition':contract})
    human('/api/open-platform/publications/office.devices/publish','POST',{'expectedRevision':1})
    sources,_ = human('/api/open-platform/action-sources?resourceCode=office.devices')
    actions = []
    for alias,kind,internal in [('office.devices.assign','command','tasset:assign_asset'),('office.devices.page-summary','handler','core.visible-page')]:
        matches = [row for row in sources if row['sourceKind']==kind and row['sourceCode']==internal]
        require(len(matches)==1 and matches[0]['available'], 'registered real business source '+alias)
        source = matches[0]
        definition = dict(code=alias,sourceKind=kind,sourceCode=internal,resourceCode='office.devices',schemaVersion=1,
            sourceFingerprint=source['fingerprint'],permission=source['permission'],scopes={'execute':alias+'.execute'},
            parameters=source['parameters'],inputFields=source['inputFields'],effectFields=source['effectFields'],outputFields=source['outputFields'],
            timeoutMs=max(5000,source['minTimeoutMs']),maxConcurrent=2)
        human('/api/open-platform/action-publications/'+alias+'/draft','PUT',{'expectedRevision':0,'definition':definition})
        published,_ = human('/api/open-platform/action-publications/'+alias+'/publish','POST',{'expectedRevision':1})
        require(published['activeVersion']==1,'published business version')
        actions.append(definition)
    record('resource-and-business-publication',len(actions)==2)
    app,_ = human('/api/open-platform/applications','POST',{'name':'Office guide integration qualification'})
    human('/api/open-platform/applications/'+app['pid']+'/installations','POST',
          {'environment':'development','scopes':['office.unpublished.reject'],'rateLimitPerMinute':10000},expected=(400,))
    record('invalid-scope-rejected',True)
    scopes = ['office.devices.read','office.devices.write','openapi.profile.read','openapi.events.read']+[row['scopes']['execute'] for row in actions]
    installation,_ = human('/api/open-platform/applications/'+app['pid']+'/installations','POST',
                          {'environment':'development','scopes':scopes,'rateLimitPerMinute':10000})
    root = '/api/open-platform/installations/'+installation['pid']
    policy = {'operations':contract['operations'],'readableFields':list(contract['fields']),
              'writableFields':['assetCode','name','location','serialNumber','assignedTo'],'rowConstraints':[{'field':'location','values':['HQ']}]}
    human(root+'/resources/office.devices','PUT',policy)
    for action in actions: human(root+'/actions/'+action['code'],'PUT',{'execute':True,'effectFields':action['effectFields']})
    record('current-installation-grants',True)
    token,_ = human(root+'/integration-tokens','POST',{'name':'Office guide caller','expirationMode':'never'},
                    headers=dict(admin,**{'Idempotency-Key':'office-'+uuid.uuid4().hex}))
    record('permanent-direct-token',token['appId']==app['pid'] and token['metadata']['expiresAt'] is None)
    parsed = Examples(); guide = Path(guide); parsed.feed(guide.read_text())
    require(set(parsed.files)=={'crud.sh','receiver.py'},'complete packaged examples')
    for name,content in parsed.files.items(): (evidence/name).write_text(content)
    private_env = dict(os.environ,AURABOOT_BASE_URL=base_url,AURABOOT_APP_ID=app['pid'],AURABOOT_TOKEN=token['token'],
                       INBOX_DB=str((evidence/'inbox.sqlite3').resolve()),AURABOOT_CONSUMER_KEY=installation['pid'],AURABOOT_WEBHOOK_SECRET=secret.decode())
    # Import only the extracted receiver. The runner adapts bind address to its explicitly admitted fixture bridge.
    saved_env = {key:os.environ.get(key) for key in ('INBOX_DB','AURABOOT_CONSUMER_KEY','AURABOOT_WEBHOOK_SECRET')}
    try:
        for key in saved_env: os.environ[key]=private_env[key]
        spec = importlib.util.spec_from_file_location('qualified_office_receiver',evidence/'receiver.py')
        module = importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
    finally:
        for key,value in saved_env.items():
            if value is None: os.environ.pop(key,None)
            else: os.environ[key]=value
    receipts = []
    class ObservedReceiver(module.Receiver):
        def do_POST(self):
            received = time.time()
            raw = self.rfile.read(int(self.headers.get('Content-Length','0')))
            self.rfile = io.BytesIO(raw)
            self.response_status = None
            super().do_POST()
            receipts.append({'receivedAt':received,'raw':base64.b64encode(raw).decode(),'timestamp':self.headers.get('X-Webhook-Timestamp'),
                'signature':self.headers.get('X-Webhook-Signature'),'eventType':self.headers.get('X-Webhook-Event'),
                'eventVersion':self.headers.get('X-Webhook-Event-Version'),'status':self.response_status})
        def send_response(self,code,message=None):
            self.response_status = code
            return super().send_response(code,message)
    server = HTTPServer((receiver_host,0),ObservedReceiver)
    thread = threading.Thread(target=server.serve_forever,daemon=True);thread.start()
    try:
        for suffix in ('created','updated','deleted'):
            human('/api/webhooks','POST',{'name':'Office guide '+suffix,'installationPid':installation['pid'],'eventType':'open.resource.office.devices.'+suffix,
                'eventVersion':1,'targetUrl':f'http://{receiver_host}:{server.server_port}/webhooks/auraboot',
                'secret':secret.decode(),'maxRetries':3,'timeoutMs':5000})
        result = subprocess.run(['bash',str(evidence/'crud.sh')],env=private_env,capture_output=True,timeout=90)
        require(result.returncode==0 and result.stdout==b'Office CRUD/List, assignment and page summary completed.\n',
                'packaged CRUD example exit '+str(result.returncode))
        record('packaged-guide-execution',True)
        deadline = time.monotonic()+45
        projection = []
        while time.monotonic()<deadline:
            module.drain()
            with sqlite3.connect(private_env['INBOX_DB']) as db:
                projection = [{'pid':row[0],'version':row[1],'deleted':row[2],'data':json.loads(row[3])} for row in db.execute(
                    'SELECT pid,version,deleted,data FROM projection WHERE consumer=? ORDER BY pid',(installation['pid'],))]
            if len(receipts)>=5 and len(projection)==2 and sorted(row['deleted'] for row in projection)==[0,1] and any(row['data'].get('status')=='in_use' and row['data'].get('assignedTo')=='External integration operator' for row in projection if not row['deleted']): break
            time.sleep(.1)
        record('real-signed-office-events',len(receipts)>=5 and all(row['status']==204 for row in receipts))
        record('durable-inbox-and-tombstone',len(projection)==2 and sorted(row['deleted'] for row in projection)==[0,1])
        report = dict(profile='office-guide-http-v1',cases=list(CASES),executed=len(CASES),passed=len(CASES),failed=0,skipped=0,
            sourceModel='tasset_asset',resource='office.devices',sourceKinds=['command','handler'],
            guideSha256=hashlib.sha256(guide.read_bytes()).hexdigest(),
            examples={name:hashlib.sha256(content.encode()).hexdigest() for name,content in parsed.files.items()},
            receipts=receipts,projection=projection)
        verify_office_report(report,secret=secret)
        mutated = dict(report,receipts=[dict(row) for row in receipts]);mutated['receipts'][0]['raw']=base64.b64encode(b'{}').decode()
        try: verify_office_report(mutated,secret=secret)
        except ProtocolError: record('tampered-proof-rejected',True)
        else: raise ProtocolError('office integration: tampered proof accepted')
        require(proofs==list(CASES),'executed case order')
        (evidence/'result.json').write_text(json.dumps(report,indent=2)+'\n')
        return report
    finally:
        server.shutdown();server.server_close();thread.join(timeout=5)
