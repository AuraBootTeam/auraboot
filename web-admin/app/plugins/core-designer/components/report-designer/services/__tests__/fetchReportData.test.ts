import { afterEach, expect, it, vi } from 'vitest';
import { fetchReportData } from '../fetchReportData';
import { createEmptyReport } from '../../types';

afterEach(() => vi.unstubAllGlobals());
it('sends all model sort fields in their declared priority order', async () => {
  const fetch = vi
    .fn()
    .mockResolvedValue({ ok: true, json: async () => ({ code: 0, data: { records: [] } }) });
  vi.stubGlobal('fetch', fetch);
  const report = createEmptyReport('Sort');
  report.dataSources = {
    rows: {
      type: 'model',
      modelCode: 'orders',
      sortBy: [
        { field: 'urgent', order: 'desc' },
        { field: 'title', order: 'asc' },
      ],
    },
  };
  await fetchReportData(report);
  const url = new URL(fetch.mock.calls[0][0], 'http://localhost');
  expect(url.searchParams.get('sortFields')).toBe('urgent:desc,title:asc');
  expect(url.searchParams.has('sortField')).toBe(false);
});
it('rejects duplicate sort fields before sending a query', async () => {
  const fetch = vi.fn();
  vi.stubGlobal('fetch', fetch);
  const report = createEmptyReport('Sort');
  report.dataSources = {
    rows: {
      type: 'model',
      modelCode: 'orders',
      sortBy: [
        { field: 'title', order: 'desc' },
        { field: 'TITLE', order: 'asc' },
      ],
    },
  };
  await expect(fetchReportData(report)).rejects.toThrow('Invalid report sort fields');
  expect(fetch).not.toHaveBeenCalled();
});

it.each([401, 403])('preserves access denial for HTTP %s', async (status) => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status }));
  const report = createEmptyReport('Protected');
  report.dataSources = { rows: { type: 'model', modelCode: 'orders' } };
  await expect(fetchReportData(report)).rejects.toMatchObject({ kind: 'access' });
});

it('prioritizes a later access denial over another source failure', async () => {
  let deny!: (value: { ok: boolean; status: number }) => void;
  const later = new Promise<{ ok: boolean; status: number }>((resolve) => {
    deny = resolve;
  });
  vi.stubGlobal(
    'fetch',
    vi.fn().mockResolvedValueOnce({ ok: false, status: 500 }).mockReturnValueOnce(later),
  );
  const report = createEmptyReport('Mixed sources');
  report.dataSources = {
    first: { type: 'model', modelCode: 'first' },
    second: { type: 'model', modelCode: 'second' },
  };
  const result = fetchReportData(report);
  const denied = expect(result).rejects.toMatchObject({ kind: 'access' });
  await Promise.resolve();
  deny({ ok: false, status: 403 });
  await denied;
});

it('preserves the governed aggregate contract and result rows', async () => {
  const rows = [
    { region: 'East', revenue: 120 },
    { region: 'West', revenue: 80 },
  ];
  const fetch = vi
    .fn()
    .mockResolvedValue({ ok: true, json: async () => ({ code: 0, data: { rows } }) });
  vi.stubGlobal('fetch', fetch);
  const report = createEmptyReport('Aggregate');
  const aggregateQuery = { type: 'aggregate' as const, semanticModelCode: 'sales', limit: 42 };
  report.dataSources = { sales: { type: 'aggregate', aggregateQuery } };
  expect(await fetchReportData(report)).toEqual({ sales: rows });
  expect(fetch).toHaveBeenCalledWith(
    '/api/reports/query/aggregate',
    expect.objectContaining({ method: 'POST', body: JSON.stringify(aggregateQuery) }),
  );
});

it.each([401, 403])('preserves aggregate access denial for HTTP %s', async (status) => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status }));
  const report = createEmptyReport('Protected aggregate');
  report.dataSources = {
    rows: { type: 'aggregate', aggregateQuery: { type: 'aggregate', semanticModelCode: 'sales' } },
  };
  await expect(fetchReportData(report)).rejects.toMatchObject({ kind: 'access' });
});

it('rejects missing aggregate rows instead of showing a successful empty report', async () => {
  vi.stubGlobal(
    'fetch',
    vi.fn().mockResolvedValue({ ok: true, json: async () => ({ code: 0, data: {} }) }),
  );
  const report = createEmptyReport('Invalid aggregate');
  report.dataSources = {
    rows: { type: 'aggregate', aggregateQuery: { type: 'aggregate', modelCode: 'orders' } },
  };
  await expect(fetchReportData(report)).rejects.toThrow();
});
