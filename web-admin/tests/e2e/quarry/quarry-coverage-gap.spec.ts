import { test, expect, type Page, type Locator } from '@playwright/test';
import {
  navigateToDynamicPage,
  clickTabAndWaitForLoad,
  executeCommandViaApi,
  findRowInPaginatedList,
  uniqueId,
} from '../helpers/index';
import { getTestProjectId } from '../quarry-management.setup';

async function expectTabVisible(page: Page, key: string, labels: RegExp[]) {
  const candidates: Locator[] = [
    page.locator(`[data-testid="tab-${key}"]`).first(),
    page.locator(`[data-testid="tab-${key.toLowerCase()}"]`).first(),
  ];
  for (const label of labels) {
    candidates.push(
      page.locator('nav[aria-label="Tabs"] button').filter({ hasText: label }).first(),
    );
  }
  // Use waitFor with timeout instead of isVisible (which returns immediately)
  for (const locator of candidates) {
    try {
      await locator.waitFor({ state: 'visible', timeout: 8000 });
      return;
    } catch {
      // try next candidate
    }
  }
  throw new Error(`Tab not found: ${key}`);
}

/**
 * Navigate to a PM sub-page via navigateToDynamicPage (uses page key, not raw route).
 * Each model is verified by asserting table/emptyState is visible and no 403 error.
 */
async function verifyPmPageLoads(page: Page, pageKey: string): Promise<void> {
  await navigateToDynamicPage(page, pageKey);
  // Assert page loaded: table OR empty-state must be visible (no 403/404)
  const content = page
    .locator('table, [class*="ant-table"], [class*="empty"], main[class*="content"]')
    .first();
  await expect(content).toBeVisible({ timeout: 15000 });
  await expect(page.locator('body')).not.toContainText(
    /Access forbidden|Page not found|Forbidden/i,
    { timeout: 5000 },
  );
}

test.describe('Quarry Coverage Gap Guards @smoke', () => {
  test.describe.configure({ mode: 'serial' });

  test('CG-001: PM model pages load without 403 — navigate via navigateToDynamicPage', async ({
    page,
  }) => {
    // Use navigateToDynamicPage (sidebar-compatible) instead of direct page.goto
    // This ensures the pages are accessible from the nav context, not just as raw URLs.
    await verifyPmPageLoads(page, 'pm-portfolio');
    await verifyPmPageLoads(page, 'pm-project');
    // time-entries uses a custom route — verify via page.goto is acceptable here
    // since this is a route-access check, not a business-flow test
    await page.goto('/project-management/time-entries', { waitUntil: 'domcontentloaded' });
    await expect(page.locator('body')).not.toContainText(
      /Access forbidden|Page not found|Forbidden/i,
      { timeout: 15000 },
    );
    await expect(page.locator('table, [class*="ant-table"], main').first()).toBeVisible({
      timeout: 15000,
    });
  });

  test('CG-002: PM tabs are present', async ({ page }) => {
    await navigateToDynamicPage(page, 'pm-project');
    await expectTabVisible(page, 'planning', [/规划中|Planning/i]);
    await expectTabVisible(page, 'archived', [/已归档|Archived/i]);

    await navigateToDynamicPage(page, 'pm-portfolio');
    await expectTabVisible(page, 'planning', [/规划中|Planning/i]);
    await expectTabVisible(page, 'active', [/进行中|Active/i]);
    await expectTabVisible(page, 'on_hold', [/暂停|On Hold/i]);
    await expectTabVisible(page, 'closed', [/已关闭|Closed/i]);

    // TODO: implement schedule-deviation, milestone, client, subcontractor models and tabs
  });

  test.fixme('CG-003: CC contract lifecycle tabs and row actions are all operable', async ({ page }) => {
    const name = `CG CC ${uniqueId()}`;
    const create = await executeCommandViaApi(page, 'cc:create_contract', {
      cc_contract_name: name,
      cc_contract_type: 'construction',
      cc_party_a: 'CG Party A',
      cc_party_b: 'CG Party B',
      cc_contract_amount: 123456,
    });
    expect(create.code).toBe('0');
    const pid = create.recordId;

    await navigateToDynamicPage(page, 'cc-contract');
    await expectTabVisible(page, 'signed', [/已签订|Signed/i]);
    await expectTabVisible(page, 'executing', [/执行中|Executing/i]);
    await expectTabVisible(page, 'settled', [/已结算|Settled/i]);
    await expectTabVisible(page, 'closed', [/已关闭|Closed/i]);

    // draft -> submit_review action button
    let row = await findRowInPaginatedList(page, name, 12000);
    await row.hover();
    await expect(
      row
        .locator(
          '[data-testid="row-action-submit_review"], button:has-text("submit_review"), button:has-text("提交审核")',
        )
        .first(),
    ).toBeVisible({ timeout: 5000 });

    let result = await executeCommandViaApi(page, 'cc:submit_review', {}, pid, 'state_transition');
    expect(result.code).toBe('0');
    result = await executeCommandViaApi(page, 'cc:approve_contract', {}, pid, 'state_transition');
    expect(result.code).toBe('0');

    // SIGNED -> start_exec action button
    await clickTabAndWaitForLoad(page, /已签订|Signed/i, 8000, 'signed');
    row = await findRowInPaginatedList(page, name, 12000);
    await row.hover();
    await expect(
      row
        .locator(
          '[data-testid="row-action-start_exec"], button:has-text("start_exec"), button:has-text("开始执行")',
        )
        .first(),
    ).toBeVisible({ timeout: 5000 });

    result = await executeCommandViaApi(page, 'cc:start_execution', {}, pid, 'state_transition');
    expect(result.code).toBe('0');

    // EXECUTING -> settle action button
    await clickTabAndWaitForLoad(page, /执行中|Executing/i, 8000, 'executing');
    row = await findRowInPaginatedList(page, name, 12000);
    await row.hover();
    await expect(
      row
        .locator(
          '[data-testid="row-action-settle"], button:has-text("settle"), button:has-text("结算")',
        )
        .first(),
    ).toBeVisible({ timeout: 5000 });

    result = await executeCommandViaApi(page, 'cc:settle_contract', {}, pid, 'state_transition');
    expect(result.code).toBe('0');

    // SETTLED -> close action button
    await clickTabAndWaitForLoad(page, /已结算|Settled/i, 8000, 'settled');
    row = await findRowInPaginatedList(page, name, 12000);
    await row.hover();
    await expect(
      row
        .locator(
          '[data-testid="row-action-close"], button:has-text("close"), button:has-text("关闭")',
        )
        .first(),
    ).toBeVisible({ timeout: 5000 });

    result = await executeCommandViaApi(page, 'cc:close_contract', {}, pid, 'state_transition');
    expect(result.code).toBe('0');

    await clickTabAndWaitForLoad(page, /已关闭|Closed/i, 8000, 'closed');
    row = await findRowInPaginatedList(page, name, 12000);
    await expect(row).toBeVisible({ timeout: 5000 });
  });

  test('CG-004: DK document/article archived tabs and row actions are covered', async ({
    page,
  }) => {
    const docTitle = `CG Doc ${uniqueId()}`;
    const doc = await executeCommandViaApi(page, 'dk:create_document', {
      dk_doc_title: docTitle,
      dk_doc_type: 'report',
      dk_doc_version: 'v1.0',
      dk_doc_content: 'cg coverage',
    });
    expect(doc.code).toBe('0');

    await navigateToDynamicPage(page, 'dk-document');
    await clickTabAndWaitForLoad(page, /草稿|Draft/i, 8000, 'draft');
    let row = await findRowInPaginatedList(page, docTitle, 12000);
    await row.hover();
    await expect(row.locator('[data-testid="row-action-publish"]')).toBeVisible({ timeout: 5000 });

    let result = await executeCommandViaApi(
      page,
      'dk:publish_document',
      {},
      doc.recordId,
      'state_transition',
    );
    expect(result.code).toBe('0');

    await clickTabAndWaitForLoad(page, /已发布|Published/i, 8000, 'published');
    row = await findRowInPaginatedList(page, docTitle, 12000);
    await row.hover();
    // archive is the primary action (directly visible)
    await expect(row.locator('[data-testid="row-action-archive"]')).toBeVisible({ timeout: 5000 });
    // revise is in the overflow "more" dropdown
    const docMoreBtn = row.locator('[data-testid="row-action-more"]').first();
    await page.evaluate(() => {
      const fab = document.querySelector('[title="Send Feedback"]') as HTMLElement | null;
      if (fab) fab.style.display = 'none';
    });
    await docMoreBtn.click();
    // revise button appears in the portal dropdown with data-testid="row-action-revise"
    await expect(page.locator('[data-testid="row-action-revise"]').first()).toBeVisible({
      timeout: 5000,
    });
    await page.keyboard.press('Escape');

    result = await executeCommandViaApi(
      page,
      'dk:archive_document',
      {},
      doc.recordId,
      'state_transition',
    );
    expect(result.code).toBe('0');

    await clickTabAndWaitForLoad(page, /已归档|Archived/i, 8000, 'archived');
    row = await findRowInPaginatedList(page, docTitle, 12000);
    await expect(row).toBeVisible({ timeout: 5000 });

    const articleTitle = `CG Article ${uniqueId()}`;
    const article = await executeCommandViaApi(page, 'dk:create_article', {
      dk_ka_title: articleTitle,
      dk_ka_content: 'cg coverage article',
    });
    expect(article.code).toBe('0');

    await navigateToDynamicPage(page, 'dk-knowledge-article');
    await clickTabAndWaitForLoad(page, /草稿|Draft/i, 8000, 'draft');
    row = await findRowInPaginatedList(page, articleTitle, 12000);
    await row.hover();
    await expect(row.locator('[data-testid="row-action-publish"]')).toBeVisible({ timeout: 5000 });

    result = await executeCommandViaApi(
      page,
      'dk:publish_article',
      {},
      article.recordId,
      'state_transition',
    );
    expect(result.code).toBe('0');

    await clickTabAndWaitForLoad(page, /已发布|Published/i, 8000, 'published');
    row = await findRowInPaginatedList(page, articleTitle, 12000);
    await row.hover();
    await expect(row.locator('[data-testid="row-action-archive"]')).toBeVisible({ timeout: 5000 });

    result = await executeCommandViaApi(
      page,
      'dk:archive_article',
      {},
      article.recordId,
      'state_transition',
    );
    expect(result.code).toBe('0');

    await clickTabAndWaitForLoad(page, /已归档|Archived/i, 8000, 'archived');
    row = await findRowInPaginatedList(page, articleTitle, 12000);
    await expect(row).toBeVisible({ timeout: 5000 });
  });

  test('CG-005: Remaining gap tabs/actions are covered (DP quality + CP process)', async ({
    page,
  }) => {
    // TODO: implement pm-project-risk model, then add tab assertions here
    const projectId = await getTestProjectId(page);

    // DP quality checkpoint row actions: pass / fail / conditional (on pending row)
    const checkpointName = `CG QC ${uniqueId()}`;
    const checkpoint = await executeCommandViaApi(page, 'dp:create_checkpoint', {
      dp_qc_name: checkpointName,
      dp_qc_category: 'process',
      dp_qc_standard: 'Coverage guard standard',
      dp_qc_inspector: 'Coverage Guard',
      dp_qc_inspection_date: new Date().toISOString().slice(0, 10),
    });
    expect(checkpoint.code).toBe('0');

    await navigateToDynamicPage(page, 'dp-quality-checkpoint');
    await clickTabAndWaitForLoad(page, /待检查|Pending/i, 8000, 'pending');
    let row = await findRowInPaginatedList(page, checkpointName, 12000);
    await row.hover();
    // pass is the primary action (directly visible); fail/conditional are in overflow dropdown
    await expect(row.locator('[data-testid="row-action-pass"]')).toBeVisible({ timeout: 5000 });
    const qcMoreBtn = row.locator('[data-testid="row-action-more"]').first();
    await page.evaluate(() => {
      const fab = document.querySelector('[title="Send Feedback"]') as HTMLElement | null;
      if (fab) fab.style.display = 'none';
    });
    await qcMoreBtn.click();
    const qcDropdown = page.locator('[data-testid="row-action-dropdown"]').first();
    await expect(qcDropdown).toBeVisible({ timeout: 3000 });
    await expect(page.locator('[data-testid="row-action-fail"]').first()).toBeVisible({
      timeout: 3000,
    });
    await expect(page.locator('[data-testid="row-action-conditional"]').first()).toBeVisible({
      timeout: 3000,
    });
    await page.keyboard.press('Escape');

    // CP material inspection: tab inspecting + row-action pass/fail (on INSPECTING row)
    const miName = `CG MI ${uniqueId()}`;
    const inspection = await executeCommandViaApi(page, 'cp:create_inspection', {
      cp_mi_project_id: projectId,
      cp_mi_material_name: miName,
      cp_mi_specification: 'm30',
      cp_mi_quantity: 10,
      cp_mi_unit: '吨',
      cp_mi_supplier: 'CG Supplier',
    });
    expect(inspection.code).toBe('0');
    let result = await executeCommandViaApi(
      page,
      'cp:start_inspection',
      {},
      inspection.recordId,
      'state_transition',
    );
    expect(result.code).toBe('0');

    await navigateToDynamicPage(page, 'cp-material-inspection');
    await expectTabVisible(page, 'inspecting', [/检验中|Inspecting/i]);
    await clickTabAndWaitForLoad(page, /检验中|Inspecting/i, 8000, 'inspecting');
    row = await findRowInPaginatedList(page, miName, 12000);
    await row.hover();
    // pass is the primary action (directly visible); fail is in overflow dropdown
    await expect(row.locator('[data-testid="row-action-pass"]')).toBeVisible({ timeout: 5000 });
    const miMoreBtn = row.locator('[data-testid="row-action-more"]').first();
    await page.evaluate(() => {
      const fab = document.querySelector('[title="Send Feedback"]') as HTMLElement | null;
      if (fab) fab.style.display = 'none';
    });
    await miMoreBtn.click();
    await expect(page.locator('[data-testid="row-action-fail"]').first()).toBeVisible({
      timeout: 3000,
    });
    await page.keyboard.press('Escape');

    // CP site issue: row-action resolve (on in_progress row)
    const issueTitle = `CG SI ${uniqueId()}`;
    const issue = await executeCommandViaApi(page, 'cp:create_issue', {
      cp_si_project_id: projectId,
      cp_si_title: issueTitle,
      cp_si_description: 'Coverage guard issue',
      cp_si_category: 'quality',
      cp_si_severity: 'medium',
      cp_si_reporter: 'Coverage Guard',
    });
    expect(issue.code).toBe('0');
    result = await executeCommandViaApi(
      page,
      'cp:start_issue',
      {},
      issue.recordId,
      'state_transition',
    );
    expect(result.code).toBe('0');

    await navigateToDynamicPage(page, 'cp-site-issue');
    row = await findRowInPaginatedList(page, issueTitle, 12000);
    await row.hover();
    // resolve is the primary action for in_progress issues (directly visible)
    await expect(row.locator('[data-testid="row-action-resolve"]')).toBeVisible({ timeout: 5000 });
  });
});
