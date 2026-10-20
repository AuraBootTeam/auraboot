import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { exportContract, fetchDocument } from './export.mjs';
const url = 'http://backend.invalid:8080/v3/api-docs/internal-web-admin';
const document = { openapi: '3.1.0', info: { title: 'Internal', version: '1' }, paths: {
  '/api/example': { get: { operationId: 'example', responses: { 200: { description: 'OK' } } } },
} };
const request = async () => new Response(JSON.stringify(document), { headers: { 'Content-Type': 'application/json' } });

test('fails closed on unexpected endpoints, HTTP failures, invalid JSON and empty catalogs', async () => {
  await assert.rejects(fetchDocument('http://backend.invalid/v3/api-docs/open-platform', request), /Only the internal/);
  await assert.rejects(fetchDocument('http://user:secret@backend.invalid/v3/api-docs/internal-web-admin', request), /without embedded credentials/);
  await assert.rejects(fetchDocument(url, async () => new Response('failed', { status: 503 })), /HTTP 503/);
  await assert.rejects(fetchDocument(url, async () => new Response('<html>Error</html>')), /Unexpected token/);
  await assert.rejects(fetchDocument(url, async () => new Response(JSON.stringify({ ...document, paths: {} }))), /surface is empty/);
});

test('requires a fresh receipt and never writes success when the live catalog drifts', async t => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'internal-contract-export-'));
  t.after(() => fs.rm(dir, { recursive: true, force: true }));
  const output = path.join(dir, 'openapi.json'), receipt = path.join(dir, 'first.json');
  const first = await exportContract({ url, output, receipt, request });
  assert.equal(first.operations, 1);
  assert.equal(first.claim, 'contract-export-only');
  await assert.rejects(exportContract({ url, output, receipt, request }), /Receipt already exists/);
  const checked = await exportContract({ url, check: output, receipt: path.join(dir, 'checked.json'), request });
  assert.equal(checked.contractSha256, first.contractSha256);
  const changed = structuredClone(document); changed.paths['/api/example'].get.responses['200'].description = 'Different';
  const failedReceipt = path.join(dir, 'must-not-exist.json');
  await assert.rejects(exportContract({ url, check: output, receipt: failedReceipt,
    request: async () => new Response(JSON.stringify(changed)) }), /contract drift/);
  await assert.rejects(fs.access(failedReceipt), { code: 'ENOENT' });
});
