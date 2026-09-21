import { test, expect } from '../../fixtures';
import * as XLSX from 'xlsx';
import { readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import * as path from 'node:path';
import { ensureSidebarExpanded, findRowInPaginatedList, clickRowActionByLocator } from '../helpers';
import { clickSidebarPage, cleanupRows, openPgClient, dynamicCreate, readDynamicRecord, queryDynamicRecords, executeCommand, type CreatedRows } from './quote-e2e-helpers';

const WORKBENCH = '/p/bom_conversion_task_pcba_workbench';

test('BOM retry missing source: UI rejection preserves failed task and prior progress', async ({ page }, info) => {
  const created: CreatedRows = { quoteId: '', quoteCode: '', rows: [] };
  try {
    const taskId = await dynamicCreate(page, 'bom_conversion_task_pcba', {
      bom_task_no: `E2E-RETRY-MISSING-${Date.now()}`, bom_task_status: 'failed',
      bom_task_processed_rows: 5, bom_task_error_message: 'Previous processing failure',
    }, created.rows);
    const before = await readDynamicRecord(page, 'bom_conversion_task_pcba', taskId);
    const optimizerErrors: string[] = [];
    let detailNavigations = 0;
    page.on('console', message => {
      if (message.text().includes('Outdated Optimize Dep')) optimizerErrors.push(message.text());
    });
    page.on('request', request => {
      if (request.isNavigationRequest() && request.frame() === page.mainFrame()
          && new URL(request.url()).pathname === `${WORKBENCH}/view/${taskId}`) detailNavigations += 1;
    });
    await page.goto(`${WORKBENCH}/view/${taskId}`);
    await expect(page.getByTestId('status-banner-bom_workbench_task_status')).toContainText('BOM 解析失败', { timeout: 20_000 });
    await expect(page.getByRole('button', { name: '重试转换', exact: true })).toBeVisible();
    const response = page.waitForResponse(r => decodeURIComponent(r.url()).includes('/api/meta/commands/execute/bom:retry_conversion') && r.request().method() === 'POST');
    await page.getByRole('button', { name: '重试转换', exact: true }).click();
    const rejected = await (await response).json();
    expect(String(rejected.code)).not.toBe('0');
    expect(JSON.stringify(rejected)).toContain('缺少原始 BOM 文件');
    expect(await readDynamicRecord(page, 'bom_conversion_task_pcba', taskId)).toEqual(before);
    expect(optimizerErrors, 'first navigation must not invalidate optimized dependencies').toEqual([]);
    expect(detailNavigations, 'no unsolicited document reload during the operator action').toBe(1);
    await page.reload();
    await expect(page.getByTestId('status-banner-bom_workbench_task_status')).toContainText('BOM 解析失败');
    await expect(page.getByRole('button', { name: '重试转换', exact: true })).toBeVisible();
    await info.attach('retry-rejected-browser', { body: await page.screenshot(), contentType: 'image/png' });
    await info.attach('retry-rejected', { body: JSON.stringify({ before, rejected }), contentType: 'application/json' });
  } finally { await cleanupRows(page, created); }
});

test('BOM retry original source: UI requeues real workbook, completes seven rows without duplicates and rejects completed retry', async ({ page }, info) => {
  test.setTimeout(150_000);
  const created: CreatedRows = { quoteId: '', quoteCode: '', rows: [] };
  try {
    const marker = `RETRY${Date.now()}`;
    const account = await executeCommand(page, 'crm:create_account', { crm_acc_name: marker }, undefined, 'create');
    const customerId = String(account.recordId ?? account.pid ?? account.id ?? '');
    expect(customerId).toBeTruthy(); created.rows.push({ model: 'crm_account_common', pid: customerId });
    const project = await executeCommand(page, 'bom:create_project', {
      bom_project_name: marker, bom_project_customer_id: customerId, bom_pcba_code: marker,
      bom_project_library_source: 'excel_current_library',
    }, undefined, 'create');
    const projectId = String(project.recordId ?? project.pid ?? project.projectId ?? '');
    expect(projectId).toBeTruthy(); created.rows.push({ model: 'req_requirement_set_pcba_bom', pid: projectId });
    const workbook = XLSX.utils.book_new();
    XLSX.utils.book_append_sheet(workbook, XLSX.utils.aoa_to_sheet([
      ['物料名称', '规格', '位号', '数量', '封装'],
      ...Array.from({ length: 7 }, (_, i) => ['贴片电阻', '10kΩ ±1%', `R${i + 1}`, 1, '0603']),
    ]), 'BOM');
    const upload = await page.request.post('/api/file/upload', { multipart: { file: {
      name: `${marker}.xlsx`, mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
      buffer: XLSX.write(workbook, { type: 'buffer', bookType: 'xlsx' }),
    } } });
    const uploaded = await upload.json(); expect(upload.ok()).toBe(true);
    expect(String(uploaded.code)).toBe('0'); const fileId = uploaded.data.fileId;
    expect(fileId).toBeTruthy();
    // Prepare a failed-before-processing task; all subsequent parsing/matching uses the real queue.
    // This does not claim recovery from an already committed partial checkpoint.
    const taskId = await dynamicCreate(page, 'bom_conversion_task_pcba', {
      bom_task_no: marker, bom_task_status: 'failed', bom_task_raw_file_id: fileId,
      bom_task_raw_filename: `${marker}.xlsx`, bom_task_customer_id: customerId,
      bom_task_project_id: projectId, bom_task_error_message: 'Retry fixture: failed before processing',
    }, created.rows);
    await page.goto(`${WORKBENCH}/view/${taskId}`);
    await expect(page.getByTestId('status-banner-bom_workbench_task_status')).toContainText('BOM 解析失败', { timeout: 20_000 });
    await expect(page.getByRole('button', { name: '重试转换', exact: true })).toBeVisible();
    const response = page.waitForResponse(r => decodeURIComponent(r.url()).includes('/api/meta/commands/execute/bom:retry_conversion') && r.request().method() === 'POST');
    await page.getByRole('button', { name: '重试转换', exact: true }).click();
    const accepted = await (await response).json(); expect(String(accepted.code)).toBe('0');
    await expect.poll(async () => (await readDynamicRecord(page, 'bom_conversion_task_pcba', taskId)).bom_task_status,
      { timeout: 90_000, intervals: [1000, 2000] }).toBe('completed');
    const task = await readDynamicRecord(page, 'bom_conversion_task_pcba', taskId);
    expect(task.bom_task_raw_file_id).toBe(fileId);
    const lines = await queryDynamicRecords(page, 'bom_standard_line_pcba', [{ fieldName: 'bom_std_task_id', operator: 'EQ', value: taskId }]);
    expect(lines).toHaveLength(7);
    expect(new Set(lines.map(row => row.bom_std_refdes)).size).toBe(7);
    expect(lines.map(row => row.bom_std_refdes).sort()).toEqual(['R1', 'R2', 'R3', 'R4', 'R5', 'R6', 'R7']);
    // Let the live task UI reach its terminal state before testing persisted reload.
    // Database completion alone does not mean the browser's command/poll cycle is done.
    await expect(page.getByTestId('status-banner-bom_workbench_task_status')).toContainText('BOM 匹配已完成', { timeout: 20_000 });
    await page.reload();
    await expect(page.getByTestId('status-banner-bom_workbench_task_status')).toContainText('BOM 匹配已完成');
    await expect(page.getByRole('button', { name: '重试转换', exact: true })).toHaveCount(0);
    // Final workflow telemetry is persisted after apply. Establish the rejection
    // baseline immediately before the command, not during the earlier worker run.
    await expect.poll(async () => {
      const current = await readDynamicRecord(page, 'bom_conversion_task_pcba', taskId);
      return typeof JSON.parse(String(current.bom_task_header_mapping)).performanceDiagnostics?.workflow?.total;
    }, { timeout: 20_000 }).toBe('number');
    const beforeReplay = await readDynamicRecord(page, 'bom_conversion_task_pcba', taskId);
    const performance = JSON.parse(String(beforeReplay.bom_task_header_mapping)).performanceDiagnostics;
    expect(performance.schema).toBe('bom_performance_diagnostics_v3');
    expect(performance.semantic).toMatchObject({ schema: 'bom_semantic_pipeline_metrics_v1' });
    expect(beforeReplay.bom_task_raw_file_id).toBe(fileId);
    expect(beforeReplay.bom_task_export_file_id).toBe(task.bom_task_export_file_id);
    const replay = await page.request.post('/api/meta/commands/execute/bom:retry_conversion', {
      data: { targetRecordPid: taskId, operationType: 'update', payload: {} },
    });
    const rejected = await replay.json(); expect(String(rejected.code)).not.toBe('0');
    expect(JSON.stringify(rejected)).toContain('只有处理失败的任务可以重试');
    expect(await readDynamicRecord(page, 'bom_conversion_task_pcba', taskId)).toEqual(beforeReplay);
    expect(await queryDynamicRecords(page, 'bom_standard_line_pcba', [{ fieldName: 'bom_std_task_id', operator: 'EQ', value: taskId }])).toEqual(lines);
    await info.attach('retry-real-queue-browser', { body: await page.screenshot(), contentType: 'image/png' });
    await info.attach('retry-real-queue', { body: JSON.stringify({ accepted, task, beforeReplay, lines, rejected }), contentType: 'application/json' });
  } finally { await cleanupRows(page, created); }
});

test('BOM retry corrupt source: real queue persists a fresh failure and no output rows', async ({ page }, info) => {
  test.setTimeout(150_000);
  const created: CreatedRows = { quoteId: '', quoteCode: '', rows: [] };
  {
    const marker = `RETRY${Date.now()}`;
    const account = await executeCommand(page, 'crm:create_account', { crm_acc_name: marker }, undefined, 'create');
    const customerId = String(account.recordId ?? account.pid ?? account.id ?? '');
    expect(customerId).toBeTruthy(); created.rows.push({ model: 'crm_account_common', pid: customerId });
    const project = await executeCommand(page, 'bom:create_project', {
      bom_project_name: marker, bom_project_customer_id: customerId, bom_pcba_code: marker,
      bom_project_library_source: 'excel_current_library',
    }, undefined, 'create');
    const projectId = String(project.recordId ?? project.pid ?? project.projectId ?? '');
    expect(projectId).toBeTruthy(); created.rows.push({ model: 'req_requirement_set_pcba_bom', pid: projectId });
    const upload = await page.request.post('/api/file/upload', { multipart: { file: {
      name: `${marker}.xlsx`, mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
      buffer: Buffer.from('This is deliberately not an XLSX ZIP archive.'),
    } } });
    const uploaded = await upload.json(); expect(upload.ok()).toBe(true);
    expect(String(uploaded.code)).toBe('0'); const fileId = uploaded.data.fileId;
    expect(fileId).toBeTruthy();
    // Prepare a failed-before-processing task; all subsequent parsing/matching uses the real queue.
    // This does not claim recovery from an already committed partial checkpoint.
    const taskId = await dynamicCreate(page, 'bom_conversion_task_pcba', {
      bom_task_no: marker, bom_task_status: 'failed', bom_task_raw_file_id: fileId,
      bom_task_raw_filename: `${marker}.xlsx`, bom_task_customer_id: customerId,
      bom_task_project_id: projectId, bom_task_error_message: 'Corrupt source fixture: previous failure',
    }, created.rows);
    await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });
    await ensureSidebarExpanded(page);
    await clickSidebarPage(page, WORKBENCH, /BOM 工作台|Workbench/i);
    const row = await findRowInPaginatedList(page, marker, 20_000);
    await expect(row).toContainText(marker);
    await Promise.all([
      page.waitForURL(url => url.pathname === `${WORKBENCH}/view/${taskId}`),
      clickRowActionByLocator(page, row, 'open_workbench', '打开'),
    ]);
    const response = page.waitForResponse(r => decodeURIComponent(r.url()).includes('/api/meta/commands/execute/bom:retry_conversion') && r.request().method() === 'POST');
    await page.getByRole('button', { name: '重试转换', exact: true }).click();
    const accepted = await (await response).json(); expect(String(accepted.code)).toBe('0');
    // A prior failed fixture must not self-satisfy the assertion: wait for a NEW error
    // written by the real worker after the UI retry has been accepted.
    await expect.poll(async () => {
      const current = await readDynamicRecord(page, 'bom_conversion_task_pcba', taskId);
      return current.bom_task_status === 'failed'
        && Boolean(current.bom_task_completed_at)
        && Boolean(current.bom_task_error_message)
        && current.bom_task_error_message !== 'Corrupt source fixture: previous failure';
    }, { timeout: 60_000, intervals: [500, 1000] }).toBe(true);
    const task = await readDynamicRecord(page, 'bom_conversion_task_pcba', taskId);
    expect(task.bom_task_raw_file_id).toBe(fileId);
    const lines = await queryDynamicRecords(page, 'bom_standard_line_pcba', [{ fieldName: 'bom_std_task_id', operator: 'EQ', value: taskId }]);
    expect(lines).toHaveLength(0);
    await page.reload();
    await expect(page.getByTestId('status-banner-bom_workbench_task_status')).toContainText('BOM 解析失败');
    await expect(page.getByRole('button', { name: '重试转换', exact: true })).toBeVisible();
    expect(task.bom_task_current_phase).toBe('failed');
    await expect(page.getByTestId('status-banner-bom_workbench_task_status')).toContainText('处理失败');
    await expect(page.getByTestId('status-banner-bom_workbench_task_status')).not.toContainText('排队中');
    await info.attach('corrupt-source-worker-failure-browser', { body: await page.screenshot(), contentType: 'image/png' });
    await info.attach('corrupt-source-worker-failure', { body: JSON.stringify({ accepted, task, lines }), contentType: 'application/json' });

  }
});

/**
 * Browser ownership for this synthetic task stops at the presentation seam:
 * an adjustment-required task exposes its evidence panel, but a task with no
 * executable issue-decision rows must not invent a confirmation action.
 *
 * Route selection and command execution are deterministic backend contracts
 * owned by BomImportPreAnalyzerTest, BomImportGatewayDecisionTest and
 * BomImportGatewayHandlersTest. This case does not establish a complete
 * atomic-decision journey; that needs separate real persisted issue decisions.
 */
test.describe('BOM import gateway manual path @smoke', () => {
  test('a task without issue decisions exposes evidence but no fake confirmation', async ({ page }, info) => {
    const created: CreatedRows = { quoteId: '', quoteCode: '', rows: [] };
    const suffix = `${Date.now()}${Math.random().toString(16).slice(2, 8)}`;
    const taskNo = `E2E-GATEWAY-MANUAL-${suffix}`;

    try {
      const taskPid = await dynamicCreate(
        page,
        'bom_conversion_task_pcba',
        {
          bom_task_no: taskNo,
          bom_task_source_package: `manual-path-${suffix}`,
          bom_task_status: 'adjustment_required',
          bom_task_raw_filename: `${taskNo}.xlsx`,
          bom_task_total_rows: 1,
          bom_task_header_mapping: JSON.stringify({
            importGatewayDecision: {
              nextAction: 'CONFIRM_FIELDS',
              questionCount: 0,
              requiresHumanReview: true,
            },
          }),
        },
        created.rows,
      );

      const before = await readDynamicRecord(page, 'bom_conversion_task_pcba', taskPid);
      await page.goto(`${WORKBENCH}/view/${taskPid}`, {
        waitUntil: 'domcontentloaded',
      });

      const openEvidence = page
        .getByRole('button', {
          name: /查看识别依据|View Recognition Details/i,
        })
        .first();
      await expect(openEvidence, 'manual-path evidence entrance').toBeVisible({
        timeout: 30_000,
      });
      await openEvidence.click();
      await expect(
        page
          .getByRole('button', {
            name: /收起识别依据|Hide Recognition Details/i,
          })
          .first(),
        'evidence panel toggles to its open state',
      ).toBeVisible({ timeout: 20_000 });

      await expect(
        page.getByTestId('workbench-action-confirm_import_intent_and_continue'),
        'a header-only synthetic task must not expose a non-executable confirmation action',
      ).toHaveCount(0);
      await expect(
        page.getByText(/确认字段来源|Confirm the source column/i),
        'a task with zero projected questions must not invent source questions',
      ).toHaveCount(0);
      const after = await readDynamicRecord(page, 'bom_conversion_task_pcba', taskPid);
      expect(after).toEqual(before);
      await info.attach('empty-decision-browser', { body: await page.screenshot(), contentType: 'image/png' });
      await info.attach('empty-decision-persistence', { body: JSON.stringify({ before, after }), contentType: 'application/json' });
      await page.getByRole('button', { name: /收起识别依据|Hide Recognition Details/i }).first().click();
      await expect(openEvidence).toBeVisible();
      await expect(page.getByTestId('workbench-action-confirm_import_intent_and_continue')).toHaveCount(0);
      expect(await readDynamicRecord(page, 'bom_conversion_task_pcba', taskPid)).toEqual(before);
    } finally {
      await cleanupRows(page, created);
    }
  });
});

// These states are held fixtures: this verifies operator cancellation and persistence,
// not a race with a live conversion worker (covered separately by queue contracts).
for (const status of ['pending', 'pre_analyzing', 'analysis_ready', 'adjustment_required', 'dry_running', 'plan_ready', 'applying', 'parsing', 'format_exploration_required', 'matching']) {
  test(`BOM gateway cancellation from ${status}: dismiss preserves, confirm persists, terminal replay rejects`, async ({ page }, info) => {
    const created: CreatedRows = { quoteId: '', quoteCode: '', rows: [] };
    try {
      const taskId = await dynamicCreate(page, 'bom_conversion_task_pcba', {
        bom_task_no: `E2E-CANCEL-${Date.now()}-${status}`,
        bom_task_status: status, bom_task_raw_filename: 'cancellation-fixture.xlsx',
      }, created.rows);
      const read = () => readDynamicRecord(page, 'bom_conversion_task_pcba', taskId);
      const before = await read();
      await page.goto(`${WORKBENCH}/view/${taskId}`);
      const cancel = page.getByRole('button', { name: '取消任务', exact: true });
      await expect(cancel).toBeVisible();
      await page.screenshot({ path: info.outputPath(`cancellable-${status}.png`) });
      await cancel.click();
      await expect(page.getByTestId('confirm-dialog')).toBeVisible();
      await page.getByTestId('confirm-cancel').click();
      await expect(page.getByTestId('confirm-dialog')).toHaveCount(0);
      expect(await read()).toEqual(before);
      await cancel.click();
      const response = page.waitForResponse(r => decodeURIComponent(r.url()).includes('/api/meta/commands/execute/bom:cancel_task') && r.request().method() === 'POST');
      await page.getByTestId('confirm-ok').click();
      const accepted = await (await response).json();
      expect(String(accepted.code)).toBe('0');
      await expect.poll(async () => (await read()).bom_task_status).toBe('cancelled');
      const after = await read();
      expect(after.bom_task_completed_at).toBeTruthy();
      expect(after.bom_task_no).toBe(before.bom_task_no);
      await page.reload();
      await expect(page.getByTestId('status-banner-bom_workbench_task_status')).toContainText('BOM 转换已取消');
      await expect(cancel).toHaveCount(0);
      await expect(page.getByRole('button', { name: '重试转换', exact: true })).toHaveCount(0);
      // An API caller cannot bypass the terminal UI and repeat the transition.
      const replay = await page.request.post('/api/meta/commands/execute/bom:cancel_task', {
        data: { targetRecordPid: taskId, operationType: 'update', payload: {} },
      });
      const rejected = await replay.json();
      expect(String(rejected.code)).not.toBe('0');
      expect(await read()).toEqual(after);
      // Exercise the same handler used by a delayed queue delivery after cancellation.
      const late = await page.request.post('/api/meta/commands/execute/bom:process_conversion_task', {
        data: { targetRecordPid: taskId, operationType: 'update', payload: { taskId } },
      });
      const lateBody = await late.json();
      expect(String(lateBody.code)).toBe('0');
      expect((lateBody.data?.data ?? lateBody.data).alreadyCancelled).toBe(true);
      expect(await read()).toEqual(after);
      await page.screenshot({ path: info.outputPath(`cancelled-${status}.png`) });
      await info.attach('cancellation-browser', { path: info.outputPath(`cancelled-${status}.png`), contentType: 'image/png' });
      await info.attach('cancellation-persistence', {
        body: JSON.stringify({ before, accepted, after, rejected, lateBody }), contentType: 'application/json',
      });
    } finally {
      await cleanupRows(page, created);
    }
  });
}



for (const status of ['completed', 'failed', 'cancelled']) {
  test(`BOM terminal ${status}: cancellation entry hidden and direct transition rejected without writes`, async ({ page }, info) => {
    const created: CreatedRows = { quoteId: '', quoteCode: '', rows: [] };
    try {
      const taskId = await dynamicCreate(page, 'bom_conversion_task_pcba', {
        bom_task_no: `E2E-CANCEL-TERMINAL-${Date.now()}-${status}`,
        bom_task_status: status, bom_task_processed_rows: 5,
        bom_task_error_message: 'Terminal-state preservation fixture',
      }, created.rows);
      const before = await readDynamicRecord(page, 'bom_conversion_task_pcba', taskId);
      await page.goto(`${WORKBENCH}/view/${taskId}`);
      await expect(page.getByTestId('status-banner-bom_workbench_task_status')).toBeVisible();
      await expect(page.getByRole('button', { name: '取消任务', exact: true })).toHaveCount(0);
      const response = await page.request.post('/api/meta/commands/execute/bom:cancel_task', {
        data: { targetRecordPid: taskId, operationType: 'update', payload: {} },
      });
      const rejected = await response.json();
      expect(String(rejected.code)).not.toBe('0');
      expect(await readDynamicRecord(page, 'bom_conversion_task_pcba', taskId)).toEqual(before);
      await page.reload();
      await expect(page.getByTestId('status-banner-bom_workbench_task_status')).toBeVisible();
      await expect(page.getByRole('button', { name: '取消任务', exact: true })).toHaveCount(0);
      expect(await readDynamicRecord(page, 'bom_conversion_task_pcba', taskId)).toEqual(before);
      await info.attach('terminal-cancellation-rejection', {
        body: JSON.stringify({ status, before, rejected }), contentType: 'application/json',
      });
      await info.attach('terminal-cancellation-browser', { body: await page.screenshot(), contentType: 'image/png' });
    } finally { await cleanupRows(page, created); }
  });
}


test('BOM UI cancellation wins against stale worker failure through real database locks', async ({ page }, info) => {
  test.setTimeout(90_000);
  const created: CreatedRows = { quoteId: '', quoteCode: '', rows: [] };
  const { client, database } = await openPgClient();
  const pending: Promise<unknown>[] = [];
  try {
    const taskId = await dynamicCreate(page, 'bom_conversion_task_pcba', {
      bom_task_no: `E2E-CANCEL-RACE-${Date.now()}`, bom_task_status: 'analysis_ready',
      bom_task_raw_filename: 'missing-source-race.xlsx', bom_task_processed_rows: 5,
      bom_task_error_message: 'Preserve prior diagnostics when cancelled',
    }, created.rows);
    const before = await readDynamicRecord(page, 'bom_conversion_task_pcba', taskId);
    await page.goto(`${WORKBENCH}/view/${taskId}`);
    await page.getByRole('button', { name: '取消任务', exact: true }).click();
    await expect(page.getByTestId('confirm-dialog')).toBeVisible();
    await client.query('BEGIN');
    await client.query("SET LOCAL idle_in_transaction_session_timeout = '30s'");
    const locked = await client.query('SELECT pid FROM mt_bom_conversion_task_pcba WHERE pid = $1 FOR SHARE', [taskId]);
    expect(locked.rowCount).toBe(1);
    const owner = (await client.query('SELECT pg_backend_pid() AS pid')).rows[0].pid;
    const waitingRequests = async () => {
      await client.query('SELECT pg_stat_clear_snapshot()');
      return (await client.query(`
      WITH RECURSIVE blocked(pid) AS (
        SELECT $1::int
        UNION
        SELECT a.pid FROM pg_stat_activity a JOIN blocked b ON b.pid = ANY(pg_blocking_pids(a.pid))
      ) SELECT a.pid, a.wait_event_type, a.query FROM pg_stat_activity a
        JOIN blocked b ON a.pid = b.pid WHERE a.pid <> $1
    `, [owner])).rows.filter(row => /mt_bom_conversion_task_pcba/i.test(row.query));
    };
    // A SHARE lock blocks the cancellation intent's exclusive target lock.
    // Queue the actual UI cancellation first and prove it is waiting on our row.
    const cancellation = page.waitForResponse(r => decodeURIComponent(r.url()).includes('/api/meta/commands/execute/bom:cancel_task') && r.request().method() === 'POST');
    pending.push(cancellation); void cancellation.catch(() => {});
    await page.getByTestId('confirm-ok').click();
    await expect.poll(async () => (await waitingRequests()).length, { timeout: 10_000 }).toBe(1);
    // Custom background handling does not take the state-transition version SHARE lock.
    // It reads the old task, detects a missing source, then queues its failure CAS.
    const worker = page.request.post('/api/meta/commands/execute/bom:process_conversion_task', {
      data: { targetRecordPid: taskId, operationType: 'update', payload: { taskId } },
    });
    pending.push(worker); void worker.catch(() => {});
    await expect.poll(async () => (await waitingRequests()).length, { timeout: 10_000 }).toBe(2);
    const contention = await waitingRequests();
    await info.attach('concurrent-task-updates', { body: JSON.stringify(contention), contentType: 'application/json' });
    expect(contention.filter(row => /^SELECT row_version[\s\S]*FOR UPDATE$/i.test(row.query))).toHaveLength(1);
    expect(contention.filter(row => /^UPDATE\s/i.test(row.query))).toHaveLength(1);
    await client.query('COMMIT');
    const accepted = await (await cancellation).json();
    const workerResult = await (await worker).json();
    expect(String(accepted.code)).toBe('0');
    expect(String(workerResult.code)).not.toBe('0');
    expect(JSON.stringify(workerResult)).toContain('fileId is required');
    const after = await readDynamicRecord(page, 'bom_conversion_task_pcba', taskId);
    expect(after.bom_task_status).toBe('cancelled');
    expect(after.bom_task_completed_at).toBeTruthy();
    expect(after.bom_task_error_message).toBe(before.bom_task_error_message);
    expect(after.bom_task_processed_rows).toBe(5);
    await page.reload();
    await expect(page.getByTestId('status-banner-bom_workbench_task_status')).toContainText('BOM 转换已取消');
    expect(await readDynamicRecord(page, 'bom_conversion_task_pcba', taskId)).toEqual(after);
    await info.attach('cancel-worker-real-lock-race', {
      body: JSON.stringify({ database, taskId, contention, before, accepted, workerResult, after }), contentType: 'application/json',
    });
    await info.attach('cancel-worker-browser', { body: await page.screenshot(), contentType: 'image/png' });
  } finally {
    await client.query('ROLLBACK').catch(() => {});
    await Promise.allSettled(pending);
    await client.end();
    await cleanupRows(page, created);
  }
});


test('BOM chunk transaction commits before queued UI cancellation and prevents final publication', async ({ page }, info) => {
  test.setTimeout(150_000);
  const created: CreatedRows = { quoteId: '', quoteCode: '', rows: [] };
  const { client, database } = await openPgClient();
  const suffix = `${Date.now()}_${Math.floor(Math.random() * 100000)}`;
  const hook = `e2e_bom_chunk_${suffix}`;
  const lockNamespace = 731505, lockKey = Math.floor(Math.random() * 2000000000);
  const pending: Promise<unknown>[] = [];
  try {
    await client.query("SET lock_timeout = '10s'");
    const marker = `CHUNK${Date.now()}`;
    const account = await executeCommand(page, 'crm:create_account', { crm_acc_name: marker }, undefined, 'create');
    const customerId = String(account.recordId ?? account.pid ?? account.id ?? '');
    expect(customerId).toBeTruthy(); created.rows.push({ model: 'crm_account_common', pid: customerId });
    const project = await executeCommand(page, 'bom:create_project', {
      bom_project_name: marker, bom_project_customer_id: customerId, bom_pcba_code: marker,
      bom_project_library_source: 'excel_current_library',
    }, undefined, 'create');
    const projectId = String(project.recordId ?? project.pid ?? project.projectId ?? '');
    expect(projectId).toBeTruthy(); created.rows.push({ model: 'req_requirement_set_pcba_bom', pid: projectId });
    const workbook = XLSX.utils.book_new();
    XLSX.utils.book_append_sheet(workbook, XLSX.utils.aoa_to_sheet([
      ['物料名称', '规格', '位号', '数量', '封装'],
      ...Array.from({ length: 7 }, (_, i) => ['贴片电阻', '10kΩ ±1%', `R${i + 1}`, 1, '0603']),
    ]), 'BOM');
    const upload = await page.request.post('/api/file/upload', { multipart: { file: {
      name: `${marker}.xlsx`, mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
      buffer: XLSX.write(workbook, { type: 'buffer', bookType: 'xlsx' }),
    } } });
    const uploaded = await upload.json(); expect(upload.ok()).toBe(true);
    expect(String(uploaded.code)).toBe('0'); const fileId = uploaded.data.fileId;
    const taskId = await dynamicCreate(page, 'bom_conversion_task_pcba', {
      bom_task_no: marker, bom_task_status: 'failed', bom_task_raw_file_id: fileId,
      bom_task_raw_filename: `${marker}.xlsx`, bom_task_customer_id: customerId,
      bom_task_project_id: projectId, bom_task_error_message: 'Fixture: retry original workbook',
    }, created.rows);
    expect(taskId).toMatch(/^[A-Z0-9]{26}$/);
    // A temporary, task-specific trigger supplies timing only. It does not alter
    // row values or emulate parsing/matching. The real second batch holds its
    // parent task lock before reaching this checkpoint INSERT.
    await client.query(`CREATE FUNCTION ${hook}() RETURNS trigger LANGUAGE plpgsql AS $body$
      BEGIN
        IF NEW.bom_chunk_task_id = '${taskId}' AND NEW.bom_chunk_no = 2 THEN
          PERFORM pg_advisory_xact_lock(${lockNamespace}, ${lockKey});
        END IF;
        RETURN NEW;
      END;
    $body$`);
    await client.query(`CREATE TRIGGER ${hook} BEFORE INSERT ON mt_bom_conversion_chunk
      FOR EACH ROW EXECUTE FUNCTION ${hook}()`);
    await client.query('SELECT pg_advisory_lock($1::int, $2::int)', [lockNamespace, lockKey]);
    const owner = (await client.query('SELECT pg_backend_pid() AS pid')).rows[0].pid;
    await page.goto(`${WORKBENCH}/view/${taskId}`);
    const retryResponse = page.waitForResponse(r => decodeURIComponent(r.url()).includes('/api/meta/commands/execute/bom:retry_conversion') && r.request().method() === 'POST');
    await page.getByRole('button', { name: '重试转换', exact: true }).click();
    const accepted = await (await retryResponse).json(); expect(String(accepted.code)).toBe('0');
    const jobCode = (accepted.data?.data ?? accepted.data)?.asyncTaskCode;
    expect(jobCode).toBeTruthy();
    const waiting = async (blockingPid: number) => {
      await client.query('SELECT pg_stat_clear_snapshot()');
      return (await client.query('SELECT pid, query, wait_event FROM pg_stat_activity WHERE $1::int = ANY(pg_blocking_pids(pid))', [blockingPid])).rows;
    };
    await expect.poll(async () => (await waiting(owner)).filter(row => row.wait_event === 'advisory').length,
      { timeout: 60_000 }).toBe(1);
    const worker = (await waiting(owner)).find(row => row.wait_event === 'advisory');
    expect(worker.query).toContain('mt_bom_conversion_chunk');
    const firstTask = await readDynamicRecord(page, 'bom_conversion_task_pcba', taskId);
    expect(Number(firstTask.bom_task_processed_rows)).toBe(5);
    const firstLines = await queryDynamicRecords(page, 'bom_standard_line_pcba', [{ fieldName: 'bom_std_task_id', operator: 'EQ', value: taskId }]);
    expect(firstLines).toHaveLength(5);
    const firstChunks = (await client.query('SELECT bom_chunk_no, bom_chunk_status, bom_chunk_actual_rows FROM mt_bom_conversion_chunk WHERE bom_chunk_task_id=$1 ORDER BY bom_chunk_no', [taskId])).rows;
    expect(firstChunks).toEqual([{ bom_chunk_no: 1, bom_chunk_status: 'committed', bom_chunk_actual_rows: 5 }]);
    const assertFirstBatchVisible=async()=>{
      for(const line of firstLines){
        const visibleRow=page.getByRole('row').filter({has:page.getByText(String(line.bom_std_refdes),{exact:true})});
        await expect(visibleRow).toHaveCount(1,{timeout:20_000});
      }
    };
    await assertFirstBatchVisible();
    await info.attach('first-batch-visible',{body:await page.screenshot({fullPage:true}),contentType:'image/png'});
    const processingUrl=page.url();
    await page.goto('/home');
    await page.goto(processingUrl);
    await assertFirstBatchVisible();
    expect((await readDynamicRecord(page,'bom_conversion_task_pcba',taskId)).bom_task_processed_rows).toBe(5);
    await info.attach('first-batch-returned',{body:await page.screenshot({fullPage:true}),contentType:'image/png'});
    await expect(page.getByRole('button', { name: '取消任务', exact: true })).toBeVisible();
    await page.getByRole('button', { name: '取消任务', exact: true }).click();
    const cancelledResponse = page.waitForResponse(r => decodeURIComponent(r.url()).includes('/api/meta/commands/execute/bom:cancel_task') && r.request().method() === 'POST');
    pending.push(cancelledResponse); void cancelledResponse.catch(() => {});
    await page.getByTestId('confirm-ok').click();
    await expect.poll(async () => (await waiting(worker.pid)).filter(row => /mt_bom_conversion_task_pcba/i.test(row.query)).length,
      { timeout: 10_000 }).toBeGreaterThan(0);
    const cancellationWait = await waiting(worker.pid);
    await info.attach('chunk-cancel-contention', {
      body: JSON.stringify({ database, taskId, worker, cancellationWait, firstTask, firstChunks, firstLines }), contentType: 'application/json',
    });
    await client.query('SELECT pg_advisory_unlock($1::int, $2::int)', [lockNamespace, lockKey]);
    const cancelHttp = await cancelledResponse;
    const cancelled = await cancelHttp.json();
    await info.attach('chunk-cancel-response', {
      body: JSON.stringify({ request: cancelHttp.request().postDataJSON(), response: cancelled }),
      contentType: 'application/json',
    });
    expect(String(cancelled.code)).toBe('0');
    await expect.poll(async () => (await client.query('SELECT status FROM ab_async_task WHERE task_code=$1', [jobCode])).rows[0]?.status,
      { timeout: 30_000 }).toBe('completed');
    const job = (await client.query('SELECT status, result_data, error_message FROM ab_async_task WHERE task_code=$1', [jobCode])).rows[0];
    const finalTask = await readDynamicRecord(page, 'bom_conversion_task_pcba', taskId);
    expect(finalTask.bom_task_status).toBe('cancelled');
    expect(Number(finalTask.bom_task_processed_rows)).toBe(7);
    expect(finalTask.bom_task_export_file_id ?? '').toBe('');
    const finalLines = await queryDynamicRecords(page, 'bom_standard_line_pcba', [{ fieldName: 'bom_std_task_id', operator: 'EQ', value: taskId }]);
    expect(finalLines).toHaveLength(7);
    expect(new Set(finalLines.map(row => row.bom_std_refdes)).size).toBe(7);
    const finalChunks = (await client.query('SELECT bom_chunk_no, bom_chunk_status, bom_chunk_actual_rows FROM mt_bom_conversion_chunk WHERE bom_chunk_task_id=$1 ORDER BY bom_chunk_no', [taskId])).rows;
    expect(finalChunks).toEqual([
      { bom_chunk_no: 1, bom_chunk_status: 'committed', bom_chunk_actual_rows: 5 },
      { bom_chunk_no: 2, bom_chunk_status: 'committed', bom_chunk_actual_rows: 2 },
    ]);
    await page.reload();
    await expect(page.getByTestId('status-banner-bom_workbench_task_status')).toContainText('BOM 转换已取消');
    expect(await readDynamicRecord(page, 'bom_conversion_task_pcba', taskId)).toEqual(finalTask);
    await page.screenshot({ path: info.outputPath('chunk-cancelled.png') });
    await info.attach('chunk-cancel-browser', { path: info.outputPath('chunk-cancelled.png'), contentType: 'image/png' });
    await info.attach('chunk-cancel-committed-result', {
      body: JSON.stringify({ accepted, cancelled, job, finalTask, finalChunks, finalLines }), contentType: 'application/json',
    });
  } finally {
    await client.query('SELECT pg_advisory_unlock($1::int, $2::int)', [lockNamespace, lockKey]).catch(() => {});
    await Promise.allSettled(pending);
    try {
      await client.query(`DROP TRIGGER IF EXISTS ${hook} ON mt_bom_conversion_chunk`);
      await client.query(`DROP FUNCTION IF EXISTS ${hook}()`);
    } finally {
      await client.end();
      await cleanupRows(page, created);
    }
  }
});


  test('B05-01 first upload entry surfaces the first chunk of five before later batches finish', async ({ page }, info) => {
    test.setTimeout(180_000);
    const { client, database } = await openPgClient();
    const suffix = `${Date.now()}${Math.random().toString(16).slice(2, 6)}`;
    const marker = `B0501${suffix}`;
    const lockKey = Math.floor(Math.random() * 2000000000);
    const created: CreatedRows = { quoteId: '', quoteCode: '', rows: [] };
    try {
      const account = await executeCommand(page, 'crm:create_account', { crm_acc_name: marker }, undefined, 'create');
      const customerId = String(account.recordId ?? account.pid ?? account.id ?? '');
      expect(customerId).toBeTruthy();
      created.rows.push({ model: 'crm_account_common', pid: customerId });
      const project = await executeCommand(page, 'bom:create_project', {
        bom_project_customer_id: customerId, bom_project_name: marker,
        bom_pcba_code: marker, bom_project_library_source: 'excel_current_library',
      }, undefined, 'create');
      const projectId = String(project.recordId ?? project.pid ?? project.projectId ?? '');
      expect(projectId).toBeTruthy();
      created.rows.push({ model: 'req_requirement_set_pcba_bom', pid: projectId });

      await page.goto('/home', { waitUntil: 'domcontentloaded' });
      await clickSidebarPage(page, WORKBENCH, /BOM 工作台|Workbench/i);
      await page.getByTestId('toolbar-btn-upload_bom').click();
      for (const [field, id] of [['bom_task_customer_id', customerId], ['bom_task_project_id', projectId]] as const) {
        const trigger = page.getByTestId(`select-trigger-${field}`);
        await trigger.click();
        await page.locator(`[role="option"][data-value="${id}"]`).click();
        await expect(trigger).toContainText(marker);
      }

      // 12 rows → chunks of 5/5/2; a BEFORE-INSERT trigger parks chunk 2 on an
      // advisory lock so the FIRST batch (5 committed, 5 UI-visible) can be
      // asserted deterministically while the second batch is still queued.
      const workbook = XLSX.utils.book_new();
      XLSX.utils.book_append_sheet(workbook, XLSX.utils.aoa_to_sheet([
        ['物料名称', '规格', '位号', '数量', '封装'],
        ...Array.from({ length: 12 }, (_, i) => ['贴片电阻', '10kΩ ±1%', `R${i + 1}`, 1, '0603']),
      ]), 'BOM');
      const uploadPromise = page.waitForResponse(
        (r) => r.url().includes('/api/file/upload') && r.request().method() === 'POST',
        { timeout: 30_000 },
      );
      await page.getByTestId('form-field-bom_task_raw_file_id').locator('input[type="file"]')
        .setInputFiles({ name: `${marker}.xlsx`, mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', buffer: XLSX.write(workbook, { type: 'buffer', bookType: 'xlsx' }) });
      const uploaded = await (await uploadPromise).json().catch(() => ({}));
      expect(String((uploaded as { code?: unknown }).code ?? '0')).toBe('0');

      const startRequests = page.waitForResponse(
        (r) => r.url().includes('/api/meta/commands/execute/bom:start_conversion') && r.request().method() === 'POST',
        { timeout: 30_000 },
      );
      await page.getByTestId('form-btn-start_conversion').click();
      const start = await (await startRequests).json().catch(() => ({}));
      expect(String((start as { code?: unknown }).code ?? '0')).toBe('0');
      await page.waitForURL(/\/p\/bom_conversion_task_pcba_workbench\/view\/[^/?#]+/, { timeout: 30_000 });
      const taskId = new URL(page.url()).pathname.split('/').pop()!;

      const hook = `e2e_b05_${suffix}`;
      await client.query(`CREATE FUNCTION ${hook}() RETURNS trigger LANGUAGE plpgsql AS $body$
        BEGIN
          IF NEW.bom_chunk_task_id = '${taskId}' AND NEW.bom_chunk_no = 2 THEN
            PERFORM pg_advisory_xact_lock(${lockKey});
          END IF;
          RETURN NEW;
        END;
      $body$`);
      await client.query(`CREATE TRIGGER ${hook} BEFORE INSERT ON mt_bom_conversion_chunk
        FOR EACH ROW EXECUTE FUNCTION ${hook}()`);
      await client.query('SELECT pg_advisory_lock($1)', [lockKey]);
      const owner = (await client.query('SELECT pg_backend_pid() AS pid')).rows[0].pid;

      await expect.poll(async () => Number((await readDynamicRecord(page, 'bom_conversion_task_pcba', taskId)).bom_task_processed_rows), { timeout: 60_000 }).toBe(5);
      const firstLines = await queryDynamicRecords(page, 'bom_standard_line_pcba', [{ fieldName: 'bom_std_task_id', operator: 'EQ', value: taskId }]);
      expect(firstLines, 'first batch exposes five rows without waiting for the full file').toHaveLength(5);
      await info.attach('b0501-first-batch-browser', { body: await page.screenshot({ fullPage: true }), contentType: 'image/png' });

      await client.query('SELECT pg_advisory_unlock($1)', [lockKey]);
      await expect.poll(async () => (await readDynamicRecord(page, 'bom_conversion_task_pcba', taskId)).bom_task_status, { timeout: 90_000, intervals: [1000, 2000] }).toBe('completed');
      const allLines = await queryDynamicRecords(page, 'bom_standard_line_pcba', [{ fieldName: 'bom_std_task_id', operator: 'EQ', value: taskId }]);
      expect(allLines, 'all twelve rows land exactly once').toHaveLength(12);
      expect(new Set(allLines.map((r) => String(r.bom_std_refdes))).size).toBe(12);
      await info.attach('b0501-completion-persistence', { body: JSON.stringify({ taskId, rows: allLines.length }), contentType: 'application/json' });
    } finally {
      await client.query(`DROP TRIGGER IF EXISTS e2e_b05_${suffix} ON mt_bom_conversion_chunk`).catch(() => {});
      await client.query(`DROP FUNCTION IF EXISTS e2e_b05_${suffix}()`).catch(() => {});
      await client.end().catch(() => {});
      await cleanupRows(page, created);
    }
  });
test('E15 upload UI isolates exactly three unsafe rows and completes fourteen without manual choices', async ({ page }, info) => {
  test.setTimeout(180_000);
  // This source-extraction contract does not certify material ranking against
  // the separately approved Kingdee snapshot.
  const file = path.join(process.env.BOM_GOLDEN_VERIFY_DIR
    || '/Users/ghj/work/auraboot/auraboot-enterprise/doa/jiejia_tech/bom-verify', 'e15-BOM.xlsx');
  const sourceSha256 = createHash('sha256').update(readFileSync(file)).digest('hex');
  expect(sourceSha256).toBe('f68411ed60b39db27e5b12399c0b2a159959efcb3b71aefcef7f8bf124e05980');
  const marker = `E15-${Date.now()}`;
  const customer = await executeCommand(page, 'crm:create_account', { crm_acc_name: marker }, undefined, 'create');
  const customerId = String(customer.recordId ?? customer.pid ?? customer.id ?? '');
  expect(customerId).toBeTruthy();
  const project = await executeCommand(page, 'bom:create_project', {
    bom_project_customer_id: customerId, bom_project_name: marker,
    bom_pcba_code: marker, bom_project_library_source: 'excel_current_library',
  }, undefined, 'create');
  const projectId = String(project.recordId ?? project.pid ?? project.projectId ?? '');
  expect(projectId).toBeTruthy();
  await page.goto('/home');
  await ensureSidebarExpanded(page);
  await clickSidebarPage(page, WORKBENCH, /BOM 工作台|Workbench/i);
  await page.getByTestId('toolbar-btn-upload_bom').click();
  let invalidStartRequests=0;
  const trackInvalidStart=(request:any)=>{
    if(request.method()==='POST' && decodeURIComponent(request.url()).includes('/api/meta/commands/execute/bom:start_conversion')) invalidStartRequests+=1;
  };
  page.on('request',trackInvalidStart);
  await page.getByTestId('form-btn-start_conversion').click();
  await expect(page.getByTestId('form-field-bom_task_raw_file_id')).toContainText('请上传原始 BOM 文件');
  await expect(page.getByTestId('form-field-bom_task_raw_file_id')).toContainText('100MB');
  expect(invalidStartRequests).toBe(0);
  expect(await queryDynamicRecords(page,'bom_conversion_task_pcba',[{fieldName:'bom_task_customer_id',operator:'EQ',value:customerId}])).toHaveLength(0);
  page.off('request',trackInvalidStart);
  await info.attach('upload-required-fields',{body:await page.screenshot(),contentType:'image/png'});
  for (const [field, id] of [['bom_task_customer_id', customerId], ['bom_task_project_id', projectId]]) {
    const trigger = page.getByTestId(`select-trigger-${field}`);
    await trigger.click();
    await page.locator(`[role="option"][data-value="${id}"]`).click();
    await expect(trigger).toContainText(marker);
  }
  const upload = page.waitForResponse(r => r.url().includes('/api/file/upload') && r.request().method() === 'POST');
  const field = page.getByTestId('form-field-bom_task_raw_file_id');
  await field.locator('input[type="file"]').first().setInputFiles(file);
  const uploaded = await upload;
  expect(uploaded.ok()).toBe(true);
  const uploadBody=await uploaded.json();
  expect(String(uploadBody.code)).toBe('0');
  const uploadedFileId=String(uploadBody.data?.fileId ?? '');
  expect(uploadedFileId).toBeTruthy();
  await expect(field).toContainText('e15-BOM.xlsx');
  const start = page.waitForResponse(r => decodeURIComponent(r.url()).includes('/api/meta/commands/execute/bom:start_conversion') && r.request().method() === 'POST');
  let startRequests=0, releaseStart!:()=>void;
  const waitingStart=new Promise<void>(resolve=>{releaseStart=resolve;});
  const startUrl=(url:URL)=>decodeURIComponent(url.toString()).includes('/api/meta/commands/execute/bom:start_conversion');
  await page.route(startUrl,async route=>{
    startRequests+=1;
    await waitingStart;
    await route.continue();
  });
  try {
    const submit=page.getByTestId('form-btn-start_conversion');
    await submit.click();
    await expect.poll(()=>startRequests).toBe(1);
    await expect(submit).toBeDisabled();
    await submit.dblclick({force:true});
    expect(startRequests).toBe(1);
    await info.attach('upload-submission-pending',{body:await page.screenshot(),contentType:'image/png'});
  } finally {releaseStart();}
  expect(String((await (await start).json()).code)).toBe('0');
  await page.unroute(startUrl);
  await page.waitForURL(/\/p\/bom_conversion_task_pcba_workbench\/view\/[^/?#]+/, { timeout: 30_000 });
  const taskId = new URL(page.url()).pathname.split('/').pop()!;
  await expect.poll(async () => (await readDynamicRecord(page, 'bom_conversion_task_pcba', taskId)).bom_task_status,
    { timeout: 90_000, intervals: [1000, 2000] }).toBe('completed');
  const task = await readDynamicRecord(page, 'bom_conversion_task_pcba', taskId);
  expect(task).toMatchObject({bom_task_customer_id:customerId,bom_task_project_id:projectId,bom_task_raw_file_id:uploadedFileId});
  expect(task.bom_task_edited_after_completion).toBe(false);
  expect(startRequests).toBe(1);
  const customerTasks=await queryDynamicRecords(page,'bom_conversion_task_pcba',[{fieldName:'bom_task_customer_id',operator:'EQ',value:customerId}]);
  expect(customerTasks.map(t=>t.pid)).toEqual([taskId]);
  const mapping = JSON.parse(String(task.bom_task_header_mapping));
  expect(mapping.importGatewayDecision).toMatchObject({ requiresHumanReview: false,
    questionCount: 0, requiredIssueCount: 0, llmEligible: false, nextAction: 'COMPLETED' });
  const plans = mapping.parsePlanV2.fieldPlans;
  expect(plans.find((p: any) => p.standardField === 'package').sourceColumnIds[0].header).toBe('Footprint');
  expect(plans.find((p: any) => p.standardField === 'qty').sourceColumnIds[0].header).toBe('Quantity');
  const rows = await queryDynamicRecords(page, 'bom_standard_line_pcba', [{ fieldName: 'bom_std_task_id', operator: 'EQ', value: taskId }]);
  expect(rows).toHaveLength(17);
  const matches = await queryDynamicRecords(page, 'bom_match_result_pcba', [{ fieldName: 'bom_mr_task_id', operator: 'EQ', value: taskId }]);
  expect(matches).toHaveLength(17);
  const isolated = rows.filter(r => ['mismatch_refdes_qty', 'field_source_conflict'].includes(String(r.bom_std_reason_code)));
  expect(isolated).toHaveLength(3);
  expect(isolated.map(r => String(r.bom_std_refdes).replace(/[，\s]/g, ',').replace(/,+/g, ',')).sort()).toEqual(['D1,D2', 'U2', 'U3']);
  expect(isolated.every(r => !r.bom_std_candidate_codes)).toBe(true);
  const isolatedIds=new Set(isolated.map(r=>r.pid));
  const isolatedMatches=matches.filter(m=>isolatedIds.has(m.bom_mr_std_item_id));
  expect(isolatedMatches).toHaveLength(3);
  const mismatchIds = new Set(
    isolated.filter(r => r.bom_std_reason_code === 'mismatch_refdes_qty').map(r => r.pid),
  );
  const conflictIds = new Set(
    isolated.filter(r => r.bom_std_reason_code === 'field_source_conflict').map(r => r.pid),
  );
  expect(
    isolatedMatches
      .filter(m => mismatchIds.has(m.bom_mr_std_item_id))
      .every(m => m.bom_mr_match_state === 'none' && m.bom_mr_evidence_state === 'missing' && m.bom_mr_reason),
  ).toBe(true);
  expect(
    isolatedMatches
      .filter(m => conflictIds.has(m.bom_mr_std_item_id))
      .every(m => m.bom_mr_match_state === 'conflict' && m.bom_mr_evidence_state === 'conflict' && m.bom_mr_reason),
  ).toBe(true);
  expect(rows.filter(r => !isolated.includes(r))).toHaveLength(14);
  await expect(page.getByTestId('status-banner-bom_workbench_completed_unresolved_warning')).toContainText('有部分行未形成可确认结果');
  await page.reload();
  await expect(page.getByTestId('status-banner-bom_workbench_completed_unresolved_warning')).toBeVisible();
  await expect(page.getByTestId('workbench-action-confirm_import_intent_and_continue')).toHaveCount(0);
  await expect(page.getByTestId('table-grouped-radio')).toHaveCount(0);
  expect(await queryDynamicRecords(page, 'bom_standard_line_pcba', [{ fieldName: 'bom_std_task_id', operator: 'EQ', value: taskId }])).toEqual(rows);
  await page.goto(WORKBENCH);
  const listedTask=await findRowInPaginatedList(page,String(task.bom_task_no));
  await expect(listedTask).toBeVisible();
  await clickRowActionByLocator(page,listedTask,'open_workbench','打开');
  await page.waitForURL(new RegExp(`/view/${taskId}`));
  await expect(page.getByTestId('status-banner-bom_workbench_completed_unresolved_warning')).toBeVisible();
  expect((await readDynamicRecord(page,'bom_conversion_task_pcba',taskId)).bom_task_raw_file_id).toBe(uploadedFileId);
  await expect(page.getByText('已同步',{exact:true})).toBeVisible();
  await info.attach('e15-isolation-browser', { body: await page.screenshot(), contentType: 'image/png' });
  await info.attach('e15-isolation-persistence', { body: JSON.stringify({ sourceSha256, task, rows, isolated, matches }), contentType: 'application/json' });
  const editedLine=rows.find(r=>!isolatedIds.has(r.pid))!;
  expect(editedLine).toBeTruthy();
  if (typeof editedLine.pid !== 'string' || !editedLine.pid) throw new Error('Edited standard line must have a persisted PID');
  const changedQty=Number(editedLine.bom_std_qty)+1;
  expect(Number.isFinite(changedQty)).toBe(true);
  await executeCommand(page,'bom:update_standard_item',{pid:editedLine.pid,bom_std_qty:changedQty},editedLine.pid,'update');
  const dirtyTask=await readDynamicRecord(page,'bom_conversion_task_pcba',taskId);
  expect(dirtyTask.bom_task_edited_after_completion).toBe(true);
  expect(dirtyTask.bom_task_export_file_id).toBe(task.bom_task_export_file_id);
  await page.reload();
  await expect(page.getByText('有变更',{exact:true})).toBeVisible();
  await info.attach('export-dirty-browser',{body:await page.screenshot(),contentType:'image/png'});
  const downloadPromise=page.waitForEvent('download',{timeout:45_000});
  const regeneratePromise=page.waitForResponse(r=>decodeURIComponent(r.url()).includes('/api/meta/commands/execute/bom:regenerate_export')&&r.request().method()==='POST');
  await page.getByRole('button',{name:'重新生成并下载',exact:true}).click();
  expect(String((await(await regeneratePromise).json()).code)).toBe('0');
  const download=await downloadPromise;
  const exportPath=info.outputPath('e15-export-after-edit.xlsx');
  await download.saveAs(exportPath);
  expect(XLSX.read(readFileSync(exportPath),{type:'buffer'}).SheetNames).toEqual(['BOM','变更记录','转换明细']);
  await info.attach('e15-export-after-edit',{path:exportPath,contentType:'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'});
  const syncedTask=await readDynamicRecord(page,'bom_conversion_task_pcba',taskId);
  expect(syncedTask.bom_task_edited_after_completion).toBe(false);
  expect(syncedTask.bom_task_export_file_id).toBeTruthy();
  expect(syncedTask.bom_task_export_file_id).not.toBe(task.bom_task_export_file_id);
  expect(Number((await readDynamicRecord(page,'bom_standard_line_pcba',editedLine.pid)).bom_std_qty)).toBe(changedQty);
  await page.reload();
  await expect(page.getByText('已同步',{exact:true})).toBeVisible();
  await info.attach('export-synced-browser',{body:await page.screenshot(),contentType:'image/png'});
  await info.attach('export-state-lifecycle',{body:JSON.stringify({task,dirtyTask,syncedTask,editedLineId:editedLine.pid,changedQty}),contentType:'application/json'});
});
