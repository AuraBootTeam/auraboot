import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { chmodSync, mkdirSync, mkdtempSync, rmSync, symlinkSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const cli = fileURLToPath(new URL('./application-cli.mjs', import.meta.url));

async function fixture(t, kind) {
  const root = mkdtempSync(join(tmpdir(), 'release-token-security-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const tokenFile = join(root, 'token');
  writeFileSync(tokenFile, 'owned-test-token\n', { mode: 0o600 });
  const plan = { applicationCode: 'fixture-app', tenantId: '7',
    expectedCurrentReleaseId: '01M00000000000000000000001', expectedVersion: 1,
    targetReleaseId: '01M00000000000000000000002', targetDigest: `sha256:${'a'.repeat(64)}` };
  const planFile = join(root, 'plan.json');
  writeFileSync(planFile, JSON.stringify(plan));
  let requests = 0;
  const server = createServer((request, response) => {
    requests++;
    response.setHeader('content-type', 'application/json');
    if (request.headers.authorization !== 'Bearer owned-test-token') {
      response.statusCode = 401;
      response.end(JSON.stringify({ code: 'UNAUTHORIZED', message: 'Unexpected test identity' }));
      return;
    }
    response.end(JSON.stringify({ code: '0', data: { tenantId: '7', releaseId: plan.targetReleaseId,
      releaseDigest: plan.targetDigest, status: 'active', version: 2 } }));
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => new Promise(resolve => server.close(resolve)));
  const hook = join(root, 'swap.mjs');
  if (kind === 'permissive') chmodSync(tokenFile, 0o644);
  if (kind === 'directory') { rmSync(tokenFile); mkdirSync(tokenFile); }
  if (kind === 'symlink' || kind === 'swapped') {
    const foreign = join(root, 'foreign');
    writeFileSync(foreign, 'foreign-test-token', { mode: 0o600 });
    if (kind === 'symlink') { rmSync(tokenFile); symlinkSync(foreign, tokenFile); }
    else writeFileSync(hook, `
      import fs from 'node:fs';
      import { syncBuiltinESMExports } from 'node:module';
      const target=${JSON.stringify(tokenFile)}, foreign=${JSON.stringify(foreign)};
      const open=fs.openSync, stat=fs.statSync, fstat=fs.fstatSync;
      let selected, swapped=false;
      const swap=()=>{if(!swapped){swapped=true;fs.renameSync(target,target+'.original');fs.symlinkSync(foreign,target);}};
      fs.openSync=(file,...args)=>{const fd=open(file,...args);if(file===target)selected=fd;return fd;};
      fs.statSync=(file,...args)=>{const value=stat(file,...args);if(file===target)swap();return value;};
      fs.fstatSync=(fd,...args)=>{const value=fstat(fd,...args);if(fd===selected)swap();return value;};
      syncBuiltinESMExports();
    `);
  }
  const args = [...(kind === 'swapped' ? ['--import', hook] : []), cli, 'upgrade-tenant',
    '--upgrade-plan', planFile, '--token-file', tokenFile,
    '--base-url', `http://127.0.0.1:${server.address().port}`, '--receipt', join(root, 'receipt.json')];
  const result = await new Promise((resolve, reject) => {
    const child = spawn(process.execPath, args, { timeout: 10_000, env: { ...process.env, AURA_RELEASE_TOKEN: '' } });
    let output = '';
    child.stdout.on('data', chunk => { output += chunk; });
    child.stderr.on('data', chunk => { output += chunk; });
    child.once('error', reject);
    child.once('close', (code, signal) => resolve({ code, signal, output }));
  });
  return { ...result, requests };
}

for (const kind of ['regular', 'swapped']) {
  test(`release CLI reads checked token descriptor: ${kind}`, async t => {
    const result = await fixture(t, kind);
    assert.equal(result.code, 0, result.output);
    assert.equal(result.requests, 1);
    assert.doesNotMatch(result.output, /owned-test-token|foreign-test-token/);
  });
}
for (const kind of ['permissive', 'directory', 'symlink']) {
  test(`release CLI rejects ${kind} token before HTTP`, async t => {
    const result = await fixture(t, kind);
    assert.notEqual(result.code, 0);
    assert.equal(result.signal, null);
    assert.equal(result.requests, 0);
    assert.match(result.output, /permissions|regular file|ELOOP/);
  });
}
