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
});
