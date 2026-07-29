import { test, expect } from '../../fixtures';
import {
  dynamicCreate,
  openQuoteDetailFromList,
  queryDynamicRecords,
  seedBomPriceManualReviewQuote,
  seedProcessFeeReviewQuote,
} from './quote-e2e-helpers';

function compact(value: string): string {
  return value.replace(/\s+/g, ' ').trim();
}

function snapshotObject(value: unknown): Record<string, unknown> {
  if (value && typeof value === 'object') return value as Record<string, unknown>;
  if (typeof value !== 'string' || value.trim() === '') return {};
  try {
    return JSON.parse(value) as Record<string, unknown>;
  } catch {
    return {};
  }
}

function editAndRepriceAudit(commandBody: Record<string, unknown>): Record<string, unknown> {
  const data = snapshotObject(commandBody.data);
  const handlerResults = Array.isArray(data.handlerResults) ? data.handlerResults : [];
  const firstHandlerResult = snapshotObject(handlerResults[0]);
  const candidates = [
    snapshotObject(data.data),
    snapshotObject(firstHandlerResult.data),
    firstHandlerResult,
    data,
  ];
  return (
    candidates.find(
      (candidate) =>
        candidate &&
        typeof candidate === 'object' &&
        Array.isArray(candidate.externalSourcesRequested),
    ) ?? {}
  );
}

test.describe('PCBA quote pricing and process-point evidence golden', () => {
  test.describe.configure({ timeout: 120_000 });

  test('shows Excel lineage and explains exact-package points while unsupported facts stay pending', async ({
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

    const processTable = page
      .locator('table')
      .filter({
        has: page.getByRole('columnheader', {
          name: /原始封装 → 标准封装|Raw → Normalized Package/,
        }),
      })
      .first();
    await expect(processTable).toBeVisible({ timeout: 20_000 });
    const headers = (await processTable.locator('thead th').allInnerTexts()).map(compact);
    expect(
      headers[0].toLocaleLowerCase(),
      `unexpected process-point headers: ${JSON.stringify(headers)}`,
    ).toBe('excel行');

    const matchedChip = page.getByTestId('metric-strip-item-matched');
    const manualRequiredChip = page.getByTestId('metric-strip-item-partial');
    const unmatchedChip = page.getByTestId('metric-strip-item-unmatched');
    await expect(matchedChip).toContainText('1');
    await expect(manualRequiredChip).toContainText('1');
    await expect(unmatchedChip).toContainText('1');

    await matchedChip.click();
    const matchedRow = processTable
      .locator('[data-testid^="table-row-"]')
      .filter({ hasText: 'E2E-EXACT' });
    await expect(matchedRow).toHaveCount(1, { timeout: 20_000 });
    const matchedCells = matchedRow.locator('td');
    await expect(matchedCells.nth(0)).toHaveText('14');
    await expect(matchedRow).toContainText('0201 → 0201');
    await expect(matchedRow).toContainText(/Excel行\s*3/);
    await expect(matchedRow).toContainText(/4\.00/);
    await expect(matchedRow).toContainText(/封装\s+0201\s+精确命中规则/);

    await processTable.scrollIntoViewIfNeeded();
    await processTable.screenshot({ path: testInfo.outputPath('01-process-points-table.png') });

    await matchedRow.click({ force: true });
    const reviewDrawer = page.getByTestId('review-drawer');
    await expect(reviewDrawer).toBeVisible({ timeout: 20_000 });
    await expect(page.getByTestId('review-drawer-raw-panel')).toBeVisible();
    await expect(page.getByTestId('review-drawer-canonical-panel')).toBeVisible();
    await expect(page.getByTestId('review-drawer-parse-summary')).toBeVisible();

    const drawerText = compact(await reviewDrawer.innerText());
    for (const expected of [
      '本行输入',
      '核算结果',
      '计算依据',
      '原始封装事实',
      '标准封装',
      'BOM封装列',
      'package_exact',
      'Excel行 3',
      '单套用量 4',
      '单件点数 2',
      '单套点数 8',
      '仅依据标准封装 0201 完全相等命中',
      '未使用名称、MPN、位号或关键词',
    ]) {
      expect(drawerText, `process-point drawer is missing ${expected}`).toContain(expected);
    }
    for (const forbidden of ['解析证据与 Profile / LLM Policy', '无需在页面维护规则', '核算结论']) {
      expect(drawerText).not.toContain(forbidden);
    }
    await expect(page.getByTestId('review-drawer-tab-source')).toHaveCount(0);
    await expect(page.getByTestId('review-drawer-tab-candidates')).toHaveCount(0);
    await reviewDrawer.screenshot({
      path: testInfo.outputPath('02-process-point-evidence-drawer.png'),
    });

    await page.getByRole('button', { name: /关闭复核浮层|Close review drawer/ }).click();
    await unmatchedChip.click();
    const unmatchedRow = processTable
      .locator('[data-testid^="table-row-"]')
      .filter({ hasText: 'E2E-UNMATCHED' });
    await expect(unmatchedRow).toHaveCount(1, { timeout: 20_000 });
    await expect(unmatchedRow).toContainText(/规则未命中|Rule Missing/);
    await expect(unmatchedRow).toContainText(/TO-999\s*→\s*TO999/);

    await manualRequiredChip.click();
    const manualRequiredRow = processTable
      .locator('[data-testid^="table-row-"]')
      .filter({ hasText: 'E2E-MIXED' });
    await expect(manualRequiredRow).toHaveCount(1, { timeout: 20_000 });
    await expect(manualRequiredRow).toContainText(/需人工复核|Needs Review/);
    await expect(manualRequiredRow).toContainText(/当前只支持封装精确匹配|补齐封装/);

    await processTable.screenshot({
      path: testInfo.outputPath('03-process-point-pending-states.png'),
    });
  });

  test('edits material info, refreshes local recent purchase and calls only Yunhan externally', async ({
    page,
  }, testInfo) => {
    const created = await seedBomPriceManualReviewQuote(page);
    const editedMpn = '1N4148W';
    const recentPrice = 0.1234;
    await dynamicCreate(
      page,
      'qo_offline_material_price_common',
      {
        qo_omp_part_no: editedMpn,
        qo_omp_mpn: editedMpn,
        qo_omp_description: 'E2E local recent-purchase candidate',
        qo_omp_unit_price: recentPrice,
        qo_omp_recent_purchase_price: recentPrice,
        qo_omp_currency: 'CNY',
        qo_omp_status: 'active',
        qo_omp_source_filename: `e2e-edit-reprice-${created.quoteCode}.xlsx`,
        qo_omp_source_row_no: 2,
      },
      created.rows,
    );

    await openQuoteDetailFromList(page, created);
    const bomPriceTab = page.getByRole('tab', { name: /BOM价格计算|BOM Price/ });
    await bomPriceTab.click();

    const priceTable = page
      .locator('table')
      .filter({
        has: page.getByRole('columnheader', {
          name: /采购分析近期价|近期价\(系数后\)|Recent Purchase/,
        }),
      })
      .first();
    await expect(priceTable).toBeVisible({ timeout: 20_000 });
    const headers = (await priceTable.locator('thead th').allInnerTexts()).map(compact);
    expect(
      headers[0].toLocaleLowerCase(),
      `unexpected BOM-price headers: ${JSON.stringify(headers)}`,
    ).toBe('excel行');

    const originalRow = priceTable
      .locator('[data-testid^="table-row-"]')
      .filter({ hasText: created.mpn });
    await expect(originalRow).toHaveCount(1, { timeout: 20_000 });
    await originalRow.click({ force: true });

    const reviewDrawer = page.getByTestId('review-drawer');
    await expect(reviewDrawer).toBeVisible({ timeout: 20_000 });
    await page.getByTestId('review-drawer-edit-open').click();
    const editForm = page.getByTestId('review-drawer-edit-form');
    await expect(editForm).toBeVisible();

    const descriptionInput = page
      .getByTestId('review-drawer-edit-field-qo_ql_description')
      .locator('input')
      .first();
    const mpnInput = page
      .getByTestId('review-drawer-edit-field-qo_ql_mpn')
      .locator('input')
      .first();
    await descriptionInput.fill('');
    await page.getByTestId('review-drawer-edit-submit').click();
    await expect(page.getByTestId('review-drawer-edit-error')).toBeVisible();

    await descriptionInput.fill('Switching diode 1N4148W SOD-123');
    await mpnInput.fill(editedMpn);
    await expect(page.getByTestId('review-drawer-edit-error')).toHaveCount(0);
    await editForm.screenshot({ path: testInfo.outputPath('04-edit-and-reprice-form.png') });

    const commandResponsePromise = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        response.url().includes('/api/meta/commands/execute/qo_quote_line_common:edit_and_reprice'),
      { timeout: 60_000 },
    );
    await page.getByTestId('review-drawer-edit-submit').click();
    const commandResponse = await commandResponsePromise;
    const commandBody = await commandResponse.json().catch(() => ({}));
    expect(commandResponse.ok(), JSON.stringify(commandBody)).toBe(true);
    expect(String(commandBody?.code), JSON.stringify(commandBody)).toBe('0');
    const repriceAudit = editAndRepriceAudit(commandBody);
    expect(repriceAudit.externalSourcesRequested, JSON.stringify(commandBody)).toEqual(['yunhan']);
    expect(repriceAudit.localLookups, JSON.stringify(commandBody)).toEqual([
      'purchase_analysis_recent_price',
    ]);
    await testInfo.attach('edit-and-reprice-response.json', {
      body: JSON.stringify(commandBody, null, 2),
      contentType: 'application/json',
    });

    const editedRow = priceTable
      .locator('[data-testid^="table-row-"]')
      .filter({ hasText: editedMpn });
    await expect(editedRow).toHaveCount(1, { timeout: 60_000 });
    await expect(editedRow).toContainText(/CNY\s+0\.12(?:34)?/);
    await expect(editedRow).not.toContainText(/价格源未配置或已关闭|云汉未配置/);

    const evidence = await queryDynamicRecords(page, 'qo_price_evidence_common', [
      { fieldName: 'qo_pe_quote_line_id', operator: 'EQ', value: created.lineId },
    ]);
    const localRecent = evidence.find(
      (row) => row.qo_pe_source === 'purchase_analysis_recent_price',
    );
    const yunhan = evidence.find((row) => row.qo_pe_source === 'yunhan');
    expect(localRecent, JSON.stringify(evidence)).toBeTruthy();
    expect(Number(localRecent?.qo_pe_unit_price)).toBe(recentPrice);
    expect(yunhan, JSON.stringify(evidence)).toBeTruthy();
    const yunhanSnapshot = snapshotObject(yunhan?.qo_pe_snapshot);
    expect(
      String(yunhanSnapshot.failureCode ?? ''),
      `Yunhan must be called with configured credentials: ${JSON.stringify(yunhan)}`,
    ).not.toBe('source_unavailable');

    await expect(page.locator('main')).not.toContainText('价格源未配置或已关闭');
    await priceTable.scrollIntoViewIfNeeded();
    await page.screenshot({
      path: testInfo.outputPath('05-edit-and-reprice-result.png'),
      fullPage: false,
    });
  });
});
