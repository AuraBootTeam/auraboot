import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import './open-platform-jfr.test.mjs';

const gate = fs.readFileSync(new URL('./run-open-platform-release-image-gate.sh', import.meta.url), 'utf8');
const probe = fs.readFileSync(new URL('./ci/open-platform-release-probe.py', import.meta.url), 'utf8');
const slo = fs.readFileSync(new URL('../tests/load/k6/open-platform-slo.js', import.meta.url), 'utf8');
const dockerfile = fs.readFileSync(new URL('../platform/Dockerfile', import.meta.url), 'utf8');

assert.match(gate, /AURA_CI_EXPECTED_REF/);
assert.match(gate, /prepare_openapi_verifier\.sh/);
assert.match(gate, /verify_openapi_artifacts\.py/);
assert.match(gate, /OpenAPI contract validation failed/);
assert.match(gate, /Generated OpenAPI declaration consumer failed/);
assert.match(gate, /"openapi": json\.load\(open\("\$ARTIFACTS\/openapi-receipt\.json"\)\)/);
assert.match(gate, /uname -s.*Linux/);
assert.match(gate, /uname -m.*x86_64/);
assert.match(gate, /status --porcelain --untracked-files=all/);
assert.match(gate, /registry publication requires a separate owner-authorized job/);
assert.match(gate, /GRADLE_DISTRIBUTION_SHA256="6f74b601422d6d6fc4e1f9a1ab6522f642c2fdcbc15ae33ebd30ba3d7198e854"/);
assert.match(gate, /sha256sum --check --status/);
assert.match(gate, /seeding BuildKit Gradle wrapper cache from verified host distribution/);
assert.match(gate, /DOCKERFILE_FRONTEND_SOURCE="docker\.io\/docker\/dockerfile@sha256:26147acbda4f14c5add9946e2fd2ed543fc402884fd75146bd342a7f6271dc1d"/);
assert.match(gate, /"\$BUILD_JDK_IMAGE" "\$RUNTIME_JRE_IMAGE" "\$DOCKERFILE_FRONTEND_SOURCE"/);
assert.match(gate, /DOCKERFILE_FRONTEND_IMAGE="\$\(base_ref "\$DOCKERFILE_FRONTEND_SOURCE"\)"/);
assert.equal((gate.match(/--build-arg "BUILDKIT_SYNTAX=\$DOCKERFILE_FRONTEND_IMAGE"/g) ?? []).length, 2);
assert.doesNotMatch(gate, /# syntax=docker\/dockerfile:1/);
assert.match(gate, /timeout 1200 docker run --rm --memory=4g --network "\$NET"/);
assert.match(gate, /-Dorg\.gradle\.jvmargs="-Xmx1536m -XX:MaxMetaspaceSize=256m/);
assert.match(gate, /-Dorg\.gradle\.workers\.max=1 -PauraTestMaxHeap=1024m -PauraTestMinHeap=128m/);
assert.match(gate, /-PauraTestMaxMetaspace=384m -PspringTestContextCacheMaxSize=1/);
assert.match(gate, /:test "\$\{args\[@\]\}" :jacocoTestReport --no-daemon --no-parallel/);
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
assert.equal((gate.match(/docker run --rm --user "\$RUNNER_UID:\$RUNNER_GID"/g) ?? []).length, 3);
assert.match(gate, /node \/sdk\/toolchain\/node_modules\/typescript\/bin\/tsc/);
assert.match(gate, /PROFILE=production/);
assert.match(gate, /collect-open-platform-jfr\.sh" start/);
assert.match(gate, /collect-open-platform-jfr\.sh" collect/);
assert.ok(gate.indexOf('collect-open-platform-jfr.sh" collect') < gate.indexOf('[[ "$SLO_RC" == 0 ]]'));
assert.match(gate, /\[\[ "\$JFR_RC" == 0 \]\] \|\| fatal/);
assert.match(gate, /SPRING_PROFILES_ACTIVE=community/);
assert.match(gate, /docker run -d.*--memory=4g/);
assert.match(gate, /--env-file "\$APP_SECRET_ENV"/);
assert.doesNotMatch(gate, /-e (?:JWT_SECRET|FIELD_ENCRYPTION_KEY|OPEN_PLATFORM_PROTOCOL_SIGNING_KEY)=/);
const secretGenerator = gate.slice(gate.indexOf('python3 - "$APP_SECRET_ENV"'), gate.indexOf('# AURA_PLUGINS_DIR'));
const keySizes = {FIELD_ENCRYPTION_KEY:32, OPEN_PLATFORM_PROTOCOL_SIGNING_KEY:48, JWT_SECRET:64};
const secretDirectory = fs.mkdtempSync(path.join(os.tmpdir(), 'open-platform-app-secrets-'));
try {
  const file = path.join(secretDirectory, 'app.env');
  const generate = script => spawnSync('bash', ['-c', script], {encoding:'utf8', env:{...process.env, APP_SECRET_ENV:file}});
  const result = generate(secretGenerator);
  assert.equal(result.status, 0, result.stderr);
  assert.equal(result.stdout, '');
  assert.equal(fs.statSync(file).mode & 0o777, 0o600);
  const values = Object.fromEntries(fs.readFileSync(file, 'utf8').trim().split('\n').map(line => {
    const index = line.indexOf('='); return [line.slice(0,index),line.slice(index+1)];
  }));
  assert.deepEqual(Object.keys(values).sort(), Object.keys(keySizes).sort());
  for (const [name,size] of Object.entries(keySizes)) assert.equal(Buffer.from(values[name],'base64').length,size);
  const before = fs.readFileSync(file);
  assert.notEqual(generate(secretGenerator).status, 0);
  assert.deepEqual(fs.readFileSync(file), before);
  fs.unlinkSync(file);
  const missing = generate(secretGenerator.replace('("FIELD_ENCRYPTION_KEY", 32),',''));
  assert.equal(missing.status, 0, missing.stderr);
  assert.equal(fs.readFileSync(file,'utf8').includes('FIELD_ENCRYPTION_KEY='),false);
  assert.throws(()=>assert.deepEqual(fs.readFileSync(file,'utf8').trim().split('\n').map(s=>s.split('=')[0]).sort(),Object.keys(keySizes).sort()));
} finally { fs.rmSync(secretDirectory,{recursive:true,force:true}); }
assert.doesNotMatch(probe, /\/api\/test\/seed/);
assert.match(slo, /open_api_errors/);
assert.match(slo, /p\(95\)<250/);
assert.match(gate, /delivery_status IN \('pending','processing','failed','dead_letter'\)/);
assert.match(gate, /webhookDrainBacklog/);
assert.match(gate, /AURA_OPEN_PLATFORM_RELEASE_MUTATION/);
assert.match(gate, /EXPECTED_RED: Webhook backlog mutation was rejected/);
assert.match(gate, /releaseReceiptCreated.*False/);
assert.match(gate, /exact frozen source-set digest is required/);
assert.doesNotMatch(probe, /\/api\/test\//);
assert.match(probe, /bootstrap_module\.bootstrap_admin\(request\)/);
assert.match(probe, /release-bootstrap\.py/);
assert.match(probe, /inventory\.stock-ins\.confirm/);
assert.match(probe, /run_dynamic_probe\(request, admin/);
assert.match(probe, /PsqlAuditReader/);
assert.match(probe, /audit_reader=audit_reader/);
assert.match(probe, /open_platform_ci/);
assert.match(probe, /consumer=consume/);
assert.match(probe, /run_sdk_consumer\(fixture, sdk_command/);
for (const [name, alias] of [['PG', 'pg'], ['REDIS', 'redis'], ['APP', 'app']]) {
  assert.ok(gate.includes(`--name "$${name}" --network "$NET" --network-alias ${alias}`));
}
assert.ok(!gate.includes('jdbc:postgresql://$PG:5432'));
assert.ok(!gate.includes('http://$APP:6443'));
assert.ok(gate.includes('SPRING_DATA_REDIS_HOST=redis'));
assert.ok(gate.includes('REDIS_HOST=redis'));
assert.match(gate, /SDK_NODE_IMAGE="\$\(base_ref/);
assert.match(gate, /prepare-open-platform-sdk\.sh \/source \/work\/sdk/);
assert.match(gate, /verify-open-platform-sdk\.py/);
const sdkPreparation = fs.readFileSync(new URL('./ci/prepare-open-platform-sdk.sh', import.meta.url), 'utf8');
assert.match(sdkPreparation, /if node consumer\.test\.mjs > runtime\.log 2>&1/);
assert.match(sdkPreparation, /--strict --noEmit --module nodenext --target es2022 consumer-ts-check\.ts/);
assert.match(sdkPreparation, /printf '0\\n' > types\.exit/);

assert.match(gate, /-p "127\.0\.0\.1:\$APP_HOST_PORT:6443"/);
assert.doesNotMatch(gate, /127\.0\.0\.1::6443/);
const portStart = gate.indexOf('APP_HOST_PORT=');
const portGenerator = gate.slice(portStart,gate.indexOf('docker run -d',portStart));
const portResult = spawnSync('bash',['-c',portGenerator+'\nprintf "%s" "$APP_HOST_PORT"'],{encoding:'utf8'});
assert.equal(portResult.status,0,portResult.stderr);
assert.ok(Number(portResult.stdout)>0 && Number(portResult.stdout)<65536);
assert.match(gate, /protocol binding is not loopback-only/);
assert.match(gate, /"sdkCases": 13/);
assert.match(gate, /BUSINESS_FIXTURE_PATH=\/artifacts\/slo-fixture\.json/);
assert.match(gate, /verify-open-platform-slo\.py/);
assert.match(gate, /"modelEventCases": 18/);
assert.match(gate, /--model-event-secret-file "\$MODEL_EVENT_SECRET_FILE"/);
assert.match(gate, /MODEL_APP="\$APP" MODEL_POSTGRES="\$PG"/);
assert.match(gate, /-e AURA_SSRF_ALLOWED_PRIVATE_HOSTS="\$NETWORK_GATEWAY"/);
assert.match(probe, /model_events = run_model_event_probe/);
assert.match(probe, /max_requests=1000/);
assert.match(probe, /measure_load=True, measure_sustained=True, measure_isolation=True/);
assert.match(probe, /qualify_replay_scope=True/);
const modelProbe = fs.readFileSync(new URL('./ci/open_platform_model_event_probe.py', import.meta.url), 'utf8');
assert.match(modelProbe, /500 if measure_sustained else 200/);
assert.match(gate, /open_platform_dynamic_probe\.py.*--verify-release-report/);
assert.match(gate, /dynamic business case reconciliation failed/);
assert.match(gate, /BACKEND_DB="open_platform_backend_ci"/);
assert.match(gate, /createdb -U auraboot "\$BACKEND_DB"/);
assert.match(gate, /open_platform_backend_catalog\.py" plan/);
assert.match(gate, /open_platform_backend_catalog\.py" verify/);
assert.match(gate, /backend case reconciliation failed/);
assert.match(gate, /--coverage "\$ARTIFACTS\/backend-jacoco\.xml"/);
assert.match(gate, /"backend": json\.load\(open\("\$ARTIFACTS\/backend-receipt\.json"\)\)/);
assert.ok(gate.indexOf('backend case reconciliation failed') < gate.indexOf('PYAPPSECRETS'));
assert.match(gate, /mapfile -t classes < \/artifacts\/backend-selectors\.txt/);
assert.match(gate, /\.\/gradlew :test "\$\{args\[@\]\}" :jacocoTestReport --no-daemon/);
assert.match(probe, /"tinv_pd_code": "REL-PRODUCT-001"/);
assert.match(probe, /"tinv_wh_code": "REL-WAREHOUSE-001"/);
assert.match(probe, /"tinv_si_code": "REL-STOCK-IN-001"/);
assert.match(probe, /expected=\(412,/);
assert.match(probe, /cursor \+ 'x'/);
assert.match(gate, /shared_preload_libraries=pg_stat_statements/);
assert.ok(gate.indexOf('pg_stat_statements_reset()') < gate.indexOf('SLO_RC=0'));
assert.ok(gate.indexOf('sql-profile.json') < gate.indexOf('[[ "$SLO_RC" == 0 ]]'));
assert.match(gate, /PROFILE_RC=0/);
assert.match(gate, /container-resources.jsonl/);
assert.match(gate, /track_wal_io_timing=on/);
assert.doesNotMatch(gate, /docker stop --timeout 120/);
assert.ok(gate.indexOf('db-io-before.json') < gate.indexOf('SLO_RC=0'));
assert.ok(gate.indexOf('db-io-after.json') < gate.indexOf('[[ "$SLO_RC" == 0 ]]'));
assert.match(gate, /wait "\$DB_SAMPLE_PID" \|\| PROFILE_RC=\$\?/);
const sampler = fs.readFileSync(new URL('./ci/sample-open-platform-db.sh', import.meta.url), 'utf8');
assert.match(sampler, /pg_blocking_pids/);
assert.doesNotMatch(sampler, /SELECT \*|usename|query,|query AS|client_addr/);
console.log('open-platform release-image contract: PASS');

// Execute the production expected-red receipt block without a Docker lifecycle.
const basePull = gate.slice(gate.indexOf("while IFS=$'\\t' read -r base_image expected_image_id; do"),
  gate.indexOf('PGVECTOR_IMAGE="$(base_ref'));
assert.ok(basePull.includes('done <<< "$PINNED_BASES"'));
const baseReference = `docker.io/pgvector/pgvector@sha256:${'b'.repeat(64)}`;
const baseSelection = gate.slice(gate.indexOf('base_ref() {'), gate.indexOf("while IFS=$'\\t' read -r base_image"));
assert.ok(baseSelection.includes('transportReference'));
for (const transportReference of [baseReference, `mirror.example/pgvector/pgvector@sha256:${'b'.repeat(64)}`]) {
const expectedImageId = `sha256:${'a'.repeat(64)}`;
for (const [actualImageId, pullExit] of [[expectedImageId, 0], [`sha256:${'c'.repeat(64)}`, 0], [expectedImageId, 124]]) {
  const temporary = fs.mkdtempSync(path.join(os.tmpdir(), 'open-platform-base-pull-'));
  try {
    fs.mkdirSync(path.join(temporary, 'logs'));
    const manifest = path.join(temporary, 'base-images.json');
    fs.writeFileSync(manifest, JSON.stringify({images: [{tag:'pgvector/pgvector:pg16', reference:baseReference, transportReference, imageId:expectedImageId}]}));
    const result = spawnSync('bash', ['-c', `set -Eeuo pipefail
fatal() { printf '%s\\n' "$*" >&2; exit 2; }
timeout() { shift; "$@"; }
docker() {
  case "$1" in
    pull)
      [[ "$*" == "pull --platform linux/amd64 $EXPECTED_REFERENCE" ]] || return 17
      return "$PULL_EXIT";;
    image) printf '%s\\n' "$ACTUAL_IMAGE_ID";;
    *) return 18;;
  esac
}
${baseSelection}
${basePull}
[[ "$(base_ref pgvector/pgvector:pg16)" == "$EXPECTED_REFERENCE" ]]
`], {encoding: 'utf8', env: {...process.env, ARTIFACTS: temporary, PULL_TIMEOUT: '120',
      BASE_IMAGE_MANIFEST: manifest, EXPECTED_REFERENCE: transportReference,
      ACTUAL_IMAGE_ID: actualImageId, PULL_EXIT: String(pullExit)}});
    assert.equal(result.status, actualImageId === expectedImageId && pullExit === 0 ? 0 : 2, result.stderr);
  } finally { fs.rmSync(temporary, {recursive: true}); }
}
}
console.log('open-platform immutable amd64 base pull and transport selection: 6 executable cases PASS');

const sourceIdentityCheck = gate.slice(gate.indexOf('[[ "${AURA_CI_SOURCE_SET_DIGEST:-}"'),
  gate.indexOf('[[ "$(uname -s)"'));
const mutationReceipt = gate.slice(gate.indexOf('if [[ "$DRAINED" != 1 &&'),
  gate.indexOf('[[ "$DRAINED" == 1 ]]'));
const failures = gate.slice(gate.indexOf('fatal() {'), gate.indexOf('info() {'));
for (const sourceDigest of ['a'.repeat(64), '', 'main']) {
  const artifacts = fs.mkdtempSync(path.join(os.tmpdir(), 'open-platform-mutation-receipt-'));
  try {
    const result = spawnSync('bash', ['-c', `set -Eeuo pipefail\n${failures}\n${sourceIdentityCheck}\n${mutationReceipt}`], {
      encoding:'utf8', env:{...process.env, ARTIFACTS:artifacts, AURA_CI_SOURCE_SET_DIGEST:sourceDigest,
        BACKLOG:'1', DRAINED:'0', RELEASE_MUTATION:'webhook-backlog', REF_SHA:'b'.repeat(40)}
    });
    assert.equal(result.status, sourceDigest.length === 64 ? 1 : 2, result.stderr);
    assert.equal(fs.existsSync(path.join(artifacts, 'release-image-receipt.json')), false);
    const receiptFile = path.join(artifacts, 'mutation-receipt.json');
    if (sourceDigest.length === 64) {
      const receipt = JSON.parse(fs.readFileSync(receiptFile, 'utf8'));
      assert.equal(receipt.sourceSetDigest, sourceDigest);
      assert.equal(receipt.guard, 'webhook-queue-drain');
      assert.equal(receipt.mutation, 'webhook-backlog');
      assert.equal(receipt.verdict, 'EXPECTED_RED');
      assert.equal(receipt.backlog, 1);
      assert.equal(receipt.ref, 'b'.repeat(40));
      assert.equal(receipt.releaseReceiptCreated, false);
    } else assert.equal(fs.existsSync(receiptFile), false);
  } finally { fs.rmSync(artifacts, {recursive:true}); }
}
console.log('open-platform expected-red receipt: 3 executable cases PASS');

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
    const modelKey = path.join(artifacts, 'private-model-key');
    fs.writeFileSync(modelKey, 'private-sentinel');
    const appKey = path.join(work, 'app-secrets.env');
    fs.writeFileSync(appKey, 'private-sentinel');
    fs.writeFileSync(path.join(artifacts, 'slo-fixture.json'), 'private-sentinel');
    const calls = path.join(work, 'docker-calls');
    const result = spawnSync('bash', ['-c', `set -Eeuo pipefail
${exitHandler}
docker() { printf '%s\n' "$*" >> "$CALLS"; }
trap cleanup EXIT
exit ${status}
`], {
      encoding:'utf8', env:{...process.env, WORK_ROOT:work, ARTIFACTS:artifacts, LOCK_DIR:lock,
        LOCK_TOKEN:'mine', CREDENTIAL_ARTIFACT:credentials, MODEL_EVENT_SECRET_FILE:modelKey, APP_SECRET_ENV:appKey, SLO_FIXTURE_ARTIFACT:path.join(artifacts, 'slo-fixture.json'), CALLS:calls, NET:'owned-network',
        DB_SAMPLE_PID:'', DB_SAMPLE_STOP:path.join(artifacts, 'db-sampling.stop'),
        APP:'owned-app', PG:'owned-pg', REDIS:'owned-redis', IMAGE:'owned-image'}
    });
    assert.equal(result.status, status, result.stderr);
    assert.equal(fs.readFileSync(database, 'utf8'), 'retained database bytes');
    assert.equal(fs.existsSync(credentials), false);
    assert.equal(fs.existsSync(modelKey), false);
    assert.equal(fs.existsSync(appKey), false);
    assert.equal(fs.existsSync(path.join(artifacts, 'slo-fixture.json')), false);
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

// Run the actual readiness loop against a Unix-only initialization phase.
const readiness = gate.slice(gate.indexOf('for attempt in $(seq 1 30)'),
  gate.indexOf('docker run --rm --network "$NET"'));
for (const timeout of [false, true]) {
  const temporary = fs.mkdtempSync(path.join(os.tmpdir(), 'open-platform-readiness-'));
  try {
    const calls = path.join(temporary, 'calls');
    const result = spawnSync('bash', ['-c', `set -Eeuo pipefail
attempts=0
docker() {
  attempts=$((attempts + 1))
  printf '%s\n' "$*" >> "$CALLS"
  # The socket succeeds immediately; TCP is unavailable during initialization.
  [[ "$*" == *"-h pg"* ]] || return 0
  [[ "$TIMEOUT" == false && "$attempts" -ge 2 ]]
}
sleep() { :; }
fatal() { printf '%s\n' "$*" >&2; exit 2; }
${readiness}
`], {encoding:'utf8', env:{...process.env, PG:'owned-pg', CALLS:calls, TIMEOUT:String(timeout)}});
    assert.equal(result.status, timeout ? 2 : 0, result.stderr);
    const commands = fs.readFileSync(calls, 'utf8').trim().split('\n');
    assert.equal(commands.length, timeout ? 30 : 2);
    assert.ok(commands.every(command => command.includes('pg_isready -h pg')));
  } finally { fs.rmSync(temporary, {recursive:true}); }
}
console.log('open-platform PostgreSQL TCP readiness: 2 executable cases PASS');
