import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ExecutionLogTraceBlock } from '../ExecutionLogTraceBlock';

const routerMocks = vi.hoisted(() => ({
  navigate: vi.fn(),
}));
const http = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  delete: vi.fn(),
}));
const scrollIntoViewMock = vi.hoisted(() => vi.fn());

vi.mock('react-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-router')>();
  return {
    ...actual,
    useNavigate: () => routerMocks.navigate,
  };
});

vi.mock('~/shared/services/ApiService', () => ({
  getApiService: () => http,
}));

const recentLog = {
  pid: 'log-1',
  traceId: 'trace-1',
  decisionCode: 'sla_deadline',
  selectedVersion: 2,
  status: 'MATCHED',
  callerType: 'AUTOMATION',
  callerRef: 'policy_1',
  rolloutArm: 'CANDIDATE',
  rolloutBucket: 12,
  durationMs: 18,
  createdAt: '2026-06-10T10:00:00Z',
  matchedRulesJson: [{ ruleId: 'R-101' }],
  outputSnapshot: {
    deadlineMinutes: 45,
    review_status: 'pending',
    severity: 'warning',
    wd_req_type: 'annual',
  },
  traceSnapshot: {
    factMetadata: {
      review_status: {
        scope: 'record',
        path: 'data.review_status',
        factKey: 'record.data.review_status',
        label: '审批状态',
        dataType: 'enum',
        modelCode: 'wd_leave_request',
        valueLabels: {
          pending: '待审批',
          approved: '已通过',
        },
      },
      'record.data.wd_req_type': {
        scope: 'record',
        path: 'data.wd_req_type',
        factKey: 'record.data.wd_req_type',
        label: '请假类型',
        dataType: 'enum',
        modelCode: 'wd_leave_request',
        dictCode: 'wd_leave_type',
        valueLabels: {
          annual: '年假',
          sick: '病假',
        },
      },
    },
    virtualSources: [
      {
        sourceRef: 'virtual.leave_request_summary.v1',
        modelCode: 'leave_request_summary_v',
        recordPid: 'REQ-001',
        status: 'RESOLVED',
        fields: {
          slaRiskScore: 91,
          tenant_id: 1,
        },
      },
    ],
    unknownReasons: ['Missing record.data.managerLevel'],
  },
};

const eventPolicyLog = {
  ...recentLog,
  pid: 'log-ep-1',
  traceId: 'trace-ep-1',
  correlationId: 'policy-run-1',
  decisionCode: 'leave_request_automation',
  callerType: 'EVENT_POLICY',
  callerRef: 'leave_request_event_policy',
  matchedRulesJson: [{ ruleId: 'notify_long_leave' }],
};

function mockLogApi() {
  http.get.mockImplementation((endpoint: string, params?: Record<string, unknown>) => {
    if (endpoint === '/decision/logs/recent') {
      return Promise.resolve({
        data: {
          records: [recentLog],
          total: 1,
          size: params?.size ?? 50,
          current: 1,
          pages: 1,
        },
      });
    }
    if (endpoint === '/decision/logs') {
      return Promise.resolve({
        data: [
          {
            ...recentLog,
            pid: 'log-0',
            decisionCode: 'eligibility_gate',
            status: 'NOT_MATCHED',
            createdAt: '2026-06-10T09:59:00Z',
          },
          recentLog,
        ],
      });
    }
    if (endpoint === '/decision/logs/log-1') {
      return Promise.resolve({ data: recentLog });
    }
    if (endpoint === '/event-policy/action-logs') {
      return Promise.resolve({
        data: [
          {
            pid: 'action-log-1',
            decisionTraceId: 'trace-ep-1',
            correlationId: 'policy-run-1',
            policyCode: 'leave_request_event_policy',
            ruleCode: 'notify_long_leave',
            actionType: 'NOTIFY',
            status: 'SUCCESS',
            idempotencyKey: 'REQ-LONG-LEAVE:notify_long_leave:NOTIFY',
            executedAt: '2026-06-10T10:00:01Z',
            resultPayload: {
              sentCount: 1,
              recipientCount: 1,
              channel: 'in_app',
              recipientType: 'ROLE',
              recipientId: 'wd_manager',
              targetUserIds: [1001],
              title: '长假申请提醒',
            },
          },
        ],
      });
    }
    return Promise.resolve({ data: {} });
  });
}

describe('ExecutionLogTraceBlock', () => {
  beforeEach(() => {
    routerMocks.navigate.mockReset();
    http.get.mockReset();
    http.post.mockReset();
    http.delete.mockReset();
    scrollIntoViewMock.mockReset();
    Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', {
      configurable: true,
      value: scrollIntoViewMock,
    });
    mockLogApi();
  });

  it('loads DSL list logs with URL policyCode as keyword and applies advanced filters', async () => {
    render(
      <MemoryRouter initialEntries={['/p/decisionops_execution_logs?policyCode=policy_1']}>
        <ExecutionLogTraceBlock block={{ props: { mode: 'list', pageSize: 50 } }} />
      </MemoryRouter>,
    );

    await waitFor(() =>
      expect(http.get).toHaveBeenCalledWith(
        '/decision/logs/recent',
        expect.objectContaining({
          keyword: 'policy_1',
          page: 0,
          size: 50,
        }),
      ),
    );
    expect(await screen.findByTestId('elta-row-log-1')).toHaveTextContent('SLA 截止时间');
    expect(screen.getByTestId('elta-row-log-1')).not.toHaveTextContent('sla_deadline');
    expect(screen.getByLabelText('log-status')).toHaveTextContent('命中');
    expect(screen.getByLabelText('log-status')).toHaveTextContent('未命中');
    expect(screen.getByLabelText('log-status')).not.toHaveTextContent('MATCHED');
    expect(screen.getByLabelText('log-status')).not.toHaveTextContent('NOT_MATCHED');
    expect(screen.getByLabelText('log-caller-type')).toHaveTextContent('自动化');
    expect(screen.getByLabelText('log-caller-type')).toHaveTextContent('事件策略');
    expect(screen.getByLabelText('log-caller-type')).toHaveTextContent('权限');
    expect(screen.getByLabelText('log-caller-type')).not.toHaveTextContent('AUTOMATION');
    expect(screen.getByLabelText('log-caller-type')).not.toHaveTextContent('EVENT_POLICY');
    expect(screen.getByLabelText('log-caller-type')).not.toHaveTextContent('PERMISSION');
    expect(screen.getByLabelText('log-matched')).toHaveTextContent('全部');
    expect(screen.getByLabelText('log-matched')).toHaveTextContent('命中');
    expect(screen.getByLabelText('log-matched')).toHaveTextContent('未命中');
    expect(screen.getByLabelText('log-matched')).not.toHaveTextContent('true');
    expect(screen.getByLabelText('log-matched')).not.toHaveTextContent('false');
    expect(screen.getByLabelText('log-rollout-arm')).toHaveTextContent('基线');
    expect(screen.getByLabelText('log-rollout-arm')).toHaveTextContent('候选');
    expect(screen.getByLabelText('log-rollout-arm')).not.toHaveTextContent('BASELINE');
    expect(screen.getByLabelText('log-rollout-arm')).not.toHaveTextContent('CANDIDATE');

    fireEvent.change(screen.getByLabelText('log-status'), { target: { value: 'MATCHED' } });
    fireEvent.change(screen.getByLabelText('log-caller-type'), { target: { value: 'AUTOMATION' } });
    fireEvent.change(screen.getByLabelText('log-matched'), { target: { value: 'true' } });
    fireEvent.change(screen.getByLabelText('log-rollout-arm'), { target: { value: 'CANDIDATE' } });
    fireEvent.change(screen.getByLabelText('log-min-duration'), { target: { value: '10' } });
    fireEvent.click(screen.getByTestId('elta-apply'));

    await waitFor(() =>
      expect(http.get).toHaveBeenCalledWith(
        '/decision/logs/recent',
        expect.objectContaining({
          keyword: 'policy_1',
          status: 'MATCHED',
          callerType: 'AUTOMATION',
          matched: true,
          rolloutArm: 'CANDIDATE',
          minDurationMs: 10,
        }),
      ),
    );
  });

  it('passes callerRef from URL filters to recent log API', async () => {
    render(
      <MemoryRouter
        initialEntries={[
          '/p/decisionops_execution_logs?callerType=SLA&callerRef=Manager%20Approval%20SLA',
        ]}
      >
        <ExecutionLogTraceBlock block={{ props: { mode: 'list', pageSize: 25 } }} />
      </MemoryRouter>,
    );

    await waitFor(() =>
      expect(http.get).toHaveBeenCalledWith(
        '/decision/logs/recent',
        expect.objectContaining({
          callerType: 'SLA',
          callerRef: 'Manager Approval SLA',
          page: 0,
          size: 25,
        }),
      ),
    );
  });

  it('links SLA execution logs back to the SLA config detail page', async () => {
    const slaLog = {
      ...recentLog,
      pid: 'sla-log-1',
      traceId: 'trace-sla-1',
      decisionCode: 'complaint_sla_deadline',
      callerType: 'SLA',
      callerRef: '01SLA_CONFIG',
    };
    http.get.mockImplementation((endpoint: string, params?: Record<string, unknown>) => {
      if (endpoint === '/decision/logs/recent') {
        return Promise.resolve({
          data: {
            records: [slaLog],
            total: 1,
            size: params?.size ?? 50,
            current: 1,
            pages: 1,
          },
        });
      }
      if (endpoint === '/decision/logs') {
        return Promise.resolve({ data: [slaLog] });
      }
      if (endpoint === '/decision/logs/sla-log-1') {
        return Promise.resolve({ data: slaLog });
      }
      return Promise.resolve({ data: [] });
    });

    render(
      <MemoryRouter initialEntries={['/p/decisionops_execution_logs?traceId=trace-sla-1']}>
        <ExecutionLogTraceBlock block={{ props: { mode: 'list', pageSize: 50 } }} />
      </MemoryRouter>,
    );

    await screen.findByTestId('elta-row-sla-log-1');
    fireEvent.click(screen.getByTestId('elta-open-trace-sla-log-1'));

    const link = await screen.findByTestId('elta-open-sla-config');
    expect(link).toHaveAttribute('href', '/p/sla_config/view/01SLA_CONFIG');
    expect(await screen.findByTestId('elta-chain-caller-sla-log-1')).toHaveTextContent(
      'SLA / 01SLA_CONFIG',
    );
    expect(screen.queryByTestId('elta-open-permission-audit')).not.toBeInTheDocument();
  });

  it('links Automation execution logs back to the automation designer', async () => {
    const automationLog = {
      ...recentLog,
      pid: 'automation-log-1',
      traceId: 'trace-automation-1',
      decisionCode: 'leave_request_automation',
      callerType: 'AUTOMATION',
      callerRef: 'auto-trace-1',
    };
    http.get.mockImplementation((endpoint: string, params?: Record<string, unknown>) => {
      if (endpoint === '/decision/logs/recent') {
        return Promise.resolve({
          data: {
            records: [automationLog],
            total: 1,
            size: params?.size ?? 50,
            current: 1,
            pages: 1,
          },
        });
      }
      if (endpoint === '/decision/logs') {
        return Promise.resolve({ data: [automationLog] });
      }
      if (endpoint === '/decision/logs/automation-log-1') {
        return Promise.resolve({ data: automationLog });
      }
      return Promise.resolve({ data: [] });
    });

    render(
      <MemoryRouter initialEntries={['/p/decisionops_execution_logs?traceId=trace-automation-1']}>
        <ExecutionLogTraceBlock block={{ props: { mode: 'list', pageSize: 50 } }} />
      </MemoryRouter>,
    );

    await screen.findByTestId('elta-row-automation-log-1');
    fireEvent.click(screen.getByTestId('elta-open-trace-automation-log-1'));

    const link = await screen.findByTestId('elta-open-automation');
    expect(link).toHaveAttribute('href', '/automation/auto-trace-1');
    expect(screen.queryByTestId('elta-open-sla-config')).not.toBeInTheDocument();
    expect(screen.queryByTestId('elta-open-permission-audit')).not.toBeInTheDocument();
  });

  it('links EventPolicy execution logs back to policy detail and designer pages', async () => {
    http.get.mockImplementation((endpoint: string, params?: Record<string, unknown>) => {
      if (endpoint === '/decision/logs/recent') {
        return Promise.resolve({
          data: {
            records: [eventPolicyLog],
            total: 1,
            size: params?.size ?? 50,
            current: 1,
            pages: 1,
          },
        });
      }
      if (endpoint === '/decision/logs') {
        return Promise.resolve({ data: [eventPolicyLog] });
      }
      if (endpoint === '/event-policy/action-logs') {
        return Promise.resolve({ data: [] });
      }
      return Promise.resolve({ data: eventPolicyLog });
    });

    render(
      <MemoryRouter initialEntries={['/p/decisionops_execution_logs?traceId=trace-ep-1']}>
        <ExecutionLogTraceBlock block={{ props: { mode: 'list', pageSize: 50 } }} />
      </MemoryRouter>,
    );

    await screen.findByTestId('elta-row-log-ep-1');
    fireEvent.click(screen.getByTestId('elta-open-trace-log-ep-1'));

    const detailLink = await screen.findByTestId('elta-open-event-policy-detail');
    expect(detailLink).toHaveAttribute(
      'href',
      '/p/decisionops_event_policies/view/leave_request_event_policy',
    );
    expect(screen.getByTestId('elta-open-event-policy-designer')).toHaveAttribute(
      'href',
      '/p/decisionops_event_policy_designer?policyCode=leave_request_event_policy',
    );
    expect(screen.queryByTestId('elta-open-sla-config')).not.toBeInTheDocument();
    expect(screen.queryByTestId('elta-open-permission-audit')).not.toBeInTheDocument();
  });

  it('shows decision output snapshots in the trace drawer', async () => {
    render(
      <MemoryRouter initialEntries={['/p/decisionops_execution_logs?traceId=trace-1']}>
        <ExecutionLogTraceBlock block={{ props: { mode: 'list', pageSize: 50 } }} />
      </MemoryRouter>,
    );

    await screen.findByTestId('elta-row-log-1');
    fireEvent.click(screen.getByTestId('elta-open-trace-log-1'));

    const output = await screen.findByTestId('elta-output-snapshot-log-1');
    expect(output).toHaveTextContent('DMN 输出');
    expect(output).toHaveTextContent('deadlineMinutes');
    expect(output).toHaveTextContent('45');
    expect(output).toHaveTextContent('severity');
    expect(output).toHaveTextContent('warning');
    expect(output).toHaveTextContent('审批状态');
    expect(output).toHaveTextContent('待审批');
    expect(output).toHaveTextContent('请假类型');
    expect(output).toHaveTextContent('年假');
    expect(output).not.toHaveTextContent('annual');
    expect(output).not.toHaveTextContent('review_status');
    expect(output).not.toHaveTextContent('pending');
  });

  it('shows low-code fact metadata snapshots in the trace drawer', async () => {
    render(
      <MemoryRouter initialEntries={['/p/decisionops_execution_logs?traceId=trace-1']}>
        <ExecutionLogTraceBlock block={{ props: { mode: 'list', pageSize: 50 } }} />
      </MemoryRouter>,
    );

    await screen.findByTestId('elta-row-log-1');
    fireEvent.click(screen.getByTestId('elta-open-trace-log-1'));

    const facts = await screen.findByTestId('elta-fact-metadata-log-1');
    expect(facts).toHaveTextContent('事实快照');
    expect(facts).toHaveTextContent('审批状态');
    expect(facts).toHaveTextContent('record.data.review_status');
    expect(facts).toHaveTextContent('pending');
    expect(facts).toHaveTextContent('待审批');
    expect(facts).toHaveTextContent('请假类型');
    expect(facts).toHaveTextContent('record.data.wd_req_type');
    expect(facts).toHaveTextContent('模型 wd_leave_request');
    expect(facts).toHaveTextContent('字典 wd_leave_type');
    expect(facts).toHaveTextContent('annual');
    expect(facts).toHaveTextContent('年假');
    expect(facts).not.toHaveTextContent('tenant_id');
  });

  it('shows virtual source trace evidence in the trace drawer', async () => {
    render(
      <MemoryRouter initialEntries={['/p/decisionops_execution_logs?traceId=trace-1']}>
        <ExecutionLogTraceBlock block={{ props: { mode: 'list', pageSize: 50 } }} />
      </MemoryRouter>,
    );

    await screen.findByTestId('elta-row-log-1');
    fireEvent.click(screen.getByTestId('elta-open-trace-log-1'));

    const virtualSources = await screen.findByTestId('elta-virtual-sources-log-1');
    expect(virtualSources).toHaveTextContent('虚拟源');
    expect(virtualSources).toHaveTextContent('virtual.leave_request_summary.v1');
    expect(virtualSources).toHaveTextContent('RESOLVED');
    expect(virtualSources).toHaveTextContent('slaRiskScore');
    expect(virtualSources).toHaveTextContent('91');
    expect(screen.getByTestId('elta-unknown-reasons-log-1')).toHaveTextContent(
      'Missing record.data.managerLevel',
    );
    expect(screen.getByTestId('elta-trace-drawer')).not.toHaveTextContent('tenant_id');
  });

  it('renders linked action evidence from URL policyCode even when no decision log row matches', async () => {
    http.get.mockImplementation((endpoint: string, params?: Record<string, unknown>) => {
      if (endpoint === '/decision/logs/recent') {
        return Promise.resolve({
          data: {
            records: [],
            total: 0,
            size: params?.size ?? 50,
            current: 1,
            pages: 0,
          },
        });
      }
      if (endpoint === '/event-policy/action-logs') {
        return Promise.resolve({
          data: [
            {
              pid: 'sla-action-log-1',
              policyCode: 'SLA_TIMEOUT:sla-1',
              ruleCode: 'SLA_TIMEOUT',
              actionType: 'SEND_SMS',
              status: 'RETRY_PENDING',
              failureStrategy: 'RETRY_ASYNC',
              idempotencyKey: 'sla-1:SEND_SMS',
              attemptCount: 2,
              maxAttempts: 3,
              nextRetryAt: '2026-07-15T09:33:05Z',
              executedAt: '2026-07-15T09:31:05Z',
              resultPayload: {
                channel: 'sms',
                failureReason: 'sms_delivery_failed',
                errorMessage: 'No real SMS sender available',
                targetPhones: ['+8613800138000'],
              },
              errorMessage: 'No real SMS sender available',
            },
          ],
        });
      }
      return Promise.resolve({ data: {} });
    });

    render(
      <MemoryRouter
        initialEntries={[
          '/p/decisionops_execution_logs?policyCode=SLA_TIMEOUT%3Asla-1&callerType=SLA&callerRef=sla-1',
        ]}
      >
        <ExecutionLogTraceBlock block={{ props: { mode: 'list' } }} />
      </MemoryRouter>,
    );

    await waitFor(() =>
      expect(http.get).toHaveBeenCalledWith(
        '/event-policy/action-logs',
        expect.objectContaining({
          policyCode: 'SLA_TIMEOUT:sla-1',
        }),
      ),
    );
    expect(await screen.findByTestId('elta-linked-action-evidence')).toHaveTextContent(
      '动作执行证据',
    );
    expect(screen.getByTestId('elta-linked-action-evidence')).toHaveTextContent(
      'SLA_TIMEOUT:sla-1',
    );
    expect(screen.getByTestId('elta-linked-action-evidence')).toHaveTextContent('发送短信');
    expect(screen.getByTestId('elta-linked-action-evidence')).not.toHaveTextContent('SEND_SMS');
    expect(screen.getByTestId('elta-linked-action-evidence')).toHaveTextContent('幂等键 已记录');
    expect(screen.getByTestId('elta-linked-action-evidence')).not.toHaveTextContent('sla-1:SEND_SMS');
    expect(screen.getByTestId('elta-linked-action-evidence')).toHaveTextContent('等待重试');
    expect(screen.getByTestId('elta-action-retry-sla-action-log-1')).toHaveTextContent('重试 2/3');
    expect(screen.getByTestId('elta-linked-action-evidence')).toHaveTextContent('失败原因');
    expect(screen.getByTestId('elta-linked-action-evidence')).toHaveTextContent('短信发送失败');
    expect(screen.getByTestId('elta-linked-action-evidence')).not.toHaveTextContent('failureReason');
    expect(screen.getByTestId('elta-linked-action-evidence')).not.toHaveTextContent('sms_delivery_failed');
    expect(screen.getByTestId('elta-linked-action-evidence')).toHaveTextContent('+8613800138000');
    expect(screen.getByTestId('elta-empty')).toHaveTextContent('无匹配日志');
  });

  it('links direct EventPolicy action evidence back to policy detail and designer pages', async () => {
    http.get.mockImplementation((endpoint: string, params?: Record<string, unknown>) => {
      if (endpoint === '/decision/logs/recent') {
        return Promise.resolve({
          data: {
            records: [],
            total: 0,
            size: params?.size ?? 50,
            current: 1,
            pages: 0,
          },
        });
      }
      if (endpoint === '/event-policy/action-logs') {
        return Promise.resolve({
          data: [
            {
              pid: 'event-policy-action-log-1',
              policyCode: 'leave_request_event_policy',
              ruleCode: 'R-1',
              actionType: 'NOTIFY',
              status: 'SUCCESS',
              idempotencyKey: 'leave_request_event_policy:R-1:NOTIFY',
              attemptCount: 1,
              maxAttempts: 3,
              executedAt: '2026-07-15T09:31:05Z',
              resultPayload: {
                channel: 'in_app',
                recipientType: 'USER',
                recipientId: 'admin',
              },
            },
          ],
        });
      }
      return Promise.resolve({ data: {} });
    });

    render(
      <MemoryRouter
        initialEntries={[
          '/p/decisionops_execution_logs?policyCode=leave_request_event_policy&callerType=EVENT_POLICY&callerRef=leave_request_event_policy',
        ]}
      >
        <ExecutionLogTraceBlock block={{ props: { mode: 'list' } }} />
      </MemoryRouter>,
    );

    expect(await screen.findByTestId('elta-linked-action-evidence')).toHaveTextContent('R-1');
    expect(screen.getByTestId('elta-empty')).toHaveTextContent('无匹配日志');
    expect(screen.getByTestId('elta-open-event-policy-detail')).toHaveAttribute(
      'href',
      '/p/decisionops_event_policies/view/leave_request_event_policy',
    );
    expect(screen.getByTestId('elta-open-event-policy-designer')).toHaveAttribute(
      'href',
      '/p/decisionops_event_policy_designer?policyCode=leave_request_event_policy',
    );
  });

  it('renders a fixed execution log table with stable column definitions and truncated cells', async () => {
    render(
      <MemoryRouter initialEntries={['/p/decisionops_execution_logs']}>
        <ExecutionLogTraceBlock block={{ props: { mode: 'list' } }} />
      </MemoryRouter>,
    );

    const row = await screen.findByTestId('elta-row-log-1');
    const table = row.closest('table');

    expect(table).toHaveClass('elta-table');
    expect(screen.getByText('执行日志')).toBeInTheDocument();
    expect(screen.queryByText('Execution Logs')).not.toBeInTheDocument();
    expect(table?.querySelectorAll('colgroup col')).toHaveLength(9);
    expect(table?.querySelector('.elta-col-actions')).toBeTruthy();
    expect(row.querySelectorAll('.elta-cell-text').length).toBeGreaterThanOrEqual(7);
    expect(row).toHaveTextContent('命中');
    expect(row).not.toHaveTextContent('MATCHED');
  });

  it('opens a trace chain drawer without returning to the old console', async () => {
    render(
      <MemoryRouter initialEntries={['/p/decisionops_execution_logs']}>
        <ExecutionLogTraceBlock block={{ props: { mode: 'list' } }} />
      </MemoryRouter>,
    );

    fireEvent.click(await screen.findByTestId('elta-open-trace-log-1'));

    await waitFor(() =>
      expect(http.get).toHaveBeenCalledWith('/decision/logs', { traceId: 'trace-1' }),
    );
    const drawer = await screen.findByTestId('elta-trace-drawer');
    await waitFor(() => expect(drawer).toHaveTextContent('eligibility_gate'));
    expect(drawer).toHaveTextContent('执行链路');
    expect(drawer).not.toHaveTextContent('Trace Chain');
    await waitFor(() => expect(drawer).toHaveTextContent('R-101'));
    expect(drawer).toHaveTextContent('命中');
    expect(drawer).toHaveTextContent('未命中');
    expect(drawer).not.toHaveTextContent('MATCHED');
    expect(drawer).not.toHaveTextContent('NOT_MATCHED');
    expect(routerMocks.navigate).not.toHaveBeenCalledWith('/decision-ops');
  });

  it('opens the list trace as an overlay drawer instead of scrolling an inline panel into view', async () => {
    render(
      <MemoryRouter initialEntries={['/p/decisionops_execution_logs']}>
        <ExecutionLogTraceBlock block={{ props: { mode: 'list' } }} />
      </MemoryRouter>,
    );

    fireEvent.click(await screen.findByTestId('elta-open-trace-log-1'));

    const drawer = await screen.findByTestId('elta-trace-drawer');
    expect(drawer).toHaveAttribute('data-mode', 'list');
    expect(drawer).toHaveAttribute('aria-modal', 'true');
    expect(screen.getByTestId('elta-trace-backdrop')).toBeInTheDocument();
    expect(scrollIntoViewMock).not.toHaveBeenCalled();

    fireEvent.click(screen.getByTestId('elta-trace-backdrop'));
    expect(screen.queryByTestId('elta-trace-drawer')).not.toBeInTheDocument();
  });

  it('renders EventPolicy action execution evidence with result payload in the trace drawer', async () => {
    http.get.mockImplementation((endpoint: string, params?: Record<string, unknown>) => {
      if (endpoint === '/decision/logs/recent') {
        return Promise.resolve({
          data: {
            records: [eventPolicyLog],
            total: 1,
            size: params?.size ?? 50,
            current: 1,
            pages: 1,
          },
        });
      }
      if (endpoint === '/decision/logs') {
        return Promise.resolve({ data: [eventPolicyLog] });
      }
      if (endpoint === '/event-policy/action-logs') {
        return Promise.resolve({
          data: [
            {
              pid: 'action-log-1',
              decisionTraceId: 'trace-ep-1',
              correlationId: 'policy-run-1',
              policyCode: 'leave_request_event_policy',
              ruleCode: 'notify_long_leave',
              actionType: 'NOTIFY',
              status: 'SUCCESS',
              idempotencyKey: 'REQ-LONG-LEAVE:notify_long_leave:NOTIFY',
              attemptCount: 1,
              maxAttempts: 3,
              lastRetryAt: '2026-06-10T10:00:01Z',
              executedAt: '2026-06-10T10:00:01Z',
              resultPayload: {
                sentCount: 1,
                recipientCount: 1,
                channel: 'in_app',
                recipientType: 'ROLE',
                recipientId: 'wd_manager',
                targetUserIds: [1001],
                title: '长假申请提醒',
              },
            },
            {
              pid: 'action-log-2',
              decisionTraceId: 'trace-ep-1',
              correlationId: 'policy-run-1',
              policyCode: 'leave_request_event_policy',
              ruleCode: 'webhook_long_leave',
              actionType: 'WEBHOOK',
              status: 'SUCCESS',
              idempotencyKey: 'REQ-LONG-LEAVE:webhook_long_leave:WEBHOOK',
              executedAt: '2026-06-10T10:00:02Z',
              resultPayload: {
                eventType: 'leave.request.escalated',
                dispatchAccepted: true,
                deliveryEventId: 'ep-webhook-evt-1',
                deliveryTraceStatus: 'tracked_delivery_logs',
                deliveryLogPids: ['delivery-log-1'],
                deliveryReceipts: [
                  {
                    subscriptionPid: 'sub-1',
                    deliveryLogPid: 'delivery-log-1',
                    eventId: 'ep-webhook-evt-1',
                    deliveryStatus: 'failed',
                  },
                ],
                payloadKeys: ['recordPid'],
              },
            },
            {
              pid: 'action-log-2b',
              decisionTraceId: 'trace-ep-1',
              correlationId: 'policy-run-1',
              policyCode: 'leave_request_event_policy',
              ruleCode: 'webhook_dispatch_failure',
              actionType: 'WEBHOOK',
              status: 'FAILED',
              idempotencyKey: 'REQ-LONG-LEAVE:webhook_dispatch_failure:WEBHOOK',
              executedAt: '2026-06-10T10:00:02Z',
              resultPayload: {
                eventType: 'leave.request.escalated',
                dispatchAccepted: false,
                deliveryEventId: 'ep-webhook-evt-failed',
                deliveryTraceStatus: 'dispatch_failed',
                failureReason: 'webhook_dispatch_failed',
                errorMessage: 'dispatcher down',
                recordPid: 'REQ-001',
                payloadKeys: ['recordPid'],
              },
              errorMessage: 'WEBHOOK dispatch failed: dispatcher down',
            },
            {
              pid: 'action-log-4',
              decisionTraceId: 'trace-ep-1',
              correlationId: 'policy-run-1',
              policyCode: 'leave_request_event_policy',
              ruleCode: 'task_long_leave',
              actionType: 'CREATE_TASK',
              status: 'SUCCESS',
              idempotencyKey: 'REQ-LONG-LEAVE:task_long_leave:CREATE_TASK',
              attemptCount: 1,
              maxAttempts: 3,
              executedAt: '2026-06-10T10:00:03Z',
              resultPayload: {
                itemType: 'task',
                createdCount: 1,
                assigneeUserIds: [1001],
                inboxItemIds: [90001],
                modelCode: 'leave_request',
                recordPid: 'REQ-001',
                delivery: 'inbox',
                attemptCount: 1,
                maxAttempts: 3,
              },
            },
            {
              pid: 'action-log-5',
              decisionTraceId: 'trace-ep-1',
              correlationId: 'policy-run-1',
              policyCode: 'leave_request_event_policy',
              ruleCode: 'cc_long_leave',
              actionType: 'CC_TASK',
              status: 'SUCCESS',
              idempotencyKey: 'REQ-LONG-LEAVE:cc_long_leave:CC_TASK',
              attemptCount: 1,
              maxAttempts: 3,
              executedAt: '2026-06-10T10:00:04Z',
              resultPayload: {
                itemType: 'mention',
                delivery: 'inbox',
                ccCount: 1,
                targetUserIds: [1002],
                inboxItemIds: [90002],
                modelCode: 'leave_request',
                recordPid: 'REQ-001',
              },
            },
            {
              pid: 'action-log-6',
              decisionTraceId: 'trace-ep-1',
              correlationId: 'policy-run-1',
              policyCode: 'leave_request_event_policy',
              ruleCode: 'cc_empty_role',
              actionType: 'CC_TASK',
              status: 'FAILED',
              idempotencyKey: 'REQ-LONG-LEAVE:cc_empty_role:CC_TASK',
              attemptCount: 1,
              maxAttempts: 3,
              executedAt: '2026-06-10T10:00:05Z',
              resultPayload: {
                itemType: 'mention',
                delivery: 'inbox',
                failureReason: 'target_resolved_no_users',
                targetType: 'ROLE',
                target: 'ROLE:empty_role',
                resolvedCount: 0,
              },
              errorMessage: 'CC_TASK target resolved no users: ROLE:empty_role',
            },
            {
              pid: 'action-log-7',
              decisionTraceId: 'trace-ep-1',
              correlationId: 'policy-run-1',
              policyCode: 'leave_request_event_policy',
              ruleCode: 'start_approval',
              actionType: 'START_PROCESS',
              status: 'SUCCESS',
              idempotencyKey: 'REQ-LONG-LEAVE:start_approval:START_PROCESS',
              attemptCount: 1,
              maxAttempts: 3,
              executedAt: '2026-06-10T10:00:06Z',
              resultPayload: {
                processDefinitionId: 'approval_flow',
                processInstanceId: '1784160001001',
                businessKey: 'REQ-001',
                recordPid: 'REQ-001',
              },
            },
            {
              pid: 'action-log-8',
              decisionTraceId: 'trace-ep-1',
              correlationId: 'policy-run-1',
              policyCode: 'leave_request_event_policy',
              ruleCode: 'start_missing_approval',
              actionType: 'START_PROCESS',
              status: 'FAILED',
              idempotencyKey: 'REQ-LONG-LEAVE:start_missing_approval:START_PROCESS',
              attemptCount: 1,
              maxAttempts: 3,
              executedAt: '2026-06-10T10:00:07Z',
              resultPayload: {
                failureReason: 'process_start_failed',
                processDefinitionId: 'missing_approval_flow',
                businessKey: 'REQ-001',
                recordPid: 'REQ-001',
              },
              errorMessage: '流程启动失败：流程未部署或流程标识不存在',
            },
            {
              pid: 'action-log-9',
              decisionTraceId: 'trace-ep-1',
              correlationId: 'policy-run-1',
              policyCode: 'leave_request_event_policy',
              ruleCode: 'start_process_missing_config',
              actionType: 'START_PROCESS',
              status: 'FAILED',
              idempotencyKey: 'REQ-LONG-LEAVE:start_process_missing_config:START_PROCESS',
              attemptCount: 1,
              maxAttempts: 3,
              executedAt: '2026-06-10T10:00:08Z',
              resultPayload: {
                failureReason: 'process_definition_missing',
                field: 'payload.processDefinitionId',
                recordPid: 'REQ-MISSING-CONFIG',
              },
              errorMessage: '缺少流程标识，无法启动流程',
            },
            {
              pid: 'action-log-10',
              decisionTraceId: 'trace-ep-1',
              correlationId: 'policy-run-1',
              policyCode: 'leave_request_event_policy',
              ruleCode: 'patch_leave_status',
              actionType: 'PATCH_RECORD',
              status: 'FAILED',
              idempotencyKey: 'REQ-LONG-LEAVE:patch_leave_status:PATCH_RECORD',
              attemptCount: 1,
              maxAttempts: 3,
              executedAt: '2026-06-10T10:00:09Z',
              resultPayload: {
                failureReason: 'record_update_failed',
                modelCode: 'leave_request',
                recordPid: 'REQ-001',
                updatedFields: ['status', 'priority'],
                fieldCount: 2,
                errorMessage: 'model field status is readonly',
                actionType: 'PATCH_RECORD',
              },
              errorMessage: 'UPDATE_RECORD failed: model field status is readonly',
            },
            {
              pid: 'action-log-11',
              decisionTraceId: 'trace-ep-1',
              correlationId: 'policy-run-1',
              policyCode: 'leave_request_event_policy',
              ruleCode: 'comment_leave_request',
              actionType: 'ADD_COMMENT',
              status: 'FAILED',
              idempotencyKey: 'REQ-LONG-LEAVE:comment_leave_request:ADD_COMMENT',
              attemptCount: 1,
              maxAttempts: 3,
              executedAt: '2026-06-10T10:00:10Z',
              resultPayload: {
                failureReason: 'comment_write_failed',
                modelCode: 'leave_request',
                recordPid: 'REQ-001',
                content: '请主管关注长假申请',
                mentions: '@wd-manager',
                errorMessage: 'comment table unavailable',
                actionType: 'ADD_COMMENT',
              },
              errorMessage: 'ADD_COMMENT failed: comment table unavailable',
            },
            {
              pid: 'action-log-12',
              decisionTraceId: 'trace-ep-1',
              correlationId: 'policy-run-1',
              policyCode: 'leave_request_event_policy',
              ruleCode: 'audit_leave_request',
              actionType: 'WRITE_AUDIT',
              status: 'FAILED',
              idempotencyKey: 'REQ-LONG-LEAVE:audit_leave_request:WRITE_AUDIT',
              attemptCount: 1,
              maxAttempts: 3,
              executedAt: '2026-06-10T10:00:11Z',
              resultPayload: {
                failureReason: 'audit_write_failed',
                tenantId: 101,
                ruleCode: 'audit_leave_request',
                actionType: 'WRITE_AUDIT',
                target: 'AUDIT:leave_request',
                message: '长假申请审计 REQ-001',
                auditPid: 'AUD-001',
                errorMessage: 'audit table unavailable',
              },
              errorMessage: 'WRITE_AUDIT failed: audit table unavailable',
            },
            {
              pid: 'action-log-3',
              decisionTraceId: 'trace-ep-1',
              correlationId: 'policy-run-1',
              policyCode: 'leave_request_event_policy',
              ruleCode: 'sms_long_leave',
              actionType: 'SEND_SMS',
              status: 'DEAD_LETTER',
              failureStrategy: 'RETRY_ASYNC',
              idempotencyKey: 'REQ-LONG-LEAVE:sms_long_leave:SEND_SMS',
              attemptCount: 3,
              maxAttempts: 3,
              lastRetryAt: '2026-06-10T10:02:00Z',
              deadLetteredAt: '2026-06-10T10:02:01Z',
              executedAt: '2026-06-10T10:02:01Z',
              resultPayload: {
                channel: 'sms',
                targetPhones: ['+8613800138000'],
                retryExhausted: true,
              },
              errorMessage:
                'Retry attempts exhausted after 3 attempts: No real SMS sender available',
            },
          ],
        });
      }
      return Promise.resolve({ data: {} });
    });

    render(
      <MemoryRouter initialEntries={['/p/decisionops_execution_logs']}>
        <ExecutionLogTraceBlock block={{ props: { mode: 'list' } }} />
      </MemoryRouter>,
    );

    fireEvent.click(await screen.findByTestId('elta-open-trace-log-ep-1'));

    await waitFor(() =>
      expect(http.get).toHaveBeenCalledWith('/event-policy/action-logs', {
        decisionTraceId: 'trace-ep-1',
        correlationId: 'policy-run-1',
      }),
    );
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent('动作执行证据');
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent('幂等键 已记录');
    expect(screen.getByTestId('elta-action-card-action-log-10')).not.toHaveTextContent(
      'REQ-LONG-LEAVE:patch_leave_status:PATCH_RECORD',
    );
    expect(screen.getByTestId('elta-action-card-action-log-11')).not.toHaveTextContent(
      'REQ-LONG-LEAVE:comment_leave_request:ADD_COMMENT',
    );
    expect(screen.getByTestId('elta-action-card-action-log-12')).not.toHaveTextContent(
      'REQ-LONG-LEAVE:audit_leave_request:WRITE_AUDIT',
    );
    expect(
      screen.getByTestId('elta-action-card-action-log-12').querySelector('[title]'),
    ).toHaveAttribute('title', 'REQ-LONG-LEAVE:audit_leave_request:WRITE_AUDIT');
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent('请假申请自动化策略');
    expect(screen.getByTestId('elta-trace-drawer')).not.toHaveTextContent(
      'leave_request_automation',
    );
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent('notify_long_leave');
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent('发送数 1');
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent('接收人数 1');
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent('通道 in_app');
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent('接收对象 wd_manager');
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent('接收用户 1001');
    expect(screen.getByTestId('elta-action-retry-action-log-1')).toHaveTextContent('尝试 1/3');
    expect(screen.getByTestId('elta-action-retry-action-log-1')).not.toHaveTextContent('重试 1/3');
    expect(screen.getByTestId('elta-action-retry-action-log-1')).not.toHaveTextContent('上次');
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent('webhook_long_leave');
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent('投递追踪 ep-webhook-evt-1');
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent('投递状态 已记录投递日志');
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent('投递日志 delivery-log-1');
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent(
      '投递回执 sub-1 / delivery-log-1 / failed',
    );
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent('webhook_dispatch_failure');
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent(
      '投递追踪 ep-webhook-evt-failed',
    );
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent('投递状态 投递失败');
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent(
      '失败原因 Webhook 投递失败',
    );
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent('错误信息 dispatcher down');
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent('业务记录 REQ-001');
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent('task_long_leave');
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent('创建任务');
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent('创建数 1');
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent('处理人 1001');
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent('待办记录 90001');
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent('待办类型 任务');
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent('投递方式 待办');
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent('模型 leave_request');
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent('业务记录 REQ-001');
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent('尝试次数 1');
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent('最大尝试 3');
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent('cc_long_leave');
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent('抄送任务');
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent('抄送数 1');
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent('接收用户 1002');
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent('待办记录 90002');
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent('待办类型 抄送任务');
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent('投递方式 待办');
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent('cc_empty_role');
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent('失败原因 目标未匹配到用户');
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent('接收类型 角色');
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent('接收对象 ROLE:empty_role');
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent('解析人数 0');
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent('start_approval');
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent('启动流程');
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent('流程标识 approval_flow');
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent('流程实例 1784160001001');
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent('业务主键 REQ-001');
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent('业务记录 REQ-001');
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent('start_missing_approval');
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent('失败原因 流程启动失败');
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent(
      '流程标识 missing_approval_flow',
    );
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent('业务主键 REQ-001');
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent('业务记录 REQ-001');
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent(
      '流程启动失败：流程未部署或流程标识不存在',
    );
    expect(screen.getByTestId('elta-action-retry-action-log-8')).toHaveTextContent('重试 1/3');
    expect(screen.getByTestId('elta-action-replay-action-log-8')).toHaveTextContent('重放');
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent(
      'start_process_missing_config',
    );
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent('失败原因 缺少流程标识');
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent('字段 流程标识');
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent(
      '业务记录 REQ-MISSING-CONFIG',
    );
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent(
      '缺少流程标识，无法启动流程',
    );
    expect(screen.getByTestId('elta-action-retry-action-log-9')).toHaveTextContent('重试 1/3');
    expect(screen.getByTestId('elta-action-replay-action-log-9')).toHaveTextContent('重放');
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent('patch_leave_status');
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent('更新记录');
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent('失败原因 更新记录失败');
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent('模型 leave_request');
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent('业务记录 REQ-001');
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent('更新字段 status, priority');
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent('错误信息 model field status is readonly');
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent('动作类型 更新记录');
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent('字段数 2');
    expect(screen.getByTestId('elta-action-retry-action-log-10')).toHaveTextContent('重试 1/3');
    expect(screen.getByTestId('elta-action-replay-action-log-10')).toHaveTextContent('重放');
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent('comment_leave_request');
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent('添加评论');
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent('失败原因 添加评论失败');
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent('模型 leave_request');
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent('业务记录 REQ-001');
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent(
      '评论内容 请主管关注长假申请',
    );
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent('提及对象 @wd-manager');
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent(
      '错误信息 comment table unavailable',
    );
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent('动作类型 添加评论');
    expect(screen.getByTestId('elta-action-retry-action-log-11')).toHaveTextContent('重试 1/3');
    expect(screen.getByTestId('elta-action-replay-action-log-11')).toHaveTextContent('重放');
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent('audit_leave_request');
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent('写入审计');
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent('失败原因 写入审计失败');
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent('租户 101');
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent('规则 audit_leave_request');
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent('接收对象 AUDIT:leave_request');
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent('消息 长假申请审计 REQ-001');
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent('审计记录 AUD-001');
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent(
      '错误信息 audit table unavailable',
    );
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent('动作类型 写入审计');
    expect(screen.getByTestId('elta-action-retry-action-log-12')).toHaveTextContent('重试 1/3');
    expect(screen.getByTestId('elta-action-replay-action-log-12')).toHaveTextContent('重放');
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent('sms_long_leave');
    expect(screen.getByTestId('elta-action-retry-action-log-3')).toHaveTextContent('重试 3/3');
    expect(screen.getByTestId('elta-action-retry-action-log-3')).toHaveTextContent(
      '上次 2026-06-10 10:02:00',
    );
    expect(screen.getByTestId('elta-action-retry-action-log-3')).toHaveTextContent(
      '死信 2026-06-10 10:02:01',
    );
    expect(screen.getByTestId('elta-action-retry-action-log-3')).toHaveTextContent('重试已耗尽');
    expect(screen.getByTestId('elta-action-replay-action-log-3')).toHaveTextContent('重放');
    expect(screen.getByTestId('elta-trace-drawer')).not.toHaveTextContent('targetUserIds');
    expect(screen.getByTestId('elta-trace-drawer')).not.toHaveTextContent('deliveryEventId');
    expect(screen.getByTestId('elta-trace-drawer')).not.toHaveTextContent('deliveryLogPids');
    expect(screen.getByTestId('elta-trace-drawer')).not.toHaveTextContent('createdCount');
    expect(screen.getByTestId('elta-trace-drawer')).not.toHaveTextContent('ccCount');
    expect(screen.getByTestId('elta-trace-drawer')).not.toHaveTextContent('inboxItemIds');
    expect(screen.getByTestId('elta-trace-drawer')).not.toHaveTextContent('modelCode');
    expect(screen.getByTestId('elta-trace-drawer')).not.toHaveTextContent('recordPid');
    expect(screen.getByTestId('elta-trace-drawer')).not.toHaveTextContent('attemptCount');
    expect(screen.getByTestId('elta-trace-drawer')).not.toHaveTextContent('maxAttempts');
    expect(screen.getByTestId('elta-trace-drawer')).not.toHaveTextContent('tracked_delivery_logs');
    expect(screen.getByTestId('elta-trace-drawer')).not.toHaveTextContent('dispatch_failed');
    expect(screen.getByTestId('elta-trace-drawer')).not.toHaveTextContent(
      'webhook_dispatch_failed',
    );
    expect(screen.getByTestId('elta-trace-drawer')).not.toHaveTextContent('errorMessage');
    expect(screen.getByTestId('elta-trace-drawer')).not.toHaveTextContent('payloadKeys');
    expect(screen.getByTestId('elta-trace-drawer')).not.toHaveTextContent('failureReason');
    expect(screen.getByTestId('elta-trace-drawer')).not.toHaveTextContent(
      'target_resolved_no_users',
    );
    expect(screen.getByTestId('elta-trace-drawer')).not.toHaveTextContent('process_start_failed');
    expect(screen.getByTestId('elta-trace-drawer')).not.toHaveTextContent(
      'process_definition_missing',
    );
    expect(screen.getByTestId('elta-trace-drawer')).not.toHaveTextContent(
      'payload.processDefinitionId',
    );
    expect(screen.getByTestId('elta-trace-drawer')).not.toHaveTextContent('resolvedCount');
    expect(screen.getByTestId('elta-trace-drawer')).not.toHaveTextContent('record_update_failed');
    expect(screen.getByTestId('elta-trace-drawer')).not.toHaveTextContent('updatedFields');
    expect(screen.getByTestId('elta-trace-drawer')).not.toHaveTextContent('fieldCount');
    expect(screen.getByTestId('elta-trace-drawer')).not.toHaveTextContent('comment_write_failed');
    expect(screen.getByTestId('elta-trace-drawer')).not.toHaveTextContent('audit_write_failed');
    expect(screen.getByTestId('elta-trace-drawer')).not.toHaveTextContent('processDefinitionId');
    expect(screen.getByTestId('elta-trace-drawer')).not.toHaveTextContent('processInstanceId');
    expect(screen.getByTestId('elta-trace-drawer')).not.toHaveTextContent('businessKey');

    http.post.mockResolvedValueOnce({
      data: {
        pid: 'action-log-3',
        decisionTraceId: 'trace-ep-1',
        correlationId: 'policy-run-1',
        policyCode: 'leave_request_event_policy',
        ruleCode: 'sms_long_leave',
        actionType: 'SEND_SMS',
        status: 'DEAD_LETTER',
        failureStrategy: 'RETRY_ASYNC',
        idempotencyKey: 'REQ-LONG-LEAVE:sms_long_leave:SEND_SMS',
        attemptCount: 4,
        maxAttempts: 3,
        lastRetryAt: '2026-06-10T10:03:00Z',
        deadLetteredAt: '2026-06-10T10:03:01Z',
        executedAt: '2026-06-10T10:03:01Z',
        resultPayload: {
          channel: 'sms',
          targetPhones: ['+8613800138000'],
          retryExhausted: true,
        },
        errorMessage: 'Retry attempts exhausted after 4 attempts: No real SMS sender available',
      },
    });
    fireEvent.click(screen.getByTestId('elta-action-replay-action-log-3'));
    await waitFor(() =>
      expect(http.post).toHaveBeenCalledWith(
        '/event-policy/action-logs/action-log-3/replay',
        undefined,
      ),
    );
    await waitFor(() =>
      expect(screen.getByTestId('elta-action-retry-action-log-3')).toHaveTextContent('重试 4/3'),
    );
    expect(screen.getByTestId('elta-action-retry-action-log-3')).toHaveTextContent(
      '上次 2026-06-10 10:03:00',
    );
  });

  it('loads a DSL detail route by pid and expands the same trace chain', async () => {
    render(
      <MemoryRouter initialEntries={['/p/decisionops_execution_logs/view/log-1']}>
        <Routes>
          <Route
            path="/p/decisionops_execution_logs/view/:recordPid"
            element={<ExecutionLogTraceBlock block={{ props: { mode: 'detail' } }} />}
          />
        </Routes>
      </MemoryRouter>,
    );

    await waitFor(() => expect(http.get).toHaveBeenCalledWith('/decision/logs/log-1', undefined));
    await waitFor(() =>
      expect(http.get).toHaveBeenCalledWith('/decision/logs', { traceId: 'trace-1' }),
    );
    expect(screen.getByTestId('elta-trace-drawer')).toHaveAttribute('data-mode', 'detail');
    expect(screen.getByTestId('elta-trace-drawer')).not.toHaveAttribute('aria-modal');
    expect(screen.queryByTestId('elta-trace-backdrop')).not.toBeInTheDocument();
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent('SLA 截止时间');
    expect(screen.getByTestId('elta-trace-drawer')).not.toHaveTextContent('sla_deadline');
  });
});
