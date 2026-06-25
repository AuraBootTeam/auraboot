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

  it('filters single-select options with an in-dropdown search input while keeping create available', () => {
    mockOptions = [
      { label: 'Alpha Project', value: 'alpha' },
      { label: 'Beta Project', value: 'beta' },
    ];

    render(
      <Select
        name="bom_project_id"
        dataSource={{
          type: 'api',
          endpoint: '/api/dynamic/bom_project/list',
          method: 'get',
        } as any}
        canCreateNew
      />,
    );

    fireEvent.click(screen.getByTestId('select-trigger-bom_project_id'));
    fireEvent.change(screen.getByTestId('select-search-bom_project_id'), {
      target: { value: 'beta' },
    });

    expect(screen.queryByText('Alpha Project')).not.toBeInTheDocument();
    expect(screen.getByText('Beta Project')).toBeInTheDocument();
    expect(screen.getByTestId('select-create-new-bom_project_id')).toBeInTheDocument();
  });

  it('does not render an empty dropdown panel when there are no options', () => {
    render(
      <Select
        name="bom_project_id"
        label="所属项目"
        value=""
        options={[]}
        validationRules={[{ type: 'required', message: '此字段为必填项' }]}
      />,
    );

    fireEvent.click(screen.getByTestId('select-trigger-bom_project_id'));

    expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
  });

  it('does not render a loading-only dropdown panel before async options arrive', () => {
    mockLoading = true;

    render(
      <Select
        name="bom_project_id"
        label="所属项目"
        value=""
        dataSource={{
          type: 'api',
          endpoint: '/api/dynamic/req_requirement_set_pcba_bom/list',
          method: 'get',
          autoFetch: false,
        } as any}
      />,
    );

    fireEvent.click(screen.getByTestId('select-trigger-bom_project_id'));

    expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
  });

  it('does not open or refetch a dependent select when the dependency value is empty', () => {
    mockOptions = [{ label: '2', value: 'project-2' }];

    render(
      <Select
        name="bom_project_id"
        label="所属项目"
        value=""
        dataSource={
          {
            type: 'api',
            endpoint: '/api/dynamic/req_requirement_set_pcba_bom/list',
            method: 'get',
            autoFetch: false,
            dependOn: ['form.bom_task_customer_id'],
          } as any
        }
        context={{ form: { bom_task_customer_id: '' } } as any}
      />,
    );

    fireEvent.click(screen.getByTestId('select-trigger-bom_project_id'));

    expect(mockRefetch).not.toHaveBeenCalled();
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
  });
});
