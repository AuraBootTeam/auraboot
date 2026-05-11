/**
 * Marketplace PID Contract Tests
 *
 * Guards public Marketplace browse payloads against leaking internal numeric
 * database IDs. Paid checkout/install-token assertions should use the
 * local-test paid backend once the running backend has been restarted with it.
 */

import { test, expect } from '../../fixtures';

type JsonRecord = Record<string, unknown>;

function recordsFromResponse(body: unknown): JsonRecord[] {
  const data = isRecord(body) && 'data' in body ? body.data : body;
  const records = isRecord(data) && 'records' in data ? data.records : data;
  return Array.isArray(records) ? records.filter(isRecord) : [];
}

function dataFromResponse(body: unknown): JsonRecord | null {
  const data = isRecord(body) && 'data' in body ? body.data : body;
  return isRecord(data) ? data : null;
}

function isRecord(value: unknown): value is JsonRecord {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function expectPidOnlyRecord(record: JsonRecord, label: string) {
  expect(record.pid, `${label} must expose pid`).toEqual(expect.any(String));
  expect(String(record.pid), `${label}.pid must be non-empty`).not.toHaveLength(0);
  expect(record, `${label} must not expose internal numeric id`).not.toHaveProperty('id');
}

test.describe('Marketplace PID Contract', () => {
  test('plugin list exposes stable pid and no internal numeric id', async ({ page }) => {
    const resp = await page.request.get('/api/marketplace/plugins');
    expect(resp.ok()).toBeTruthy();

    const plugins = recordsFromResponse(await resp.json());
    expect(plugins.length, 'marketplace seed must include at least one plugin').toBeGreaterThan(0);

    for (const [index, plugin] of plugins.entries()) {
      expectPidOnlyRecord(plugin, `plugin[${index}]`);
      expect(plugin.pluginId, `plugin[${index}] may expose package pluginId`).toEqual(expect.any(String));
    }
  });

  test('plugin detail and versions expose pids without internal numeric ids', async ({ page }) => {
    const listResp = await page.request.get('/api/marketplace/plugins');
    expect(listResp.ok()).toBeTruthy();

    const plugins = recordsFromResponse(await listResp.json());
    expect(plugins.length, 'marketplace seed must include at least one plugin').toBeGreaterThan(0);

    const pluginId = plugins[0].pluginId;
    expect(pluginId, 'plugin detail route uses package pluginId, not database id').toEqual(expect.any(String));

    const detailResp = await page.request.get(`/api/marketplace/plugins/${encodeURIComponent(String(pluginId))}`);
    expect(detailResp.ok()).toBeTruthy();

    const detail = dataFromResponse(await detailResp.json());
    expect(detail).not.toBeNull();
    expectPidOnlyRecord(detail!, 'plugin detail');

    const detailVersions = Array.isArray(detail!.versions) ? detail!.versions.filter(isRecord) : [];
    for (const [index, version] of detailVersions.entries()) {
      expectPidOnlyRecord(version, `plugin detail version[${index}]`);
    }

    const versionsResp = await page.request.get(
      `/api/marketplace/plugins/${encodeURIComponent(String(pluginId))}/versions`,
    );
    expect(versionsResp.ok()).toBeTruthy();

    const versions = recordsFromResponse(await versionsResp.json());
    expect(versions.length, 'marketplace seed must include at least one plugin version').toBeGreaterThan(0);
    for (const [index, version] of versions.entries()) {
      expectPidOnlyRecord(version, `version[${index}]`);
    }
  });
});
