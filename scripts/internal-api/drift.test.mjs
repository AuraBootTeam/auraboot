import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { compareCaptured } from './drift.mjs';
const document = {openapi:'3.1.0',info:{title:'Test',version:'1'},paths:{'/api/read':{get:{operationId:'read',responses:{200:{description:'OK'}}}}}};
function fixture(run) {
  const root=fs.mkdtempSync(path.join(os.tmpdir(),'internal-api-drift-'));
  const input={expected:path.join(root,'expected.json'),observed:path.join(root,'actual.json'),receipt:path.join(root,'receipt.json')};
  fs.writeFileSync(input.expected,JSON.stringify(document));fs.writeFileSync(input.observed,JSON.stringify(document));
  try {run(input);} finally {fs.rmSync(root,{recursive:true,force:true});}
}
test('captured drift compares semantic contracts and refuses receipt reuse', () => fixture(input => {
  const actual={...document,servers:[{url:'http://runtime.invalid'}]};fs.writeFileSync(input.observed,JSON.stringify(actual));
  assert.equal(compareCaptured(input).operations,1);assert.equal(JSON.parse(fs.readFileSync(input.receipt)).claim,'captured-snapshot-drift-only');
  assert.throws(()=>compareCaptured(input),/fresh/);
}));
test('captured drift never creates success evidence for an empty or changed producer document', () => fixture(input => {
  fs.writeFileSync(input.observed,JSON.stringify({...document,paths:{}}));assert.throws(()=>compareCaptured(input));assert.equal(fs.existsSync(input.receipt),false);
  const changed=structuredClone(document);changed.paths['/api/read'].get.responses[200].description='changed';
  fs.writeFileSync(input.observed,JSON.stringify(changed));assert.throws(()=>compareCaptured(input),/drift/);assert.equal(fs.existsSync(input.receipt),false);
}));

test('core release projection permits product additions but rejects core schema parameter and security drift', () => {
  const core = structuredClone(document);
  core.security = [{ bearer: [] }]; core.tags = [{name:'Core'}];
  core.paths['/api/read'].get.tags = ['Core'];
  core.paths['/api/read'].get.parameters = [{$ref:'#/components/parameters/Query'}];
  core.paths['/api/read'].get.responses[200].content = {'application/json': {schema: {$ref:'#/components/schemas/Record'}}};
  core.components = {
    schemas: {Record: {type:'object',required:['pid'],properties:{pid:{type:'string'},label:{type:['string','null']}}}},
    parameters: {Query: {name:'keyword',in:'query',schema:{type:'string'}}},
    securitySchemes: {bearer: {type:'http',scheme:'bearer'}},
  };
  const product = structuredClone(core);
  product.paths['/api/product'] = {get:{operationId:'productRead',tags:['Product'],responses:{200:{$ref:'#/components/responses/ProductResponse'}}}};
  product.tags.push({name:'Product'});
  product.components.responses = {ProductResponse:{description:'Product',content:{'application/json':{schema:{$ref:'#/components/schemas/ProductRecord'}}}}};
  product.components.schemas.ProductRecord = {type:'object',properties:{extra:{type:'string'}}};
  product.components.parameters.Unused = {name:'unusedProductQuery',in:'query',schema:{$ref:'#/components/schemas/ProductRecord'}};
  fixture(input => {
    fs.writeFileSync(input.expected,JSON.stringify(core)); fs.writeFileSync(input.observed,JSON.stringify(product));
    const result = compareCaptured({...input,mode:'core-operations'});
    assert.equal(result.claim,'captured-core-operation-closure-drift-only');
    assert.equal(result.coreOperations,1); assert.equal(result.productAdditionalOperations,1);
    assert.equal(result.productContractAcceptance,'not-assessed');
    assert.throws(() => compareCaptured({...input,receipt:path.join(path.dirname(input.receipt),'exact.json')}),/drift/);
  });
  const mutations = [
    value => { delete value.paths['/api/read']; },
    value => { value.components.schemas.Record.properties.pid.type = 'number'; },
    value => { value.components.schemas.Record.required = []; },
    value => { value.components.schemas.Record.properties.label.type = 'string'; },
    value => { value.components.schemas.Record.properties.id = {type:'integer',format:'int64'}; },
    value => { value.components.parameters.Query.name = 'wrongKeyword'; },
    value => { value.components.securitySchemes.bearer.scheme = 'basic'; },
    value => { delete value.components.securitySchemes.bearer; },
    value => { value.paths['/api/product'].get.responses[200].$ref = '#/components/responses/Missing'; },
  ];
  for (const mutate of mutations) fixture(input => {
    const changed = structuredClone(product); mutate(changed);
    fs.writeFileSync(input.expected,JSON.stringify(core)); fs.writeFileSync(input.observed,JSON.stringify(changed));
    assert.throws(() => compareCaptured({...input,mode:'core-operations'}));
    assert.equal(fs.existsSync(input.receipt),false,'No successful receipt may survive a core incompatibility');
  });
});
