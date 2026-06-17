/**
 * Unified Designer — inspector authoring golden coverage.
 *
 * Closes the gaps recorded in
 * docs/backlog/2026-06-17-page-designer-coverage-ux-gap.md §2.A:
 *   A2  helper-block inspectors (bpm-panel / activity-timeline / field-history)
 *   A4  form-section inspector (collapsible / visibleWhen JSON / columns)
 *   A5  AI lock toggle → canvas badge → persisted props.aiLocked
 *   A6  Advanced JSON tab (happy apply + invalid-JSON error state)
 *
 * Pattern follows tests/e2e/designer/unified-designer-workbench.spec.ts:
 *   seed a page via POST /api/pages with STABLE block ids ->
 *   open /unified-designer?pageId=<pid> ->
 *   select a block via outline-item-<id> ->
 *   edit inspector-field-<path> (or the Advanced JSON editors) ->
 *   click designer-save and wait for the PUT round-trip ->
 *   reload + GET /api/pages/<pid> and assert the persisted block.
 *
 * Every edit is paired with a GET readback `toMatchObject` so a save that
 * silently drops a prop fails here (not just a green UI). The inspector
 * data-testids are verified against the live source:
 *   - basic field: inspector-field-<dotPath>            (SchemaInspector.tsx)
 *   - boolean field: checkbox at the same testid
 *   - json field: inspector-field-<path> + inspector-json-field-apply-<path>
 *   - advanced tab: inspector-tab-advanced, inspector-json-<key>,
 *                   inspector-json-apply-<key>, inspector-json-error-<key>
 *   - AI lock badge: ai-lock-badge-<blockId>            (CanvasHost.tsx)
 *
 * @since 4.0.0
 */

import type { Page } from '@playwright/test';
import { test, expect } from '../../fixtures';
import { uniqueId } from '../helpers';

const ADMIN_STORAGE_STATE =
  process.env.PW_ADMIN_STORAGE_STATE ||
  (process.env.PW_STORAGE_DIR ? `${process.env.PW_STORAGE_DIR}/admin.json` : './tests/storage/admin.json');

// ab_announcement is a published platform meta-model present in every OSS stack.
// The helper blocks under test (bpm-panel / activity-timeline / field-history /
// detail-section) do not bind model fields, so the model only has to satisfy the
// detail-page contract (a real, published modelCode for the root detail block).
const MODEL_CODE = 'ab_announcement';

interface DslBlock {
  id?: string;
  blockType?: string;
  field?: string;
  actionType?: string;
  title?: unknown;
  region?: string;
  props?: Record<string, unknown>;
  layout?: Record<string, unknown>;
  dataSource?: Record<string, unknown>;
  blocks?: DslBlock[];
}

interface PageSchemaDto {
  pid: string;
  pageKey: string;
  kind?: string;
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
  // The route lazily compiles on the first hit and the workbench mounts only
  // after the page schema + model fields load, so allow generous first-paint time.
  await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 30_000 });
  await expect(page.getByTestId('designer-dirty-state')).toHaveText('已保存', { timeout: 15_000 });
}

async function selectBlock(page: Page, blockId: string): Promise<void> {
  await page.getByTestId(`outline-item-${blockId}`).click();
  await expect(page.getByTestId('inspector-selected-id')).toContainText(blockId);
}

/**
 * Save and wait for the real PUT to land. The save button is disabled while the
 * document is clean / saving / invalid, and a click issued right after an
 * inspector edit can be lost to the blur/re-render that click triggers. So wait
 * for the button to be enabled (dirty), then retry click + PUT until it fires
 * (mirrors saveDesignerPage in UDW).
 */
async function saveDesigner(page: Page, pid: string): Promise<void> {
  const saveButton = page.getByTestId('designer-save');
  await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');
  await expect(saveButton).toBeEnabled({ timeout: 10_000 });
  await expect(async () => {
    const saveResp = page.waitForResponse(
      (r) => r.url().includes(`/api/pages/${pid}`) && r.request().method() === 'PUT',
      { timeout: 5_000 },
    );
    await saveButton.click();
    const resp = await saveResp;
    expect(resp.status()).toBe(200);
    const body = await resp.json();
    expect(body.code).toBe('0');
  }).toPass({ timeout: 30_000 });
  await expect(page.getByTestId('designer-dirty-state')).toHaveText('已保存');
}

/** Apply a basic-tab JSON inspector field (inspector-json-field-apply-<path>). */
async function applyJsonField(page: Page, path: string, value: unknown): Promise<void> {
  const textarea = page.getByTestId(`inspector-field-${path}`);
  await expect(textarea).toBeVisible({ timeout: 5_000 });
  await textarea.fill(JSON.stringify(value, null, 2));
  // Let the draft state commit before the apply handler reads it (UDW pattern).
  await page.evaluate(
    () => new Promise<void>((resolve) => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))),
  );
  await page.getByTestId(`inspector-json-field-apply-${path}`).click();
  // Let the apply commit to the document before the next interaction so the
  // dirty snapshot reflects this edit (avoids a clean-snapshot save race).
  await page.evaluate(
    () => new Promise<void>((resolve) => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))),
  );
}

const BPM_BLOCK = 'pd_inspector_bpm';
const TIMELINE_BLOCK = 'pd_inspector_timeline';
const HISTORY_BLOCK = 'pd_inspector_history';
const SECTION_BLOCK = 'pd_inspector_section';

test.describe.serial('Unified Designer inspector authoring golden', () => {
  // Real save/reopen round-trips plus several inspector edits; the 15s default is tight.
  test.describe.configure({ timeout: 90_000 });

  const uid = uniqueId('pdinsp');
  let pid = '';

  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: ADMIN_STORAGE_STATE });
    const page = await ctx.newPage();

    const resp = await page.request.post('/api/pages', {
      data: {
        name: `Inspector authoring ${uid}`,
        pageKey: `pd_inspector_${uid}`.replace(/-/g, '_'),
        title: `Inspector authoring ${uid}`,
        kind: 'detail',
        modelCode: MODEL_CODE,
        // The unified designer loads/saves a V3 document; its client validator
        // requires schemaVersion 3 (validatePageSchemaV3), matching the UDW
        // reference suite seed. A schemaVersion 4 seed loads but fails the
        // client save validation, so the save PUT never fires.
        schemaVersion: 3,
        blocks: [
          {
            id: 'detail_root',
            blockType: 'detail',
            title: 'Inspector authoring root',
            dataSource: { model: MODEL_CODE },
            layout: { span: 12 },
            // detail-section is allowed directly under detail; the bpm-panel /
            // activity-timeline / field-history helper blocks are NOT allowed
            // directly under detail (BlockRegistry.allowedChildren), so they are
            // nested in a `columns` container which does allow them. Matches the
            // V3 canContain contract the client validator enforces on save.
            blocks: [
              {
                id: SECTION_BLOCK,
                blockType: 'detail-section',
                title: 'Section under test',
                layout: { columns: 12 },
                blocks: [],
              },
              {
                id: 'detail_helpers_columns',
                blockType: 'columns',
                title: 'Helper blocks',
                layout: { columns: 1, span: 12 },
                blocks: [
                  {
                    id: BPM_BLOCK,
                    blockType: 'bpm-panel',
                    title: 'Approval panel',
                    props: { status: 'draft' },
                  },
                  {
                    id: TIMELINE_BLOCK,
                    blockType: 'activity-timeline',
                    title: 'Activity',
                    props: {},
                  },
                  {
                    id: HISTORY_BLOCK,
                    blockType: 'field-history',
                    title: 'Field history',
                    props: {},
                  },
                ],
              },
            ],
          },
        ],
        extension: { e2e: true, scenario: 'inspector-authoring-golden' },
      },
    });
    expect(resp.ok(), `seed page failed: ${resp.status()} ${await resp.text()}`).toBeTruthy();
    const body = await resp.json();
    expect(body.code, 'seed page API code').toBe('0');
    pid = String(body.data?.pid ?? '');
    expect(pid, 'seeded pid').toBeTruthy();

    await ctx.close();
  });

  test('A2: bpm-panel inspector — status/assignee/dueAt + actions JSON persist and reload', async ({
    page,
  }) => {
    const assignee = `Approver ${uid}`;
    const dueAt = '2026-07-15';
    const actions = [
      { label: 'Approve', command: 'page_schema:approve' },
      { label: 'Reject', command: 'page_schema:reject' },
    ];

    await openDesigner(page, pid);
    await selectBlock(page, BPM_BLOCK);

    await page.getByTestId('inspector-field-props.status').selectOption('pending');
    await page.getByTestId('inspector-field-props.assignee').fill(assignee);
    await page.getByTestId('inspector-field-props.dueAt').fill(dueAt);
    await applyJsonField(page, 'props.actions', actions);
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');

    await saveDesigner(page, pid);

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15_000 });
    await selectBlock(page, BPM_BLOCK);
    await expect(page.getByTestId('inspector-field-props.status')).toHaveValue('pending');
    await expect(page.getByTestId('inspector-field-props.assignee')).toHaveValue(assignee);
    await expect(page.getByTestId('inspector-field-props.dueAt')).toHaveValue(dueAt);
    await expect(page.getByTestId('inspector-field-props.actions')).toContainText('page_schema:approve');

    const persisted = await readPage(page, pid);
    const block = findBlockById(persisted.blocks, BPM_BLOCK);
    expect(block).toMatchObject({
      blockType: 'bpm-panel',
      props: expect.objectContaining({ status: 'pending', assignee, dueAt, actions }),
    });
  });

  test('A2: activity-timeline + field-history inspectors — items/entries JSON persist', async ({
    page,
  }) => {
    const items = [
      { actor: `User ${uid}`, action: 'submitted', time: '2026-07-01 09:00' },
      { actor: `User ${uid}`, action: 'approved', time: '2026-07-02 14:30' },
    ];
    const entries = [
      { field: 'status', from: 'draft', to: 'pending', changedBy: `User ${uid}` },
    ];
    const timelineEmpty = `No activity ${uid}`;
    const historyEmpty = `No history ${uid}`;

    await openDesigner(page, pid);

    await selectBlock(page, TIMELINE_BLOCK);
    await applyJsonField(page, 'props.items', items);
    await page.getByTestId('inspector-field-props.emptyText').fill(timelineEmpty);

    await selectBlock(page, HISTORY_BLOCK);
    await applyJsonField(page, 'props.entries', entries);
    await page.getByTestId('inspector-field-props.emptyText').fill(historyEmpty);
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');

    await saveDesigner(page, pid);

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15_000 });
    await selectBlock(page, TIMELINE_BLOCK);
    await expect(page.getByTestId('inspector-field-props.items')).toContainText('submitted');
    await expect(page.getByTestId('inspector-field-props.emptyText')).toHaveValue(timelineEmpty);
    await selectBlock(page, HISTORY_BLOCK);
    await expect(page.getByTestId('inspector-field-props.entries')).toContainText('changedBy');
    await expect(page.getByTestId('inspector-field-props.emptyText')).toHaveValue(historyEmpty);

    const persisted = await readPage(page, pid);
    expect(findBlockById(persisted.blocks, TIMELINE_BLOCK)).toMatchObject({
      blockType: 'activity-timeline',
      props: expect.objectContaining({ items, emptyText: timelineEmpty }),
    });
    expect(findBlockById(persisted.blocks, HISTORY_BLOCK)).toMatchObject({
      blockType: 'field-history',
      props: expect.objectContaining({ entries, emptyText: historyEmpty }),
    });
  });

  test('A4: form-section inspector — collapsible / visibleWhen JSON / columns persist and reload', async ({
    page,
  }) => {
    const visibleWhen = { field: 'status', op: 'eq', value: 'pending' };

    await openDesigner(page, pid);
    await selectBlock(page, SECTION_BLOCK);

    // props.collapsible is a boolean inspector field rendered as a checkbox.
    const collapsible = page.getByTestId('inspector-field-props.collapsible');
    await expect(collapsible).toBeVisible({ timeout: 5_000 });
    if (!(await collapsible.isChecked())) await collapsible.click();
    await expect(collapsible).toBeChecked();

    await applyJsonField(page, 'props.visibleWhen', visibleWhen);

    // layout.columns is a number inspector field.
    await page.getByTestId('inspector-field-layout.columns').fill('6');
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');

    await saveDesigner(page, pid);

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15_000 });
    await selectBlock(page, SECTION_BLOCK);
    await expect(page.getByTestId('inspector-field-props.collapsible')).toBeChecked();
    await expect(page.getByTestId('inspector-field-props.visibleWhen')).toContainText('pending');
    await expect(page.getByTestId('inspector-field-layout.columns')).toHaveValue('6');

    const persisted = await readPage(page, pid);
    const block = findBlockById(persisted.blocks, SECTION_BLOCK);
    expect(block).toMatchObject({
      blockType: 'detail-section',
      props: expect.objectContaining({ collapsible: true, visibleWhen }),
      layout: expect.objectContaining({ columns: 6 }),
    });
  });

  test('A5: AI lock toggle → canvas badge appears → persisted props.aiLocked=true', async ({ page }) => {
    await openDesigner(page, pid);
    await selectBlock(page, BPM_BLOCK);

    // No badge before locking.
    await expect(page.getByTestId(`ai-lock-badge-${BPM_BLOCK}`)).toHaveCount(0);

    // props.aiLocked is appended to every block's inspector by getFieldsForBlock.
    const aiLock = page.getByTestId('inspector-field-props.aiLocked');
    await expect(aiLock).toBeVisible({ timeout: 5_000 });
    if (!(await aiLock.isChecked())) await aiLock.click();
    await expect(aiLock).toBeChecked();

    // The canvas immediately reflects the lock with a visible badge.
    await expect(page.getByTestId(`ai-lock-badge-${BPM_BLOCK}`)).toBeVisible({ timeout: 5_000 });
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');

    await saveDesigner(page, pid);

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15_000 });
    // Badge re-renders from the persisted schema on reload.
    await expect(page.getByTestId(`ai-lock-badge-${BPM_BLOCK}`)).toBeVisible({ timeout: 10_000 });
    await selectBlock(page, BPM_BLOCK);
    await expect(page.getByTestId('inspector-field-props.aiLocked')).toBeChecked();

    const persisted = await readPage(page, pid);
    const block = findBlockById(persisted.blocks, BPM_BLOCK);
    expect(block?.props).toMatchObject({ aiLocked: true });
  });

  test('A6: Advanced JSON tab — valid apply persists; invalid JSON shows error and does not write', async ({
    page,
  }) => {
    const validProps = { badgeText: `Live ${uid}`, tone: 'info' };

    await openDesigner(page, pid);
    await selectBlock(page, TIMELINE_BLOCK);

    // Switch to the Advanced JSON tab (raw props/layout/dataSource/extension editors).
    await page.getByTestId('inspector-tab-advanced').click();
    const propsEditor = page.getByTestId('inspector-json-props');
    await expect(propsEditor).toBeVisible({ timeout: 5_000 });

    // --- sad path first: invalid JSON shows an error and does not commit ---
    await propsEditor.fill('{ not valid json,,, }');
    await page.evaluate(
      () => new Promise<void>((resolve) => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))),
    );
    await page.getByTestId('inspector-json-apply-props').click();
    await expect(page.getByTestId('inspector-json-error-props')).toBeVisible({ timeout: 5_000 });

    // The invalid draft must not have mutated the persisted props: a clean reload
    // of the readback (timeline still has the items from A2) proves no write here.
    const beforeApply = await readPage(page, pid);
    const timelineBefore = findBlockById(beforeApply.blocks, TIMELINE_BLOCK);
    expect(timelineBefore?.props).not.toMatchObject({ badgeText: expect.anything() });

    // --- happy path: valid JSON applies, error clears, save persists ---
    await propsEditor.fill(JSON.stringify(validProps, null, 2));
    await page.evaluate(
      () => new Promise<void>((resolve) => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))),
    );
    await page.getByTestId('inspector-json-apply-props').click();
    await expect(page.getByTestId('inspector-json-error-props')).toHaveCount(0);
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');

    await saveDesigner(page, pid);

    const persisted = await readPage(page, pid);
    const block = findBlockById(persisted.blocks, TIMELINE_BLOCK);
    // Advanced "props" apply replaces the whole props object with the typed JSON.
    expect(block).toMatchObject({
      blockType: 'activity-timeline',
      props: validProps,
    });
  });

  test('A8: dirty pill + unsaved-changes leave warning (cancel keeps you on the designer)', async ({
    page,
  }) => {
    await openDesigner(page, pid);

    // Clean state: dirty pill shows saved, save button disabled, no leave warning.
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('已保存');
    await expect(page.getByTestId('designer-save')).toBeDisabled();
    await expect(page.getByTestId('designer-leave-warning')).toHaveCount(0);

    // A page-bound document renders a return link; making an edit dirties the doc.
    const returnLink = page.getByTestId('designer-return-link');
    await expect(returnLink).toBeVisible({ timeout: 10_000 });

    await selectBlock(page, SECTION_BLOCK);
    await page.getByTestId('inspector-field-title').fill(`Leave warning ${uid}`);
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');
    await expect(page.getByTestId('designer-save')).toBeEnabled();

    // Navigating away with unsaved changes is intercepted by a leave warning
    // instead of leaving immediately.
    await returnLink.click();
    await expect(page.getByTestId('designer-leave-warning')).toBeVisible({ timeout: 5_000 });
    await expect(page.getByTestId('designer-leave-cancel')).toBeVisible();
    await expect(page.getByTestId('designer-leave-confirm')).toBeVisible();
    // Still on the designer — the click did not navigate away.
    expect(page.url()).toContain('unified-designer');

    // Cancel dismisses the warning and keeps the unsaved edit (still dirty).
    await page.getByTestId('designer-leave-cancel').click();
    await expect(page.getByTestId('designer-leave-warning')).toHaveCount(0);
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible();
  });
});
