import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdirSync, mkdtempSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import test from 'node:test';

const root = resolve(import.meta.dirname, '../..');
const stage = readFileSync(resolve(root, 'scripts/application/run-product-release-archive-stage.sh'), 'utf8');
const receiptTool = resolve(root, 'scripts/application/product_release_archive_receipt.py');
const receiptToolSource = readFileSync(receiptTool, 'utf8');

const sha40 = (character) => character.repeat(40);
const sha64 = (value) => createHash('sha256').update(value).digest('hex');

function initSourceCheckout(directory, seed) {
  execFileSync('git', ['init', '--quiet', directory]);
  execFileSync('git', ['-C', directory, 'config', 'user.email', 'contract@example.invalid']);
  execFileSync('git', ['-C', directory, 'config', 'user.name', 'contract']);
  writeFileSync(join(directory, 'seed.txt'), `${seed}\n`);
  execFileSync('git', ['-C', directory, 'add', 'seed.txt']);
  execFileSync('git', ['-C', directory, 'commit', '--quiet', '-m', seed]);
  return execFileSync('git', ['-C', directory, 'rev-parse', 'HEAD'], { encoding: 'utf8' }).trim();
}

// The portable source-set digest contract: canonical JSON with sorted keys and
// compact separators, exactly as validated by product_release_archive_receipt.py.
function canonicalJson(value) {
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(',')}]`;
  if (value && typeof value === 'object') {
    return `{${Object.keys(value).sort()
      .map((key) => `${JSON.stringify(key)}:${canonicalJson(value[key])}`).join(',')}}`;
  }
  return JSON.stringify(value);
}

function canonicalSourceSetDigest(content) {
  return createHash('sha256').update(canonicalJson(content)).digest('hex');
}

function frozenInputsEvidence({ coreOid, productOid, builderOid }) {
  const evidence = {
    contractVersion: 1,
    ciJobId: 'job-archive-contract',
    sourceSetDigest: '0'.repeat(64),
    manifestDigest: '1'.repeat(64),
    suiteDigest: '2'.repeat(64),
    repositories: { 'aura-bpm': productOid, auraboot: coreOid },
    builderRepositories: { 'auraboot-workspace': builderOid },
    executor: { repository: 'auraboot-workspace', commit: builderOid,
      path: 'tools/regression/orchestrator.sh', sha256: '3'.repeat(64) },
    checkouts: [],
  };
  evidence.sourceSetDigest = canonicalSourceSetDigest({
    builderRepositories: evidence.builderRepositories,
    repositories: evidence.repositories,
    suiteDigest: evidence.suiteDigest,
  });
  return evidence;
}

function receiptFixture(directory) {
  const coreOid = initSourceCheckout(join(directory, 'core'), 'core');
  const productOid = initSourceCheckout(join(directory, 'product'), 'product');
  const builderOid = sha40('b');
  mkdirSync(join(directory, 'artifacts'), { recursive: true });
  writeFileSync(join(directory, 'artifacts', 'release-image-receipt.json'), JSON.stringify({
    schemaVersion: 1, status: 'PASS', product: 'aura-bpm',
    coreCommit: coreOid, productCommit: productOid,
    loadedImageId: `sha256:${'a'.repeat(64)}`,
  }));
  writeFileSync(join(directory, 'artifacts', 'image-refs.env'),
    `IMAGE_ID=sha256:${'a'.repeat(64)}\nOCI_LAYOUT_DIGEST=sha256:${'c'.repeat(64)}\n`);
  writeFileSync(join(directory, 'artifacts', 'images-archive.tar.gz'), 'archive-bytes');
  writeFileSync(join(directory, 'artifacts', 'control-bundle.tar.gz'), 'bundle-bytes');
  writeFileSync(join(directory, 'frozen-inputs.json'),
    JSON.stringify(frozenInputsEvidence({ coreOid, productOid, builderOid })));
  return {
    directory, coreOid, productOid, builderOid,
    args: [receiptTool, 'create',
      '--product', 'aura-bpm',
      '--frozen-inputs', join(directory, 'frozen-inputs.json'),
      '--output', join(directory, 'release-archive-receipt.json'),
      '--release-id', 'aura-bpm-contract-v1',
      '--ci-job-id', 'job-archive-contract',
      '--builder-id', 'aura-ci-linux-amd64-1',
      '--platform', 'linux/amd64',
      '--docker-version', '29.1.3',
      '--gate-receipt', join(directory, 'artifacts', 'release-image-receipt.json'),
      '--image-refs', join(directory, 'artifacts', 'image-refs.env'),
      '--control-bundle', join(directory, 'artifacts', 'control-bundle.tar.gz'),
      '--image-archive', join(directory, 'artifacts', 'images-archive.tar.gz'),
      '--source', `core=${join(directory, 'core')}`,
      '--source', `bpm=${join(directory, 'product')}`],
  };
}

function createWithProduct(fixture, product) {
  const args = fixture.args.map((value, index, all) => (all[index - 1] === '--product' ? product : value));
  return spawnSync('python3', args, { encoding: 'utf8' });
}

test('archive stage is CI-only, archive-only and refuses the quote-bom contract', () => {
  assert.match(stage, /uname -s.*Linux/);
  assert.match(stage, /uname -m.*x86_64/);
  assert.match(stage, /quote-bom\) fatal/);
  assert.match(stage, /archive-checksum-byte/);
  assert.match(stage, /exit 42/);
  assert.match(stage, /gzip -t/);
  assert.match(stage, /docker save "\$IMAGE_ID" \| gzip/);
  assert.match(stage, /archive reload did not restore the verified image ID/);
  assert.match(stage, /release-archive-receipt\.json/);
  assert.match(stage, /mv -Tn "\$release_store_tmp" "\$release_store_final"/);
  assert.match(stage, /chmod 0444/);
  assert.match(stage, /release-archive\.lock/);
  assert.match(stage, /verify-freeze/);
  assert.match(stage, /run-product-release-image-gate\.sh/);
  assert.doesNotMatch(stage, /docker (push|pull|login)/,
    'the archive stage never requires registry credentials or push/pull');
  assert.doesNotMatch(stage, /AURA_OCI_BUILDER/,
    'the archive stage never builds; it seals a completed gate attempt');
});

test('archive stage requires the PASS gate receipt of the same attempt', () => {
  assert.match(stage, /gate receipt missing; run the product release-image gate first/);
  assert.match(stage, /schemaVersion !== 1 \|\| receipt\.status !== "PASS"/);
  assert.match(stage, /receipt\.product !== process\.argv\[2\]/);
  assert.match(stage, /loadedImageId/);
});

test('receipt tool admits only the aura-bpm contract and keeps quote-bom reserved', () => {
  assert.match(receiptToolSource, /ADMITTED_PRODUCTS = \{"aura-bpm"/);
  assert.match(receiptToolSource, /quote-bom/);
  assert.match(receiptToolSource, /schemaVersion.*3/);
  assert.match(receiptToolSource, /-release-image-receipt/);
  assert.match(receiptToolSource, /os\.link\(temporary, path\)/);
});

test('receipt tool seals the frozen source set, archive and control bundle', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'archive-receipt-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const fixture = receiptFixture(directory);
  const created = spawnSync('python3', fixture.args, { encoding: 'utf8' });
  assert.equal(created.status, 0, created.stderr);

  const receiptPath = join(directory, 'release-archive-receipt.json');
  const receipt = JSON.parse(readFileSync(receiptPath, 'utf8'));
  assert.equal(receipt.schemaVersion, 3);
  assert.equal(receipt.kind, 'aura-bpm-release-image-receipt');
  assert.equal(receipt.releaseId, 'aura-bpm-contract-v1');
  assert.deepEqual(receipt.transport, { mode: 'ci-archive' });
  assert.equal(receipt.verdict, 'PASS');
  assert.equal(receipt.smoke.verdict, 'PASS');
  assert.equal(receipt.sources.core.oid, fixture.coreOid);
  assert.equal(receipt.sources.bpm.oid, fixture.productOid);
  assert.deepEqual(receipt.frozenInputs.repositories,
    { auraboot: fixture.coreOid, 'aura-bpm': fixture.productOid });
  assert.deepEqual(receipt.frozenInputs.builderRepositories, { 'auraboot-workspace': fixture.builderOid });
  assert.equal(receipt.artifacts.images.application.ref, `sha256:${'a'.repeat(64)}`);
  assert.equal(receipt.artifacts.images.application.layoutDigest, `sha256:${'c'.repeat(64)}`);
  assert.equal(receipt.artifacts.imageArchive.fileName, 'images-archive.tar.gz');
  assert.equal(receipt.artifacts.imageArchive.sha256, sha64('archive-bytes'));
  assert.equal(receipt.artifacts.controlBundle.fileName, 'control-bundle.tar.gz');

  // The receipt is create-once and read-only.
  assert.equal(statSync(receiptPath).mode & 0o777, 0o444);

  const verified = spawnSync('python3', [
    receiptTool, 'verify',
    '--product', 'aura-bpm',
    '--file', receiptPath,
    '--expected-sha256', sha64(readFileSync(receiptPath)),
    '--release-id', 'aura-bpm-contract-v1',
    '--artifact-dir', join(directory, 'artifacts'),
    '--source', `core=${join(directory, 'core')}`,
    '--source', `bpm=${join(directory, 'product')}`,
  ], { encoding: 'utf8' });
  assert.equal(verified.status, 0, verified.stderr);
});

test('receipt tool rejects foreign contracts and mismatched evidence', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'archive-receipt-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const fixture = receiptFixture(directory);

  const quote = createWithProduct(fixture, 'quote-bom');
  assert.notEqual(quote.status, 0);
  assert.match(quote.stderr, /dedicated receipt contract|no admitted archive receipt contract/);

  const tamperedFrozen = JSON.parse(readFileSync(join(directory, 'frozen-inputs.json'), 'utf8'));
  tamperedFrozen.repositories['aura-bpm'] = sha40('9');
  writeFileSync(join(directory, 'frozen-inputs-tampered.json'), JSON.stringify(tamperedFrozen));
  const mismatched = spawnSync('python3', fixture.args.map((value, index, all) =>
    (all[index - 1] === '--frozen-inputs' ? join(directory, 'frozen-inputs-tampered.json') : value)),
  { encoding: 'utf8' });
  assert.notEqual(mismatched.status, 0);
  assert.match(mismatched.stderr, /source OID mismatch/);

  // A dirty product checkout can never be sealed.
  writeFileSync(join(directory, 'product', 'dirty.txt'), 'uncommitted\n');
  const dirty = spawnSync('python3', fixture.args, { encoding: 'utf8' });
  assert.notEqual(dirty.status, 0);
  assert.match(dirty.stderr, /source checkout is dirty/);
});

test('verify rejects wrong digests and corrupted artifacts', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'archive-receipt-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const fixture = receiptFixture(directory);
  const created = spawnSync('python3', fixture.args, { encoding: 'utf8' });
  assert.equal(created.status, 0, created.stderr);

  const receiptPath = join(directory, 'release-archive-receipt.json');
  const verifyArgs = (digest) => [
    receiptTool, 'verify',
    '--product', 'aura-bpm',
    '--file', receiptPath,
    '--expected-sha256', digest,
    '--release-id', 'aura-bpm-contract-v1',
    '--artifact-dir', join(directory, 'artifacts'),
    '--source', `core=${join(directory, 'core')}`,
    '--source', `bpm=${join(directory, 'product')}`,
  ];
  const wrongDigest = spawnSync('python3', verifyArgs(sha64('other')), { encoding: 'utf8' });
  assert.notEqual(wrongDigest.status, 0);
  assert.match(wrongDigest.stderr, /SHA-256 mismatch/);

  writeFileSync(join(directory, 'artifacts', 'images-archive.tar.gz'), 'mutated');
  const corrupted = spawnSync('python3', verifyArgs(sha64(readFileSync(receiptPath))), { encoding: 'utf8' });
  assert.notEqual(corrupted.status, 0);
  assert.match(corrupted.stderr, /imageArchive SHA-256 mismatch/);
});
