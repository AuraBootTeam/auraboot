import { renderHook, act, waitFor } from '@testing-library/react';
import { describe, expect, it, vi, beforeEach } from 'vitest';

vi.mock('~/shared/services/dynamicService', () => ({
  dynamicService: {
    findByPage: vi.fn(),
    findById: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
    deleteById: vi.fn(),
    batchCreate: vi.fn(),
    batchUpdate: vi.fn(),
    batchDelete: vi.fn(),
    getFieldOptions: vi.fn(),
    getRelatedData: vi.fn(),
    exportData: vi.fn(),
    importData: vi.fn(),
    getStats: vi.fn(),
    getPageMetadata: vi.fn(),
  },
}));

import { useDynamicApi } from '../useDynamicApi';
import { dynamicService } from '~/shared/services/dynamicService';

const ds = dynamicService as unknown as Record<string, ReturnType<typeof vi.fn>>;

describe('useDynamicApi', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('starts with loading=false and error=null', () => {
    const { result } = renderHook(() => useDynamicApi());
    expect(result.current.loading).toBe(false);
    expect(result.current.error).toBeNull();
  });

  it('findByPage sets loading during call and resolves result', async () => {
    const page = { records: [{ id: '1' }], total: 1, page: 0, size: 20 };
    ds.findByPage.mockResolvedValue(page);

    const { result } = renderHook(() => useDynamicApi());
    let resolved: any;

    await act(async () => {
      resolved = await result.current.findByPage('order', { page: 0, size: 20 });
    });

    expect(ds.findByPage).toHaveBeenCalledWith('order', { page: 0, size: 20 });
    expect(resolved).toEqual(page);
    expect(result.current.loading).toBe(false);
    expect(result.current.error).toBeNull();
  });

  it('findById delegates to service', async () => {
    const entity = { id: '42', name: 'test' };
    ds.findById.mockResolvedValue(entity);

    const { result } = renderHook(() => useDynamicApi());
    let out: any;
    await act(async () => {
      out = await result.current.findById('product', '42');
    });

    expect(ds.findById).toHaveBeenCalledWith('product', '42');
    expect(out).toEqual(entity);
  });

  it('create delegates to service', async () => {
    const created = { id: 'new', name: 'item' };
    ds.create.mockResolvedValue(created);

    const { result } = renderHook(() => useDynamicApi());
    let out: any;
    await act(async () => {
      out = await result.current.create('product', { name: 'item' });
    });

    expect(ds.create).toHaveBeenCalledWith('product', { name: 'item' });
    expect(out).toEqual(created);
  });

  it('update delegates to service with correct args', async () => {
    const updated = { id: '7', name: 'updated' };
    ds.update.mockResolvedValue(updated);

    const { result } = renderHook(() => useDynamicApi());
    let out: any;
    await act(async () => {
      out = await result.current.update('product', '7', { name: 'updated' });
    });

    expect(ds.update).toHaveBeenCalledWith('product', '7', { name: 'updated' });
    expect(out).toEqual(updated);
  });

  it('deleteById delegates to service', async () => {
    ds.deleteById.mockResolvedValue(undefined);

    const { result } = renderHook(() => useDynamicApi());
    await act(async () => {
      await result.current.deleteById('product', '5');
    });

    expect(ds.deleteById).toHaveBeenCalledWith('product', '5');
  });

  it('sets error state when service throws', async () => {
    ds.findByPage.mockRejectedValue(new Error('fetch failed'));

    const { result } = renderHook(() => useDynamicApi());
    await act(async () => {
      try {
        await result.current.findByPage('order', { page: 0, size: 20 });
      } catch {
        // expected
      }
    });

    expect(result.current.error).toBe('fetch failed');
    expect(result.current.loading).toBe(false);
  });

  it('batchCreate delegates to service', async () => {
    ds.batchCreate.mockResolvedValue([{ id: '1' }, { id: '2' }]);

    const { result } = renderHook(() => useDynamicApi());
    let out: any;
    await act(async () => {
      out = await result.current.batchCreate('product', [{ name: 'a' }, { name: 'b' }]);
    });

    expect(ds.batchCreate).toHaveBeenCalledWith('product', [{ name: 'a' }, { name: 'b' }]);
    expect(out).toHaveLength(2);
  });

  it('batchDelete delegates to service', async () => {
    ds.batchDelete.mockResolvedValue(undefined);

    const { result } = renderHook(() => useDynamicApi());
    await act(async () => {
      await result.current.batchDelete('product', ['1', '2', '3']);
    });

    expect(ds.batchDelete).toHaveBeenCalledWith('product', ['1', '2', '3']);
  });

  it('getPageSchema calls getPageMetadata', async () => {
    const schema = { listSchema: {}, formSchema: { fields: [] } };
    ds.getPageMetadata.mockResolvedValue(schema);

    const { result } = renderHook(() => useDynamicApi());
    let out: any;
    await act(async () => {
      out = await result.current.getPageSchema('order');
    });

    expect(ds.getPageMetadata).toHaveBeenCalledWith('order');
    expect(out).toEqual(schema);
  });
});
