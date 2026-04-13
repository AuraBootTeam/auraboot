/**
 * Doc-Knowledge Plugin (dk) — E2E Tests
 *
 * Tests the full lifecycle for the doc-knowledge plugin models:
 *   1. Document Category CRUD
 *   2. Document CRUD & Lifecycle (draft -> published -> archived, revise flow)
 *   3. Knowledge Article Lifecycle
 *   4. Document Version management
 *   5. Project-Document linking
 *
 * Prerequisites: doc-knowledge plugin must be imported and models published.
 *
 * @since 9.0.0
 */
import { test, expect } from '@playwright/test';
import {
  navigateToDynamicPage,
  uniqueId,
  executeCommandViaApi,
  waitForDynamicPageLoad,
  findRowInPaginatedList,
  acceptConfirmDialog,
  waitForFormReady,
  todayStr,
  clickRowActionByLocator,
} from '../helpers/index';
import { ErrorCodes } from '~/services/http-client/types';

// ---------------------------------------------------------------------------
// Page keys (hyphenated for URL / API compatibility)
// ---------------------------------------------------------------------------
const PAGE = {
  DOCUMENT: 'dk-document',
  CATEGORY: 'dk-doc-category',
  VERSION: 'dk-doc-version',
  ARTICLE: 'dk-knowledge-article',
  PROJECT_DOC: 'dk-project-document',
} as const;

// ---------------------------------------------------------------------------
// 1. Document Category CRUD
// ---------------------------------------------------------------------------
test.describe('DK Category — CRUD', () => {
  test.describe.configure({ mode: 'serial' });

  let catPid: string;
  const catName = `E2E Category ${uniqueId()}`;
  const catCode = `E2E-CAT-${uniqueId()}`;
  const updatedCatName = `${catName} Updated`;

  test('CAT-001: Create category via API, verify in list', async ({ page }) => {
    const result = await executeCommandViaApi(page, 'dk:create_category', {
      dk_cat_name: catName,
      dk_cat_code: catCode,
      dk_cat_description: 'Test category for E2E',
      dk_cat_sort_order: 1,
    });
    expect(result.code).toBe(ErrorCodes.SUCCESS);
    expect(result.recordId).toBeTruthy();
    catPid = result.recordId;

    // Navigate to category list and verify
    await navigateToDynamicPage(page, PAGE.CATEGORY);
    const table = page.locator('table, [role="table"]');
    await expect(table.first()).toBeVisible({ timeout: 10000 });

    const row = await findRowInPaginatedList(page, catName, 12000);
    await expect(row).toBeVisible({ timeout: 5000 });

    const resp = await page.request.get(`/api/dynamic/${PAGE.CATEGORY}/${catPid}`);
    expect(resp.ok()).toBe(true);
    const body = await resp.json().catch(() => ({}));
    const data = body.data ?? body;
    expect((data as any).dk_cat_name).toBe(catName);
    expect((data as any).dk_cat_code).toBe(catCode);
  });

  test('CAT-002: Update category name via UI', async ({ page }) => {
    if (!catPid) {
      const seed = await executeCommandViaApi(page, 'dk:create_category', {
        dk_cat_name: catName,
        dk_cat_code: catCode,
        dk_cat_description: 'Seed category for CAT-002',
        dk_cat_sort_order: 1,
      });
      expect(seed.code).toBe(ErrorCodes.SUCCESS);
      catPid = seed.recordId;
    }
    await page.goto(`/p/dk_doc_category/${catPid}/edit`, { waitUntil: 'domcontentloaded' });

    // Wait for form to load with existing data
    await waitForFormReady(page);
    await page.waitForFunction(
      () => {
        const inputs = document.querySelectorAll('form input[type="text"], form input:not([type])');
        return Array.from(inputs).some((el) => (el as HTMLInputElement).value.length > 0);
      },
      { timeout: 12000 },
    );

    // Update the name field
    const nameInput = page.locator('[data-testid="form-field-dk_cat_name"] input').first();
    if (await nameInput.isVisible({ timeout: 3000 }).catch(() => false)) {
      await nameInput.clear();
      await nameInput.fill(updatedCatName);
    } else {
      const fallbackInput = page
        .locator('[name="dk_cat_name"], [data-field="dk_cat_name"] input')
        .first();
      await fallbackInput.clear();
      await fallbackInput.fill(updatedCatName);
    }

    // Submit the form
    const submitBtn = page
      .locator(
        '[data-testid="form-btn-submit"], button:has-text("Submit"), button:has-text("Save"), button:has-text("提交"), button:has-text("保存")',
      )
      .first();
    await expect(submitBtn).toBeVisible({ timeout: 5000 });

    const saveResponse = page.waitForResponse(
      (r) => r.url().includes('/execute/') && r.status() === 200,
      { timeout: 10000 },
    );
    await submitBtn.click();
    await saveResponse;

    // Verify update by recordId.
    await expect
      .poll(
        async () => {
          const resp = await page.request.get(`/api/dynamic/${PAGE.CATEGORY}/${catPid}`);
          if (!resp.ok()) return 'missing';
          const body = await resp.json().catch(() => ({}));
          const data = body.data ?? body;
          return String((data as any)?.dk_cat_name ?? '');
        },
        { timeout: 10000, intervals: [400, 800, 1200] },
      )
      .toBe(updatedCatName);
  });

  test('CAT-003: Delete category via UI', async ({ page }) => {
    await navigateToDynamicPage(page, PAGE.CATEGORY);

    let row = await findRowInPaginatedList(page, updatedCatName, 6000);
    if (!(await row.isVisible({ timeout: 1500 }).catch(() => false))) {
      row = await findRowInPaginatedList(page, catName, 6000);
    }
    await expect(row).toBeVisible({ timeout: 5000 });

    const deleteResponse = page.waitForResponse(
      (r) => r.url().includes('/execute/') && r.status() === 200,
      { timeout: 10000 },
    );
    await clickRowActionByLocator(page, row, 'delete');
    await acceptConfirmDialog(page);
    await deleteResponse;

    // Verify deletion via API
    const resp = await page.request.get(`/api/dynamic/${PAGE.CATEGORY}/${catPid}`);
    expect(resp.ok()).toBe(false);
  });
});

// ---------------------------------------------------------------------------
// 2. Document CRUD & Lifecycle
// ---------------------------------------------------------------------------
test.describe('DK Document — CRUD & Lifecycle', () => {
  test.describe.configure({ mode: 'serial' });

  let docPid: string;
  const docTitle = `E2E Document ${uniqueId()}`;
  const updatedTitle = `${docTitle} Updated`;
  const updatedAbstract = 'Updated abstract content for E2E test';

  test('DOC-001: Create document via API, verify in list', async ({ page }) => {
    const result = await executeCommandViaApi(page, 'dk:create_document', {
      dk_doc_title: docTitle,
      dk_doc_type: 'report',
      dk_doc_version: 'v1.0',
      dk_doc_abstract: 'Test document abstract',
      dk_doc_content: 'Test document content for E2E testing',
      dk_doc_tags: 'test,e2e',
    });
    expect(result.code).toBe(ErrorCodes.SUCCESS);
    expect(result.recordId).toBeTruthy();
    docPid = result.recordId;

    // Navigate to document list and verify
    await navigateToDynamicPage(page, PAGE.DOCUMENT);
    const table = page.locator('table, [role="table"]');
    await expect(table.first()).toBeVisible({ timeout: 10000 });

    const row = await findRowInPaginatedList(page, docTitle, 12000);
    await expect(row).toBeVisible({ timeout: 5000 });

    const resp = await page.request.get(`/api/dynamic/${PAGE.DOCUMENT}/${docPid}`);
    expect(resp.ok()).toBe(true);
    const body = await resp.json().catch(() => ({}));
    const data = body.data ?? body;
    expect((data as any).dk_doc_title).toBe(docTitle);
    expect((data as any).dk_doc_status).toBe('draft');
  });

  test('DOC-002: View document detail page', async ({ page }) => {
    if (!docPid) {
      const seed = await executeCommandViaApi(page, 'dk:create_document', {
        dk_doc_title: docTitle,
        dk_doc_type: 'report',
        dk_doc_version: 'v1.0',
        dk_doc_abstract: 'Test document abstract',
        dk_doc_content: 'Test document content for E2E testing',
        dk_doc_tags: 'test,e2e',
      });
      expect(seed.code).toBe(ErrorCodes.SUCCESS);
      docPid = seed.recordId;
    }

    await navigateToDynamicPage(page, PAGE.DOCUMENT);

    const row = await findRowInPaginatedList(page, docTitle, 6000);
    if (await row.isVisible({ timeout: 1500 }).catch(() => false)) {
      // Click the view action button
      const viewBtn = row
        .locator('[data-testid="row-action-detail"], [data-testid="row-action-view"]')
        .first();
      const hasViewBtn = await viewBtn.isVisible({ timeout: 3000 }).catch(() => false);
      if (hasViewBtn) {
        await viewBtn.click();
      } else {
        await row.click();
        await page
          .waitForURL(/\/p\/dk_document\/view\//, { timeout: 5000 })
          .catch(async () => {
            await page.goto(`/p/dk_document/view/${docPid}`, {
              waitUntil: 'domcontentloaded',
            });
          });
      }
    } else {
      await page.goto(`/p/dk_document/view/${docPid}`, {
        waitUntil: 'domcontentloaded',
      });
    }

    // Wait for detail page to load
    await waitForDynamicPageLoad(page);

    // Verify detail page shows document data
    await expect(page.locator('body')).toContainText(docTitle, { timeout: 10000 });
    await expect(page.locator('body')).toContainText('v1.0');
  });

  test('DOC-003: Edit document (update title & abstract)', async ({ page }) => {
    if (!docPid) {
      const seed = await executeCommandViaApi(page, 'dk:create_document', {
        dk_doc_title: docTitle,
        dk_doc_type: 'report',
        dk_doc_version: 'v1.0',
        dk_doc_abstract: 'Seed document abstract',
        dk_doc_content: 'Seed document content',
        dk_doc_tags: 'seed,e2e',
      });
      expect(seed.code).toBe(ErrorCodes.SUCCESS);
      docPid = seed.recordId;
    }
    await page.goto(`/p/dk_document/${docPid}/edit`, { waitUntil: 'domcontentloaded' });

    // Wait for form to load with existing data
    await waitForFormReady(page);
    await page.waitForFunction(
      () => {
        const inputs = document.querySelectorAll('form input[type="text"], form input:not([type])');
        return Array.from(inputs).some((el) => (el as HTMLInputElement).value.length > 0);
      },
      { timeout: 10000 },
    );

    // Update the title
    const titleInput = page.locator('[data-testid="form-field-dk_doc_title"] input').first();
    if (await titleInput.isVisible({ timeout: 3000 }).catch(() => false)) {
      await titleInput.clear();
      await titleInput.fill(updatedTitle);
    } else {
      const fallbackInput = page
        .locator('[name="dk_doc_title"], [data-field="dk_doc_title"] input')
        .first();
      await fallbackInput.clear();
      await fallbackInput.fill(updatedTitle);
    }

    // Update the abstract
    const abstractInput = page
      .locator('[data-testid="form-field-dk_doc_abstract"] textarea')
      .first();
    if (await abstractInput.isVisible({ timeout: 3000 }).catch(() => false)) {
      await abstractInput.clear();
      await abstractInput.fill(updatedAbstract);
    } else {
      const fallbackTextarea = page
        .locator('[name="dk_doc_abstract"], [data-field="dk_doc_abstract"] textarea')
        .first();
      if (await fallbackTextarea.isVisible({ timeout: 2000 }).catch(() => false)) {
        await fallbackTextarea.clear();
        await fallbackTextarea.fill(updatedAbstract);
      }
    }

    // Submit
    const submitBtn = page
      .locator(
        '[data-testid="form-btn-submit"], button:has-text("Submit"), button:has-text("Save"), button:has-text("提交"), button:has-text("保存")',
      )
      .first();
    await expect(submitBtn).toBeVisible({ timeout: 5000 });

    const saveResponse = page.waitForResponse(
      (r) => r.url().includes('/execute/') && r.status() === 200,
      { timeout: 10000 },
    );
    await submitBtn.click();
    await saveResponse;

    // Verify via API
    const resp = await page.request.get(`/api/dynamic/${PAGE.DOCUMENT}/${docPid}`);
    expect(resp.ok()).toBe(true);
    const body = await resp.json().catch(() => ({}));
    const data = body.data ?? body;
    expect((data as any).dk_doc_title).toBe(updatedTitle);
    expect((data as any).dk_doc_abstract).toBe(updatedAbstract);
  });

  test('DOC-004: Publish document (draft -> published)', async ({ page }) => {
    expect(docPid).toBeTruthy();

    const result = await executeCommandViaApi(
      page,
      'dk:publish_document',
      {},
      docPid,
      'state_transition',
    );
    expect(result.code).toBe(ErrorCodes.SUCCESS);

    // Verify status by recordId to avoid list query lag.
    await expect
      .poll(
        async () => {
          const resp = await page.request.get(`/api/dynamic/${PAGE.DOCUMENT}/${docPid}`);
          if (!resp.ok()) return '';
          const body = await resp.json().catch(() => ({}));
          const data = body.data ?? body;
          return String((data as any)?.dk_doc_status ?? '');
        },
        { timeout: 10000, intervals: [400, 800, 1200] },
      )
      .toBe('published');
  });

  test('DOC-005: Revise document (published -> draft)', async ({ page }) => {
    expect(docPid).toBeTruthy();

    const result = await executeCommandViaApi(
      page,
      'dk:revise_document',
      {},
      docPid,
      'state_transition',
    );
    expect(result.code).toBe(ErrorCodes.SUCCESS);

    // Verify status returned to draft
    const resp = await page.request.get(`/api/dynamic/${PAGE.DOCUMENT}/${docPid}`);
    expect(resp.ok()).toBe(true);
    const body = await resp.json().catch(() => ({}));
    const data = body.data ?? body;
    expect((data as any).dk_doc_status).toBe('draft');
  });

  test('DOC-006: Archive document (publish again, then published -> archived)', async ({
    page,
  }) => {
    expect(docPid).toBeTruthy();

    // First re-publish (draft -> published)
    const pubResult = await executeCommandViaApi(
      page,
      'dk:publish_document',
      {},
      docPid,
      'state_transition',
    );
    expect(pubResult.code).toBe(ErrorCodes.SUCCESS);

    // Then archive (published -> archived)
    const archResult = await executeCommandViaApi(
      page,
      'dk:archive_document',
      {},
      docPid,
      'state_transition',
    );
    expect(archResult.code).toBe(ErrorCodes.SUCCESS);

    // Verify status by recordId to avoid list query lag.
    await expect
      .poll(
        async () => {
          const resp = await page.request.get(`/api/dynamic/${PAGE.DOCUMENT}/${docPid}`);
          if (!resp.ok()) return '';
          const body = await resp.json().catch(() => ({}));
          const data = body.data ?? body;
          return String((data as any)?.dk_doc_status ?? '');
        },
        { timeout: 10000, intervals: [400, 800, 1200] },
      )
      .toBe('archived');
  });
});

// ---------------------------------------------------------------------------
// 3. Knowledge Article Lifecycle
// ---------------------------------------------------------------------------
test.describe('DK Knowledge Article — Lifecycle', () => {
  test.describe.configure({ mode: 'serial' });

  let articlePid: string;
  const articleTitle = `E2E Article ${uniqueId()}`;

  test('KA-001: Create article via API', async ({ page }) => {
    const result = await executeCommandViaApi(page, 'dk:create_article', {
      dk_ka_title: articleTitle,
      dk_ka_content: 'Knowledge article content for E2E testing',
      dk_ka_tags: 'test,knowledge',
    });
    expect(result.code).toBe(ErrorCodes.SUCCESS);
    expect(result.recordId).toBeTruthy();
    articlePid = result.recordId;

    // UI interaction: verify article list page renders after creation.
    await navigateToDynamicPage(page, PAGE.ARTICLE);
    await expect(
      page.locator('table, [role="table"], [data-testid="dynamic-list"]').first(),
    ).toBeVisible({ timeout: 10000 });
  });

  test('KA-002: Verify article appears in list', async ({ page }) => {
    await navigateToDynamicPage(page, PAGE.ARTICLE);

    const table = page.locator('table, [role="table"]');
    await expect(table.first()).toBeVisible({ timeout: 10000 });

    const articleRow = page.locator('tbody tr', { hasText: articleTitle }).first();
    await expect(articleRow).toBeVisible({ timeout: 10000 });
    await expect(articleRow).toContainText('draft');
  });

  test('KA-003: Publish article (draft -> published)', async ({ page }) => {
    expect(articlePid).toBeTruthy();

    const result = await executeCommandViaApi(
      page,
      'dk:publish_article',
      {},
      articlePid,
      'state_transition',
    );
    expect(result.code).toBe(ErrorCodes.SUCCESS);

    // Verify status change
    await navigateToDynamicPage(page, PAGE.ARTICLE);
    const articleRow = page.locator('tbody tr', { hasText: articleTitle }).first();
    await expect(articleRow).toBeVisible({ timeout: 10000 });
    await expect(articleRow).toContainText('published');
  });

  test('KA-004: Archive article (published -> archived)', async ({ page }) => {
    expect(articlePid).toBeTruthy();

    const result = await executeCommandViaApi(
      page,
      'dk:archive_article',
      {},
      articlePid,
      'state_transition',
    );
    expect(result.code).toBe(ErrorCodes.SUCCESS);

    // Verify status change
    await navigateToDynamicPage(page, PAGE.ARTICLE);
    const articleRow = page.locator('tbody tr', { hasText: articleTitle }).first();
    await expect(articleRow).toBeVisible({ timeout: 10000 });
    await expect(articleRow).toContainText('archived');
  });
});

// ---------------------------------------------------------------------------
// 4. Document Version
// ---------------------------------------------------------------------------
test.describe('DK Document Version', () => {
  test.describe.configure({ mode: 'serial' });

  let docPid: string;
  let versionPid: string;
  const docTitle = `E2E VerDoc ${uniqueId()}`;
  const versionNumber = 'v1.1';

  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({
      storageState: 'tests/storage/admin.json',
      baseURL: 'http://localhost:5173',
    });
    const setupPage = await ctx.newPage();

    // Create a document to associate versions with
    const result = await executeCommandViaApi(setupPage, 'dk:create_document', {
      dk_doc_title: docTitle,
      dk_doc_type: 'report',
      dk_doc_version: 'v1.0',
      dk_doc_abstract: 'Document for version testing',
      dk_doc_content: 'Version test content',
    });
    expect(result.code).toBe(ErrorCodes.SUCCESS);
    docPid = result.recordId;

    await setupPage.close();
    await ctx.close();
  });

  test('VER-001: Create version entry linked to a document', async ({ page }) => {
    expect(docPid).toBeTruthy();

    const result = await executeCommandViaApi(page, 'dk:create_version', {
      dk_ver_document_id: docPid,
      dk_ver_number: versionNumber,
      dk_ver_change_summary: 'Updated section 3 with new guidelines',
      dk_ver_content_snapshot: 'Updated content snapshot for v1.1',
    });
    expect(result.code).toBe(ErrorCodes.SUCCESS);
    expect(result.recordId).toBeTruthy();
    versionPid = result.recordId;

    // UI interaction: version list page should render and include version number.
    await navigateToDynamicPage(page, PAGE.VERSION);
    const row = await findRowInPaginatedList(page, versionNumber, 12000);
    await expect(row).toBeVisible({ timeout: 5000 });
  });

  test('VER-002: Verify version in list', async ({ page }) => {
    await navigateToDynamicPage(page, PAGE.VERSION);

    const table = page.locator('table, [role="table"]');
    await expect(table.first()).toBeVisible({ timeout: 10000 });

    const row = await findRowInPaginatedList(page, versionNumber, 12000);
    await expect(row).toBeVisible({ timeout: 5000 });

    const resp = await page.request.get(`/api/dynamic/${PAGE.VERSION}/${versionPid}`);
    expect(resp.ok()).toBe(true);
    const body = await resp.json().catch(() => ({}));
    const data = body.data ?? body;
    expect((data as any).dk_ver_number).toBe(versionNumber);
  });

  test.afterAll(async ({ browser }) => {
    const ctx = await browser.newContext({
      storageState: 'tests/storage/admin.json',
      baseURL: 'http://localhost:5173',
    });
    const cleanupPage = await ctx.newPage();

    // Clean up version
    if (versionPid) {
      await executeCommandViaApi(cleanupPage, 'dk:delete_version', {}, versionPid, 'delete').catch(
        () => {},
      );
    }
    // Clean up document
    if (docPid) {
      await executeCommandViaApi(cleanupPage, 'dk:delete_document', {}, docPid, 'delete').catch(
        () => {},
      );
    }

    await cleanupPage.close();
    await ctx.close();
  });
});

// ---------------------------------------------------------------------------
// 5. Project-Document Link
// ---------------------------------------------------------------------------
test.describe('DK Project-Document Link', () => {
  test.describe.configure({ mode: 'serial' });

  let docPid: string;
  let projectPid: string;
  let linkPid: string;
  const docTitle = `E2E LinkDoc ${uniqueId()}`;
  const projectName = `DK Test Project ${uniqueId()}`;
  const linkRemark = 'Initial upload for E2E test';

  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({
      storageState: 'tests/storage/admin.json',
      baseURL: 'http://localhost:5173',
    });
    const setupPage = await ctx.newPage();

    // Create a project
    const projResult = await executeCommandViaApi(setupPage, 'pm:create_project', {
      pm_project_name: projectName,
      pm_project_code: `DK-${uniqueId()}`,
    });
    expect(projResult.code).toBe(ErrorCodes.SUCCESS);
    projectPid = projResult.recordId;

    // Create a document
    const docResult = await executeCommandViaApi(setupPage, 'dk:create_document', {
      dk_doc_title: docTitle,
      dk_doc_type: 'report',
      dk_doc_version: 'v1.0',
      dk_doc_abstract: 'Document for linking test',
      dk_doc_content: 'Link test content',
    });
    expect(docResult.code).toBe(ErrorCodes.SUCCESS);
    docPid = docResult.recordId;

    await setupPage.close();
    await ctx.close();
  });

  test('LINK-001: Link document to project', async ({ page }) => {
    expect(projectPid).toBeTruthy();
    expect(docPid).toBeTruthy();

    const result = await executeCommandViaApi(page, 'dk:link_document', {
      dk_pd_project_id: projectPid,
      dk_pd_document_id: docPid,
      dk_pd_upload_date: todayStr(),
      dk_pd_remark: linkRemark,
    });
    expect(result.code).toBe(ErrorCodes.SUCCESS);
    expect(result.recordId).toBeTruthy();
    linkPid = result.recordId;

    // UI interaction: project-document list page should render after linking.
    await navigateToDynamicPage(page, PAGE.PROJECT_DOC);
    await expect(
      page.locator('table, [role="table"], [data-testid="dynamic-list"]').first(),
    ).toBeVisible({ timeout: 10000 });
  });

  test('LINK-002: Verify link in list', async ({ page }) => {
    await navigateToDynamicPage(page, PAGE.PROJECT_DOC);

    const table = page.locator('table, [role="table"]');
    await expect(table.first()).toBeVisible({ timeout: 10000 });

    const linkRow = page.locator('tbody tr', { hasText: linkRemark }).first();
    await expect(linkRow).toBeVisible({ timeout: 10000 });
    // The list displays formatted reference codes (e.g., PRJ-xxx) rather than raw pids.
    // Just verify the row is present with the remark — the remark + doc title are sufficient
    // to confirm the link was created correctly.
    await expect(linkRow).toContainText(docTitle);
  });

  test.afterAll(async ({ browser }) => {
    const ctx = await browser.newContext({
      storageState: 'tests/storage/admin.json',
      baseURL: 'http://localhost:5173',
    });
    const cleanupPage = await ctx.newPage();

    // Clean up link
    if (linkPid) {
      await executeCommandViaApi(cleanupPage, 'dk:unlink_document', {}, linkPid, 'delete').catch(
        () => {},
      );
    }
    // Clean up document
    if (docPid) {
      await executeCommandViaApi(cleanupPage, 'dk:delete_document', {}, docPid, 'delete').catch(
        () => {},
      );
    }

    await cleanupPage.close();
    await ctx.close();
  });
});
