import assert from 'node:assert/strict';
import { readFileSync, readdirSync, existsSync, realpathSync, writeFileSync, renameSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { join, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';
import { pathToFileURL } from 'node:url';

const digest = value => createHash('sha256').update(value).digest('hex');
const hashFile = file => digest(readFileSync(file));

export function verifyArtifact(row, sources) {
  assert.match(row.sha256, /^[a-f0-9]{64}$/, 'invalid artifact digest');
  assert.ok(existsSync(row.source) && existsSync(row.staged), 'runtime artifact missing');
  const source = realpathSync(row.source);
  const owners = sources.filter(s => source.startsWith(`${realpathSync(s.actual.root)}/`));
  assert.equal(owners.length, 1, 'artifact must have exactly one registered source owner');
  assert.equal(hashFile(source), row.sha256, 'source artifact changed');
  assert.equal(hashFile(row.staged), row.sha256, 'staged artifact changed');
  return { ...row, sourceKey: owners[0].key };
}

export function verifyBinding(info, runtime, core) {
  assert.equal(info.runtime, runtime, 'runtime identity mismatch');
  assert.ok(info.sources?.length, 'registered source set missing');
  for (const source of info.sources) assert.equal(source.status, 'ok', 'source identity drift');
  assert.equal(info.sources.find(s => s.key === 'core')?.actual.root, core, 'wrong Core checkout');
  assert.ok(info.environment.POSTGRES_DB, 'database binding missing');
  assert.equal(Object.keys(info.ports).length, 3, 'three declared ports required');
  for (const port of Object.values(info.ports)) {
    assert.equal(port.status, 'listening', 'runtime listener missing');
    assert.equal(port.pids.length, 1, 'runtime listener ownership ambiguous');
  }
}

export function verifyLiveStack(workspace, core, runtime, state) {
  const command = (executable, args) => {
    const result = spawnSync(executable, args, { encoding: 'utf8', maxBuffer: 8 * 1024 * 1024 });
    assert.equal(result.status, 0, `${executable} identity probe failed`);
    return result.stdout;
  };
  // explain can be red solely because previous registered PIDs have stopped.
  const probe = spawnSync(join(workspace, 'aura'), ['runtime', 'explain', runtime, '--json'], { encoding: 'utf8' });
  assert.ok([0, 1].includes(probe.status), 'public identity probe unavailable');
  const info = JSON.parse(probe.stdout);
  verifyBinding(info, runtime, core);
  // A newly spawned listener is initially unknown to the public registry. It is
  // admitted only through the actual cwd/JAR/environment checks below; publication
  // then requires public verify to pass with the new descriptors installed.
  const allowed = new Set(['PROCESS_IDENTITY_DRIFT', 'UNKNOWN_LISTENER']);
  for (const finding of info.findings || []) assert.ok(allowed.has(finding.code), `public identity finding: ${finding.code}`);
  const boot = info.artifacts.find(item => item.key === 'backend')?.path;
  assert.ok(boot, 'registered backend artifact missing');
  const artifacts = [verifyArtifact({ key: 'core', source: join(core, 'platform/build/libs/AuraBoot-1.0.0-boot.jar'), staged: boot, sha256: hashFile(boot) }, info.sources)];
  const receipt = readFileSync(join(state, 'pf4j-staging.tsv'), 'utf8').trim().split('\n');
  assert.equal(receipt.shift(), 'plugin\tplugin_dir\tsource_jar\tstaged_jar\tsha256', 'invalid PF4J staging receipt');
  for (const line of receipt) {
    const [key, , source, staged, sha256] = line.split('\t');
    artifacts.push(verifyArtifact({ key, source, staged, sha256 }, info.sources));
  }
  assert.equal(new Set(artifacts.map(a => a.key)).size, artifacts.length, 'duplicate staged plugin');
  const stagedFiles = readdirSync(join(state, 'pf4j-plugins')).filter(f => f.endsWith('.jar')).sort();
  const receiptFiles = artifacts.slice(1).map(a => a.staged.split('/').at(-1)).sort();
  assert.deepEqual(receiptFiles, stagedFiles, 'PF4J receipt does not cover every staged JAR');
  const imported = readFileSync(join(state, 'logs/import.log'), 'utf8');
  assert.match(imported, /Plugin import complete\./, 'plugin import incomplete');
  assert.match(imported, /Plugin activation: OK \(\d+ enabled\)/, 'plugin activation incomplete');
  assert.doesNotMatch(imported, /\bFAIL\b/, 'plugin import failed');
  const processes = [];
  for (const [key, port] of Object.entries(info.ports)) {
    const pid = port.pids[0];
    const cwd = command('lsof', ['-a', '-p', String(pid), '-d', 'cwd', '-Fn']).split('\n').find(v => v.startsWith('n'))?.slice(1);
    assert.equal(cwd, join(core, key === 'backend' ? 'platform' : 'web-admin'), 'listener cwd mismatch');
    const invocation = command('ps', ['-ww', '-p', String(pid), '-o', 'command=']).trimEnd();
    const environment = command('ps', ['eww', '-p', String(pid), '-o', 'command=']);
    const value = name => environment.match(new RegExp(`(?:^|\\s)${name}=([^ ]+)`))?.[1];
    if (key === 'backend') {
      assert.ok(invocation.includes(boot), 'backend does not execute the verified runtime JAR');
      assert.equal(value('SPRING_DATASOURCE_URL'), `jdbc:postgresql://127.0.0.1:5432/${info.environment.POSTGRES_DB}?charSet=UTF8`, 'backend database drift');
      assert.equal(value('SPRING_DATA_REDIS_DATABASE'), info.environment.REDIS_DATABASE, 'backend Redis drift');
    }
    if (key === 'bff') {
      const upstream = `http://127.0.0.1:${info.ports.backend.port}`;
      assert.equal(value('SPRING_BOOT_URL'), upstream, 'BFF upstream drift');
      assert.equal(value('BFF_INTERNAL_URL'), upstream, 'BFF internal upstream drift');
    }
    processes.push({ key, pid, cwd, started: command('ps', ['-p', String(pid), '-o', 'lstart=']).trim(), commandHash: digest(invocation) });
  }
  // No credentials, raw command lines, or arbitrary environment fields are persisted.
  return { schemaVersion: 1, runtime, product: 'oss-golden-stack', generatedAt: new Date().toISOString(), sourceCommit: info.sources.find(s => s.key === 'core').actual.commit, sources: info.sources.map(s => ({ key: s.key, ...s.actual })), artifacts, processes, artifactHash: digest(JSON.stringify(artifacts)) };
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try {
    const [workspace, core, runtime, state] = process.argv.slice(2);
    assert.ok(workspace && core && runtime && state, 'explicit workspace/core/runtime/state required');
    const proof = verifyLiveStack(workspace, core, runtime, state);
    const aura = args => {
      const result = spawnSync(join(workspace, 'aura'), args, { encoding: 'utf8' });
      assert.equal(result.status, 0, `public runtime operation failed: ${args.slice(0, 3).join(' ')}`);
      return result.stdout.trim();
    };
    const token = aura(['runtime', 'process', 'token', runtime]);
    for (const process of proof.processes) {
      const current = spawnSync('ps', ['-ww', '-p', String(process.pid), '-o', 'command='], { encoding: 'utf8' });
      assert.equal(current.status, 0, 'listener disappeared before registration');
      assert.equal(digest(current.stdout.trimEnd()), process.commandHash, 'listener changed before registration');
      aura(['runtime', 'process', 'register', runtime, '--key', process.key, '--pid', String(process.pid), '--token', token]);
    }
    for (const artifact of proof.artifacts) {
      const key = artifact.key === 'core' ? 'oss-core' : `oss-pf4j-${artifact.key.replaceAll('_', '-')}`;
      aura(['runtime', 'artifact', 'stage', runtime, '--key', key, '--source', artifact.sourceKey, '--file', artifact.source]);
    }
    const file = join(state, 'manifest.json');
    writeFileSync(`${file}.tmp`, `${JSON.stringify(proof, null, 2)}\n`, { mode: 0o600 });
    renameSync(`${file}.tmp`, file);
    aura(['runtime', 'manifest', 'publish', runtime, '--product', 'oss-pf4j-stack', '--from', file, '--verified-by', 'oss-golden-stack.sh:verify-artifacts']);
    aura(['runtime', 'verify', runtime]);
    console.log(`Verified ${proof.artifacts.length} artifacts and ${proof.processes.length} listener identities for ${runtime}`);
  } catch (error) {
    console.error(`OSS_STACK_IDENTITY_INVALID: ${error.message}`);
    process.exitCode = 1;
  }
}
