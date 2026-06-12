import type { Page } from '@playwright/test';
import { test, expect } from '../../fixtures';
import { uniqueId } from '../helpers';

const DETAIL_MODEL = 'showcase_all_fields';
const SEED_SECTION_ID = 'pd_detail_sections_seed';

async function createDetailSectionsPage(page: Page) {
  const id = uniqueId('pd_detail_sections');
  const pageKey = id.replace(/-/g, '_');
  const title = `Detail sections authoring ${id}`;
  const payload = {
    name: title,
    pageKey,
    title,
    kind: 'detail',
    modelCode: DETAIL_MODEL,
    profile: 'admin',
    layout: { type: 'stack', gap: 12 },
    blocks: [
      {
        id: SEED_SECTION_ID,
        blockType: 'detail-section',
        title: 'Seed section',
        columns: 2,
        fields: [],
      },
    ],
    dataSources: {},
    schemaVersion: 4,
    metaInfo: { runtimeE2E: true, detailSectionsAuthoring: true },
    semver: '0.1.0',
  };

  const createResp = await page.request.post('/api/pages', { data: payload });
  expect(createResp.ok(), `Create detail sections page failed: ${createResp.status()}`).toBeTruthy();
  const createBody = await createResp.json();
  expect(createBody.code, 'create page API code').toBe('0');
  const pid = String(createBody.data?.pid || '');
  expect(pid, 'created detail sections pid').toBeTruthy();
  return { pid, pageKey, title };
}

async function fetchModelFieldsByCode(page: Page, modelCode: string) {
  const modelResp = await page.request.get(`/api/meta/models/code/${modelCode}`);
  expect(modelResp.ok(), `Fetch model ${modelCode} failed: ${modelResp.status()}`).toBeTruthy();
  const modelBody = await modelResp.json();
  expect(modelBody.code, 'model lookup API code').toBe('0');
  const modelPid = String(modelBody.data?.pid || '');
  expect(modelPid, `model pid for ${modelCode}`).toBeTruthy();

  const fieldsResp = await page.request.get(`/api/meta/models/${modelPid}/fields`);
  expect(fieldsResp.ok(), `Fetch model fields failed: ${fieldsResp.status()}`).toBeTruthy();
  const fieldsBody = await fieldsResp.json();
  expect(fieldsBody.code, 'model fields API code').toBe('0');
  const fields = Array.isArray(fieldsBody.data) ? fieldsBody.data : [];
  return new Map(fields.map((field: any) => [String(field.code), String(field.dataType)]));
}

async function openDetailDesigner(page: Page, pid: string) {
  await page.goto(`/page-designer/${pid}`, { waitUntil: 'domcontentloaded' });
  await expect(page.getByTestId('detail-config-panel')).toBeVisible({ timeout: 15_000 });
  await expect(page.getByTestId('toolbar-save')).toBeVisible({ timeout: 10_000 });
}

async function openSectionsTab(page: Page) {
  await expect(page.getByTestId('detail-tab-sections')).toBeVisible({ timeout: 5_000 });
  await page.getByTestId('detail-tab-sections').click();
  await expect(page.getByTestId('add-section-btn')).toBeVisible({ timeout: 5_000 });
}

async function selectSection(page: Page, index: number, expectedTitle: string) {
  const titleInput = page.getByTestId('schema-config-field-title').locator('input');
  if (
    (await titleInput.isVisible().catch(() => false)) &&
    (await titleInput.inputValue().catch(() => '')) === expectedTitle
  ) {
    return;
  }
  const sectionSelect = page.getByTestId(`section-select-${index}`);
  await sectionSelect.scrollIntoViewIfNeeded();
  await expect(sectionSelect).toBeVisible({ timeout: 5_000 });
  await sectionSelect.click();
  await expect(titleInput).toHaveValue(expectedTitle, { timeout: 5_000 });
}

async function setSectionTitle(page: Page, title: string) {
  const titleInput = page.getByTestId('schema-config-field-title').locator('input');
  await titleInput.fill('');
  await titleInput.fill(title);
  await expect(titleInput).toHaveValue(title);
}

async function selectSectionColumns(page: Page, label: string) {
  const wrapper = page.getByTestId('schema-config-field-columns');
  await wrapper.getByRole('combobox').click();
  await page.getByRole('option', { name: label, exact: true }).click();
  await expect(wrapper.getByRole('combobox')).toContainText(label);
}

async function toggleSectionSwitch(page: Page, key: string) {
  const wrapper = page.getByTestId(`schema-config-field-${key}`);
  await expect(wrapper).toBeVisible({ timeout: 5_000 });
  await wrapper.getByRole('switch').click();
  await expect(wrapper.getByRole('switch')).toHaveAttribute('data-state', 'checked');
}

async function assignField(page: Page, fieldCode: string) {
  const fieldRow = page.getByTestId(`section-field-${fieldCode}`);
  await expect(fieldRow).toBeVisible({ timeout: 5_000 });
  const checkbox = fieldRow.locator('input[type="checkbox"]');
  await checkbox.check();
  await expect(checkbox).toBeChecked();
}

async function saveDesignerAndWait(page: Page, pid: string) {
  const saveButton = page.getByTestId('toolbar-save');
  await expect(saveButton).toBeVisible({ timeout: 5_000 });
  await expect
    .poll(async () => saveButton.isEnabled().catch(() => false), { timeout: 8_000 })
    .toBe(true);

  const saveResp = page.waitForResponse(
    (response) =>
      response.url().includes(`/api/pages/${pid}`) &&
      response.request().method() === 'PUT' &&
      response.status() < 400,
    { timeout: 10_000 },
  );
  await saveButton.click();
  await saveResp;
}

async function fetchPageByPid(page: Page, pid: string): Promise<Record<string, any>> {
  const resp = await page.request.get(`/api/pages/${pid}`);
  expect(resp.ok(), `Fetch page ${pid} failed: ${resp.status()}`).toBeTruthy();
  const body = await resp.json();
  expect(body.code, 'fetch page API code').toBe('0');
  return body.data ?? {};
}

function detailSections(savedPage: Record<string, any>): Array<Record<string, any>> {
  return (savedPage.blocks ?? []).filter((block: any) => block.blockType === 'detail-section');
}

test.describe('Page Designer detail sections authoring', () => {
  test('adds, configures, reorders, deletes, saves, and reloads detail sections', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 1600, height: 950 });

    const fieldTypes = await fetchModelFieldsByCode(page, DETAIL_MODEL);
    expect(fieldTypes.get('sc_name'), 'metadata dataType for sc_name').toBe('string');
    expect(fieldTypes.get('sc_description'), 'metadata dataType for sc_description').toBe('text');
    expect(fieldTypes.get('sc_quantity'), 'metadata dataType for sc_quantity').toBe('integer');

    const { pid } = await createDetailSectionsPage(page);
    await openDetailDesigner(page, pid);
    await openSectionsTab(page);

    await page.getByTestId('add-section-btn').click();
    await expect(page.getByTestId('section-item-1')).toContainText('分组 2', { timeout: 5_000 });
    await page.getByTestId('add-section-btn').click();
    await expect(page.getByTestId('section-item-2')).toContainText('分组 3', { timeout: 5_000 });

    await selectSection(page, 1, '分组 2');
    await setSectionTitle(page, 'Operational snapshot');
    await selectSectionColumns(page, '3 列');
    await toggleSectionSwitch(page, 'collapsible');
    await toggleSectionSwitch(page, 'defaultCollapsed');
    await assignField(page, 'sc_name');
    await assignField(page, 'sc_quantity');

    await selectSection(page, 2, '分组 3');
    await setSectionTitle(page, 'Narrative');
    await selectSectionColumns(page, '1 列');
    await assignField(page, 'sc_description');

    await page.getByTestId('section-move-up-2').click();
    await expect(page.getByTestId('section-item-1')).toContainText('Narrative');
    await page.getByTestId('section-remove-0').click();
    await expect(page.getByTestId('section-item-0')).toContainText('Narrative');
    await expect(page.getByTestId('section-item-1')).toContainText('Operational snapshot');
    await expect(page.getByTestId('section-item-2')).toHaveCount(0);

    await saveDesignerAndWait(page, pid);
    const savedPage = await fetchPageByPid(page, pid);
    expect(detailSections(savedPage)).toMatchObject([
      {
        title: 'Narrative',
        columns: 1,
        fields: ['sc_description'],
      },
      {
        title: 'Operational snapshot',
        columns: 3,
        fields: ['sc_name', 'sc_quantity'],
        collapsible: true,
        defaultCollapsed: true,
      },
    ]);

    await openDetailDesigner(page, pid);
    await openSectionsTab(page);
    await expect(page.getByTestId('section-item-0')).toContainText('Narrative');
    await expect(page.getByTestId('section-item-1')).toContainText('Operational snapshot');
    await selectSection(page, 1, 'Operational snapshot');
    await expect(page.getByTestId('schema-config-field-title').locator('input')).toHaveValue(
      'Operational snapshot',
    );
    await expect(page.getByTestId('schema-config-field-columns').getByRole('combobox')).toContainText(
      '3 列',
    );
    await expect(page.getByTestId('schema-config-field-collapsible').getByRole('switch')).toHaveAttribute(
      'data-state',
      'checked',
    );
    await expect(page.getByTestId('schema-config-field-defaultCollapsed').getByRole('switch')).toHaveAttribute(
      'data-state',
      'checked',
    );
    await expect(
      page.getByTestId('section-field-sc_name').locator('input[type="checkbox"]'),
    ).toBeChecked();
    await expect(
      page.getByTestId('section-field-sc_quantity').locator('input[type="checkbox"]'),
    ).toBeChecked();
  });
});
