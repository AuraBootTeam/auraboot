import { test, expect, type APIRequestContext, type Page } from '@playwright/test';
import {
  createDefaultTableView,
  restoreDefaultTableView,
  type DefaultTableViewState,
} from './helpers/default-table-view';

const model = 'showcase_all_fields';
const pageKey = `${model}_detail`;
let snapshot: Record<string, any> | undefined;
let savedView: DefaultTableViewState | null = null;
const created: string[] = [];

async function writeBlocks(request: APIRequestContext, blocks: unknown[]) {
  const result = await request.put(`/api/pages/${snapshot!.pid}`, {
    data: {
      pageKey,
      name: snapshot!.name,
      title: snapshot!.title,
      modelCode: model,
      kind: 'detail',
      layout: snapshot!.layout,
      extension: snapshot!.extension,
      dataSources: snapshot!.dataSources,
      blocks,
    },
  });
  expect(result.ok()).toBe(true);
  expect((await result.json()).code).toBe('0');
}

async function createRow(
  request: APIRequestContext,
  name: string,
  description: string,
  quantity: number,
) {
  const response = await request.post('/api/meta/commands/execute/sc:create_showcase', {
    data: {
      operationType: 'create',
      payload: {
        sc_name: name,
        sc_description: description,
        sc_quantity: quantity,
        sc_price: 12.34,
        sc_priority: 'medium',
        sc_category: 'electronics',
      },
    },
  });
  expect(response.ok()).toBe(true);
  const body = await response.json();
  expect(body.code).toBe('0');
  const pid = body.data.data.recordPid;
  expect(typeof pid).toBe('string');
  expect(pid.length).toBeGreaterThan(5);
  created.push(pid);
  return pid as string;
}

async function openParent(page: Page, parentName: string, parentPid: string) {
  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });
  await page.evaluate(() => localStorage.removeItem('sidebar-collapsed'));
  await page.reload({ waitUntil: 'domcontentloaded' });
  const leaf = page.locator(`a[href="/p/${model}"]`).first();
  if (!(await leaf.isVisible())) {
    await page
      .locator('button, [role="menuitem"]')
      .filter({ hasText: /字段展示|能力展示|Field Showcase|Showcase|menu\.sc_root/i })
      .first()
      .click();
  }
  await leaf.click();
  await expect(page).toHaveURL(new RegExp(`/p/${model}(?:$|\\?)`));
  const row = page.locator('[data-testid="dynamic-list"] tbody tr').filter({ hasText: parentName });
  await expect(row).toHaveCount(1);
  await row.hover();
  const link = row.locator(`a[href*="/view/${parentPid}"]`).first();
  if (await link.count()) await link.click();
  else
    await row
      .getByRole('button', { name: /查看|View|详情/i })
      .first()
      .click();
  await expect(page).toHaveURL(new RegExp(`/p/${model}/view/${parentPid}(?:$|\\?)`));
  await expect(page.locator('vite-error-overlay')).toHaveCount(0);
}

async function dropFirstDown(page: Page, pid: string) {
  const handle = page.getByTestId(`drag-handle-${pid}`);
  await handle.scrollIntoViewIfNeeded();
  await handle.focus();
  const target = await page
    .locator('[data-testid^="sortable-row-"]')
    .nth(1)
    .getAttribute('data-testid');
  expect(target).toBeTruthy();
  await handle.press('Space');
  await expect(handle).toHaveAttribute('aria-pressed', 'true');
  await page.evaluate(
    () =>
      new Promise<void>((resolve) =>
        requestAnimationFrame(() => requestAnimationFrame(() => resolve())),
      ),
  );
  await page.keyboard.press('ArrowDown');
  await expect(page.locator('[id^="DndLiveRegion"]')).toContainText(
    `was moved over droppable area ${target!.replace('sortable-row-', '')}.`,
  );
  await page.keyboard.press('Space');
}

test.afterEach(async ({ request }) => {
  if (snapshot) {
    await writeBlocks(request, snapshot.blocks);
    const restored = await request.get(`/api/pages/key/${pageKey}`);
    expect((await restored.json()).data.blocks).toEqual(snapshot.blocks);
    snapshot = undefined;
  }
  while (created.length) {
    const response = await request.post('/api/meta/commands/execute/sc:delete_showcase', {
      data: { operationType: 'delete', targetRecordPid: created.pop()! },
    });
    expect(response.ok()).toBe(true);
    expect((await response.json()).code).toBe('0');
  }
  await restoreDefaultTableView(request, savedView);
  savedView = null;
});

test('sortable detail persists keyboard order and reloads after transport or partial batch failures', async ({
  page,
  request,
}, testInfo) => {
  test.setTimeout(90_000);
  savedView = await createDefaultTableView(request, model, `${model}_list`, 'owned-sort');
  const sortedView = await request.put(`/api/views/${savedView.createdPid}`, {
    data: { viewConfig: { sorts: [{ fieldCode: 'sc_code', direction: 'desc' }] } },
  });
  expect(sortedView.ok()).toBe(true);
  expect((await sortedView.json()).code).toBe('0');
  const unique = `OwnedSort${Date.now()}`;
  const parent = await createRow(request, `${unique}Parent`, 'Owned sorting parent', 7);
  const a = await createRow(request, `${unique}Alpha`, parent, 10);
  const b = await createRow(request, `${unique}Beta`, parent, 20);
  const original = await request.get(`/api/pages/key/${pageKey}`);
  expect(original.ok()).toBe(true);
  snapshot = (await original.json()).data;
  expect(snapshot!.pid).toBeTruthy();
  await writeBlocks(request, [
    {
      id: 'owned_identity',
      blockType: 'detail-section',
      title: { 'zh-CN': '排序验证父记录', en: 'Owned sorting parent' },
      columns: 2,
      fields: [{ field: 'sc_name' }],
    },
    {
      id: 'owned_sorting',
      blockType: 'sub-table',
      title: { 'zh-CN': '排序验证子项', en: 'Owned sortable children' },
      subTable: {
        childModel: model,
        parentField: 'sc_description',
        readOnly: false,
        sortable: true,
        sortField: 'sc_quantity',
        defaultSort: { field: 'sc_quantity', order: 'asc' },
        columns: [
          { field: 'sc_name', label: { 'zh-CN': '名称', en: 'Name' } },
          {
            field: 'sc_quantity',
            label: { 'zh-CN': '顺序', en: 'Order' },
            valueType: 'number',
            precision: 0,
          },
        ],
      },
    },
  ]);
  const serverOrder = async () => {
    const filters = encodeURIComponent(
      JSON.stringify([{ fieldName: 'sc_description', operator: 'EQ', value: parent }]),
    );
    const response = await request.get(
      `/api/dynamic/${model}/list?filters=${filters}&sortField=sc_quantity&sortOrder=asc&pageSize=20`,
    );
    expect(response.ok()).toBe(true);
    const body = await response.json();
    expect(body.code).toBe('0');
    return body.data.records.map((row: { pid: string; sc_quantity: number }) => ({
      pid: row.pid,
      quantity: row.sc_quantity,
    }));
  };
  await openParent(page, `${unique}Parent`, parent);
  const section = page
    .locator('.sub-table-section')
    .filter({ hasText: /排序验证子项|Owned sortable children/ });
  await expect(section).toBeVisible();
  const domOrder = () =>
    section
      .locator('tbody [data-testid^="sortable-row-"]')
      .evaluateAll((rows) => rows.map((row) => row.getAttribute('data-testid')));
  await expect.poll(domOrder).toEqual([`sortable-row-${a}`, `sortable-row-${b}`]);
  const batchResponse = page.waitForResponse(
    (r) =>
      r.request().method() === 'PUT' && new URL(r.url()).pathname === `/api/dynamic/${model}/batch`,
  );
  await dropFirstDown(page, a);
  const acknowledged = await batchResponse;
  expect(acknowledged.ok()).toBe(true);
  expect((await acknowledged.json()).code).toBe('0');
  await expect.poll(domOrder).toEqual([`sortable-row-${b}`, `sortable-row-${a}`]);
  expect(await serverOrder()).toEqual([
    { pid: b, quantity: 1000 },
    { pid: a, quantity: 2000 },
  ]);
  await page.reload({ waitUntil: 'domcontentloaded' });
  await expect.poll(domOrder).toEqual([`sortable-row-${b}`, `sortable-row-${a}`]);
  await section.screenshot({ path: testInfo.outputPath('persisted-sort.png') });

  let release!: () => void;
  const gate = new Promise<void>((resolve) => {
    release = resolve;
  });
  const routePattern = `**/api/dynamic/${model}/batch`;
  await page.route(routePattern, async (route) => {
    if (route.request().method() !== 'PUT') return route.fallback();
    await gate;
    await route.abort('failed');
  });
  try {
    const pending = page.waitForRequest(
      (r) => r.method() === 'PUT' && new URL(r.url()).pathname === `/api/dynamic/${model}/batch`,
    );
    pending.catch(() => {});
    await dropFirstDown(page, b);
    await pending;
    await expect.poll(domOrder).toEqual([`sortable-row-${a}`, `sortable-row-${b}`]);
    expect(await serverOrder()).toEqual([
      { pid: b, quantity: 1000 },
      { pid: a, quantity: 2000 },
    ]);
    const recoveredRead = page.waitForResponse(
      (r) =>
        r.request().method() === 'GET' &&
        new URL(r.url()).pathname === `/api/dynamic/${model}/list`,
    );
    release();
    expect((await recoveredRead).ok()).toBe(true);
    await expect.poll(domOrder).toEqual([`sortable-row-${b}`, `sortable-row-${a}`]);
    expect(await serverOrder()).toEqual([
      { pid: b, quantity: 1000 },
      { pid: a, quantity: 2000 },
    ]);
    await section.screenshot({ path: testInfo.outputPath('recovered-sort.png') });
  } finally {
    release();
    await page.unroute(routePattern);
  }

  // A concurrent deletion produces a genuine code=0 batch envelope with a row failure.
  const deletion = await request.post('/api/meta/commands/execute/sc:delete_showcase', {
    data: { operationType: 'delete', targetRecordPid: a },
  });
  expect(deletion.ok()).toBe(true);
  expect((await deletion.json()).code).toBe('0');
  expect(await serverOrder()).toEqual([{ pid: b, quantity: 1000 }]);
  created.splice(created.indexOf(a), 1);
  let recoveryReads = 0;
  page.on('response', (response) => {
    if (
      response.request().method() === 'GET' &&
      new URL(response.url()).pathname === `/api/dynamic/${model}/list`
    )
      recoveryReads++;
  });
  const partialResponse = page.waitForResponse(
    (r) =>
      r.request().method() === 'PUT' && new URL(r.url()).pathname === `/api/dynamic/${model}/batch`,
  );
  await dropFirstDown(page, b);
  const partial = await (await partialResponse).json();
  expect(partial.code).toBe('0');
  expect(partial.data.failed).toBe(1);
  expect(partial.data.success).toBe(1);
  await expect.poll(domOrder).toEqual([`sortable-row-${b}`]);
  expect(recoveryReads).toBeGreaterThan(0);
  expect(await serverOrder()).toEqual([{ pid: b, quantity: 2000 }]);
  await section.screenshot({ path: testInfo.outputPath('row-failure-recovered-sort.png') });
});
