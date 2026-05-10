/**
 * Showcase Demo Seed Invariants
 *
 * These tests run after the showcase seed scripts. They intentionally fail
 * when a seed command reported success but the expected demo resources are
 * not queryable through the same API surface used by the frontend.
 */

import { expect, test, type APIRequestContext } from '@playwright/test';

interface ListResponse {
  code?: string;
  data?: {
    records?: unknown[];
    total?: number | string;
    totalCount?: number | string;
    totalElements?: number | string;
    pagination?: { total?: number | string };
    page?: { total?: number | string };
  };
}

async function readJson(response: { json: () => Promise<unknown> }): Promise<unknown> {
  return response.json().catch(() => null);
}

function extractTotal(body: ListResponse): number {
  const raw =
    body.data?.total ??
    body.data?.totalCount ??
    body.data?.totalElements ??
    body.data?.pagination?.total ??
    body.data?.page?.total ??
    body.data?.records?.length ??
    0;

  const value = Number(raw);
  return Number.isFinite(value) ? value : 0;
}

async function expectDynamicListHasRecords(
  request: APIRequestContext,
  modelCode: string,
): Promise<number> {
  const response = await request.get(`/api/dynamic/${modelCode}/list?pageNum=1&pageSize=1`);
  const body = (await readJson(response)) as ListResponse | null;
  const failure = `${modelCode} seed invariant failed: ${response.status()} ${JSON.stringify(body)}`;

  expect(response.ok(), failure).toBeTruthy();
  expect(body?.code, failure).toBe('0');

  const total = extractTotal(body ?? {});
  expect(total, failure).toBeGreaterThan(0);
  return total;
}

async function expectDashboardExists(
  request: APIRequestContext,
  code: string,
): Promise<void> {
  const response = await request.get(`/api/dashboards/code/${code}`);
  const body = await readJson(response);
  const failure = `${code} dashboard invariant failed: ${response.status()} ${JSON.stringify(body)}`;

  expect(response.ok(), failure).toBeTruthy();
  expect((body as any)?.code, failure).toBe('0');
  expect((body as any)?.data?.pid, failure).toBeTruthy();
  expect((body as any)?.data?.code, failure).toBe(code);
}

test.describe('Showcase demo seed invariants', () => {
  test.use({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });

  test('CRM core seed data is queryable', async ({ request }) => {
    await expectDynamicListHasRecords(request, 'crm_account');
    await expectDynamicListHasRecords(request, 'crm_lead');
    await expectDynamicListHasRecords(request, 'crm_opportunity');
  });

  test('arsenal showcase data and dashboard are queryable', async ({ request }) => {
    await expectDynamicListHasRecords(request, 'showcase_all_fields');
    await expectDashboardExists(request, 'arsenal_capability_dashboard');
  });
});
