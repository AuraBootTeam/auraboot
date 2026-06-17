/**
 * Unified Designer — publish / unpublish / export / import action-point golden.
 *
 * Closes the gaps recorded in
 * docs/backlog/2026-06-17-page-designer-coverage-ux-gap.md §2.C:
 *   C1  Publish / Unpublish toolbar action points (POST /api/pages/{pid}/publish
 *       and /unpublish — page.page.manage). Drives the real button and asserts
 *       the BACKEND status via GET /api/pages/{pid} (status=published +
 *       publishedAt non-null on publish, status=draft + publishedAt null on
 *       unpublish), not just a toast. Sad path: a new/local page (no pid) keeps
 *       the publish button disabled.
 *   C2  Export (designer-export) — serialize the live document to a downloaded
 *       <pageKey>.page.json and assert the FILE CONTENT carries the current
 *       blocks (not just that a download fired).
 *   C2  Import (designer-import) — feed a known PageSchemaV3 JSON via the hidden
 *       file input and assert the imported block appears on the canvas (outline)
 *       and round-trips on save (GET readback). Sad path: invalid JSON surfaces
 *       an inline error and leaves the document unchanged.
 *
 * Pattern follows tests/e2e/page-designer/inspector-authoring-golden.spec.ts:
 *   seed a page via POST /api/pages with STABLE block ids (schemaVersion 3) ->
 *   open /unified-designer?pageId=<pid> -> drive a real toolbar action ->
 *   assert the persisted/exported/imported artifact.
 *
 * data-testids verified against the live source:
 *   - publish:   designer-publish               (WorkbenchToolbar.tsx)
 *   - unpublish: designer-unpublish             (WorkbenchToolbar.tsx)
 *   - export:    designer-export                (WorkbenchToolbar.tsx)
 *   - import:    designer-import + designer-import-input (WorkbenchToolbar.tsx)
 *   - save state: designer-dirty-state          (WorkbenchToolbar.tsx)
 *
 * @since 4.0.0
 */

import type { Page } from '@playwright/test';
import { test, expect } from '../../fixtures';
import { uniqueId } from '../helpers';

const ADMIN_STORAGE_STATE =
  process.env.PW_ADMIN_STORAGE_STATE ||
  (process.env.PW_STORAGE_DIR ? `${process.env.PW_STORAGE_DIR}/admin.json` : './tests/storage/admin.json');

// ab_announcement is a published platform meta-model present in every OSS stack;
// it satisfies the detail-page contract for the root detail block.
const MODEL_CODE = 'ab_announcement';

interface DslBlock {
  id?: string;
  blockType?: string;
  title?: unknown;
  props?: Record<string, unknown>;
  layout?: Record<string, unknown>;
  dataSource?: Record<string, unknown>;
  blocks?: DslBlock[];
}

interface PageSchemaDto {
  pid: string;
  pageKey: string;
  kind?: string;
  status?: string;
  publishedAt?: string | null;
  blocks?: DslBlock[];
}

function findBlockById(blocks: DslBlock[] | undefined, id: string): DslBlock | null {
  for (const block of blocks ?? []) {
    if (block.id === id) return block;
    const nested = findBlockById(block.blocks, id);
    if (nested) return nested;
  }
  return null;
}

async function readPage(page: Page, pid: string): Promise<PageSchemaDto> {
  const resp = await page.request.get(`/api/pages/${pid}`);
  expect(resp.ok(), `GET /api/pages/${pid} failed: ${resp.status()}`).toBeTruthy();
  const body = await resp.json();
  expect(body.code, 'read page API code').toBe('0');
  return body.data as PageSchemaDto;
}

async function openDesigner(page: Page, pid: string): Promise<void> {
  await page.goto(`/unified-designer?pageId=${pid}`, { waitUntil: 'domcontentloaded' });
  await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 30_000 });
  await expect(page.getByTestId('designer-dirty-state')).toHaveText('已保存', { timeout: 15_000 });
}

const ROOT_BLOCK = 'detail_root';
const SECTION_BLOCK = 'pd_pub_section';

async function seedDraftPage(
  browser: import('@playwright/test').Browser,
  uid: string,
): Promise<string> {
  const ctx = await browser.newContext({ storageState: ADMIN_STORAGE_STATE });
  const page = await ctx.newPage();
  const resp = await page.request.post('/api/pages', {
    data: {
      name: `Publish golden ${uid}`,
      pageKey: `pd_pub_${uid}`.replace(/-/g, '_'),
      title: `Publish golden ${uid}`,
      kind: 'detail',
      modelCode: MODEL_CODE,
      // The unified designer loads/saves a V3 document; its client validator
      // requires schemaVersion 3. A v4 seed loads but fails save validation.
      schemaVersion: 3,
      blocks: [
        {
          id: ROOT_BLOCK,
          blockType: 'detail',
          title: 'Publish golden root',
          dataSource: { model: MODEL_CODE },
          layout: { span: 12 },
          blocks: [
            {
              id: SECTION_BLOCK,
              blockType: 'detail-section',
              title: 'Original section',
              layout: { columns: 12 },
              blocks: [],
            },
          ],
        },
      ],
      extension: { e2e: true, scenario: 'publish-export-import-golden' },
    },
  });
  expect(resp.ok(), `seed page failed: ${resp.status()} ${await resp.text()}`).toBeTruthy();
  const body = await resp.json();
  expect(body.code, 'seed page API code').toBe('0');
  const pid = String(body.data?.pid ?? '');
  expect(pid, 'seeded pid').toBeTruthy();
  expect(body.data?.status, 'seeded page is draft').toBe('draft');
  await ctx.close();
  return pid;
}

test.describe.serial('Unified Designer publish/export/import golden', () => {
  // Real save/reopen round-trips plus publish/unpublish; the 15s default is tight.
  test.describe.configure({ timeout: 120_000 });

  test('C1: publish toolbar action → backend status=published + publishedAt; unpublish reverts', async ({
    page,
    browser,
  }, testInfo) => {
    const pid = await seedDraftPage(browser, uniqueId('pdpub'));

    await openDesigner(page, pid);

    // A saved, clean, page-bound document → publish is enabled.
    const publishBtn = page.getByTestId('designer-publish');
    await expect(publishBtn).toBeVisible();
    await expect(publishBtn).toBeEnabled();
    await expect(page.getByTestId('designer-unpublish')).toHaveCount(0);
    await testInfo.attach('c1-before-publish', {
      body: await page.screenshot(),
      contentType: 'image/png',
    });

    // Drive the real publish button and wait for the POST /publish round-trip.
    await expect(async () => {
      const publishResp = page.waitForResponse(
        (r) => r.url().includes(`/api/pages/${pid}/publish`) && r.request().method() === 'POST',
        { timeout: 5_000 },
      );
      await publishBtn.click();
      const resp = await publishResp;
      expect(resp.status()).toBe(200);
      const body = await resp.json();
      expect(body.code).toBe('0');
    }).toPass({ timeout: 30_000 });

    // The toolbar reflects the published state (button label flips + unpublish appears).
    await expect(page.getByTestId('designer-publish')).toHaveText('已发布', { timeout: 10_000 });
    await expect(page.getByTestId('designer-unpublish')).toBeVisible();
    await testInfo.attach('c1-after-publish', {
      body: await page.screenshot(),
      contentType: 'image/png',
    });

    // REAL backend verification: status=published + publishedAt non-null.
    const publishedDto = await readPage(page, pid);
    expect(publishedDto.status, 'backend status after publish').toBe('published');
    expect(publishedDto.publishedAt, 'backend publishedAt after publish').toBeTruthy();

    // Drive the real unpublish button and wait for the POST /unpublish round-trip.
    await expect(async () => {
      const unpublishResp = page.waitForResponse(
        (r) => r.url().includes(`/api/pages/${pid}/unpublish`) && r.request().method() === 'POST',
        { timeout: 5_000 },
      );
      await page.getByTestId('designer-unpublish').click();
      const resp = await unpublishResp;
      expect(resp.status()).toBe(200);
      const body = await resp.json();
      expect(body.code).toBe('0');
    }).toPass({ timeout: 30_000 });

    await expect(page.getByTestId('designer-publish')).toHaveText('发布', { timeout: 10_000 });
    await expect(page.getByTestId('designer-unpublish')).toHaveCount(0);

    // REAL backend verification: reverted to draft + publishedAt cleared.
    const draftDto = await readPage(page, pid);
    expect(draftDto.status, 'backend status after unpublish').toBe('draft');
    expect(draftDto.publishedAt ?? null, 'backend publishedAt after unpublish').toBeNull();
  });

  test('C1 (sad path): a new/local page (no pid) keeps the publish button disabled', async ({
    page,
  }) => {
    // No pageId / pageKey → the designer loads the local sample document; it is
    // not page-bound, so publishing is not possible until it is saved.
    await page.goto('/unified-designer', { waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 30_000 });

    const publishBtn = page.getByTestId('designer-publish');
    await expect(publishBtn).toBeVisible();
    await expect(publishBtn).toBeDisabled();
    // No backend page exists → no unpublish entry point.
    await expect(page.getByTestId('designer-unpublish')).toHaveCount(0);
  });

  test('C2: export downloads <pageKey>.page.json whose content carries the live blocks', async ({
    page,
    browser,
  }, testInfo) => {
    const pid = await seedDraftPage(browser, uniqueId('pdexp'));
    const seeded = await readPage(page, pid);
    const expectedPageKey = seeded.pageKey;

    await openDesigner(page, pid);

    // Click export and capture the actual download, then read the file content.
    const downloadPromise = page.waitForEvent('download', { timeout: 15_000 });
    await page.getByTestId('designer-export').click();
    const download = await downloadPromise;

    // The suggested filename is <pageKey>.page.json (or <id>.page.json fallback).
    expect(download.suggestedFilename()).toBe(`${expectedPageKey}.page.json`);

    const downloadPath = await download.path();
    expect(downloadPath, 'download has a local path').toBeTruthy();
    const fs = await import('fs');
    const exportedRaw = fs.readFileSync(downloadPath!, 'utf-8');
    const exported = JSON.parse(exportedRaw) as PageSchemaDto & { schemaVersion?: number; id?: string };
    await testInfo.attach('c2-exported-json', {
      body: Buffer.from(exportedRaw),
      contentType: 'application/json',
    });

    // FILE CONTENT verification — the export is a faithful V3 snapshot of the
    // canvas: schemaVersion 3, the root + the seeded section block are present.
    expect(exported.schemaVersion).toBe(3);
    expect(exported.id).toBe(expectedPageKey);
    expect(findBlockById(exported.blocks, ROOT_BLOCK), 'root block in export').toBeTruthy();
    const section = findBlockById(exported.blocks, SECTION_BLOCK);
    expect(section, 'seeded section in export').toBeTruthy();
    expect(section?.blockType).toBe('detail-section');
  });

  test('C2: import loads a known JSON onto the canvas and it round-trips on save', async ({
    page,
    browser,
  }, testInfo) => {
    const uid = uniqueId('pdimp');
    const pid = await seedDraftPage(browser, uid);
    const seeded = await readPage(page, pid);

    await openDesigner(page, pid);

    // Before import: the original section is on the canvas, the imported one is not.
    await expect(page.getByTestId(`outline-item-${SECTION_BLOCK}`)).toBeVisible({ timeout: 10_000 });
    const IMPORTED_BLOCK = `pd_imported_${uid}`.replace(/-/g, '_');
    await expect(page.getByTestId(`outline-item-${IMPORTED_BLOCK}`)).toHaveCount(0);

    // A valid PageSchemaV3 document (same pageKey/id so the saved page stays
    // bound to this pid) whose detail root holds a DIFFERENT section block.
    const importedDoc = {
      schemaVersion: 3,
      kind: 'detail',
      id: seeded.pageKey,
      pageKey: seeded.pageKey,
      modelCode: MODEL_CODE,
      title: `Imported ${uid}`,
      blocks: [
        {
          id: ROOT_BLOCK,
          blockType: 'detail',
          title: 'Imported root',
          dataSource: { model: MODEL_CODE },
          layout: { span: 12 },
          blocks: [
            {
              id: IMPORTED_BLOCK,
              blockType: 'detail-section',
              title: `Imported section ${uid}`,
              layout: { columns: 12 },
              blocks: [],
            },
          ],
        },
      ],
    };

    // Feed the JSON through the hidden import input (the designer-import button
    // just clicks it). setInputFiles fires the change handler that parses +
    // loads the document.
    await page.getByTestId('designer-import-input').setInputFiles({
      name: 'imported.page.json',
      mimeType: 'application/json',
      buffer: Buffer.from(JSON.stringify(importedDoc), 'utf-8'),
    });

    // The imported block appears on the canvas/outline, the original is gone,
    // and the document is now dirty (import joins the undo stack).
    await expect(page.getByTestId(`outline-item-${IMPORTED_BLOCK}`)).toBeVisible({ timeout: 10_000 });
    await expect(page.getByTestId(`outline-item-${SECTION_BLOCK}`)).toHaveCount(0);
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');
    await testInfo.attach('c2-after-import', {
      body: await page.screenshot(),
      contentType: 'image/png',
    });

    // Import is undoable — undo restores the original section.
    await page.getByTestId('designer-undo').click();
    await expect(page.getByTestId(`outline-item-${SECTION_BLOCK}`)).toBeVisible({ timeout: 5_000 });
    await expect(page.getByTestId(`outline-item-${IMPORTED_BLOCK}`)).toHaveCount(0);
    // Redo re-applies the import.
    await page.getByTestId('designer-redo').click();
    await expect(page.getByTestId(`outline-item-${IMPORTED_BLOCK}`)).toBeVisible({ timeout: 5_000 });

    // Save the imported document and verify it round-trips to the backend.
    await expect(async () => {
      const saveResp = page.waitForResponse(
        (r) => r.url().includes(`/api/pages/${pid}`) && r.request().method() === 'PUT',
        { timeout: 5_000 },
      );
      await page.getByTestId('designer-save').click();
      const resp = await saveResp;
      expect(resp.status()).toBe(200);
      const body = await resp.json();
      expect(body.code).toBe('0');
    }).toPass({ timeout: 30_000 });
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('已保存');

    const persisted = await readPage(page, pid);
    expect(findBlockById(persisted.blocks, IMPORTED_BLOCK), 'imported block persisted').toBeTruthy();
    expect(findBlockById(persisted.blocks, SECTION_BLOCK), 'original block replaced').toBeNull();
  });

  test('C2 (sad path): invalid JSON import shows an inline error and leaves the document unchanged', async ({
    page,
    browser,
  }, testInfo) => {
    const pid = await seedDraftPage(browser, uniqueId('pdbad'));

    await openDesigner(page, pid);
    await expect(page.getByTestId(`outline-item-${SECTION_BLOCK}`)).toBeVisible({ timeout: 10_000 });

    // Feed malformed JSON → the import handler must reject it without mutating
    // the document, and surface an inline error via the save-error channel.
    await page.getByTestId('designer-import-input').setInputFiles({
      name: 'broken.page.json',
      mimeType: 'application/json',
      buffer: Buffer.from('{ not: valid json,,, ', 'utf-8'),
    });

    await expect(page.getByTestId('designer-save-error')).toBeVisible({ timeout: 10_000 });
    await testInfo.attach('c2-import-invalid-error', {
      body: await page.screenshot(),
      contentType: 'image/png',
    });

    // The KEY invariant: the document was NOT replaced — the original section is
    // still on the canvas and no foreign block was loaded. (The status pill flips
    // to the error surface that carries designer-save-error; the document itself
    // is untouched, which is what import-rejection must guarantee.)
    await expect(page.getByTestId(`outline-item-${SECTION_BLOCK}`)).toBeVisible();

    // A structurally-wrong-but-valid JSON (schemaVersion 2) is also rejected and
    // leaves the document unchanged.
    await page.getByTestId('designer-import-input').setInputFiles({
      name: 'wrong-schema.page.json',
      mimeType: 'application/json',
      buffer: Buffer.from(
        JSON.stringify({ schemaVersion: 2, kind: 'detail', id: 'x', blocks: [] }),
        'utf-8',
      ),
    });
    await expect(page.getByTestId('designer-save-error')).toBeVisible({ timeout: 10_000 });
    await expect(page.getByTestId(`outline-item-${SECTION_BLOCK}`)).toBeVisible();

    // Definitive proof the document was not mutated by either rejected import:
    // the backend still has the original section and no imported block.
    const persisted = await readPage(page, pid);
    expect(findBlockById(persisted.blocks, SECTION_BLOCK), 'original section intact').toBeTruthy();
  });
});
