import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {spawnSync} from 'node:child_process';

test('the native drift preflight fails for stale version and silently added backdated SQL',()=>{
 const root=fs.mkdtempSync(path.join(os.tmpdir(),'migration-floor-'));
 try {
  const scripts=path.join(root,'scripts/db'),migrations=path.join(root,'platform/src/main/resources/db/migration/core');
  fs.mkdirSync(scripts,{recursive:true});fs.mkdirSync(migrations,{recursive:true});
  const floor=path.join(scripts,'migration-version-floor.txt');
  fs.writeFileSync(floor,'version=20261113010000\ncount=1\n');
  fs.writeFileSync(path.join(migrations,'V20261113010000__current.sql'),'SELECT 1;');
  const run=()=>spawnSync('bash',[new URL('./check-migration-version-floor.sh',import.meta.url).pathname],{env:{...process.env,AURA_CORE_ROOT:root},encoding:'utf8'});
  assert.equal(run().status,0);
  fs.writeFileSync(path.join(migrations,'V20261105001000__backdated.sql'),'SELECT 2;');
  assert.equal(run().status,1,'Adding an older SQL without the release inventory must fail');
  fs.writeFileSync(floor,'version=20261112010000\ncount=2\n');assert.equal(run().status,1);
  fs.writeFileSync(floor,'version=20261113010000\ncount=2\n');assert.equal(run().status,0);
  fs.writeFileSync(floor,'version=invalid\ncount=2\n');assert.equal(run().status,2);
 }finally{fs.rmSync(root,{recursive:true,force:true});}
});
