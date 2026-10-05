import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';

// Hermetic shell/API contract only. This fixture does not prove product installation.
async function runQuickstart(spaces) {
  const calls = [];
  const server = http.createServer(async (req, res) => {
    let input = '';
    for await (const chunk of req) input += chunk;
    const body = input ? JSON.parse(input) : null;
    calls.push({ path: req.url, authorization: req.headers.authorization, body });
    let result;
    if (req.url === '/api/bootstrap/status') result = { code: '0', data: { initialized: true } };
    else if (req.url === '/api/auth/login') result = { code: '0', data: { jwt: 'fixture-onboarding-token' } };
    else if (req.url === '/api/tenant-selection/my-spaces') result = { code: '0', data: spaces };
    else if (req.url === '/api/tenant-selection/process') {
      assert.equal(body.tenantId, '365088088063610880');
      assert.equal(body.action, 'select');
      result = { code: '0', data: { jwt: 'fixture-business-token', tenantId: body.tenantId } };
    } else if (req.url === '/api/plugins/import/import-directory-sync') {
      result = { success: req.headers.authorization === 'Bearer fixture-business-token' };
    } else if (req.url === '/api/plugins/import/verify-reference-integrity') {
      result = { valid: req.headers.authorization === 'Bearer fixture-business-token' };
    } else { res.statusCode = 404; result = { error: 'unexpected fixture route' }; }
    res.setHeader('Content-Type', 'application/json'); res.end(JSON.stringify(result));
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  try {
    const script = process.env.AURA_QUICKSTART_TEST_SCRIPT || fileURLToPath(new URL('./quickstart.sh', import.meta.url));
    const child = spawn('bash', [script], { env: { ...process.env, BACKEND_URL: `http://127.0.0.1:${server.address().port}`, ADMIN_EMAIL: 'fixture-admin@example.test', ADMIN_PASSWORD: 'fixture-private-password', AURA_ADMIN_TENANT_ID: '', NO_PROXY: 'localhost,127.0.0.1', no_proxy: 'localhost,127.0.0.1' } });
    let output = ''; child.stdout.on('data', chunk => output += chunk); child.stderr.on('data', chunk => output += chunk);
    const code = await new Promise((resolve, reject) => { child.on('exit', resolve); child.on('error', reject); });
    return { code, output, calls, baseURL: `http://127.0.0.1:${server.address().port}` };
  } finally { await new Promise(resolve => server.close(resolve)); }
}
const business = { tenantId: '365088088063610880', spaceType: 'business' };
test('quickstart selects the exact business space before all plugin imports and does not print secrets', async () => {
  const result = await runQuickstart([{ tenantId: '1', spaceType: 'platform' }, business]);
  assert.equal(result.code, 0, result.output);
  const imports = result.calls.filter(call => call.path === '/api/plugins/import/import-directory-sync');
  assert.equal(imports.length, 8);
  assert.ok(result.output.includes(`${result.baseURL} `));
  assert.ok(imports.every(call => call.authorization === 'Bearer fixture-business-token'));
  assert.ok(result.calls.findIndex(call => call.path === '/api/tenant-selection/process') < result.calls.findIndex(call => call.path === '/api/plugins/import/import-directory-sync'));
  for (const secret of ['fixture-private-password', 'fixture-onboarding-token', 'fixture-business-token']) assert.ok(!result.output.includes(secret));
});
test('quickstart refuses ambiguous business memberships before any plugin writes', async () => {
  const result = await runQuickstart([business, { tenantId: '2', spaceType: 'business' }]);
  assert.notEqual(result.code, 0);
  assert.match(result.output, /exactly one business space/);
  assert.equal(result.calls.filter(call => call.path.startsWith('/api/plugins/import/')).length, 0);
  assert.ok(!result.output.includes('fixture-private-password'));
});
