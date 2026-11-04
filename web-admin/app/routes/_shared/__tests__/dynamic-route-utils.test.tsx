import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { DynamicField, resolveLocalMenuRedirect } from '../dynamic-route-utils';
const fileMocks = vi.hoisted(() => ({ fetchResult: vi.fn() }));
vi.mock('~/shared/services/http-client', () => ({ fetchResult: fileMocks.fetchResult }));

describe('resolveLocalMenuRedirect', () => {
  it('resolves app-local menu redirects and preserves current query/hash when target omits them', () => {
    expect(
      resolveLocalMenuRedirect('/dashboards/view/crawler_overview', {
        pathname: '/crawler',
        search: '?tenant=demo',
        hash: '#top',
      }),
    ).toEqual({
      shouldRedirect: true,
      target: '/dashboards/view/crawler_overview?tenant=demo#top',
    });
  });

  it('keeps explicit query/hash from the target redirect', () => {
    expect(
      resolveLocalMenuRedirect('/reports/view/sales?tab=chart#summary', {
        pathname: '/legacy-sales',
        search: '?tenant=demo',
        hash: '#old',
      }),
    ).toEqual({
      shouldRedirect: true,
      target: '/reports/view/sales?tab=chart#summary',
    });
  });

  it('rejects external and protocol-relative redirects', () => {
    expect(resolveLocalMenuRedirect('https://example.com', { pathname: '/crawler' })).toMatchObject(
      {
        shouldRedirect: false,
        error: expect.stringContaining('app-local'),
      },
    );
    expect(resolveLocalMenuRedirect('//example.com/app', { pathname: '/crawler' })).toMatchObject({
      shouldRedirect: false,
      error: expect.stringContaining('app-local'),
    });
  });

  it('rejects redirects that point back to the current route', () => {
    expect(
      resolveLocalMenuRedirect('/crawler?tenant=demo#top', {
        pathname: '/crawler',
        search: '?tenant=demo',
        hash: '#top',
      }),
    ).toMatchObject({
      shouldRedirect: false,
      error: expect.stringContaining('current route'),
    });
  });
});

describe('DynamicField', () => {
  it('uses a safe same-page download for controlled file attachments', () => {
    render(<DynamicField field={{ field: 'files', component: 'fileattachment' } as any}
      value={JSON.stringify([{ name: 'Policy.txt', url: '/api/announcements/versions/v1/attachments/f1', size: 12 },
        { name: 'Unsafe.txt', url: 'javascript:alert(1)' }])} onChange={vi.fn()} readOnly locale="en-US" />);
    const safe = screen.getByRole('link', { name: 'Policy.txt (12 B)' });
    expect(safe).toHaveAttribute('href', '/api/announcements/versions/v1/attachments/f1');
    expect(safe).toHaveAttribute('download', 'Policy.txt');
    expect(safe).toHaveAttribute('target', '_self');
    expect(screen.getByText('Unsafe.txt')).toHaveAttribute('aria-disabled', 'true');
    expect(screen.getByText('Unsafe.txt')).not.toHaveAttribute('href');
  });

  it('resolves a scalar file reference to authorized metadata and a controlled download', async () => {
    fileMocks.fetchResult.mockResolvedValue({ code: '0', data: {
      fileId: '01J4P1X4S5R6T7V8W9X0Y1Z2A3', originalName: 'environment.yaml', fileSize: 768,
      url: 'https://untrusted.example/ignored',
    } });
    render(<DynamicField field={{ field: 'original', component: 'fileattachment' } as any}
      value="01J4P1X4S5R6T7V8W9X0Y1Z2A3" onChange={vi.fn()} readOnly locale="en-US" />);
    const link = await screen.findByRole('link', { name: 'environment.yaml 768 B' });
    expect(fileMocks.fetchResult).toHaveBeenCalledWith('/api/file/01J4P1X4S5R6T7V8W9X0Y1Z2A3', { method: 'get' });
    expect(link).toHaveAttribute('href', '/api/file/download/01J4P1X4S5R6T7V8W9X0Y1Z2A3');
    expect(link).toHaveAttribute('download', 'environment.yaml');
    expect(link).toHaveAttribute('target', '_self');
  });

  it.each(['403', '404'])('shows unavailable rather than empty for a denied or missing scalar file (%s)', async (code) => {
    fileMocks.fetchResult.mockResolvedValue({ code });
    render(<DynamicField field={{ field: 'original', component: 'fileattachment' } as any}
      value="01J4P1X4S5R6T7V8W9X0Y1Z2A4" onChange={vi.fn()} readOnly locale="en-US" />);
    expect(await screen.findByRole('alert')).toHaveTextContent('File unavailable');
    expect(screen.queryByRole('link')).not.toBeInTheDocument();
    expect(screen.queryByText('01J4P1X4S5R6T7V8W9X0Y1Z2A4')).not.toBeInTheDocument();
  });

  it('does not display a previous file or accept its late metadata after the scalar reference changes', async () => {
    let finishOld!: (result: unknown) => void;
    fileMocks.fetchResult.mockImplementationOnce(() => new Promise(resolve => { finishOld = resolve; }))
      .mockResolvedValueOnce({ code: '0', data: { fileId: '01J4P1X4S5R6T7V8W9X0Y1Z2A5', originalName: 'new.yaml', fileSize: 0 } });
    const field = { field: 'original', component: 'fileattachment' } as any;
    const { rerender } = render(<DynamicField field={field} value="01J4P1X4S5R6T7V8W9X0Y1Z2A6" onChange={vi.fn()} readOnly locale="en-US" />);
    expect(screen.getByRole('status')).toHaveTextContent('Loading file');
    rerender(<DynamicField field={field} value="01J4P1X4S5R6T7V8W9X0Y1Z2A5" onChange={vi.fn()} readOnly locale="en-US" />);
    expect(await screen.findByRole('link', { name: 'new.yaml 0 B' })).toHaveAttribute('href', '/api/file/download/01J4P1X4S5R6T7V8W9X0Y1Z2A5');
    await act(async () => { finishOld({ code: '0', data: { fileId: '01J4P1X4S5R6T7V8W9X0Y1Z2A6', originalName: 'old.yaml', fileSize: 1 } }); });
    await waitFor(() => expect(screen.queryByText('old.yaml')).not.toBeInTheDocument());
    expect(screen.getAllByRole('link')).toHaveLength(1);
  });

  it.each([
    { fileId: 'different-file', originalName: 'wrong.yaml', fileSize: 1 },
    { fileId: '01J4P1X4S5R6T7V8W9X0Y1Z2A3', originalName: '', fileSize: 1 },
    { fileId: '01J4P1X4S5R6T7V8W9X0Y1Z2A3', originalName: 'invalid-size.yaml', fileSize: -1 },
  ])('rejects success envelopes with missing or mismatched metadata ($originalName)', async data => {
    fileMocks.fetchResult.mockResolvedValue({ code: '0', data });
    render(<DynamicField field={{ field: 'original', component: 'fileattachment' } as any}
      value="01J4P1X4S5R6T7V8W9X0Y1Z2A3" onChange={vi.fn()} readOnly locale="en-US" />);
    expect(await screen.findByRole('alert')).toHaveTextContent('File unavailable');
    expect(screen.queryByRole('link')).not.toBeInTheDocument();
  });

  it('uses the real stored filename only when originalName is unavailable', async () => {
    fileMocks.fetchResult.mockResolvedValue({ code: '0', data: {
      fileId: '01J4P1X4S5R6T7V8W9X0Y1Z2A3', originalName: null, fileName: 'stored.yaml', fileSize: 1024,
    } });
    render(<DynamicField field={{ field: 'original', component: 'fileattachment' } as any}
      value="01J4P1X4S5R6T7V8W9X0Y1Z2A3" onChange={vi.fn()} readOnly locale="en-US" />);
    expect(await screen.findByRole('link', { name: 'stored.yaml 1.0 KB' })).toHaveAttribute('download', 'stored.yaml');
  });

  it('reports a transport failure without retrying or exposing a scalar identity', async () => {
    fileMocks.fetchResult.mockReset().mockRejectedValue(new Error('offline'));
    render(<DynamicField field={{ field: 'original', component: 'fileattachment' } as any}
      value="01J4P1X4S5R6T7V8W9X0Y1Z2A3" onChange={vi.fn()} readOnly locale="en-US" />);
    expect(await screen.findByRole('alert')).toHaveTextContent('File unavailable');
    expect(fileMocks.fetchResult).toHaveBeenCalledTimes(1);
    expect(screen.queryByText('01J4P1X4S5R6T7V8W9X0Y1Z2A3')).not.toBeInTheDocument();
  });

  it('does not query file metadata for empty or malformed scalar references', () => {
    fileMocks.fetchResult.mockClear();
    const field = { field: 'original', component: 'fileattachment' } as any;
    const { rerender } = render(<DynamicField field={field} value="" onChange={vi.fn()} readOnly locale="en-US" />);
    expect(screen.getByText('—')).toBeVisible();
    rerender(<DynamicField field={field} value="../secret" onChange={vi.fn()} readOnly locale="en-US" />);
    expect(screen.getByRole('alert')).toHaveTextContent('File unavailable');
    expect(fileMocks.fetchResult).not.toHaveBeenCalled();
    expect(screen.queryByRole('link')).not.toBeInTheDocument();
  });

  it('formats read-only numeric and money controls with locale grouping', () => {
    const { rerender } = render(
      <DynamicField
        field={{ field: 'amount', label: 'Amount', component: 'money' } as any}
        value={680000}
        onChange={vi.fn()}
        readOnly
        locale="zh-CN"
      />,
    );

    expect(screen.getByText('680,000')).toBeInTheDocument();

    rerender(
      <DynamicField
        field={{ field: 'probability', label: 'Probability', component: 'number' } as any}
        value={35.5}
        onChange={vi.fn()}
        readOnly
        locale="zh-CN"
      />,
    );
    expect(screen.getByText('35.5')).toBeInTheDocument();
  });
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

  it('matches readonly dict values case-insensitively for API-backed detail pages', () => {
    render(
      <DynamicField
        field={{
          field: 'target_type',
          label: '目标类型',
          component: 'smartselect',
          dictCode: 'sla_target_type',
        }}
        value="NODE"
        onChange={vi.fn()}
        readOnly
        getDictItems={(code) =>
          code === 'sla_target_type' ? [{ value: 'node', label: '节点' }] : []
        }
      />,
    );

    expect(screen.getByText('节点')).toBeInTheDocument();
    expect(screen.queryByText('NODE')).not.toBeInTheDocument();
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

  it('renders an already-resolved readonly user label without looking it up as a PID', async () => {
    render(
      <DynamicField
        field={{
          field: 'created_by',
          label: '创建人',
          component: 'userselect',
        }}
        value="SOT qo_sales"
        onChange={vi.fn()}
        readOnly
      />,
    );

    await waitFor(() => {
      expect(screen.getByTestId('member-picker-readonly')).toHaveTextContent('SOT qo_sales');
    });
    expect(globalThis.fetch).not.toHaveBeenCalled();
  });

  it('keeps the raw user PID visible when lookup returns a non-success response', async () => {
    globalThis.fetch = vi.fn().mockResolvedValue({ ok: false }) as unknown as typeof fetch;

    render(
      <DynamicField
        field={{
          field: 'sc_assignee',
          label: '负责人',
          component: 'userselect',
        }}
        value="missing-user-1"
        onChange={vi.fn()}
        readOnly
      />,
    );

    await waitFor(() => {
      expect(screen.getByTestId('member-picker-readonly')).toHaveTextContent('missing-user-1');
    });
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

  it('prefers readonly reference display values over resolving raw ids', () => {
    const ulid = '01KVR37707ZCV3ADJMHFGPDMGP';
    globalThis.fetch = vi.fn() as unknown as typeof fetch;

    render(
      <DynamicField
        field={{
          field: 'crm_lead_assigned_to',
          label: '分配给',
          component: 'user-select',
          refTarget: { targetModel: 'ab_user', targetField: 'user_name' },
        }}
        value={ulid}
        displayValue="e2e-operator"
        onChange={vi.fn()}
        readOnly
      />,
    );

    expect(screen.getByText('e2e-operator')).toBeInTheDocument();
    expect(screen.queryByText(ulid)).not.toBeInTheDocument();
    expect(globalThis.fetch).not.toHaveBeenCalled();
  });

  it('shows recoverable feedback without the raw id when the referenced record cannot be loaded', async () => {
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
      expect(screen.getByTestId('reference-readonly-error')).toHaveTextContent('来源暂时无法读取');
      expect(screen.queryByText(ulid)).not.toBeInTheDocument();
    });
  });

  it('resolves the configured display field again when it changes', async () => {
    const id = 'reference-display-field-contract';
    globalThis.fetch = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ code: '0', data: { name: 'Account name', code: 'ACC-42' } }),
    }) as unknown as typeof fetch;
    const props = { value: id, onChange: vi.fn(), readOnly: true };
    const { rerender } = render(
      <DynamicField
        {...props}
        field={{
          field: 'source',
          component: 'reference',
          refTarget: { targetModel: 'crm_account', displayField: 'name' },
        }}
      />,
    );
    await waitFor(() => expect(screen.getByText('Account name')).toBeInTheDocument());
    rerender(
      <DynamicField
        {...props}
        field={{
          field: 'source',
          component: 'reference',
          refTarget: { targetModel: 'crm_account', displayField: 'code' },
        }}
      />,
    );
    await waitFor(() => expect(screen.getByText('ACC-42')).toBeInTheDocument());
  });

  it('does not reuse a previous mounted identity label after permission changes', async () => {
    const id = 'reference-permission-contract';
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({ code: '0', data: { name: 'Private account' } }),
      })
      .mockResolvedValueOnce({ ok: false, status: 403 });
    globalThis.fetch = fetchMock as unknown as typeof fetch;
    const props = {
      field: {
        field: 'source',
        component: 'reference',
        refTarget: { targetModel: 'crm_account', displayField: 'name' },
      },
      value: id,
      onChange: vi.fn(),
      readOnly: true,
    };
    const first = render(<DynamicField {...props} />);
    await waitFor(() => expect(screen.getByText('Private account')).toBeInTheDocument());
    first.unmount();
    render(<DynamicField {...props} />);
    await waitFor(() =>
      expect(screen.getByTestId('reference-readonly-error')).toHaveTextContent('无权读取来源'),
    );
    expect(screen.queryByText('Private account')).not.toBeInTheDocument();
    expect(screen.queryByText(id)).not.toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('retries an unavailable source and replaces feedback with its business label', async () => {
    const id = 'reference-retry-contract';
    globalThis.fetch = vi
      .fn()
      .mockRejectedValueOnce(new Error('offline'))
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({ code: '0', data: { code: 'NCR-42' } }),
      }) as unknown as typeof fetch;
    render(
      <DynamicField
        field={{
          field: 'source',
          component: 'reference',
          refTarget: { targetModel: 'qc_ncr', displayField: 'code' },
        }}
        value={id}
        onChange={vi.fn()}
        readOnly
      />,
    );
    await waitFor(() => expect(screen.getByTestId('reference-readonly-error')).toBeInTheDocument());
    fireEvent.click(screen.getByRole('button', { name: '重试' }));
    await waitFor(() => expect(screen.getByText('NCR-42')).toBeInTheDocument());
    expect(screen.queryByTestId('reference-readonly-error')).not.toBeInTheDocument();
    expect(screen.queryByText(id)).not.toBeInTheDocument();
  });

  it('reports a missing business label without falling back to internal identity', async () => {
    const id = 'reference-unnamed-contract';
    globalThis.fetch = vi
      .fn()
      .mockResolvedValue({
        ok: true,
        json: async () => ({ code: '0', data: { pid: id } }),
      }) as unknown as typeof fetch;
    render(
      <DynamicField
        field={{
          field: 'source',
          component: 'reference',
          refTarget: { targetModel: 'qc_ncr', displayField: 'code' },
        }}
        value={id}
        onChange={vi.fn()}
        readOnly
      />,
    );
    await waitFor(() =>
      expect(screen.getByTestId('reference-readonly-error')).toHaveTextContent('来源缺少业务名称'),
    );
    expect(screen.queryByText(id)).not.toBeInTheDocument();
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
          code === 'crm_opp_stage_dict' ? [{ value: 'negotiation', label: '谈判中' }] : []
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

  it('renders readonly jsonviewer strings as formatted JSON blocks', () => {
    render(
      <DynamicField
        field={{
          field: 'bom_sfp_header_rule_json',
          label: '表头规则 JSON',
          component: 'jsonviewer',
        }}
        value='{"requiredHeaders":["Designator","Quantity"],"minScore":80}'
        onChange={vi.fn()}
        readOnly
      />,
    );

    expect(screen.getByTestId('readonly-json-bom_sfp_header_rule_json')).toHaveTextContent(
      '"requiredHeaders": [',
    );
    expect(screen.getByTestId('readonly-json-bom_sfp_header_rule_json')).toHaveTextContent(
      '"minScore": 80',
    );
  });

  it('renders readonly field codes ending in json as formatted JSON blocks', () => {
    render(
      <DynamicField
        field={{
          field: 'bom_sfp_llm_policy_json',
          label: 'LLM 策略 JSON',
        }}
        value='{"enabled":true,"allowedModes":["field_parse","attribute_extract"]}'
        onChange={vi.fn()}
        readOnly
      />,
    );

    expect(screen.getByTestId('readonly-json-bom_sfp_llm_policy_json')).toHaveTextContent(
      '"enabled": true',
    );
    expect(screen.getByTestId('readonly-json-bom_sfp_llm_policy_json')).toHaveTextContent(
      '"allowedModes": [',
    );
  });
});
