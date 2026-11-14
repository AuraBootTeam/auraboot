import { act, renderHook, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { useListWorkspaceTabs } from '../list/useListWorkspaceTabs';
const transport = vi.hoisted(() => vi.fn());
vi.mock('~/shared/services/http-client', () => ({ fetchResult: (...args: any[]) => transport(...args) }));
function input(api = true): any {
  const tabs = { blockType: 'tabs', showCounts: true, tabs: [
    { key: 'all' }, { key: 'quoted', filter: { field: 'rfqStatus', operator: 'eq', value: 'quoted' } },
  ] };
  return { t: (k: string) => k, locale: 'en', tableName: 'orders', allBlocks: [tabs],
    schema: { modelCode: 'orders', ...(api ? { dataSource: { type: 'api', endpoint: '/business/orders', method: 'get' } } : {}) },
    dataReady: true, user: { pid: 'buyer' }, buildFiltersParam: vi.fn(() => '[{"field":"native"}]'),
    getSubmittedFilters: () => ({ created_at: { start: '2026-11-01', end: '2026-11-14' } }),
    chipFilters: [], modelFieldMapRef: { current: new Map() }, token: 'fixture',
    keywordRef: { current: ' RFQ-1 ' }, data: [], schemaExtension: {},
  };
}
describe('list tab counts use the active list source', () => {
  it('uses API pagination, submitted date filters, keyword and each business status', async () => {
    transport.mockReset().mockImplementation(async (_url, options) => ({ code: '0', data: { total: options.params.rfqStatus ? 2 : 7 } }));
    const args = input(); const { result } = renderHook(() => useListWorkspaceTabs(args));
    await waitFor(() => expect(result.current.workspaceTabCounts).toEqual({ all: 7, quoted: 2 }));
    expect(transport.mock.calls.map(([url]) => url)).toEqual(['/business/orders', '/business/orders']);
    expect(transport.mock.calls[0][1].params).toEqual({ page: 0, size: 1, keyword: 'RFQ-1', created_at_start: '2026-11-01', created_at_end: '2026-11-14' });
    expect(transport.mock.calls[1][1].params.rfqStatus).toBe('quoted');
    expect(args.buildFiltersParam).not.toHaveBeenCalled();
  });
  it('keeps native list pagination and filter serialization', async () => {
    transport.mockReset().mockResolvedValue({ code: '0', data: { total: 3 } });
    const args = input(false); const { result } = renderHook(() => useListWorkspaceTabs(args));
    await waitFor(() => expect(result.current.workspaceTabCounts.all).toBe(3));
    expect(transport.mock.calls[0][0]).toMatch(/orders\/list$/);
    expect(transport.mock.calls[0][1].params).toEqual({ pageNum: 1, pageSize: 1, keyword: 'RFQ-1', filters: '[{"field":"native"}]' });
  });
  it('reports an API failure without querying a different source', async () => {
    transport.mockReset().mockResolvedValue({ code: '403', message: 'Denied' });
    const args = input(); const { result } = renderHook(() => useListWorkspaceTabs(args));
    await waitFor(() => expect(result.current.workspaceTabCountError).toBe(true));
    expect(result.current.workspaceTabCounts).toEqual({});
    expect(transport.mock.calls.every(([url]) => url === '/business/orders')).toBe(true);
  });
  it('does not let an older query overwrite newer counts', async () => {
    const old: Array<(value: any) => void> = [];
    transport.mockReset().mockImplementation(() => new Promise(resolve => old.push(resolve)));
    const args = input(); const { result, rerender } = renderHook((props: any) => useListWorkspaceTabs(props), { initialProps: args });
    transport.mockResolvedValue({ code: '0', data: { total: 9 } });
    rerender({ ...args, data: [{ pid: 'new-result' }] });
    await waitFor(() => expect(result.current.workspaceTabCounts.all).toBe(9));
    await act(async () => { old.forEach(resolve => resolve({ code: '0', data: { total: 1 } })); });
    await waitFor(() => expect(result.current.workspaceTabCounts.all).toBe(9));
  });
});
