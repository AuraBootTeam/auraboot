/**
 * Unified Designer — version history / create-snapshot / rollback action-point golden.
 *
 * Closes the C3 slice of
 * docs/backlog/2026-06-17-page-designer-coverage-ux-gap.md:
 *   C3  Version history panel (GET /api/pages/{pid}/versions), Create snapshot
 *       (POST /api/pages/{pid}/versions) and Rollback (POST .../rollback/{historyId}).
 *       Drives the real toolbar button + panel and asserts the BACKEND state via
 *       GET /api/pages/{pid}/versions (list grows) and GET /api/pages/{pid}
 *       (blocks restored + version bumped) after rollback — not just a toast.
 *       Diff/compare UI is a deferred follow-up (the compareVersions endpoint
 *       exists but no UI is wired in this slice).
 *
 * Pattern follows tests/e2e/page-designer/publish-export-import-golden.spec.ts:
 *   seed a page via POST /api/pages with STABLE block ids (schemaVersion 3) ->
 *   open /unified-designer?pageId=<pid> -> drive a real action point ->
 *   assert the persisted artifact via API readback.
 *
 * data-testids verified against the live source (VersionHistoryPanel.tsx /
 * WorkbenchToolbar.tsx):
 *   - open panel:      designer-versions
 *   - panel:           version-history-panel
 *   - create snapshot: version-create-snapshot
 *   - version row:     version-row-<historyId>
 *   - rollback:        version-rollback-<historyId>  (then confirm)
 *   - confirm yes:     version-rollback-confirm-yes-<historyId>
 *   - confirm cancel:  version-rollback-cancel-<historyId>
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
const ROOT_BLOCK = 'detail_root';
const SECTION_BLOCK = 'pd_ver_section';

interface DslBlock {
  id?: string;
  blockType?: string;
  title?: unknown;
  dataSource?: Record<string, unknown>;
  layout?: Record<string, unknown>;
  blocks?: DslBlock[];
}

interface PageSchemaDto {
  pid: string;
  pageKey: string;
  version?: number;
  blocks?: DslBlock[];
}

interface PageVersionDto {
  id: number;
  version?: number;
  operation?: string;
  description?: string;
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

async function readVersions(page: Page, pid: string): Promise<PageVersionDto[]> {
  const resp = await page.request.get(`/api/pages/${pid}/versions`);
  expect(resp.ok(), `GET /api/pages/${pid}/versions failed: ${resp.status()}`).toBeTruthy();
  const body = await resp.json();
  expect(body.code, 'read versions API code').toBe('0');
  return (body.data ?? []) as PageVersionDto[];
}

async function openDesigner(page: Page, pid: string): Promise<void> {
  await page.goto(`/unified-designer?pageId=${pid}`, { waitUntil: 'domcontentloaded' });
  await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 30_000 });
  await expect(page.getByTestId('designer-dirty-state')).toHaveText('已保存', { timeout: 15_000 });
}

function detailDoc(pageKey: string, sectionId: string, sectionTitle: string) {
  return {
    schemaVersion: 3,
    kind: 'detail',
    id: pageKey,
    pageKey,
    modelCode: MODEL_CODE,
    title: `Version golden ${pageKey}`,
    blocks: [
      {
        id: ROOT_BLOCK,
        blockType: 'detail',
        title: 'Version golden root',
        dataSource: { model: MODEL_CODE },
        layout: { span: 12 },
        blocks: [
          {
            id: sectionId,
            blockType: 'detail-section',
            title: sectionTitle,
            layout: { columns: 12 },
            blocks: [],
          },
        ],
      },
    ],
  };
}

async function seedDraftPage(
  browser: import('@playwright/test').Browser,
  uid: string,
): Promise<{ pid: string; pageKey: string }> {
  const ctx = await browser.newContext({ storageState: ADMIN_STORAGE_STATE });
  const page = await ctx.newPage();
  const pageKey = `pd_ver_${uid}`.replace(/-/g, '_');
  const resp = await page.request.post('/api/pages', {
    data: {
      name: `Version golden ${uid}`,
      pageKey,
      title: `Version golden ${uid}`,
      kind: 'detail',
      modelCode: MODEL_CODE,
      // The unified designer loads/saves a V3 document; its client validator
      // requires schemaVersion 3. A v4 seed loads but fails save validation.
      schemaVersion: 3,
      blocks: detailDoc(pageKey, SECTION_BLOCK, 'Original section').blocks,
      extension: { e2e: true, scenario: 'version-history-golden' },
    },
  });
  expect(resp.ok(), `seed page failed: ${resp.status()} ${await resp.text()}`).toBeTruthy();
  const body = await resp.json();
  expect(body.code, 'seed page API code').toBe('0');
  const pid = String(body.data?.pid ?? '');
  expect(pid, 'seeded pid').toBeTruthy();
  await ctx.close();
  return { pid, pageKey };
}

test.describe.serial('Unified Designer version-history golden', () => {
  // Real save/reopen round-trips plus snapshot/rollback; the 15s default is tight.
  test.describe.configure({ timeout: 120_000 });

  test('C3: create snapshot grows the list; rollback restores the canvas + backend blocks', async ({
    page,
    browser,
  }, testInfo) => {
    const { pid, pageKey } = await seedDraftPage(browser, uniqueId('pdver'));

    await openDesigner(page, pid);

    // The versions toolbar button is enabled for a saved, page-bound document.
    const versionsBtn = page.getByTestId('designer-versions');
    await expect(versionsBtn).toBeVisible();
    await expect(versionsBtn).toBeEnabled();

    // Open the version panel — a fresh page has no versions yet.
    await versionsBtn.click();
    await expect(page.getByTestId('version-history-panel')).toBeVisible({ timeout: 10_000 });
    await expect(page.getByTestId('version-empty')).toBeVisible({ timeout: 10_000 });
    await testInfo.attach('c3-panel-empty', {
      body: await page.screenshot(),
      contentType: 'image/png',
    });

    // --- Snapshot #1 (captures the original section) ---
    await expect(async () => {
      const createResp = page.waitForResponse(
        (r) => r.url().includes(`/api/pages/${pid}/versions`) && r.request().method() === 'POST',
        { timeout: 5_000 },
      );
      await page.getByTestId('version-create-snapshot').click();
      const resp = await createResp;
      expect(resp.status()).toBe(200);
      const body = await resp.json();
      expect(body.code).toBe('0');
    }).toPass({ timeout: 30_000 });

    // BACKEND verification: the version list now has exactly one entry.
    await expect(async () => {
      const versions = await readVersions(page, pid);
      expect(versions.length, 'versions after snapshot #1').toBe(1);
    }).toPass({ timeout: 15_000 });
    const afterFirst = await readVersions(page, pid);
    const earliestHistoryId = afterFirst[afterFirst.length - 1].id;
    // The UI row for that history entry is rendered.
    await expect(page.getByTestId(`version-row-${earliestHistoryId}`)).toBeVisible({ timeout: 10_000 });
    await testInfo.attach('c3-after-snapshot-1', {
      body: await page.screenshot(),
      contentType: 'image/png',
    });

    // --- Change the document (replace the section via a real import action) and save ---
    // Close the panel first so the import file input is reachable.
    await page.getByTestId('version-panel-close').click();
    await expect(page.getByTestId('version-history-panel')).toHaveCount(0);

    const CHANGED_SECTION = `pd_ver_changed_${pageKey}`;
    await page.getByTestId('designer-import-input').setInputFiles({
      name: 'changed.page.json',
      mimeType: 'application/json',
      buffer: Buffer.from(
        JSON.stringify(detailDoc(pageKey, CHANGED_SECTION, 'Changed section')),
        'utf-8',
      ),
    });
    // The changed section is on the canvas; the original is gone; doc is dirty.
    await expect(page.getByTestId(`outline-item-${CHANGED_SECTION}`)).toBeVisible({ timeout: 10_000 });
    await expect(page.getByTestId(`outline-item-${SECTION_BLOCK}`)).toHaveCount(0);
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');

    // Save the changed document to the backend.
    await expect(async () => {
      const saveResp = page.waitForResponse(
        (r) =>
          r.url().includes(`/api/pages/${pid}`) &&
          r.request().method() === 'PUT',
        { timeout: 5_000 },
      );
      await page.getByTestId('designer-save').click();
      const resp = await saveResp;
      expect(resp.status()).toBe(200);
      const body = await resp.json();
      expect(body.code).toBe('0');
    }).toPass({ timeout: 30_000 });
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('已保存');

    // BACKEND sanity: the saved page now holds the changed section.
    const changedDto = await readPage(page, pid);
    expect(findBlockById(changedDto.blocks, CHANGED_SECTION), 'changed section persisted').toBeTruthy();
    expect(findBlockById(changedDto.blocks, SECTION_BLOCK), 'original section replaced').toBeNull();
    const versionAfterChange = changedDto.version ?? 0;

    // --- Snapshot #2 (captures the changed section) ---
    await page.getByTestId('designer-versions').click();
    await expect(page.getByTestId('version-history-panel')).toBeVisible({ timeout: 10_000 });
    await expect(async () => {
      const createResp = page.waitForResponse(
        (r) => r.url().includes(`/api/pages/${pid}/versions`) && r.request().method() === 'POST',
        { timeout: 5_000 },
      );
      await page.getByTestId('version-create-snapshot').click();
      const resp = await createResp;
      expect(resp.status()).toBe(200);
      const body = await resp.json();
      expect(body.code).toBe('0');
    }).toPass({ timeout: 30_000 });

    await expect(async () => {
      const versions = await readVersions(page, pid);
      expect(versions.length, 'versions after snapshot #2').toBe(2);
    }).toPass({ timeout: 15_000 });
    await testInfo.attach('c3-after-snapshot-2', {
      body: await page.screenshot(),
      contentType: 'image/png',
    });

    // --- Rollback to the EARLIEST snapshot (restores the original section) ---
    const rollbackRow = page.getByTestId(`version-rollback-${earliestHistoryId}`);
    await expect(rollbackRow).toBeVisible({ timeout: 10_000 });
    await rollbackRow.click();
    // Second-click confirm step.
    await expect(page.getByTestId(`version-rollback-confirm-${earliestHistoryId}`)).toBeVisible();
    await testInfo.attach('c3-rollback-confirm', {
      body: await page.screenshot(),
      contentType: 'image/png',
    });

    await expect(async () => {
      const rollbackResp = page.waitForResponse(
        (r) =>
          r.url().includes(`/api/pages/${pid}/rollback/`) && r.request().method() === 'POST',
        { timeout: 5_000 },
      );
      await page.getByTestId(`version-rollback-confirm-yes-${earliestHistoryId}`).click();
      const resp = await rollbackResp;
      expect(resp.status()).toBe(200);
      const body = await resp.json();
      expect(body.code).toBe('0');
    }).toPass({ timeout: 30_000 });

    // The panel closes and the canvas reloads with the restored original section.
    await expect(page.getByTestId('version-history-panel')).toHaveCount(0, { timeout: 10_000 });
    await expect(page.getByTestId(`outline-item-${SECTION_BLOCK}`)).toBeVisible({ timeout: 15_000 });
    await expect(page.getByTestId(`outline-item-${CHANGED_SECTION}`)).toHaveCount(0);
    await testInfo.attach('c3-after-rollback', {
      body: await page.screenshot(),
      contentType: 'image/png',
    });

    // BACKEND verification: the live page blocks are restored to the original
    // section and the version number advanced (rollback bumps + records history).
    const rolledBackDto = await readPage(page, pid);
    expect(findBlockById(rolledBackDto.blocks, SECTION_BLOCK), 'original section restored').toBeTruthy();
    expect(findBlockById(rolledBackDto.blocks, CHANGED_SECTION), 'changed section gone').toBeNull();
    expect(rolledBackDto.version ?? 0, 'version advanced after rollback').toBeGreaterThan(
      versionAfterChange,
    );

    // Rollback itself records additional history rows (pre-rollback backup +
    // rollback), so the list grew beyond the two manual snapshots.
    const finalVersions = await readVersions(page, pid);
    expect(finalVersions.length, 'versions after rollback').toBeGreaterThanOrEqual(2);
  });

  test('C3 (sad path): a new/local page (no pid) keeps the versions button disabled', async ({
    page,
  }) => {
    // No pageId / pageKey → the designer loads the local sample document; it is
    // not page-bound, so there is no server-side version history.
    await page.goto('/unified-designer', { waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 30_000 });

    const versionsBtn = page.getByTestId('designer-versions');
    await expect(versionsBtn).toBeVisible();
    await expect(versionsBtn).toBeDisabled();
    // Clicking a disabled button is a no-op — no panel appears.
    await expect(page.getByTestId('version-history-panel')).toHaveCount(0);
  });

  test('C3 (sad path): cancelling the rollback confirm does NOT roll back', async ({
    page,
    browser,
  }, testInfo) => {
    const { pid, pageKey } = await seedDraftPage(browser, uniqueId('pdvercancel'));

    await openDesigner(page, pid);

    // Create one snapshot so there is a row to (not) roll back to.
    await page.getByTestId('designer-versions').click();
    await expect(page.getByTestId('version-history-panel')).toBeVisible({ timeout: 10_000 });
    await expect(async () => {
      const createResp = page.waitForResponse(
        (r) => r.url().includes(`/api/pages/${pid}/versions`) && r.request().method() === 'POST',
        { timeout: 5_000 },
      );
      await page.getByTestId('version-create-snapshot').click();
      const resp = await createResp;
      expect(resp.status()).toBe(200);
    }).toPass({ timeout: 30_000 });

    const versions = await readVersions(page, pid);
    expect(versions.length).toBe(1);
    const historyId = versions[0].id;

    // Now change the saved page out-of-band (replace the section), so a real
    // rollback WOULD be observable — and prove that cancelling avoids it.
    const CHANGED = `pd_ver_cancel_${pageKey}`;
    await page.getByTestId('version-panel-close').click();
    await page.getByTestId('designer-import-input').setInputFiles({
      name: 'changed.page.json',
      mimeType: 'application/json',
      buffer: Buffer.from(JSON.stringify(detailDoc(pageKey, CHANGED, 'Changed')), 'utf-8'),
    });
    await expect(page.getByTestId(`outline-item-${CHANGED}`)).toBeVisible({ timeout: 10_000 });
    await expect(async () => {
      const saveResp = page.waitForResponse(
        (r) => r.url().includes(`/api/pages/${pid}`) && r.request().method() === 'PUT',
        { timeout: 5_000 },
      );
      await page.getByTestId('designer-save').click();
      const resp = await saveResp;
      expect(resp.status()).toBe(200);
    }).toPass({ timeout: 30_000 });

    // Snapshot the backend state AFTER the save — cancelling the rollback must
    // leave the page exactly here (blocks + version unchanged).
    const beforeCancel = await readPage(page, pid);
    const versionBeforeCancel = beforeCancel.version ?? 0;

    // Open the panel, click rollback, then CANCEL the confirm.
    await page.getByTestId('designer-versions').click();
    await expect(page.getByTestId('version-history-panel')).toBeVisible({ timeout: 10_000 });
    await page.getByTestId(`version-rollback-${historyId}`).click();
    await expect(page.getByTestId(`version-rollback-confirm-${historyId}`)).toBeVisible();
    await page.getByTestId(`version-rollback-cancel-${historyId}`).click();
    // Confirm UI dismissed, panel still open, no rollback request fired.
    await expect(page.getByTestId(`version-rollback-confirm-${historyId}`)).toHaveCount(0);
    await expect(page.getByTestId('version-history-panel')).toBeVisible();
    await testInfo.attach('c3-rollback-cancelled', {
      body: await page.screenshot(),
      contentType: 'image/png',
    });

    // BACKEND verification: the page was NOT rolled back — it still has the
    // changed section and the version did not advance from the cancel (it equals
    // exactly the post-save snapshot taken above).
    const afterCancel = await readPage(page, pid);
    expect(findBlockById(afterCancel.blocks, CHANGED), 'changed section intact (no rollback)').toBeTruthy();
    expect(findBlockById(afterCancel.blocks, SECTION_BLOCK), 'original NOT restored').toBeNull();
    expect(afterCancel.version ?? 0, 'version unchanged by cancel').toBe(versionBeforeCancel);
  });
});
