import { afterEach, expect, it, vi } from 'vitest';
import { reportDesignerService } from '../reportDesignerService';
import { fetchReportData } from '../fetchReportData';
import type { ReportDsl } from '../../types';
afterEach(() => vi.unstubAllGlobals());
const report = { title: 'Monthly sales', body: [], dataSources: {} } as unknown as ReportDsl;
it('creates directly in report definitions with no shadow write', async () => {
  const fetchMock = vi
    .fn()
    .mockResolvedValue(new Response(JSON.stringify({ code: 0, data: { pid: 'report-1' } })));
  vi.stubGlobal('fetch', fetchMock);
  expect(await reportDesignerService.save(report)).toBe('report-1');
  expect(fetchMock).toHaveBeenCalledTimes(1);
  expect(fetchMock.mock.calls[0][0]).toBe('/api/report-definitions');
  expect(JSON.parse(fetchMock.mock.calls[0][1].body).dsl).toEqual(report);
});
it.each([403, 404, 500])(
  'does not hide report read error %s by reading another store',
  async (status) => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(new Response(JSON.stringify({ message: 'Read failed' }), { status }));
    vi.stubGlobal('fetch', fetchMock);
    await expect(reportDesignerService.loadByPid('report-1')).rejects.toThrow();
    expect(fetchMock).toHaveBeenCalledTimes(1);
  },
);
it('rejects a failed dataset instead of rendering an empty report', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{}', { status: 500 })));
  await expect(
    fetchReportData({
      ...report,
      dataSources: { orders: { type: 'model', modelCode: 'orders' } },
    } as ReportDsl),
  ).rejects.toThrow();
});
