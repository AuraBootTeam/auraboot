import { test } from 'node:test';
import assert from 'node:assert/strict';
import { discoverCalls, matchOperation } from './discover.mjs';
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
