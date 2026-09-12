import { afterEach, describe, expect, it, vi } from 'vitest';
import { reportDesignerService } from '../reportDesignerService';
import { createEmptyReport } from '../../types';
const report = createEmptyReport('Sales');
const pid = 'report-1';
function response(data: unknown) {
  return new Response(JSON.stringify({ code: 0, data }), { status: 200 });
}
afterEach(() => vi.unstubAllGlobals());

describe('canonical report persistence', () => {
  it('updates the saved ID without changing its code or writing a page', async () => {
    const fetchMock = vi.fn().mockResolvedValue(response({ pid }));
    vi.stubGlobal('fetch', fetchMock);
    expect(await reportDesignerService.save(report, pid)).toBe(pid);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe('/api/report-definitions/report-1');
    expect(init.method).toBe('put');
    expect(JSON.parse(init.body)).toEqual({ title: 'Sales', profile: 'paged-media', dsl: report });
  });
  it.each(['network', 'http', 'business'])('rejects a %s save failure', async (mode) => {
    const fetchMock = vi.fn();
    if (mode === 'network') fetchMock.mockRejectedValue(new Error('network'));
    else
      fetchMock.mockResolvedValue(
        new Response(JSON.stringify({ code: 500, message: 'failed' }), {
          status: mode === 'http' ? 500 : 200,
        }),
      );
    vi.stubGlobal('fetch', fetchMock);
    await expect(reportDesignerService.save(report)).rejects.toThrow();
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
  it.each(['pid', 'code'])('loads the complete DSL by %s from its only store', async (key) => {
    const fetchMock = vi.fn().mockResolvedValue(response({ pid, dsl: report }));
    vi.stubGlobal('fetch', fetchMock);
    const result =
      key === 'pid'
        ? await reportDesignerService.loadByPid(pid)
        : await reportDesignerService.loadByPageKey('sales');
    expect(result).toEqual({ pid, dsl: report });
    expect(fetchMock).toHaveBeenCalledExactlyOnceWith(
      key === 'pid' ? '/api/report-definitions/report-1' : '/api/report-definitions/by-code/sales',
      expect.any(Object),
    );
  });
  it.each(['network', 'missing-dsl', 'not-found'])(
    'does not mask %s reads by code',
    async (mode) => {
      const fetchMock = vi.fn();
      if (mode === 'network') fetchMock.mockRejectedValue(new Error('network'));
      else
        fetchMock.mockResolvedValue(
          mode === 'missing-dsl' ? response({ pid }) : new Response('{}', { status: 404 }),
        );
      vi.stubGlobal('fetch', fetchMock);
      await expect(reportDesignerService.loadByPageKey('sales')).rejects.toThrow();
      expect(fetchMock).toHaveBeenCalledTimes(1);
    },
  );
});
