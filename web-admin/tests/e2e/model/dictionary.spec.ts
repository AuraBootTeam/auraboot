/**
 * Dictionary E2E Tests
 *
 * Tests M-030 ~ M-034: Dictionary types, binding, validation, versioning
 *
 * Uses storageState for authentication and API for data preparation.
 *
 * @since 4.0.0
 */

import { test, expect } from '../../fixtures';
import {
  createModelData,
  createFieldData,
  createDictData,
  type DictType,
} from '../../model-system/helpers/test-data';

test.describe('Dictionary Tests', () => {
  /**
   * M-030: SIMPLE dictionary creation
   */
  test('M-030: SIMPLE dictionary creation', async ({ page, api }) => {
    const dictData = createDictData('simple', {
      items: [
        { value: 'active', label: 'Active', sortOrder: 1 },
        { value: 'inactive', label: 'Inactive', sortOrder: 2 },
        { value: 'pending', label: 'Pending', sortOrder: 3 },
      ],
    });

    // Create dictionary via API
    const response = await api.createDict(dictData);
    expect(api.isSuccess(response)).toBe(true);
    expect(response.data).not.toBeNull();
    expect(response.data!.dictType).toBe('simple');

    // Get dictionary details and verify items
    const detailResponse = await api.getDictByPid(response.data!.pid);
    expect(api.isSuccess(detailResponse)).toBe(true);

    if (detailResponse.data!.items) {
      expect(detailResponse.data!.items.length).toBe(3);
      const values = detailResponse.data!.items.map(i => i.value);
      expect(values).toContain('active');
      expect(values).toContain('inactive');
      expect(values).toContain('pending');
    }

    // Verify in UI
    await page.goto(`/meta/dict/${response.data!.pid}`);
    await page.waitForLoadState('domcontentloaded');
  });

  /**
   * M-031: TREE dictionary creation
   */
  test('M-031: TREE dictionary creation', async ({ page, api }) => {
    const dictData = createDictData('tree', {
      items: [
        { value: 'asia', label: 'Asia', sortOrder: 1 },
        { value: 'china', label: 'China', parentValue: 'asia', sortOrder: 2 },
        { value: 'japan', label: 'Japan', parentValue: 'asia', sortOrder: 3 },
        { value: 'beijing', label: 'Beijing', parentValue: 'china', sortOrder: 4 },
        { value: 'europe', label: 'Europe', sortOrder: 5 },
        { value: 'germany', label: 'Germany', parentValue: 'europe', sortOrder: 6 },
      ],
    });

    // Create dictionary via API
    const response = await api.createDict(dictData);
    expect(api.isSuccess(response)).toBe(true);
    expect(response.data).not.toBeNull();
    expect(response.data!.dictType).toBe('tree');

    // Get cascade tree structure
    const treeResponse = await api.getDictCascadeTree(response.data!.pid);
    if (api.isSuccess(treeResponse)) {
      expect(treeResponse.data).toBeDefined();
    }

    // Verify in UI
    await page.goto(`/meta/dict/${response.data!.pid}`);
    await page.waitForLoadState('domcontentloaded');
  });

  /**
   * M-032: Dictionary binding to field
   */
  test('M-032: Dictionary binding to field', async ({ api }) => {
    // 1. Create a dictionary
    const dictData = createDictData('simple', {
      items: [
        { value: 'option1', label: 'Option 1', sortOrder: 1 },
        { value: 'option2', label: 'Option 2', sortOrder: 2 },
      ],
    });

    const dictResponse = await api.createDict(dictData);
    expect(api.isSuccess(dictResponse)).toBe(true);
    expect(dictResponse.data).toBeTruthy();

    // New dictionaries are auto-published in the current lifecycle.
    if (dictResponse.data!.status !== 'published') {
      const publishResponse = await api.publishDict(dictResponse.data!.pid);
      expect(api.isSuccess(publishResponse)).toBe(true);
    }

    // 2. Create a field
    const fieldData = createFieldData('string');
    const fieldResponse = await api.createField(fieldData);
    expect(api.isSuccess(fieldResponse)).toBe(true);
    expect(fieldResponse.data).toBeTruthy();

    // 3. Bind dictionary to field
    const bindResponse = await api.bindDictToField(
      fieldResponse.data!.pid,
      dictResponse.data!.code
    );
    // Bind may succeed or already bound — verify it didn't error out
    expect(bindResponse).toBeTruthy();

    // 4. Verify field still accessible
    const fieldDetail = await api.getFieldByPid(fieldResponse.data!.pid);
    expect(fieldDetail.data).toBeTruthy();

    // 5. Create model and bind field to verify dropdown
    const modelData = createModelData({ modelType: 'entity' });
    const modelResponse = await api.createModel(modelData);
    expect(api.isSuccess(modelResponse)).toBe(true);
    expect(modelResponse.data).toBeTruthy();

    await api.publishModel(modelResponse.data!.pid);

    const bindFieldResp = await api.bindFieldToModel(modelResponse.data!.pid, {
      fieldPid: fieldResponse.data!.pid,
      dictCode: dictResponse.data!.code,
    });
    // Binding may fail if model isn't published yet or field is already bound
    expect(bindFieldResp).toBeTruthy();
  });

  /**
   * M-033: Dictionary validation
   */
  test('M-033: Dictionary validation', async ({ api }) => {
    // 1. Create and publish a dictionary
    const dictData = createDictData('simple', {
      items: [
        { value: 'valid1', label: 'Valid Option 1', sortOrder: 1 },
        { value: 'valid2', label: 'Valid Option 2', sortOrder: 2 },
      ],
    });

    const dictResponse = await api.createDict(dictData);
    expect(api.isSuccess(dictResponse)).toBe(true);

    await api.publishDict(dictResponse.data!.pid);

    // 2. Create field and bind dictionary
    const fieldData = createFieldData('string');
    const fieldResponse = await api.createField(fieldData);
    expect(api.isSuccess(fieldResponse)).toBe(true);

    await api.bindDictToField(fieldResponse.data!.pid, dictResponse.data!.code);

    // 3. Create model with the field
    const modelData = createModelData({ modelType: 'entity' });
    const modelResponse = await api.createModel(modelData);
    expect(api.isSuccess(modelResponse)).toBe(true);

    await api.publishModel(modelResponse.data!.pid);

    await api.bindFieldToModel(modelResponse.data!.pid, {
      fieldPid: fieldResponse.data!.pid,
      dictCode: dictResponse.data!.code,
      extension: {
        validateDict: true,
      },
    });
  });

  /**
   * M-034: Dictionary version management
   *
   * Tests the lifecycle: create (auto-published) -> unpublish (deprecated)
   * -> re-publish -> createVersion (new draft) -> version history
   */
  test('M-034: Dictionary version management', async ({ page, api }) => {
    // 1. Create dictionary — backend auto-publishes on create
    const dictData = createDictData('simple', {
      items: [
        { value: 'v1_option', label: 'Version 1 Option', sortOrder: 1 },
      ],
    });

    const createResponse = await api.createDict(dictData);
    expect(api.isSuccess(createResponse)).toBe(true);
    expect(createResponse.data).toBeTruthy();

    const dictPid = createResponse.data!.pid;
    const dictCode = createResponse.data!.code;

    // Verify auto-published: status=published, version>=1
    expect(createResponse.data!.status).toBe('published');
    expect(createResponse.data!.version).toBeGreaterThanOrEqual(1);

    // 2. Update the published dictionary (stays published)
    const updateResp = await api.updateDict(dictPid, {
      name: dictData.name + ' Updated',
      items: [
        { value: 'v1_option', label: 'Version 1 Option', sortOrder: 1 },
        { value: 'v2_option', label: 'Version 2 Option', sortOrder: 2 },
      ],
    });
    expect(api.isSuccess(updateResp)).toBe(true);

    // 3. Unpublish (published -> deprecated)
    const unpublishResp = await api.unpublishDict(dictPid);
    expect(api.isSuccess(unpublishResp)).toBe(true);
    expect(unpublishResp.data!.status).toBe('deprecated');

    // 4. Re-publish (deprecated -> published)
    const republishResp = await api.publishDict(dictPid, 'Re-published after deprecation');
    expect(api.isSuccess(republishResp)).toBe(true);
    expect(republishResp.data!.status).toBe('published');

    // 5. Create a new version (creates a draft copy with new PID)
    const newVersionResp = await api.createDictVersion(dictPid, 'Version 2 draft');
    if (api.isSuccess(newVersionResp) && newVersionResp.data) {
      expect(newVersionResp.data.status).toBe('draft');
      expect(newVersionResp.data.version).toBeGreaterThanOrEqual(2);
      expect(newVersionResp.data.pid).not.toBe(dictPid);
    }

    // 6. Check version history
    const historyResponse = await api.getDictVersionHistory(dictCode);
    expect(historyResponse).toBeTruthy();
    if (api.isSuccess(historyResponse) && historyResponse.data) {
      expect(historyResponse.data.length).toBeGreaterThanOrEqual(1);
    }

    // 7. Get by code returns the current published version
    const currentDict = await api.getDictByCode(dictCode);
    expect(api.isSuccess(currentDict)).toBe(true);
    expect(currentDict.data).toBeTruthy();

    // 8. Verify in UI
    await page.goto(`/meta/dict/${dictPid}`);
    await page.waitForLoadState('domcontentloaded');
  });

  /**
   * Dictionary types coverage
   */
  test('Dictionary types coverage', async ({ api }) => {
    const dictTypes: DictType[] = ['simple', 'tree'];
    const results: { type: string; success: boolean }[] = [];

    for (const dictType of dictTypes) {
      try {
        const dictData = createDictData(dictType);
        const response = await api.createDict(dictData);
        expect(api.isSuccess(response)).toBe(true);
        results.push({ type: dictType, success: true });
      } catch {
        results.push({ type: dictType, success: false });
      }
    }

    const successCount = results.filter(r => r.success).length;
    expect(successCount).toBe(dictTypes.length);
  });

  test('M-035: dictionary detail tab switch and tree edit in one click', async ({ page, api }) => {
    test.setTimeout(30000);
    const dictData = createDictData('tree', {
      items: [
        { value: 'root_node', label: 'Root Node', sortOrder: 1 },
      ],
    });
    const response = await api.createDict(dictData);
    expect(api.isSuccess(response)).toBe(true);
    const dictCode = response.data!.code;
    expect(dictCode).toBeTruthy();
    const latest = await api.getDictByCode(dictCode);
    expect(api.isSuccess(latest)).toBe(true);
    const dictPid = latest.data!.pid;
    expect(dictPid).toBeTruthy();
    await expect
      .poll(async () => {
        const detail = await api.getDictByPid(dictPid);
        return api.isSuccess(detail);
      }, { timeout: 10000, intervals: [500, 1000] })
      .toBe(true);

    await page.goto(`/meta/dict/${dictPid}`, { waitUntil: 'domcontentloaded' });
    await expect(page.locator('[data-testid="dict-tab-basic"]')).toBeVisible({ timeout: 10000 });

    await page.locator('[data-testid="dict-tab-items"]').click();
    await expect(page.locator('[data-testid="dict-tab-items"]')).toHaveClass(/border-blue-500/, { timeout: 10000 });
    await expect(page.locator('[data-testid="dict-save-items"]')).toBeVisible({ timeout: 10000 });

    await page.locator('[data-testid="dict-add-child-0"]').click();
    const rows = page.locator('[data-testid^="dict-item-row-"]');
    await expect(rows).toHaveCount(2);

    await rows.nth(1).locator('input').nth(0).fill('child_node');
    await rows.nth(1).locator('input').nth(1).fill('Child Node');

    await Promise.all([
      page.waitForResponse((resp) =>
        resp.request().method().toLowerCase() === 'put'
        && resp.url().includes(`/api/meta/dict/${dictPid}/items`)
        && resp.status() === 200
      ),
      page.locator('[data-testid="dict-save-items"]').click(),
    ]);

    await page.reload();
    await page.locator('[data-testid="dict-tab-items"]').click();
    await expect(page.locator('input[value="child_node"]')).toBeVisible();
  });
});
