import assert from 'node:assert/strict';
import test from 'node:test';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import YAML from 'yaml';

const read = name => fs.readFileSync(new URL(`../${name}`, import.meta.url), 'utf8');
const workflow = YAML.parse(read('.github/workflows/quickstart.yml'));

test('both deployment paths pass an operator key without a shared secret default', () => {
  for (const name of ['docker-compose.yml', 'docker-compose.pull.yml']) {
    const backend = YAML.parse(read(name)).services.backend;
    assert.equal(backend.environment.OPEN_PLATFORM_PROTOCOL_SIGNING_KEY,
      null);
    assert.equal(backend.environment.SPRING_PROFILES_ACTIVE, 'community');
  }
  assert.equal(YAML.parse(read('docker-compose.prod.yml')).services.backend.environment.OPEN_PLATFORM_PROTOCOL_SIGNING_KEY,
    '${OPEN_PLATFORM_PROTOCOL_SIGNING_KEY:?OPEN_PLATFORM_PROTOCOL_SIGNING_KEY is required for production deployments (openssl rand -hex 32)}');
});

test('quickstart CI creates a private fresh key before compose and never overwrites one', () => {
  const steps = workflow.jobs.quickstart.steps;
  const index = steps.findIndex(step => step.name === 'Prepare private protocol signing key');
  assert.ok(index >= 0);
  assert.ok(index < steps.findIndex(step => step.name === 'Build & start full stack'));
  const step = steps[index];
  assert.equal(step.if, "steps.changes.outputs.run == 'true'");
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'quickstart-key-'));
  try {
    const generate = () => spawnSync('bash', ['-eu', '-c', step.run], { cwd: directory, encoding: 'utf8' });
    const first = generate();
    assert.equal(first.status, 0, first.stderr);
    assert.equal(first.stdout, '');
    const file = path.join(directory, '.env');
    const readEnv = () => {
      const fd = fs.openSync(file, 'r');
      try {
        return { mode: fs.fstatSync(fd).mode & 0o777, contents: fs.readFileSync(fd, 'utf8') };
      } finally { fs.closeSync(fd); }
    };
    const before = readEnv();
    assert.equal(before.mode, 0o600);
    assert.match(before.contents, /^OPEN_PLATFORM_PROTOCOL_SIGNING_KEY=[a-f0-9]{96}\n$/);
    assert.notEqual(generate().status, 0);
    assert.equal(readEnv().contents, before.contents);
    fs.unlinkSync(file);
    assert.equal(generate().status, 0);
    assert.notEqual(readEnv().contents, before.contents);
  } finally { fs.rmSync(directory, { recursive: true, force: true }); }
});
