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

  it('renders readonly generic reference values as the target display name, not the ULID', async () => {
    const ulid = '01KT4T39ZE8D9PGR29QNXFE8NW';
    globalThis.fetch = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        code: '0',
        data: {
          pid: ulid,
          crm_acc_name: '示范客户有限公司',
          name: '示范客户有限公司',
        },
      }),
    }) as unknown as typeof fetch;

    render(
      <DynamicField
        field={{
          field: 'crm_opp_account',
          label: '关联客户',
          component: 'reference',
          refTarget: { targetModel: 'crm_account', displayField: 'crm_acc_name' },
        }}
        value={ulid}
        onChange={vi.fn()}
        readOnly
      />,
    );

    await waitFor(() => {
      expect(screen.getByText('示范客户有限公司')).toBeInTheDocument();
    });

    expect(globalThis.fetch).toHaveBeenCalledWith(`/api/dynamic/crm_account/${ulid}`);
    // The raw ULID must never leak into the rendered detail.
    expect(screen.queryByText(ulid)).not.toBeInTheDocument();
  });

  it('resolves reference via referenceModelCode + targetField fallback chain', async () => {
    const ulid = '01KT4OPPORTUNITY0000000000A';
    globalThis.fetch = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        code: '0',
        data: { pid: ulid, crm_opp_name: '2026 年度框架采购商机' },
      }),
    }) as unknown as typeof fetch;

    render(
      <DynamicField
        field={{
          field: 'so_source_opportunity',
          label: '来源商机',
          component: 'smartinput',
          referenceModelCode: 'crm_opportunity',
          refTarget: { targetField: 'crm_opp_name' },
        }}
        value={ulid}
        onChange={vi.fn()}
        readOnly
      />,
    );

    await waitFor(() => {
      expect(screen.getByText('2026 年度框架采购商机')).toBeInTheDocument();
    });

    expect(globalThis.fetch).toHaveBeenCalledWith(`/api/dynamic/crm_opportunity/${ulid}`);
    expect(screen.queryByText(ulid)).not.toBeInTheDocument();
  });

  it('falls back to the raw id when the referenced record cannot be loaded', async () => {
    const ulid = '01KT4MISSING000000000000000';
    globalThis.fetch = vi.fn().mockResolvedValue({
      ok: false,
      json: async () => ({}),
    }) as unknown as typeof fetch;

    render(
      <DynamicField
        field={{
          field: 'crm_opp_account',
          label: '关联客户',
          component: 'reference',
          refTarget: { targetModel: 'crm_account', displayField: 'crm_acc_name' },
        }}
        value={ulid}
        onChange={vi.fn()}
        readOnly
      />,
    );

    await waitFor(() => {
      expect(screen.getByText(ulid)).toBeInTheDocument();
    });
  });

  it('does not treat dict-coded fields as references', () => {
    // A field with a dictCode must still resolve through the dict path even if some
    // stray reference metadata is present — no /api/dynamic reference fetch.
    globalThis.fetch = vi.fn() as unknown as typeof fetch;

    render(
      <DynamicField
        field={{
          field: 'crm_opp_stage',
          label: '阶段',
          component: 'smartselect',
          dictCode: 'crm_opp_stage_dict',
        }}
        value="negotiation"
        onChange={vi.fn()}
        readOnly
        getDictItems={(code) =>
          code === 'crm_opp_stage_dict'
            ? [{ value: 'negotiation', label: '谈判中' }]
            : []
        }
      />,
    );

    expect(screen.getByText('谈判中')).toBeInTheDocument();
    expect(globalThis.fetch).not.toHaveBeenCalled();
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
