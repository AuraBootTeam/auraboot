import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';

import {
  auditProductionReactRuntimeFiles,
  verifyProductionReactRuntime,
} from './verify-production-react-runtime.mjs';

test('accepts one ordinary host React runtime', () => {
  assert.deepEqual(
    auditProductionReactRuntimeFiles([
      'entry.client-a1.js',
      'react-b2.js',
      'react-dom-c3.js',
    ]),
    { pass: true, forbidden: [] },
  );
});

test('rejects the duplicate federation React artifact that breaks hydration', () => {
  assert.deepEqual(
    auditProductionReactRuntimeFiles([
      'entry.client-a1.js',
      'react-b2.js',
      '__federation_shared_react-soXCLqRk.js',
    ]),
    {
      pass: false,
      forbidden: ['__federation_shared_react-soXCLqRk.js'],
    },
  );
});

test('fails closed when the production asset directory is absent', () => {
  assert.throws(
    () => verifyProductionReactRuntime('/definitely/missing/web-assets'),
    /production client asset directory is missing/,
  );
});

test('audits real directory entries rather than trusting a manifest claim', () => {
  const directory = fs.mkdtempSync(
    path.join(os.tmpdir(), 'react-runtime-gate-'),
  );
  try {
    fs.writeFileSync(path.join(directory, 'entry.client-ok.js'), 'export {}');
    assert.equal(verifyProductionReactRuntime(directory).pass, true);
    fs.writeFileSync(
      path.join(directory, '__federation_shared_react-bad.js'),
      'export {}',
    );
    assert.throws(
      () => verifyProductionReactRuntime(directory),
      /second federation React runtime/,
    );
  } finally {
    fs.rmSync(directory, { recursive: true, force: true });
  }
});
