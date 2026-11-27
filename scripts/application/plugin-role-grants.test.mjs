import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { chmodSync, closeSync, fstatSync, mkdtempSync, openSync, readFileSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

import {
  batchGrantBody,
  grantPluginRoles,
  indexPermissionTree,
  parseArgs,
  readRoleTemplates,
  roleGrantPlan,
  verifyGrants,
} from './plugin-role-grants.mjs';

const script = fileURLToPath(import.meta.url).replace(/\.test\.mjs$/, '.mjs');
const permission = (id, code) => ({ id, pid: `PERM${String(id).padStart(22, '0')}`, code });
const [execute, account, channel, target] = [
  permission(1, 'meta.command.execute'),
  permission(2, 'crm.account.manage'),
  permission(3, 'crm.channel.manage'),
  permission(4, 'crm.target.manage'),
];
const tree = [{ ...execute, children: [{ ...account, children: [] }] }, { ...channel, children: [] }, target];
const admin = { pid: 'ROLE0000000000000000000000A', code: 'crm_admin' };
const sales = { pid: 'ROLE0000000000000000000000B', code: 'crm_sales' };

test('a roles file is read as role codes with unique permission codes, and nothing else', () => {
  assert.deepEqual(
    readRoleTemplates([{ code: 'crm_sales', name: 'x', permissions: ['meta.command.execute', 'meta.command.execute'] }]),
    [{ code: 'crm_sales', permissions: ['meta.command.execute'] }],
  );
  assert.throws(() => readRoleTemplates({ roles: [] }), /must be an array/);
  assert.throws(() => readRoleTemplates([{ permissions: [] }]), /roles\[0\]\.code/);
  assert.throws(() => readRoleTemplates([{ code: 'crm_sales', permissions: 'meta.command.execute' }]), /permissions/);
  assert.throws(() => readRoleTemplates([{ code: 'crm_sales', permissions: [7] }]), /permissions/);
});

test('the permission tree is indexed by code down to its numeric id, children included', () => {
  const index = indexPermissionTree(tree);
  assert.equal(index.pidByCode.get('crm.account.manage'), account.pid);
  assert.equal(index.pidByCode.get('crm.channel.manage'), channel.pid);
  assert.equal(index.idByPid.get(account.pid), 2);
  assert.equal(index.pidByCode.has('not.imported.yet'), false);
  assert.deepEqual(indexPermissionTree(undefined), { pidByCode: new Map(), idByPid: new Map() });
});

test('a role plan names what is missing, keeps what is granted, and sets aside what the tenant does not have', () => {
  const plan = roleGrantPlan(
    { code: 'crm_admin', permissions: ['meta.command.execute', 'crm.channel.manage', 'not.imported.yet', 'crm.target.manage'] },
    [execute.pid],
    indexPermissionTree(tree),
  );
  assert.deepEqual(plan.missing, [
    { code: 'crm.channel.manage', pid: channel.pid, permissionId: 3 },
    { code: 'crm.target.manage', pid: target.pid, permissionId: 4 },
  ]);
  assert.deepEqual(plan.unknown, ['not.imported.yet']);
});

test('the batch body only ever asks for grants', () => {
  const body = batchGrantBody([{ code: 'a', pid: 'P1', permissionId: 3 }, { code: 'b', pid: 'P2', permissionId: 4 }]);
  assert.deepEqual(body, [{ permissionId: 3, granted: true }, { permissionId: 4, granted: true }]);
  assert.deepEqual(batchGrantBody([]), []);
});

test('verification reports a grant that was not added and an earlier grant that went missing', () => {
  const missing = [{ code: 'crm.channel.manage', pid: channel.pid, permissionId: 3 }];
  assert.deepEqual(verifyGrants([execute.pid], [execute.pid, channel.pid], missing), { notAdded: [], lost: [] });
  assert.deepEqual(verifyGrants([execute.pid], [execute.pid], missing), { notAdded: ['crm.channel.manage'], lost: [] });
  assert.deepEqual(verifyGrants([execute.pid, account.pid], [execute.pid, channel.pid], missing), {
    notAdded: [],
    lost: [account.pid],
  });
});

/** A tenant in memory. Any write to the replacing role permissions endpoint fails the test. */
function tenant({ grants, onBatch = () => {}, applyBatch = true } = {}) {
  const held = grants ?? new Map([[admin.pid, new Set([execute.pid, account.pid])], [sales.pid, new Set([execute.pid])]]);
  const calls = [];
  const request = async (method, path, body) => {
    calls.push({ method, path, body });
    if (method === 'GET' && path === '/api/roles/all') return [admin, sales];
    if (method === 'GET' && path === '/api/permissions/tree') return tree;
    const role = path.match(/^\/api\/roles\/([^/]+)\/permissions$/);
    if (role) {
      if (method !== 'GET') throw new Error(`the replacing role permissions endpoint was written: ${method} ${path}`);
      return [...held.get(role[1])];
    }
    if (method === 'PUT' && path.match(/^\/api\/permissions\/matrix\/[^/]+\/batch$/)) {
      onBatch(path, body);
      for (const entry of applyBatch ? body : []) {
        if (entry.granted !== true) throw new Error(`a revocation was sent: ${JSON.stringify(entry)}`);
        held.get(decodeURIComponent(path.split('/')[4])).add(`PERM${String(entry.permissionId).padStart(22, '0')}`);
      }
      return null;
    }
    throw new Error(`unexpected request ${method} ${path}`);
  };
  return { request, calls, held };
}

const templates = readRoleTemplates([
  { code: 'crm_admin', permissions: ['meta.command.execute', 'crm.account.manage', 'crm.channel.manage', 'crm.target.manage'] },
  { code: 'crm_sales', permissions: ['meta.command.execute', 'crm.channel.manage', 'not.imported.yet'] },
  { code: 'crm_absent', permissions: ['meta.command.execute'] },
]);

test('without --apply nothing is written and the plan still names every gap', async () => {
  const { request, calls, held } = tenant();
  const result = await grantPluginRoles({ templates, request });

  assert.deepEqual(result.absentRoles, ['crm_absent']);
  assert.deepEqual(result.roles.map((role) => [role.roleCode, role.missing, role.unknown]), [
    ['crm_admin', ['crm.channel.manage', 'crm.target.manage'], []],
    ['crm_sales', ['crm.channel.manage'], ['not.imported.yet']],
  ]);
  assert.equal(result.applied, false);
  assert.equal(result.failed, false);
  assert.equal(result.incomplete, true);
  assert.ok(calls.every((call) => call.method === 'GET'));
  assert.equal(held.get(admin.pid).size, 2);
});

test('--apply grants only what is missing, through the additive endpoint, and reads each role back', async () => {
  const batches = [];
  const { request, held } = tenant({ onBatch: (path, body) => batches.push([path, body]) });
  const result = await grantPluginRoles({ templates, request, apply: true });

  assert.deepEqual(batches, [
    [`/api/permissions/matrix/${admin.pid}/batch`, [{ permissionId: 3, granted: true }, { permissionId: 4, granted: true }]],
    [`/api/permissions/matrix/${sales.pid}/batch`, [{ permissionId: 3, granted: true }]],
  ]);
  assert.deepEqual(result.roles.map((role) => [role.roleCode, role.granted, role.lost, role.notAdded]), [
    ['crm_admin', 2, [], []],
    ['crm_sales', 1, [], []],
  ]);
  assert.equal(result.failed, false);
  assert.deepEqual([...held.get(admin.pid)].sort(), [execute.pid, account.pid, channel.pid, target.pid].sort());
  assert.deepEqual([...held.get(sales.pid)].sort(), [execute.pid, channel.pid].sort());
});

test('a role that is complete is not written at all', async () => {
  const batches = [];
  const { request, calls } = tenant({ onBatch: (path, body) => batches.push(body) });
  const result = await grantPluginRoles({
    templates: readRoleTemplates([{ code: 'crm_admin', permissions: ['meta.command.execute'] }]),
    request,
    apply: true,
  });
  assert.deepEqual(result.roles[0], { roleCode: 'crm_admin', rolePid: admin.pid, grantedBefore: 2, missing: [], unknown: [] });
  assert.deepEqual(batches, []);
  assert.ok(calls.every((call) => call.method === 'GET'));
});

test('a write that loses an earlier grant fails the run and names the grant', async () => {
  const { request, held } = tenant({ onBatch: () => held.get(admin.pid).delete(account.pid) });
  const result = await grantPluginRoles({
    templates: readRoleTemplates([{ code: 'crm_admin', permissions: ['meta.command.execute', 'crm.account.manage', 'crm.channel.manage'] }]),
    request,
    apply: true,
  });
  assert.equal(result.failed, true);
  assert.deepEqual(result.roles[0].lost, [account.pid]);
  assert.deepEqual(result.roles[0].notAdded, []);
});

test('a grant the API accepted but did not add fails the run', async () => {
  const { request } = tenant({ applyBatch: false });
  const result = await grantPluginRoles({
    templates: readRoleTemplates([{ code: 'crm_admin', permissions: ['crm.channel.manage'] }]),
    request,
    apply: true,
  });
  assert.equal(result.failed, true);
  assert.deepEqual(result.roles[0].notAdded, ['crm.channel.manage']);
  assert.equal(result.roles[0].granted, 0);
});

test('the script has no way to revoke and writes only to the additive endpoint', () => {
  const source = readFileSync(script, 'utf8');
  assert.equal(/granted:\s*false/.test(source), false);
  const writes = source.split('\n').filter((line) => /request\('(PUT|POST|PATCH|DELETE)'/.test(line));
  assert.deepEqual(writes.map((line) => line.trim()), [
    "await request('PUT', `/api/permissions/matrix/${encodeURIComponent(rolePid)}/batch`, batchGrantBody(missing));",
  ]);
});

test('arguments resolve the plugin roles file and refuse anything unknown', () => {
  assert.equal(parseArgs(['--plugin', '/repo/plugin-aura/crm']).roles, join('/repo/plugin-aura/crm', 'config', 'roles.json'));
  assert.equal(parseArgs(['--roles', '/tmp/roles.json', '--apply']).apply, true);
  assert.throws(() => parseArgs(['--base-url']), /requires a value/);
  assert.throws(() => parseArgs(['--dry-run']), /unknown argument/);
});

/** The real script against a real local HTTP server that behaves like the four endpoints. */
function serve({ dropOnBatch = false } = {}) {
  const held = new Map([[admin.pid, new Set([execute.pid, account.pid])], [sales.pid, new Set([execute.pid])]]);
  const calls = [];
  const server = createServer((incoming, response) => {
    const chunks = [];
    incoming.on('data', (chunk) => chunks.push(chunk));
    incoming.on('end', () => {
      const path = new URL(incoming.url, 'http://localhost').pathname;
      const body = chunks.length ? JSON.parse(Buffer.concat(chunks).toString('utf8')) : undefined;
      calls.push({ method: incoming.method, path, body });
      const reply = (status, data) => {
        response.writeHead(status, { 'content-type': 'application/json' });
        response.end(JSON.stringify({ code: status === 200 ? '0' : String(status), data }));
      };
      if (incoming.headers.authorization !== 'Bearer session-token') return reply(401, null);
      if (incoming.method === 'GET' && path === '/api/roles/all') return reply(200, [admin, sales]);
      if (incoming.method === 'GET' && path === '/api/permissions/tree') return reply(200, tree);
      const role = path.match(/^\/api\/roles\/([^/]+)\/permissions$/);
      if (role && incoming.method === 'GET') return reply(200, [...held.get(role[1])]);
      if (role) return reply(405, null);
      if (incoming.method === 'PUT' && path.match(/^\/api\/permissions\/matrix\/[^/]+\/batch$/)) {
        const target = held.get(decodeURIComponent(path.split('/')[4]));
        for (const entry of body) target.add(`PERM${String(entry.permissionId).padStart(22, '0')}`);
        if (dropOnBatch) target.delete(account.pid);
        return reply(200, null);
      }
      return reply(404, null);
    });
  });
  return new Promise((resolve) => {
    server.listen(0, '127.0.0.1', () => resolve({ server, calls, held, baseUrl: `http://127.0.0.1:${server.address().port}` }));
  });
}

// Spawned asynchronously: the fake API server lives in this process and must answer while the script runs.
function run(args, { token = 'session-token' } = {}) {
  const env = { ...process.env };
  delete env.AURA_PLUGIN_ROLE_GRANTS_TOKEN;
  if (token) env.AURA_PLUGIN_ROLE_GRANTS_TOKEN = token;
  return new Promise((resolveRun, reject) => {
    const child = spawn(process.execPath, [script, ...args], { env });
    let stdout = '';
    let stderr = '';
    child.stdout.on('data', (chunk) => { stdout += chunk; });
    child.stderr.on('data', (chunk) => { stderr += chunk; });
    child.on('error', reject);
    child.on('close', (status) => resolveRun({ status, stdout, stderr }));
  });
}

async function withServer(body, options = {}) {
  const live = await serve(options);
  try {
    return await body(live);
  } finally {
    live.server.close();
  }
}

test('the command prints its usage and writes nothing without --apply', async () => {
  assert.equal((await run(['--help'])).status, 0);
  assert.equal((await run([])).status, 2);
  assert.equal((await run(['--roles', '/nowhere.json'])).status, 1);
  await withServer(async ({ calls, baseUrl, held }) => {
    const result = await run(['--roles', writeRoles('crm_admin', ['meta.command.execute', 'crm.channel.manage']), '--base-url', `${baseUrl}/`, '--json']);
    assert.equal(result.status, 0);
    assert.deepEqual(JSON.parse(result.stdout).roles[0].missing, ['crm.channel.manage']);
    assert.match(result.stdout, /"applied": false/);
    assert.ok(calls.every((call) => call.method === 'GET'));
    assert.equal(held.get(admin.pid).size, 2);
  });
});

test('the command grants through the additive endpoint, reads back and leaves a receipt', async () => {
  const roles = writeRoles('crm_admin', ['meta.command.execute', 'crm.channel.manage', 'crm.target.manage']);
  const token = join(mkdtempSync(join(tmpdir(), 'role-grants-')), 'session');
  writeFileSync(token, 'session-token\n', { mode: 0o600 });
  const receipt = `${token}.receipt.json`;
  await withServer(async ({ calls, baseUrl, held }) => {
    const result = await run(['--roles', roles, '--base-url', baseUrl, '--token-file', token, '--apply', '--receipt', receipt]);
    assert.equal(result.status, 0, result.stderr);
    assert.match(result.stdout, /granted 2/);
    assert.match(result.stdout, /Every missing grant was added and read back/);
    assert.deepEqual(calls.filter((call) => call.method !== 'GET'), [
      { method: 'PUT', path: `/api/permissions/matrix/${admin.pid}/batch`, body: [{ permissionId: 3, granted: true }, { permissionId: 4, granted: true }] },
    ]);
    assert.equal(held.get(admin.pid).size, 4);
    const first = readWhole(receipt);
    assert.equal(JSON.parse(first.text).kind, 'plugin-role-grants');
    assert.equal(JSON.parse(first.text).result.failed, false);
    assert.equal(JSON.parse(first.text).result.roles[0].granted, 2);
    assert.equal(first.mode & 0o077, 0);
    // A second run with the same receipt is refused and leaves the first one as it was.
    const again = await run(['--roles', roles, '--base-url', baseUrl, '--token-file', token, '--receipt', receipt]);
    assert.equal(again.status, 1);
    assert.match(again.stderr, /must name a new file/);
    const second = readWhole(receipt);
    assert.equal(second.text, first.text);
    assert.equal(second.mtimeMs, first.mtimeMs);
  });
});

test('the command fails when a role does not read back as expected, and when a permission is not in the tenant', async () => {
  await withServer(async ({ baseUrl }) => {
    const result = await run(['--roles', writeRoles('crm_admin', ['meta.command.execute', 'crm.channel.manage']), '--base-url', baseUrl, '--apply']);
    assert.equal(result.status, 1);
    assert.ok(result.stdout.includes(`grants lost: ${account.pid}`), result.stdout);
  }, { dropOnBatch: true });
  await withServer(async ({ baseUrl, calls }) => {
    const result = await run(['--roles', writeRoles('crm_sales', ['not.imported.yet']), '--base-url', baseUrl, '--apply']);
    assert.equal(result.status, 3);
    assert.match(result.stdout, /not in this tenant: not\.imported\.yet/);
    assert.ok(calls.every((call) => call.method === 'GET'));
  });
});

test('a token file other accounts can read is refused before any request is made', async () => {
  const directory = mkdtempSync(join(tmpdir(), 'role-grants-'));
  const token = join(directory, 'session');
  writeFileSync(token, 'session-token\n', { mode: 0o600 });
  chmodSync(token, 0o640);
  await withServer(async ({ baseUrl, calls }) => {
    const result = await run(['--roles', writeRoles('crm_sales', ['meta.command.execute']), '--base-url', baseUrl, '--token-file', token, '--apply'], { token: '' });
    assert.equal(result.status, 1);
    assert.match(result.stderr, /must not grant group or other permissions/);
    assert.deepEqual(calls, []);
  });
});

// One descriptor for the mode, the modification time and the content, so each describes what was read.
function readWhole(path) {
  const descriptor = openSync(path, 'r');
  try {
    const stat = fstatSync(descriptor);
    return { mode: stat.mode, mtimeMs: stat.mtimeMs, text: readFileSync(descriptor, 'utf8') };
  } finally {
    closeSync(descriptor);
  }
}

function writeRoles(code, permissions) {
  const file = join(mkdtempSync(join(tmpdir(), 'role-grants-')), 'roles.json');
  writeFileSync(file, JSON.stringify([{ code, permissions }]));
  return file;
}
