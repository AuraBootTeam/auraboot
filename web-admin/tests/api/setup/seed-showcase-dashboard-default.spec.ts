/**
 * Showcase Demo Dashboard Default
 *
 * Runs after CRM plugin import and showcase data seeding. The target dashboard
 * is explicit so OSS crm-starter can use crm_overview while full CRM demos can
 * use crm_dashboard without silent fallback.
 */

import { expect, test, type APIRequestContext } from '@playwright/test';

interface DashboardRecord {
  pid: string;
  code: string;
  title?: string;
  isDefault?: boolean;
}

const defaultDashboardCode = process.env.SHOWCASE_DEFAULT_DASHBOARD_CODE?.trim() || 'crm_dashboard';

async function getDashboardByCode(
  request: APIRequestContext,
  code: string,
): Promise<DashboardRecord> {
  const response = await request.get(`/api/dashboards/code/${code}`);
  const body = await response.json().catch(() => null);
  const failure = `${code} dashboard lookup failed: ${response.status()} ${JSON.stringify(body)}`;

  expect(response.ok(), failure).toBeTruthy();
  expect(body?.code, failure).toBe('0');
  expect(body?.data?.pid, failure).toBeTruthy();
  expect(body?.data?.code, failure).toBe(code);

  return body.data as DashboardRecord;
}

async function listPublishedDashboards(
  request: APIRequestContext,
): Promise<DashboardRecord[]> {
  const response = await request.get('/api/dashboards?status=published&size=100');
  const body = await response.json().catch(() => null);
  const failure = `dashboard list failed: ${response.status()} ${JSON.stringify(body)}`;

  expect(response.ok(), failure).toBeTruthy();
  expect(body?.code, failure).toBe('0');

  return (body?.data?.records ?? []) as DashboardRecord[];
}

test(`showcase seed sets ${defaultDashboardCode} as the demo default dashboard`, async ({
  request,
}) => {
  const targetDashboard = await getDashboardByCode(request, defaultDashboardCode);
  const publishedDashboards = await listPublishedDashboards(request);

  for (const dashboard of publishedDashboards) {
    if (dashboard.pid !== targetDashboard.pid && dashboard.isDefault) {
      const response = await request.put(`/api/dashboards/${dashboard.pid}`, {
        data: { isDefault: false },
      });
      const body = await response.json().catch(() => null);
      const failure = `failed to clear default dashboard ${dashboard.code}: ${response.status()} ${JSON.stringify(body)}`;

      expect(response.ok(), failure).toBeTruthy();
      expect(body?.code, failure).toBe('0');
    }
  }

  const setDefault = await request.post(`/api/dashboards/${targetDashboard.pid}/set-default`);
  const setDefaultBody = await setDefault.json().catch(() => null);
  const setDefaultFailure = `failed to set ${defaultDashboardCode} as default: ${setDefault.status()} ${JSON.stringify(setDefaultBody)}`;

  expect(setDefault.ok(), setDefaultFailure).toBeTruthy();
  expect(setDefaultBody?.code, setDefaultFailure).toBe('0');
  expect(setDefaultBody?.data?.code, setDefaultFailure).toBe(defaultDashboardCode);
  expect(setDefaultBody?.data?.isDefault, setDefaultFailure).toBe(true);

  const defaultResponse = await request.get('/api/dashboards/default');
  const defaultBody = await defaultResponse.json().catch(() => null);
  const defaultFailure = `default dashboard should resolve to ${defaultDashboardCode}: ${defaultResponse.status()} ${JSON.stringify(defaultBody)}`;

  expect(defaultResponse.ok(), defaultFailure).toBeTruthy();
  expect(defaultBody?.code, defaultFailure).toBe('0');
  expect(defaultBody?.data?.code, defaultFailure).toBe(defaultDashboardCode);
});
