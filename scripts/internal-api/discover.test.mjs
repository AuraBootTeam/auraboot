import { test } from 'node:test';
import assert from 'node:assert/strict';
import { discoverCalls, matchOperation, inventory } from './discover.mjs';
const response = { 200: { description: 'OK' } };
const parameter = { name: 'pid', in: 'path', required: true, schema: { type: 'string' } };
const document = { openapi: '3.1.0', info: { title: 'Test', version: '1' }, paths: {
  '/api/views': { post: { operationId: 'create', responses: response } },
  '/api/views/{pid}': { parameters: [parameter], get: { operationId: 'read', responses: response } },
  '/api/views/accessible': { get: { operationId: 'accessible', responses: response } },
  '/api/upload': { post: { operationId: 'upload', responses: response } },
} };

test('resolves imported aliases, local constants, class base URLs and encoded templates', () => {
  const result = discoverCalls([{ file: 'web-admin/app/view.ts', text: `
    import {get as read, post} from './http-client';
    const BASE = '/api/views';
    interface View {pid: string}
    class Views { private baseUrl = BASE;
      load(pid: string) { return read<View>(\`\${this.baseUrl}/\${encodeURIComponent(pid)}\`); }
      create() { return post(BASE, {name:'Sample'}); }
    }
  ` }], document);
  assert.equal(result.calls.length, 2);
  assert.equal(result.calls[0].route, '/api/views/{pid}');
  assert.equal(result.calls[0].matches[0].operationId, 'read');
  assert.equal(result.calls[1].matches[0].operationId, 'create');
  assert.equal(result.declarations[0].symbol, 'View');
});

test('matches fixed routes preferentially and does not guess fixed paths from client variables', () => {
  assert.equal(matchOperation('/api/views/accessible', 'get', document)[0].operationId, 'accessible');
  assert.equal(matchOperation('/api/views/{code}', 'get', document)[0].operationId, 'read');
  assert.deepEqual(matchOperation('/api/views/accessible', 'delete', document), []);
});

test('retains missing, external and indirect calls rather than silently excluding them', () => {
  const result = discoverCalls([{ file: 'web-admin/app/example.ts', text: `
    import {fetchResult} from '~/shared/services/http-client';
    fetch('/api/missing');
    fetch('https://external.invalid/api/views');
    fetchResult('/api/views', {method:'post',params:{}});
    fetchResult(url, options);
  ` }], document);
  assert.deepEqual(result.calls.map(c => c.classification), [
    'unmatched-operation', 'absolute-origin-needs-review', 'source-linked', 'non-api-or-unresolved-base',
  ]);
  assert.equal(result.calls[3].method, 'unresolved');
  assert.equal(result.calls[3].migration, 'not-migrated');
  assert.equal(result.calls[3].evidence.length, 0);
});

test('counts generated operations without mistaking request headers or request caches for HTTP calls', () => {
  const result = discoverCalls([{ file: 'web-admin/app/client.ts', text: `
    import {internalRequest as call} from '~/shared/services/http-client/internal-api';
    import {internalFormRequest as upload} from '~/shared/services/http-client/internal-api';
    request.headers.get('Cookie');
    failedRequests.current.delete('key');
    call('get', '/api/views/{pid}', {path:{pid:'view-pid'}});
    upload('post', '/api/upload', {body:{file}});
  ` }], document);
  assert.equal(result.calls.length, 2);
  assert.equal(result.calls[0].matches[0].operationId, 'read');
  assert.equal(result.calls[0].migration, 'generated-operation');
  assert.equal(result.calls[1].matches[0].operationId, 'upload');
  assert.equal(result.calls[1].method, 'post');
  assert.equal(result.calls[1].migration, 'generated-operation');
  const candidates = discoverCalls([{ file: 'web-admin/app/cache.ts', text: `
    import {fetchResult} from '~/shared/services/http-client';
    const apiResolvedCache = new Map<string, object>();
    const apiAlias = apiResolvedCache;
    apiResolvedCache.get(key);
    apiAlias.delete(key);
    fetchResult(endpoint, {method:'get'});
    function uncertain(apiClient: unknown) { apiClient.get(endpoint); }
  ` }], document);
  assert.equal(candidates.calls.length, 4, 'Local collections remain explicit candidates');
  for (const row of candidates.calls.slice(0, 2)) {
    assert.equal(row.classification, 'excluded-local-collection');
    assert.equal(row.migration, 'not-applicable');
    assert.equal(row.evidence[0].constructor, 'Map');
    assert.equal(row.evidence[0].binding, 'apiResolvedCache');
    assert.equal(row.evidence[0].declarationLine, 3);
    assert.deepEqual(row.matches, []);
  }
  assert.equal(candidates.calls[2].migration, 'not-migrated');
  assert.equal(candidates.calls[3].migration, 'not-migrated');
  const shadowed = discoverCalls([{ file: 'web-admin/app/shadow.ts', text: `
    import {Map} from './network-client';
    const apiClient = new Map();
    apiClient.get('/api/views/accessible');
  ` }], document);
  assert.equal(shadowed.calls[0].classification, 'source-linked');
  assert.equal(shadowed.calls[0].matches[0].operationId, 'accessible');
});


test('enumerates finite route branches and retains every missing external or unbounded alternative', () => {
  const result = discoverCalls([{ file: 'web-admin/app/branches.ts', text: `
    import {get} from './http-client';
    const query = enabled ? '?q=one' : '';
    get(\`/api/views/accessible\${query}\`);
    get(enabled ? '/api/views/accessible' : '/api/views/other');
    get(enabled ? '/api/views/accessible' : '/api/missing');
    get(enabled ? '/api/views/accessible' : 'https://external.invalid/api/views');
    get(enabled ? '/api/views/accessible' : computeRoute());
    const bit = enabled ? 'a' : 'b';
    get('/api/' + bit + bit + bit + bit + bit + bit);
    get(\`/api/views/\${bit + bit + bit + bit + bit + bit}\`);
  ` }], document);
  assert.equal(result.calls.length, 7);
  assert.equal(result.calls[0].classification, 'source-linked');
  assert.deepEqual(result.calls[0].branches.map(b => b.route), ['/api/views/accessible?q=one', '/api/views/accessible']);
  assert.equal(result.calls[0].matches.length, 1);
  assert.equal(result.calls[1].classification, 'source-linked');
  assert.deepEqual(result.calls[1].matches.map(m => m.operationId), ['accessible', 'read']);
  assert.equal(result.calls[2].classification, 'unresolved-route-branches');
  assert.deepEqual(result.calls[2].branches.map(b => b.classification), ['source-linked', 'unmatched-operation']);
  assert.equal(result.calls[3].classification, 'unresolved-route-branches');
  assert.equal(result.calls[3].branches[1].classification, 'absolute-origin-needs-review');
  assert.equal(result.calls[4].classification, 'unresolved-route-branches');
  assert.equal(result.calls[4].branches[1].classification, 'unresolved-route');
  assert.equal(result.calls[5].classification, 'unresolved-route');
  assert.equal(result.calls[5].branches[0].route, null);
  assert.equal(result.calls[6].classification, 'unresolved-route', 'Overflow cannot become a guessed path placeholder');
});


test('retains aliased bound and injected network transports without marking shadowed generated calls migrated', () => {
  const result = discoverCalls([{ file: 'web-admin/app/transports.ts', text: `
    import {internalRequest as call, get} from './http-client/internal-api';
    const read = get;
    read('/api/views/accessible');
    const native = globalThis.fetch.bind(globalThis);
    native('/api/views/accessible');
    function injected(fetchImpl: typeof fetch) { fetchImpl('/api/views/accessible'); }
    const fallback = options.fetchImpl ?? globalThis.fetch.bind(globalThis);
    fallback('/api/views/accessible');
    (dependencies.fetchImpl ?? fetch)('/api/views/accessible');
    const operation = call;
    operation('get', '/api/views/{pid}', {path:{pid}});
    function shadow(call: Function) { call('get', '/api/views/{pid}'); }
    function shadowNamedFetch(fetch: Function) { fetch('/api/views/accessible'); }
    let mutable = call;
    mutable = replacement;
    mutable('get', '/api/views/{pid}');
    function overridable(request = call) { request('get', '/api/views/{pid}'); }
  ` }], document);
  assert.equal(result.calls.length, 9);
  for (const row of result.calls.slice(0, 5)) {
    assert.equal(row.classification, 'source-linked');
    assert.equal(row.migration, 'not-migrated');
  }
  assert.equal(result.calls[0].transportResolution, 'get');
  assert.equal(result.calls[1].transportResolution, 'fetch');
  assert.equal(result.calls[2].transportResolution, 'fetch');
  assert.equal(result.calls[3].transportResolution, 'candidate');
  assert.equal(result.calls[4].transportResolution, 'candidate');
  assert.equal(result.calls[5].migration, 'generated-operation');
  assert.equal(result.calls[5].matches[0].operationId, 'read');
  assert.equal(result.calls[6].migration, 'not-migrated');
  assert.equal(result.calls[6].transportResolution, 'name-heuristic-candidate');
  for (const row of result.calls.slice(7)) {
    assert.equal(row.migration, 'not-migrated');
    assert.equal(row.transportResolution, 'candidate');
  }
});


test('keeps replaceable route defaults and mutable bases outside proven source links', () => {
  const result = discoverCalls([{ file: 'web-admin/app/defaults.ts', text: `
    import {get} from './http-client';
    function configured(base = '/api/views') { get(base + '/accessible'); }
    let base = '/api/views';
    get(base + '/accessible');
    class Mutable { base = '/api/views'; load() { get(this.base + '/accessible'); } }
    class Fixed { readonly base = '/api/views'; load() { get(this.base + '/accessible'); } }
    const fixed = '/api/views';
    get(fixed + '/accessible');
  ` }], document);
  assert.equal(result.calls.length, 5);
  assert.equal(result.summary.sourceLinkedOperations, 1);
  assert.equal(result.summary.candidateOperations, 1);
  const defaultsOnly = discoverCalls([{ file: 'web-admin/app/default-only.ts', text: `
    function configured(base = '/api/views') { fetch(base + '/accessible'); }
  ` }], document);
  assert.equal(defaultsOnly.summary.sourceLinkedOperations, 0);
  assert.equal(defaultsOnly.summary.candidateOperations, 1);
  for (const row of result.calls.slice(0, 4)) {
    assert.equal(row.classification, 'route-input-needs-review');
    assert.equal(row.matches[0].operationId, 'accessible', 'Default match remains candidate evidence');
    assert.equal(row.routeInputEvidence.length, 1);
  }
  assert.equal(result.calls[0].routeInputEvidence[0].kind, 'replaceable-parameter-default');
  assert.equal(result.calls[1].routeInputEvidence[0].kind, 'mutable-route-value');
  for (const row of result.calls.slice(4)) {
    assert.equal(row.classification, 'source-linked');
    assert.deepEqual(row.routeInputEvidence, []);
  }
});

test('network call identities survive unrelated layout and generated transport edits while ambiguous duplicates remain explicit', () => {
  const file = 'web-admin/app/identity.ts';
  const original = `import {get} from './http-client';
    export function left() { return get('/api/views/accessible'); }
    export function right() { return get('/api/views/accessible'); }
    export function unknown() { return fetch(route + '/suffix-a'); }
  `;
  const before = discoverCalls([{file,text:original}], document);
  const layout = discoverCalls([{file,text:'// unrelated header\n\n'+original.replace('return get(', '/* note */ return get (')}], document);
  assert.deepEqual(layout.calls.map(row => row.id), before.calls.map(row => row.id));
  assert.notDeepEqual(layout.calls.map(row => row.legacyId), before.calls.map(row => row.legacyId));
  const migrated = original.replace("import {get} from './http-client';", "import {internalRequest} from './http-client/internal-api';")
    .replaceAll("get('/api/views/accessible')", "internalRequest('get','/api/views/accessible',{})");
  const after = discoverCalls([{file,text:migrated}], document);
  assert.deepEqual(after.calls.map(row => row.id), before.calls.map(row => row.id));
  assert.equal(after.calls[0].migration, 'generated-operation');
  assert.notEqual(before.calls[0].id, before.calls[1].id);
  const inserted = discoverCalls([{file,text:original+"\nfunction unrelated(){return fetch('/api/missing');}"}], document);
  assert.deepEqual(inserted.calls.slice(0,3).map(row => row.id), before.calls.map(row => row.id));
  const callback = `import {get} from './http-client'; function load(){ useEffect(()=>get('/api/views/accessible'),[]); }`;
  const wrappedCallback = callback.replace("()=>get('/api/views/accessible')", "((()=>get('/api/views/accessible')))");
  assert.equal(discoverCalls([{file,text:callback}],document).calls[0].id,
    discoverCalls([{file,text:wrappedCallback}],document).calls[0].id);
  const changedLiteral = discoverCalls([{file,text:original.replace('/suffix-a','/suffix-b')}], document);
  assert.notEqual(changedLiteral.calls[2].id,before.calls[2].id);
  const duplicates = discoverCalls([{file,text:"function duplicate(){fetch('/api/missing');fetch('/api/missing');}"}], document).calls;
  assert.equal(new Set(duplicates.map(row => row.id)).size,2);
  assert.ok(duplicates.every(row => row.identityStability==='duplicate-order-dependent' && row.identityCount===2));
  assert.deepEqual(duplicates.map(row => row.identityOccurrence),[1,2]);
  assert.ok(before.calls.every(row => row.identityStability==='unique-anchor' && row.identityVersion===2));
});


test('inventory binds clean source and tool input hashes and refuses dirty snapshots', async () => {
  const fs = await import('node:fs'), os = await import('node:os'), path = await import('node:path');
  const { execFileSync } = await import('node:child_process');
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'internal-api-inventory-'));
  const artifacts = fs.mkdtempSync(path.join(os.tmpdir(), 'internal-api-inventory-output-'));
  const git = (...args) => execFileSync('git', ['-C', root, ...args], { encoding: 'utf8', stdio: ['ignore','pipe','pipe'] }).trim();
  try {
    fs.mkdirSync(path.join(root, 'web-admin/app'), { recursive: true });
    fs.writeFileSync(path.join(root, 'web-admin/tsconfig.json'), JSON.stringify({compilerOptions:{baseUrl:'.',paths:{}}}));
    const file = path.join(root, 'web-admin/app/root.tsx');
    fs.writeFileSync(file, "import {get} from './http-client'; export function root(){return get('/api/views/accessible');}");
    for (const entry of ['routes.ts','entry.client.tsx','entry.server.tsx','server/bff.server.ts']) {
      const target = path.join(root,'web-admin/app',entry);
      fs.mkdirSync(path.dirname(target),{recursive:true}); fs.writeFileSync(target,'export {};');
    }
    git('init','-q'); git('add','.'); git('-c','user.name=Contract fixture','-c','user.email=contract@example.invalid','commit','-qm','fixture');
    const result = inventory(root, document);
    assert.equal(result.sourceClaim,'clean-git-snapshot'); assert.equal(result.sourceCommit,git('rev-parse','HEAD'));
    assert.equal(result.calls.length,1); assert.equal(result.callIdentityVersion,2);
    assert.match(result.scanInputsSha256,/^[a-f0-9]{64}$/);
    assert.equal(Object.keys(result.sourceInputs).length,6);
    assert.ok(result.sourceInputs['web-admin/app/root.tsx'] && result.sourceInputs['web-admin/tsconfig.json']);
    assert.ok(Object.values(result.toolInputHashes).every(value => /^[a-f0-9]{64}$/.test(value)));
    const catalogPath=path.join(artifacts,'catalog.json'),inventoryPath=path.join(artifacts,'inventory.json');
    fs.writeFileSync(catalogPath,JSON.stringify(document));fs.writeFileSync(inventoryPath,JSON.stringify(result));
    const { fileURLToPath }=await import('node:url');
    for (const [script,input] of [['discover.mjs',catalogPath],['type-origins.mjs',inventoryPath]]) {
      const invoke=output=>execFileSync(process.execPath,[fileURLToPath(new URL(script,import.meta.url)),root,input,output],{stdio:'pipe'});
      const existing=path.join(artifacts,script+'.existing.json'),target=path.join(artifacts,script+'.target');
      const linked=path.join(artifacts,script+'.link.json'),fresh=path.join(artifacts,script+'.fresh.json');
      fs.writeFileSync(existing,'existing evidence');
      assert.throws(()=>invoke(existing),error=>error.status===1);
      assert.equal(fs.readFileSync(existing,'utf8'),'existing evidence');
      fs.writeFileSync(target,'protected symlink target');fs.symlinkSync(target,linked);
      assert.throws(()=>invoke(linked),error=>error.status===1);
      assert.equal(fs.readFileSync(target,'utf8'),'protected symlink target');assert.ok(fs.lstatSync(linked).isSymbolicLink());
      invoke(fresh);
      assert.equal(JSON.parse(fs.readFileSync(fresh,'utf8')).sourceCommit,result.sourceCommit);
      if(process.platform!=='win32')assert.equal(fs.statSync(fresh).mode&0o777,0o600);
    }
    fs.appendFileSync(file,'\n// uncommitted edit');
    assert.throws(() => inventory(root,document), /clean source checkout/);
  } finally { fs.rmSync(root,{recursive:true,force:true});fs.rmSync(artifacts,{recursive:true,force:true}); }
});
