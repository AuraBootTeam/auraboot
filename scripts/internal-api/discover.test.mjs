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
});
