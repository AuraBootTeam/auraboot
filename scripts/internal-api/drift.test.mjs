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
