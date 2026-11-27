import type { Page } from '@playwright/test';
import { test, expect } from '../../fixtures';
import { openQuoteDetailFromList, seedFixedCountQuote, queryDynamicRecords } from './quote-e2e-helpers';

const drill = 'M48\nMETRIC\nT01C0.800\n%\nT01\nX0.000Y0.000\nX1.500Y0.000\nM30\n';

/** Execute the existing count-scope form; API reads only observe the resulting task. */
async function dispatchCompute(page: Page, quoteId: string, payload: Record<string, unknown>) {
  const mode = payload.count_hole_mode === 'default' ? 'pads' : String(payload.count_hole_mode);
  await page.getByTestId('toolbar-btn-count_process').click();
  await expect(page.getByTestId('form-dialog')).toBeVisible();
  await page.getByTestId('form-dialog-field-count_hole_mode').selectOption(mode);
  if (mode === 'selected') {
    await page.getByTestId('form-dialog-field-count_drill_text').setInputFiles({
      name: 'billing-holes.drl', mimeType: 'text/plain', buffer: Buffer.from(String(payload.count_drill_text)),
    });
  }
  const requested = page.waitForResponse(response => response.request().method() === 'POST'
    && response.url().includes('/api/meta/commands/execute/qo_quote_common:compute_process_fee'));
  await page.getByTestId('form-dialog-submit').click();
  const response = await requested;
  expect(response.ok()).toBe(true);
  const request = response.request().postDataJSON();
  expect(request.targetRecordPid ?? request.targetRecordId).toBe(quoteId);
  expect(request.payload.count_hole_mode).toBe(mode);
  if (mode === 'selected') {
    expect(request.payload.count_drill_text).toBe(payload.count_drill_text);
    expect(request.payload.count_drill_filename).toBe('billing-holes.drl');
  }
  const body = await response.json();
  expect(String(body.code)).toBe('0');
  const receipt = body.data?.data ?? body.data;
  expect(receipt.taskCode).toBeTruthy();
  let terminal: any;
  await expect.poll(async () => {
    const result = await page.request.get(`/api/async-tasks/${receipt.taskCode}`);
    expect(result.ok()).toBe(true);
    terminal = (await result.json()).data;
    return ['completed', 'failed', 'cancelled'].includes(terminal.status);
  }, { timeout: 60_000 }).toBe(true);
  const close = page.getByRole('button', { name: '关闭', exact: true });
  await expect(close).toBeVisible();
  await close.click();
  return terminal;
}

async function saved(page: Page, quoteId: string) {
  return queryDynamicRecords(page, 'qo_process_fee_rule_hit_common', [
    { fieldName: 'qo_pfrh_quote_id', operator: 'EQ', value: quoteId },
  ]);
}

async function assertDisplayedCounts(page: Page, quoteId: string) {
  const rows = await saved(page, quoteId);
  expect(rows.length).toBeGreaterThan(0);
  const number = (text: string | null) => Number(String(text).replace(/,/g, '').trim());
  const total = rows.reduce((sum, row) => sum + Number(row.qo_pfrh_total_points), 0);
  const metrics = page.getByTestId('metric-strip-qo_process_fee_count_metrics');
  await expect.poll(async () => number(await metrics.getByTestId('metric-strip-item-total_points').locator('span').last().textContent())).toBe(total);
  const table = page.getByTestId('table-block').filter({ has: page.getByRole('columnheader', { name: '计数对象', exact: true }) });
  await expect(table.locator('[data-testid^="table-row-"]')).toHaveCount(rows.length);
  let displayedTotal = 0;
  for (const row of rows) {
    const cells = table.getByTestId(`table-row-${row.pid}`).getByRole('cell');
    await expect(cells.nth(0)).toHaveText(String(row.qo_pfrh_fact_normalized));
    expect(number(await cells.nth(1).textContent())).toBe(Number(row.qo_pfrh_metering_qty));
    expect(number(await cells.nth(2).textContent())).toBe(Number(row.qo_pfrh_unit_points));
    const points = number(await cells.nth(3).textContent());
    expect(points).toBe(Number(row.qo_pfrh_total_points));
    displayedTotal += points;
  }
  expect(displayedTotal).toBe(total);
  return { rows, displayedTotal };
}

test('whole-board count: pads default口径, explicit scope semantics, reuse and failed replacement preservation', async ({ page }, info) => {
  test.setTimeout(180_000);
  const quote = await seedFixedCountQuote(page);
  await openQuoteDetailFromList(page, quote);
  await page.getByRole('tab', { name: /加工点数|Process Points/ }).click();
  await expect(page.getByText('尚未按新口径计算', { exact: true })).toBeVisible();
  await expect(page.getByText('待人工确认', { exact: true })).toHaveCount(0);

  // 工具栏按钮直发 count_hole_mode=default(无口径弹窗):首算自动 pads——纯焊膏档案
  // 无计费孔,大声拒绝,不虚构点数(设计契约)。失败记录在任务 errorMessage 里。
  const first = dispatchCompute(page, quote.quoteId, { count_hole_mode: 'default' });
  const firstTerminal = await first;
  expect(firstTerminal.status).toBe('failed');
  expect(String(firstTerminal.errorMessage ?? '')).toContain('包内未找到钻孔文件');
  expect(await saved(page, quote.quoteId)).toEqual([]);

  // The user excludes holes in the existing scope dialog: three paste openings × 0.5.
  const none = await dispatchCompute(page, quote.quoteId, { count_hole_mode: 'none' });
  expect(none.status).toBe('completed');
  const noneRows = await saved(page, quote.quoteId);
  expect(noneRows.reduce((n, r) => n + Number(r.qo_pfrh_total_points), 0)).toBe(1.5);
  await page.reload();
  await page.getByRole('tab', { name: /加工点数|Process Points/ }).click();
  await assertDisplayedCounts(page, quote.quoteId);
  await info.attach('process-count-paste-only', { body: await page.screenshot({ fullPage: true }), contentType: 'image/png' });

  // 显式 selected + 钻孔文本:焊膏 1.5 + 圆孔 2 = 3.5
  const selected = await dispatchCompute(page, quote.quoteId, { count_hole_mode: 'selected', count_drill_text: drill });
  expect(selected.status).toBe('completed');
  const selectedRows = await saved(page, quote.quoteId);
  const selectedTotal = selectedRows.reduce((n, r) => n + Number(r.qo_pfrh_total_points), 0);
  expect(selectedTotal).toBe(3.5);
  expect(selectedRows.every((r) => !r.qo_pfrh_quote_line_id && r.qo_pfrh_point_source === 'SIMPLE_COUNT_V1')).toBe(true);
  expect(selectedRows.filter((r) => r.qo_pfrh_process_stage === 'SMT').reduce((n, r) => n + Number(r.qo_pfrh_metering_qty), 0)).toBe(3);
  expect(selectedRows.filter((r) => r.qo_pfrh_process_stage === 'DIP').reduce((n, r) => n + Number(r.qo_pfrh_metering_qty), 0)).toBe(2);
  await page.reload();
  await page.getByRole('tab', { name: /加工点数|Process Points/ }).click();
  await assertDisplayedCounts(page, quote.quoteId);
  await info.attach('process-count-selected-drill', { body: await page.screenshot({ fullPage: true }), contentType: 'image/png' });

  // reuse:沿用上次成功口径,不重复计孔
  const reuse = await dispatchCompute(page, quote.quoteId, { count_hole_mode: 'reuse' });
  expect(reuse.status).toBe('completed');
  const previous = await saved(page, quote.quoteId);
  expect(previous.reduce((n, r) => n + Number(r.qo_pfrh_total_points), 0)).toBe(3.5);
  await page.reload();
  await page.getByRole('tab', { name: /加工点数|Process Points/ }).click();
  await assertDisplayedCounts(page, quote.quoteId);
  await info.attach('process-count-reused-scope', { body: await page.screenshot({ fullPage: true }), contentType: 'image/png' });

  // 坏钻孔文件:任务失败且保留上次成功结果
  const bad = await dispatchCompute(page, quote.quoteId, { count_hole_mode: 'selected', count_drill_text: 'not a drill file' });
  expect(bad.status).toBe('failed');
  const afterBad = await saved(page, quote.quoteId);
  expect(afterBad.reduce((n, r) => n + Number(r.qo_pfrh_total_points), 0)).toBe(3.5);
  await page.reload();
  await page.getByRole('tab', { name: /加工点数|Process Points/ }).click();
  for (const name of ['计数对象', '数量', '每个点数', '合计点数']) {
    await expect(page.getByRole('columnheader', { name, exact: true })).toBeVisible();
  }
  await expect(page.getByRole('columnheader', { name: '来源文件／范围', exact: true })).toHaveCount(0);
  const display = await assertDisplayedCounts(page, quote.quoteId);
  await page.screenshot({ path: info.outputPath('fixed-count-result.png'), fullPage: true });
  await info.attach('fixed-count-display-persistence', { body: JSON.stringify(display), contentType: 'application/json' });
});
