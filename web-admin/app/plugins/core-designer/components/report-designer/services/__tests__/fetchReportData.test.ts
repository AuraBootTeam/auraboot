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
