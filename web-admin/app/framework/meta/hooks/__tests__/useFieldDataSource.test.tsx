import React from 'react';
import { render, waitFor } from '@testing-library/react';
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
});
