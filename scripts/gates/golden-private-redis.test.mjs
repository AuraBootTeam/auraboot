import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync, symlinkSync, unlinkSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import net from 'node:net';
import { spawnSync } from 'node:child_process';
import { once } from 'node:events';
import { privateRedisPort, privateRedisConfig, readPrivateRedisConfig, verifyPrivateRedis, privateRedisRuntimeReport, verifyBackendRedisConnection } from '../lib/golden-private-redis.mjs';

// Hermetic contract controls; live Redis/process isolation is verified separately.
function fixture() {
  const repo = '/owned/oss';
  const report = { runtime: 'owned', stateDir: '/state', environment: { REDIS_DATABASE: '3' },
    sources: [{ key: 'core', status: 'ok', expected: { root: repo } }],
    ports: { backend: { port: 6407 }, web: { port: 5107 }, bff: { port: 6107 } },
    processes: [{ key: 'redis-owned', status: 'ok', expected: { pid: 123, cwd: '/state/redis/owned' } }] };
  const config = privateRedisConfig(report, repo, '16307');
  return { report, repo, descriptor: { config }, listeners: [123], liveDirectory: config.dataDirectory, pong: 'PONG' };
}

test('a private endpoint requires an owned listener and storage, not only a Redis DB number', () => {
  const value = verifyPrivateRedis(fixture());
  assert.deepEqual(value, { isolated: true, host: '127.0.0.1', port: 16307, database: '3',
    pid: 123, dataDirectory: '/state/redis/owned' });
});

for (const value of ['', undefined, '0', '1023', '6379', '65536', '16307.5', 'host:16307']) {
  test(`refuses invalid or shared private Redis port ${String(value)}`, () => assert.throws(() => privateRedisPort(value)));
}

for (const [label, mutate] of Object.entries({
  'foreign source': f => { f.report.sources[0].expected.root = '/foreign'; },
  'source drift': f => { f.report.sources[0].status = 'drift'; },
  'missing listener': f => { f.listeners = []; },
  'foreign listener': f => { f.listeners = [999]; },
  'ambiguous listener': f => { f.listeners.push(999); },
  'changed PID': f => { f.report.processes[0].expected.pid = 999; },
  'process drift': f => { f.report.processes[0].status = 'drift'; },
  'foreign working directory': f => { f.report.processes[0].expected.cwd = '/foreign'; },
  'foreign live Redis directory': f => { f.liveDirectory = '/foreign'; },
  'failed Redis ping': f => { f.pong = 'not-PONG'; },
  'descriptor owner drift': f => { f.descriptor.config.runtime = 'foreign'; },
  'descriptor storage drift': f => { f.descriptor.config.dataDirectory = '/foreign'; },
})) test(`refuses ${label} before declaring Redis isolation`, () => {
  const f = fixture(); mutate(f); assert.throws(() => verifyPrivateRedis(f));
});

test('refuses a private Redis port overlapping a product listener', () => {
  const f = fixture(); assert.throws(() => privateRedisConfig(f.report, f.repo, '6407'));
});

test('fingerprints retained configuration and refuses path or symlink substitution', t => {
  const root = mkdtempSync(join(tmpdir(), 'golden-private-redis-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const f = fixture(); f.report.stateDir = root;
  const config = privateRedisConfig(f.report, f.repo, '16307');
  const file = join(root, 'runtimes/owned/oss-stack/isolated-redis.json');
  mkdirSync(join(root, 'runtimes/owned/oss-stack'), { recursive: true });
  writeFileSync(file, JSON.stringify(config));
  const first = readPrivateRedisConfig(f.report, f.repo);
  writeFileSync(file, JSON.stringify({ ...config, port: 16308 }));
  assert.notEqual(readPrivateRedisConfig(f.report, f.repo).hash, first.hash);
  writeFileSync(file, JSON.stringify({ ...config, dataDirectory: '/foreign' }));
  assert.throws(() => readPrivateRedisConfig(f.report, f.repo));
  unlinkSync(file); symlinkSync(join(root, 'untrusted'), file);
  assert.throws(() => readPrivateRedisConfig(f.report, f.repo));
  unlinkSync(file); writeFileSync(file, JSON.stringify(config));
  const foreign = join(root, 'foreign'); mkdirSync(foreign);
  symlinkSync(foreign, join(root, 'redis'));
  assert.throws(() => readPrivateRedisConfig(f.report, f.repo));
});

test('uses live explain probes even while other startup listeners are absent', t => {
  const root = mkdtempSync(join(tmpdir(), 'golden-private-redis-probe-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const cli = join(root, 'cli'), f = fixture();
  const script = (value, status) => `#!${process.execPath}\n` +
    `if (JSON.stringify(process.argv.slice(2)) !== '${JSON.stringify(['runtime', 'explain', 'owned', '--json'])}') process.exit(2);\n` +
    `process.stdout.write(${JSON.stringify(JSON.stringify(value))}); process.exit(${status});\n`;
  writeFileSync(cli, script(f.report, 1), { mode: 0o700 });
  assert.deepEqual(privateRedisRuntimeReport(cli, 'owned'), f.report);
  writeFileSync(cli, script({ ...f.report, runtime: 'foreign' }, 1));
  assert.throws(() => privateRedisRuntimeReport(cli, 'owned'));
  writeFileSync(cli, script(f.report, 2));
  assert.throws(() => privateRedisRuntimeReport(cli, 'owned'));
});

test('matches a live numeric loopback connection independently of hostname resolution', async t => {
  const peers = new Set();
  const server = net.createServer(socket => peers.add(socket));
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  const endpoint = { host: '127.0.0.1', port: server.address().port };
  const client = net.connect(endpoint.port, endpoint.host);
  t.after(async () => {
    client.destroy();
    for (const socket of peers) socket.destroy();
    await new Promise(done => server.close(done));
  });
  await once(client, 'connect');
  verifyBackendRedisConnection(process.pid, endpoint);
  assert.throws(() => verifyBackendRedisConnection(process.pid, { ...endpoint, host: '127.0.0.2' }));
});


test('reads one no-follow descriptor even when its path is replaced after validation or open', t => {
  const root = mkdtempSync(join(tmpdir(), 'golden-private-redis-race-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const result = spawnSync(process.execPath, ['--input-type=module', '-e', `
    import fs from 'node:fs';
    import assert from 'node:assert/strict';
    import { syncBuiltinESMExports } from 'node:module';
    import { join } from 'node:path';
    const helper = await import(process.argv[1]), root = process.argv[2];
    const report = {runtime:'owned',stateDir:root,environment:{REDIS_DATABASE:'3'},ports:{},
      sources:[{key:'core',status:'ok',expected:{root:'/owned/oss'}}],processes:[]};
    const config = helper.privateRedisConfig(report,'/owned/oss','16307');
    const directory=join(root,'runtimes/owned/oss-stack');fs.mkdirSync(directory,{recursive:true});
    const file=join(directory,'isolated-redis.json'), foreign=join(root,'foreign.json');
    fs.writeFileSync(file,JSON.stringify(config));fs.writeFileSync(foreign,JSON.stringify(config));
    const originalStat=fs.lstatSync,originalOpen=fs.openSync,originalRead=fs.readFileSync;
    let checks=0,swapped=false,foreignReads=0;
    const swap=()=>{if(!swapped){fs.unlinkSync(file);fs.symlinkSync(foreign,file);swapped=true;}};
    fs.lstatSync=(path,...args)=>{const value=originalStat(path,...args);if(path===file&&++checks===3)swap();return value;};
    fs.openSync=(path,...args)=>{const fd=originalOpen(path,...args);if(path===file)swap();return fd;};
    fs.readFileSync=(path,...args)=>{if(path===file&&originalStat(file).isSymbolicLink())foreignReads++;return originalRead(path,...args);};
    syncBuiltinESMExports();
    const actual=helper.readPrivateRedisConfig(report,'/owned/oss');
    assert(swapped,'race mutation must actually replace the descriptor path');
    assert.deepEqual(actual.config,config);
    assert.equal(foreignReads,0,'must not read through a path replaced after its metadata check');
  `, new URL('../lib/golden-private-redis.mjs', import.meta.url).href, root], {encoding:'utf8'});
  assert.equal(result.status,0,result.stdout+result.stderr);
});


test('refuses a FIFO descriptor promptly instead of blocking on open', t => {
  const root=mkdtempSync(join(tmpdir(),'golden-private-redis-fifo-'));
  t.after(()=>rmSync(root,{recursive:true,force:true}));
  const directory=join(root,'runtimes/owned/oss-stack');mkdirSync(directory,{recursive:true});
  const file=join(directory,'isolated-redis.json');
  const created=spawnSync('mkfifo',[file],{encoding:'utf8'});
  assert.equal(created.status,0,created.stderr);
  const result=spawnSync(process.execPath,['--input-type=module','-e',`
    import assert from 'node:assert/strict';
    const helper=await import(process.argv[1]),root=process.argv[2];
    const report={runtime:'owned',stateDir:root};
    assert.throws(()=>helper.readPrivateRedisConfig(report,'/owned/oss'),/Invalid private Redis descriptor/);
  `,new URL('../lib/golden-private-redis.mjs',import.meta.url).href,root],{encoding:'utf8',timeout:2000});
  assert.equal(result.error,undefined,'non-regular descriptor must not block the identity probe');
  assert.equal(result.status,0,result.stdout+result.stderr);
});
