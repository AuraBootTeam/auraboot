import type { Page } from '@playwright/test';
import { expect } from '../../fixtures';

export type CreatedRows = {
  quoteId: string;
  quoteCode: string;
  rows: Array<{ model: string; pid: string }>;
};

export type DynamicFilter = {
  fieldName: string;
  operator: string;
  value: unknown;
};

export type BomPriceManualReviewSeed = CreatedRows & {
  lineId: string;
  mpn: string;
  suggestedEvidenceId: string;
  failedEvidenceId: string;
};

type QuoteLineSeed = {
  sourceRef: string;
  sourceRowNo: number;
  description: string;
  refdes: string;
  mpn: string;
  packageName: string;
  qty: number;
  itemType?: string;
  unitCost?: number;
  lineCost?: number;
  linePrice?: number;
  smtPoints: number;
  thtPoints: number;
  boardWidthMm?: number;
  boardHeightMm?: number;
  boardAreaMm2?: number;
  gerberParseStatus?: string;
  gerberValidationStatus?: string;
  gerberValidationMessages?: string[];
  gerberInspection?: Record<string, unknown>;
};

export const GERBER_RUNTIME_TOP_FILE_ID = '01KV22CQ7PKX3W50Y7MM575ACK';
export const GERBER_RUNTIME_BOTTOM_FILE_ID = '01KV22CQ7PKX3W50Y7MM575ACM';

async function pollAsyncTaskResult(page: Page, taskCode: string): Promise<Record<string, unknown>> {
  const terminal = new Set(['completed', 'failed', 'cancelled']);
  let resultData: Record<string, unknown> = {};

  await expect
    .poll(
      async () => {
        const resp = await page.request.get(`/api/async-tasks/${encodeURIComponent(taskCode)}`, {
          timeout: 15_000,
        });
        const body = await resp.json().catch(() => ({}));
        if (!resp.ok()) {
          return `http:${resp.status()}:${JSON.stringify(body).slice(0, 500)}`;
        }
        const task = ((body as any).data ?? {}) as Record<string, unknown>;
        const status = String(task.status ?? '').toLowerCase();
        if (terminal.has(status)) {
          if (status === 'completed') {
            resultData = ((task as any).resultData ?? {}) as Record<string, unknown>;
            return 'completed';
          }
          return `terminal:${status}:${JSON.stringify(task).slice(0, 800)}`;
        }
        return status || 'pending';
      },
      {
        timeout: 180_000,
        intervals: [1000, 1500, 2000, 3000],
        message: `async task ${taskCode} should complete`,
      },
    )
    .toBe('completed');

  return resultData;
}

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
  expect(
    resp.ok(),
    `${commandCode} HTTP ${resp.status()}: ${JSON.stringify(body).slice(0, 500)}`,
  ).toBe(true);
  expect(String((body as any).code), `${commandCode} should return code=0`).toBe('0');
  const commandData = ((body as any).data?.data ?? {}) as Record<string, unknown>;
  if (commandData.async === true && typeof commandData.taskCode === 'string') {
    return pollAsyncTaskResult(page, commandData.taskCode);
  }
  return commandData;
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
  expect(
    resp.ok(),
    `${model} create HTTP ${resp.status()}: ${JSON.stringify(body).slice(0, 500)}`,
  ).toBe(true);
  const record = ((body as any).data?.data ?? (body as any).data ?? body) as Record<
    string,
    unknown
  >;
  const pid = String(record.pid ?? record.recordId ?? record.id ?? '');
  expect(pid, `${model} create should return pid`).toBeTruthy();
  rows.push({ model, pid });
  return pid;
}

function extractRecords(body: unknown): Record<string, unknown>[] {
  const root = body as any;
  const data = root?.data?.data ?? root?.data ?? root;
  if (Array.isArray(data)) return data as Record<string, unknown>[];
  if (Array.isArray(data?.records)) return data.records as Record<string, unknown>[];
  if (Array.isArray(data?.data)) return data.data as Record<string, unknown>[];
  if (Array.isArray(data?.list)) return data.list as Record<string, unknown>[];
  if (Array.isArray(data?.items)) return data.items as Record<string, unknown>[];
  return [];
}

export async function readDynamicRecord(
  page: Page,
  model: string,
  pid: string,
): Promise<Record<string, unknown>> {
  const resp = await page.request.get(`/api/dynamic/${model}/${pid}`, { timeout: 15_000 });
  const body = await resp.json().catch(() => ({}));
  expect(
    resp.ok(),
    `${model}/${pid} HTTP ${resp.status()}: ${JSON.stringify(body).slice(0, 500)}`,
  ).toBe(true);
  const record = ((body as any).data?.data ?? (body as any).data ?? body) as Record<
    string,
    unknown
  >;
  expect(record?.pid ?? record?.id, `${model}/${pid} should return a record`).toBeTruthy();
  return record;
}

export async function queryDynamicRecords(
  page: Page,
  model: string,
  filters: DynamicFilter[],
  options: { pageSize?: number; timeout?: number } = {},
): Promise<Record<string, unknown>[]> {
  const filtersParam = encodeURIComponent(JSON.stringify(filters));
  const pageSize = options.pageSize ?? 50;
  const resp = await page.request.get(
    `/api/dynamic/${model}/list?pageNum=1&pageSize=${pageSize}&filters=${filtersParam}`,
    { timeout: options.timeout ?? 15_000 },
  );
  const body = await resp.json().catch(() => ({}));
  expect(
    resp.ok(),
    `${model} list HTTP ${resp.status()}: ${JSON.stringify(body).slice(0, 500)}`,
  ).toBe(true);
  return extractRecords(body);
}

export async function queryNamedDataSourceRecords(
  page: Page,
  queryCode: string,
  params: Record<string, unknown>,
): Promise<Record<string, unknown>[]> {
  const search = new URLSearchParams({
    datasourceId: `nq:${queryCode}`,
    valueField: 'pid',
    labelField: 'name',
    format: 'records',
  });
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== null) search.set(key, String(value));
  }

  const resp = await page.request.get(`/api/datasource/list?${search.toString()}`, {
    timeout: 15_000,
  });
  const body = await resp.json().catch(() => ({}));
  expect(
    resp.ok(),
    `${queryCode} datasource HTTP ${resp.status()}: ${JSON.stringify(body).slice(0, 500)}`,
  ).toBe(true);
  expect(String((body as any).code), `${queryCode} datasource should return code=0`).toBe('0');
  return extractRecords(body);
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
    let pcbaRfqId = await dynamicCreate(
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
    const quoteResult = await executeCommand(
      page,
      'qo_quote_common:create',
      {
        qo_quote_customer: `E2E ${marker} Customer ${suffix}`,
        qo_quote_code: quoteCode,
        qo_quote_customer_request_id: customerRequestId,
        qo_quote_tax_rate: 0.13,
        qo_quote_factory_class: factoryClass,
      },
      undefined,
      'create',
    );
    const quoteId = String(
      quoteResult.recordId ??
        quoteResult.quoteId ??
        quoteResult.pid ??
        ((quoteResult.quote as Record<string, unknown> | undefined)?.pid ?? ''),
    );
    expect(quoteId, 'qo_quote_common:create should return quote id').toBeTruthy();
    created.rows.push({ model: 'qo_quote_common', pid: quoteId });
    created.quoteId = quoteId;
    const returnedPcbaRfqId = String(quoteResult.pcbaRfqId ?? '');
    if (returnedPcbaRfqId && returnedPcbaRfqId !== pcbaRfqId) {
      pcbaRfqId = returnedPcbaRfqId;
      created.rows.push({ model: 'crm_customer_request_pcba_rfq', pid: pcbaRfqId });
    }

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
          qo_ql_item_type: line.itemType ?? 'component',
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
          ...(line.boardWidthMm !== undefined ? { qo_ql_board_width_mm: line.boardWidthMm } : {}),
          ...(line.boardHeightMm !== undefined
            ? { qo_ql_board_height_mm: line.boardHeightMm }
            : {}),
          ...(line.boardAreaMm2 !== undefined ? { qo_ql_board_area_mm2: line.boardAreaMm2 } : {}),
          ...(line.gerberParseStatus ? { qo_ql_gerber_parse_status: line.gerberParseStatus } : {}),
          ...(line.gerberValidationStatus
            ? { qo_ql_gerber_validation_status: line.gerberValidationStatus }
            : {}),
          ...(line.gerberValidationMessages
            ? { qo_ql_gerber_validation_messages: line.gerberValidationMessages }
            : {}),
          ...(line.gerberInspection ? { qo_ql_gerber_inspection: line.gerberInspection } : {}),
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
    await executeCommand(
      page,
      'qo_quote_common:compute_process_fee',
      {},
      created.quoteId,
      'update',
    );
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
  const created = await seedQuoteScaffold(page, 'PFR', [
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
  ]);

  try {
    await executeCommand(
      page,
      'qo_quote_common:compute_process_fee',
      {},
      created.quoteId,
      'update',
    );
    return created;
  } catch (error) {
    await cleanupRows(page, created);
    throw error;
  }
}

export async function seedBomPriceManualReviewQuote(page: Page): Promise<BomPriceManualReviewSeed> {
  const suffix = `${Date.now()}${Math.random().toString(16).slice(2, 8)}`;
  const mpn = `E2E-MANUAL-${suffix}`;
  const created = (await seedQuoteScaffold(page, 'BPM', [
    {
      sourceRef: `BOM-BPM-MANUAL-${suffix}`,
      sourceRowNo: 2,
      description: 'Manual price E2E resistor',
      refdes: 'R10',
      mpn,
      packageName: '0603',
      qty: 10,
      unitCost: 0,
      lineCost: 0,
      linePrice: 0,
      smtPoints: 1,
      thtPoints: 0,
    },
  ])) as BomPriceManualReviewSeed;

  try {
    const lineId = created.rows.find((row) => row.model === 'qo_quote_line_common')?.pid ?? '';
    expect(lineId, 'BOM price manual review seed should create one quote line').toBeTruthy();
    created.lineId = lineId;
    created.mpn = mpn;

    created.suggestedEvidenceId = await dynamicCreate(
      page,
      'qo_price_evidence_common',
      {
        qo_pe_quote_line_id: lineId,
        qo_pe_part_no: mpn,
        qo_pe_source: 'deepseek_llm',
        qo_pe_source_ref: `e2e-deepseek-${suffix}`,
        qo_pe_supplier_name: 'DeepSeek AI',
        qo_pe_unit_price: 1.1111,
        qo_pe_currency: 'CNY',
        qo_pe_moq: 1,
        qo_pe_mpq: 1,
        qo_pe_confidence: 0.42,
        qo_pe_valid_until: '2030-12-31',
        qo_pe_status: 'suggested',
        qo_pe_snapshot: {
          source: 'deepseek_llm',
          suggestion: 'E2E AI candidate price',
          queryPartNo: mpn,
        },
      },
      created.rows,
    );

    created.failedEvidenceId = await dynamicCreate(
      page,
      'qo_price_evidence_common',
      {
        qo_pe_quote_line_id: lineId,
        qo_pe_part_no: mpn,
        qo_pe_source: 'kingdee_purchase_history',
        qo_pe_source_ref: `e2e-kingdee-not-found-${suffix}`,
        qo_pe_supplier_name: 'Kingdee history',
        qo_pe_currency: 'CNY',
        qo_pe_confidence: 0,
        qo_pe_status: 'not_found',
        qo_pe_override_reason: 'E2E historical price missing',
        qo_pe_snapshot: {
          source: 'kingdee_purchase_history',
          failureCode: 'price_not_found',
          queryPartNo: mpn,
        },
      },
      created.rows,
    );

    return created;
  } catch (error) {
    await cleanupRows(page, created);
    throw error;
  }
}

export async function seedGerberRuntimeQuote(page: Page): Promise<CreatedRows> {
  const suffix = `${Date.now()}${Math.random().toString(16).slice(2, 8)}`;
  return seedQuoteScaffold(page, 'GERBER', [
    {
      sourceRef: `GERBER-E2E-${suffix}`,
      sourceRowNo: 2,
      description: 'E2E Gerber runtime board',
      refdes: 'C1,J1',
      mpn: `E2E-GERBER-${suffix}`,
      packageName: 'E2E-GERBER-PKG',
      qty: 1,
      unitCost: 0.5,
      lineCost: 0.5,
      linePrice: 1,
      smtPoints: 2,
      thtPoints: 1,
      boardWidthMm: 42,
      boardHeightMm: 18,
      boardAreaMm2: 756,
      gerberParseStatus: 'parsed',
      gerberValidationStatus: 'warning',
      gerberValidationMessages: ['E2E_ALIGNMENT_WARNING'],
      gerberInspection: {
        project: {
          code: 'E2E-GERBER-RUNTIME',
          name: 'Dynamic line persisted Gerber inspection',
        },
        board: {
          xMinMm: 0,
          yMinMm: 0,
          xMaxMm: 42,
          yMaxMm: 18,
          widthMm: 42,
          heightMm: 18,
        },
        boardSvgUrls: {
          top: `/${GERBER_RUNTIME_TOP_FILE_ID}.svg`,
          bottom: `/${GERBER_RUNTIME_BOTTOM_FILE_ID}.svg`,
        },
        summary: {
          bomRefCount: 2,
          cplRefCount: 2,
          smdCount: 2,
          thtCount: 1,
          errorCount: 0,
          warningCount: 1,
        },
        layerManifest: [
          {
            filename: 'E2E-TopLayer.GTL',
            role: 'top_copper',
            side: 'top',
            kind: 'gerber',
            flashCount: 2,
          },
          {
            filename: 'E2E-BottomLayer.GBL',
            role: 'bottom_copper',
            side: 'bottom',
            kind: 'gerber',
            flashCount: 1,
          },
        ],
        drillFiles: [{ filename: 'E2E-PTH.DRL', plated: true, hitCount: 1 }],
        issues: [
          {
            severity: 'warning',
            code: 'E2E_ALIGNMENT_WARNING',
            refdes: 'J1',
            message: 'E2E warning generated from persisted inspection JSON.',
          },
        ],
        components: [
          {
            refdes: 'C1',
            footprint: 'C0603',
            xMm: 10,
            yMm: 6,
            side: 'top',
            smd: true,
            pins: 2,
            rotation: 90,
            issues: [],
            bomItem: { materialName: 'E2E capacitor', process: 'SMT' },
          },
          {
            refdes: 'J1',
            footprint: 'HDR-2P',
            xMm: 28,
            yMm: 12,
            side: 'bottom',
            smd: false,
            pins: 2,
            rotation: 180,
            issues: [
              {
                severity: 'warning',
                code: 'E2E_ALIGNMENT_WARNING',
                refdes: 'J1',
                message: 'E2E bottom-side marker warning.',
              },
            ],
            bomItem: { materialName: 'E2E header', process: 'DIP' },
          },
        ],
      },
    },
  ]);
}
