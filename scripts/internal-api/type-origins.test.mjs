import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { execFileSync } from 'node:child_process';
import { discoverCalls } from './discover.mjs';
import { traceCallTypes, typeInventory } from './type-origins.mjs';
import { sha256 } from './contract.mjs';

function fixture() {
  const root = fs.mkdtempSync(path.join(os.tmpdir(),'internal-api-type-origins-'));
  const sources = {
    'web-admin/tsconfig.json': JSON.stringify({compilerOptions:{strict:true,target:'es2022',module:'es2022',
      moduleResolution:'bundler',baseUrl:'.',paths:{'~/*':['app/*']}}}),
    'web-admin/app/http-client.ts': 'export declare function get<T>(url:string, options?:unknown): Promise<{code:number;data:T}>;',
    'web-admin/app/generated/internal-api/schema.d.ts': 'export interface components { schemas: { Person: {pid:string} } }',
    'web-admin/app/models.ts': `import type {components} from './generated/internal-api/schema';
      export type GeneratedPerson=components['schemas']['Person'];
      export interface Leaf { value: string; parent?: Model }
      export interface Model { nested: Leaf[]; person: GeneratedPerson | null }
      export type Response=Model[];`,
    'web-admin/app/reexports.ts': `export type {Response as Renamed} from './models';`,
    'web-admin/app/read.ts': `import {get} from './http-client'; import type {Renamed} from '~/reexports';
      export function read() { return get<Renamed>('/api/person',{body: {pid:'p'} as unknown as Record<string,any>}); }
      export function unchecked() { return get<MissingModel>('/api/person'); }
      export function raw() { return fetch('/api/person'); }`,
  };
  for (const [file,text] of Object.entries(sources)) {
    fs.mkdirSync(path.dirname(path.join(root,file)),{recursive:true}); fs.writeFileSync(path.join(root,file),text);
  }
  const document = {openapi:'3.1.0',info:{title:'Fixture',version:'1'},paths:{'/api/person':{get:{operationId:'read',responses:{200:{description:'OK'}}}}}};
  const found = discoverCalls([{file:'web-admin/app/read.ts',text:sources['web-admin/app/read.ts']}],document);
  return {root,sources,inventory:{sourceCommit:'a'.repeat(40),sourceInputs:Object.fromEntries(Object.entries(sources).map(([f,t])=>[f,sha256(t)])),...found}};
}

test('type provenance follows real compiler aliases reexports nested declarations and standard library types without marking legacy calls migrated', () => {
  const {root,inventory} = fixture();
  try {
    const result = traceCallTypes(root,inventory);
    assert.equal(result.calls.length,3);
    const [typed,unchecked,raw] = result.calls;
    assert.equal(typed.migration,'not-migrated');
    assert.equal(typed.explicitTypeArguments[0].syntax,'Renamed');
    assert.match(typed.result.display,/Promise/);
    assert.equal(typed.assertions.length,2);
    assert.ok(typed.assertions.some(a=>a.asserted.boundaries.includes('unknown')));
    assert.ok(typed.assertions.some(a=>a.asserted.display.includes('any')));
    const names = new Set(result.declarations.map(d=>d.name));
    for (const name of ['Response','Model','Leaf','GeneratedPerson','components','Promise']) assert.ok(names.has(name),name);
    assert.ok(result.declarations.some(d=>d.category==='generated-contract'));
    assert.ok(result.declarations.some(d=>d.category==='dependency-declaration' && d.file.includes('lib.es')));
    assert.ok(result.declarations.some(d=>d.name==='Leaf' && d.references.some(r=>r.syntax==='Model')));
    assert.ok(unchecked.explicitTypeArguments[0].boundaries.includes('any'));
    assert.equal(unchecked.explicitTypeArguments[0].references[0].status,'unresolved-symbol');
    assert.match(raw.result.display,/Promise<Response>/);
    assert.ok(raw.result.origins.some(id=>result.declarations.find(d=>d.id===id)?.name==='Response'));
    assert.equal(raw.explicitTypeArguments.length,0);
    assert.equal(result.claim,'compiler-associated-declaration-provenance-only');
    assert.ok(Object.keys(result.compilerInputs).some(f=>f.includes('lib.dom.d.ts')));
    assert.match(result.compilerInputsSha256,/^[a-f0-9]{64}$/);
  } finally { fs.rmSync(root,{recursive:true,force:true}); }
});

test('type provenance refuses stale source call bindings dirty or mismatched inventory and preserves unknown type boundaries', () => {
  const {root,inventory,sources} = fixture();
  const git=(...args)=>execFileSync('git',['-C',root,...args],{encoding:'utf8',stdio:['ignore','pipe','pipe']}).trim();
  try {
    const stale=structuredClone(inventory); stale.calls[0].callSha256='0'.repeat(64);
    assert.throws(()=>traceCallTypes(root,stale),/Call binding mismatch/);
    const changed=structuredClone(inventory); changed.sourceInputs['web-admin/app/models.ts']='0'.repeat(64);
    assert.throws(()=>traceCallTypes(root,changed),/Inventory input mismatch/);
    git('init','-q'); git('add','.'); git('-c','user.name=Contract fixture','-c','user.email=contract@example.invalid','commit','-qm','fixture');
    assert.throws(()=>typeInventory(root,inventory),/matching clean source/);
    inventory.sourceCommit=git('rev-parse','HEAD');
    const result=typeInventory(root,inventory);
    assert.equal(result.sourceClaim,'clean-git-snapshot');
    assert.ok(Object.values(result.toolInputHashes).every(h=>/^[a-f0-9]{64}$/.test(h)));
    fs.appendFileSync(path.join(root,'web-admin/app/models.ts'),'\n// dirty');
    assert.throws(()=>typeInventory(root,inventory),/matching clean source/);
    fs.writeFileSync(path.join(root,'web-admin/app/models.ts'),sources['web-admin/app/models.ts']);
  } finally { fs.rmSync(root,{recursive:true,force:true}); }
});
