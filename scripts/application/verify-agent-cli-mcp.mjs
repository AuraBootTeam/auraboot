import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { spawnSync } from 'node:child_process';
import { mkdirSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
const [cliRoot, origin, expected, output] = process.argv.slice(2);
assert.ok(['installed', 'not_installed'].includes(expected));
assert.ok(process.env.AURA_TEST_EMAIL && process.env.AURA_TEST_PASSWORD, 'Explicit bootstrap credentials required');
mkdirSync(output, { recursive: false });
const require = createRequire(path.join(cliRoot, 'package.json'));
const { Client } = await import(pathToFileURL(require.resolve('@modelcontextprotocol/sdk/client/index.js')));
const { StdioClientTransport } = await import(pathToFileURL(require.resolve('@modelcontextprotocol/sdk/client/stdio.js')));
async function json(endpoint, token, body) {
  const response = await fetch(new URL(endpoint, origin), { method: body ? 'POST' : 'GET',
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body ? JSON.stringify(body) : undefined });
  assert.equal(response.status, 200, endpoint);
  const payload = await response.json();
  assert.equal(payload.code, '0', endpoint);
  return payload.data;
}
const login = await json('/api/auth/login', null, { email: process.env.AURA_TEST_EMAIL, password: process.env.AURA_TEST_PASSWORD });
const spaces = await json('/api/tenant-selection/my-spaces', login.jwt);
const business = spaces.filter(space => space.spaceType === 'business');
assert.equal(business.length, 1);
const ready = await json('/api/tenant-selection/process', login.jwt, { action: 'select', tenantId: business[0].tenantId });
assert.equal(ready.status, 'success');
const env = { ...process.env, AURA_TOKEN: ready.jwt, AURA_API_URL: origin, NO_COLOR: '1' };
const cases = [];
function cli(args) {
  const result = spawnSync(process.execPath, [path.join(cliRoot, 'dist/index.js'), ...args], { env, encoding: 'utf8', timeout: 15000 });
  assert.ifError(result.error);
  return result;
}
const status = cli(['--format', 'json', 'status']);
assert.equal(status.status, 0, 'status exit');
const statusJson = JSON.parse(status.stdout);
assert.equal(statusJson.services.find(service => service.name === 'Backend').status, 'up');
assert.equal(statusJson.services.find(service => service.name === 'Agent (ACP)').installationState, expected);
if (expected === 'not_installed') assert.equal(statusJson.services.find(service => service.name === 'Agent (ACP)').status, 'not_installed');
cases.push({ id: 'CLI-STATUS', verdict: 'pass', services: statusJson.services });
if (expected === 'not_installed') {
  for (const [id, args] of [['CLI-ASK-ABSENT', ['--agent-mode', 'ask', 'assembly verification']], ['CLI-DISPATCH-ABSENT', ['--agent-mode', 'run', 'dispatch', 'retained-task']]]) {
    const result = cli(args);
    assert.equal(result.status, 1, id);
    assert.match(result.stderr, /Agent module is not installed/);
    assert.equal(result.stdout, '', 'preflight refuses before output/side effects');
    cases.push({ id, verdict: 'pass', exitCode: result.status, feedback: 'Agent module is not installed' });
  }
}
for (const profile of ['read', 'dsl-authoring', 'full']) {
  const transport = new StdioClientTransport({ command: process.execPath,
    args: [path.join(cliRoot, 'dist/index.js'), 'mcp', 'serve', '--profile', profile], env, stderr: 'pipe' });
  const client = new Client({ name: 'agent-assembly-acceptance', version: '1.0.0' });
  try {
    await client.connect(transport);
    const { tools } = await client.listTools();
    const names = tools.map(tool => tool.name);
    assert.ok(names.includes('query_entity'));
    for (const name of ['ask_aurabot', 'dispatch_agent', 'list_agents', 'list_tools']) {
      assert.equal(names.includes(name), expected === 'installed', `${profile}: ${name}`);
    }
    if (profile === 'full') {
      const result = await client.callTool({ name: 'query_existing_models', arguments: { keyword: 'org_', limit: 50 } });
      assert.ok(!result.isError, JSON.stringify(result));
      const body = JSON.parse(result.content[0].text);
      assert.ok(JSON.stringify(body).includes('org_department'), 'ordinary model discovery returns the imported real model');
      cases.push({ id: 'MCP-GENERIC-QUERY', verdict: 'pass', fixture: 'org_department' });
      if (expected === 'not_installed') {
        const refused = await client.callTool({ name: 'dispatch_agent', arguments: { taskPid: 'retained-task' } });
        assert.equal(refused.isError, true);
        cases.push({ id: 'MCP-ABSENT-DIRECT-CALL', verdict: 'pass' });
      }
    }
    cases.push({ id: `MCP-DISCOVERY-${profile}`, verdict: 'pass', tools: names });
  } finally { await client.close(); }
}
process.env.AURA_API_URL = origin;
const { startHttpMcpServer } = await import(pathToFileURL(path.join(cliRoot, 'dist/mcp/http-server.js')));
const { StreamableHTTPClientTransport } = await import(pathToFileURL(require.resolve('@modelcontextprotocol/sdk/client/streamableHttp.js')));
const server = await startHttpMcpServer({ host: '127.0.0.1', port: 0, profile: 'full' });
const httpOrigin = `http://127.0.0.1:${server.address().port}/mcp`;
try {
  const anonymous = await fetch(httpOrigin, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' });
  assert.equal(anonymous.status, 401);
  cases.push({ id: 'MCP-HTTP-ANONYMOUS', verdict: 'pass' });
  for (const profile of ['read', 'dsl-authoring', 'full']) {
    const client = new Client({ name: 'agent-assembly-http', version: '1.0.0' });
    try {
      await client.connect(new StreamableHTTPClientTransport(new URL(httpOrigin), { requestInit: { headers: { Authorization: `Bearer ${ready.jwt}`, 'x-aura-tools': profile } } }));
      const { tools } = await client.listTools();
      const names = tools.map(tool => tool.name);
      assert.ok(names.includes('query_entity'));
      for (const name of ['ask_aurabot', 'dispatch_agent', 'list_agents', 'list_tools']) assert.equal(names.includes(name), expected === 'installed', `HTTP ${profile}: ${name}`);
      if (profile === 'full') {
        const result = await client.callTool({ name: 'query_existing_models', arguments: { keyword: 'org_', limit: 50 } });
        assert.ok(!result.isError, JSON.stringify(result));
        assert.match(result.content[0].text, /org_department/);
        cases.push({ id: 'MCP-HTTP-GENERIC-QUERY', verdict: 'pass' });
      }
      cases.push({ id: `MCP-HTTP-DISCOVERY-${profile}`, verdict: 'pass', tools: names });
    } finally { await client.close(); }
  }
} finally { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); }
const registered = expected === 'installed' ? 10 : 13;
assert.equal(cases.length, registered, 'Missing registered CLI/MCP cases');
writeFileSync(path.join(output, 'report.json'), JSON.stringify({ expected, origin, registered, executed: cases.length, passed: cases.length, failed: 0, cases }, null, 2)+'\n');
console.log(`CLI/MCP ${expected}: ${cases.length} cases passed`);
