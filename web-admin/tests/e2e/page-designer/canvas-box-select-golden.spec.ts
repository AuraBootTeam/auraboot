/**
 * Unified Designer — canvas box-select (geometric marquee) golden coverage (C5).
 *
 * Feature (extends the modifier-click multi-select shipped in #751): dragging a
 * selection rectangle across the EMPTY canvas selects every block the rectangle
 * covers. The marquee:
 *   - starts only on empty canvas (never on a block frame / control),
 *   - shows a live rectangle overlay (`marquee-rect`) while dragging,
 *   - on release, drops covered ancestor containers and selects the innermost
 *     blocks (so a box across two sibling sections selects exactly those two,
 *     not the page-root container around them),
 *   - feeds the existing multi-selection state + batch bar from #751.
 *
 * Real-stack golden, mirroring canvas-multiselect-golden.spec.ts:
 *   seed a detail page via POST /api/pages with STABLE block ids (3 deletable
 *   same-level detail-sections under an undeletable detail root) ->
 *   open /unified-designer?pageId=<pid> ->
 *   drag a marquee across two sibling sections on the real canvas ->
 *   assert the batch bar + data-multi-selected, batch delete, designer-save
 *   (PUT round-trip) + GET /api/pages readback to prove the delete persisted.
 *
 * ⚠️ Flaky-class honesty (AGENTS §2.2 + dnd conventions): box-select is the most
 * flake-prone golden class. The marquee gesture needs MULTI-STEP pointer frames
 * (mouse.move x N, not a single dragTo — a single step won't cross the start
 * threshold). The deterministic correctness guarantee lives in the pure
 * hit-test unit tests (marqueeHitTest.test.ts, 17 cases); this E2E is the
 * best-effort real-browser regression. If the multi-step pointer proves
 * unstable in CI it must be reported as flake (with screenshots), NOT papered
 * over with retries.
 *
 * data-testids verified against the live source:
 *   - canvas host:        unified-canvas-host          (CanvasHost.tsx)
 *   - marquee overlay:    marquee-rect                 (CanvasHost.tsx)
 *   - canvas block:       canvas-block-<id>            (CanvasHost.tsx)
 *   - additive selection: data-multi-selected          (CanvasHost.tsx)
 *   - batch bar:          multi-select-bar / -count / -delete (UDW)
 *
 * @since 4.0.0
 */

import type { Page } from '@playwright/test';
import { test, expect } from '../../fixtures';
import { uniqueId } from '../helpers';

// ab_announcement is a published platform meta-model present in every OSS stack.
const MODEL_CODE = 'ab_announcement';

const ROOT_BLOCK = 'detail_root';
const SECTION_A = 'pd_bs_section_a';
const SECTION_B = 'pd_bs_section_b';
const SECTION_C = 'pd_bs_section_c';

interface DslBlock {
  id?: string;
  blockType?: string;
  title?: unknown;
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
  await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 30_000 });
  await expect(page.getByTestId('designer-dirty-state')).toHaveText('已保存', { timeout: 15_000 });
}

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

async function shot(page: Page, name: string): Promise<void> {
  await page.screenshot({ path: `test-results/canvas-box-select-${name}.png`, fullPage: true });
}

/**
 * Drag a marquee from `from` to `to` in viewport pixels using MULTI-STEP pointer
 * frames. A single move would not cross the start threshold (and dnd marquee
 * gestures generally need intermediate frames), so we step the pointer.
 */
async function dragMarquee(
  page: Page,
  from: { x: number; y: number },
  to: { x: number; y: number },
  steps = 12,
): Promise<void> {
  await page.mouse.move(from.x, from.y);
  await page.mouse.down();
  for (let i = 1; i <= steps; i += 1) {
    const t = i / steps;
    await page.mouse.move(from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t);
  }
  await page.mouse.up();
}

/** Bounding box of a canvas block in viewport pixels. */
async function boxOf(page: Page, blockId: string): Promise<{ x: number; y: number; width: number; height: number }> {
  const box = await page.getByTestId(`canvas-block-${blockId}`).boundingBox();
  expect(box, `bounding box for ${blockId}`).not.toBeNull();
  return box!;
}

async function seedPage(page: Page, label: string): Promise<string> {
  const uid = uniqueId('pdbs');
  const resp = await page.request.post('/api/pages', {
    data: {
      name: `Box-select golden ${label} ${uid}`,
      pageKey: `pd_bs_${label}_${uid}`.replace(/-/g, '_'),
      title: `Box-select golden ${label} ${uid}`,
      kind: 'detail',
      modelCode: MODEL_CODE,
      schemaVersion: 3,
      blocks: [
        {
          id: ROOT_BLOCK,
          blockType: 'detail',
          title: 'Box-select root',
          dataSource: { model: MODEL_CODE },
          layout: { span: 12 },
          blocks: [
            { id: SECTION_A, blockType: 'detail-section', title: 'Section A', layout: { columns: 12 }, blocks: [] },
            { id: SECTION_B, blockType: 'detail-section', title: 'Section B', layout: { columns: 12 }, blocks: [] },
            { id: SECTION_C, blockType: 'detail-section', title: 'Section C', layout: { columns: 12 }, blocks: [] },
          ],
        },
      ],
      extension: { e2e: true, scenario: 'canvas-box-select-golden' },
    },
  });
  expect(resp.ok(), `seed page failed: ${resp.status()} ${await resp.text()}`).toBeTruthy();
  const body = await resp.json();
  expect(body.code, 'seed page API code').toBe('0');
  const pid = String(body.data?.pid ?? '');
  expect(pid, 'seeded pid').toBeTruthy();
  return pid;
}

test.describe('Unified Designer canvas box-select (marquee) golden', () => {
  test.describe.configure({ timeout: 90_000 });

  test('happy: marquee across two sibling sections multi-selects them, then batch delete persists', async ({
    page,
  }) => {
    const pid = await seedPage(page, 'happy');
    await openDesigner(page, pid);

    await expect(page.getByTestId(`canvas-block-${SECTION_A}`)).toBeVisible();
    await expect(page.getByTestId(`canvas-block-${SECTION_B}`)).toBeVisible();
    await expect(page.getByTestId(`canvas-block-${SECTION_C}`)).toBeVisible();
    await shot(page, '01-seeded');

    // Geometry: sections are full-width (span 12) and stacked vertically. Build a
    // marquee that starts in the empty canvas gutter to the LEFT of Section A's
    // top and drags down-right to mid Section B — covering A + B but not C.
    const a = await boxOf(page, SECTION_A);
    const b = await boxOf(page, SECTION_B);
    const c = await boxOf(page, SECTION_C);

    // Start just left of the section column (empty canvas band) at A's top edge.
    const start = { x: Math.max(a.x - 24, 8), y: a.y + 4 };
    // End inside Section B (below A, above C) and to the right edge of the section.
    const end = { x: b.x + b.width - 8, y: Math.min(b.y + b.height - 6, c.y - 6) };

    await dragMarquee(page, start, end);

    // The batch bar appears with exactly the two covered sections selected.
    await expect(page.getByTestId('multi-select-bar')).toBeVisible({ timeout: 5_000 });
    await expect(page.getByTestId('multi-select-count')).toHaveText('已选 2 项');
    await expect(page.getByTestId(`canvas-block-${SECTION_A}`)).toHaveAttribute('data-multi-selected', 'true');
    await expect(page.getByTestId(`canvas-block-${SECTION_B}`)).toHaveAttribute('data-multi-selected', 'true');
    await expect(page.getByTestId(`canvas-block-${SECTION_C}`)).toHaveAttribute('data-multi-selected', 'false');
    // The page-root container that wraps both sections is NOT in the selection
    // (ancestor-drop), so the count is 2 and the root survives a batch delete.
    await expect(page.getByTestId(`canvas-block-${ROOT_BLOCK}`)).toHaveAttribute('data-multi-selected', 'false');
    await shot(page, '02-marquee-two-selected');

    // Batch delete -> the two covered sections vanish; C + root remain.
    await page.getByTestId('multi-select-delete').click();
    await expect(page.getByTestId(`canvas-block-${SECTION_A}`)).toHaveCount(0);
    await expect(page.getByTestId(`canvas-block-${SECTION_B}`)).toHaveCount(0);
    await expect(page.getByTestId(`canvas-block-${SECTION_C}`)).toBeVisible();
    await expect(page.getByTestId(`canvas-block-${ROOT_BLOCK}`)).toBeVisible();
    await expect(page.getByTestId('multi-select-bar')).toHaveCount(0);
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');
    await shot(page, '03-after-delete');

    // Persist and prove via GET readback.
    await saveDesigner(page, pid);
    const persisted = await readPage(page, pid);
    expect(findBlockById(persisted.blocks, SECTION_A), 'A removed').toBeNull();
    expect(findBlockById(persisted.blocks, SECTION_B), 'B removed').toBeNull();
    expect(findBlockById(persisted.blocks, SECTION_C), 'C kept (not covered)').not.toBeNull();
    expect(findBlockById(persisted.blocks, ROOT_BLOCK), 'root kept').not.toBeNull();
  });

  test('happy: a marquee enclosing all three sections selects all three', async ({ page }) => {
    const pid = await seedPage(page, 'all');
    await openDesigner(page, pid);

    const a = await boxOf(page, SECTION_A);
    const c = await boxOf(page, SECTION_C);

    // A tall marquee from above A's top-left to below C's bottom-right.
    const start = { x: Math.max(a.x - 24, 8), y: a.y + 2 };
    const end = { x: c.x + c.width - 8, y: c.y + c.height - 4 };
    await dragMarquee(page, start, end, 16);

    await expect(page.getByTestId('multi-select-bar')).toBeVisible({ timeout: 5_000 });
    await expect(page.getByTestId('multi-select-count')).toHaveText('已选 3 项');
    await expect(page.getByTestId(`canvas-block-${SECTION_A}`)).toHaveAttribute('data-multi-selected', 'true');
    await expect(page.getByTestId(`canvas-block-${SECTION_B}`)).toHaveAttribute('data-multi-selected', 'true');
    await expect(page.getByTestId(`canvas-block-${SECTION_C}`)).toHaveAttribute('data-multi-selected', 'true');
    await shot(page, '04-marquee-all-three');
  });

  test('sad: a tiny empty-canvas drag (below threshold) selects nothing and shows no marquee', async ({
    page,
  }) => {
    const pid = await seedPage(page, 'empty');
    await openDesigner(page, pid);

    await expect(page.getByTestId(`canvas-block-${SECTION_A}`)).toBeVisible();

    // A 2px nudge on empty canvas: below the start threshold → no marquee, no
    // selection, no batch bar, document stays clean.
    const host = await page.getByTestId('unified-canvas-host').boundingBox();
    expect(host).not.toBeNull();
    const px = { x: host!.x + 6, y: host!.y + 6 };
    await page.mouse.move(px.x, px.y);
    await page.mouse.down();
    await page.mouse.move(px.x + 2, px.y + 2);
    await page.mouse.up();

    await expect(page.getByTestId('marquee-rect')).toHaveCount(0);
    await expect(page.getByTestId('multi-select-bar')).toHaveCount(0);
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('已保存');
    await shot(page, '05-empty-no-select');
  });
});
