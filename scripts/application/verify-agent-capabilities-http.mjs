import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';

// Targeted real-stack suite. Run once per independently selected packaged assembly.
// Missing prerequisites fail; no test is silently skipped and no token enters evidence.
const [origin, expectedState, evidence] = process.argv.slice(2);
assert.equal(process.argv.length, 5, 'Usage: node verify-agent-capabilities-http.mjs <origin> <installed|not_installed> <fresh-evidence-directory>');
const url = new URL(origin);
assert.ok(['http:', 'https:'].includes(url.protocol) && url.origin === origin, 'Supply an explicit origin without credentials or path');
assert.ok(['installed', 'not_installed'].includes(expectedState), 'Supply the independently selected assembly expectation');
assert.ok(process.env.AURA_TEST_EMAIL && process.env.AURA_TEST_PASSWORD, 'Explicit AURA_TEST_EMAIL and AURA_TEST_PASSWORD required');
fs.mkdirSync(evidence, { recursive: false });
const endpoint = `${origin}/api/application/capabilities`;
const names = ['unauthenticated-denied', 'invalid-token-denied', 'authenticated-installation', 'conditional-request-refetches', 'unrelated-provider-query-cannot-select-assembly'];
const report = { schemaVersion: 1, claim: 'targeted-packaged-http', origin, expectedState, registered: names,
  executed: [], verdict: 'fail', skipped: 0 };
const request = (target, options = {}) => fetch(target, { ...options, redirect: 'error', signal: AbortSignal.timeout(30_000) });
async function run(name, body) {
  const result = { name, verdict: 'fail' };
  report.executed.push(result);
  await body(result);
  result.verdict = 'pass';
}
function installation(body) {
  assert.deepEqual(body, { schemaVersion: 1, agent: { state: expectedState } });
}
try {
  for (const [name, authorization] of [[names[0], null], [names[1], 'Bearer invalid-discovery-token']]) {
    await run(name, async result => {
      const response = await request(endpoint, { headers: authorization ? { Authorization: authorization } : {} });
      result.status = response.status;
      assert.ok([401, 403].includes(response.status), `Unauthenticated request must be denied, got ${response.status}`);
      const body = await response.text();
      assert.ok(!body.includes('schemaVersion') && !body.includes('not_installed') && !body.includes('"installed"'), 'Denial must not disclose installation facts');
    });
  }
  const login = await request(`${origin}/api/auth/login`, { method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email: process.env.AURA_TEST_EMAIL, password: process.env.AURA_TEST_PASSWORD }) });
  assert.equal(login.status, 200, 'Real login HTTP contract failed');
  const session = await login.json();
  assert.equal(session.success, true, 'Real login was denied');
  assert.ok(session.data?.jwt, 'Login did not issue a session');
  const headers = { Authorization: `Bearer ${session.data.jwt}`, Accept: 'application/json' };
  for (const [name, suffix, extra] of [[names[2], '', {}], [names[3], '', { 'If-None-Match': '*' }],
    [names[4], '?agent=false&provider=missing&entitlement=false', {}]]) {
    await run(name, async result => {
      const response = await request(endpoint + suffix, { headers: { ...headers, ...extra } });
      result.status = response.status;
      result.cacheControl = response.headers.get('cache-control');
      assert.equal(response.status, 200);
      assert.equal(result.cacheControl, 'no-store');
      assert.equal(response.headers.get('etag'), null);
      assert.match(response.headers.get('content-type'), /application\/json/);
      result.body = await response.json();
      installation(result.body);
    });
  }
  assert.deepEqual(report.executed.map(row => row.name), names, 'Suite denominator changed or was not fully executed');
  assert.ok(report.executed.every(row => row.verdict === 'pass'));
  report.verdict = 'pass';
} catch (error) {
  report.error = error.message;
  process.exitCode = 1;
} finally {
  fs.writeFileSync(path.join(evidence, 'report.json'), JSON.stringify(report, null, 2) + '\n', { flag: 'wx' });
}
console.log(`Agent capabilities HTTP: ${report.verdict}; executed=${report.executed.length}/${names.length}; expected=${expectedState}`);
