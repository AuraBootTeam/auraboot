import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync,writeFileSync,readFileSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {hydrateCapsuleOverrides} from './web-shell-capsule.mjs';
function fixture(t,overrides) {
  const root=mkdtempSync(join(tmpdir(),'web-capsule-'));
  t.after(()=>rmSync(root,{recursive:true,force:true}));
  writeFileSync(join(root,'package.json'),JSON.stringify({name:'fixture',pnpm:{onlyBuiltDependencies:['esbuild']}}));
  writeFileSync(join(root,'pnpm-overrides.json'),JSON.stringify(overrides));
  writeFileSync(join(root,'pnpm-lock.yaml'),'lockfileVersion: 9\noverrides:\n  vite: 8.1.6\n');
  return root;
}
test('hydrates the stripped map without changing lock bytes or other pnpm settings',t=>{
  const root=fixture(t,{vite:'8.1.6','body-parser@1':'1.20.9'});
  const lock=readFileSync(join(root,'pnpm-lock.yaml'));
  hydrateCapsuleOverrides(root);
  assert.deepEqual(JSON.parse(readFileSync(join(root,'package.json'))).pnpm,{onlyBuiltDependencies:['esbuild'],overrides:{vite:'8.1.6','body-parser@1':'1.20.9'}});
  assert.deepEqual(readFileSync(join(root,'pnpm-lock.yaml')),lock);
  const first=readFileSync(join(root,'package.json'));hydrateCapsuleOverrides(root);
  assert.deepEqual(readFileSync(join(root,'package.json')),first);
});
for(const invalid of [null,[],{vite:8}]) test('rejects invalid capsule overrides '+JSON.stringify(invalid),t=>{
  const root=fixture(t,invalid);const manifest=readFileSync(join(root,'package.json'));
  assert.throws(()=>hydrateCapsuleOverrides(root),/Invalid capsule override map/);
  assert.deepEqual(readFileSync(join(root,'package.json')),manifest);
});
