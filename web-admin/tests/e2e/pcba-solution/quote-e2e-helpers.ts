import type { Page } from '@playwright/test';
import { expect } from '../../fixtures';

export type CreatedRows = {
  quoteId: string;
  quoteCode: string;
  rows: Array<{ model: string; pid: string }>;
};

type QuoteLineSeed = {
  sourceRef: string;
  sourceRowNo: number;
  description: string;
  refdes: string;
  mpn: string;
  packageName: string;
  qty: number;
  unitCost?: number;
  lineCost?: number;
  linePrice?: number;
  smtPoints: number;
  thtPoints: number;
};

export async function executeCommand(
  page: Page,
  commandCode: string,
  payload: Record<string, unknown> = {},
  targetRecordId?: string,
  operationType?: string,
): Promise<Record<string, unknown>> {
  const data: Record<string, unknown> = { payload };
  if (targetRecordId) data.targetRecordId = targetRecordId;
  if (operationType) data.operationType = operationType;
  const resp = await page.request.post(`/api/meta/commands/execute/${commandCode}`, {
    data,
    timeout: 30_000,
  });
  const body = await resp.json().catch(() => ({}));
  expect(resp.ok(), `${commandCode} HTTP ${resp.status()}: ${JSON.stringify(body).slice(0, 500)}`).toBe(true);
  expect(String((body as any).code), `${commandCode} should return code=0`).toBe('0');
  return ((body as any).data?.data ?? {}) as Record<string, unknown>;
}

export async function dynamicCreate(
  page: Page,
  model: string,
  data: Record<string, unknown>,
  rows: CreatedRows['rows'],
): Promise<string> {
  const resp = await page.request.post(`/api/dynamic/${model}/create`, {
    data,
    timeout: 15_000,
  });
  const body = await resp.json().catch(() => ({}));
  expect(resp.ok(), `${model} create HTTP ${resp.status()}: ${JSON.stringify(body).slice(0, 500)}`).toBe(true);
  const record = ((body as any).data?.data ?? (body as any).data ?? body) as Record<string, unknown>;
  const pid = String(record.pid ?? record.recordId ?? record.id ?? '');
  expect(pid, `${model} create should return pid`).toBeTruthy();
  rows.push({ model, pid });
  return pid;
}

export async function cleanupRows(page: Page, created: CreatedRows): Promise<void> {
  for (const row of [...created.rows].reverse()) {
    await page.request.delete(`/api/dynamic/${row.model}/${row.pid}`).catch(() => {});
  }
  if (created.quoteId) {
    await page.request.delete(`/api/dynamic/qo_quote_common/${created.quoteId}`).catch(() => {});
  }
}

async function seedQuoteScaffold(
  page: Page,
  marker: string,
  lines: QuoteLineSeed[],
  factoryClass = 'consumer',
): Promise<CreatedRows> {
  const suffix = `${Date.now()}${Math.random().toString(16).slice(2, 8)}`;
  const quoteCode = `QO-E2E-${marker}-${suffix}`;
  const created: CreatedRows = { quoteId: '', quoteCode, rows: [] };

  try {
    const customerRequestId = await dynamicCreate(
      page,
      'crm_customer_request_common',
      {
        crm_cr_code: `CR-E2E-${marker}-${suffix}`,
        crm_cr_title: `E2E ${marker} request ${suffix}`,
        crm_cr_type: 'pcba_quote',
        crm_cr_status: 'draft',
        crm_cr_priority: 'normal',
        crm_cr_source_channel: `quote_${marker.toLowerCase()}_e2e`,
      },
      created.rows,
    );
    const pcbaRfqId = await dynamicCreate(
      page,
      'crm_customer_request_pcba_rfq',
      {
        crm_crq_code: `PCBA-RFQ-E2E-${marker}-${suffix}`,
        crm_customer_request_id: customerRequestId,
        crm_crq_product_model: `E2E-BOARD-${marker}-${suffix}`,
        crm_crq_board_count: 3,
        crm_crq_board_layer: 4,
        crm_crq_pcba_qty: 3,
        crm_crq_assembly_type: 'SMT',
        crm_crq_delivery_class: 'standard',
        crm_crq_dfm_status: 'pending',
        crm_crq_bom_status: 'pending',
      },
      created.rows,
    );
    const quoteId = await dynamicCreate(
      page,
      'qo_quote_common',
      {
        qo_quote_customer: `E2E ${marker} Customer ${suffix}`,
        qo_quote_code: quoteCode,
        qo_quote_status: 'draft',
        qo_quote_version_no: 1,
        qo_quote_customer_request_id: customerRequestId,
        qo_quote_tax_rate: 0.13,
        qo_quote_factory_class: factoryClass,
        qo_quote_industry: 'pcba',
      },
      created.rows,
    );
    created.quoteId = quoteId;

    await dynamicCreate(
      page,
      'qo_rfq_source_attachment_common',
      {
        qo_rsa_rfq_id: pcbaRfqId,
        qo_rsa_type: 'raw_bom',
        qo_rsa_filename: `bom-${suffix}.xlsx`,
        qo_rsa_file_id: `e2e-raw-bom-${suffix}`,
        qo_rsa_version_no: 1,
        qo_rsa_parse_status: 'parsed',
        qo_rsa_validation_status: 'passed',
        qo_rsa_uploaded_at: new Date().toISOString(),
      },
      created.rows,
    );
    await dynamicCreate(
      page,
      'qo_rfq_source_attachment_common',
      {
        qo_rsa_rfq_id: pcbaRfqId,
        qo_rsa_type: 'gerber_package',
        qo_rsa_filename: `gerber-${suffix}.zip`,
        qo_rsa_file_id: `e2e-gerber-${suffix}`,
        qo_rsa_version_no: 1,
        qo_rsa_parse_status: 'parsed',
        qo_rsa_validation_status: 'passed',
        qo_rsa_uploaded_at: new Date().toISOString(),
      },
      created.rows,
    );

    for (const line of lines) {
      await dynamicCreate(
        page,
        'qo_quote_line_common',
        {
          qo_ql_quote_id: quoteId,
          qo_ql_item_type: 'component',
          qo_ql_source_ref: line.sourceRef,
          qo_ql_source_row_no: line.sourceRowNo,
          qo_ql_description: line.description,
          qo_ql_refdes: line.refdes,
          qo_ql_mpn: line.mpn,
          qo_ql_package: line.packageName,
          qo_ql_qty: line.qty,
          qo_ql_unit: 'PCS',
          qo_ql_unit_cost: line.unitCost ?? 0,
          qo_ql_line_cost: line.lineCost ?? 0,
          qo_ql_line_price: line.linePrice ?? 0,
          qo_ql_smt_points: line.smtPoints,
          qo_ql_tht_points: line.thtPoints,
          qo_ql_risk: 'ok',
          qo_ql_validation_status: 'confirmed',
        },
        created.rows,
      );
    }

    return created;
  } catch (error) {
    await cleanupRows(page, created);
    throw error;
  }
}

export async function seedDownloadableQuote(page: Page): Promise<CreatedRows> {
  const created = await seedQuoteScaffold(page, 'XLSX', [
    {
      sourceRef: 'BOM-XLSX-1',
      sourceRowNo: 2,
      description: 'STM32F103C8T6 MCU',
      refdes: 'U1',
      mpn: 'STM32F103C8T6',
      packageName: 'LQFP48',
      qty: 3,
      unitCost: 1.25,
      lineCost: 3.75,
      linePrice: 5,
      smtPoints: 2,
      thtPoints: 0,
    },
  ]);
  try {
    await executeCommand(page, 'qo_quote_common:compute_process_fee', {}, created.quoteId, 'update');
    await executeCommand(
      page,
      'qo_quote_common:override_process_fee',
      {
        amount: 1.2,
        reason: 'E2E manual confirmation for generated Excel download',
      },
      created.quoteId,
      'update',
    );
    await executeCommand(page, 'qo_quote_common:rollup_cost', {}, created.quoteId, 'update');
    return created;
  } catch (error) {
    await cleanupRows(page, created);
    throw error;
  }
}

export async function seedProcessFeeReviewQuote(page: Page): Promise<CreatedRows> {
  const suffix = `${Date.now()}${Math.random().toString(16).slice(2, 8)}`;
  const created = await seedQuoteScaffold(
    page,
    'PFR',
    [
      {
        sourceRef: 'BOM-PFR-UNMATCHED',
        sourceRowNo: 2,
        description: 'Unmatched process-fee package',
        refdes: 'U9',
        mpn: `E2E-UNMATCHED-${suffix}`,
        packageName: `NO_RULE_PKG_${suffix}`,
        qty: 3,
        unitCost: 0.5,
        lineCost: 1.5,
        linePrice: 2,
        smtPoints: 2,
        thtPoints: 0,
      },
      {
        sourceRef: 'BOM-PFR-MIXED',
        sourceRowNo: 3,
        description: 'Mixed SMT and DIP row requiring manual review',
        refdes: 'U10,J10',
        mpn: `E2E-MIXED-${suffix}`,
        packageName: 'MIXED-PKG',
        qty: 2,
        unitCost: 0.75,
        lineCost: 1.5,
        linePrice: 2.5,
        smtPoints: 1,
        thtPoints: 1,
      },
    ],
  );

  try {
    await executeCommand(page, 'qo_quote_common:compute_process_fee', {}, created.quoteId, 'update');
    return created;
  } catch (error) {
    await cleanupRows(page, created);
    throw error;
  }
}
