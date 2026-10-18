import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createResponseShapeValidator } from './response-shape.mjs';

function fixture() {
  return { openapi: '3.1.0', info: { title: 'Response shape', version: '1' }, paths: {
    '/api/items': { get: { operationId: 'items', responses: {
      200: { description: 'OK', content: { 'application/json': { schema: { $ref: '#/components/schemas/Result' } } } },
      422: { $ref: '#/components/responses/Invalid' },
    } } },
  }, components: {
    schemas: { Result: { type: 'object', required: ['items', 'count'], properties: {
      count: { type: 'integer' }, items: { type: 'array', items: { $ref: '#/components/schemas/Item' } },
    } }, Item: { type: 'object', required: ['name'], properties: { name: { type: ['string', 'null'] } } } },
    responses: { Invalid: { description: 'Invalid', content: { 'application/json': { schema: { type: 'object', required: ['code'], properties: { code: { type: 'string' } } } } } } },
  } };
}
const response = payload => ({ method: 'get', path: '/api/items', status: 200, contentType: 'application/json; charset=utf-8', payload });

test('response shape validates nested references required fields nullability arrays and scalar types', () => {
  const validate = createResponseShapeValidator(fixture());
  assert.equal(validate(response({ items: [{ name: null }], count: 0 })).valid, true);
  for (const payload of [{ items: [{}], count: 1 }, { items: null, count: 0 }, { items: [], count: '0' }, { items: [{ name: false }], count: 1 }]) {
    const result = validate(response(payload));
    assert.equal(result.valid, false);
    assert.ok(result.errors.length);
  }
});

test('response shape refuses undeclared operations statuses media and validates error response references', () => {
  const legacy = fixture(); legacy.openapi = '3.0.3';
  assert.throws(() => createResponseShapeValidator(legacy), /requires OpenAPI 3.1/);
  const validate = createResponseShapeValidator(fixture());
  assert.throws(() => validate({ ...response({}), path: '/api/unknown' }), /Undeclared response operation/);
  assert.throws(() => validate({ ...response({}), status: 500 }), /Undeclared response status/);
  assert.throws(() => validate({ ...response({}), contentType: 'text/html' }), /Expected a JSON response/);
  assert.equal(validate({ ...response({ code: '35000' }), status: 422 }).valid, true);
  assert.equal(validate({ ...response({ code: 35000 }), status: 422 }).valid, false);
});

test('response shape detects stale nullable metadata without claiming format or business acceptance', () => {
  const document = fixture();
  document.components.schemas.Item.properties.name.type = 'string';
  const result = createResponseShapeValidator(document)(response({ items: [{ name: null }], count: 0 }));
  assert.equal(result.valid, false);
  assert.equal(result.claim, 'json-response-structure-only');
  assert.equal(result.formatValidation, 'not-performed');
});
