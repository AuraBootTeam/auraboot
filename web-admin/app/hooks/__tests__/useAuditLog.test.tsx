import { renderHook, act } from '@testing-library/react';
import { describe, expect, it, vi, beforeEach } from 'vitest';

const showErrorToast = vi.fn();

vi.mock('~/contexts/ToastContext', () => ({
  useToastContext: () => ({ showErrorToast }),
}));

vi.mock('~/shared/services/http-client', () => ({
  fetchResult: vi.fn(),
}));

vi.mock('~/utils/type', () => ({
  ResultHelper: {
    isSuccess: (r: { code: string }) => r.code === '0',
  },
}));

import { useAuditLog } from '../useAuditLog';
import { fetchResult } from '~/shared/services/http-client';

const mockFetch = fetchResult as ReturnType<typeof vi.fn>;

const ok = (data: unknown) => ({ code: '0', data, desc: '' });
const err = (desc = 'error') => ({ code: '1', data: null, desc });

describe('useAuditLog', () => {
  beforeEach(() => vi.clearAllMocks());

  it('initial state', () => {
    const { result } = renderHook(() => useAuditLog());
    expect(result.current.changeLogs).toEqual([]);
    expect(result.current.myChanges).toEqual([]);
    expect(result.current.total).toBe(0);
    expect(result.current.eventStream).toBeNull();
    expect(result.current.loading).toBe(false);
    expect(result.current.eventLoading).toBe(false);
  });

  it('getRecordHistory populates changeLogs', async () => {
    const logs = [{ id: 1, modelCode: 'order', recordId: 'r1', operation: 'create' }];
    mockFetch.mockResolvedValue(ok(logs));

    const { result } = renderHook(() => useAuditLog());
    let data: any;
    await act(async () => {
      data = await result.current.getRecordHistory('order', 'r1');
    });

    expect(mockFetch).toHaveBeenCalledWith(
      '/api/meta/change-logs/history',
      expect.objectContaining({ params: { modelCode: 'order', recordId: 'r1' } }),
    );
    expect(result.current.changeLogs).toEqual(logs);
    expect(data).toEqual(logs);
  });

  it('getRecordHistory handles array response directly', async () => {
    const logs = [{ id: 2, modelCode: 'task', recordId: 'r2', operation: 'update' }];
    mockFetch.mockResolvedValue(logs); // raw array

    const { result } = renderHook(() => useAuditLog());
    let data: any;
    await act(async () => {
      data = await result.current.getRecordHistory('task', 'r2');
    });

    expect(result.current.changeLogs).toEqual(logs);
    expect(data).toEqual(logs);
  });

  it('getRecordHistory shows error toast and returns empty on failure', async () => {
    mockFetch.mockResolvedValue(err('not found'));

    const { result } = renderHook(() => useAuditLog());
    let data: any;
    await act(async () => {
      data = await result.current.getRecordHistory('order', 'bad');
    });

    expect(showErrorToast).toHaveBeenCalledWith('not found');
    expect(data).toEqual([]);
  });

  it('getMyChanges handles paginated response shape', async () => {
    const records = [{ id: 5 }];
    mockFetch.mockResolvedValue(ok({ records, total: 1 }));

    const { result } = renderHook(() => useAuditLog());
    await act(async () => {
      await result.current.getMyChanges({ pageNum: 1, pageSize: 10 });
    });

    expect(result.current.myChanges).toEqual(records);
    expect(result.current.total).toBe(1);
  });

  it('getMyChanges handles flat array response shape', async () => {
    const records = [{ id: 6 }, { id: 7 }];
    mockFetch.mockResolvedValue(ok(records));

    const { result } = renderHook(() => useAuditLog());
    await act(async () => {
      await result.current.getMyChanges({ pageNum: 1, pageSize: 20 });
    });

    expect(result.current.myChanges).toEqual(records);
    expect(result.current.total).toBe(2);
  });

  it('getMyChanges passes optional filters to API', async () => {
    mockFetch.mockResolvedValue(ok({ records: [], total: 0 }));

    const { result } = renderHook(() => useAuditLog());
    await act(async () => {
      await result.current.getMyChanges({
        pageNum: 2,
        pageSize: 5,
        modelCode: 'order',
        operation: 'update',
      });
    });

    expect(mockFetch).toHaveBeenCalledWith(
      '/api/meta/change-logs/my',
      expect.objectContaining({
        params: expect.objectContaining({ modelCode: 'order', operation: 'update' }),
      }),
    );
  });

  it('getChangeLogById returns data on success', async () => {
    const log = { id: 10, modelCode: 'order', recordId: 'r10', operation: 'delete' };
    mockFetch.mockResolvedValue(ok(log));

    const { result } = renderHook(() => useAuditLog());
    let out: any;
    await act(async () => {
      out = await result.current.getChangeLogById(10);
    });

    expect(out).toEqual(log);
  });

  it('getChangeLogById returns null on failure', async () => {
    mockFetch.mockResolvedValue(err('not found'));

    const { result } = renderHook(() => useAuditLog());
    let out: any;
    await act(async () => {
      out = await result.current.getChangeLogById(999);
    });

    expect(out).toBeNull();
    expect(showErrorToast).toHaveBeenCalled();
  });

  it('getEventStream populates eventStream on success', async () => {
    const stream = { aggregateType: 'order', aggregateId: 'a1', currentVersion: 3, totalEvents: 3, events: [] };
    mockFetch.mockResolvedValue(ok(stream));

    const { result } = renderHook(() => useAuditLog());
    let out: any;
    await act(async () => {
      out = await result.current.getEventStream('order', 'a1');
    });

    expect(result.current.eventStream).toEqual(stream);
    expect(out).toEqual(stream);
    expect(result.current.eventLoading).toBe(false);
  });

  it('getEventStream returns null on failure', async () => {
    mockFetch.mockResolvedValue(err('stream error'));

    const { result } = renderHook(() => useAuditLog());
    let out: any;
    await act(async () => {
      out = await result.current.getEventStream('order', 'bad');
    });

    expect(out).toBeNull();
    expect(showErrorToast).toHaveBeenCalledWith('stream error');
  });

  it('replayAggregate returns data on success', async () => {
    const state = { field: 'value' };
    mockFetch.mockResolvedValue(ok(state));

    const { result } = renderHook(() => useAuditLog());
    let out: any;
    await act(async () => {
      out = await result.current.replayAggregate('order', 'a1');
    });

    expect(out).toEqual(state);
  });

  describe('parseChanges', () => {
    it('returns array as-is', () => {
      const { result } = renderHook(() => useAuditLog());
      const changes = [{ field: 'name', oldValue: 'a', newValue: 'b' }];
      expect(result.current.parseChanges(changes)).toEqual(changes);
    });

    it('parses valid JSON string', () => {
      const { result } = renderHook(() => useAuditLog());
      const json = '[{"field":"status","oldValue":"draft","newValue":"active"}]';
      const parsed = result.current.parseChanges(json);
      expect(parsed).toEqual([{ field: 'status', oldValue: 'draft', newValue: 'active' }]);
    });

    it('returns empty array for invalid JSON', () => {
      const { result } = renderHook(() => useAuditLog());
      expect(result.current.parseChanges('not json')).toEqual([]);
    });
  });
});
