import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';

const gate = fs.readFileSync(new URL('./run-open-platform-release-image-gate.sh', import.meta.url), 'utf8');
const probe = fs.readFileSync(new URL('./ci/open-platform-release-probe.py', import.meta.url), 'utf8');
const slo = fs.readFileSync(new URL('../tests/load/k6/open-platform-slo.js', import.meta.url), 'utf8');
const dockerfile = fs.readFileSync(new URL('../platform/Dockerfile', import.meta.url), 'utf8');

assert.match(gate, /AURA_CI_EXPECTED_REF/);
assert.match(gate, /uname -s.*Linux/);
assert.match(gate, /uname -m.*x86_64/);
assert.match(gate, /status --porcelain --untracked-files=all/);
assert.match(gate, /registry publication requires a separate owner-authorized job/);
assert.match(gate, /GRADLE_DISTRIBUTION_SHA256="6f74b601422d6d6fc4e1f9a1ab6522f642c2fdcbc15ae33ebd30ba3d7198e854"/);
assert.match(gate, /sha256sum --check --status/);
assert.match(gate, /seeding BuildKit Gradle wrapper cache from verified host distribution/);
assert.match(gate, /--mount=type=cache,target=\/root\/\.gradle\/wrapper/);
assert.match(dockerfile, /COPY VERSION \/VERSION/);
assert.match(gate, /docker logs "\$APP" > "\$ARTIFACTS\/logs\/app\.log"/);
assert.match(gate, /-v "\$STAGE\/plugins":\/plugins:ro/);
assert.match(gate, /chmod -R a\+rX "\$STAGE\/plugins"/);
assert.match(gate, /: > "\$CREDENTIAL_ARTIFACT"/);
assert.match(gate, /chmod 0600 "\$CREDENTIAL_ARTIFACT"/);
assert.match(gate, /cleanup\(\)[\s\S]*rm -f "\$CREDENTIAL_ARTIFACT"/);
assert.match(gate, /RUNNER_UID="\$\(id -u\)"/);
assert.match(gate, /RUNNER_GID="\$\(id -g\)"/);
assert.equal((gate.match(/docker run --rm --user "\$RUNNER_UID:\$RUNNER_GID"/g) ?? []).length, 2);
assert.match(gate, /PROFILE=production/);
assert.match(slo, /open_api_errors/);
assert.match(slo, /p\(95\)<250/);
assert.match(gate, /delivery_status IN \('pending','processing','failed','dead_letter'\)/);
assert.match(gate, /webhookDrainBacklog/);
assert.match(gate, /AURA_OPEN_PLATFORM_RELEASE_MUTATION/);
assert.match(gate, /EXPECTED_RED: Webhook backlog mutation was rejected/);
assert.match(gate, /releaseReceiptCreated.*False/);
assert.match(probe, /inventory\.stock-ins\.confirm/);
assert.match(probe, /"tinv_pd_code": "REL-PRODUCT-001"/);
assert.match(probe, /"tinv_wh_code": "REL-WAREHOUSE-001"/);
assert.match(probe, /"tinv_si_code": "REL-STOCK-IN-001"/);
assert.match(probe, /expected=\(412,/);
assert.match(probe, /cursor \+ 'x'/);
console.log('open-platform release-image contract: PASS');

// Exercise the actual exit handler without starting a container runtime.
const exitHandler = gate.slice(gate.indexOf('cleanup() {'), gate.indexOf('trap cleanup EXIT'));
for (const [status, ownLock] of [[0, true], [1, true], [42, true], [42, false], [130, true], [143, true]]) {
  const temporary = fs.mkdtempSync(path.join(os.tmpdir(), 'open-platform-retention-'));
  try {
    const work = path.join(temporary, 'work');
    const artifacts = path.join(temporary, 'artifacts');
    const lock = path.join(temporary, 'lock');
    for (const directory of [work, path.join(artifacts, 'logs'), lock]) fs.mkdirSync(directory, {recursive:true});
    const database = path.join(work, 'database');
    fs.writeFileSync(database, 'retained database bytes');
    fs.writeFileSync(path.join(lock, 'owner'), ownLock ? 'mine' : 'other');
    const credentials = path.join(artifacts, 'private-credentials');
    fs.writeFileSync(credentials, 'private-sentinel');
    const calls = path.join(work, 'docker-calls');
    const result = spawnSync('bash', ['-c', `set -Eeuo pipefail
${exitHandler}
docker() { printf '%s\n' "$*" >> "$CALLS"; }
trap cleanup EXIT
exit ${status}
`], {
      encoding:'utf8', env:{...process.env, WORK_ROOT:work, ARTIFACTS:artifacts, LOCK_DIR:lock,
        LOCK_TOKEN:'mine', CREDENTIAL_ARTIFACT:credentials, CALLS:calls, NET:'owned-network',
        APP:'owned-app', PG:'owned-pg', REDIS:'owned-redis', IMAGE:'owned-image'}
    });
    assert.equal(result.status, status, result.stderr);
    assert.equal(fs.readFileSync(database, 'utf8'), 'retained database bytes');
    assert.equal(fs.existsSync(credentials), false);
    assert.equal(fs.existsSync(lock), !ownLock);
    if (!ownLock) assert.equal(fs.readFileSync(path.join(lock, 'owner'), 'utf8'), 'other');
    const commands = fs.readFileSync(calls, 'utf8');
    assert.match(commands, /ps --all/);
    assert.doesNotMatch(commands, /(?:^|\n)(?:rm|stop|network rm|image rm)(?: |$)/);
    const receipt = JSON.parse(fs.readFileSync(path.join(artifacts, 'runtime-retention.json'), 'utf8'));
    assert.equal(receipt.runnerExitCode, status);
    assert.equal(receipt.runtimeDeleted, false);
    assert.equal(receipt.databaseDeleted, false);
    assert.doesNotMatch(JSON.stringify(receipt) + result.stdout + result.stderr, /private-sentinel/);
  } finally { fs.rmSync(temporary, {recursive:true}); }
}
console.log('open-platform runtime retention: 6 executable cases PASS');
