/**
 * Unified Designer — version compare / diff-viewer action-point golden.
 *
 * Extends the C3 version-history slice with the Compare / diff viewer
 * (docs/backlog/2026-06-17-page-designer-coverage-ux-gap.md). Drives the real
 * panel Compare mode against the backend compare endpoint:
 *   GET /api/pages/{pid}/versions/{from}/compare/{to}  →  PageSchemaVersionComparisonDTO
 *
 * Pattern follows version-history-golden.spec.ts:
 *   seed a draft page via POST /api/pages (STABLE block ids, schemaVersion 3) ->
 *   open /unified-designer?pageId=<pid> -> snapshot v1 -> change the document
 *   (import a new section + title) + save -> snapshot v2 -> enter Compare mode,
 *   pick the two snapshots, run Compare, and assert the diff view + summary +
 *   diff rows against the REAL backend response.
 *
 * The compare endpoint is coarse-grained (top-level key diff; `blocks` is one
 * JSON blob, not drilled into per-block), so the happy path asserts on the
 * coarse rows the server actually returns (a `blocks` MODIFIED row and/or a
 * `title` MODIFIED row) — it does NOT assume a block-level drill-down.
 *
 * data-testids verified against the live source (VersionHistoryPanel.tsx /
 * WorkbenchToolbar.tsx):
 *   - open panel:        designer-versions
 *   - panel:             version-history-panel
 *   - create snapshot:   version-create-snapshot
 *   - version row:       version-row-<historyId>
 *   - compare toggle:    version-compare-toggle
 *   - compare select:    version-compare-select-<historyId>
 *   - compare run:       version-compare-run
 *   - diff view:         version-diff-view
 *   - diff summary:      version-diff-summary
 *   - diff row:          version-diff-row-<fieldPath>
 *   - diff empty:        version-diff-empty
 *   - diff back:         version-diff-back
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
const SECTION_BLOCK = 'pd_diff_section';

interface DslBlock {
  id?: string;
  blockType?: string;
  title?: unknown;
  dataSource?: Record<string, unknown>;
  layout?: Record<string, unknown>;
  blocks?: DslBlock[];
}

interface PageVersionDto {
  id: number;
  version?: number;
  operation?: string;
  description?: string;
}

interface CompareDiff {
  differences: Array<{ fieldPath: string; type: string }>;
  summary?: { totalDifferences?: number; modifiedFields?: number };
}

/**
 * Drive the Compare run, await the real compare endpoint, and return its parsed
 * `data` payload. Keeping the capture in a typed helper (rather than mutating a
 * `let` inside the toPass closure) avoids the TS control-flow narrowing that
 * turns a closure-assigned outer variable into `never`.
 */
async function runCompare(page: Page, pid: string): Promise<CompareDiff> {
  let captured: CompareDiff | undefined;
  await expect(async () => {
    const compareResp = page.waitForResponse(
      (r) => r.url().includes(`/api/pages/${pid}/versions/`) && r.url().includes('/compare/'),
      { timeout: 5_000 },
    );
    await page.getByTestId('version-compare-run').click();
    const resp = await compareResp;
    expect(resp.status()).toBe(200);
    const body = await resp.json();
    expect(body.code).toBe('0');
    captured = body.data as CompareDiff;
  }).toPass({ timeout: 30_000 });
  expect(captured, 'compare payload captured').toBeTruthy();
  return captured as CompareDiff;
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

function detailDoc(pageKey: string, sectionId: string, sectionTitle: string, title: string) {
  return {
    schemaVersion: 3,
    kind: 'detail',
    id: pageKey,
    pageKey,
    modelCode: MODEL_CODE,
    title,
    blocks: [
      {
        id: ROOT_BLOCK,
        blockType: 'detail',
        title: 'Diff golden root',
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
  const pageKey = `pd_diff_${uid}`.replace(/-/g, '_');
  const resp = await page.request.post('/api/pages', {
    data: {
      name: `Diff golden ${uid}`,
      pageKey,
      title: `Diff golden ${uid}`,
      kind: 'detail',
      modelCode: MODEL_CODE,
      // The unified designer loads/saves a V3 document; its client validator
      // requires schemaVersion 3. A v4 seed loads but fails save validation.
      schemaVersion: 3,
      blocks: detailDoc(pageKey, SECTION_BLOCK, 'Original section', `Diff golden ${uid}`).blocks,
      extension: { e2e: true, scenario: 'version-diff-golden' },
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

/** Snapshot the current page; asserts the POST .../versions round-trip returns 0. */
async function createSnapshot(page: Page, pid: string): Promise<void> {
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
}

test.describe.serial('Unified Designer version-diff golden', () => {
  // Real save/reopen round-trips plus two snapshots + a compare; 15s default is tight.
  test.describe.configure({ timeout: 120_000 });

  test('happy: compare two snapshots surfaces the backend diff (summary + rows)', async ({
    page,
    browser,
  }, testInfo) => {
    const { pid, pageKey } = await seedDraftPage(browser, uniqueId('pddiff'));

    await openDesigner(page, pid);

    // --- Snapshot #1 (original section + title) ---
    await page.getByTestId('designer-versions').click();
    await expect(page.getByTestId('version-history-panel')).toBeVisible({ timeout: 10_000 });
    await expect(page.getByTestId('version-empty')).toBeVisible({ timeout: 10_000 });
    await createSnapshot(page, pid);
    await expect(async () => {
      const versions = await readVersions(page, pid);
      expect(versions.length, 'versions after snapshot #1').toBe(1);
    }).toPass({ timeout: 15_000 });
    const afterFirst = await readVersions(page, pid);
    const v1HistoryId = afterFirst[afterFirst.length - 1].id;
    await testInfo.attach('diff-after-snapshot-1', {
      body: await page.screenshot(),
      contentType: 'image/png',
    });

    // --- Change the document (replace section + title via a real import action) + save ---
    await page.getByTestId('version-panel-close').click();
    await expect(page.getByTestId('version-history-panel')).toHaveCount(0);

    const CHANGED_SECTION = `pd_diff_changed_${pageKey}`;
    await page.getByTestId('designer-import-input').setInputFiles({
      name: 'changed.page.json',
      mimeType: 'application/json',
      buffer: Buffer.from(
        JSON.stringify(
          detailDoc(pageKey, CHANGED_SECTION, 'Changed section', `Diff golden ${pageKey} CHANGED`),
        ),
        'utf-8',
      ),
    });
    await expect(page.getByTestId(`outline-item-${CHANGED_SECTION}`)).toBeVisible({ timeout: 10_000 });
    await expect(page.getByTestId(`outline-item-${SECTION_BLOCK}`)).toHaveCount(0);
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');

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

    // --- Snapshot #2 (changed section + title) ---
    await page.getByTestId('designer-versions').click();
    await expect(page.getByTestId('version-history-panel')).toBeVisible({ timeout: 10_000 });
    await createSnapshot(page, pid);
    await expect(async () => {
      const versions = await readVersions(page, pid);
      expect(versions.length, 'versions after snapshot #2').toBe(2);
    }).toPass({ timeout: 15_000 });
    const afterSecond = await readVersions(page, pid);
    const v2HistoryId = afterSecond[0].id; // newest-first
    expect(v2HistoryId, 'distinct snapshot ids').not.toBe(v1HistoryId);

    // --- Enter Compare mode, pick the two snapshots, run Compare ---
    await page.getByTestId('version-compare-toggle').click();
    // Compare cannot run until exactly two versions are selected.
    await expect(page.getByTestId('version-compare-run')).toBeDisabled();
    await page.getByTestId(`version-compare-select-${v1HistoryId}`).check();
    await page.getByTestId(`version-compare-select-${v2HistoryId}`).check();
    await expect(page.getByTestId('version-compare-run')).toBeEnabled();
    await testInfo.attach('diff-compare-selected', {
      body: await page.screenshot(),
      contentType: 'image/png',
    });

    // Drive the real compare endpoint and capture the parsed payload.
    const payload = await runCompare(page, pid);

    // The diff view replaces the list.
    await expect(page.getByTestId('version-diff-view')).toBeVisible({ timeout: 10_000 });

    // BACKEND-driven assertions — the wire payload drives what the UI must show.
    const modifiedFields =
      payload.summary?.modifiedFields ??
      payload.differences.filter((d) => String(d.type).toUpperCase() === 'MODIFIED').length;
    expect(modifiedFields, 'at least one modified field (coarse blocks/title diff)').toBeGreaterThanOrEqual(1);
    expect(
      payload.summary?.totalDifferences ?? payload.differences.length,
      'non-empty diff between two distinct snapshots',
    ).toBeGreaterThanOrEqual(1);

    // Summary strip renders.
    await expect(page.getByTestId('version-diff-summary')).toBeVisible();

    // At least one of the coarse rows the backend returns (blocks/title/rowVersion)
    // is rendered. We assert on whichever the server actually emitted so the test
    // mirrors the real coarse-grained diff rather than a block-level drill-down.
    const rowFieldPaths = payload.differences.map((d) => d.fieldPath);
    expect(rowFieldPaths.length, 'diff has rows').toBeGreaterThanOrEqual(1);
    // The changed section lives inside `blocks` and the title changed too, so at
    // least one of those coarse keys must be present.
    expect(
      rowFieldPaths.includes('blocks') || rowFieldPaths.includes('title'),
      `expected a blocks/title MODIFIED row, got: ${rowFieldPaths.join(', ')}`,
    ).toBeTruthy();
    const firstField = rowFieldPaths.find((f) => f === 'blocks' || f === 'title') ?? rowFieldPaths[0];
    await expect(page.getByTestId(`version-diff-row-${firstField}`)).toBeVisible({ timeout: 10_000 });

    await testInfo.attach('diff-view', {
      body: await page.screenshot(),
      contentType: 'image/png',
    });

    // Returning to the list is possible from the diff view.
    await page.getByTestId('version-diff-back').click();
    await expect(page.getByTestId('version-diff-view')).toHaveCount(0);
    await expect(page.getByTestId('version-list')).toBeVisible();
  });

  test('sad: comparing two identical snapshots shows the no-differences state', async ({
    page,
    browser,
  }, testInfo) => {
    const { pid } = await seedDraftPage(browser, uniqueId('pddiffsame'));

    await openDesigner(page, pid);

    // Take two snapshots of the UNCHANGED document. The backend compares the two
    // version snapshots and — because the content is identical — returns an empty
    // differences list (totalDifferences = 0). This is the same "compare a version
    // with itself" empty state, expressible through the two-checkbox UI.
    await page.getByTestId('designer-versions').click();
    await expect(page.getByTestId('version-history-panel')).toBeVisible({ timeout: 10_000 });
    await createSnapshot(page, pid);
    await createSnapshot(page, pid);
    await expect(async () => {
      expect((await readVersions(page, pid)).length, 'two snapshots').toBe(2);
    }).toPass({ timeout: 15_000 });
    const both = await readVersions(page, pid);
    const a = both[0].id;
    const b = both[1].id;
    expect(a, 'two distinct snapshot ids').not.toBe(b);

    // Enter compare mode, pick both snapshots, run compare.
    await page.getByTestId('version-compare-toggle').click();
    await page.getByTestId(`version-compare-select-${a}`).check();
    await page.getByTestId(`version-compare-select-${b}`).check();
    await expect(page.getByTestId('version-compare-run')).toBeEnabled();

    const p = await runCompare(page, pid);

    // BACKEND verification: identical content snapshots diff to nothing.
    expect(p.summary?.totalDifferences ?? p.differences.length, 'no differences').toBe(0);

    // UI: the diff view shows the no-differences empty state.
    await expect(page.getByTestId('version-diff-view')).toBeVisible({ timeout: 10_000 });
    await expect(page.getByTestId('version-diff-empty')).toBeVisible({ timeout: 10_000 });
    await testInfo.attach('diff-no-differences', {
      body: await page.screenshot(),
      contentType: 'image/png',
    });
  });
});
