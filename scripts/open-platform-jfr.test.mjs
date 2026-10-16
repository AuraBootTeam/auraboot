import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {spawnSync} from 'node:child_process';

// contract / hermetic / blocking-commit / unit: process identity and fail-closed
// collection. Fake Docker is not evidence of actual JDK attachment or API SLOs.
const root = fs.mkdtempSync(path.join(os.tmpdir(), 'open-platform-jfr-'));
const script = new URL('./ci/collect-open-platform-jfr.sh', import.meta.url).pathname;
const ref = 'a'.repeat(40), jdk = `temurin@sha256:${'b'.repeat(64)}`;
try {
  const binary = path.join(root, 'docker');
  fs.writeFileSync(binary, `#!/usr/bin/env python3
import json, os, sys
a=sys.argv[1:]
with open(os.environ['JFR_TEST_CALLS'],'a') as f: f.write(json.dumps(a)+'\\n')
mode=os.environ.get('JFR_TEST_MODE','')
if a[0]=='inspect': print(('false' if mode=='stopped' else 'true')+' job '+('c'*40 if mode=='wrong-source' else 'a'*40))
elif a[0]=='exec':
 if 'chmod' in a:
  if mode=='chmod-failed': sys.exit(43)
 elif a[-2:]==['id','-u'] or a[-2:]==['id','-g']: print('1000')
 else: print('7\\n8' if mode=='multiple-java' else '7')
elif a[0]=='run':
 if mode=='attach-failed' and 'jcmd' in a: sys.exit(42)
 if 'JFR.start' in a or 'JFR.stop' in a:
  print('Could not access recording configuration' if mode=='jvm-rejected' else ('Started recording 1' if 'JFR.start' in a else 'Stopped recording 1'))
 if 'summary' in a: print(' jdk.InitialEnvironmentVariable '+('1' if mode=='sensitive' else '0')+' 0\\n jdk.InitialSystemProperty 0 0')
elif a[0]=='cp' and a[1].startswith('open-platform-job-app:'):
 open(a[2],'wb').write(b'fixture-recording')
`, {mode:0o755});
  let cases = 0;
  const run = (action, mode='', overrides={}) => {
    const artifacts=path.join(root, `artifacts-${++cases}`);fs.mkdirSync(artifacts);fs.mkdirSync(path.join(artifacts,'logs'));
    const calls=path.join(artifacts,'calls');
    const result=spawnSync('bash',[script,action,overrides.app??'open-platform-job-app','job',ref,overrides.jdk??jdk,artifacts],{encoding:'utf8',env:{...process.env,PATH:`${root}:${process.env.PATH}`,JFR_TEST_MODE:mode,JFR_TEST_CALLS:calls}});
    return {...result,artifacts,calls:fs.existsSync(calls)?fs.readFileSync(calls,'utf8').trim().split('\n').map(JSON.parse):[]};
  };
  for (const action of ['start','collect']) {
    const result=run(action);assert.equal(result.status,0,result.stderr);
    const attach=result.calls.find(a=>a.includes('jcmd'));
    assert.ok(attach.includes('--network')&&attach.includes('none'));
    assert.ok(attach.includes('--pid=container:open-platform-job-app'));
    assert.equal(attach[attach.indexOf('--user')+1],'1000:1000');
    assert.equal(attach[attach.indexOf('jcmd')+1],'7');
    assert.equal(attach[attach.indexOf('jcmd')-1],jdk);
    if(action==='start') assert.ok(result.calls.some(a=>a.join(' ')==='exec --user 0 open-platform-job-app chmod 0644 /tmp/open-platform-slo.jfc'));
    assert.equal(result.calls.some(a=>a.some(s=>s.includes('VM.system_properties')||s.includes('Config.Env'))),false);
  }
  for (const mode of ['stopped','wrong-source','multiple-java']) {
    const result=run('start',mode);assert.notEqual(result.status,0);assert.equal(result.calls.some(a=>a.includes('jcmd')),false);
  }
  for (const overrides of [{app:'someone-else-app'},{jdk:'temurin:21-jdk'}]) {
    const result=run('start','',overrides);assert.notEqual(result.status,0);assert.equal(result.calls.length,0);
  }
  for(const action of ['start','collect']) {
    const result=run(action,'attach-failed');assert.equal(result.status,42);
    assert.equal(fs.existsSync(path.join(result.artifacts,'open-platform-slo.jfr')),false);
  }
  const sensitive=run('collect','sensitive');assert.notEqual(sensitive.status,0);assert.match(sensitive.stderr,/unexpected sensitive JFR event/);
  for(const action of ['start','collect']) {
    const rejected=run(action,'jvm-rejected');assert.equal(rejected.status,2);assert.match(rejected.stderr,/JVM rejected JFR/);
    assert.equal(fs.existsSync(path.join(rejected.artifacts,'open-platform-slo.jfr')),false);
  }
  const unreadable=run('start','chmod-failed');assert.equal(unreadable.status,43);assert.equal(unreadable.calls.some(a=>a.includes('jcmd')),false);
  console.log(`open-platform JFR identity/privacy/collection: ${cases} hermetic cases PASS`);
} finally {fs.rmSync(root,{recursive:true,force:true});}
