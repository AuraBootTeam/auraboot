/**
 * Showcase V2 seed invariants.
 *
 * Run after the Enterprise Showcase V2 seed scripts have executed against the
 * target stack. These checks intentionally use public frontend-facing APIs.
 */

import { expect, test, type APIRequestContext } from '@playwright/test';
import { readFileSync } from 'node:fs';
import path from 'node:path';

type ListBody = {
  code?: string | number;
  data?: any;
  records?: unknown[];
};

type SeedMenu = {
  menuCode: string;
  modelCode: string;
  expectedSearchValue: string;
};

const ENTERPRISE_ROOT =
  process.env.AURABOOT_ENTERPRISE_ROOT || '/Users/ghj/work/auraboot/auraboot-enterprise';
const PCBA_SEED_FILE = path.join(
  ENTERPRISE_ROOT,
  'plugins/pcba-solution/config/demo-data/pcba-demo-20260426.json',
);

const MOBILE_FAVORITES = [
  '宁波鑫越汽车电子',
  'BMS RFQ',
  'BMS 采购比价',
  'FQC 质量告警',
  'Showcase 归档样例',
];

const MOBILE_RECENTS = [
  '宁波鑫越汽车电子',
  'BMS 采样控制板 RFQ',
  'Automotive BMS MCU',
  'BMS FQC 质量告警',
  'BMS 批次追溯',
];

const AI_AGENTS = [
  'showcase_bms_procurement_assistant',
  'showcase_bms_sales_assistant',
];

function extractRecords(body: ListBody | null): unknown[] {
  if (!body) return [];
  if (Array.isArray(body.data)) return body.data;
  return body.data?.records || body.data?.content || body.records || [];
}

function extractTotal(body: ListBody | null): number {
  if (!body) return 0;
  if (Array.isArray(body.data)) return body.data.length;
  const raw =
    body.data?.total ??
    body.data?.totalCount ??
    body.data?.totalElements ??
    body.data?.pagination?.total ??
    body.data?.page?.total ??
    extractRecords(body).length;
  const value = Number(raw);
  return Number.isFinite(value) ? value : 0;
}

function containsValue(value: unknown, expected: string): boolean {
  if (value == null) return false;
  if (typeof value === 'string') return value === expected || value.includes(expected);
  if (typeof value === 'number' || typeof value === 'boolean') return String(value) === expected;
  if (Array.isArray(value)) return value.some((item) => containsValue(item, expected));
  if (typeof value === 'object') {
    return Object.values(value as Record<string, unknown>).some((child) => containsValue(child, expected));
  }
  return false;
}

async function readJson(response: { json: () => Promise<unknown> }): Promise<unknown> {
  return response.json().catch(() => null);
}

function expectApiOk(response: { ok: () => boolean; status: () => number }, body: unknown, label: string): void {
  const failure = `${label}: HTTP ${response.status()} ${JSON.stringify(body).slice(0, 800)}`;
  expect(response.ok(), failure).toBeTruthy();
  expect(String((body as ListBody | null)?.code), failure).toBe('0');
}

async function dynamicList(
  request: APIRequestContext,
  modelCode: string,
  keyword?: string,
  pageSize = 100,
): Promise<{ body: ListBody; records: unknown[]; total: number }> {
  const query = new URLSearchParams({ pageNum: '1', pageSize: String(pageSize) });
  if (keyword) query.set('keyword', keyword);
  const response = await request.get(`/api/dynamic/${modelCode}/list?${query.toString()}`);
  const body = (await readJson(response)) as ListBody;
  expectApiOk(response, body, `${modelCode} list`);
  return { body, records: extractRecords(body), total: extractTotal(body) };
}

async function expectDynamicContains(
  request: APIRequestContext,
  modelCode: string,
  keyword: string,
): Promise<void> {
  const result = await dynamicList(request, modelCode, keyword, 20);
  const found = result.records.some((record) => containsValue(record, keyword));
  expect(found, `${modelCode} should contain ${keyword}`).toBeTruthy();
}

async function expectEngagementLabels(
  request: APIRequestContext,
  engagementType: string,
  targetType: string,
  labels: string[],
): Promise<void> {
  const response = await request.get(
    `/api/user-engagement?engagementType=${encodeURIComponent(engagementType)}&targetType=${encodeURIComponent(targetType)}`,
  );
  const body = (await readJson(response)) as ListBody;
  expectApiOk(response, body, `${engagementType}:${targetType}`);
  const records = extractRecords(body);
  for (const label of labels) {
    expect(
      records.some((record) => containsValue(record, label)),
      `${engagementType}:${targetType} should contain ${label}`,
    ).toBeTruthy();
  }
}

function pcbaDemoMenus(): SeedMenu[] {
  const seed = JSON.parse(readFileSync(PCBA_SEED_FILE, 'utf8')) as { demoMenus?: SeedMenu[] };
  return seed.demoMenus || [];
}

test.describe('Showcase V2 seed invariants', () => {
  test.use({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });
  test.setTimeout(180_000);

  test('automotive BMS records are queryable through Dynamic API', async ({ request }) => {
    await expectDynamicContains(request, 'crm_account', 'PCBA-DEMO-CUSTOMER-BMS-001');
    await expectDynamicContains(request, 'pe_rfq', 'PCBA-DEMO-RFQ-BMS-001');
    await expectDynamicContains(request, 'qc_fqc_order', 'PCBA-DEMO-FQC-BMS-001');
    await expectDynamicContains(request, 'qc_batch_trace', 'PCBA-DEMO-BATCH-BMS-001');
  });

  test('PCBA Demo Flow expected values resolve from every list API', async ({ request }) => {
    const menus = pcbaDemoMenus();
    expect(menus.length, 'PCBA seed should define Demo Flow menu checks').toBeGreaterThan(0);
    for (const menu of menus) {
      await test.step(`${menu.menuCode}: ${menu.expectedSearchValue}`, async () => {
        await expectDynamicContains(request, menu.modelCode, menu.expectedSearchValue);
      });
    }
  });

  test('mobile Objects favorites and recents are seeded for the current user', async ({ request }) => {
    await expectEngagementLabels(request, 'favorite', 'menu_item', MOBILE_FAVORITES);
    await expectEngagementLabels(request, 'recent_view', 'record', MOBILE_RECENTS);
  });

  test('AI assistants and BMS war-room shell are queryable', async ({ request }) => {
    for (const agentCode of AI_AGENTS) {
      const result = await dynamicList(request, 'agent_definition', agentCode, 20);
      expect(
        result.records.some((record) => containsValue(record, agentCode) && containsValue(record, 'BMS')),
        `agent_definition should contain ${agentCode} with BMS prompt context`,
      ).toBeTruthy();
    }

    const response = await request.get('/api/im/conversations?type=group');
    const body = (await readJson(response)) as ListBody;
    expectApiOk(response, body, 'IM group conversations');
    expect(
      extractRecords(body).some((record) => containsValue(record, '宁波鑫越 BMS 项目战情室')),
      'IM group list should contain the BMS war room',
    ).toBeTruthy();
  });

  test('dev-pipeline dogfood records expose clear, blocked, and request-changes states', async ({ request }) => {
    const runs = await dynamicList(request, 'dpl_pipeline_run', undefined, 200);
    const runRecords = runs.records.filter((record) => containsValue(record, 'DPL-DEMO'));
    expect(runRecords.length, 'dpl_pipeline_run should contain dogfood demo runs').toBeGreaterThanOrEqual(3);
    expect(runRecords.some((record) => containsValue(record, 'DPL-DEMO-GREEN-001'))).toBeTruthy();
    expect(runRecords.some((record) => containsValue(record, 'environment-invalid'))).toBeTruthy();
    expect(runRecords.some((record) => containsValue(record, 'REQUEST_CHANGES'))).toBeTruthy();

    const gates = await dynamicList(request, 'dpl_gate_result', undefined, 200);
    const gateRecords = gates.records.filter((record) => containsValue(record, 'DPL-DEMO'));
    expect(gateRecords.some((record) => containsValue(record, 'clear'))).toBeTruthy();
    expect(gateRecords.some((record) => containsValue(record, 'failed'))).toBeTruthy();

    const evidence = await dynamicList(request, 'dpl_evidence', undefined, 200);
    expect(
      evidence.records.some((record) => containsValue(record, 'pipeline/showcase-v2')),
      'dpl_evidence should contain Showcase V2 artifact paths',
    ).toBeTruthy();

    const schedules = await dynamicList(request, 'dpl_schedule_run', undefined, 200);
    expect(
      schedules.records.some((record) => containsValue(record, 'pipeline/showcase-v2')),
      'dpl_schedule_run should contain Showcase V2 report paths',
    ).toBeTruthy();
  });
});
