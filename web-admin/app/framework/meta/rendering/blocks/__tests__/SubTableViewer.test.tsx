import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { SubTableConfig } from '~/framework/meta/schemas/types';

const fetchResultMock = vi.fn();
const navigateMock = vi.hoisted(() => vi.fn());
const hasPermissionMock = vi.hoisted(() => vi.fn(() => true));
const confirmDialogMock = vi.hoisted(() => vi.fn(async (_options?: unknown) => true));
const toolbarRendererMock = vi.hoisted(() => vi.fn());

vi.mock('react-router', () => ({
  useNavigate: () => navigateMock,
}));

vi.mock('~/shared/services/http-client', () => ({
  fetchResult: (...args: unknown[]) => fetchResultMock(...args),
}));

vi.mock('~/contexts/AuthContext', () => ({
  useAuth: () => ({ token: undefined, hasPermission: hasPermissionMock }),
}));

vi.mock('~/utils/confirmDialog', () => ({
  confirmDialog: (options: unknown) => confirmDialogMock(options),
}));

vi.mock('~/framework/meta/hooks/useTreeData', () => ({
  useTreeData: (rows: Array<Record<string, unknown>>) => ({
    visibleRows: rows,
    toggleExpand: vi.fn(),
  }),
}));

vi.mock('~/framework/meta/rendering/pages/hooks/useDictCache', () => ({
  useDictCache: () => ({
    getDictItems: (code: string) => code === 'priority' ? [{ value: 'high', label: 'High' }] : [],
    getDictLabel: (_code: string, value: string) => value,
  }),
}));

vi.mock('~/framework/meta/components/subtable/DndSubTableWrapper', () => ({
  DndSubTableWrapper: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}));

vi.mock('~/framework/meta/components/subtable/SortableSubTableRow', () => ({
  SortableSubTableRow: ({ id, children }: { id: string; children: React.ReactNode }) => (
    <tr data-testid={`sortable-row-${id}`}>{children}</tr>
  ),
}));

vi.mock('~/framework/meta/components/subtable/SubTableSummaryRow', () => ({
  SubTableSummaryRow: () => null,
}));

vi.mock('../ToolbarBlockRenderer', () => ({
  ToolbarBlockRenderer: (props: any) => {
    toolbarRendererMock(props);
    return <div data-testid="mock-subtable-toolbar">toolbar</div>;
  },
}));

vi.mock('~/ui/smart/form/Select', () => ({
  Select: ({ name, value, onChange, dataSource }: any) => (
    <select
      data-testid={`reference-picker-${name}`}
      data-endpoint={dataSource?.endpoint}
      data-label-field={dataSource?.labelField}
      value={value || ''}
      onChange={(event) => onChange(event.target.value)}
    >
      <option value="">Select</option>
      <option value="product-public-pid">Business product name</option>
    </select>
  ),
}));
import { SubTableViewer } from '../SubTableViewer';

function buildConfig(overrides?: Partial<SubTableConfig>): SubTableConfig {
  return {
    childModel: 'wd_leave_request_approval_history',
    parentField: 'processInstanceId',
    columns: [
      { field: 'taskName', label: '节点' },
      { field: 'status', label: '状态' },
    ],
    readOnly: true,
    ...overrides,
  };
}

describe('SubTableViewer', () => {
  beforeEach(() => {
    fetchResultMock.mockReset();
    navigateMock.mockReset();
    hasPermissionMock.mockReset();
    hasPermissionMock.mockReturnValue(true);
    confirmDialogMock.mockReset();
    confirmDialogMock.mockResolvedValue(true);
    toolbarRendererMock.mockReset();
  });

  it('renders a generic delivery collection from actual rows without conflating reported progress', async () => {
    fetchResultMock.mockResolvedValue({ code: '0', data: { records: [
      { pid: 'checkpoint-1', taskName: 'Requirements agreed', status: 'reached', criteria: 'Signed scope' },
      { pid: 'checkpoint-2', taskName: 'Core integration', status: 'pending', criteria: 'Procurement and receiving' },
    ] } });
    render(<SubTableViewer config={{ ...buildConfig(), presentation: { kind: 'collection', titleField: 'taskName', descriptionField: 'criteria', statusField: 'status', completedValues: ['reached'] } }} parentRecordPid="project-1" parentRecordData={{ progress: 68 }} locale="en" />);
    expect(await screen.findByTestId('collection-completion')).toHaveTextContent('1 / 2 completed');
    expect(screen.getByRole('row', { name: /Core integration/ })).toHaveTextContent('Procurement and receiving');
    expect(screen.queryByRole('progressbar')).not.toBeInTheDocument();
  });

  it('selects the first pending checkpoint for the declarative focus presentation', async () => {
    fetchResultMock.mockResolvedValue({ code: '0', data: { records: [
      { pid: 'checkpoint-1', taskName: 'Requirements agreed', status: 'reached' },
      { pid: 'checkpoint-2', taskName: 'Core integration', status: 'pending', criteria: 'Procurement and receiving' },
    ] } });
    render(<SubTableViewer config={{ ...buildConfig(), presentation: { kind: 'focus', titleField: 'taskName', descriptionField: 'criteria', statusField: 'status', completedValues: ['reached'] } }} parentRecordPid="project-1" locale="en" />);
    expect(await screen.findByTestId('collection-focus')).toHaveTextContent('Core integration');
    expect(screen.getByTestId('collection-focus')).not.toHaveTextContent('Requirements agreed');
  });

  it.each([
    { code: '403', message: 'Approval history access denied', data: null },
    { code: '1', message: 'Approval history unavailable', data: null },
    { code: '403', message: 'Approval history access denied', data: { records: [{ pid: 'forbidden-row', taskName: 'Private task' }] } },
  ])('shows the datasource error instead of an empty or unauthorized collection ($code)', async (result) => {
    fetchResultMock.mockResolvedValue(result);
    render(
      <SubTableViewer
        config={{ ...buildConfig(), dataSource: { kind: 'namedQuery', queryCode: 'wd_leave_request_approval_history' } } as any}
        parentRecordPid="leave-1"
      />,
    );
    await expect(screen.findByRole('alert')).resolves.toHaveTextContent(result.message);
    expect(screen.queryByTestId('subtable-empty-state')).not.toBeInTheDocument();
    expect(screen.queryByText('Private task')).not.toBeInTheDocument();
  });

  it('rejects a successful datasource envelope with a malformed records payload', async () => {
    fetchResultMock.mockResolvedValue({ code: '0', data: { records: { private: 'not a collection' } } });
    render(
      <SubTableViewer
        config={{ ...buildConfig(), dataSource: { kind: 'namedQuery', queryCode: 'wd_leave_request_approval_history' } } as any}
        parentRecordPid="leave-1"
        t={(key) => key === 'common.loadDataFailed' ? 'Unable to load data' : key}
      />,
    );
    await expect(screen.findByRole('alert')).resolves.toHaveTextContent('Unable to load data');
    expect(screen.queryByTestId('subtable-empty-state')).not.toBeInTheDocument();
  });

  it('renders parent-scoped toolbar actions above an empty child collection', async () => {
    fetchResultMock.mockResolvedValue({ code: '0', data: { records: [] } });

    render(
      <SubTableViewer
        config={buildConfig({
          toolbarActions: [
            {
              code: 'add-priced-product',
              label: 'Add priced product',
              action: { type: 'navigate', to: 'priced_product_form' },
            },
          ],
        })}
        parentRecordPid="OPP-PARENT-1"
        parentRecordData={{ pid: 'STALE-PID', crm_opp_name: 'Enterprise renewal' }}
        runtime={{} as any}
        t={(key) => (key === 'common.noData' ? 'No data' : key)}
      />,
    );

    await expect(screen.findByTestId('subtable-empty-state')).resolves.toBeInTheDocument();
    expect(screen.getByRole('columnheader', { name: '节点' })).toBeInTheDocument();
    expect(screen.getByRole('columnheader', { name: '状态' })).toBeInTheDocument();
    expect(screen.getByTestId('subtable-toolbar-actions')).toBeInTheDocument();
    expect(screen.getByTestId('mock-subtable-toolbar')).toBeInTheDocument();
    const renderedBlock = toolbarRendererMock.mock.calls.at(-1)?.[0]?.block;
    expect(renderedBlock.buttons[0].code).toBe('add-priced-product');
    expect(renderedBlock.record).toEqual({
      pid: 'OPP-PARENT-1',
      crm_opp_name: 'Enterprise renewal',
    });
  });

  afterEach(() => {
    document.body.innerHTML = '';
  });

  it('builds namedQuery datasource params and interpolates both ${field} and ${record.field}', async () => {
    fetchResultMock.mockResolvedValue({
      code: '0',
      data: {
        records: [{ pid: 'row-1', taskName: 'task_manager_approve', status: 'pending' }],
      },
    });

    render(
      <SubTableViewer
        config={
          {
            ...buildConfig(),
            dataSource: {
              kind: 'namedQuery',
              queryCode: 'wd_leave_request_approval_history',
              params: {
                processInstanceId: '${wd_req_process_instance}',
                applicantPid: '${record.wd_req_applicant}',
              },
            },
          } as any
        }
        parentRecordPid="record-1"
        parentRecordData={{
          wd_req_process_instance: '1776844530463',
          wd_req_applicant: '01KPT1P5S8F33HPQQXWKY1EJHG',
        }}
        t={(key) => (key === 'common.noData' ? 'No data' : key)}
      />,
    );

    await waitFor(() => {
      expect(fetchResultMock).toHaveBeenCalledWith('/api/datasource/list', {
        method: 'get',
        params: {
          datasourceId: 'nq:wd_leave_request_approval_history',
          format: 'records',
          processInstanceId: '1776844530463',
          applicantPid: '01KPT1P5S8F33HPQQXWKY1EJHG',
        },
        token: undefined,
      });
    });

    await expect(screen.findByTestId('sortable-row-row-1')).resolves.toBeInTheDocument();
    expect(screen.getByTestId('subtable-viewer')).toHaveClass('overflow-x-auto');
    expect(screen.getByTestId('subtable-viewer').textContent).toContain('task_manager_approve');
    expect(screen.getByTestId('subtable-viewer').textContent).toContain('pending');
  });

  it('skips data loading when a ${field} placeholder resolves to empty', async () => {
    fetchResultMock.mockResolvedValue({
      code: '0',
      data: {
        records: [],
      },
    });

    render(
      <SubTableViewer
        config={
          {
            ...buildConfig(),
            dataSource: {
              kind: 'namedQuery',
              queryCode: 'wd_leave_request_approval_history',
              params: {
                processInstanceId: '${missing_process_instance}',
              },
            },
          } as any
        }
        parentRecordPid="record-1"
        parentRecordData={{}}
        t={(key) => (key === 'common.noData' ? 'No data' : key)}
      />,
    );

    await waitFor(() => {
      expect(screen.getByTestId('subtable-viewer').textContent).toMatch(/No data|暂无数据/i);
    });

    expect(fetchResultMock).not.toHaveBeenCalled();
    expect(screen.getByTestId('subtable-viewer').textContent).toMatch(/No data|暂无数据/i);
  });

  it('loads API dataSource rows from endpoint configs with interpolated params', async () => {
    fetchResultMock.mockResolvedValue({
      code: '0',
      data: {
        records: [{ pid: 'row-1', taskName: 'D5 DataSource Row', status: 'active' }],
      },
    });

    render(
      <SubTableViewer
        config={
          {
            ...buildConfig(),
            dataSource: {
              type: 'api',
              endpoint: '/api/dynamic/showcase_all_fields/list',
              params: {
                pageNum: '1',
                pageSize: '5',
                filters: '[{"fieldName":"pid","operator":"EQ","value":"${record.pid}"}]',
              },
            },
          } as any
        }
        parentRecordPid="record-1"
        parentRecordData={{ pid: '01KR8WEQZ0EXXF28KMA2B06YEN' }}
        t={(key) => (key === 'common.noData' ? 'No data' : key)}
      />,
    );

    await waitFor(() => {
      expect(fetchResultMock).toHaveBeenCalledWith('/api/dynamic/showcase_all_fields/list', {
        method: 'get',
        params: {
          pageNum: '1',
          pageSize: '5',
          filters: '[{"fieldName":"pid","operator":"EQ","value":"01KR8WEQZ0EXXF28KMA2B06YEN"}]',
        },
        token: undefined,
      });
    });

    await expect(screen.findByTestId('sortable-row-row-1')).resolves.toBeInTheDocument();
    expect(screen.getByTestId('subtable-viewer').textContent).toContain('D5 DataSource Row');
  });

  it('resolves recordPid placeholders in API dataSource URLs and params', async () => {
    fetchResultMock.mockResolvedValue({
      code: '0',
      data: {
        records: [{ pid: 'row-pid', taskName: 'Record PID DataSource Row', status: 'active' }],
      },
    });

    render(
      <SubTableViewer
        config={
          {
            ...buildConfig(),
            dataSource: {
              type: 'api',
              url: '/api/scheduled-tasks/${recordPid}/logs',
              params: {
                owner: '${recordPid}',
              },
            },
          } as any
        }
        parentRecordPid="01KR8WEQZ0EXXF28KMA2B06YEN"
        parentRecordData={{ pid: '01KR8WEQZ0EXXF28KMA2B06YEN' }}
        t={(key) => (key === 'common.noData' ? 'No data' : key)}
      />,
    );

    await waitFor(() => {
      expect(fetchResultMock).toHaveBeenCalledWith(
        '/api/scheduled-tasks/01KR8WEQZ0EXXF28KMA2B06YEN/logs',
        {
          method: 'get',
          params: {
            owner: '01KR8WEQZ0EXXF28KMA2B06YEN',
          },
          token: undefined,
        },
      );
    });

    await expect(screen.findByTestId('sortable-row-row-pid')).resolves.toBeInTheDocument();
  });

  it('uses child field metadata for raw-code-free labels and reference display values', async () => {
    fetchResultMock.mockImplementation((endpoint: string) => {
      if (endpoint === '/api/dynamic/inv_inbound_line/field-meta') {
        return Promise.resolve({
          code: '0',
          data: [
            {
              code: 'inv_in_line_product_id',
              displayName: '商品',
              dataType: 'reference',
              refTarget: {
                targetModel: 'prod_product',
                targetField: 'prod_name',
              },
            },
            {
              code: 'inv_in_line_qty',
              displayName: '数量',
              dataType: 'integer',
            },
          ],
        });
      }
      if (endpoint === '/api/dynamic/inv_inbound_line/list') {
        return Promise.resolve({
          code: '0',
          data: {
            records: [
              {
                pid: 'line-1',
                inv_in_line_receipt_id: 'receipt-1',
                inv_in_line_product_id: 'prod-1',
                inv_in_line_product_id_display: 'P-RAW-CODE-001',
                inv_in_line_qty: 12,
              },
            ],
          },
        });
      }
      if (endpoint === '/api/dynamic/prod_product/list') {
        return Promise.resolve({
          code: '0',
          data: {
            records: [{ pid: 'prod-1', prod_name: '示范电源模块' }],
          },
        });
      }
      return Promise.resolve({ code: '0', data: { records: [] } });
    });

    render(
      <SubTableViewer
        config={{
          childModel: 'inv_inbound_line',
          parentField: 'inv_in_line_receipt_id',
          columns: [
            { field: 'inv_in_line_product_id', width: 250 },
            { field: 'inv_in_line_qty', width: 120, align: 'right' },
          ],
          readOnly: true,
        }}
        parentRecordPid="receipt-1"
        t={(key) => (key === 'common.noData' ? 'No data' : key)}
      />,
    );

    await expect(screen.findByTestId('sortable-row-line-1')).resolves.toBeInTheDocument();
    await waitFor(() => {
      const text = screen.getByTestId('subtable-viewer').textContent ?? '';
      expect(text).toContain('商品');
      expect(text).toContain('数量');
      expect(text).toContain('示范电源模块');
      expect(text).not.toContain('P-RAW-CODE-001');
      expect(text).not.toContain('inv_in_line_product_id');
      expect(text).not.toContain('prod-1');
    });
  });

  it('renders configured empty state copy and action label', async () => {
    fetchResultMock.mockResolvedValue({
      code: '0',
      data: {
        records: [],
      },
    });

    render(
      <SubTableViewer
        config={{
          ...buildConfig(),
          commands: { create: 'crm:create_contact' },
          emptyState: {
            title: { 'zh-CN': '暂无联系人', 'en-US': 'No contacts yet' },
            description: {
              'zh-CN': '该客户尚未录入对接人，建议先添加主联系人。',
              'en-US': 'Add a primary contact first.',
            },
            actionLabel: { 'zh-CN': '添加联系人', 'en-US': 'Add Contact' },
          },
        }}
        parentRecordPid="record-1"
        isEditable
        t={(key) => (key === 'common.noData' ? 'No data' : key)}
      />,
    );

    await expect(screen.findByTestId('subtable-empty-state')).resolves.toBeInTheDocument();
    expect(screen.getByTestId('subtable-empty-state').textContent).toContain('暂无联系人');
    expect(screen.getByTestId('subtable-empty-state').textContent).toContain(
      '该客户尚未录入对接人，建议先添加主联系人。',
    );
    expect(screen.getByTestId('subtable-empty-action').textContent).toContain('添加联系人');
  });

  it('uses a Chinese fallback label for the add-line action when i18n is missing', async () => {
    fetchResultMock.mockResolvedValue({
      code: '0',
      data: {
        records: [],
      },
    });

    render(
      <SubTableViewer
        config={{
          ...buildConfig(),
          commands: { create: 'crm:create_contact' },
        }}
        parentRecordPid="record-1"
        isEditable
        t={(key) => (key === 'common.noData' ? '暂无数据' : key)}
      />,
    );

    await expect(screen.findByTestId('subtable-empty-state')).resolves.toBeInTheDocument();
    expect(screen.getByTestId('subtable-empty-action').textContent).toContain('添加明细');
    expect(screen.getByTestId('subtable-empty-action').textContent).not.toContain('Add Line');
  });

  it('uses configured action label for the add-row button when rows already exist', async () => {
    fetchResultMock.mockResolvedValue({
      code: '0',
      data: {
        records: [{ pid: 'row-1', taskName: 'Initial contact', status: 'active' }],
      },
    });

    render(
      <SubTableViewer
        config={{
          ...buildConfig(),
          readOnly: false,
          commands: { create: 'crm:create_contact' },
          emptyState: {
            actionLabel: { 'zh-CN': '添加联系人', 'en-US': 'Add Contact' },
          },
        }}
        parentRecordPid="record-1"
        isEditable
      />,
    );

    await expect(screen.findByTestId('sortable-row-row-1')).resolves.toBeInTheDocument();
    expect(screen.getByTestId('subtable-add-row').textContent).toContain('添加联系人');
  });

  it('sends the configured milestone date ordering to the child query', async () => {
    fetchResultMock.mockResolvedValue({ code: '0', data: { records: [] } });
    render(<SubTableViewer config={{ ...buildConfig(), defaultSort: { field: 'due_date', order: 'asc' } }}
      parentRecordPid="project" />);
    await waitFor(() => expect(fetchResultMock).toHaveBeenCalledWith(
      expect.stringContaining('/list'), expect.objectContaining({
        params: expect.objectContaining({ sortField: 'due_date', sortOrder: 'asc' }),
      }),
    ));
  });

  it('uses typed add editors and persists their values through the existing create command', async () => {
    fetchResultMock.mockImplementation(async (endpoint: string) => endpoint.endsWith('/field-meta')
      ? { code: '0', data: [] } : endpoint.includes('/commands/execute/')
        ? { code: '0', data: {} } : { code: '0', data: { records: [] } });
    render(<SubTableViewer config={{ ...buildConfig(), readOnly: false,
      commands: { create: 'task:create' }, columns: [
        { field: 'title', label: 'Title', required: true },
        { field: 'due', label: 'Due', valueType: 'date' },
        { field: 'priority', label: 'Priority', valueType: 'tag', dictCode: 'priority' },
        { field: 'amount', label: 'Amount', format: 'number' },
      ] }} parentRecordPid="parent-1" isEditable />);
    fireEvent.click(await screen.findByTestId('subtable-empty-action'));
    fireEvent.click(screen.getByTestId('subtable-save-btn'));
    expect(await screen.findByTestId('subtable-error-title')).toHaveTextContent('此项为必填项');
    expect(screen.queryByTestId('inline-edit-error')).not.toBeInTheDocument();
    expect(fetchResultMock.mock.calls.filter(([url]) => url.includes('/commands/execute/'))).toHaveLength(0);
    expect(screen.getByTestId('subtable-add-due')).toHaveAttribute('type', 'date');
    expect(screen.getByTestId('subtable-add-priority').tagName).toBe('SELECT');
    expect(screen.getByTestId('subtable-add-amount')).toHaveAttribute('type', 'number');
    for (const [field, value] of Object.entries({ title: 'Training', due: '2026-11-10', priority: 'high', amount: '0' }))
      fireEvent.change(screen.getByTestId(`subtable-add-${field}`), { target: { value } });
    fireEvent.click(screen.getByTestId('subtable-save-btn'));
    await waitFor(() => expect(fetchResultMock).toHaveBeenCalledWith('/api/meta/commands/execute/task:create',
      expect.objectContaining({ params: expect.objectContaining({ payload: expect.objectContaining({
        title: 'Training', due: '2026-11-10', priority: 'high', amount: 0, processInstanceId: 'parent-1',
      }) }) })));
  });

  it('merges configured default values into create payloads with parent record templates', async () => {
    fetchResultMock.mockImplementation(async (endpoint: string) => {
      if (endpoint.endsWith('/field-meta')) return { code: '0', data: [] };
      if (endpoint.includes('/commands/execute/')) return { code: '0', data: {} };
      return { code: '0', data: { records: [] } };
    });

    render(
      <SubTableViewer
        config={{
          ...buildConfig(),
          parentField: 'crm_act_related_id',
          readOnly: false,
          commands: { create: 'crm:create_activity' },
          defaultValues: {
            crm_act_type: 'task',
            crm_act_status: 'open',
            crm_act_related_model: 'crm_customer_request',
            crm_act_owner: '${record.crm_cr_owner}',
          },
          columns: [
            { field: 'crm_act_subject', label: 'Subject', required: true },
            { field: 'crm_act_assignee', label: 'Assignee' },
          ],
          emptyState: {
            actionLabel: 'Add Task',
          },
        }}
        parentRecordPid="request-1"
        parentRecordData={{ crm_cr_owner: 'sales-owner' }}
        isEditable
      />,
    );

    fireEvent.click(await screen.findByTestId('subtable-empty-action'));
    fireEvent.change(screen.getByTestId('subtable-add-crm_act_subject'), {
      target: { value: 'Review customer BOM' },
    });
    fireEvent.change(screen.getByTestId('subtable-add-crm_act_assignee'), {
      target: { value: 'FAE Team' },
    });
    fireEvent.click(screen.getByTestId('subtable-save-btn'));

    await waitFor(() => {
      expect(fetchResultMock).toHaveBeenCalledWith(
        '/api/meta/commands/execute/crm:create_activity',
        expect.objectContaining({
          method: 'post',
          params: expect.objectContaining({
            operationType: 'create',
            payload: expect.objectContaining({
              crm_act_type: 'task',
              crm_act_status: 'open',
              crm_act_related_model: 'crm_customer_request',
              crm_act_related_id: 'request-1',
              crm_act_owner: 'sales-owner',
              crm_act_subject: 'Review customer BOM',
              crm_act_assignee: 'FAE Team',
            }),
          }),
        }),
      );
    });
  });

  it('executes configured row state actions against the selected child record pid', async () => {
    fetchResultMock
      .mockResolvedValueOnce({
        code: '0',
        data: {
          records: [
            {
              pid: 'task-1',
              crm_act_subject: 'Review BOM risk',
              crm_act_status: 'open',
            },
          ],
        },
      })
      .mockResolvedValueOnce({ code: '0', data: {} })
      .mockResolvedValueOnce({
        code: '0',
        data: {
          records: [
            {
              pid: 'task-1',
              crm_act_subject: 'Review BOM risk',
              crm_act_status: 'in_progress',
            },
          ],
        },
      });

    render(
      <SubTableViewer
        config={{
          ...buildConfig(),
          parentField: 'crm_act_related_id',
          readOnly: false,
          actions: [
            {
              code: 'start_task',
              label: { 'zh-CN': '开始', 'en-US': 'Start' },
              action: { type: 'state_transition', command: 'crm:start_task' },
              visibleWhen: "row.crm_act_status === 'open'",
            },
          ],
          columns: [
            { field: 'crm_act_subject', label: 'Subject' },
            { field: 'crm_act_status', label: 'Status' },
          ],
        }}
        parentRecordPid="request-1"
        isEditable
      />,
    );

    await expect(screen.findByTestId('sortable-row-task-1')).resolves.toBeInTheDocument();
    fireEvent.click(screen.getByTestId('subtable-row-action-start_task-0'));

    await waitFor(() => {
      expect(fetchResultMock).toHaveBeenCalledWith(
        '/api/meta/commands/execute/crm:start_task',
        expect.objectContaining({
          method: 'post',
          params: expect.objectContaining({
            targetRecordPid: 'task-1',
            operationType: 'UPDATE',
            payload: expect.objectContaining({
              pid: 'task-1',
              crm_act_status: 'open',
            }),
          }),
        }),
      );
    });
  });

  it('hides configured row actions when the current user lacks their permission', async () => {
    hasPermissionMock.mockImplementation((code?: string) => code !== 'crm.activity.manage');
    fetchResultMock.mockResolvedValue({
      code: '0',
      data: {
        records: [{ pid: 'task-1', crm_act_subject: 'Review BOM risk', crm_act_status: 'open' }],
      },
    });

    render(
      <SubTableViewer
        config={{
          ...buildConfig(),
          actions: [
            {
              code: 'view_task',
              label: 'View',
              permissionCode: 'crm.activity.read',
              action: { type: 'navigate', to: 'crm_activity_common_detail' },
            },
            {
              code: 'start_task',
              label: 'Start',
              permissionCode: 'crm.activity.manage',
              action: { type: 'state_transition', command: 'crm:start_task' },
            },
          ],
          columns: [{ field: 'crm_act_subject', label: 'Subject' }],
        }}
        parentRecordPid="opportunity-1"
      />,
    );

    await expect(screen.findByTestId('sortable-row-task-1')).resolves.toBeInTheDocument();
    expect(screen.getByTestId('subtable-row-action-view_task-0')).toBeInTheDocument();
    expect(screen.queryByTestId('subtable-row-action-start_task-0')).not.toBeInTheDocument();
  });

  it('honors a destructive row action confirmation before executing its command', async () => {
    confirmDialogMock.mockResolvedValueOnce(false).mockResolvedValueOnce(true);
    fetchResultMock
      .mockResolvedValueOnce({
        code: '0',
        data: {
          records: [{ pid: 'task-1', crm_act_subject: 'Review BOM risk', crm_act_status: 'open' }],
        },
      })
      .mockResolvedValueOnce({ code: '0', data: {} })
      .mockResolvedValueOnce({ code: '0', data: { records: [] } });

    render(
      <SubTableViewer
        config={{
          ...buildConfig(),
          actions: [
            {
              code: 'cancel_task',
              label: 'Cancel',
              danger: true,
              permissionCode: 'crm.activity.manage',
              confirm: { 'zh-CN': '确定取消该任务？', 'en-US': 'Cancel this task?' },
              action: { type: 'state_transition', command: 'crm:cancel_task' },
            },
          ],
          columns: [{ field: 'crm_act_subject', label: 'Subject' }],
        }}
        parentRecordPid="opportunity-1"
      />,
    );

    await expect(screen.findByTestId('sortable-row-task-1')).resolves.toBeInTheDocument();
    const cancelButton = screen.getByTestId('subtable-row-action-cancel_task-0');
    fireEvent.click(cancelButton);
    await waitFor(() => expect(confirmDialogMock).toHaveBeenCalledTimes(1));
    expect(fetchResultMock).toHaveBeenCalledTimes(1);

    fireEvent.click(cancelButton);
    await waitFor(() => {
      expect(fetchResultMock).toHaveBeenCalledWith(
        '/api/meta/commands/execute/crm:cancel_task',
        expect.objectContaining({
          method: 'post',
          params: expect.objectContaining({ targetRecordPid: 'task-1' }),
        }),
      );
    });
  });

  it('accepts legacy rowActions and collects command input fields before execution', async () => {
    fetchResultMock
      .mockResolvedValueOnce({
        code: '0',
        data: {
          records: [
            {
              pid: 'pick-line-1',
              inv_pkl_status: 'pending',
              inv_pkl_picked_qty: 0,
            },
          ],
        },
      })
      .mockResolvedValueOnce({ code: '0', data: {} })
      .mockResolvedValueOnce({ code: '0', data: { records: [] } });

    const submitDialog = (event: Event) => {
      const detail = (event as CustomEvent).detail;
      expect(detail.fields[0].helpText['zh-CN']).toContain('累计数量');
      detail.onSubmit({ quantity: 2 });
    };
    window.addEventListener('dialog:form', submitDialog, { once: true });

    render(
      <SubTableViewer
        config={{
          ...buildConfig(),
          rowActions: [
            {
              code: 'record_pick',
              label: '记录拣货',
              visibleWhen:
                "form.inv_pick_source_type === 'production_issue' && row.inv_pkl_status === 'pending'",
              action: {
                type: 'command',
                command: 'inv:record_issue_pick',
                inputFieldsTitle: '记录本次实物拣货',
                inputFields: [
                  {
                    field: 'quantity',
                    type: 'number',
                    required: true,
                    helpText: { 'zh-CN': '累计数量不得超过需求量。' },
                  },
                ],
              },
            },
          ],
          columns: [{ field: 'inv_pkl_status', label: '状态' }],
        }}
        parentRecordPid="pick-1"
        parentRecordData={{ inv_pick_source_type: 'production_issue' }}
      />,
    );

    await expect(screen.findByTestId('sortable-row-pick-line-1')).resolves.toBeInTheDocument();
    fireEvent.click(screen.getByTestId('subtable-row-action-record_pick-0'));

    await waitFor(() => {
      expect(fetchResultMock).toHaveBeenCalledWith(
        '/api/meta/commands/execute/inv:record_issue_pick',
        expect.objectContaining({
          method: 'post',
          params: expect.objectContaining({
            targetRecordPid: 'pick-line-1',
            payload: expect.objectContaining({ quantity: 2 }),
          }),
        }),
      );
    });
  });

  it('submits a versioned row edit with exact contextual defaults and projected payload', async () => {
    fetchResultMock
      .mockResolvedValueOnce({
        code: '0',
        data: {
          records: [
            {
              pid: 'receipt-line-versioned',
              row_version: 5,
              quantity: '0.100000',
              unit_price: '0.973451000000',
              source_pid: 'receipt-parent',
              private_snapshot: 'not-a-command-input',
            },
          ],
        },
      })
      .mockResolvedValue({ code: '0', data: { records: [] } });
    let observedDefaults: unknown;
    const submit = (event: Event) => {
      const detail = (event as CustomEvent).detail;
      observedDefaults = detail.defaults;
      detail.onSubmit({ quantity: '0.2', unit_price: '0.973451000000' });
    };
    window.addEventListener('dialog:form', submit, { once: true });
    render(
      <SubTableViewer
        config={{
          ...buildConfig(),
          readOnly: true,
          rowActions: [
            {
              code: 'adjust_line',
              action: {
                type: 'command',
                command: 'test:adjust_line',
                operationType: 'update',
                expectedVersion: '${row.row_version}',
                idempotent: true,
                payload: {},
                inputFields: [
                  { field: 'quantity', type: 'text', defaultValue: '${row.quantity}' },
                  { field: 'unit_price', type: 'text', defaultValue: '${record.unit_price}' },
                ],
              },
            },
          ],
          columns: [{ field: 'quantity', label: 'Quantity' }],
        }}
        parentRecordPid="receipt-parent"
        parentRecordData={{ status: 'draft' }}
      />,
    );
    await screen.findByTestId('sortable-row-receipt-line-versioned');
    fireEvent.click(screen.getByTestId('subtable-row-action-adjust_line-0'));
    await waitFor(() =>
      expect(fetchResultMock).toHaveBeenCalledWith(
        '/api/meta/commands/execute/test:adjust_line',
        expect.objectContaining({
          params: expect.objectContaining({
            targetRecordPid: 'receipt-line-versioned',
            expectedVersion: 5,
            payload: { quantity: '0.2', unit_price: '0.973451000000' },
            clientRequestId: expect.any(String),
          }),
        }),
      ),
    );
    expect(observedDefaults).toEqual({ quantity: '0.100000', unit_price: '0.973451000000' });
  });

  it('fails closed before prompting or posting when an opted-in row version is missing', async () => {
    fetchResultMock.mockResolvedValue({
      code: '0',
      data: { records: [{ pid: 'unversioned-line', quantity: '1' }] },
    });
    const dialog = vi.fn();
    window.addEventListener('dialog:form', dialog);
    try {
      render(
        <SubTableViewer
          config={{
            ...buildConfig(),
            readOnly: true,
            actions: [
              {
                code: 'adjust_versioned',
                action: {
                  type: 'command',
                  command: 'test:adjust',
                  expectedVersion: '${row.row_version}',
                  inputFields: [{ field: 'quantity', type: 'text' }],
                },
              },
            ],
            columns: [{ field: 'quantity', label: 'Quantity' }],
          }}
          parentRecordPid="receipt-parent"
        />,
      );
      await screen.findByTestId('sortable-row-unversioned-line');
      fireEvent.click(screen.getByTestId('subtable-row-action-adjust_versioned-0'));
      await screen.findByText('Unable to load the current record');
      expect(dialog).not.toHaveBeenCalled();
      expect(fetchResultMock.mock.calls.some(([url]) => url.includes('/commands/execute/'))).toBe(
        false,
      );
    } finally {
      window.removeEventListener('dialog:form', dialog);
    }
  });

  it('cancels deletion without a write and sends version zero with the explicit delete operation', async () => {
    confirmDialogMock.mockResolvedValueOnce(false).mockResolvedValueOnce(true);
    fetchResultMock
      .mockResolvedValueOnce({
        code: '0',
        data: { records: [{ pid: 'delete-line', row_version: 0, quantity: '1' }] },
      })
      .mockResolvedValue({ code: '0', data: { records: [] } });
    render(
      <SubTableViewer
        config={{
          ...buildConfig(),
          readOnly: true,
          actions: [
            {
              code: 'delete_versioned',
              danger: true,
              confirm: { en: 'Remove this draft line?' },
              action: {
                type: 'command',
                command: 'test:delete',
                operationType: 'delete',
                expectedVersion: '${row.row_version}',
                idempotent: true,
                payload: {},
              },
            },
          ],
          columns: [{ field: 'quantity', label: 'Quantity' }],
        }}
        parentRecordPid="receipt-parent"
      />,
    );
    await screen.findByTestId('sortable-row-delete-line');
    fireEvent.click(screen.getByTestId('subtable-row-action-delete_versioned-0'));
    await waitFor(() => expect(confirmDialogMock).toHaveBeenCalledTimes(1));
    expect(fetchResultMock.mock.calls.some(([url]) => url.includes('/commands/execute/'))).toBe(
      false,
    );
    fireEvent.click(screen.getByTestId('subtable-row-action-delete_versioned-0'));
    await waitFor(() =>
      expect(fetchResultMock).toHaveBeenCalledWith(
        '/api/meta/commands/execute/test:delete',
        expect.objectContaining({
          params: expect.objectContaining({
            targetRecordPid: 'delete-line',
            expectedVersion: 0,
            operationType: 'delete',
            payload: {},
            clientRequestId: expect.any(String),
          }),
        }),
      ),
    );
  });

  it('shows a prompt option load failure instead of silently treating it as cancellation', async () => {
    fetchResultMock
      .mockResolvedValueOnce({
        code: '0',
        data: { records: [{ pid: 'option-line', row_version: 1 }] },
      })
      .mockRejectedValueOnce(new Error('SECRET_BACKEND_STACK'));
    render(
      <SubTableViewer
        locale="en-US"
        config={{
          ...buildConfig(),
          readOnly: true,
          actions: [
            {
              code: 'edit_with_choices',
              action: {
                type: 'command',
                command: 'test:edit',
                expectedVersion: '${row.row_version}',
                inputFields: [
                  {
                    field: 'choice',
                    type: 'select',
                    dataSource: {
                      type: 'api',
                      endpoint: '/api/choices',
                      valueField: 'pid',
                      labelField: 'label',
                    },
                  },
                ],
              },
            },
          ],
          columns: [],
        }}
        parentRecordPid="receipt-parent"
      />,
    );
    await screen.findByTestId('sortable-row-option-line');
    fireEvent.click(screen.getByTestId('subtable-row-action-edit_with_choices-0'));
    await screen.findByText('Unable to load data. Try again.');
    expect(screen.queryByText('SECRET_BACKEND_STACK')).not.toBeInTheDocument();
    expect(fetchResultMock.mock.calls.some(([url]) => url.includes('/commands/execute/'))).toBe(
      false,
    );
  });

  it('navigates row actions with row field path templates', async () => {
    fetchResultMock.mockResolvedValue({
      code: '0',
      data: {
        records: [
          {
            pid: 'summary-1',
            crm_qs_code: 'QS-001',
            crm_qs_source_quote_id: 'quote-1',
          },
        ],
      },
    });

    render(
      <SubTableViewer
        config={{
          ...buildConfig(),
          parentField: 'crm_qs_customer_request_id',
          readOnly: false,
          actions: [
            {
              code: 'open_quoteops',
              label: { 'zh-CN': '打开报价', 'en-US': 'Open Quote' },
              action: { type: 'navigate', to: '/p/qo_quote/view/{crm_qs_source_quote_id}' },
              visibleWhen:
                "row.crm_qs_source_quote_id !== null && row.crm_qs_source_quote_id !== ''",
            },
          ],
          columns: [
            { field: 'crm_qs_code', label: 'Code' },
            { field: 'crm_qs_source_quote_id', label: 'Quote ID' },
          ],
        }}
        parentRecordPid="request-1"
        isEditable
      />,
    );

    await expect(screen.findByTestId('sortable-row-summary-1')).resolves.toBeInTheDocument();
    fireEvent.click(screen.getByTestId('subtable-row-action-open_quoteops-0'));

    expect(navigateMock).toHaveBeenCalledWith('/p/qo_quote/view/quote-1');
  });
});

describe('SubTableViewer reference input', () => {
  it('offers a business-name picker and persists its selected public PID', async () => {
    fetchResultMock.mockImplementation(async (endpoint: string) =>
      endpoint.endsWith('/field-meta')
        ? {
            code: '0',
            data: [
              {
                code: 'line_product_id',
                dataType: 'reference',
                refTarget: { targetModel: 'prod_product', targetField: 'prod_name' },
              },
            ],
          }
        : { code: '0', data: { records: [] } },
    );
    render(
      <SubTableViewer
        config={{
          childModel: 'line',
          parentField: 'parent_id',
          columns: [{ field: 'line_product_id', label: 'Product' }],
          commands: { create: 'inventory:add_line' },
          readOnly: false,
        }}
        parentRecordPid="parent-public-pid"
        isEditable
      />,
    );
    fireEvent.click(await screen.findByTestId('subtable-empty-action'));
    const picker = await screen.findByTestId('reference-picker-line_product_id');
    expect(picker).toHaveAttribute('data-endpoint', '/api/dynamic/prod_product/list');
    expect(picker).toHaveAttribute('data-label-field', 'prod_name');
    fireEvent.change(picker, { target: { value: 'product-public-pid' } });
    fireEvent.click(screen.getByTestId('subtable-save-btn'));
    await waitFor(() =>
      expect(fetchResultMock).toHaveBeenCalledWith(
        '/api/meta/commands/execute/inventory:add_line',
        expect.objectContaining({
          params: expect.objectContaining({
            payload: expect.objectContaining({
              parent_id: 'parent-public-pid',
              line_product_id: 'product-public-pid',
            }),
          }),
        }),
      ),
    );
  });
});

describe('SubTableViewer string constraints', () => {
  it('inherits the child field maximum length in the actual row input', async () => {
    fetchResultMock.mockImplementation(async (endpoint: string) =>
      endpoint.endsWith('/field-meta')
        ? {
            code: '0',
            data: [
              { code: 'lot_code', dataType: 'string', feature: { validation: { maxLength: 100 } } },
            ],
          }
        : { code: '0', data: { records: [] } },
    );
    render(
      <SubTableViewer
        config={{
          childModel: 'line',
          parentField: 'parent_id',
          columns: [{ field: 'lot_code', label: 'Batch' }],
          commands: { create: 'inventory:add_line' },
          readOnly: false,
        }}
        parentRecordPid="parent-public-pid"
        isEditable
      />,
    );
    fireEvent.click(await screen.findByTestId('subtable-empty-action'));
    await waitFor(() =>
      expect(screen.getByTestId('subtable-add-lot_code')).toHaveAttribute('maxlength', '100'),
    );
  });
});
