import React from 'react';
import { act, render, renderHook, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { useFieldDataSource } from '../useFieldDataSource';

describe('useFieldDataSource', () => {
  it('does not re-register equivalent inline dataSource configs across rerenders', async () => {
    const manager = {
      register: vi.fn(),
      subscribe: vi.fn(() => vi.fn()),
      getState: vi.fn(() => ({ data: [], loading: false, error: null })),
      unregister: vi.fn(),
    };

    function Harness({ tick }: { tick: number }) {
      useFieldDataSource({
        managerInstance: manager as any,
        dataSource: {
          type: 'api',
          endpoint: '/api/dynamic/crm_account/list',
          method: 'get',
          params: { pageNum: 1, pageSize: 200 },
          adaptor: 'optionList',
          valueField: 'pid',
          labelField: 'crm_acc_name',
          autoFetch: false,
        },
      });
      return <div data-testid="tick">{tick}</div>;
    }

    const { rerender } = render(<Harness tick={1} />);

    await waitFor(() => {
      expect(manager.register).toHaveBeenCalledTimes(1);
    });

    rerender(<Harness tick={2} />);

    await waitFor(() => {
      expect(manager.register).toHaveBeenCalledTimes(1);
    });
  });

  it('registers inline dataSource configs with modelCode for model-scoped reloads', async () => {
    const manager = {
      register: vi.fn(),
      registerWithModel: vi.fn(),
      subscribe: vi.fn(() => vi.fn()),
      getState: vi.fn(() => ({ data: [], loading: false, error: null })),
      unregister: vi.fn(),
    };

    function Harness() {
      useFieldDataSource({
        managerInstance: manager as any,
        dataSource: {
          type: 'api',
          endpoint: '/api/dynamic/customer/list',
          method: 'get',
          params: { pageNum: 1, pageSize: 200 },
          adaptor: 'optionList',
          valueField: 'pid',
          labelField: 'name',
          autoFetch: true,
          modelCode: 'customer',
        } as any,
      });
      return <div />;
    }

    render(<Harness />);

    await waitFor(() => {
      expect(manager.registerWithModel).toHaveBeenCalledTimes(1);
    });
    expect(manager.registerWithModel.mock.calls[0][2]).toBe('customer');
    expect(manager.register).not.toHaveBeenCalled();
  });
  it('forwards search extras without replacing configured parent constraints', async () => {
    const config = { type: 'api', endpoint: '/api/dynamic/req_requirement_set_pcba_bom/list',
      params: { pageNum: 1, pageSize: 200, bom_project_customer_id: 'CUSTOMER-A' }, autoFetch: false } as any;
    const manager = { register: vi.fn(), subscribe: vi.fn(() => vi.fn()), unregister: vi.fn(),
      getState: vi.fn(() => ({ data: [], loading: false, error: null })),
      fetch: vi.fn(async () => [{ value: 'PROJECT-201', label: 'Matching project' }]) };
    const { result } = renderHook(() => useFieldDataSource({ dataSource: config, managerInstance: manager as any }));
    await act(async () => { await result.current.refetch({ keyword: 'PROJECT-201', pageNum: 1 }); });
    expect(manager.fetch).toHaveBeenCalledWith(expect.any(String), { keyword: 'PROJECT-201', pageNum: 1 });
    expect(config.params).toEqual({ pageNum: 1, pageSize: 200, bom_project_customer_id: 'CUSTOMER-A' });
    expect(result.current.options).toEqual([{ value: 'PROJECT-201', label: 'Matching project' }]);
  });

  it('keeps a newer search busy when the discarded earlier request finishes', async () => {
    let firstDone!: (data: any) => void;
    let secondDone!: (data: any) => void;
    const manager = { register: vi.fn(), subscribe: vi.fn(() => vi.fn()), unregister: vi.fn(),
      getState: vi.fn(() => ({ data: [], loading: false, error: null })),
      fetch: vi.fn().mockImplementationOnce(() => new Promise(resolve => { firstDone = resolve; }))
        .mockImplementationOnce(() => new Promise(resolve => { secondDone = resolve; })) };
    const { result } = renderHook(() => useFieldDataSource({ managerInstance: manager as any,
      dataSource: { type: 'api', endpoint: '/api/dynamic/crm_account_common/list', autoFetch: false } as any }));
    let first!: Promise<void>; let second!: Promise<void>;
    act(() => { first = result.current.refetch({ keyword: 'earlier' }); });
    act(() => { second = result.current.refetch({ keyword: 'latest' }); });
    await act(async () => { firstDone(null); await first; });
    expect(result.current.loading).toBe(true);
    await act(async () => { secondDone([{ value: 'LATEST', label: 'Latest customer' }]); await second; });
    expect(result.current.loading).toBe(false);
    expect(result.current.options[0].value).toBe('LATEST');
  });

});
