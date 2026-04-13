/**
 * API Mock fixture for E2E tests.
 * Provides typed API mocking utilities for dynamic CRUD endpoints.
 *
 * NOTE: This fixture is deprecated. E2E tests should use real API calls
 * instead of mocks to ensure true integration testing.
 *
 * The new architecture uses:
 * - storageState for authentication
 * - ApiClient from helpers/api-client.ts for data setup
 * - Workflows for reusable business operations
 *
 * This file is kept for backward compatibility and may be used in special
 * cases where mocking is truly necessary (e.g., error scenario testing).
 *
 * @deprecated Prefer real API calls over mocks for E2E tests
 * @since 4.0.0
 */

import { type Page, type Route } from '@playwright/test';
import { ErrorCodes } from '~/shared/services/http-client/types';

export interface MockListResponse<T = Record<string, any>> {
  records: T[];
  total: number;
  pageNum: number;
  pageSize: number;
}

export interface ApiMockConfig {
  /** Model code for dynamic API endpoints */
  modelCode: string;
  /** API base path (default: /api/dynamic/{modelCode}) */
  basePath?: string;
  /** Mock data records */
  records?: Record<string, any>[];
  /** Page size (default: 20) */
  pageSize?: number;
}

/**
 * Set up API mocks for a dynamic CRUD model.
 * Mocks list, get, create, update, delete endpoints.
 */
export async function setupCrudMocks(page: Page, config: ApiMockConfig): Promise<void> {
  const basePath = config.basePath ?? `/api/dynamic/${config.modelCode}`;
  const records = config.records ?? [];
  const pageSize = config.pageSize ?? 20;

  // List endpoint
  await page.route(`**${basePath}/list*`, async (route) => {
    const url = new URL(route.request().url());
    const page = parseInt(url.searchParams.get('pageNum') ?? '1');
    const size = parseInt(url.searchParams.get('pageSize') ?? String(pageSize));
    const start = (page - 1) * size;
    const paged = records.slice(start, start + size);

    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        code: ErrorCodes.SUCCESS,
        data: { records: paged, total: records.length, pageNum: page, pageSize: size },
      }),
    });
  });

  // Get by ID endpoint
  await page.route(`**${basePath}/*`, async (route) => {
    if (route.request().method() !== 'get') {
      await route.fallback();
      return;
    }
    const url = route.request().url();
    const id = url.split('/').pop();
    const record = records.find((r) => r.id === id || r.pid === id);

    await route.fulfill({
      status: record ? 200 : 404,
      contentType: 'application/json',
      body: JSON.stringify(
        record ? { code: ErrorCodes.SUCCESS, data: record } : { code: '404', desc: 'Not found' },
      ),
    });
  });

  // Create endpoint
  await page.route(`**${basePath}`, async (route) => {
    if (route.request().method() !== 'post') {
      await route.fallback();
      return;
    }
    const body = route.request().postDataJSON();
    const newRecord = { id: `mock-${Date.now()}`, pid: `pid-${Date.now()}`, ...body };

    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: ErrorCodes.SUCCESS, data: newRecord }),
    });
  });

  // Update endpoint
  await page.route(`**${basePath}/*`, async (route) => {
    if (route.request().method() !== 'put') {
      await route.fallback();
      return;
    }
    const body = route.request().postDataJSON();

    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: ErrorCodes.SUCCESS, data: body }),
    });
  });

  // Delete endpoint
  await page.route(`**${basePath}/*`, async (route) => {
    if (route.request().method() !== 'delete') {
      await route.fallback();
      return;
    }

    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: ErrorCodes.SUCCESS, desc: 'Deleted' }),
    });
  });
}

/**
 * Set up a datasource mock for select/dropdown options.
 */
export async function setupDataSourceMock(
  page: Page,
  datasourceId: string,
  options: Array<{ value: string; label: string }>,
): Promise<void> {
  await page.route(`**/api/datasource/list*datasourceId=${datasourceId}*`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        code: ErrorCodes.SUCCESS,
        data: options.map((o) => ({ value: o.value, name: o.label })),
      }),
    });
  });
}

/**
 * Intercept and record API calls for assertion.
 */
export async function interceptApiCalls(
  page: Page,
  pattern: string,
): Promise<{ calls: Array<{ method: string; url: string; body: any }> }> {
  const calls: Array<{ method: string; url: string; body: any }> = [];

  await page.route(pattern, async (route) => {
    calls.push({
      method: route.request().method(),
      url: route.request().url(),
      body: route.request().postDataJSON(),
    });
    await route.fallback();
  });

  return { calls };
}

/**
 * Wait for a specific API call to be made.
 */
export async function waitForApiCall(
  page: Page,
  urlPattern: string,
  method: string = 'get',
  timeout: number = 5000,
): Promise<{ url: string; body: any }> {
  const response = await page.waitForRequest(
    (req) => req.url().includes(urlPattern) && req.method() === method,
    { timeout },
  );
  return {
    url: response.url(),
    body: response.postDataJSON(),
  };
}
