import assert from 'node:assert/strict';
import fs from 'node:fs';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';

import { runProductionHydrationSmoke } from './verify-production-hydration.mjs';

async function fixtureServer(extraScript = '') {
  const server = http.createServer((_request, response) => {
    response.writeHead(200, { 'content-type': 'text/html; charset=utf-8' });
    response.end(`<!doctype html>
      <html><body>
        <input id="password" type="password">
        <button data-testid="login-toggle-password">toggle</button>
        <script>
          document.querySelector('[data-testid="login-toggle-password"]')
            .addEventListener('click', () => {
              document.querySelector('#password').type = 'text';
            });
          ${extraScript}
        </script>
      </body></html>`);
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const address = server.address();
  return {
    server,
    baseUrl: `http://127.0.0.1:${address.port}`,
  };
}

test('passes only after a real browser executes the login interaction', async () => {
  const fixture = await fixtureServer();
  const evidenceDir = fs.mkdtempSync(path.join(os.tmpdir(), 'hydration-pass-'));
  try {
    const summary = await runProductionHydrationSmoke({
      baseUrl: fixture.baseUrl,
      evidenceDir,
    });
    assert.equal(summary.status, 'PASS');
    assert.equal(summary.passwordToggleBefore, 'password');
    assert.equal(summary.passwordToggleAfter, 'text');
    assert.ok(fs.existsSync(path.join(evidenceDir, 'summary.json')));
  } finally {
    fixture.server.close();
    fs.rmSync(evidenceDir, { recursive: true, force: true });
  }
});

test('fails closed on a hydration-time page error even when HTTP is 200', async () => {
  const fixture = await fixtureServer(
    "queueMicrotask(() => { throw new TypeError('duplicate React dispatcher'); });",
  );
  const evidenceDir = fs.mkdtempSync(path.join(os.tmpdir(), 'hydration-fail-'));
  try {
    await assert.rejects(
      runProductionHydrationSmoke({
        baseUrl: fixture.baseUrl,
        evidenceDir,
      }),
      /browser page errors: duplicate React dispatcher/,
    );
    const summary = JSON.parse(
      fs.readFileSync(path.join(evidenceDir, 'summary.json'), 'utf8'),
    );
    assert.equal(summary.status, 'FAIL');
    assert.deepEqual(summary.pageErrors, ['duplicate React dispatcher']);
  } finally {
    fixture.server.close();
    fs.rmSync(evidenceDir, { recursive: true, force: true });
  }
});
