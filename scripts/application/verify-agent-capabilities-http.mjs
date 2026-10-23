import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { randomUUID } from 'node:crypto';

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
const names = ['unauthenticated-denied', 'invalid-token-denied', 'authenticated-installation', 'conditional-request-refetches', 'unrelated-provider-query-cannot-select-assembly', 'member-without-ai-or-admin-permissions'];
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
async function businessSession(email, password) {
  const login = await request(`${origin}/api/auth/login`, { method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password }) });
  assert.equal(login.status, 200, 'Real login HTTP contract failed');
  const session = await login.json();
  assert.equal(session.code, '0', 'Real login was denied');
  assert.ok(session.data?.jwt, 'Login did not issue a session');
  const loginHeaders = { Authorization: `Bearer ${session.data.jwt}`, Accept: 'application/json' };
  const spaces = await request(`${origin}/api/tenant-selection/my-spaces`, { headers: loginHeaders });
  assert.equal(spaces.status, 200);
  const memberships = await spaces.json();
  assert.equal(memberships.code, '0');
  const business = memberships.data.filter(space => space.spaceType === 'business');
  assert.equal(business.length, 1, 'Suite requires the isolated single-business-tenant bootstrap fixture');
  assert.equal(typeof business[0].tenantId, 'string', 'Keep public tenant identity lossless');
  const selected = await request(`${origin}/api/tenant-selection/process`, { method: 'POST',
    headers: { ...loginHeaders, 'Content-Type': 'application/json' },
    body: JSON.stringify({ action: 'select', tenantId: business[0].tenantId }) });
  assert.equal(selected.status, 200);
  const ready = await selected.json();
  assert.equal(ready.code, '0');
  assert.equal(ready.data.status, 'success');
  assert.ok(ready.data.jwt, 'Space selection did not issue a tenant-scoped session');
  return { Authorization: `Bearer ${ready.data.jwt}`, Accept: 'application/json' };
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
  const headers = await businessSession(process.env.AURA_TEST_EMAIL, process.env.AURA_TEST_PASSWORD);
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
  await run(names[5], async result => {
    const email = `capabilities-${randomUUID()}@example.com`;
    const password = `Discovery-${randomUUID()}xA9!`;
    const provision = await request(`${origin}/api/admin/users`, { method: 'POST',
      headers: { ...headers, 'Content-Type': 'application/json' },
      body: JSON.stringify({ email, displayName: 'Capability discovery member', initialPassword: password,
        roleAssignmentMode: 'NONE', roleCodes: [], sendInviteEmail: false }) });
    assert.equal(provision.status, 200, 'Owned member fixture provisioning failed');
    const created = await provision.json();
    assert.equal(created.code, '0', 'Owned member fixture provisioning was denied');
    assert.deepEqual(created.data.assignedRoles, [], 'Fixture must not receive a default role');
    const memberHeaders = await businessSession(email, password);
    const me = await request(`${origin}/api/auth/me`, { headers: memberHeaders });
    assert.equal(me.status, 200);
    const identity = await me.json();
    assert.equal(identity.code, '0');
    assert.deepEqual(identity.data.permissions.roles, []);
    // The bootstrap tenant_member baseline is implicit even with no assigned role.
    // Pin its ordinary read-only codes; neither AI authority nor an admin wildcard is allowed.
    assert.deepEqual([...identity.data.permissions.permissionCodes].sort(),
      ['dashboard.saved_view.read', 'meta.model.read', 'notification.view', 'page.page.read', 'party.party.read']);
    const response = await request(endpoint, { headers: memberHeaders });
    result.status = response.status;
    result.cacheControl = response.headers.get('cache-control');
    assert.equal(response.status, 200);
    assert.equal(result.cacheControl, 'no-store');
    result.body = await response.json();
    installation(result.body);
  });
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
