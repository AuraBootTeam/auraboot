import React from 'react';
import { render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { Select } from '../Select';

let mockOptions: Array<{ label: string; value: string }> = [];
let mockLoading = false;

vi.mock('react-router', async () => {
  const actual = await vi.importActual<typeof import('react-router')>('react-router');
  return {
    ...actual,
    useActionData: () => null,
  };
});

vi.mock('~/framework/meta/hooks/useFieldDataSource', () => ({
  useFieldDataSource: () => ({
    options: mockOptions,
    loading: mockLoading,
    error: null,
    refetch: vi.fn(),
  }),
}));

describe('Smart Select', () => {
  beforeEach(() => {
    mockOptions = [];
    mockLoading = false;
  });

  it('shows the selected option label after async options load without requiring reselection', () => {
    const { rerender } = render(
      <Select
        name="wd_req_type"
        value="annual"
        dataSource={{
          type: 'api',
          endpoint: '/api/meta/dict/by-code/wd_leave_type/data',
          method: 'get',
          adaptor: 'dictData',
        } as any}
      />,
    );

    const trigger = screen.getByTestId('select-trigger-wd_req_type');
    expect(trigger).not.toHaveTextContent('年假');

    mockOptions = [{ label: '年假', value: 'annual' }];
    rerender(
      <Select
        name="wd_req_type"
        value="annual"
        dataSource={{
          type: 'api',
          endpoint: '/api/meta/dict/by-code/wd_leave_type/data',
          method: 'get',
          adaptor: 'dictData',
        } as any}
      />,
    );

    expect(screen.getByTestId('select-trigger-wd_req_type')).toHaveTextContent('年假');
  });
});
