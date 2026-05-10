import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import type { SubTableConfig } from '~/framework/meta/schemas/types';

const fetchResultMock = vi.fn();

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

  it('falls back to empty string for unknown ${field} placeholders instead of leaking template text', async () => {
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
      expect(fetchResultMock).toHaveBeenCalledWith('/api/datasource/list', {
        method: 'get',
        params: {
          datasourceId: 'nq:wd_leave_request_approval_history',
          format: 'records',
          processInstanceId: '',
        },
        token: undefined,
      });
    });

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
});
