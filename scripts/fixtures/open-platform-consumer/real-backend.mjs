/** Fresh-tarball consumer. Every fetch goes to the real backend; stdin is private. */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { OpenPlatformClient, OpenPlatformError } from '@auraboot/open-platform-sdk';

const profile = JSON.parse(fs.readFileSync(new URL('./real-backend.profile.json', import.meta.url), 'utf8'));
const fixture = JSON.parse(fs.readFileSync(0, 'utf8'));
const rows = profile.cases.map(id => ({ id, status: 'did_not_run' }));
let requests = 0, exchanges = 0;
const client = new OpenPlatformClient({ baseUrl: fixture.baseUrl,
  clientId: fixture.clientId, clientSecret: fixture.clientSecret, scope: fixture.scopes.join(' '),
  fetch: async (url, init) => {
    requests++;
    if (new URL(url).pathname === '/oauth2/token') exchanges++;
    return globalThis.fetch(url, init);
  },
});
const report = { schemaVersion: 1, profile: profile.id, cases: rows, executed: 0, passed: 0 };
const deny = status => error => error instanceof OpenPlatformError && error.status === status;
const projection = value => assert.deepEqual(Object.keys(value).sort(), ['name', 'pid', 'zone']);
async function check(id, action) {
  const row = rows.find(row => row.id === id);
  assert.equal(row.status, 'did_not_run');
  try { await action(); row.status = 'passed'; }
  catch (error) { row.status = 'failed'; report.errorType = error.name; throw error; }
}
let created, changed;
try {
  await check('scoped-list', async () => {
    const page = await client.listResources(fixture.resourceCode, { limit: 10, filters: [{field: 'zone', operator: 'EQ', value: 'A'}] });
    assert.deepEqual(page.items, [{ pid: fixture.allowedPid, name: 'Allowed A', zone: 'A' }]);
  });
  await check('versioned-read', async () => {
    const result = await client.getResource(fixture.resourceCode, fixture.allowedPid);
    assert.equal(result.resource.name, 'Allowed A'); projection(result.resource);
    assert.match(result.etag, /^".+"$/);
  });
  await check('hidden-record', () => assert.rejects(client.getResource(fixture.resourceCode, fixture.hiddenPid), deny(404)));
  await check('create-metadata', async () => {
    created = await client.createResource(fixture.resourceCode, { name: 'SDK created', zone: 'A' }, 'release-sdk-create');
    assert.equal(created.resource.name, 'SDK created'); assert.equal(created.resource.zone, 'A'); projection(created.resource);
    assert.match(created.etag, /^".+"$/); assert.equal(created.idempotentReplay, false);
    assert.equal(created.location, `/api/open/v1/resources/${fixture.resourceCode}/${created.resource.pid}`);
    assert.equal(typeof created.requestId, 'string'); assert.ok(created.requestId.length > 0);
  });
  await check('idempotent-replay', async () => {
    const replay = await client.createResource(fixture.resourceCode, { name: 'SDK created', zone: 'A' }, 'release-sdk-create');
    assert.deepEqual(replay.resource, created.resource); assert.equal(replay.etag, created.etag); assert.equal(replay.idempotentReplay, true);
  });
  await check('idempotent-conflict', () => assert.rejects(client.createResource(fixture.resourceCode, {name: 'Changed intent', zone: 'A'}, 'release-sdk-create'), deny(409)));
  await check('query-source-projection', async () => {
    const a = await client.executeQuery(fixture.queryCode, {parameters: {zone: 'A'}, limit: 10});
    const b = await client.executeQuery(fixture.queryCode, {parameters: {zone: 'B'}, limit: 10});
    assert.deepEqual(a.items.map(row => row.name).sort(), ['Allowed A', 'SDK created']); assert.deepEqual(b.items, []);
    for (const row of a.items) assert.deepEqual(Object.keys(row).sort(), ['name', 'pid']);
  });
  await check('update-version', async () => {
    changed = await client.updateResource(fixture.resourceCode, created.resource.pid, {name: 'SDK changed'}, 'release-sdk-update', created.etag);
    assert.equal(changed.resource.name, 'SDK changed'); projection(changed.resource); assert.notEqual(changed.etag, created.etag);
  });
  await check('stale-write', async () => {
    await assert.rejects(client.updateResource(fixture.resourceCode, created.resource.pid, {name: 'Stale'}, 'release-sdk-stale', created.etag), deny(412));
    const persisted = await client.getResource(fixture.resourceCode, created.resource.pid);
    assert.deepEqual(persisted.resource, changed.resource); assert.equal(persisted.etag, changed.etag);
  });
  await check('outside-create', () => assert.rejects(client.createResource(fixture.resourceCode, {name: 'Outside', zone: 'B'}, 'release-sdk-outside'), deny(403)));
  await check('prospective-row-denial', async () => {
    await assert.rejects(client.updateResource(fixture.resourceCode, created.resource.pid, {zone: 'B'}, 'release-sdk-zone', changed.etag), deny(403));
    assert.deepEqual((await client.getResource(fixture.resourceCode, created.resource.pid)).resource, changed.resource);
  });
  await check('delete-empty-body', async () => {
    const deleted = await client.deleteResource(fixture.resourceCode, created.resource.pid, 'release-sdk-delete', changed.etag);
    assert.equal(deleted.idempotentReplay, false); assert.equal(typeof deleted.requestId, 'string');
    await assert.rejects(client.getResource(fixture.resourceCode, created.resource.pid), deny(404));
  });
  await check('cached-real-token', async () => { assert.equal(exchanges, 1); assert.equal(requests, 17); });
} catch {
  // Only safe structural diagnostics leave this process; credentials and bodies never do.
} finally {
  report.executed = rows.filter(row => row.status !== 'did_not_run').length;
  report.passed = rows.filter(row => row.status === 'passed').length;
  report.requests = requests; report.tokenExchanges = exchanges;
  console.log(JSON.stringify(report));
}
if (report.passed !== profile.cases.length) process.exitCode = 1;
