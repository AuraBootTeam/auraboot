import type { Page } from '@playwright/test';
import { test, expect } from '../../fixtures';
import { openQuoteDetailFromList, seedFixedCountQuote, queryDynamicRecords } from './quote-e2e-helpers';

const drill = 'M48\nMETRIC\nT01C0.800\n%\nT01\nX0.000Y0.000\nX1.500Y0.000\nM30\n';

async function calculate(page: Page, mode: string, text?: string, expectedStatus = 'completed') {
  await page.getByRole('button', { name: '计算／重新计算', exact: true }).click();
  const dialog = page.getByTestId('form-dialog');
  await expect(dialog).toBeVisible();
  await dialog.getByTestId('form-dialog-field-count_hole_mode').selectOption(mode);
  if (text !== undefined) await dialog.getByTestId('form-dialog-field-count_drill_text').setInputFiles({
    name: 'selected.drl', mimeType: 'text/plain', buffer: Buffer.from(text),
  });
  const pending = page.waitForResponse((r) => r.request().method() === 'POST'
    && r.url().includes('/api/meta/commands/execute/qo_quote_common:compute_process_fee'));
  await dialog.getByTestId('form-dialog-submit').click();
  const response = await pending;
  expect(response.ok()).toBe(true);
  expect(response.request().postDataJSON().payload.count_hole_mode).toBe(mode);
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
  expect(terminal.status, JSON.stringify(terminal)).toBe(expectedStatus);
  await expect(page.getByRole('button', { name: '关闭', exact: true })).toBeVisible({ timeout: 15_000 });
  await page.getByRole('button', { name: '关闭', exact: true }).click();
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

test('whole-board count: explicit scope, real parser, reuse and failed replacement preservation', async ({ page }, info) => {
  test.setTimeout(180_000);
  const quote = await seedFixedCountQuote(page);
  await openQuoteDetailFromList(page, quote);
  await page.getByRole('tab', { name: /加工点数|Process Points/ }).click();
  await expect(page.getByText('尚未按新口径计算', { exact: true })).toBeVisible();
  await expect(page.getByText('待人工确认', { exact: true })).toHaveCount(0);
  await calculate(page, 'none');
  await expect.poll(async () => (await saved(page, quote.quoteId)).reduce((n, r) => n + Number(r.qo_pfrh_total_points), 0)).toBe(1.5);
  await calculate(page, 'selected', drill);
  const selected = await saved(page, quote.quoteId);
  expect(selected.reduce((n, r) => n + Number(r.qo_pfrh_total_points), 0)).toBe(3.5);
  expect(selected.every((r) => !r.qo_pfrh_quote_line_id && r.qo_pfrh_point_source === 'SIMPLE_COUNT_V1')).toBe(true);
  expect(selected.filter((r) => r.qo_pfrh_process_stage === 'SMT').reduce((n, r) => n + Number(r.qo_pfrh_metering_qty), 0)).toBe(3);
  expect(selected.filter((r) => r.qo_pfrh_process_stage === 'DIP').reduce((n, r) => n + Number(r.qo_pfrh_metering_qty), 0)).toBe(2);
  await calculate(page, 'reuse');
  const previous = await saved(page, quote.quoteId);
  expect(previous.reduce((n, r) => n + Number(r.qo_pfrh_total_points), 0)).toBe(3.5);
  await assertDisplayedCounts(page, quote.quoteId);
  await calculate(page, 'selected', 'not a drill file', 'failed');
  expect(await saved(page, quote.quoteId)).toEqual(previous);
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
