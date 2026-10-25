import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import { closeSync, fstatSync, openSync, mkdtempSync, readFileSync, readdirSync, rmSync, statSync, mkdirSync, writeFileSync, existsSync, symlinkSync, cpSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path, { join, resolve } from 'node:path';
import {createHash} from 'node:crypto';
import {fileURLToPath} from 'node:url';
import {exportProductImage,unpackOciArchive,verifyOciLayout} from './export-product-image.mjs';
import {exportPlatformArtifacts,unpackPlatformArtifacts,verifyPlatformArtifacts,consumePlatformArtifacts} from './platform-artifact-package.mjs';
import {canonicalJson,readStructuredFile,resolveApplication,sha256Path} from './application-contract.mjs';
import {captureCoreWebInputs,coreWebEnvironment,createCoreWebComponent,copyCoreWebComponent,verifyCoreWebComponent,readCoreWebSelection}
  from './core-web-component.mjs';
import test from 'node:test';

const root = resolve(import.meta.dirname, '../..');
const stage = readFileSync(resolve(root, 'scripts/application/stage-core-only-artifacts.mjs'), 'utf8');
const sessionFacade = readFileSync(
  resolve(root, 'packages/dsl-runtime/src/shared/services/session.ts'),
  'utf8',
);
const deploy = readFileSync(resolve(root, 'scripts/application/auraboot-core-env.sh'), 'utf8');
const audit = readFileSync(resolve(root, 'scripts/application/audit-core-only-schema.sh'), 'utf8');
const oci = readFileSync(resolve(root, 'scripts/application/oci-layout.mjs'), 'utf8');
const productImageGate = readFileSync(resolve(root, 'scripts/application/run-product-release-image-gate.sh'), 'utf8');

test('product image reentry preserves the existing work root and refuses implicit replacement', t => {
  const directory = mkdtempSync(join(tmpdir(), 'product-work-root-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const script = productImageGate.slice(productImageGate.indexOf('WORK_ROOT="$WORK_PARENT/'), productImageGate.indexOf('CORE_RELEASE="$WORK_ROOT/'));
  const existing = join(directory, 'owned-edu'); mkdirSync(existing);
  writeFileSync(join(existing, 'ownership-proof'), 'preserve');
  const run = () => spawnSync('bash', ['-c', `fatal() { echo "$*" >&2; exit 2; }\n${script}`], {
    env: { ...process.env, WORK_PARENT: directory, AURA_CI_JOB_ID: 'owned', AURA_PRODUCT_ID: 'edu' }, encoding: 'utf8',
  });
  assert.equal(run().status, 2);
  assert.equal(readFileSync(join(existing, 'ownership-proof'), 'utf8'), 'preserve');
  rmSync(existing, { recursive: true });
  symlinkSync(directory, existing, 'dir');
  assert.equal(run().status, 2);
  rmSync(existing);
  assert.equal(run().status, 0);
  assert.ok(statSync(existing).isDirectory());
});

function productCleanupFixture(t, { keep = '0', stopStatus = 0, action = 'exit 0' } = {}) {
  const directory = mkdtempSync(join(tmpdir(), 'product-cleanup-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const work = join(directory, 'work'), artifacts = join(directory, 'artifacts'), calls = join(directory, 'calls');
  mkdirSync(join(work, 'release'), { recursive: true }); mkdirSync(join(artifacts, 'logs'), { recursive: true });
  writeFileSync(join(work, 'ownership-proof'), 'preserve');
  writeFileSync(join(work, 'release/lifecycle'), `#!/bin/bash\necho stop >> "$CALLS"\nexit ${stopStatus}\n`, { mode: 0o755 });
  const cleanup = productImageGate.match(/cleanup\(\) \{[\s\S]*?\n\}/)?.[0]; assert.ok(cleanup);
  const traps = productImageGate.slice(productImageGate.indexOf('\ntrap cleanup EXIT'), productImageGate.indexOf('\nCI=1 pnpm'));
  const script = `set -Eeuo pipefail
docker() { echo "docker $*" >> "$CALLS"; [[ "$1" != inspect ]]; }
info() { echo info >> "$CALLS"; }
${cleanup}
${traps}
${action}`;
  const run = spawnSync('bash', ['-c', script], { encoding: 'utf8', env: { ...process.env,
    CALLS: calls, ARTIFACTS: artifacts, KEEP_RUNTIME: keep, AURA_CI_JOB_ID: 'owned', NETWORK: 'owned-network',
    WORK_ROOT: work, STATE_ROOT: work, PRODUCT_RELEASE: join(work, 'release'), AURA_PRODUCT_LIFECYCLE: 'lifecycle',
    APP_CONTAINER: 'owned-app', PUBLISH_CONTAINER: 'owned-publisher', PLAYWRIGHT_CONTAINER: 'owned-playwright',
    PAYLOAD_CONTAINER: 'owned-payload', PG_CONTAINER: 'owned-pg', BUILD_PG_CONTAINER: 'owned-build-pg',
    REGISTRY_IMAGE: '', REGISTRY_DIGEST_REF: '', IMAGE_REF: '', IMAGE_ID: '', DOCKER_CONFIG_ROOT: join(work, 'docker-config'),
  } });
  return { run, work, artifacts, calls: readFileSync(calls, 'utf8') };
}

test('product cleanup preserves ownership and data on stop failure without hiding the original verdict', t => {
  for (const initial of [0, 1]) {
    const f = productCleanupFixture(t, { stopStatus: 7, action: `exit ${initial}` });
    assert.equal(f.run.status, initial || 2, f.run.stderr);
    assert.equal(readFileSync(join(f.work, 'ownership-proof'), 'utf8'), 'preserve');
    assert.doesNotMatch(f.calls, /docker (rm|network rm|image rm)/);
    assert.equal(f.calls.split('\n').filter(v => v === 'stop').length, 1);
    assert.match(readFileSync(join(f.artifacts, 'runtime-retained.txt'), 'utf8'), /cleanup=product-stop-failed/);
  }
  const succeeded = productCleanupFixture(t);
  assert.equal(succeeded.run.status, 0, succeeded.run.stderr);
  assert.equal(existsSync(succeeded.work), false);
});

test('product signal exit retains its nonzero status and executes cleanup once', t => {
  const f = productCleanupFixture(t, { keep: '1', action: 'kill -TERM $$' });
  assert.equal(f.run.status, 143, f.run.stderr);
  assert.equal(f.calls.split('\n').filter(v => v === 'info').length, 1);
  assert.match(readFileSync(join(f.artifacts, 'runtime-retained.txt'), 'utf8'), /exit_code=143/);
  assert.equal(readFileSync(join(f.work, 'ownership-proof'), 'utf8'), 'preserve');
});

test('product runtime argument guard accepts role lists and rejects shell syntax', () => {
  const guard = productImageGate.split('\n').find(line => line.includes('[[ "$runtime_arg" =~'));
  assert.ok(guard);
  const expression = guard.trim().replace(/\\$/, '').trim();
  const check = argument => spawnSync('bash', ['-c', `runtime_arg="$1"\n${expression}`, 'argument-guard', argument]).status;
  assert.equal(check('--aura.tenant.creator-roles=xy_school_admin,xy_teacher'), 0);
  assert.equal(check('--aura.application.bundle-root=/opt/auraboot'), 0);
  for (const argument of ['--x=a b', '--x=$(id)', '--x=`id`', '--x=a;b', '--x=a\nb', '--x=', '-x=a']) {
    assert.equal(check(argument), 1, argument);
  }
});

test('image verification creates a private per-attempt encryption key and refuses overwrite', t => {
  const directory = mkdtempSync(join(tmpdir(), 'release-image-crypto-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const fn = productImageGate.match(/create_verification_crypto_env\(\) \{[\s\S]*?\n\}/)?.[0];
  assert.ok(fn);
  const create = file => spawnSync('bash', ['-c', `umask 022\n${fn}\ncreate_verification_crypto_env "$1"`, 'crypto-env', file], { encoding: 'utf8' });
  const files = [join(directory, 'publication-and-runtime.env'), join(directory, 'next-attempt.env')];
  for (const file of files) {
    const result = create(file); assert.equal(result.status, 0, result.stderr);
    assert.equal(result.stdout, '', 'the generated key is never printed');
    const descriptor = openSync(file, 'r');
    try {
      assert.equal(fstatSync(descriptor).mode & 0o777, 0o600);
      const value = readFileSync(descriptor, 'utf8').trim().split('=');
      assert.equal(value[0], 'FIELD_ENCRYPTION_KEY');
      assert.equal(Buffer.from(value.slice(1).join('='), 'base64').length, 32);
    } finally {
      closeSync(descriptor);
    }
  }
  assert.notEqual(readFileSync(files[0], 'utf8'), readFileSync(files[1], 'utf8'));
  const original = readFileSync(files[0]); assert.notEqual(create(files[0]).status, 0);
  assert.deepEqual(readFileSync(files[0]), original);
  assert.notEqual(create('relative').status, 0);
  assert.match(productImageGate, /--env-file "\$RUNTIME_CRYPTO_ENV_FILE"/);
});

test('core release stages a compiled Web shell and a self-contained deployment driver', () => {
  assert.match(stage, /platform\/gradlew/);
  assert.match(stage, /'clean', 'bootJar', '--no-daemon', '-x', 'test'/);
  assert.match(stage, /materializeAndBuildCoreWeb/);
  assert.match(stage, /pnpm', \['--dir', 'web-admin', 'build'\]/);
  assert.match(stage, /pnpm-overrides\.json/);
  assert.match(stage, /hydrateCapsuleOverrides\(workRoot\)/);
  assert.match(stage, /pnpm: rootManifest\.pnpm/);
  assert.match(stage, /bin\/auraboot-core-env\.sh/);
  assert.match(stage, /bin\/application/);
  assert.match(stage, /web: \{ path: 'web', digest:/);
  assert.match(stage, /type: 'config', id: 'core-decisionops'/);
  assert.match(stage, /type: 'config', id: 'showcase'/);
  assert.match(sessionFacade, /export \* from '~\/shared\/services\/session'/);
});

test('product release-image gate is CI-only, Docker-only and evidence-backed', () => {
  assert.match(productImageGate, /SPRING_PROFILES_ACTIVE=community/);
  assert.doesNotMatch(productImageGate, /--aura\.persistence\.tenant-bypass-table-prefixes=/);
  assert.match(productImageGate, /AURA_CI_JOB_ID/);
  assert.match(productImageGate, /uname -s.*Linux/);
  assert.match(productImageGate, /uname -m.*x86_64/);
  assert.match(productImageGate, /AURA_OCI_BUILDER must be docker/);
  assert.match(productImageGate, /SPRING_PROFILES_ACTIVE=community/);
  assert.doesNotMatch(productImageGate, /--aura\.persistence\.tenant-bypass-table-prefixes=\S+/,
    'production image verification must retain tenant isolation without table-family bypasses');
  assert.match(productImageGate, /docker load --input/);
  assert.match(productImageGate, /ci\/isolated-release-network\.sh/);
  assert.match(productImageGate, /create_isolated_release_network "\$NETWORK" "\$ARTIFACTS\/network-allocation\.tsv"/);
  assert.match(productImageGate, /AURA_RELEASE_PUBLISH_REGISTRY:-0/);
  assert.match(productImageGate, /AURA_RELEASE_PUBLISH_REGISTRY must be 0 or 1/);
  assert.match(productImageGate, /required when remote publication is enabled/);
  assert.doesNotMatch(productImageGate, /AURA_RELEASE_REGISTRY:\?AURA_RELEASE_REGISTRY is required\}/);
  assert.match(productImageGate, /docker --config "\$DOCKER_CONFIG_ROOT" push/);
  assert.match(productImageGate, /PUSH_DIGEST" == "\$LAYOUT_DIGEST/);
  assert.match(productImageGate, /docker --config "\$DOCKER_CONFIG_ROOT" pull "\$REGISTRY_DIGEST_REF"/);
  assert.match(productImageGate, /PULLED_IMAGE_ID" == "\$IMAGE_ID/);
  assert.match(productImageGate, /"mode": "remote-registry" if publish_registry == "1" else "ci-local-oci"/);
  assert.match(productImageGate, /"remoteRegistryRequested": publish_registry == "1"/);
  assert.match(productImageGate, /"applicationLock": \{"path": "application\.lock", "digest": digest/);
  assert.match(productImageGate, /"sbom": \{"path": "sbom\.cdx\.json", "format": "CycloneDX-1\.5"/);
  assert.match(productImageGate, /fresh database migration failed/);
  assert.match(productImageGate, /registry-role-policy\.mjs/);
  assert.match(productImageGate, /RUNTIME_DB_ROLE=aura_runtime_ci/);
  assert.match(productImageGate, /REGISTRATION_DB_ROLE=aura_registry_ci/);
  assert.match(productImageGate, /SPRING_DATASOURCE_USERNAME="\$db_role"/);
  assert.match(productImageGate, /start_application_phase "\$PUBLISH_CONTAINER" "\$PUBLISH_DB_ROLE" "\$PUBLISH_DB_PASSWORD" publication/);
  assert.match(productImageGate, /start_application_phase "\$APP_CONTAINER" "\$RUNTIME_DB_ROLE" "\$RUNTIME_DB_PASSWORD" runtime/);
  assert.match(productImageGate, /publication\/runtime database authority separation failed/);
  assert.match(productImageGate, /AURA_REGISTRY_REGISTRATION_USERNAME="\$REGISTRATION_DB_ROLE"/);
  assert.match(productImageGate, /CI=1 pnpm --dir "\$CORE_ROOT" install --frozen-lockfile --ignore-scripts/);
  assert.match(productImageGate, /core-pnpm-install\.log/);
  assert.match(productImageGate, /NPM_CONFIG_REGISTRY=https:\/\/registry\.npmjs\.org AURA_OCI_BUILDER=docker/);
  assert.match(productImageGate, /product-pnpm-install\.log/);
  assert.match(productImageGate, /AURA_PRODUCT_PNPM_AUTO_INSTALL_PEERS must be true or false/);
  assert.match(productImageGate, /env CI=1 "\$\{PRODUCT_PNPM_ENV\[@\]\}" pnpm --dir "\$PRODUCT_ROOT" install/);
  assert.match(productImageGate, /POSTGRES_DB=aura_product_build_ci/);
  assert.match(productImageGate, /product-test-flyway\.log/);
  assert.match(productImageGate, /PostgreSQL init process complete; ready for start up\./);
  assert.match(productImageGate, /wait_for_final_postgres "\$BUILD_PG_CONTAINER" aura_product_build_ci 'product build'/);
  assert.match(productImageGate, /wait_for_final_postgres "\$PG_CONTAINER" aura_product_ci 'runtime'/);
  assert.match(productImageGate, /SPRING_DATASOURCE_PASSWORD=auraboot_ci/);
  assert.match(productImageGate, /docker rm -f "\$BUILD_PG_CONTAINER"/);
  assert.match(productImageGate, /docker logs "\$APP_CONTAINER" >"\$ARTIFACTS\/logs\/application-final\.log"/);
  assert.match(productImageGate, /playwright test --config playwright\.release\.config\.ts/);
  assert.match(productImageGate, /docker pull "\$PLAYWRIGHT_IMAGE"/);
  assert.match(productImageGate, /\[\[ "\$PLAYWRIGHT_VERSION" == "\$PLAYWRIGHT_IMAGE_VERSION" \]\]/);
  assert.match(productImageGate, /locked Playwright and image versions differ/);
  assert.match(productImageGate, /PW_TEST_CONNECT_WS_ENDPOINT/);
  assert.match(productImageGate, /release-image-receipt\.json/);
  assert.match(productImageGate, /release-control-receipt\.json/);
  assert.match(productImageGate, /"activeBindingCount": int\(binding_count\)/);
  assert.match(productImageGate, /"sourceLockIdentity": lock/);
  assert.match(productImageGate, /docker cp "\$PAYLOAD_CONTAINER:\/opt\/auraboot\/\." "\$IMAGE_PAYLOAD_ROOT\/"/);
  assert.match(productImageGate, /diff -qr "\$PRODUCT_RELEASE\/\$payload_directory" "\$IMAGE_PAYLOAD_ROOT\/\$payload_directory"/);
  assert.match(productImageGate, /cmp "\$PRODUCT_RELEASE\/\$payload_file" "\$IMAGE_PAYLOAD_ROOT\/\$payload_file"/);
  assert.match(productImageGate, /cmp "\$PRODUCT_ROOT\/app\.yaml" "\$IMAGE_PAYLOAD_ROOT\/app\.yaml"/);
  assert.doesNotMatch(productImageGate, /config\/\$AURA_PRODUCT_MIGRATION_OWNER/);
  assert.match(productImageGate, /git -C "\$PRODUCT_ROOT" archive "\$PRODUCT_SHA" "\$FIXTURE_REL"/);
  assert.match(productImageGate, /release acceptance fixture must be a tracked directory at the exact product commit/);
  assert.match(productImageGate, /AURA_RELEASE_FIXTURE_SOURCE_ROOT="\$PRODUCT_ROOT\/\$FIXTURE_REL"/);
  assert.match(productImageGate, /includedInReleaseImage/);
  assert.match(productImageGate, /AURA_RELEASE_MUTATION/);
  assert.match(productImageGate, /JSON\.parse\(fs\.readFileSync\(process\.argv\[1\],'utf8'\)\)/);
  assert.doesNotMatch(productImageGate, /const l=require\(process\.argv\[1\]\)/);
  assert.match(productImageGate, /controlled locked-plugin mutation was correctly rejected \(expected red\)/);
  assert.match(productImageGate, /releaseImagePushed: false/);
  assert.match(productImageGate, /"evidenceRoot": evidence_root/);
  assert.match(productImageGate, /"database": \{"engine": "PostgreSQL"/);
  assert.match(productImageGate, /"lifecycle": "ephemeral-ci"/);
  assert.match(productImageGate, /create-release-screenshot-manifest\.mjs/);
  assert.match(productImageGate, /AURA_RELEASE_SCREENSHOT_IDS/);
  assert.match(productImageGate, /product runtime args file must be tracked at the exact product commit/);
  assert.match(productImageGate, /invalid product runtime argument/);
  assert.match(productImageGate, /AURA_REGISTRY_REGISTRATION_JDBC_URL/);
  assert.match(productImageGate, /image\/Release\/binding identity mismatch/);
  assert.match(productImageGate, /source_lock_identity/);
  assert.doesNotMatch(productImageGate, /\bcontainer\s+(?:build|run|image)/);
});

test('core deployment forces an empty PF4J directory and verifies exact runtime identity', () => {
  assert.match(deploy, /AURA_APPLICATION_MODE=core-only/);
  assert.match(deploy, /-Daura\.plugins\.dir="\$STATE_ROOT\/empty-plugins"/);
  assert.match(deploy, /api\/application\/identity/);
  assert.match(deploy, /actual_identity.*expected_identity/);
  assert.match(deploy, /audit-core-only-schema\.sh/);
  assert.match(deploy, /code ~\* '\(\^\|_\)\(bpm\|crm\)/);
});

test('core bootstrap source contains no BPM or CRM product seed content', () => {
  const categorySeeder = readFileSync(resolve(root, 'platform/src/main/java/com/auraboot/framework/application/bootstrap/seeder/MarketplaceCategorySeeder.java'), 'utf8');
  const solutionSeeder = readFileSync(resolve(root, 'platform/src/main/java/com/auraboot/framework/application/bootstrap/seeder/SolutionSeeder.java'), 'utf8');
  const agentSeeder = readFileSync(resolve(root, 'platform/platform-agent/src/main/java/com/auraboot/framework/agent/integration/bootstrap/AgentTemplateSeeder.java'), 'utf8');
  const bootstrap = readFileSync(resolve(root, 'platform/src/main/resources/tenant-templates/default-bootstrap.json'), 'utf8');
  for (const source of [categorySeeder, solutionSeeder, agentSeeder]) {
    assert.doesNotMatch(source, /(^|[^a-z0-9])(crm|bpm)([^a-z0-9]|$)/i);
  }
  assert.doesNotMatch(bootstrap, /BPM/);
});

test('core schema audit keeps provider-neutral automation storage in core', () => {
  assert.doesNotMatch(audit, /'ab_automation_node_execution'/);
  assert.match(audit, /tablename ~ '\(\^\|_\)\(bpm\|crm\)\(_\|\$\)'/);
  assert.match(audit, /tablename LIKE 'se_%'/);
  assert.match(audit, /tablename LIKE 'ab_sla_%'/);
});

test('release OCI is built on a pinned Linux JRE and requires a real image builder', () => {
  assert.match(oci, /eclipse-temurin:21-jre@sha256:[0-9a-f]{64}/);
  assert.doesNotMatch(oci, /execFileSync\('container'/);
  assert.match(oci, /self-hosted Linux CI Docker builder \(linux\/amd64\)/);
  assert.match(oci, /AURA_OCI_BUILDER must be docker/);
  assert.match(oci, /'buildx', 'build'/);
  assert.match(oci, /'--platform', 'linux\/amd64'/);
  assert.match(oci, /mkdtempSync\(resolve\(dirname\(output\), '\.auraboot-image-'\)\)/);
  assert.doesNotMatch(oci, /application payload layer/);
});

test('acceptance migration paths stay under the declared fixture namespace', () => {
  const line = productImageGate.split('\n').find(l => l.includes('[[ -n "$FIXTURE_REL" && "$ACCEPTANCE_MIGRATIONS_REL"'));
  assert.ok(line);
  const guard = line.trim().replace(/\\$/, '').trim();
  const check = (fixture, path) => spawnSync('bash', ['-c',
    `FIXTURE_REL="$1"; ACCEPTANCE_MIGRATIONS_REL="$2"; ${guard}`, 'fixture-scope', fixture, path]).status;
  assert.equal(check('test-fixtures/workflow-demo', 'test-fixtures/workflow-demo/migrations'), 0);
  for (const [fixture, path] of [['', '/tmp/migrations'], ['test-fixtures/workflow-demo', '/tmp/migrations'],
    ['test-fixtures/workflow-demo', 'test-fixtures/other/migrations'],
    ['test-fixtures/workflow-demo', 'test-fixtures/workflow-demo/../migrations']]) {
    assert.equal(check(fixture, path), 1);
  }
});

test('corpus preflight rejects retries, missing results and fabricated summary counts', t => {
  const directory = mkdtempSync(join(tmpdir(), 'corpus-preflight-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const script = productImageGate.match(/node - "\$ARTIFACTS\/corpus\/preflight-results.json"[^\n]* <<'NODE'\n([\s\S]*?)\nNODE/)?.[1];
  assert.ok(script);
  const passed = () => ({ status: 'expected', results: [{ status: 'passed', retry: 0 }] });
  const report = () => ({ stats: { expected: 2, unexpected: 0, skipped: 0, flaky: 0 },
    suites: [{ specs: [{ tests: [passed(), passed()] }] }] });
  const check = value => spawnSync(process.execPath, ['-e', script.replace(
    "JSON.parse(fs.readFileSync(process.argv[2], 'utf8'))", 'JSON.parse(process.argv[1])').replaceAll('process.argv[3]', 'process.argv[2]'),
    JSON.stringify(value), '2'], { encoding: 'utf8' }).status;
  assert.equal(check(report()), 0);
  const mutations = [
    value => { value.suites = []; },
    value => { value.errors = [{ message: 'runner failed' }]; },
    value => { value.suites[0].specs[0].tests[0].results = []; },
    value => { value.suites[0].specs[0].tests[0].results[0].retry = 1; },
    value => { value.suites[0].specs[0].tests[0].results.push({ status: 'passed', retry: 1 }); },
    value => { value.stats.expected = 1; },
  ];
  for (const mutate of mutations) {
    const value = report(); mutate(value); assert.notEqual(check(value), 0);
  }
});

function ociFixture(t) {
  const root=mkdtempSync(path.join(tmpdir(),'native-oci-export-')),layout=path.join(root,'layout');
  t.after(()=>rmSync(root,{recursive:true,force:true}));mkdirSync(path.join(layout,'blobs','sha256'),{recursive:true});
  const blob=(value,mediaType)=>{const bytes=typeof value==='string'?value:JSON.stringify(value);
    const digest='sha256:'+createHash('sha256').update(bytes).digest('hex');
    writeFileSync(path.join(layout,'blobs','sha256',digest.slice(7)),bytes);return {digest,size:Buffer.byteLength(bytes),mediaType};};
  const config=blob({architecture:'amd64',os:'linux'},'application/vnd.oci.image.config.v1+json');
  const layer=blob('fixture layer bytes','application/vnd.oci.image.layer.v1.tar');
  const manifest=blob({schemaVersion:2,config,layers:[layer]},'application/vnd.oci.image.manifest.v1+json');
  writeFileSync(path.join(layout,'index.json'),JSON.stringify({schemaVersion:2,manifests:[manifest]}));
  writeFileSync(path.join(layout,'oci-layout'),' {"imageLayoutVersion":"1.0.0"}');
  const receiptFile=path.join(root,'release-image-receipt.json');
  const receipt={schemaVersion:1,status:'PASS',product:'fixture',freshDatabase:'PASS',readiness:'PASS',payload:'PASS',browserJourney:'PASS',ociLayoutDigest:manifest.digest,loadedImageId:config.digest,artifacts:{}};
  writeFileSync(receiptFile,JSON.stringify(receipt));return {root,layout,receiptFile,receipt,config,layer};
}
test('native OCI export retains verified bytes and updates only transport fields',async t=>{
  const f=ociFixture(t),result=await exportProductImage(f);
  assert.equal(result.transport.mode,'ci-oci-archive');assert.equal(result.loadedImageId,f.receipt.loadedImageId);
  const archive=path.join(f.root,'image.oci.tar');
  assert.equal(result.artifacts.imageArchive.digest,'sha256:'+createHash('sha256').update(readFileSync(archive)).digest('hex'));
  const extracted=execFileSync('tar',['-xOf',archive,'./index.json'],{encoding:'utf8'});
  assert.equal(JSON.parse(extracted).manifests[0].digest,result.ociLayoutDigest);
  await assert.rejects(exportProductImage(f),/already selected/);
});

test('containerd image IDs bind primary image manifests while layers and auxiliary roots stay rejected',async t=>{
  const f=ociFixture(t),primary=f.receipt.ociLayoutDigest;
  assert.ok(await verifyOciLayout(f.layout,primary,primary));
  await assert.rejects(verifyOciLayout(f.layout,primary,f.layer.digest),/tested loaded image/);
  const index=JSON.parse(readFileSync(path.join(f.layout,'index.json')));
  const other={...JSON.parse(readFileSync(path.join(f.layout,'blobs','sha256',primary.slice(7)))),annotations:{auxiliary:'other root'}};
  const bytes=JSON.stringify(other),digest='sha256:'+createHash('sha256').update(bytes).digest('hex');
  writeFileSync(path.join(f.layout,'blobs','sha256',digest.slice(7)),bytes);
  index.manifests.push({mediaType:'application/vnd.oci.image.manifest.v1+json',digest,size:Buffer.byteLength(bytes)});
  writeFileSync(path.join(f.layout,'index.json'),JSON.stringify(index));
  await assert.rejects(verifyOciLayout(f.layout,primary,digest),/tested loaded image/);
  assert.ok(await verifyOciLayout(f.layout,primary,primary));
  f.receipt.loadedImageId=primary;writeFileSync(f.receiptFile,JSON.stringify(f.receipt));
  assert.equal((await exportProductImage(f)).loadedImageId,primary);
  const nested=ociFixture(t),imageIndex=JSON.parse(readFileSync(path.join(nested.layout,'index.json')));
  const nestedBytes=JSON.stringify({schemaVersion:2,manifests:imageIndex.manifests});
  const nestedDigest='sha256:'+createHash('sha256').update(nestedBytes).digest('hex');
  writeFileSync(path.join(nested.layout,'blobs','sha256',nestedDigest.slice(7)),nestedBytes);
  writeFileSync(path.join(nested.layout,'index.json'),JSON.stringify({schemaVersion:2,manifests:[{
    mediaType:'application/vnd.oci.image.index.v1+json',digest:nestedDigest,size:Buffer.byteLength(nestedBytes),
  }]}));
  assert.ok(await verifyOciLayout(nested.layout,nestedDigest,nestedDigest));
  assert.ok(await verifyOciLayout(nested.layout,nestedDigest,nested.receipt.ociLayoutDigest));
  await assert.rejects(verifyOciLayout(nested.layout,nestedDigest,nested.layer.digest),/tested loaded image/);
});
test('wrong OCI digest, tested image, corrupt blobs and extra files cannot export',async t=>{
  for(const mutation of ['digest','image','blob','extra','symlink']) {
    const f=ociFixture(t);
    if(mutation==='digest')f.receipt.ociLayoutDigest='sha256:'+'f'.repeat(64);
    if(mutation==='image')f.receipt.loadedImageId='sha256:'+'f'.repeat(64);
    if(mutation==='blob')writeFileSync(path.join(f.layout,'blobs','sha256',f.layer.digest.slice(7)),'corrupt');
    if(mutation==='extra')writeFileSync(path.join(f.layout,'unindexed'),'extra');
    if(mutation==='symlink'){const target=path.join(f.layout,'blobs','sha256',f.layer.digest.slice(7));rmSync(target);symlinkSync('../../index.json',target);}
    writeFileSync(f.receiptFile,JSON.stringify(f.receipt));await assert.rejects(exportProductImage(f));
    assert.equal(existsSync(path.join(f.root,'image.oci.tar')),false);
    assert.equal(JSON.parse(readFileSync(f.receiptFile)).transport,undefined);
  }
});
test('partial native acceptance and an existing archive fail closed',async t=>{
  const f=ociFixture(t);f.receipt.readiness='FAIL';writeFileSync(f.receiptFile,JSON.stringify(f.receipt));
  await assert.rejects(exportProductImage(f),/completed native/);
  f.receipt.readiness='PASS';writeFileSync(f.receiptFile,JSON.stringify(f.receipt));
  writeFileSync(path.join(f.root,'image.oci.tar'),'preserved prior attempt');
  await assert.rejects(exportProductImage(f),/EEXIST/);
  assert.equal(readFileSync(path.join(f.root,'image.oci.tar'),'utf8'),'preserved prior attempt');
});
test('public exporter runs real tar without a container dependency',t=>{
  const f=ociFixture(t),cli=fileURLToPath(new URL('./export-product-image.mjs',import.meta.url));
  const good=spawnSync(process.execPath,[cli,f.layout,f.receiptFile],{encoding:'utf8'});
  assert.equal(good.status,0,good.stderr);assert.equal(JSON.parse(readFileSync(f.receiptFile)).transport.mode,'ci-oci-archive');
  assert.equal(spawnSync(process.execPath,[cli,f.layout,f.receiptFile,'--overwrite'],{encoding:'utf8'}).status,2);
});
test('OCI archive consumption binds external archive and image digests before accepting bytes',async t=>{
  const f=ociFixture(t),receipt=await exportProductImage(f),archive=path.join(f.root,'image.oci.tar');
  const args={archive,destination:path.join(f.root,'selected'),archiveSha256:receipt.artifacts.imageArchive.digest.slice(7),
    manifestDigest:f.receipt.ociLayoutDigest,imageId:f.receipt.loadedImageId};
  await unpackOciArchive(args);
  assert.equal(readFileSync(path.join(args.destination,'index.json'),'utf8'),readFileSync(path.join(f.layout,'index.json'),'utf8'));
  await assert.rejects(unpackOciArchive({...args,destination:path.join(f.root,'wrong'),archiveSha256:'f'.repeat(64)}),/checksum differs/);
  assert.equal(existsSync(path.join(f.root,'wrong')),false);
  await assert.rejects(unpackOciArchive(args));
  const bad=ociFixture(t),outside=path.join(bad.layout,'outside');writeFileSync(outside,'unindexed');
  const malicious=path.join(bad.root,'extra.tar');execFileSync('tar',['-C',bad.layout,'-cf',malicious,'.']);
  const digest=createHash('sha256').update(readFileSync(malicious)).digest('hex');
  const target=path.join(bad.root,'rejected');
  await assert.rejects(unpackOciArchive({...args,archive:malicious,destination:target,archiveSha256:digest}));
  assert.equal(existsSync(target),false);
});

function platformFixture(t) {
  const directory=mkdtempSync(join(tmpdir(),'platform-package-'));
  t.after(()=>rmSync(directory,{recursive:true,force:true}));
  const staged=join(directory,'native');mkdirSync(staged);
  const manifest=readStructuredFile(join(root,'distribution/application/examples/core-only/app.yaml'));
  const source={repository:'git@github.com:AuraBootTeam/auraboot.git',commit:'a'.repeat(40)};
  const image=ociFixture(t);cpSync(image.layout,join(staged,'oci'),{recursive:true});
  const coordinates=[['runtime','com.auraboot:runtime',manifest.platform.runtime],
    ['oci',manifest.platform.baseImage.id,manifest.platform.baseImage.version],
    ['maven','com.auraboot:platform-plugin-api',manifest.platform.pluginApi],
    ['npm','@auraboot/web-shell',manifest.platform.webShell],
    ['npm','@auraboot/plugin-sdk',manifest.platform.pluginSdk],
    ...manifest.platform.webPackages.map(a=>['npm',a.package,a.version]),
    ...manifest.backend.migrationSets.map(id=>['migration',id,'1.0.0']),
    ...manifest.config.importOrder.map(id=>['config',id,'1.0.0'])];
  const artifacts=coordinates.map(([type,id,version],i)=>{
    const localPath=type==='oci'?'oci':`payload-${i}`;
    if(type!=='oci')writeFileSync(join(staged,localPath),id+'\n');
    return {type,id,version,source,localPath,uri:`artifact:${localPath}`,digest:sha256Path(join(staged,localPath))};
  });
  const lock=resolveApplication(manifest,{schemaVersion:1,artifacts});
  const write=(name,value)=>writeFileSync(join(staged,name),JSON.stringify(value,null,2)+'\n');
  write('artifact-catalog.json',{schemaVersion:1,artifacts});write('application.lock',lock);
  for(const name of ['web','sbom','driver'])writeFileSync(join(staged,name),name);
  const receipt={schemaVersion:1,application:lock.application,source,lockIdentity:lock.identity,
    manifestDigest:lock.manifestDigest,graphDigest:lock.composition.graphDigest,
    verification:{result:'PASS',resolver:'application-contract-v1',checksum:'sha256'},
    image:{id:'auraboot-runtime',layout:'oci',digest:image.receipt.ociLayoutDigest},
    artifacts:lock.artifacts.map(({type,id,version,digest,localPath})=>({type,id,version,digest,localPath})),
    web:{path:'web',digest:sha256Path(join(staged,'web'))},
    sbom:{path:'sbom',digest:sha256Path(join(staged,'sbom'))},
    deploymentScript:{path:'driver',digest:sha256Path(join(staged,'driver'))}};
  write('release-receipt.json',receipt);
  mkdirSync(join(staged,'empty-directory'));
  return {root:staged,coreCommit:source.commit,archive:join(directory,'platform.tar'),directory,receipt,write};
}

function webComponentFixture(t) {
  const f=platformFixture(t),repo=join(f.directory,'source');mkdirSync(repo);
  const put=(name,body)=>{mkdirSync(path.dirname(join(repo,name)),{recursive:true});writeFileSync(join(repo,name),body);};
  for(const name of ['VERSION','LICENSE.txt','package.json','pnpm-lock.yaml','pnpm-workspace.yaml',
    'web-admin/package.json','web-admin/app/view.ts','packages/sdk/package.json',
    'scripts/application/stage-core-only-artifacts.mjs','scripts/application/core-web-component.mjs',
    'scripts/application/application-contract.mjs','distribution/application/manifest.json','platform/Backend.java'])put(name,name+'\n');
  for(const name of ['package.json','web-admin/package.json','packages/sdk/package.json'])put(name,'{}\n');
  put('pnpm-workspace.yaml','packages: []\n');
  put('VERSION',readFileSync(join(root,'VERSION')));
  put('distribution/application/examples/core-only/app.yaml',readFileSync(join(root,'distribution/application/examples/core-only/app.yaml')));
  const git=args=>execFileSync('git',args,{cwd:repo,encoding:'utf8'}).trim();
  git(['init','-q']);git(['config','user.name','Fixture']);git(['config','user.email','fixture@example.invalid']);
  git(['remote','add','origin','git@github.com:AuraBootTeam/auraboot.git']);
  const commit=()=>{git(['add','.']);git(['commit','-qm','fixture source']);return captureCoreWebInputs(repo);};
  const before=commit(),catalog=JSON.parse(readFileSync(join(f.root,'artifact-catalog.json')));
  for(const artifact of catalog.artifacts)artifact.source=before.source;
  rmSync(join(f.root,'web'));mkdirSync(join(f.root,'web'));writeFileSync(join(f.root,'web/app.js'),'compiled fixture Web bytes\n');
  const lock=resolveApplication(readStructuredFile(join(root,'distribution/application/examples/core-only/app.yaml')),catalog);
  Object.assign(f.receipt,{source:before.source,lockIdentity:lock.identity,graphDigest:lock.composition.graphDigest,
    web:{path:'web',digest:sha256Path(join(f.root,'web'))},webComponent:createCoreWebComponent({root:f.root,catalog,before,after:before})});
  f.write('artifact-catalog.json',catalog);f.write('application.lock',lock);f.write('release-receipt.json',f.receipt);
  return {...f,coreCommit:before.source.commit,repo,put,commit,before,catalog};
}

function frozenWebSelection(f,exported) {
  const selection=join(f.directory,'core-web-selection.json'),inputs=join(f.directory,'dataset.json');
  const document={schemaVersion:1,kind:'native-core-web-selection',
    producer:{attemptId:'original-attempt',candidateDigest:'a'.repeat(64),qualificationDigest:'b'.repeat(64),job:'original-job'},
    source:f.receipt.source,lockIdentity:f.receipt.lockIdentity,archiveSha256:exported.archive.sha256,
    inputDigest:f.receipt.webComponent.inputDigest,
    componentDigest:'sha256:'+createHash('sha256').update(canonicalJson(f.receipt.webComponent)).digest('hex')};
  const write=()=>{
    writeFileSync(selection,JSON.stringify(document,null,2)+'\n');
    writeFileSync(inputs,JSON.stringify({files:[f.archive,selection].map(file=>({file,sha256:sha256Path(file).slice(7)}))}));
  };
  write();return {selection,inputs,document,write};
}

test('Web component input identity ignores only backend source and preserves original producer bytes',async t=>{
  const f=webComponentFixture(t),selected=await exportPlatformArtifacts(f);
  const frozen=frozenWebSelection(f,selected),{inputs}=frozen;
  const managed=readCoreWebSelection({archive:f.archive,...frozen});
  const original=join(f.directory,'selected'),consumption=await consumePlatformArtifacts({...f,inputs,destination:original});
  f.put('platform/Backend.java','corrected backend\n');const current=f.commit();
  assert.notEqual(current.source.commit,f.before.source.commit);assert.equal(current.inputDigest,f.before.inputDigest);
  const destination=join(f.directory,'new-platform');mkdirSync(destination);
  const proof=copyCoreWebComponent({root:original,destination,catalog:f.catalog,receipt:f.receipt,current,
    consumption,datasetReceiptSha256:sha256Path(inputs).slice(7),selection:managed.document,selectionSha256:managed.selectionSha256});
  assert.deepEqual(proof.webComponent.source,f.before.source);
  assert.deepEqual(proof.webConsumption.selection,frozen.document);
  assert.equal(sha256Path(join(destination,'web')),f.receipt.web.digest);
  for(const artifact of f.catalog.artifacts.filter(item=>item.type==='npm'))
    assert.equal(sha256Path(join(destination,artifact.localPath)),artifact.digest);
  // Complete the next native metadata fixture with the new backend origin.
  cpSync(original,destination,{recursive:true,force:false});
  const catalog=structuredClone(f.catalog);
  for(const artifact of catalog.artifacts)if(artifact.type!=='npm')artifact.source=current.source;
  const lock=resolveApplication(readStructuredFile(join(root,'distribution/application/examples/core-only/app.yaml')),catalog);
  const receipt={...f.receipt,...proof,source:current.source,lockIdentity:lock.identity,graphDigest:lock.composition.graphDigest};
  for(const [name,value] of Object.entries({'artifact-catalog.json':catalog,'application.lock':lock,'release-receipt.json':receipt}))
    writeFileSync(join(destination,name),JSON.stringify(value));
  await verifyPlatformArtifacts({root:destination,coreCommit:current.source.commit});
  assert.equal(verifyCoreWebComponent({root:destination,catalog,receipt}).source.commit,f.before.source.commit);
  delete receipt.webConsumption;writeFileSync(join(destination,'release-receipt.json'),JSON.stringify(receipt));
  await assert.rejects(verifyPlatformArtifacts({root:destination,coreCommit:current.source.commit}),/without consumption proof/);
});

test('managed Web selection rejects unbound, duplicate, linked and changed evidence before consumption',async t=>{
  const f=webComponentFixture(t),exported=await exportPlatformArtifacts(f),frozen=frozenWebSelection(f,exported);
  const read=()=>readCoreWebSelection({archive:f.archive,...frozen});
  assert.equal(read().document.source.commit,f.coreCommit);
  for(const mutate of [
    ()=>writeFileSync(frozen.inputs,JSON.stringify({files:[]})),
    ()=>{const value=JSON.parse(readFileSync(frozen.inputs));value.files.push(value.files[1]);writeFileSync(frozen.inputs,JSON.stringify(value));},
    ()=>{const value=JSON.parse(readFileSync(frozen.inputs));value.files[1].sha256='f'.repeat(64);writeFileSync(frozen.inputs,JSON.stringify(value));},
    ()=>{frozen.document.archiveSha256='f'.repeat(64);frozen.write();},
    ()=>{frozen.document.producer.job='';frozen.write();},
    ()=>{frozen.document.extra='unreviewed';frozen.write();},
    ()=>{writeFileSync(frozen.selection,JSON.stringify(frozen.document));
      const value=JSON.parse(readFileSync(frozen.inputs));value.files[1].sha256=sha256Path(frozen.selection).slice(7);writeFileSync(frozen.inputs,JSON.stringify(value));},
  ]) {
    const original=structuredClone(frozen.document);mutate();assert.throws(read);
    for(const key of Object.keys(frozen.document))delete frozen.document[key];Object.assign(frozen.document,original);frozen.write();
  }
  const linked=join(f.directory,'linked-selection.json');symlinkSync(frozen.selection,linked);
  assert.throws(()=>readCoreWebSelection({archive:f.archive,selection:linked,inputs:frozen.inputs}),/Regular absolute/);
  assert.equal(read().document.source.commit,f.coreCommit,'restored original bytes remain consumable');
});

test('changed Web source, lock, recipe, mode and toolchain reject selection without writing output',t=>{
  for(const mode of ['source','lock','recipe','mode','toolchain','environment','denominator']) {
    const f=webComponentFixture(t);
    if(mode==='source')f.put('web-admin/app/view.ts','changed Web\n');
    if(mode==='lock')f.put('pnpm-lock.yaml','changed locked dependency\n');
    if(mode==='recipe')f.put('scripts/application/stage-core-only-artifacts.mjs','changed recipe\n');
    if(mode==='mode')execFileSync('chmod',['+x',join(f.repo,'web-admin/app/view.ts')]);
    let current=['toolchain','environment','denominator'].includes(mode)?structuredClone(f.before):f.commit();
    if(mode==='toolchain')current.inputs.toolchain.pnpm='99.0.0';
    if(mode==='environment')current.inputs.environment.AURA_COMPOSITION_KEY='sha256:'+'e'.repeat(64);
    if(mode==='denominator')current.inputs.files.pop();
    const destination=join(f.directory,'refused');mkdirSync(destination);
    assert.throws(()=>copyCoreWebComponent({root:f.root,destination,catalog:f.catalog,receipt:f.receipt,current,
      consumption:{source:f.receipt.source,lockIdentity:f.receipt.lockIdentity,archive:{sha256:'a'.repeat(64)}},
      datasetReceiptSha256:'b'.repeat(64)}),/effective inputs/);
    assert.equal(existsSync(join(destination,'web')),false);
  }
});

test('Web proof rejects output tampering, missing historical proof, unsafe inputs and incomplete provenance',t=>{
  for(const mode of ['web','output-mode','npm','history','consumption','link']) {
    const f=webComponentFixture(t);
    if(mode==='web')writeFileSync(join(f.root,'web/app.js'),'changed bytes');
    if(mode==='output-mode')execFileSync('chmod',['+x',join(f.root,'web/app.js')]);
    if(mode==='npm')writeFileSync(join(f.root,f.receipt.webComponent.artifacts[0].localPath),'changed npm');
    if(mode==='history')delete f.receipt.webComponent;
    if(mode==='consumption')f.receipt.webConsumption={schemaVersion:1};
    if(mode==='link'){rmSync(join(f.root,'web/app.js'));symlinkSync('/tmp',join(f.root,'web/app.js'));}
    const destination=join(f.directory,'refused');mkdirSync(destination);
    assert.throws(()=>copyCoreWebComponent({root:f.root,destination,catalog:f.catalog,receipt:f.receipt,current:f.before,
      consumption:{source:f.receipt.source,lockIdentity:f.receipt.lockIdentity,archive:{sha256:'a'.repeat(64)}},
      datasetReceiptSha256:'b'.repeat(64)}));assert.equal(existsSync(join(destination,'web')),false);
  }
  const f=webComponentFixture(t);f.put('.gitignore','web-admin/app/.env.local\n');f.commit();
  f.put('web-admin/app/.env.local','SECRET=not-a-build-input\n');
  assert.throws(()=>captureCoreWebInputs(f.repo),/Untracked or ignored/);
});

test('declared Web environment drops ambient public values and credentials and guards public stage wiring',()=>{
  const environment=coreWebEnvironment({PATH:'/usr/bin',HOME:'/fixture',
    VITE_SECRET:'not-forwarded',GITHUB_TOKEN:'not-forwarded'});
  assert.equal(environment.NODE_OPTIONS,undefined);assert.equal(environment.VITE_SECRET,undefined);
  assert.equal(environment.GITHUB_TOKEN,undefined);assert.equal(environment.NPM_CONFIG_USERCONFIG,'/dev/null');
  assert.throws(()=>coreWebEnvironment({NPM_CONFIG_REGISTRY:'https://user:secret@example.invalid'}),/Credential-free/);
  assert.throws(()=>coreWebEnvironment({AURA_COMPOSITION_KEY:'latest'}),/composition key/);
  assert.throws(()=>coreWebEnvironment({NODE_OPTIONS:'--require malicious'}),/bounded Web heap/);
  assert.equal(coreWebEnvironment({NODE_OPTIONS:'--max-old-space-size=4096'}).NODE_OPTIONS,'--max-old-space-size=4096');
  assert.match(stage,/consumePlatformArtifacts/);assert.match(stage,/copyCoreWebComponent/);
  assert.match(stage,/createCoreWebComponent/);assert.match(stage,/if\(!selectedWeb\)materializeAndBuildCoreWeb/);
});

test('public Core stager rejects missing Web proof before backend work and cleans selected bytes on compile failure',async t=>{
  for(const mode of ['legacy','raw','managed','changed-selection']) {
    const legacy=mode==='legacy';
    const f=webComponentFixture(t),marker=join(f.directory,'backend-invoked');
    // Failure-only tripwire: no successful Java build or Linux evidence is simulated.
    f.put('platform/gradlew',`#!/usr/bin/env node\nrequire('node:fs').writeFileSync(${JSON.stringify(marker)},'invoked');process.exit(99);\n`);
    execFileSync('chmod',['+x',join(f.repo,'platform/gradlew')]);const current=f.commit();
    if(legacy){delete f.receipt.webComponent;f.write('release-receipt.json',f.receipt);}
    const selected=await exportPlatformArtifacts(f),inputs=join(f.directory,'dataset.json');
    let selectionArgs=['--web-platform-source',f.coreCommit];
    if(mode==='managed'||mode==='changed-selection') {
      const frozen=frozenWebSelection(f,selected);
      if(mode==='changed-selection'){frozen.document.componentDigest='sha256:'+'f'.repeat(64);frozen.write();}
      selectionArgs=['--web-platform-selection',frozen.selection];
    } else writeFileSync(inputs,JSON.stringify({files:[{file:f.archive,sha256:selected.archive.sha256}]}));
    const output=join(f.directory,'output');
    const before=new Set(readdirSync(tmpdir()).filter(name=>name.startsWith('auraboot-selected-web-')));
    const result=spawnSync(process.execPath,[join(root,'scripts/application/stage-core-only-artifacts.mjs'),
      '--repo-root',f.repo,'--output',output,'--web-platform-archive',f.archive,...selectionArgs],
      {encoding:'utf8',env:{...process.env,AURA_CI_DATASET_INPUTS:inputs}});
    assert.equal(result.status,1,result.stderr);
    assert.match(result.stderr,legacy ? /Web effective inputs differ or are missing/ : mode==='changed-selection'
      ? /Frozen Web selection differs/ : /gradlew.*failed/);
    assert.equal(existsSync(marker),!legacy&&mode!=='changed-selection');assert.equal(existsSync(output),false);
    assert.deepEqual(new Set(readdirSync(tmpdir()).filter(name=>name.startsWith('auraboot-selected-web-'))),before);
    assert.equal(current.inputDigest,f.before.inputDigest);
  }
});

test('native platform package preserves original catalog, lock and full files without recompilation',async t=>{
  const f=platformFixture(t),before=await verifyPlatformArtifacts(f),selected=await exportPlatformArtifacts(f);
  const destination=join(f.directory,'consumer');
  const consumed=await unpackPlatformArtifacts({...f,destination,archiveSha256:selected.archive.sha256});
  assert.equal(consumed.lockIdentity,selected.lockIdentity);assert.equal(consumed.releaseQualification,false);
  assert.deepEqual((await verifyPlatformArtifacts({...f,root:destination})).entries,before.entries);
  const original=readFileSync(f.archive);await assert.rejects(exportPlatformArtifacts(f),/EEXIST/);
  assert.deepEqual(readFileSync(f.archive),original);
  await assert.rejects(unpackPlatformArtifacts({...f,destination,archiveSha256:selected.archive.sha256}),/fresh/);
});

test('platform reuse rejects stale selection, source, catalog, lock, receipt and auxiliary bytes',async t=>{
  for(const mode of ['source','catalog','lock','receipt','auxiliary','link']) {
    const f=platformFixture(t);
    if(mode==='source')f.coreCommit='b'.repeat(40);
    if(mode==='catalog') {
      const catalog=JSON.parse(readFileSync(join(f.root,'artifact-catalog.json')));
      catalog.artifacts[0].source.commit='b'.repeat(40);f.write('artifact-catalog.json',catalog);
    }
    if(mode==='lock') {
      const lock=JSON.parse(readFileSync(join(f.root,'application.lock')));
      lock.artifacts[0].digest=`sha256:${'f'.repeat(64)}`;f.write('application.lock',lock);
    }
    if(mode==='receipt'){f.receipt.artifacts.pop();f.write('release-receipt.json',f.receipt);}
    if(mode==='auxiliary')writeFileSync(join(f.root,'driver'),'changed');
    if(mode==='link')symlinkSync('/tmp',join(f.root,'linked-directory'));
    await assert.rejects(exportPlatformArtifacts(f));assert.equal(existsSync(f.archive),false,mode);
  }
  const f=platformFixture(t),selected=await exportPlatformArtifacts(f),destination=join(f.directory,'wrong');
  await assert.rejects(unpackPlatformArtifacts({...f,destination,archiveSha256:'f'.repeat(64)}),/checksum differs/);
  assert.equal(existsSync(destination),false);
  await assert.rejects(unpackPlatformArtifacts({...f,destination,archiveSha256:selected.archive.sha256,coreCommit:'b'.repeat(40)}),/matching native Core/);
});

test('platform unpack refuses escaping, duplicate and linked members before writing a destination',async t=>{
  for(const mode of ['escape','duplicate','link']) {
    const f=platformFixture(t);
    execFileSync('python3',['-c',`import io,sys,tarfile
archive,mode=sys.argv[1:]
with tarfile.open(archive,'w') as t:
 m=tarfile.TarInfo('../outside' if mode=='escape' else 'file');m.size=1;t.addfile(m,io.BytesIO(b'x'))
 if mode=='duplicate': t.addfile(m,io.BytesIO(b'x'))
 if mode=='link':
  m=tarfile.TarInfo('linked');m.type=tarfile.SYMTYPE;m.linkname='/tmp';t.addfile(m)
`,f.archive,mode]);
    const destination=join(f.directory,'consumer'),archiveSha256=createHash('sha256').update(readFileSync(f.archive)).digest('hex');
    await assert.rejects(unpackPlatformArtifacts({...f,destination,archiveSha256}));
    assert.equal(existsSync(destination),false,mode);assert.equal(existsSync(join(f.directory,'outside')),false);
  }
});

test('platform consumer uses the original frozen dataset selection and never reselects a changed archive',async t=>{
  const f=platformFixture(t),selected=await exportPlatformArtifacts(f),inputs=join(f.directory,'dataset-inputs.json');
  const record={file:f.archive,sha256:selected.archive.sha256};
  writeFileSync(inputs,JSON.stringify({files:[record]}));
  const result=await consumePlatformArtifacts({...f,inputs,destination:join(f.directory,'consumer')});
  assert.equal(result.archive.sha256,record.sha256);
  for(const [name,files] of [['missing',[]],['duplicate',[record,record]],['changed',[{...record,sha256:'f'.repeat(64)}]]]) {
    writeFileSync(inputs,JSON.stringify({files}));const destination=join(f.directory,name);
    await assert.rejects(consumePlatformArtifacts({...f,inputs,destination}));assert.equal(existsSync(destination),false);
  }
});
