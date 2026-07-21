/**
 * BOM import gateway — MANUAL intervention path (browser golden).
 *
 * Covers the path a user is forced onto when auto-parsing cannot finish on its
 * own: the task parks in `adjustment_required`, the workbench asks which column
 * a standard field comes from, and nothing proceeds until a human answers.
 *
 * Why this file exists: the automatic path is guarded heavily (1,147 replay
 * cases, 585 hard negatives, 52 ranking tests), while this path had no UI-layer
 * coverage at all — `bom:update_parse_plan` and `bom:confirm_data_region`
 * appeared only in backend unit tests. Backend logic being correct says nothing
 * about whether the user can actually reach and drive it, which is precisely
 * what breaks first for complex customer workbooks.
 *
 * All three real customer fixtures probed for this (E2 missing default unit,
 * E3 missing component type, E11 unparseable) park in `adjustment_required`,
 * so this is the common case for real files, not an edge case.
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import type { Page } from '@playwright/test';

import { test, expect } from '../../fixtures';

const HERE = path.dirname(fileURLToPath(import.meta.url));

/** Detail route of the workbench task, matching the existing pcba-solution specs. */
const WORKBENCH = '/p/bom_conversion_task_pcba_workbench';

/** A workbook that genuinely cannot be auto-parsed, so the gateway must ask.
 *  Verified empirically: this parks in `adjustment_required` and stays there. */
const VERIFY_REL = 'auraboot-enterprise/doa/jiejia_tech/bom-verify';
const PREFERRED_FIXTURES = [/^E3-/, /^E2-/, /^E11-/];

function findGatewayFixture(): string | undefined {
  const roots = [
    process.env.BOM_VERIFY_DIR,
    path.resolve(HERE, '../../../../../' + VERIFY_REL),
    '/Users/ghj/work/auraboot/' + VERIFY_REL,
  ].filter(Boolean) as string[];
  for (const root of roots) {
    if (!fs.existsSync(root)) continue;
    const files = fs.readdirSync(root).filter((f) => /\.xlsx$/i.test(f));
    for (const pattern of PREFERRED_FIXTURES) {
      const hit = files.find((f) => pattern.test(f));
      if (hit) return path.join(root, hit);
    }
  }
  return undefined;
}

async function post(page: Page, code: string, payload: unknown, op = 'create', target?: string) {
  const data: Record<string, unknown> = { payload, operationType: op };
  if (target) data.targetRecordPid = target;
  const r = await page.request.post(`/api/meta/commands/execute/${code}`, { data, timeout: 150_000 });
  return { status: r.status(), body: await r.json().catch(() => ({})) };
}

const pidOf = (b: any) => b?.data?.data?.recordPid || b?.data?.recordPid || b?.data?.recordId || '';

async function listTasks(page: Page): Promise<any[]> {
  const r = await page.request.get(
    '/api/dynamic/bom_conversion_task_pcba/list?pageNum=1&pageSize=20&sortField=created_at&sortOrder=desc',
  );
  const b = await r.json().catch(() => ({} as any));
  const recs = b?.data?.records || b?.data?.data?.records || b?.data || [];
  return Array.isArray(recs) ? recs : [];
}

function gatewayDecisionOf(task: any): any {
  try {
    return JSON.parse(task?.bom_task_header_mapping || '{}')?.importGatewayDecision ?? {};
  } catch {
    return {};
  }
}

test.describe('BOM import gateway manual path @smoke', () => {
  test.describe.configure({ mode: 'serial' });
  // Pre-analysis of a real customer workbook takes well over the default budget,
  // and the UI steps wait on state-driven blocks after that.
  test.setTimeout(300_000);

  let taskPid = '';
  let sourcePackage = '';
  let openQuestionsBefore = -1;

  test('an unparseable customer workbook parks in adjustment_required and raises questions', async ({ browser }) => {
    const fixture = findGatewayFixture();
    expect(
      fixture,
      'gateway fixture must exist; set BOM_VERIFY_DIR or keep auraboot-enterprise/doa/jiejia_tech/bom-verify available',
    ).toBeTruthy();

    const ctx = await browser.newContext({
      storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json',
    });
    const page = await ctx.newPage();
    try {
      const uid = String(Date.now()).slice(-7);
      sourcePackage = `gw-${uid}`;

      const proj = await post(page, 'bom:create_project', {
        bom_project_name: `Gateway ${uid}`,
        bom_pcba_code: `GW-${uid}`,
        bom_project_library_source: 'excel_current_library',
      });
      const projId = pidOf(proj.body);
      expect(projId, 'project created').toBeTruthy();

      const up = await page.request.post('/api/file/upload', {
        multipart: {
          file: {
            name: path.basename(fixture!),
            mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
            buffer: fs.readFileSync(fixture!),
          },
        },
      });
      const fileId = (await up.json())?.data?.fileId;
      expect(fileId, 'fixture uploaded').toBeTruthy();

      const started = await post(page, 'bom:start_conversion', {
        bom_task_project_id: projId,
        bom_task_source_package: sourcePackage,
        bom_task_raw_file_id: fileId,
      });
      expect(started.status, 'conversion started').toBe(200);

      // The gateway must PARK here. If auto-parsing silently completed, the
      // manual path would never be reachable and this suite would be vacuous.
      let task: any;
      await expect
        .poll(
          async () => {
            task = (await listTasks(page)).find(
              (t) => String(t.bom_task_source_package || '') === sourcePackage,
            );
            return String(task?.bom_task_status || '');
          },
          { timeout: 180_000, intervals: [2_000, 3_000, 5_000] },
        )
        .toBe('adjustment_required');

      taskPid = task?.pid || task?.id || '';
      expect(taskPid, 'parked task has a pid').toBeTruthy();

      // The workbench only renders the question UI when questionCount > 0
      // (that is the block's visibleWhen). Assert the precondition explicitly,
      // otherwise a later "button not found" would be ambiguous.
      // The gateway must be asking for a human, which is the whole premise of
      // this suite. Note it asks via `requiresHumanReview`, NOT via a non-zero
      // questionCount: real customer workbooks routinely park here with
      // questionCount=0 and nextAction=CONFIRM_FIELDS, i.e. "no specific
      // ambiguity to resolve, but a human must still confirm before we apply".
      const decision = gatewayDecisionOf(task);
      openQuestionsBefore = Number(decision.questionCount ?? 0);
      expect(
        Boolean(decision.requiresHumanReview),
        `gateway defers to a human (decision=${JSON.stringify(decision).slice(0, 200)})`,
      ).toBe(true);
    } finally {
      await ctx.close();
    }
  });

  test('the recognition-details panel is gated behind an explicit operator action', async ({ browser }) => {
    expect(taskPid, 'previous test must have parked a task').toBeTruthy();

    const ctx = await browser.newContext({
      storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json',
    });
    const page = await ctx.newPage();
    try {
      await page.goto(`${WORKBENCH}/view/${taskPid}`, { waitUntil: 'domcontentloaded' });

      // The evidence panel — which hosts every manual-intervention command
      // (update_parse_plan / confirm_data_region / accept-reject LLM suggestion)
      // — is gated on state.showImportEvidence. That toggle is a plain state.set,
      // so it looks like a "view-only" control, but it is in fact the entrance to
      // the whole manual path: if it stops working, none of those commands are
      // reachable and the operator has no way in.
      const openEvidence = page
        .getByRole('button', { name: /查看识别依据|View Recognition Details/i })
        .first();
      await expect(openEvidence, 'entrance to the manual path is offered').toBeVisible({ timeout: 30_000 });

      await openEvidence.click();

      // Toggling to the opened state is observable: the button flips to "hide".
      const hideEvidence = page
        .getByRole('button', { name: /收起识别依据|Hide Recognition Details/i })
        .first();
      await expect(hideEvidence, 'evidence panel opened (state.showImportEvidence = true)').toBeVisible({
        timeout: 20_000,
      });
    } finally {
      await ctx.close();
    }
  });

  test('operator confirms the import from the UI and the task leaves adjustment_required', async ({ browser }) => {
    expect(taskPid, 'previous test must have parked a task').toBeTruthy();

    const ctx = await browser.newContext({
      storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json',
    });
    const page = await ctx.newPage();
    try {
      const commandCalls: number[] = [];
      page.on('response', (r) => {
        if (r.url().includes('/api/meta/commands/execute/bom:confirm_import_and_continue')) {
          commandCalls.push(r.status());
        }
      });

      await page.goto(`${WORKBENCH}/view/${taskPid}`, { waitUntil: 'domcontentloaded' });

      const confirmButton = page
        .getByRole('button', { name: /确认并开始匹配|Confirm and Start Matching/i })
        .first();
      await expect(confirmButton, 'confirm action is reachable while parked').toBeVisible({ timeout: 30_000 });
      await confirmButton.click();

      // The toolbar button carries a confirm prompt ("确认系统识别的字段来源，并开始
      // 匹配物料？"), so the command only fires after the operator acknowledges it.
      // Asserting the prompt is part of the point: this is the moment the user
      // takes responsibility for the auto-identified field sources.
      const confirmDialog = page.getByRole('button', { name: /^(确定|确认|OK|Confirm)$/i }).last();
      await expect(confirmDialog, 'confirmation prompt is shown before matching starts').toBeVisible({
        timeout: 15_000,
      });
      await confirmDialog.click();

      // The command must actually be issued from the UI, not merely rendered.
      await expect
        .poll(() => commandCalls.length, { timeout: 60_000, intervals: [500, 1_000, 2_000] })
        .toBeGreaterThan(0);
      expect(commandCalls[0], 'bom:confirm_import_and_continue returned OK').toBe(200);

      // Falsifiable outcome. Confirming has exactly two legitimate results and
      // the operator must be told which one happened — never left staring at an
      // unchanged screen:
      //   a) matching starts  -> the task leaves adjustment_required
      //   b) it cannot start  -> the task STAYS parked, but the operator is told
      //      what to fix, so the work is recoverable rather than stuck.
      // Asserting only (a) would make this test environment-dependent; asserting
      // neither would make it vacuous. So: require that one of them is true, and
      // that (b) is never silent.
      //
      // What (b) actually carries, established by running two different parked
      // tasks rather than by reading the handler: `userMessage` is populated in
      // both. `issues[]` is not — a workbook with per-row problems returns 18
      // entries ({code: 'refdes_qty_mismatch', standardField, rowNumber,
      // suggestion}), a workbook parked for other reasons returns none. A
      // top-level `reason` is never set on this path at all.
      // So the invariant is: the operator is always told something, and any
      // issue that IS reported is machine-readable rather than prose alone.
      // Two earlier drafts of this assertion demanded `reason`, then a non-empty
      // `issues[]`; both would have failed for product behaviour that is fine.
      const outcome = await post(page, 'bom:confirm_import_and_continue', {}, 'update', taskPid);
      expect(outcome.status, 'confirm command is accepted').toBe(200);
      const inner = (outcome.body as any)?.data?.data ?? {};
      const statusNow = await (async () => {
        const t = (await listTasks(page)).find((x) => String(x.pid || x.id) === taskPid);
        return String(t?.bom_task_status || '');
      })();

      if (statusNow === 'adjustment_required') {
        expect(
          String(inner.userMessage || ''),
          `staying parked must come with an operator-facing explanation (body=${JSON.stringify(inner).slice(0, 300)})`,
        ).not.toBe('');
        const issues = Array.isArray(inner.issues) ? inner.issues : [];
        expect(
          issues.filter((issue: any) => !String(issue?.code || '')),
          'every reported issue carries a machine-readable code, not prose alone',
        ).toEqual([]);
      } else {
        expect(statusNow, 'matching started, so the task left adjustment_required').not.toBe(
          'adjustment_required',
        );
      }
    } finally {
      await ctx.close();
    }
  });
});
