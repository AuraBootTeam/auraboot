import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import { existsSync, mkdtempSync, readFileSync, realpathSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
const helper=readFileSync(new URL('./lib/workspace-control.sh',import.meta.url),'utf8');
function fixture(t) {
 const root=realpathSync(mkdtempSync(join(tmpdir(),'product-control-')));t.after(()=>rmSync(root,{recursive:true,force:true}));
 const git=(...args)=>execFileSync('git',['-C',root,...args],{encoding:'utf8'}).trim();
 writeFileSync(join(root,'.gitignore'),'feature/\n.workspace/\nreceived.json\n');
 writeFileSync(join(root,'runtime.yaml'),'repos: {}');
 writeFileSync(join(root,'aura'),"import {writeFileSync} from 'node:fs';writeFileSync(process.env.AURA_WORKSPACE_ROOT+'/received.json',JSON.stringify({args:process.argv.slice(2),state:process.env.AURA_WORKSPACE_STATE_DIR}));if (process.argv.slice(2).join(' ') !== 'control require 1') process.exitCode=3;\n");
 git('init','-q','-b','main');git('config','user.name','Test');git('config','user.email','test@example.com');git('add','.');git('commit','-qm','fixture');
 const feature=join(root,'feature');git('worktree','add','-b','codex/product',feature);
 writeFileSync(join(feature,'aura'),"throw new Error('frozen legacy controller must not execute');\n");
 const run=()=>spawnSync('bash',['-c',`set -eu\n${helper}\nREPO_ROOT="$1"\naura_bind_workspace_control "$1"\nprintf '%s\\n' "$WORKSPACE" "$DEV" "$REPO_ROOT"`, '--',feature],{encoding:'utf8',env:{...process.env,AURA_WORKSPACE_ROOT:feature,AURA_WORKSPACE_STATE_DIR:join(root,'.workspace')}});
 return {root,feature,run};
}
test('product helper selects canonical controller while preserving frozen product root',t=>{
 const {root,feature,run}=fixture(t);const r=run();assert.equal(r.status,0,r.stderr);
 assert.deepEqual(r.stdout.trim().split('\n'),[root,join(root,'aura'),feature]);
 assert.deepEqual(JSON.parse(readFileSync(join(root,'received.json'))),{args:['control','require','1'],state:join(root,'.workspace')});
 assert.equal(existsSync(join(feature,'.workspace')),false);
});
test('old canonical controller is refused before a product runtime can start',t=>{
 const {root,run}=fixture(t);writeFileSync(join(root,'aura'),"process.exitCode=2;console.error('unknown control command');\n");
 const r=run();assert.notEqual(r.status,0);assert.match(r.stderr,/upgrade canonical Workspace/);
 assert.equal(existsSync(join(root,'.workspace')),false);
});
test('missing canonical controller is refused without using the feature executable',t=>{
 const {root,run}=fixture(t);rmSync(join(root,'aura'));const r=run();assert.notEqual(r.status,0);assert.match(r.stderr,/canonical Workspace controller is unavailable/);
});
test('all four edited golden launchers bind control before allocation or gate work',()=>{
 for(const name of ['oss-golden-stack.sh','oss-e2e-gate-run.sh','hifi-golden-gate-run.sh','aurabot-scenario-golden-run.sh']){
  const s=readFileSync(new URL('./'+name,import.meta.url),'utf8');assert.match(s,/aura_bind_workspace_control .*\|\| exit 2/);
 }
});
