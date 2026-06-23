import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { auditRepo, REGISTRY_VERSION } from './check-designer-boundary.mjs';

// --- fixture helpers ---------------------------------------------------------

const VALID_REGISTRY = {
  registryVersion: REGISTRY_VERSION,
  families: {
    'block-tree': { canonicalKernel: 'unified-designer' },
    grid: { canonicalKernel: 'dashboard-grid' },
    flow: { canonicalKernel: 'flow-designer-sdk' },
    'physical-canvas': { canonicalKernel: null },
    'cell-grid': { canonicalKernel: 'cell-grid-kernel' },
  },
};

function makeRepo(config = {}, registry = VALID_REGISTRY) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'designerbnd-'));
  const cfg = {
    registryPath: 'scripts/designer-family-registry.json',
    surfaceRoots: ['surfaces'],
    expectedSurfaces: [],
    allowlist: {},
    ...config,
  };
  write(root, '.designer-boundary.json', JSON.stringify(cfg));
  if (registry !== null) {
    write(root, cfg.registryPath, JSON.stringify(registry));
  }
  return root;
}

function write(root, relPath, content) {
  const abs = path.join(root, relPath);
  fs.mkdirSync(path.dirname(abs), { recursive: true });
  fs.writeFileSync(abs, content);
  return abs;
}

function mkSurface(root, relDir, manifest) {
  const dir = path.join(root, relDir);
  fs.mkdirSync(dir, { recursive: true });
  if (manifest !== undefined) {
    fs.writeFileSync(
      path.join(dir, 'designer.family.json'),
      typeof manifest === 'string' ? manifest : JSON.stringify(manifest),
    );
  }
  return dir;
}

const GOOD_MANIFEST = {
  surface: 'foo-designer',
  family: 'block-tree',
  layer1Kernel: 'unified-designer',
  layer0: ['PropertySchema', 'InspectorSchemaRegistry'],
  storage: { primary: 'ab_foo' },
};

function codes(result) {
  return result.findings.map((f) => f.code);
}
function errorCodes(result) {
  return result.findings.filter((f) => f.severity === 'error').map((f) => f.code);
}

// --- tests -------------------------------------------------------------------

test('clean repo: every discovered designer surface has a valid manifest → no errors', () => {
  const root = makeRepo();
  mkSurface(root, 'surfaces/foo-designer', GOOD_MANIFEST);
  mkSurface(root, 'surfaces/bar-designer-sdk', { ...GOOD_MANIFEST, surface: 'bar-designer-sdk' });
  // non-designer dirs are ignored by name pattern
  mkSurface(root, 'surfaces/query-builder', undefined);
  const result = auditRepo(root);
  assert.equal(result.configError, false);
  assert.deepEqual(errorCodes(result), [], JSON.stringify(result.findings));
});

test('discovered designer dir without manifest → S-DESIGNER-NO-MANIFEST error', () => {
  const root = makeRepo();
  mkSurface(root, 'surfaces/foo-designer', undefined);
  const result = auditRepo(root);
  assert.ok(errorCodes(result).includes('S-DESIGNER-NO-MANIFEST'));
});

test('manifest with family not in registry → S-DESIGNER-MANIFEST-BAD-FAMILY', () => {
  const root = makeRepo();
  mkSurface(root, 'surfaces/foo-designer', { ...GOOD_MANIFEST, family: 'totally-made-up' });
  const result = auditRepo(root);
  assert.ok(errorCodes(result).includes('S-DESIGNER-MANIFEST-BAD-FAMILY'));
});

test('cell-grid family is accepted (5th registered family)', () => {
  const root = makeRepo();
  mkSurface(root, 'surfaces/foo-designer', {
    ...GOOD_MANIFEST,
    family: 'cell-grid',
    layer1Kernel: 'cell-grid-kernel',
  });
  const result = auditRepo(root);
  assert.deepEqual(errorCodes(result), []);
});

test('missing layer1Kernel → field error', () => {
  const root = makeRepo();
  const m = { ...GOOD_MANIFEST };
  delete m.layer1Kernel;
  mkSurface(root, 'surfaces/foo-designer', m);
  const result = auditRepo(root);
  assert.ok(codes(result).includes('S-DESIGNER-MANIFEST-FIELD'));
  assert.ok(result.findings.some((f) => f.message.includes('layer1Kernel')));
});

test('missing layer0 array → field error', () => {
  const root = makeRepo();
  const m = { ...GOOD_MANIFEST };
  delete m.layer0;
  mkSurface(root, 'surfaces/foo-designer', m);
  const result = auditRepo(root);
  assert.ok(result.findings.some((f) => f.code === 'S-DESIGNER-MANIFEST-FIELD' && f.message.includes('layer0')));
});

test('layer0 present but not an array → field error', () => {
  const root = makeRepo();
  mkSurface(root, 'surfaces/foo-designer', { ...GOOD_MANIFEST, layer0: 'PropertySchema' });
  const result = auditRepo(root);
  assert.ok(result.findings.some((f) => f.code === 'S-DESIGNER-MANIFEST-FIELD' && f.message.includes('layer0')));
});

test('missing storage → field error', () => {
  const root = makeRepo();
  const m = { ...GOOD_MANIFEST };
  delete m.storage;
  mkSurface(root, 'surfaces/foo-designer', m);
  const result = auditRepo(root);
  assert.ok(result.findings.some((f) => f.code === 'S-DESIGNER-MANIFEST-FIELD' && f.message.includes('storage')));
});

test('storage as non-empty string is accepted', () => {
  const root = makeRepo();
  mkSurface(root, 'surfaces/foo-designer', { ...GOOD_MANIFEST, storage: 'ab_dashboard' });
  const result = auditRepo(root);
  assert.deepEqual(errorCodes(result), []);
});

test('missing surface field → field error', () => {
  const root = makeRepo();
  const m = { ...GOOD_MANIFEST };
  delete m.surface;
  mkSurface(root, 'surfaces/foo-designer', m);
  const result = auditRepo(root);
  assert.ok(result.findings.some((f) => f.code === 'S-DESIGNER-MANIFEST-FIELD' && f.message.includes('surface')));
});

test('invalid JSON manifest → S-DESIGNER-MANIFEST-INVALID-JSON', () => {
  const root = makeRepo();
  mkSurface(root, 'surfaces/foo-designer', '{ not valid json ');
  const result = auditRepo(root);
  assert.ok(errorCodes(result).includes('S-DESIGNER-MANIFEST-INVALID-JSON'));
});

test('allowlisted dir without manifest → skipped, no error', () => {
  const root = makeRepo({
    allowlist: { 'surfaces/foo-designer': 'placeholder stub' },
  });
  mkSurface(root, 'surfaces/foo-designer', undefined);
  const result = auditRepo(root);
  assert.deepEqual(errorCodes(result), []);
  assert.ok(result.skipped.some((s) => s.relPath === 'surfaces/foo-designer'));
});

test('expectedSurface that does not match the -designer name pattern is still required to have a manifest', () => {
  const root = makeRepo({ expectedSurfaces: ['plugins/core-dashboard'] });
  // core-dashboard does not contain "-designer" so only the explicit expectedSurfaces entry covers it
  mkSurface(root, 'plugins/core-dashboard', undefined);
  const result = auditRepo(root);
  assert.ok(errorCodes(result).includes('S-DESIGNER-NO-MANIFEST'));
});

test('expectedSurface with a valid manifest → no error', () => {
  const root = makeRepo({ expectedSurfaces: ['plugins/core-dashboard'] });
  mkSurface(root, 'plugins/core-dashboard', {
    ...GOOD_MANIFEST,
    surface: 'dashboard-designer',
    family: 'grid',
    layer1Kernel: 'dashboard-grid',
    storage: { primary: 'ab_dashboard' },
  });
  const result = auditRepo(root);
  assert.deepEqual(errorCodes(result), []);
});

test('missing .designer-boundary.json config → configError (exit 2 semantics)', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'designerbnd-noconfig-'));
  const result = auditRepo(root);
  assert.equal(result.configError, true);
});

test('missing registry file → configError', () => {
  const root = makeRepo({}, null);
  const result = auditRepo(root);
  assert.equal(result.configError, true);
});

test('registry version mismatch → S-DESIGNER-REGISTRY-STALE warning (not a hard error)', () => {
  const root = makeRepo({}, { ...VALID_REGISTRY, registryVersion: REGISTRY_VERSION + 99 });
  mkSurface(root, 'surfaces/foo-designer', GOOD_MANIFEST);
  const result = auditRepo(root);
  assert.ok(codes(result).includes('S-DESIGNER-REGISTRY-STALE'));
  assert.ok(result.findings.some((f) => f.code === 'S-DESIGNER-REGISTRY-STALE' && f.severity === 'warning'));
  // not a hard error
  assert.ok(!errorCodes(result).includes('S-DESIGNER-REGISTRY-STALE'));
});

test('each finding carries severity, code, file, message', () => {
  const root = makeRepo();
  mkSurface(root, 'surfaces/foo-designer', undefined);
  const result = auditRepo(root);
  for (const f of result.findings) {
    assert.equal(typeof f.severity, 'string');
    assert.equal(typeof f.code, 'string');
    assert.equal(typeof f.file, 'string');
    assert.equal(typeof f.message, 'string');
  }
});
