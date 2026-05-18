/**
 * Doc Knowledge — Lifecycle E2E Tests
 *
 * DK-001 @smoke    : Navigate to 文档管理 list → table visible, i18n headers
 * DK-002 @smoke    : Navigate to 知识文章 list → table visible
 * DK-003 @critical : Document draft → published → archived lifecycle
 * DK-004 @critical : Document published → revise → draft branch
 * DK-005 @critical : Knowledge Article draft → published → archived lifecycle
 *
 * Menu root: 文档与知识库 (dk_root)
 *   /doc-knowledge/documents  → model: dk_document
 *   /doc-knowledge/articles   → model: dk_knowledge_article
 *
 * Prerequisites: doc-knowledge plugin imported and all models published.
 *
 * @since 10.0.0
 */

import { test, expect, type Page } from '../../fixtures';
import { uniqueId, executeCommandViaApi } from '../helpers/index';

// ---------------------------------------------------------------------------
// Navigation helper
// ---------------------------------------------------------------------------

async function navigateToDkPage(page: Page, leafName: string, modelCode: string): Promise<void> {
  await page.goto('/dashboards');
  await page.waitForLoadState('domcontentloaded');

  const nav = page.locator('nav');

  // Expand 文档与知识库 root menu
  const rootBtn = nav.getByRole('button', { name: '文档与知识库' });
  await rootBtn.scrollIntoViewIfNeeded();
  await rootBtn.evaluate((el: HTMLElement) => el.click());
  await page.waitForResponse(() => true, { timeout: 2_000 }).catch(() => null);

  // Set up waitForResponse BEFORE click
  const leafLink = nav.getByRole('link', { name: leafName });
  await leafLink.scrollIntoViewIfNeeded();
  const listResponsePromise = page
    .waitForResponse((r) => r.url().includes(`/api/dynamic/${modelCode}`) && r.status() === 200, {
      timeout: 15_000,
    })
    .catch(() => null);
  await leafLink.evaluate((el: HTMLElement) => el.click());
  await listResponsePromise;

  await expect(page.locator('table, [class*="ant-table"]').first()).toBeVisible({
    timeout: 10_000,
  });
}

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const UID = uniqueId('DK');

// ---------------------------------------------------------------------------
// Test suite
// ---------------------------------------------------------------------------

test.describe('Doc Knowledge — Lifecycle', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(60_000);

  let documentId: string;
  let reviseDocumentId: string;
  let articleId: string;

  // -------------------------------------------------------------------------
  // Setup: create test records via API
  // -------------------------------------------------------------------------

  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({
      storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json',
    });
    const page = await ctx.newPage();

    try {
      // Create document for lifecycle test (draft→published→archived)
      const docResult = await executeCommandViaApi(
        page,
        'dk:create_document',
        {
          dk_doc_title: `E2E Document ${UID}`,
          dk_doc_type: 'specification',
          dk_doc_version: '1.0',
          dk_doc_abstract: `E2E test document abstract ${UID}`,
          dk_doc_content: `E2E test document content ${UID}`,
          dk_doc_access_level: 'internal',
        },
        undefined,
        'create',
      );
      documentId = docResult.recordId;

      // Create document for revise branch test (draft→published→revise→draft)
      const doc2Result = await executeCommandViaApi(
        page,
        'dk:create_document',
        {
          dk_doc_title: `E2E DocRevise ${UID}`,
          dk_doc_type: 'guide',
          dk_doc_version: '1.0',
          dk_doc_abstract: `E2E revise test ${UID}`,
          dk_doc_content: `E2E revise content ${UID}`,
          dk_doc_access_level: 'internal',
        },
        undefined,
        'create',
      );
      reviseDocumentId = doc2Result.recordId;

      // Create knowledge article
      const articleResult = await executeCommandViaApi(
        page,
        'dk:create_article',
        {
          dk_ka_title: `E2E Article ${UID}`,
          dk_ka_content: `E2E knowledge article content ${UID}`,
        },
        undefined,
        'create',
      );
      articleId = articleResult.recordId;
    } finally {
      await ctx.close();
    }
  });

  // =========================================================================
  // DK-001 @smoke: Navigate to 文档管理
  // =========================================================================

  test('DK-001 @smoke: Navigate to 文档管理 list via sidebar menu', async ({ page }) => {
    await navigateToDkPage(page, '文档管理', 'dk_document');

    const rows = page.locator('tbody tr');
    await expect(rows.first()).toBeVisible({ timeout: 8_000 });

    // i18n: headers must not contain raw field codes
    const headerRow = page.locator('thead tr').first();
    await expect(headerRow).toBeVisible({ timeout: 5_000 });
    const headerText = await headerRow.textContent();
    expect(headerText).not.toMatch(/dk_doc_/i);
  });

  // =========================================================================
  // DK-002 @smoke: Navigate to 知识文章
  // =========================================================================

  test('DK-002 @smoke: Navigate to 知识文章 list via sidebar menu', async ({ page }) => {
    await navigateToDkPage(page, '知识文章', 'dk_knowledge_article');

    const table = page.locator('table, [class*="ant-table"]').first();
    await expect(table).toBeVisible({ timeout: 10_000 });

    const rows = page.locator('tbody tr');
    await expect(rows.first()).toBeVisible({ timeout: 8_000 });
  });

  // =========================================================================
  // DK-003 @critical: Document draft → published → archived
  // =========================================================================

  test('DK-003 @critical: Document draft → published → archived', async ({ page }) => {
    expect(documentId).toBeTruthy();

    // Verify starts as draft
    let resp = await page.request.get(`/api/dynamic/dk_document/${documentId}`);
    expect(resp.ok()).toBe(true);
    const draftBody = await resp.json();
    expect((draftBody?.data ?? draftBody).dk_doc_status).toBe('draft');

    // draft → published
    await executeCommandViaApi(page, 'dk:publish_document', {}, documentId, 'state_transition');

    resp = await page.request.get(`/api/dynamic/dk_document/${documentId}`);
    const publishedBody = await resp.json();
    expect((publishedBody?.data ?? publishedBody).dk_doc_status).toBe('published');

    // published → archived
    await executeCommandViaApi(page, 'dk:archive_document', {}, documentId, 'state_transition');

    resp = await page.request.get(`/api/dynamic/dk_document/${documentId}`);
    const archivedBody = await resp.json();
    expect((archivedBody?.data ?? archivedBody).dk_doc_status).toBe('archived');

    // Verify in list UI
    await navigateToDkPage(page, '文档管理', 'dk_document');
    const rows = page.locator('tbody tr');
    await expect(rows.first()).toBeVisible({ timeout: 8_000 });
  });

  // =========================================================================
  // DK-004 @critical: Document published → revise → draft
  // =========================================================================

  test('DK-004 @critical: Document published → revise → draft', async ({ page }) => {
    expect(reviseDocumentId).toBeTruthy();

    // Publish first
    await executeCommandViaApi(
      page,
      'dk:publish_document',
      {},
      reviseDocumentId,
      'state_transition',
    );

    let resp = await page.request.get(`/api/dynamic/dk_document/${reviseDocumentId}`);
    expect(resp.ok()).toBe(true);
    const publishedBody = await resp.json();
    expect((publishedBody?.data ?? publishedBody).dk_doc_status).toBe('published');

    // published → revise → draft
    await executeCommandViaApi(
      page,
      'dk:revise_document',
      {},
      reviseDocumentId,
      'state_transition',
    );

    resp = await page.request.get(`/api/dynamic/dk_document/${reviseDocumentId}`);
    const revisedBody = await resp.json();
    expect((revisedBody?.data ?? revisedBody).dk_doc_status).toBe('draft');
  });

  // =========================================================================
  // DK-005 @critical: Knowledge Article draft → published → archived
  // =========================================================================

  test('DK-005 @critical: Knowledge Article draft → published → archived', async ({ page }) => {
    expect(articleId).toBeTruthy();

    // Verify starts as draft
    let resp = await page.request.get(`/api/dynamic/dk_knowledge_article/${articleId}`);
    expect(resp.ok()).toBe(true);
    const draftBody = await resp.json();
    expect((draftBody?.data ?? draftBody).dk_ka_status).toBe('draft');

    // draft → published
    await executeCommandViaApi(page, 'dk:publish_article', {}, articleId, 'state_transition');

    resp = await page.request.get(`/api/dynamic/dk_knowledge_article/${articleId}`);
    const publishedBody = await resp.json();
    expect((publishedBody?.data ?? publishedBody).dk_ka_status).toBe('published');

    // published → archived
    await executeCommandViaApi(page, 'dk:archive_article', {}, articleId, 'state_transition');

    resp = await page.request.get(`/api/dynamic/dk_knowledge_article/${articleId}`);
    const archivedBody = await resp.json();
    expect((archivedBody?.data ?? archivedBody).dk_ka_status).toBe('archived');

    // Verify in list UI
    await navigateToDkPage(page, '知识文章', 'dk_knowledge_article');
    const rows = page.locator('tbody tr');
    await expect(rows.first()).toBeVisible({ timeout: 8_000 });
  });
});
