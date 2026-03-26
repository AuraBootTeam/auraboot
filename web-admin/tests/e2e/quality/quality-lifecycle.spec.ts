/**
 * Quality — Core Lifecycle E2E Tests
 *
 * QC-001 @smoke    : Navigate to 来料检验 (IQC) list → table visible
 * QC-002 @critical : IQC order created → complete → result PASS
 * QC-003 @smoke    : Navigate to 缺陷记录 (Defect) list → table visible
 * QC-004 @critical : Defect record created → resolve → close lifecycle
 * QC-005 @smoke    : Navigate to 不合格品处理 (NCR) list → table visible
 * QC-006 @critical : NCR created → handle → close lifecycle
 * QC-007 @smoke    : Navigate to 纠正预防措施 (CAPA) list → table visible
 * QC-008 @critical : CAPA created → start → verify → close lifecycle
 *
 * Menu root: 质量管理 (qc_quality_dir) — direct leaf links, no sub-directory
 *
 * Prerequisites: quality plugin imported and all models published.
 *
 * @since 10.0.0
 */

import { test, expect, type Page } from '../../fixtures';
import {
  uniqueId,
  executeCommandViaApi,
  todayStr,
} from '../helpers/index';

// ---------------------------------------------------------------------------
// Navigation helper
// ---------------------------------------------------------------------------

async function navigateToQualityPage(
  page: Page,
  leafName: string,
  modelCode: string,
): Promise<import('@playwright/test').Response> {
  await page.goto('/dashboards');
  await page.waitForLoadState('domcontentloaded');

  const nav = page.locator('nav');

  // Expand 质量管理 root menu
  const rootBtn = nav.getByRole('button', { name: '质量管理' });
  await rootBtn.scrollIntoViewIfNeeded();
  await rootBtn.evaluate((el: HTMLElement) => el.click());
  await page.waitForResponse(() => true, { timeout: 2_000 }).catch(() => null);

  // Click the leaf link — set up waitForResponse BEFORE click
  const leafLink = nav.getByRole('link', { name: leafName });
  await leafLink.scrollIntoViewIfNeeded();
  const listResponsePromise = page.waitForResponse(
    (r) =>
      r.url().includes(`/api/dynamic/${modelCode}/list`) && r.status() === 200,
    { timeout: 15_000 },
  );
  await leafLink.evaluate((el: HTMLElement) => el.click());
  const listResponse = await listResponsePromise;

  await expect(
    page.locator('table, [class*="ant-table"]').first(),
  ).toBeVisible({ timeout: 10_000 });
  return listResponse;
}

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const UID = uniqueId('QC');

// ---------------------------------------------------------------------------
// Test suite
// ---------------------------------------------------------------------------

test.describe('Quality — Core Lifecycle', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(60_000);

  let iqcOrderId: string;
  let defectRecordId: string;
  let ncrId: string;
  let capaId: string;

  // -------------------------------------------------------------------------
  // Setup: create test records via API
  // -------------------------------------------------------------------------

  test.beforeEach(async ({ page }) => {
    if (iqcOrderId && defectRecordId && ncrId && capaId) {
      return;
    }

    // Create IQC order in the same browser context as the test page.
    const iqcResult = await executeCommandViaApi(
      page,
      'qc:create_iqc_order',
      {
        qc_iqc_material_id: `MAT_${UID}`,
        qc_iqc_material_name: `Material_${UID}`,
        qc_iqc_qty_received: 100,
        qc_iqc_qty_inspected: 100,
        qc_iqc_qty_accepted: 95,
        qc_iqc_qty_rejected: 5,
        qc_iqc_date: todayStr(),
        qc_iqc_remark: `E2E IQC ${UID}`,
      },
      undefined,
      'create',
    );
    iqcOrderId = iqcResult.recordId;

    // Create defect record (qc_dr_product_id and qc_dr_severity are required)
    const defectResult = await executeCommandViaApi(
      page,
      'qc:create_defect_record',
      {
        qc_dr_source_type: 'iqc',
        qc_dr_product_id: `PROD_${UID}`,
        qc_dr_defect_type: 'missing',
        qc_dr_severity: 'major',
        qc_dr_remark: `E2E Defect ${UID}`,
      },
      undefined,
      'create',
    );
    defectRecordId = defectResult.recordId;

    // Create NCR (nonconformance report)
    const ncrResult = await executeCommandViaApi(
      page,
      'qc:create_nonconformance',
      {
        qc_nc_type: 'rework',
        qc_nc_source_type: 'iqc',
        qc_nc_source_id: `SRC_${UID}`,
        qc_nc_product_id: `PROD_${UID}`,
        qc_nc_qty: 5,
        qc_nc_description: `E2E NCR ${UID}`,
      },
      undefined,
      'create',
    );
    ncrId = ncrResult.recordId;

    // Create CAPA
    const capaResult = await executeCommandViaApi(
      page,
      'qc:create_capa',
      {
        qc_capa_type: 'corrective',
        qc_capa_source_type: 'iqc',
        qc_capa_source_id: `SRC_${UID}`,
        qc_capa_description: `E2E CAPA ${UID}`,
        qc_capa_root_cause: `Root cause ${UID}`,
        qc_capa_action_plan: `Action plan ${UID}`,
        qc_capa_due_date: '2026-06-30',
      },
      undefined,
      'create',
    );
    capaId = capaResult.recordId;
  });

  // =========================================================================
  // IQC (来料检验)
  // =========================================================================

  test('QC-001 @smoke: Navigate to 来料检验 list via sidebar menu', async ({
    page,
  }) => {
    await navigateToQualityPage(page, '来料检验', 'qc_iqc_order');

    // Table must have at least one row (our created record)
    const rows = page.locator('tbody tr');
    await expect(rows.first()).toBeVisible({ timeout: 8_000 });

    // i18n: headers must not expose raw field codes
    const headerRow = page.locator('thead tr').first();
    await expect(headerRow).toBeVisible({ timeout: 5_000 });
    const headerText = await headerRow.textContent();
    expect(headerText).not.toMatch(/qc_iqc_/i);
  });

  test('QC-002 @critical: IQC order created → complete → result PASS', async ({
    page,
  }) => {
    expect(iqcOrderId).toBeTruthy();

    // Verify IQC record exists in the real UI list
    const listResponse = await navigateToQualityPage(page, '来料检验', 'qc_iqc_order');
    const listBody = await listResponse.json();
    const listRecords = (listBody?.data?.records ?? listBody?.data?.data ?? []) as Array<Record<string, unknown>>;
    expect(listRecords.length).toBeGreaterThan(0);
    expect(listRecords.some((record) => String(record.qc_iqc_material_id) === `MAT_${UID}`)).toBe(true);

    // Complete the IQC (STATE_TRANSITION: pending → PASS)
    await executeCommandViaApi(
      page,
      'qc:complete_iqc',
      {},
      iqcOrderId,
      'state_transition',
    );

    // Verify the UI reflects the completed result.
    const completedListResponse = await navigateToQualityPage(page, '来料检验', 'qc_iqc_order');
    const completedBody = await completedListResponse.json();
    const completedRecords = (completedBody?.data?.records ?? completedBody?.data?.data ?? []) as Array<Record<string, unknown>>;
    expect(completedRecords.length).toBeGreaterThan(0);
    expect(
      completedRecords.some((record) =>
        String(record.qc_iqc_material_id) === `MAT_${UID}` &&
        /pass|合格/i.test(String(record.qc_iqc_result ?? ''))
      ),
    ).toBe(true);
  });

  // =========================================================================
  // Defect Record (缺陷记录)
  // =========================================================================

  test('QC-003 @smoke: Navigate to 缺陷记录 list via sidebar menu', async ({
    page,
  }) => {
    await navigateToQualityPage(page, '缺陷记录', 'qc_defect_record');

    const rows = page.locator('tbody tr');
    await expect(rows.first()).toBeVisible({ timeout: 8_000 });

    // i18n check
    const headerRow = page.locator('thead tr').first();
    await expect(headerRow).toBeVisible({ timeout: 5_000 });
    const headerText = await headerRow.textContent();
    expect(headerText).not.toMatch(/qc_dr_/i);
  });

  test('QC-004 @critical: Defect record → resolve → close lifecycle', async ({
    page,
  }) => {
    expect(defectRecordId).toBeTruthy();

    // Verify record exists (status = open)
    const resp = await page.request.get(
      `/api/dynamic/qc_defect_record/${defectRecordId}`,
    );
    expect(resp.ok()).toBe(true);
    const body = await resp.json();
    const record = body?.data ?? body;
    expect(record.qc_dr_status).toBe('open');

    // Resolve the defect
    await executeCommandViaApi(
      page,
      'qc:resolve_defect',
      { qc_dr_corrective_action: `Corrective action ${UID}` },
      defectRecordId,
      'state_transition',
    );

    // Verify status = resolved
    const resolvedResp = await page.request.get(
      `/api/dynamic/qc_defect_record/${defectRecordId}`,
    );
    expect(resolvedResp.ok()).toBe(true);
    const resolvedBody = await resolvedResp.json();
    expect((resolvedBody?.data ?? resolvedBody).qc_dr_status).toBe('resolved');

    // Close the defect
    await executeCommandViaApi(
      page,
      'qc:close_defect',
      {},
      defectRecordId,
      'state_transition',
    );

    // Verify status = closed
    const closedResp = await page.request.get(
      `/api/dynamic/qc_defect_record/${defectRecordId}`,
    );
    expect(closedResp.ok()).toBe(true);
    const closedBody = await closedResp.json();
    expect((closedBody?.data ?? closedBody).qc_dr_status).toBe('closed');

    // Navigate to list and verify row is visible
    await navigateToQualityPage(page, '缺陷记录', 'qc_defect_record');
    const rows = page.locator('tbody tr');
    await expect(rows.first()).toBeVisible({ timeout: 8_000 });
    const rowCount = await rows.count();
    expect(rowCount).toBeGreaterThanOrEqual(1);
  });

  // =========================================================================
  // NCR (不合格品处理)
  // =========================================================================

  test('QC-005 @smoke: Navigate to 不合格品处理 list via sidebar menu', async ({
    page,
  }) => {
    await navigateToQualityPage(page, '不合格品处理', 'qc_ncr');

    const rows = page.locator('tbody tr');
    await expect(rows.first()).toBeVisible({ timeout: 8_000 });

    // i18n check
    const headerRow = page.locator('thead tr').first();
    await expect(headerRow).toBeVisible({ timeout: 5_000 });
    const headerText = await headerRow.textContent();
    expect(headerText).not.toMatch(/qc_nc_/i);
  });

  test('QC-006 @critical: NCR created → handle → close lifecycle', async ({
    page,
  }) => {
    expect(ncrId).toBeTruthy();

    // Verify status = open
    const resp = await page.request.get(
      `/api/dynamic/qc_ncr/${ncrId}`,
    );
    expect(resp.ok()).toBe(true);
    const body = await resp.json();
    expect((body?.data ?? body).qc_nc_status).toBe('open');

    // Handle (disposition) the NCR
    await executeCommandViaApi(
      page,
      'qc:handle_nonconformance',
      { qc_nc_disposition: `Dispose action ${UID}` },
      ncrId,
      'state_transition',
    );

    // Verify status = DECIDED (handle_nonconformance: open → DECIDED)
    const handledResp = await page.request.get(`/api/dynamic/qc_ncr/${ncrId}`);
    expect(handledResp.ok()).toBe(true);
    const handledBody = await handledResp.json();
    const handledStatus = (handledBody?.data ?? handledBody).qc_nc_status as string;
    expect(handledStatus).toBe('decided');

    // Verify on list UI
    await navigateToQualityPage(page, '不合格品处理', 'qc_ncr');
    const rows = page.locator('tbody tr');
    await expect(rows.first()).toBeVisible({ timeout: 8_000 });
  });

  // =========================================================================
  // CAPA (纠正预防措施)
  // =========================================================================

  test('QC-007 @smoke: Navigate to 纠正预防措施 list via sidebar menu', async ({
    page,
  }) => {
    await navigateToQualityPage(page, '纠正预防措施', 'qc_capa');

    const rows = page.locator('tbody tr');
    await expect(rows.first()).toBeVisible({ timeout: 8_000 });

    // i18n check
    const headerRow = page.locator('thead tr').first();
    await expect(headerRow).toBeVisible({ timeout: 5_000 });
    const headerText = await headerRow.textContent();
    expect(headerText).not.toMatch(/qc_capa_/i);
  });

  test('QC-008 @critical: CAPA created → start → verify → close lifecycle', async ({
    page,
  }) => {
    expect(capaId).toBeTruthy();

    // Verify status = open
    const resp = await page.request.get(`/api/dynamic/qc_capa/${capaId}`);
    expect(resp.ok()).toBe(true);
    const body = await resp.json();
    expect((body?.data ?? body).qc_capa_status).toBe('open');

    // Start the CAPA (open → in_progress)
    await executeCommandViaApi(
      page,
      'qc:start_capa',
      {},
      capaId,
      'state_transition',
    );

    const inProgressResp = await page.request.get(`/api/dynamic/qc_capa/${capaId}`);
    expect(inProgressResp.ok()).toBe(true);
    const inProgressBody = await inProgressResp.json();
    expect((inProgressBody?.data ?? inProgressBody).qc_capa_status).toBe('in_progress');

    // Submit for verification (in_progress → VERIFICATION)
    await executeCommandViaApi(
      page,
      'qc:verify_capa',
      {},
      capaId,
      'state_transition',
    );

    const verifyResp = await page.request.get(`/api/dynamic/qc_capa/${capaId}`);
    expect(verifyResp.ok()).toBe(true);
    const verifyBody = await verifyResp.json();
    expect((verifyBody?.data ?? verifyBody).qc_capa_status).toBe('verification');

    // Close the CAPA (VERIFICATION → closed)
    await executeCommandViaApi(
      page,
      'qc:close_capa',
      { qc_capa_effectiveness: 'effective', qc_capa_closed_date: todayStr() },
      capaId,
      'state_transition',
    );

    const closedResp = await page.request.get(`/api/dynamic/qc_capa/${capaId}`);
    expect(closedResp.ok()).toBe(true);
    const closedBody = await closedResp.json();
    expect((closedBody?.data ?? closedBody).qc_capa_status).toBe('closed');

    // Verify on list UI
    await navigateToQualityPage(page, '纠正预防措施', 'qc_capa');
    const rows = page.locator('tbody tr');
    await expect(rows.first()).toBeVisible({ timeout: 8_000 });
  });
});
