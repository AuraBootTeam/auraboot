import React from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { Select } from '../Select';

let mockOptions: Array<{ label: string; value: string }> = [];
let mockLoading = false;
let mockRefetch = vi.fn();

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
    refetch: mockRefetch,
  }),
}));

describe('Smart Select', () => {
  beforeEach(() => {
    mockOptions = [];
    mockLoading = false;
    mockRefetch = vi.fn();
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

  it('keeps the Radix select controlled when an empty value later becomes populated', () => {
    mockOptions = [
      { label: 'Queued', value: 'QUEUED' },
      { label: 'Running', value: 'RUNNING' },
    ];
    vi.mocked(console.error).mockClear();

    const { rerender } = render(<Select name="crawler_status" />);

    rerender(<Select name="crawler_status" value="RUNNING" />);

    const consoleErrors = vi
      .mocked(console.error)
      .mock.calls.map((call) => call.map(String).join(' '))
      .join('\n');
    expect(consoleErrors).not.toContain('changing from uncontrolled to controlled');
  });

  it('refetches async options when opening a single select', () => {
    render(
      <Select
        name="bom_project_id"
        dataSource={{
          type: 'api',
          endpoint: '/api/dynamic/req_requirement_set_pcba_bom/list',
          method: 'get',
          autoFetch: false,
        } as any}
      />,
    );

    fireEvent.click(screen.getByTestId('select-trigger-bom_project_id'));

    expect(mockRefetch).toHaveBeenCalledTimes(1);
  });
});
