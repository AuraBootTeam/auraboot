import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { SubTableConfig } from '~/framework/meta/schemas/types';

const fetchResultMock = vi.fn();
const navigateMock = vi.hoisted(() => vi.fn());

vi.mock('react-router', () => ({
  useNavigate: () => navigateMock,
}));

vi.mock('~/shared/services/http-client', () => ({
  fetchResult: (...args: unknown[]) => fetchResultMock(...args),
}));

vi.mock('~/framework/meta/hooks/useTreeData', () => ({
  useTreeData: (rows: Array<Record<string, unknown>>) => ({
    visibleRows: rows,
    toggleExpand: vi.fn(),
  }),
}));

vi.mock('~/framework/meta/rendering/pages/hooks/useDictCache', () => ({
  useDictCache: () => ({
    getDictItems: () => [],
    getDictLabel: (_code: string, value: string) => value,
  }),
}));

vi.mock('~/framework/meta/components/subtable/DndSubTableWrapper', () => ({
  DndSubTableWrapper: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}));

vi.mock('~/framework/meta/components/subtable/SortableSubTableRow', () => ({
  SortableSubTableRow: ({
    id,
    children,
  }: {
    id: string;
    children: React.ReactNode;
  }) => <tr data-testid={`sortable-row-${id}`}>{children}</tr>,
}));

vi.mock('~/framework/meta/components/subtable/InlineEditableCell', () => ({
  InlineEditableCell: () => null,
}));

vi.mock('~/framework/meta/components/subtable/SubTableSummaryRow', () => ({
  SubTableSummaryRow: () => null,
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
        config={{
          ...buildConfig(),
          dataSource: {
            kind: 'namedQuery',
            queryCode: 'wd_leave_request_approval_history',
            params: {
              processInstanceId: '${wd_req_process_instance}',
              applicantPid: '${record.wd_req_applicant}',
            },
          },
        } as any}
        parentRecordId="record-1"
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
        config={{
          ...buildConfig(),
          dataSource: {
            kind: 'namedQuery',
            queryCode: 'wd_leave_request_approval_history',
            params: {
              processInstanceId: '${missing_process_instance}',
            },
          },
        } as any}
        parentRecordId="record-1"
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
        config={{
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
        } as any}
        parentRecordId="record-1"
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
          filters:
            '[{"fieldName":"pid","operator":"EQ","value":"01KR8WEQZ0EXXF28KMA2B06YEN"}]',
        },
        token: undefined,
      });
    });

    await expect(screen.findByTestId('sortable-row-row-1')).resolves.toBeInTheDocument();
    expect(screen.getByTestId('subtable-viewer').textContent).toContain('D5 DataSource Row');
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
        parentRecordId="record-1"
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
        parentRecordId="record-1"
        isEditable
      />,
    );

    await expect(screen.findByTestId('sortable-row-row-1')).resolves.toBeInTheDocument();
    expect(screen.getByTestId('subtable-add-row').textContent).toContain('添加联系人');
  });

  it('merges configured default values into create payloads with parent record templates', async () => {
    fetchResultMock
      .mockResolvedValueOnce({ code: '0', data: { records: [] } })
      .mockResolvedValueOnce({ code: '0', data: {} })
      .mockResolvedValueOnce({ code: '0', data: { records: [] } });

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
        parentRecordId="request-1"
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
        parentRecordId="request-1"
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
              visibleWhen: "row.crm_qs_source_quote_id !== null && row.crm_qs_source_quote_id !== ''",
            },
          ],
          columns: [
            { field: 'crm_qs_code', label: 'Code' },
            { field: 'crm_qs_source_quote_id', label: 'Quote ID' },
          ],
        }}
        parentRecordId="request-1"
        isEditable
      />,
    );

    await expect(screen.findByTestId('sortable-row-summary-1')).resolves.toBeInTheDocument();
    fireEvent.click(screen.getByTestId('subtable-row-action-open_quoteops-0'));

    expect(navigateMock).toHaveBeenCalledWith('/p/qo_quote/view/quote-1');
  });
});
