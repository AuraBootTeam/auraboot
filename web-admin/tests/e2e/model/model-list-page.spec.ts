import { test, expect, type Page } from '../../fixtures';
import {
  createFieldBindingData,
  createFieldData,
  createModelData,
} from '../../model-system/helpers/test-data';

interface PreparedModel {
  pid: string;
  code: string;
  displayName: string;
  status: 'draft' | 'published';
  fieldPid?: string;
}

const createdModels: PreparedModel[] = [];

test.describe.configure({ timeout: 45_000 });

function getModelRow(page: Page, code: string) {
  return page.locator('tbody tr', { hasText: code }).first();
}

async function navigateToModelListViaMenu(page: Page) {
  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' }).catch(() => {});

  const parent = page
    .locator('button', { hasText: /元数据管理|Metadata|menu\.meta_management/i })
    .first();
  await expect(parent).toBeVisible({ timeout: 10_000 });
  await parent.evaluate((element: HTMLElement) => element.click());

  const listResponse = page.waitForResponse(
    (response) =>
      response.url().includes('/api/meta/models') &&
      response.request().method() === 'GET' &&
      response.status() === 200,
    { timeout: 10_000 },
  );

  const leaf = page.locator('a[href="/meta/models"], a[href*="/meta/models"]').first();
  await expect(leaf).toBeAttached({ timeout: 5_000 });
  await leaf.evaluate((element: HTMLElement) => element.click());
  await listResponse;

  await expect(page).toHaveURL(/\/meta\/models(?:\?|$)/, { timeout: 10_000 });
  await expect(page.locator('table').first()).toBeVisible({ timeout: 10_000 });
}

async function searchModel(page: Page, keyword: string) {
  // Prefer the stable data-testid; fall back to placeholder substrings
  // (the actual i18n placeholder may render as "查询..." or "Search...").
  const searchInput = page
    .locator(
      [
        '[data-testid="list-search-input"]',
        'input[placeholder*="Search"]',
        'input[placeholder*="搜索"]',
        'input[placeholder*="查询"]',
      ].join(', '),
    )
    .first();
  await expect(searchInput).toBeVisible({ timeout: 10_000 });
  await searchInput.click();
  await searchInput.fill(keyword);

  const listResponse = page.waitForResponse(
    (response) =>
      response.url().includes('/api/meta/models') &&
      response.request().method() === 'GET' &&
      response.status() === 200,
    { timeout: 10_000 },
  );
  await searchInput.press('Enter');
  await listResponse;
}

async function chooseFilterOption(page: Page, index: number, optionText: string) {
  const combo = page.getByRole('combobox').nth(index);
  await expect(combo).toBeVisible({ timeout: 10_000 });
  await combo.click();
  const option = page.getByRole('option', { name: optionText }).first();
  await expect(option).toBeVisible({ timeout: 10_000 });
  await option.click();
}

async function submitFilters(page: Page) {
  const responsePromise = page.waitForResponse(
    (response) =>
      response.url().includes('/api/meta/models') &&
      response.request().method() === 'GET' &&
      response.status() === 200,
    { timeout: 10_000 },
  );
  await page.getByTestId('filter-search').click();
  return responsePromise;
}

async function clickColumnHeader(page: Page, columnLabel: string, expectedOrder: 'asc' | 'desc') {
  const header = page.locator('th').filter({ hasText: columnLabel }).first();
  await expect(header).toBeVisible({ timeout: 10_000 });
  const responsePromise = page.waitForResponse(
    (response) =>
      response.url().includes('/api/meta/models') &&
      response.request().method() === 'GET' &&
      response.url().includes('sortField=displayName') &&
      response.url().includes(`sortOrder=${expectedOrder}`) &&
      response.status() === 200,
    { timeout: 10_000 },
  );
  await header.click();
  return responsePromise;
}

async function prepareModel(
  api: any,
  status: 'draft' | 'published',
  overrides: Partial<{ code: string; displayName: string }> = {},
): Promise<PreparedModel> {
  const modelData = createModelData({
    code: overrides.code,
    displayName: overrides.displayName || `List Page ${status} ${Date.now()}`,
  });
  const created = await api.createModel(modelData);
  expect(created.code).toBe('0');
  expect(created.data).not.toBeNull();

  const prepared: PreparedModel = {
    pid: created.data!.pid,
    code: created.data!.code,
    displayName: created.data!.displayName,
    status,
  };

  if (status === 'published') {
    const fieldData = createFieldData('string', {
      uiSchema: { label: `Published Field ${modelData.code}` },
    });
    const field = await api.createField(fieldData);
    expect(field.code).toBe('0');
    expect(field.data).not.toBeNull();
    prepared.fieldPid = field.data!.pid;

    const bind = await api.bindFieldToModel(
      prepared.pid,
      createFieldBindingData(prepared.fieldPid!, { displayOrder: 0 }),
    );
    expect(bind.code).toBe('0');

    const publish = await api.publishModel(prepared.pid, 'Model list page E2E publish');
    expect(publish.code).toBe('0');
    expect(publish.data?.status).toBe('published');
  }

  createdModels.push(prepared);
  return prepared;
}

test.describe('Model List Page', () => {
  test.afterEach(async ({ api }) => {
    while (createdModels.length > 0) {
      const model = createdModels.pop()!;
      await api.deleteModel(model.pid).catch(() => null);
      if (model.fieldPid) {
        await api.deleteField(model.fieldPid).catch(() => null);
      }
    }
  });

  test('ML-01: list renders status and version columns without render errors', async ({
    page,
    api,
  }) => {
    const unique = Date.now().toString(36);
    const draftModel = await prepareModel(api, 'draft', {
      code: `ml_draft_${unique}`,
      displayName: `List Draft ${unique}`,
    });
    const publishedModel = await prepareModel(api, 'published', {
      code: `ml_pub_${unique}`,
      displayName: `List Published ${unique}`,
    });

    await navigateToModelListViaMenu(page);
    await searchModel(page, draftModel.code);

    const draftRow = getModelRow(page, draftModel.code);
    await expect(draftRow).toBeVisible({ timeout: 10_000 });
    await expect(draftRow).not.toContainText('渲染错误');
    await expect(draftRow).toContainText(/草稿|Draft/);
    await expect(draftRow).toContainText(/物理表|Physical/);
    await expect(draftRow.locator('[data-testid$="-fieldCount"]')).toHaveText(/^0$/);

    await searchModel(page, publishedModel.code);
    const publishedRow = getModelRow(page, publishedModel.code);
    await expect(publishedRow).toBeVisible({ timeout: 10_000 });
    await expect(publishedRow).not.toContainText('渲染错误');
    await expect(publishedRow).toContainText(/已发布|Published/);
    await expect(publishedRow).toContainText(/物理表|Physical/);
    await expect(publishedRow.locator('[data-testid$="-fieldCount"]')).toHaveText(/^1$/);
  });

  test('ML-02: row actions navigate to detail and edit pages', async ({ page, api }) => {
    const unique = Date.now().toString(36);
    const model = await prepareModel(api, 'draft', {
      code: `ml_actions_${unique}`,
      displayName: `Model Actions ${unique}`,
    });

    await navigateToModelListViaMenu(page);
    await searchModel(page, model.code);

    const row = getModelRow(page, model.code);
    await expect(row).toBeVisible({ timeout: 10_000 });

    await page.evaluate(() => {
      document.querySelectorAll('vite-error-overlay').forEach((el) => el.remove());
    });
    const viewButton = row.getByRole('button', { name: '查看' }).first();
    await expect(viewButton).toBeVisible({ timeout: 5_000 });
    await viewButton.evaluate((element: HTMLElement) => element.click());
    await expect(page).toHaveURL(new RegExp(`/meta/models/${model.pid}(?:#|\\?|$)`), {
      timeout: 10_000,
    });
    await expect(page.getByText(model.displayName).first()).toBeVisible({ timeout: 10_000 });

    await page.goBack();
    await expect(page).toHaveURL(/\/meta\/models(?:\?|$)/, { timeout: 10_000 });
    await searchModel(page, model.code);

    const rowAfterBack = getModelRow(page, model.code);
    await expect(rowAfterBack).toBeVisible({ timeout: 10_000 });
    await page.evaluate(() => {
      document.querySelectorAll('vite-error-overlay').forEach((el) => el.remove());
    });
    const editButton = rowAfterBack.getByRole('button', { name: '编辑' }).first();
    await expect(editButton).toBeVisible({ timeout: 5_000 });
    await editButton.evaluate((element: HTMLElement) => element.click());

    await expect(page).toHaveURL(new RegExp(`/meta/models/${model.pid}/edit(?:\\?|$)`), {
      timeout: 10_000,
    });
    await expect(page.locator('input').first()).toBeVisible({ timeout: 10_000 });
  });

  test('ML-03: status and sourceType filters are applied server-side', async ({
    page,
    api,
    request,
  }) => {
    const unique = Date.now().toString(36);
    const draftModel = await prepareModel(api, 'draft', {
      code: `ml_filter_draft_${unique}`,
      displayName: `Model Filter Pair ${unique}`,
    });
    const publishedModel = await prepareModel(api, 'published', {
      code: `ml_filter_pub_${unique}`,
      displayName: `Model Filter Pair ${unique}`,
    });

    const namedQueryResp = await request.get('/api/meta/models', {
      params: {
        sourceType: 'namedQuery',
        size: 20,
        current: 1,
      },
    });
    expect(namedQueryResp.ok()).toBeTruthy();
    const namedQueryBody = await namedQueryResp.json();
    const namedQueryRecord = (namedQueryBody.data?.records ?? [])[0];
    test.skip(!namedQueryRecord, '需要至少一个已存在的 namedQuery 模型来验证来源筛选');

    await navigateToModelListViaMenu(page);
    await searchModel(page, `Model Filter Pair ${unique}`);

    await chooseFilterOption(page, 1, '已发布');
    const statusResp = await submitFilters(page);
    expect(statusResp.url()).toContain('status=published');

    await expect(getModelRow(page, publishedModel.code)).toBeVisible({ timeout: 10_000 });
    await expect(getModelRow(page, draftModel.code)).toHaveCount(0);

    await searchModel(page, '');
    await chooseFilterOption(page, 0, 'NamedQuery');
    const sourceResp = await submitFilters(page);
    expect(sourceResp.url()).toContain('sourceType=namedQuery');

    await expect(getModelRow(page, draftModel.code)).toHaveCount(0);
    await expect(getModelRow(page, publishedModel.code)).toHaveCount(0);
    await expect(getModelRow(page, namedQueryRecord.code)).toBeVisible({ timeout: 10_000 });
  });

  test('ML-04: displayName sorting updates server query and row order', async ({ page, api }) => {
    const unique = Date.now().toString(36);
    const first = await prepareModel(api, 'draft', {
      code: `ml_sort_pair_${unique}_alpha`,
      displayName: `Sort Pair ${unique} Alpha`,
    });
    const second = await prepareModel(api, 'draft', {
      code: `ml_sort_pair_${unique}_zulu`,
      displayName: `Sort Pair ${unique} Zulu`,
    });

    await navigateToModelListViaMenu(page);
    await searchModel(page, `ml_sort_pair_${unique}`);

    const ascResp = await clickColumnHeader(page, '显示名称', 'asc');
    expect(ascResp.url()).toContain('sortField=displayName');
    expect(ascResp.url()).toContain('sortOrder=asc');
    const ascBody = await ascResp.json();
    const ascRecords = (ascBody.data?.records ?? []).filter((record: any) =>
      String(record?.code ?? '').includes(`ml_sort_pair_${unique}`),
    );
    expect(ascRecords[0]?.displayName).toContain('Alpha');
    expect(ascRecords[1]?.displayName).toContain('Zulu');

    const rowsAfterAsc = page.locator('tbody tr');
    await expect(rowsAfterAsc.first()).toContainText(first.displayName);

    const descResp = await clickColumnHeader(page, '显示名称', 'desc');
    expect(descResp.url()).toContain('sortField=displayName');
    expect(descResp.url()).toContain('sortOrder=desc');
    const descBody = await descResp.json();
    const descRecords = (descBody.data?.records ?? []).filter((record: any) =>
      String(record?.code ?? '').includes(`ml_sort_pair_${unique}`),
    );
    expect(descRecords[0]?.displayName).toContain('Zulu');
    expect(descRecords[1]?.displayName).toContain('Alpha');

    const rowsAfterDesc = page.locator('tbody tr');
    await expect(rowsAfterDesc.first()).toContainText(second.displayName);
  });
});
