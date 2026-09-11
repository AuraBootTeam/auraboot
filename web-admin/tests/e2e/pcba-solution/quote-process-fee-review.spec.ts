import { writeFile } from 'node:fs/promises';
import { test, expect } from '../../fixtures';
import {
  openQuoteDetailFromList,
  seedProcessFeeReviewQuote,
  seedProcessFeeGeometryQuote,
  queryDynamicRecords,
  executeCommand,
} from './quote-e2e-helpers';

function compact(value: string): string {
  return value.replace(/\s+/g, ' ').trim();
}

test.describe('PCBA quote pricing and process-point evidence golden', () => {
  test.describe.configure({ timeout: 120_000 });

  test('keeps the gerber-caliber header, seven-column rows and actionable missing-fact reasons', async ({
    page,
  }, testInfo) => {
    // Unique fixture identifiers make old retained evidence unable to satisfy this run.
    const created = await seedProcessFeeReviewQuote(page);

    await openQuoteDetailFromList(page, created);
    await expect(page.getByRole('button', { name: /价格系数\(百分比\)\s+100\.00%/ })).toBeVisible({
      timeout: 20_000,
    });

    const processPointsTab = page.getByRole('tab', { name: /加工点数|Process Points/ });
    await expect(processPointsTab).toBeVisible({ timeout: 20_000 });
    await processPointsTab.click();

    // Gerber-only caliber v2 (2026-09-06): the strip keeps TOTAL points and the
    // rule version; the board summary card retired. Without Gerber histograms
    // every row stays 待人工确认 and the rows aggregate back to ORIGINAL Excel
    // lines (refdes lists intact).
    await expect(page.getByText(/规则版本|Rule Version/)).toBeVisible({ timeout: 20_000 });
    await expect(page.getByTestId('metric-strip-item-total_points')).toBeVisible();

    const processTable = page
      .locator('table')
      .filter({
        has: page.getByRole('columnheader', {
          name: /原始行|Source Row/,
        }),
      })
      .first();
    await expect(processTable).toBeVisible({ timeout: 20_000 });
    const headers = (await processTable.locator('thead th').allInnerTexts()).map(compact);
    expect(headers, `unexpected process-point headers: ${JSON.stringify(headers)}`).toEqual([
      '原始行',
      '位号',
      '状态',
      '物料/规格',
      '贴装类型',
      'GERBER事实',
      '数量/点数',
    ]);

    const matchedChip = page.getByTestId('metric-strip-item-matched');
    const manualRequiredChip = page.getByTestId('metric-strip-item-partial');
    const unmatchedChip = page.getByTestId('metric-strip-item-unmatched');
    await expect(matchedChip).toContainText('0');
    await expect(manualRequiredChip).toContainText('3');
    await expect(unmatchedChip).toContainText('0');

    await manualRequiredChip.click();
    for (const mpn of ['E2E-EXACT', 'E2E-UNMATCHED', 'E2E-MIXED']) {
      const row = processTable.locator('[data-testid^="table-row-"]').filter({ hasText: mpn });
      await expect(row).toHaveCount(1, { timeout: 20_000 });
      await expect(row).toContainText(/待人工确认|Needs Review/);
      // Missing geometry must explain the recovery path directly in the fact column.
      await expect(row).toContainText('已算 0 点 · 待确定');
      await expect(row).toContainText('未上传 Gerber，无法按面积区间计点');
    }

    await processTable.scrollIntoViewIfNeeded();
    await processTable.screenshot({ path: testInfo.outputPath('01-process-points-table.png') });

    // The review drawer is retired: clicking a row must not open any overlay.
    const anyRow = processTable
      .locator('[data-testid^="table-row-"]')
      .filter({ hasText: 'E2E-EXACT' });
    await anyRow.click({ force: true });
    await expect(page.getByTestId('review-drawer')).toHaveCount(0);

    // Matched / rule-missing filters have no rows to show before Gerber is parsed.
    await matchedChip.click();
    for (const mpn of ['E2E-EXACT', 'E2E-UNMATCHED', 'E2E-MIXED']) {
      await expect(
        processTable.locator('[data-testid^="table-row-"]').filter({ hasText: mpn }),
      ).toHaveCount(0);
    }
    await unmatchedChip.click();
    await expect(
      processTable.locator('[data-testid^="table-row-"]').filter({ hasText: 'E2E-EXACT' }),
    ).toHaveCount(0);

    await processTable.screenshot({
      path: testInfo.outputPath('03-process-point-pending-states.png'),
    });
  });
});

test('process-point release gate: inline rules, row-status filters, grouped geometry and rule imports', async ({
  page,
}, testInfo) => {
  test.setTimeout(180_000);
  await page.setViewportSize({ width: 1280, height: 900 });
  const created = await seedProcessFeeGeometryQuote(page);
  await openQuoteDetailFromList(page, created);
  await page.getByRole('tab', { name: '加工点数', exact: true }).click();
  const view = page.getByRole('button', { name: '查看计算规则', exact: true });
  const upload = page.getByRole('button', { name: '导入计算规则', exact: true });
  const recompute = page.getByRole('button', { name: '重新计算点数', exact: true });
  const rulesDisclosure = page.locator('details').filter({ has: page.locator('summary').filter({ hasText: '计点规则' }) });
  await expect(rulesDisclosure).toHaveCount(1);
  await expect(rulesDisclosure).not.toHaveAttribute('open', '');
  await rulesDisclosure.locator('summary').click();
  await expect(rulesDisclosure).toHaveAttribute('open', '');

  await expect(view).toHaveCount(0);
  for (const button of [upload, recompute]) {
    await expect(button).toBeEnabled();
    await expect(button).toHaveClass(/bg-accent/);
  }
  // Read-only API baseline for preserving the installed rules during the upload test.
  // The user-visible read journey is the automatically populated inline table.
  const artifact = await executeCommand(page, 'qo_quote_common:view_process_fee_point_rules', {}, created.quoteId);
  const raw = Buffer.from(String(artifact.contentBase64), 'base64');
  await writeFile(testInfo.outputPath('original-point-rules.json'), raw);
  const rules = JSON.parse(raw.toString());
  const ruleTable = page.getByRole('table').filter({
    has: page.getByRole('columnheader', { name: '计点规则（展开查看）', exact: true }),
  });
  await expect(ruleTable).toBeVisible();
  const rulesBounds = await ruleTable.boundingBox();
  expect(rulesBounds!.x + rulesBounds!.width).toBeLessThanOrEqual(1280);
  await expect(ruleTable).toContainText(rules.ruleSet.version);
  for (const [name, buckets, unit, pointKey] of [
    ['贴片（SMT）计点规则', rules.smt.buckets, 'mm²', 'pointsPerPad'],
    ['插件（DIP）计点规则', rules.dip.buckets, 'mm', 'pointsPerHole'],
  ] as const) {
    const table = page.getByRole('table', { name, exact: true });
    await expect(table).toBeVisible();
    const rows = table.locator('tbody tr');
    await expect(rows).toHaveCount(buckets.length);
    let lower: number | null = null;
    for (let index = 0; index < buckets.length; index++) {
      const bucket = buckets[index];
      const range = bucket.maxInclusive == null
        ? (lower == null ? '所有尺寸' : `> ${lower} ${unit}`)
        : `${lower == null ? '≤ ' : `${lower} < 尺寸 ≤ `}${bucket.maxInclusive} ${unit}`;
      await expect(rows.nth(index).getByRole('cell').nth(0)).toHaveText(range);
      await expect(rows.nth(index).getByRole('cell').nth(1)).toHaveText(`${bucket[pointKey]} 点`);
      lower = bucket.maxInclusive ?? null;
    }
  }

  async function importRules(buffer: Buffer, name: string) {
    const chooserPromise = page.waitForEvent('filechooser');
    await upload.click();
    const chooser = await chooserPromise;
    expect(await chooser.element().getAttribute('accept')).toBe('.json');
    const responsePromise = page.waitForResponse(
      (r) =>
        r
          .url()
          .includes('/api/meta/commands/execute/qo_quote_common:import_process_fee_point_rules') &&
        r.request().method() === 'POST',
    );
    await chooser.setFiles({ name, mimeType: 'application/json', buffer });
    const response = await responsePromise;
    const payload = response.request().postDataJSON();
    expect(payload.targetRecordPid ?? payload.targetRecordId).toBe(created.quoteId);
    expect(payload.payload.process_point_rules_filename).toBe(name);
    expect(payload.payload.process_point_rules_file_id).toBeTruthy();
    return response.json();
  }
  const rejected = await importRules(
    Buffer.from('{"schemaVersion":1}'),
    'invalid-point-rules.json',
  );
  expect(String(rejected.code)).not.toBe('0');
  // A rejected import must not replace the current valid rules.
  const unchanged = await executeCommand(page, 'qo_quote_common:view_process_fee_point_rules', {}, created.quoteId);
  expect(Buffer.from(String(unchanged.contentBase64), 'base64')).toEqual(raw);
  const rulesRefresh = page.waitForResponse(r => r.url().includes('/api/meta/commands/execute/qo_quote_common:view_process_fee_point_rules')
    && r.request().postDataJSON()?.payload?.presentationOnly === true);
  const accepted = await importRules(raw, 'valid-point-rules.json');
  expect(String(accepted.code)).toBe('0');
  const refreshed = await rulesRefresh;
  expect(refreshed.request().postDataJSON().targetRecordPid).toBe(created.quoteId);
  const refreshedBody = await refreshed.json();
  expect(String(refreshedBody.code)).toBe('0');
  expect(refreshedBody.data.data.contentBase64).toBeUndefined();
  await expect(ruleTable).toContainText(rules.ruleSet.version);

  const commandPromise = page.waitForResponse(
    (r) =>
      r.url().includes('/api/meta/commands/execute/qo_quote_common:compute_process_fee') &&
      r.request().method() === 'POST',
  );
  await recompute.click();
  const command = await commandPromise;
  const commandBody = command.request().postDataJSON();
  expect(commandBody.targetRecordPid ?? commandBody.targetRecordId).toBe(created.quoteId);
  expect(String((await command.json()).code)).toBe('0');

  const bucketPoints = (size: number, buckets: any[], key: string) => {
    const bucket = buckets.find((b) => b.maxInclusive == null || size <= b.maxInclusive);
    expect(bucket, `no bucket for ${size}`).toBeTruthy();
    return Number(bucket[key]);
  };
  const smtPoints =
    2 * (10 * bucketPoints(4, rules.smt.buckets, 'pointsPerPad') +
    bucketPoints(8, rules.smt.buckets, 'pointsPerPad'));
  const dipPoints =
    2 * bucketPoints(1.2, rules.dip.buckets, 'pointsPerHole') +
    bucketPoints(2, rules.dip.buckets, 'pointsPerHole');
  const table = page
    .getByRole('table')
    .filter({ has: page.getByRole('columnheader', { name: 'Gerber事实', exact: true }) });
  const smt = table.getByRole('row').filter({ hasText: 'GEOMETRY-SMT' });
  const dip = table.getByRole('row').filter({ hasText: 'GEOMETRY-DIP' });
  await expect(smt).toContainText('贴片', { timeout: 60_000 });
  await expect(smt).toContainText('4 mm² × 10 + 8 mm² × 1');
  await expect(smt).toContainText(') × 2 位号 ≈ 96.000 mm²');
  const factCell = smt.getByRole('cell').filter({ hasText: 'mm²' });
  await expect(factCell).not.toHaveClass(/truncate/);
  expect(await factCell.evaluate((cell) => cell.scrollWidth <= cell.clientWidth)).toBe(true);
  await expect(smt).toContainText(`共 ${smtPoints} 点`);
  await expect(dip).toContainText('插件');
  await expect(dip).toContainText('1.2 mm × 2 + 2 mm × 1');
  await expect(dip).toContainText(`共 ${dipPoints} 点`);
  await expect(page.getByTestId('metric-strip-item-total_points')).toContainText(
    String(smtPoints + dipPoints),
  );
  const hits = await queryDynamicRecords(page, 'qo_process_fee_rule_hit_common', [
    { fieldName: 'qo_pfrh_quote_id', operator: 'EQ', value: created.quoteId },
  ]);
  expect(hits.filter((h) => h.qo_pfrh_match_status === 'matched')).toHaveLength(3);
  const pending = hits.filter((h) => h.qo_pfrh_match_status === 'manual_required');
  const excluded = hits.filter((h) => h.qo_pfrh_match_status === 'excluded');
  expect(excluded).toHaveLength(1);
  expect(excluded[0].qo_pfrh_refdes).toBe('EMPTY1');
  expect(excluded[0].qo_pfrh_reason_code).toBe('OUTSIDE_EFFECTIVE_BOM');
  expect(Number(excluded[0].qo_pfrh_total_points)).toBe(0);
  expect(pending).toHaveLength(1);
  expect(pending[0].qo_pfrh_reason_code).toBe('BOM_GEOMETRY_MISSING');
  expect(pending[0].qo_pfrh_reason).toContain('P5');
  expect(pending[0].qo_pfrh_remediation).toBeTruthy();
  expect(hits.reduce((sum, h) => sum + Number(h.qo_pfrh_total_points), 0)).toBe(
    smtPoints + dipPoints,
  );
  await page.getByRole('button', { name: '关闭', exact: true }).click();
  // Excluded non-BOM facts remain persisted, without a duplicate visible table.
  await expect(page.getByRole('columnheader', { name: '计点影响', exact: true })).toHaveCount(0);
  await expect(page.getByTestId('metric-strip-item-all')).toHaveText('全部3');
  await expect(page.getByTestId('metric-strip-item-matched')).toHaveText('已计算2');
  await expect(page.getByTestId('metric-strip-item-partial')).toHaveText('待人工确认1');
  await page.getByTestId('metric-strip-item-partial').click();
  await expect(table.locator('tbody tr')).toHaveCount(1);
  await expect(table).toContainText('GEOMETRY-MISSING');
  await expect(table).toContainText('未找到可关联的焊盘/孔事实');
  await page.getByTestId('metric-strip-item-matched').click();
  await expect(table.locator('tbody tr')).toHaveCount(2);
  await expect(table).not.toContainText('GEOMETRY-MISSING');
  await page.getByTestId('metric-strip-item-all').click();
  await expect(table.locator('tbody tr')).toHaveCount(3);
  const tableBounds = await table.boundingBox();
  expect(tableBounds!.x + tableBounds!.width).toBeLessThanOrEqual(1280);
  await page.reload();
  await expect(page.getByRole('row').filter({ hasText: 'GEOMETRY-SMT' })).toContainText(
    `共 ${smtPoints} 点`,
  );
  await page.screenshot({ path: testInfo.outputPath('process-buttons-and-area.png') });
  await table.screenshot({ path: testInfo.outputPath('process-area-evidence.png') });
});
