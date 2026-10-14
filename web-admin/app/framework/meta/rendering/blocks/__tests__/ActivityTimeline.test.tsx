import { render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { ActivityTimeline } from '../ActivityTimeline';

const fetchResultMock = vi.fn();

vi.mock('~/shared/services/http-client', () => ({
  fetchResult: (...args: unknown[]) => fetchResultMock(...args),
}));

describe('ActivityTimeline', () => {
  afterEach(() => {
    fetchResultMock.mockReset();
  });

  it('does not render internal pid values from activity actor names', async () => {
    fetchResultMock.mockResolvedValue({
      code: '0',
      data: [
        {
          id: 11,
          pid: '01KV2G36HAZXJVKVC8P1GTQFJ1',
          objectModel: 'qo_quote_common',
          objectRecord: '01KV2G6Y80PST72VASRDYV0S4A',
          activityType: 'update',
          subject: 'QO-20260614-001',
          content: null,
          actorType: 'system',
          actorId: null,
          actorName: '01KV2G36HAZXJVKVC8P1GTQFJ1',
          commandCode: 'qo_quote_common:generate_document',
          operationType: 'update',
          metadata: null,
          occurredAt: '2026-06-14T15:34:00+08:00',
          createdAt: '2026-06-14T15:34:00+08:00',
        },
      ],
    });

    const { container } = render(
      <ActivityTimeline
        modelCode="qo_quote_common"
        recordPid="01KV2G6Y80PST72VASRDYV0S4A"
        locale="zh-CN"
      />,
    );

    await waitFor(() => expect(screen.getByTestId('activity-timeline')).toBeVisible());

    expect(screen.getByText('QO-20260614-001')).toBeVisible();
    expect(screen.getByText('更新')).toBeVisible();
    expect(container).not.toHaveTextContent('01KV2G36HAZXJVKVC8P1GTQFJ1');
    expect(container).not.toHaveTextContent('01KV2G6Y80PST72VASRDYV0S4A');
    expect(container).not.toHaveTextContent('qo_quote_common:generate_document');
  });

  it('merges CRM interactions with audit changes and filters them by user intent', async () => {
    fetchResultMock
      .mockResolvedValueOnce({
        code: '0',
        data: [
          {
            id: 12,
            pid: 'audit-row',
            objectModel: 'crm_opportunity_common',
            objectRecord: 'opportunity-1',
            activityType: 'STATE_CHANGE',
            subject: '商机阶段已更新',
            content: null,
            actorType: 'USER',
            actorId: 7,
            actorName: '林经理',
            commandCode: 'crm:advance_opp',
            operationType: 'update',
            metadata: JSON.stringify({ fromState: 'qualified', toState: 'proposal' }),
            occurredAt: '2026-08-11T10:00:00+08:00',
            createdAt: '2026-08-11T10:00:00+08:00',
          },
        ],
      })
      .mockResolvedValueOnce({
        code: '0',
        data: {
          records: [
            {
              pid: '01KCRM0123456789ABCDEFGHJK',
              crm_act_type: 'meeting',
              crm_act_subject: '方案评审会',
              crm_act_content: '确认决策链与下一步演示安排',
              crm_act_status: 'completed',
              crm_act_priority: 'high',
              owner_name: '周顾问',
              crm_act_date: '2026-08-11T11:00:00+08:00',
            },
            {
              pid: 'task-row',
              crm_act_type: 'task',
              crm_act_subject: '准备演示环境',
              crm_act_status: 'in_progress',
              owner_name: '周顾问',
              crm_act_date: '2026-08-11T09:00:00+08:00',
            },
          ],
        },
      });

    const { container } = render(
      <ActivityTimeline
        modelCode="crm_opportunity_common"
        recordPid="opportunity-1"
        locale="zh-CN"
        businessRecordMapping={{
          activityType: 'crm_act_type',
          subject: 'crm_act_subject',
          content: 'crm_act_content',
          status: 'crm_act_status',
          priority: 'crm_act_priority',
          actorName: 'owner_name',
          occurredAt: 'crm_act_date',
        }}
        businessDataSource={{
          queryCode: 'crm_activities_by_object',
          params: { objectType: 'opportunity', objectId: '${recordPid}' },
        }}
      />,
    );

    await waitFor(() => expect(screen.getByText('方案评审会')).toBeVisible());
    expect(screen.getByText('准备演示环境')).toBeVisible();
    expect(screen.getByText('商机阶段已更新')).toBeVisible();
    expect(screen.getByTestId('activity-timeline-filter-all')).toHaveTextContent('3');
    expect(screen.getByTestId('activity-timeline-filter-interaction')).toHaveTextContent('1');
    expect(screen.getByTestId('activity-timeline-filter-task')).toHaveTextContent('1');
    expect(screen.getByTestId('activity-timeline-filter-system')).toHaveTextContent('1');
    expect(container).not.toHaveTextContent('01KCRM0123456789ABCDEFGHJK');
    expect(fetchResultMock).toHaveBeenNthCalledWith(
      2,
      '/api/datasource/list',
      expect.objectContaining({
        params: expect.objectContaining({
          datasourceId: 'nq:crm_activities_by_object',
          objectId: 'opportunity-1',
        }),
      }),
    );
  });
  it('keeps compact delivery activities without the CRM filter header', async () => {
    fetchResultMock.mockResolvedValue({ code: '0', data: [{
      id: 'completion', pid: 'completion', activityType: 'STATE_CHANGE',
      subject: 'Integration accepted', actorType: 'SYSTEM', occurredAt: '2026-10-14T10:00:00Z',
    }, { id: 'note', pid: 'note', activityType: 'NOTE', subject: null, content: 'Field delta recorded', occurredAt: '2026-10-14T09:00:00Z' }] });
    render(<ActivityTimeline modelCode="tpm_project" recordPid="project" compact locale="en" />);
    await waitFor(() => expect(screen.getByText('Integration accepted')).toBeVisible());
    expect(screen.getByText('Field delta recorded')).toBeVisible();
    expect(screen.queryByRole('tablist')).toBeNull();
    expect(screen.queryByText('Customer activity timeline')).toBeNull();
  });

  it('shows a denied business source even when the audit source succeeds', async () => {
    fetchResultMock.mockResolvedValueOnce({ code: '0', data: [] })
      .mockResolvedValueOnce({ code: '403', message: 'Access forbidden', data: null });
    render(<ActivityTimeline modelCode="tpm_project" recordPid="project" locale="en"
      businessDataSource={{ queryCode: 'delivery_activity' }} />);
    await waitFor(() => expect(screen.getByText('No permission to view activities')).toBeVisible());
    expect(screen.queryByTestId('activity-timeline-empty')).toBeNull();
  });

});
