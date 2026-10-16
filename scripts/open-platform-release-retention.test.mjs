import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';

// Exercise the actual EXIT handler with a recording Docker boundary. This is a
// hermetic lifecycle contract, not proof that the Linux image gate ran.
const gate = fs.readFileSync(new URL('./run-open-platform-release-image-gate.sh', import.meta.url), 'utf8');
const handler = gate.slice(gate.indexOf('cleanup() {'), gate.indexOf('trap cleanup EXIT'));
for (const status of [0, 1, 130]) {
  for (const foreignLock of [false, true]) {
    test(`retain verification environment on exit ${status}, foreign lock=${foreignLock}`, () => {
      const root = fs.mkdtempSync(path.join(os.tmpdir(), 'open-image-retention-'));
      const artifacts = path.join(root, 'artifacts');
      const lock = path.join(root, 'lock');
      fs.mkdirSync(path.join(artifacts, 'logs'), { recursive: true });
      fs.mkdirSync(lock);
      fs.writeFileSync(path.join(lock, 'owner'), foreignLock ? 'foreign-owner' : 'owned-token');
      fs.writeFileSync(path.join(artifacts, 'credentials.json'), 'fixture credential');
      fs.writeFileSync(path.join(artifacts, 'slo-fixture.json'), 'fixture credential');
      fs.writeFileSync(path.join(artifacts, 'model-event-secret.key'), 'fixture credential');
      const script = `set -Eeuo pipefail
      docker() { printf '%s\\n' "$*" >> "$CALL_LOG"; }
      ${handler}
      trap cleanup EXIT
      exit "$TEST_EXIT"
      `;
      try {
        const result = spawnSync('bash', ['-c', script], {
          encoding: 'utf8',
          env: { ...process.env, TEST_EXIT: String(status), CALL_LOG: path.join(root, 'calls'),
            ARTIFACTS: artifacts, CREDENTIAL_ARTIFACT: path.join(artifacts, 'credentials.json'),
            SLO_FIXTURE_ARTIFACT: path.join(artifacts, 'slo-fixture.json'),
            MODEL_EVENT_SECRET_FILE: path.join(artifacts, 'model-event-secret.key'),
            WORK_ROOT: root, DB_SAMPLE_PID: '', DB_SAMPLE_STOP: path.join(artifacts, 'db-sampling.stop'),
            APP: 'owned-app', PG: 'owned-pg', REDIS: 'owned-redis', NET: 'owned-net',
            IMAGE: 'owned-image', LOCK_DIR: lock, LOCK_TOKEN: 'owned-token' },
        });
        assert.equal(result.status, status, result.stderr);
        const calls = fs.readFileSync(path.join(root, 'calls'), 'utf8').trim().split('\n');
        assert.deepEqual(calls, ['inspect owned-app', 'logs owned-app', 'logs owned-pg',
          'logs owned-redis', 'ps --all --filter network=owned-net']);
        assert.equal(fs.existsSync(path.join(artifacts, 'credentials.json')), false);
        assert.equal(fs.existsSync(path.join(artifacts, 'slo-fixture.json')), false);
        assert.equal(fs.existsSync(path.join(artifacts, 'model-event-secret.key')), false);
        assert.equal(fs.existsSync(lock), foreignLock);
        const receipt = JSON.parse(fs.readFileSync(path.join(artifacts, 'runtime-retention.json'), 'utf8'));
        assert.equal(receipt.runnerExitCode, status);
        assert.equal(receipt.network, 'owned-net');
        assert.equal(receipt.image, 'owned-image');
        assert.equal(receipt.databaseDeleted, false);
        assert.equal(receipt.runtimeDeleted, false);
        assert.doesNotMatch(JSON.stringify(receipt) + result.stdout + result.stderr, /fixture credential/);
        assert.equal(fs.existsSync(path.join(artifacts, 'logs', 'app.log')), true);
      } finally {
        fs.rmSync(root, { recursive: true, force: true });
      }
    });
  }
}
