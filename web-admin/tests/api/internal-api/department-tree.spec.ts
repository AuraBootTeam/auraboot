import { test, expect } from '@playwright/test';
import fs from 'node:fs/promises';
import { createHash, randomUUID } from 'node:crypto';
import pg from 'pg';
import { PG_CONN } from '../../helpers/environments';
import { provisionInternalApiUser } from '../../helpers/internal-api-owned-user';
import { createResponseShapeValidator } from '../../../../scripts/internal-api/response-shape.mjs';

const document = JSON.parse(await fs.readFile(new URL('../../../../contracts/internal-web-admin/openapi.json', import.meta.url), 'utf8'));
const catalog = JSON.parse(await fs.readFile(new URL('../../../../scripts/internal-api/catalog.json', import.meta.url), 'utf8'));
const validate = createResponseShapeValidator(document);

test('generated department tree reads preserve recursive public identities and nullable roots', async ({ browser, baseURL }, info) => {
  test.setTimeout(120_000);
  if (!baseURL) throw new Error('A managed runtime is required');
  expect(PG_CONN.database).toMatch(/^auraboot_\d+$/);
  const actor = await provisionInternalApiUser(browser, baseURL);
  const anonymous = await browser.newContext({ baseURL, storageState: { cookies: [], origins: [] } });
  const token = randomUUID().replaceAll('-', '').slice(0, 16);
  const owned: Array<{ code: string; name: string; pid?: string }> = [
    { code: `ic_dept_${token}_root`, name: `Owned root ${token}` },
    { code: `ic_dept_${token}_child`, name: `Owned child ${token}` },
  ];
  const rows: object[] = [], cleanupErrors: unknown[] = [], deletionProofs: object[] = [], observed = new Set<string>();
  let journeyError: unknown;
  async function read(path: string, query = '') {
    const response = await actor.context.request.get(path + query);
    const payload = await response.json();
    const shape = validate({ method: 'get', path, status: response.status(), contentType: response.headers()['content-type'], payload });
    rows.push({ method: 'get', path, status: response.status(), shape, payloadSha256: createHash('sha256').update(JSON.stringify(payload)).digest('hex') });
    expect(response.status()).toBe(200); expect(payload.code).toBe('0'); expect(shape.errors).toEqual([]);
    expect(Array.isArray(payload.data)).toBe(true); observed.add(`get ${path}`);
    return payload.data;
  }
  async function create(index: number, parentPid: string | null) {
    const fixture = owned[index];
    const response = await actor.context.request.post('/api/org/departments', { headers: { Origin: actor.origin }, data: {
      org_dept_name: fixture.name, org_dept_code: fixture.code, org_dept_parent_id: parentPid, org_dept_order: index, org_dept_status: 'active',
    } });
    const payload = await response.json();
    if (typeof payload.data?.pid === 'string') fixture.pid = payload.data.pid;
    expect(response.status()).toBe(200); expect(payload.code).toBe('0'); expect(fixture.pid).toBeTruthy();
    return fixture.pid!;
  }
  function assertOwnedBranch(tree: Array<{ pid: string; name: string; parentPid: string | null; employeeCount: number; children: unknown[] }>) {
    const root = tree.find(node => node.pid === owned[0].pid);
    expect(root).toBeDefined();
    expect(root).toMatchObject({ pid: owned[0].pid, name: owned[0].name, parentPid: null, employeeCount: 0 });
    expect(Object.keys(root!).sort()).toEqual(['children', 'employeeCount', 'name', 'parentPid', 'pid']);
    expect(root!.children).toEqual([{ pid: owned[1].pid, name: owned[1].name, parentPid: owned[0].pid, employeeCount: 0, children: [] }]);
  }
  try {
    await create(0, null); await create(1, owned[0].pid!);
    expect(owned[0].pid).not.toBe(owned[1].pid);
    assertOwnedBranch(await read('/api/org/departments/tree'));
    assertOwnedBranch(await read('/api/org/departments/permitted-tree', '?resource=org_department&action=read'));
    expect([...observed].sort()).toEqual(catalog.profiles.departmentTreeWire.operations.map((row: { method: string; path: string }) => `${row.method} ${row.path}`).sort());
    for (const path of ['/api/org/departments/tree', '/api/org/departments/permitted-tree?resource=org_department&action=read'])
      expect((await anonymous.request.get(path)).status()).toBe(401);
  } catch (error) { journeyError = error; }
  finally {
    const db = new pg.Client(PG_CONN);
    try {
      await db.connect();
      for (const fixture of [...owned].reverse()) {
        try {
          // Unique submitted names/codes retain ownership even if the create response was malformed.
          const found = await db.query('SELECT pid, deleted_flag FROM mt_org_department WHERE org_dept_code=$1 AND org_dept_name=$2', [fixture.code, fixture.name]);
          expect(found.rowCount).toBeLessThanOrEqual(1);
          if (!found.rowCount) { expect(fixture.pid).toBeUndefined(); continue; }
          if (fixture.pid) expect(found.rows[0].pid).toBe(fixture.pid);
          fixture.pid = found.rows[0].pid;
          if (!found.rows[0].deleted_flag) {
            const removed = await actor.context.request.delete(`/api/org/departments/${fixture.pid}`, { headers: { Origin: actor.origin } });
            expect(removed.status()).toBe(200); expect((await removed.json()).code).toBe('0');
          }
          const remaining = await db.query('SELECT org_dept_code, deleted_flag FROM mt_org_department WHERE pid=$1', [fixture.pid]);
          expect(remaining.rowCount).toBeLessThanOrEqual(1);
          if (remaining.rowCount) {
            expect(remaining.rows[0].org_dept_code).toBe(fixture.code);
            expect(remaining.rows[0].deleted_flag).toBe(true);
          }
          deletionProofs.push({ pid: fixture.pid, code: fixture.code, remainingRows: remaining.rowCount,
            mode: remaining.rowCount === 0 ? 'physical-delete' : 'soft-delete',
            deletedFlag: remaining.rowCount ? remaining.rows[0].deleted_flag : null });
        } catch (error) { cleanupErrors.push(error); }
      }
      if (!cleanupErrors.length) {
        const tree = await read('/api/org/departments/tree');
        expect(tree.some((node: { pid: string }) => node.pid === owned[0].pid || node.pid === owned[1].pid)).toBe(false);
      }
    } catch (error) { cleanupErrors.push(error); }
    finally { await db.end(); }
    try { await anonymous.close(); await actor.dispose(); } catch (error) { cleanupErrors.push(error); }
    await info.attach('department-tree-observations', { body: JSON.stringify({
      claim: 'two department tree read operations and owned root/leaf persistence; no cross-tenant or picker-browser acceptance',
      rows, owned, deletionProofs, observed: [...observed], sessionCleanup: actor.sessionCleanup,
      journeyError: journeyError ? String(journeyError) : null, cleanupErrors: cleanupErrors.map(String),
    }), contentType: 'application/json' });
  }
  if (journeyError || cleanupErrors.length) throw new AggregateError([...(journeyError ? [journeyError] : []), ...cleanupErrors], 'Department tree journey or owned cleanup failed');
});
