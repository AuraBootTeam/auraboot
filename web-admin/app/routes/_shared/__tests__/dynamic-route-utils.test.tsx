import { render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { DynamicField } from '../dynamic-route-utils';

describe('DynamicField', () => {
  const originalFetch = globalThis.fetch;

  beforeEach(() => {
    globalThis.fetch = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        code: '0',
        data: {
          pid: 'user-1',
          displayName: 'Admin User',
          email: 'admin@auraboot.com',
        },
      }),
    }) as unknown as typeof fetch;
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
    vi.restoreAllMocks();
  });

  it('renders readonly memberpicker labels from persisted JSON arrays', async () => {
    render(
      <DynamicField
        field={{
          field: 'wd_req_cc_users',
          label: '抄送人',
          component: 'memberpicker',
          props: { multiple: true },
        }}
        value='["user-1"]'
        onChange={vi.fn()}
        readOnly
      />,
    );

    await waitFor(() => {
      expect(screen.getByTestId('member-picker-readonly')).toHaveTextContent('Admin User');
    });

    expect(globalThis.fetch).toHaveBeenCalledWith('/api/admin/users/user-1');
    expect(screen.queryByText('["user-1"]')).not.toBeInTheDocument();
  });

  it('renders readonly selector values using dictCode from field props', () => {
    render(
      <DynamicField
        field={{
          field: 'sc_cascade_category',
          label: '级联分类',
          component: 'cascadeselect',
          props: { dictCode: 'sc_cascade_category_dict' },
        }}
        value="service_consulting_strategy"
        onChange={vi.fn()}
        readOnly
        getDictItems={(code) =>
          code === 'sc_cascade_category_dict'
            ? [{ value: 'service_consulting_strategy', label: '战略咨询' }]
            : []
        }
      />,
    );

    expect(screen.getByText('战略咨询')).toBeInTheDocument();
    expect(screen.queryByText('service_consulting_strategy')).not.toBeInTheDocument();
  });

  it('renders readonly userselect values as user labels', async () => {
    render(
      <DynamicField
        field={{
          field: 'sc_assignee',
          label: '负责人',
          component: 'userselect',
        }}
        value="user-1"
        onChange={vi.fn()}
        readOnly
      />,
    );

    await waitFor(() => {
      expect(screen.getByTestId('member-picker-readonly')).toHaveTextContent('Admin User');
    });

    expect(screen.queryByText('user-1')).not.toBeInTheDocument();
  });

  it('renders readonly organizationselect values as department labels', async () => {
    globalThis.fetch = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        code: '0',
        data: {
          pid: 'dept-1',
          org_dept_name: '研发中心',
          org_dept_code: 'RND',
        },
      }),
    }) as unknown as typeof fetch;

    render(
      <DynamicField
        field={{
          field: 'sc_department',
          label: '所属部门',
          component: 'organizationselect',
        }}
        value="dept-1"
        onChange={vi.fn()}
        readOnly
      />,
    );

    await waitFor(() => {
      expect(screen.getByText('研发中心')).toBeInTheDocument();
    });

    expect(globalThis.fetch).toHaveBeenCalledWith('/api/dynamic/org_department/dept-1');
    expect(screen.queryByText('dept-1')).not.toBeInTheDocument();
  });

  it('renders readonly addressfield JSON as a readable address', () => {
    render(
      <DynamicField
        field={{
          field: 'sc_address',
          label: '地址',
          component: 'addressfield',
        }}
        value='{"province":"上海市","city":"上海市","district":"松江区","detail":"109 号示范园区 10 幢"}'
        onChange={vi.fn()}
        readOnly
      />,
    );

    expect(screen.getByText('上海市 上海市 松江区 109 号示范园区 10 幢')).toBeInTheDocument();
    expect(screen.queryByText(/province/)).not.toBeInTheDocument();
  });
});
