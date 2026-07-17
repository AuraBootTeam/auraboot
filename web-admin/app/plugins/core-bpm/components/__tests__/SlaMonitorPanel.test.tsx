import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { readFileSync } from 'node:fs';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const stableToastContext = {
  showErrorToast: vi.fn(),
};

vi.mock('react-router', () => ({
  useNavigate: () => vi.fn(),
}));

vi.mock('~/contexts/ToastContext', () => ({
  useToastContext: () => stableToastContext,
}));

vi.mock('~/utils/i18n', () => ({
  useSmartText: () => (key: string, fallback?: string) => fallback ?? key,
}));

vi.mock('~/plugins/core-bpm/services/slaService', () => ({
  getDashboard: vi.fn(),
  listSlaConfigs: vi.fn(),
  listSlaDecisionLogs: vi.fn(),
  listSlaActionLogs: vi.fn(),
  replaySlaActionLog: vi.fn(),
  listSlaRecords: vi.fn(),
}));

import { SlaMonitorPanel } from '../SlaMonitorPanel';
import * as slaService from '~/plugins/core-bpm/services/slaService';

const mockGetDashboard = vi.mocked(slaService.getDashboard);
const mockListSlaConfigs = vi.mocked((slaService as any).listSlaConfigs);
const mockListSlaDecisionLogs = vi.mocked((slaService as any).listSlaDecisionLogs);
const mockListSlaActionLogs = vi.mocked((slaService as any).listSlaActionLogs);
const mockReplaySlaActionLog = vi.mocked((slaService as any).replaySlaActionLog);
const mockListSlaRecords = vi.mocked(slaService.listSlaRecords);

const REQUIRED_SLA_MONITOR_I18N_KEYS = [
  'common.enabled',
  'common.disabled',
  'common.view',
  'bpm.sla.monitor.strategyChain',
  'bpm.sla.monitor.strategyChain.subtitle',
  'bpm.sla.monitor.strategyChain.configs',
  'bpm.sla.monitor.strategyChain.empty',
  'bpm.sla.monitor.strategyChain.noInput',
  'bpm.sla.monitor.strategyChain.noOutput',
  'bpm.sla.monitor.strategyChain.noActions',
  'bpm.sla.monitor.strategyChain.actionEvidence',
  'bpm.sla.monitor.strategyChain.noActionEvidence',
  'bpm.sla.monitor.strategyChain.replay',
  'bpm.sla.monitor.strategyChain.replaying',
  'bpm.sla.monitor.strategyChain.replayFailed',
  'bpm.sla.monitor.strategyChain.trace',
  'bpm.sla.monitor.strategyChain.logs',
  'bpm.sla.monitor.strategyChain.noLogs',
  'bpm.sla.monitor.strategyChain.loadError',
];

describe('SlaMonitorPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    stableToastContext.showErrorToast.mockReset();
    mockGetDashboard.mockResolvedValue({
      processDefinitions: { total: 1, draft: 0, deployed: 1, suspended: 0 },
      sla: { active: 0, running: 0, warning: 0, overdue: 0, paused: 0 },
      slaConfigs: { total: 2, enabled: 2 },
    });
    mockListSlaConfigs.mockResolvedValue([]);
    mockListSlaDecisionLogs.mockResolvedValue([]);
    mockListSlaActionLogs.mockResolvedValue([]);
    mockReplaySlaActionLog.mockResolvedValue({});
    mockListSlaRecords.mockResolvedValue([]);
  });

  it('loads the dashboard once even when translated callbacks are not referentially stable', async () => {
    render(<SlaMonitorPanel />);

    await waitFor(() => expect(screen.getByTestId('sla-dashboard-configs')).toBeInTheDocument());

    await new Promise((resolve) => setTimeout(resolve, 0));

    expect(mockGetDashboard).toHaveBeenCalledTimes(1);
    expect(screen.queryByText('Loading...')).not.toBeInTheDocument();
  });

  it('loads drill-down records once after a stat card is opened', async () => {
    render(<SlaMonitorPanel />);

    await waitFor(() => expect(screen.getByTestId('sla-stat-ALL')).toBeInTheDocument());
    fireEvent.click(screen.getByTestId('sla-stat-ALL'));

    await waitFor(() => expect(screen.getByTestId('sla-drill-empty')).toBeInTheDocument());
    await new Promise((resolve) => setTimeout(resolve, 0));

    expect(mockListSlaRecords).toHaveBeenCalledTimes(1);
    expect(mockListSlaRecords).toHaveBeenCalledWith(undefined);
  });

  it('shows the SLA strategy chain with rule binding, action plan, and recent log evidence', async () => {
    mockListSlaConfigs.mockResolvedValue([
      {
        pid: 'sla-1',
        name: '主管审批 SLA',
        targetType: 'NODE',
        targetKey: 'task_manager_approve',
        deadlineMode: 'FIXED',
        deadlineValue: 'PT30S',
        suspendPolicy: 'pause',
        enabled: true,
        ruleBinding: {
          consumerType: 'SLA',
          consumerCode: 'wd_manager_approve_sla',
          consumerNodeId: 'task_manager_approve',
          bindingKind: 'DECISION_REF',
          enabled: true,
          decisionBinding: {
            decisionCode: 'complaint_sla_deadline',
            inputMappings: [
              {
                input: 'targetKey',
                source: { kind: 'FIELD', scope: 'record', path: 'data.targetKey' },
              },
            ],
            outputMappings: [
              {
                output: 'deadlineMinutes',
                target: { kind: 'SLA_FIELD', path: 'deadlineMinutes' },
              },
            ],
          },
        },
        actionPolicy: {
          trigger: 'SLA_TIMEOUT',
          failureStrategy: 'FAIL_FAST',
          actions: [
            {
              type: 'NOTIFY',
              target: 'ROLE:wd_manager',
              order: 10,
              payload: { title: '主管审批 SLA 超时' },
            },
            {
              type: 'SEND_SMS',
              target: 'PHONE:+8613800138000',
              order: 20,
              payload: { content: '主管审批 SLA 超时' },
            },
            {
              type: 'SEND_IM',
              target: 'USER:42',
              order: 25,
              payload: { content: 'IM 提醒 ${record.recordPid}' },
            },
            {
              type: 'WEBHOOK',
              target: 'WEBHOOK:sla.timeout',
              order: 28,
              payload: { eventType: 'sla.timeout' },
            },
            {
              type: 'CREATE_TASK',
              target: 'USER:42',
              order: 30,
              payload: { title: '创建复核待办' },
            },
            {
              type: 'CC_TASK',
              target: 'USER:42',
              order: 40,
              payload: { taskTitle: '抄送 HR 复核' },
            },
          ],
        },
      },
    ]);
    mockListSlaDecisionLogs.mockResolvedValue([
      {
        pid: 'log-1',
        decisionCode: 'complaint_sla_deadline',
        callerType: 'SLA',
        callerRef: 'sla-1',
        status: 'MATCHED',
        matched: true,
        createdAt: '2026-07-05T09:30:00Z',
      },
    ]);
    mockListSlaActionLogs.mockResolvedValue([
      {
        pid: 'act-missing',
        policyCode: 'SLA_TIMEOUT:sla-1',
        ruleCode: 'SLA_TIMEOUT',
        actionType: 'UNKNOWN_ACTION',
        status: 'NO_HANDLER',
        errorMessage: 'no handler for action type UNKNOWN_ACTION',
        resultPayload: {},
        executedAt: '2026-07-05T09:30:40Z',
      },
      {
        pid: 'act-not-executed',
        policyCode: 'SLA_TIMEOUT:sla-1',
        ruleCode: 'SLA_TIMEOUT',
        actionType: 'NOTIFY',
        status: 'NOT_EXECUTED',
        resultPayload: {},
        executedAt: '2026-07-05T09:30:41Z',
      },
      {
        pid: 'act-1',
        policyCode: 'SLA_TIMEOUT:sla-1',
        ruleCode: 'SLA_TIMEOUT',
        actionType: 'SEND_SMS',
        status: 'FAILED',
        errorMessage: 'No real SMS sender available',
        resultPayload: { channel: 'sms', targetPhones: ['+8613800138000'] },
        executedAt: '2026-07-05T09:31:00Z',
      },
      {
        pid: 'act-retry',
        policyCode: 'SLA_TIMEOUT:sla-1',
        decisionTraceId: 'trace-sla-retry-1',
        correlationId: 'corr-sla-retry-1',
        ruleCode: 'SLA_TIMEOUT',
        actionType: 'SEND_SMS',
        status: 'RETRY_PENDING',
        failureStrategy: 'RETRY_ASYNC',
        attemptCount: 2,
        maxAttempts: 3,
        lastRetryAt: '2026-07-05T09:32:05Z',
        nextRetryAt: '2026-07-05T09:33:05Z',
        errorMessage: 'No real SMS sender available',
        resultPayload: { channel: 'sms', targetPhones: ['+8613800138002'] },
        executedAt: '2026-07-05T09:31:05Z',
      },
      {
        pid: 'act-dead-letter',
        policyCode: 'SLA_TIMEOUT:sla-1',
        ruleCode: 'SLA_TIMEOUT',
        actionType: 'SEND_SMS',
        status: 'DEAD_LETTER',
        failureStrategy: 'RETRY_ASYNC',
        attemptCount: 3,
        maxAttempts: 3,
        lastRetryAt: '2026-07-05T09:34:10Z',
        deadLetteredAt: '2026-07-05T09:34:11Z',
        errorMessage: 'No real SMS sender available',
        resultPayload: { channel: 'sms', targetPhones: ['+8613800138003'], retryExhausted: true },
        executedAt: '2026-07-05T09:31:10Z',
      },
      {
        pid: 'act-im',
        policyCode: 'SLA_TIMEOUT:sla-1',
        ruleCode: 'SLA_TIMEOUT',
        actionType: 'SEND_IM',
        status: 'SUCCESS',
        attemptCount: 1,
        maxAttempts: 3,
        lastRetryAt: '2026-07-05T09:31:30Z',
        resultPayload: {
          channel: 'im',
          sentCount: 1,
          targetUserIds: [42],
          conversationIds: [9101],
          messageIds: [9102],
          recordPid: 'REQ-SLA-3',
        },
        executedAt: '2026-07-05T09:31:30Z',
      },
      {
        pid: 'act-webhook',
        policyCode: 'SLA_TIMEOUT:sla-1',
        ruleCode: 'SLA_TIMEOUT',
        actionType: 'WEBHOOK',
        status: 'SUCCESS',
        resultPayload: {
          eventType: 'sla.timeout',
          dispatchAccepted: true,
          deliveryEventId: 'REQ-SLA-4:timeout:WEBHOOK:event',
          deliveryTraceStatus: 'tracked_delivery_logs',
          deliveryLogPids: ['wh-log-1'],
          deliveryReceipts: [{ deliveryStatus: 'failed', deliveryLogPid: 'wh-log-1' }],
          recordPid: 'REQ-SLA-4',
        },
        executedAt: '2026-07-05T09:31:45Z',
      },
      {
        pid: 'act-audit',
        policyCode: 'SLA_TIMEOUT:sla-1',
        ruleCode: 'SLA_TIMEOUT',
        actionType: 'WRITE_AUDIT',
        status: 'SUCCESS',
        resultPayload: {
          auditPid: 'audit-1',
          message: 'SLA 审计 REQ-SLA-5',
        },
        executedAt: '2026-07-05T09:31:50Z',
      },
      {
        pid: 'act-comment',
        policyCode: 'SLA_TIMEOUT:sla-1',
        ruleCode: 'SLA_TIMEOUT',
        actionType: 'ADD_COMMENT',
        status: 'SUCCESS',
        resultPayload: {
          commentPid: 'comment-1',
          content: 'SLA 评论 REQ-SLA-6',
          recordPid: 'REQ-SLA-6',
        },
        executedAt: '2026-07-05T09:31:55Z',
      },
      {
        pid: 'act-2',
        policyCode: 'SLA_TIMEOUT:sla-1',
        ruleCode: 'SLA_TIMEOUT',
        actionType: 'CREATE_TASK',
        status: 'SUCCESS',
        resultPayload: {
          itemType: 'task',
          createdCount: 1,
          assigneeUserIds: [42],
          inboxItemIds: [9001],
          recordPid: 'REQ-SLA-1',
        },
        executedAt: '2026-07-05T09:32:00Z',
      },
      {
        pid: 'act-3',
        policyCode: 'SLA_TIMEOUT:sla-1',
        ruleCode: 'SLA_TIMEOUT',
        actionType: 'CC_TASK',
        status: 'SUCCESS',
        resultPayload: {
          itemType: 'mention',
          ccCount: 1,
          targetUserIds: [42],
          inboxItemIds: [9002],
          recordPid: 'REQ-SLA-2',
        },
        executedAt: '2026-07-05T09:33:00Z',
      },
    ]);

    render(<SlaMonitorPanel />);

    await waitFor(() => expect(screen.getByTestId('sla-strategy-chain')).toBeInTheDocument());

    expect(mockListSlaConfigs).toHaveBeenCalledTimes(1);
    expect(mockListSlaDecisionLogs).toHaveBeenCalledWith({ callerType: 'SLA', size: 20 });
    expect(mockListSlaActionLogs).toHaveBeenCalledWith({ policyCodePrefix: 'SLA_TIMEOUT:', size: 50 });
    expect(screen.getByText('主管审批 SLA')).toBeInTheDocument();
    expect(screen.getByText('节点 / 主管审批节点')).toBeInTheDocument();
    expect(screen.queryByText('节点 / task_manager_approve')).not.toBeInTheDocument();
    expect(screen.queryByText('NODE / task_manager_approve')).not.toBeInTheDocument();
    const decisionLink = screen.getByTestId('sla-decision-link-complaint_sla_deadline');
    expect(decisionLink).toHaveTextContent('请假审批 SLA 截止时间');
    expect(decisionLink).not.toHaveTextContent('complaint_sla_deadline');
    expect(screen.queryByText('complaint_sla_deadline')).not.toBeInTheDocument();
    expect(screen.getByText('SLA 节点 ← 当前记录.SLA 节点')).toBeInTheDocument();
    expect(screen.getByText('截止分钟数 → SLA.截止分钟数')).toBeInTheDocument();
    expect(screen.queryByText('targetKey ← record.data.targetKey')).not.toBeInTheDocument();
    expect(screen.queryByText('deadlineMinutes → SLA.deadlineMinutes')).not.toBeInTheDocument();
    expect(screen.getByText('SLA 超时')).toBeInTheDocument();
    expect(screen.getByTestId('sla-failure-strategy-sla-1')).toHaveTextContent('失败即停止');
    expect(screen.queryByText('SLA_TIMEOUT')).not.toBeInTheDocument();
    expect(screen.getAllByText('站内通知').length).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByText('短信').length).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByText('发送 IM').length).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByText('Webhook').length).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByText('创建任务').length).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByText('抄送任务').length).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByText('审计').length).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByText('评论').length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText('动作执行证据')).toBeInTheDocument();
    expect(screen.getByText('失败')).toBeInTheDocument();
    expect(screen.getByText('处理器缺失')).toBeInTheDocument();
    expect(screen.getByText('等待重试')).toBeInTheDocument();
    expect(screen.getByText('进入死信')).toBeInTheDocument();
    expect(screen.getByText('未执行')).toBeInTheDocument();
    expect(screen.getAllByText('成功').length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText(/动作处理器不可用/)).toBeInTheDocument();
    expect(screen.getByText(/前序失败已阻断/)).toBeInTheDocument();
    expect(screen.getByText(/等待重试.*短信 provider 不可用/)).toBeInTheDocument();
    expect(screen.getByText(/已进入死信.*短信 provider 不可用/)).toBeInTheDocument();
    expect(screen.getByTestId('sla-action-retry-act-retry')).toHaveTextContent('重试 2/3');
    expect(screen.getByTestId('sla-action-retry-act-retry')).toHaveTextContent('上次 2026-07-05 09:32:05');
    expect(screen.getByTestId('sla-action-retry-act-retry')).toHaveTextContent('下次 2026-07-05 09:33:05');
    expect(screen.getByTestId('sla-action-retry-act-dead-letter')).toHaveTextContent('重试 3/3');
    expect(screen.getByTestId('sla-action-retry-act-dead-letter')).toHaveTextContent('死信 2026-07-05 09:34:11');
    expect(screen.getByTestId('sla-action-retry-act-dead-letter')).toHaveTextContent('重试已耗尽');
    expect(screen.getByTestId('sla-action-retry-act-im')).toHaveTextContent('尝试 1/3');
    expect(screen.getByTestId('sla-action-retry-act-im')).not.toHaveTextContent('重试 1/3');
    expect(screen.getByTestId('sla-action-retry-act-im')).not.toHaveTextContent('上次');
    expect(screen.getByTestId('sla-action-replay-act-missing')).toHaveTextContent('重放');
    expect(screen.getByTestId('sla-action-replay-act-1')).toHaveTextContent('重放');
    expect(screen.getByTestId('sla-action-replay-act-retry')).toHaveTextContent('重放');
    expect(screen.getByTestId('sla-action-replay-act-dead-letter')).toHaveTextContent('重放');
    expect(screen.queryByTestId('sla-action-replay-act-not-executed')).not.toBeInTheDocument();
    const retryTraceHref = screen.getByTestId('sla-action-trace-act-retry').getAttribute('href');
    expect(retryTraceHref).toBeTruthy();
    const retryTraceUrl = new URL(retryTraceHref!, 'http://localhost');
    expect(retryTraceUrl.pathname).toBe('/p/decisionops_execution_logs');
    expect(retryTraceUrl.searchParams.get('traceId')).toBe('trace-sla-retry-1');
    expect(retryTraceUrl.searchParams.get('policyCode')).toBe('SLA_TIMEOUT:sla-1');
    expect(retryTraceUrl.searchParams.get('correlationId')).toBe('corr-sla-retry-1');
    expect(retryTraceUrl.searchParams.get('callerType')).toBe('SLA');
    expect(retryTraceUrl.searchParams.get('callerRef')).toBe('sla-1');
    expect(screen.getByTestId('sla-action-trace-act-retry')).toHaveTextContent('统一 Trace');
    expect(screen.getAllByText(/短信 provider 不可用/).length).toBeGreaterThanOrEqual(3);
    expect(screen.getByText(/号码 \+8613800138000/)).toBeInTheDocument();
    expect(screen.getByText(/号码 \+8613800138002/)).toBeInTheDocument();
    expect(screen.getByText(/号码 \+8613800138003/)).toBeInTheDocument();
    expect(screen.getByText(/发送 1/)).toBeInTheDocument();
    expect(screen.getByText(/消息 1 条/)).toBeInTheDocument();
    expect(screen.getByText(/事件 sla\.timeout/)).toBeInTheDocument();
    expect(screen.getByText(/投递日志 1 条/)).toBeInTheDocument();
    expect(screen.getByText(/投递 failed/)).toBeInTheDocument();
    expect(screen.getByText(/审计 1 条/)).toBeInTheDocument();
    expect(screen.getByText(/评论 1 条/)).toBeInTheDocument();
    expect(screen.getByText(/内容 SLA 审计 REQ-SLA-5/)).toBeInTheDocument();
    expect(screen.getByText(/内容 SLA 评论 REQ-SLA-6/)).toBeInTheDocument();
    expect(screen.getByText(/创建任务 1/)).toBeInTheDocument();
    expect(screen.getByText(/抄送 1/)).toBeInTheDocument();
    expect(screen.getAllByText(/用户 1 人/).length).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByText(/待办 1 条/).length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText(/记录 REQ-SLA-1/)).toBeInTheDocument();
    expect(screen.getByText(/记录 REQ-SLA-2/)).toBeInTheDocument();
    expect(screen.getByText(/记录 REQ-SLA-3/)).toBeInTheDocument();
    expect(screen.getByText(/记录 REQ-SLA-4/)).toBeInTheDocument();
    expect(screen.getByText(/记录 REQ-SLA-6/)).toBeInTheDocument();
    expect(screen.getByText('命中')).toBeInTheDocument();
    expect(screen.queryByText('MATCHED')).not.toBeInTheDocument();
    expect(screen.getByTestId('sla-config-link-sla-1')).toHaveAttribute(
      'href',
      '/p/sla_config/view/sla-1',
    );
    expect(decisionLink).toHaveAttribute(
      'href',
      '/p/decisionops_definitions/view/complaint_sla_deadline',
    );
    expect(screen.getByRole('link', { name: /查看/ })).toHaveAttribute(
      'href',
      '/p/decisionops_execution_logs?callerType=SLA&callerRef=sla-1',
    );

    fireEvent.click(screen.getByTestId('sla-action-replay-act-dead-letter'));
    await waitFor(() => expect(mockReplaySlaActionLog).toHaveBeenCalledWith('act-dead-letter'));
    await waitFor(() => expect(mockListSlaActionLogs).toHaveBeenCalledTimes(2));
  });

  it('prioritizes the SLA strategy chain before summary stats', async () => {
    render(<SlaMonitorPanel />);

    await waitFor(() => expect(screen.getByTestId('sla-strategy-chain')).toBeInTheDocument());
    await waitFor(() =>
      expect(screen.getByTestId('sla-dashboard-process-definitions')).toBeInTheDocument(),
    );

    const strategyChain = screen.getByTestId('sla-strategy-chain');
    const processStats = screen.getByTestId('sla-dashboard-process-definitions');
    expect(strategyChain.compareDocumentPosition(processStats)).toBe(
      Node.DOCUMENT_POSITION_FOLLOWING,
    );
  });

  it('contains i18n seed labels for the SLA strategy chain', () => {
    const entries = JSON.parse(
      readFileSync('../plugins/platform-admin/config/i18n.json', 'utf8'),
    ) as Array<Record<string, string>>;
    const keys = new Set(entries.map((entry) => entry.key));

    for (const key of REQUIRED_SLA_MONITOR_I18N_KEYS) {
      expect(keys, `${key} is missing from platform-admin i18n seed`).toContain(key);
      const entry = entries.find((item) => item.key === key);
      expect(entry?.['zh-CN'], `${key} must have a zh-CN label`).toBeTruthy();
      expect(entry?.['en-US'], `${key} must have an en-US label`).toBeTruthy();
    }
  });
});
