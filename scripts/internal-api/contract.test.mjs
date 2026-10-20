import { test } from 'node:test';
import assert from 'node:assert/strict';
import { assertSameContract, canonicalJson, normalizeDocument, selectOperations, validateDocument } from './contract.mjs';

function fixture() {
  return {
    openapi: '3.1.0', info: { title: 'Internal API', version: '1' }, servers: [{ url: 'http://runtime.invalid:6681' }],
    paths: {
      '/api/items/{pid}': { parameters: [{ name: 'pid', in: 'path', required: true, schema: { type: 'string' } }],
        get: { operationId: 'readItem', responses: { 200: { description: 'OK', content: {
          'application/json': { schema: { $ref: '#/components/schemas/Item' } },
        } } }, security: [{ bearerAuth: [] }] } },
      '/api/items': { post: { operationId: 'createItem', requestBody: { required: true,
        content: { 'application/json': { schema: { $ref: '#/components/schemas/CreateInput' } } } },
        responses: { 201: { description: 'Created' } } } },
    },
    components: { securitySchemes: { bearerAuth: { type: 'http', scheme: 'bearer' } }, schemas: {
      Item: { type: 'object', required: ['pid'], properties: { pid: { type: 'string' },
        nested: { $ref: '#/components/schemas/Nested' } } },
      Nested: { type: ['object', 'null'], properties: { parent: { $ref: '#/components/schemas/Item' } } },
      CreateInput: { type: 'object', required: ['name'], properties: { name: { type: 'string', enum: ['A', 'B'] } } },
    } },
  };
}

test('normalizes only ordering and runtime origins without changing schema semantics', () => {
  const source = fixture();
  const copy = structuredClone(source);
  copy.servers = [{ url: 'https://other.invalid' }];
  assert.equal(assertSameContract(source, copy).length, 64);
  assert.equal(source.servers[0].url, 'http://runtime.invalid:6681');
  assert.deepEqual(normalizeDocument(source).components.schemas.Nested.type, ['object', 'null']);
  assert.equal(canonicalJson(normalizeDocument(source)), canonicalJson(normalizeDocument(normalizeDocument(source))));
});

test('rejects empty, duplicate, dangling and missing-path-parameter contracts', () => {
  const empty = fixture(); empty.paths = {};
  assert.throws(() => validateDocument(empty), /surface is empty/);
  const duplicate = fixture(); duplicate.paths['/api/items'].post.operationId = 'readItem';
  assert.throws(() => validateDocument(duplicate), /Duplicate operationId/);
  const dangling = fixture(); delete dangling.components.schemas.Nested;
  assert.throws(() => validateDocument(dangling), /Unresolved reference/);
  const parameter = fixture(); parameter.paths['/api/items/{pid}'].parameters[0].required = false;
  assert.throws(() => validateDocument(parameter), /Missing required path parameter/);
});

test('does not normalize away field, required, enum, nullability, security or operation drift', () => {
  const mutations = [
    d => { delete d.components.schemas.Item.properties.pid; },
    d => { d.components.schemas.Item.required = []; },
    d => { d.components.schemas.CreateInput.properties.name.enum = ['A']; },
    d => { d.components.schemas.Nested.type = 'object'; },
    d => { d.paths['/api/items/{pid}'].get.security = []; },
    d => { delete d.paths['/api/items']; },
  ];
  for (const mutate of mutations) {
    const changed = fixture(); mutate(changed);
    assert.throws(() => assertSameContract(fixture(), changed), /contract drift/);
  }
});

test('explicit selection preserves recursive schemas and denies missing or repeated operations', () => {
  const selectors = [{ method: 'get', path: '/api/items/{pid}' }];
  const selected = selectOperations(fixture(), selectors);
  assert.deepEqual(Object.keys(selected.paths), ['/api/items/{pid}']);
  assert.deepEqual(Object.keys(selected.components.schemas), ['Item', 'Nested']);
  assert.equal(selected.components.securitySchemes.bearerAuth.scheme, 'bearer');
  assert.throws(() => selectOperations(fixture(), []), /selectors are empty/);
  assert.throws(() => selectOperations(fixture(), [...selectors, ...selectors]), /Duplicate operation selector/);
  assert.throws(() => selectOperations(fixture(), [{ method: 'delete', path: '/api/items' }]), /absent/);
});

test('external references fail closed instead of reading arbitrary remote contracts', () => {
  const source = fixture();
  source.components.schemas.Nested = { $ref: 'https://external.invalid/schema.json' };
  assert.throws(() => normalizeDocument(source), /bundled local references/);
});
