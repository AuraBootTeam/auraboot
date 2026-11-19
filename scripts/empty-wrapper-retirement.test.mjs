import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync, mkdtempSync, writeFileSync, existsSync, chmodSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {fileURLToPath} from 'node:url';
import {spawnSync} from 'node:child_process';

const root=fileURLToPath(new URL('../',import.meta.url));
const profiles=JSON.parse(readFileSync(new URL('./dev/plugin-import-profiles.json',import.meta.url),'utf8'));

test('the legacy full demo no longer selects either empty wrapper and retains all quarry members',()=>{
  for(const names of Object.values(profiles)) {
    assert.ok(!names.includes('quarry-solution'));
    assert.ok(!names.includes('marketing-crm-demo'));
  }
  assert.equal(profiles['quarry-delivery'].length,8);
  for(const name of profiles['quarry-delivery']) assert.ok(profiles['enterprise-demo'].includes(name),name);
});

for(const name of ['quarry-solution','marketing-crm-demo','pcba-warehouse']) {
  test(`explicit ${name} names, identities and legacy paths reject before any network call`,()=>{
    const fixture=mkdtempSync(join(tmpdir(),'retired-wrapper-'));
    const curl=join(fixture,'curl');const marker=join(fixture,'network-called');
    writeFileSync(curl,`#!/bin/sh\ntouch '${marker}'\nexit 99\n`);chmodSync(curl,0o755);
    for(const selection of [name,`com.auraboot.${name}`,`/old/checkout/${name}/`]) {
      const result=spawnSync('bash',['scripts/import-plugins.sh','--backend-url=http://127.0.0.1:1',selection],{
        cwd:root,encoding:'utf8',env:{...process.env,PATH:`${fixture}:${process.env.PATH}`},
      });
      assert.equal(result.status,2,result.stderr);
      assert.match(result.stderr,/retired plugin/);
      assert.match(result.stderr,name==='quarry-solution'?/quarry-delivery/:name==='pcba-warehouse'?/inventory/:/showcase\/media\/marketing-crm/);
      assert.equal(existsSync(marker),false,'No login or import request may precede retirement rejection');
    }
  });
}
