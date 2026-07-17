import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';

const listExecutionTimelineMock = vi.fn();

vi.mock('~/plugins/core-bpm/services/bpmWorkbenchService', async () => {
  const actual = await vi.importActual<
    typeof import('~/plugins/core-bpm/services/bpmWorkbenchService')
  >('~/plugins/core-bpm/services/bpmWorkbenchService');
  return {
    ...actual,
    listExecutionTimeline: (...args: unknown[]) => listExecutionTimelineMock(...args),
  };
});

import { BpmRuleTraceSection } from '../BpmRuleTraceSection';
import type { BpmExecutionLogEntry } from '~/plugins/core-bpm/services/bpmWorkbenchService';

function buildEntry(overrides: Partial<BpmExecutionLogEntry> = {}): BpmExecutionLogEntry {
  return {
    pid: 'log-1',
    executionId: 'pi-rule-1',
    nodeId: 'task_manager_approve',
    nodeType: 'ruleBinding',
    eventType: 'rule_evaluated',
    inputData: null,
    outputData: {
      ruleBinding: {
        traceId: 'trace-bpm-1',
        consumerType: 'BPM',
        consumerCode: 'wd_leave_approval',
        consumerNodeId: 'task_manager_approve',
        bindingKind: 'DECISION',
        decisionCode: 'approval_routing',
        versionPolicy: 'LATEST_PUBLISHED',
        status: 'MATCHED',
        matched: true,
        inputs: { nodeId: 'task_manager_approve' },
        outputs: { reviewGroups: ['wd_manager'], primaryAssignee: 'u-manager' },
        durationMs: 12,
      },
    },
    errorMessage: null,
    durationMs: 12,
    createdAt: '2026-07-07T08:00:00Z',
    ...overrides,
  };
}

beforeEach(() => {
  listExecutionTimelineMock.mockReset();
});

afterEach(() => {
  document.body.innerHTML = '';
});

describe('BpmRuleTraceSection', () => {
  it('renders rule_evaluated entries as business trace cards', async () => {
    listExecutionTimelineMock.mockResolvedValue([
      buildEntry(),
      buildEntry({
        pid: 'log-node-start',
        nodeType: 'userTask',
        eventType: 'node_start',
        outputData: null,
      }),
    ]);

    render(<BpmRuleTraceSection processInstanceId="pi-rule-1" />);

    await waitFor(() => {
      expect(screen.getByTestId('bpm-rule-trace-panel')).toBeInTheDocument();
    });

    expect(listExecutionTimelineMock).toHaveBeenCalledWith('pi-rule-1');
    expect(screen.getByTestId('bpm-rule-trace-decision')).toHaveTextContent('approval_routing');
    expect(screen.getByTestId('bpm-rule-trace-status')).toHaveTextContent('已命中');
    expect(screen.getByTestId('bpm-rule-trace-output')).toHaveTextContent('审批组');
    expect(screen.getByTestId('bpm-rule-trace-output')).toHaveTextContent('wd_manager');
    expect(screen.getByTestId('bpm-rule-trace-id')).toHaveTextContent('trace-bpm-1');
    expect(screen.queryByTestId('bpm-rule-trace-item-log-node-start')).toBeNull();
  });

  it('renders an empty state when the execution has no rule trace rows', async () => {
    listExecutionTimelineMock.mockResolvedValue([
      buildEntry({ pid: 'log-plain', nodeType: 'userTask', eventType: 'node_complete', outputData: null }),
    ]);

    render(<BpmRuleTraceSection processInstanceId="pi-empty" />);

    await waitFor(() => {
      expect(screen.getByTestId('bpm-rule-trace-empty')).toBeInTheDocument();
    });
    expect(screen.getByTestId('bpm-rule-trace-empty')).toHaveTextContent('暂无规则或动作执行轨迹');
  });

  it('renders a stable error state when the timeline request fails', async () => {
    listExecutionTimelineMock.mockRejectedValue(new Error('timeline unavailable'));

    render(<BpmRuleTraceSection processInstanceId="pi-error" />);

    await waitFor(() => {
      expect(screen.getByTestId('bpm-rule-trace-error')).toBeInTheDocument();
    });
    expect(screen.getByTestId('bpm-rule-trace-error')).toHaveTextContent('timeline unavailable');
  });

  it('renders fail-closed rule traces without leaking raw backend error details', async () => {
    listExecutionTimelineMock.mockResolvedValue([
      buildEntry({
        pid: 'log-fail-closed',
        outputData: {
          ruleBinding: {
            traceId: 'trace-fail-closed-1',
            consumerType: 'BPM',
            consumerCode: 'wd_leave_approval',
            consumerNodeId: 'task_manager_approve',
            bindingKind: 'DECISION',
            decisionCode: 'approval_routing',
            versionPolicy: 'LATEST_PUBLISHED',
            status: 'ERROR',
            matched: false,
            inputs: { nodeId: 'task_manager_approve' },
            outputs: {},
            fallbackApplied: true,
            durationMs: 18,
            errorCode: 'DECISION_EVALUATION_FAILED',
            errors: ['adapter failed: missing field'],
          },
        },
      }),
    ]);

    render(<BpmRuleTraceSection processInstanceId="pi-fail-closed" />);

    await waitFor(() => {
      expect(screen.getByTestId('bpm-rule-trace-panel')).toBeInTheDocument();
    });

    expect(screen.getByTestId('bpm-rule-trace-status')).toHaveTextContent('失败关闭');
    expect(screen.getByTestId('bpm-rule-trace-fail-closed')).toHaveTextContent(
      '阻断候选审批人分配',
    );
    expect(screen.queryByText(/DECISION_EVALUATION_FAILED/)).not.toBeInTheDocument();
    expect(screen.queryByText(/adapter failed/)).not.toBeInTheDocument();
  });

  it('renders BPM action provider failures as productized trace cards without raw error leakage', async () => {
    listExecutionTimelineMock.mockResolvedValue([
      buildEntry({
        pid: 'log-action-fail',
        nodeId: 'sms_action',
        nodeType: null,
        eventType: 'node_failure',
        inputData: {
          action: {
            status: 'FAILED',
            actionType: 'SEND_SMS',
            channel: 'sms',
            failureReason: 'provider_unavailable',
            targetPhones: ['+8613800138000'],
            sentCount: 0,
            error: 'No real SMS sender available',
          },
          actionType: 'SEND_SMS',
          status: 'FAILED',
          resultVar: 'smsResult',
        },
        outputData: null,
        errorMessage: 'No real SMS sender available',
      }),
    ]);

    render(<BpmRuleTraceSection processInstanceId="pi-action-fail" />);

    await waitFor(() => {
      expect(screen.getByTestId('bpm-action-trace-item-sms_action')).toBeInTheDocument();
    });

    expect(screen.getByTestId('bpm-action-trace-title')).toHaveTextContent('发送短信');
    expect(screen.getByTestId('bpm-action-trace-status')).toHaveTextContent('动作失败');
    expect(screen.getByTestId('bpm-action-trace-summary')).toHaveTextContent('流程已失败关闭');
    const fieldText = screen
      .getAllByTestId('bpm-action-trace-field')
      .map((field: HTMLElement) => field.textContent ?? '')
      .join(' ');
    expect(fieldText).toContain('失败原因=真实短信 provider 不可用');
    expect(fieldText).toContain('通道=短信');
    expect(fieldText).toContain('目标手机号=+8613800138000');
    expect(fieldText).toContain('发送数量=0');
    expect(screen.queryByText(/provider_unavailable/)).not.toBeInTheDocument();
    expect(screen.queryByText(/No real SMS sender available/)).not.toBeInTheDocument();
  });

  it('renders BPM action success entries as productized trace cards', async () => {
    listExecutionTimelineMock.mockResolvedValue([
      buildEntry({
        pid: 'log-action-success',
        nodeId: 'send_im_action',
        nodeType: 'action',
        eventType: 'action_executed',
        inputData: {
          actionType: 'SEND_IM',
          status: 'SUCCESS',
          resultVar: 'imResult',
        },
        outputData: {
          action: {
            status: 'SUCCESS',
            actionType: 'SEND_IM',
            channel: 'im',
            sentCount: 1,
            targetUserIds: [1],
            conversationIds: [10],
            messageIds: [25],
          },
        },
        errorMessage: null,
      }),
    ]);

    render(<BpmRuleTraceSection processInstanceId="pi-action-success" />);

    await waitFor(() => {
      expect(screen.getByTestId('bpm-action-trace-item-send_im_action')).toBeInTheDocument();
    });

    expect(screen.getByTestId('bpm-action-trace-title')).toHaveTextContent('发送 IM 消息');
    expect(screen.getByTestId('bpm-action-trace-status')).toHaveTextContent('动作成功');
    expect(screen.getByTestId('bpm-action-trace-summary')).toHaveTextContent('流程已继续推进');
    const fieldText = screen
      .getAllByTestId('bpm-action-trace-field')
      .map((field: HTMLElement) => field.textContent ?? '')
      .join(' ');
    expect(fieldText).toContain('通道=IM');
    expect(fieldText).toContain('接收用户=1');
    expect(fieldText).toContain('发送数量=1');
    expect(fieldText).toContain('会话=10');
    expect(fieldText).toContain('消息=25');
    expect(screen.queryByText(/SEND_IM/)).not.toBeInTheDocument();
    expect(screen.queryByText(/SUCCESS/)).not.toBeInTheDocument();
  });

  it('renders modern message and task action failures without leaking raw failure codes', async () => {
    listExecutionTimelineMock.mockResolvedValue([
      buildEntry({
        pid: 'log-task-fail',
        nodeId: 'create_task_action',
        nodeType: null,
        eventType: 'node_failure',
        inputData: {
          action: {
            status: 'FAILED',
            actionType: 'CREATE_TASK',
            delivery: 'inbox',
            itemType: 'task',
            failureReason: 'target_invalid',
            targetType: 'USER',
            target: 'abc',
            invalidTarget: 'abc',
            field: 'payload.assignee',
            requiredContext: ['payload.assignee', 'action.target'],
            modelCode: 'wd_leave_request',
            recordPid: 'REQ-BPM-1',
          },
          actionType: 'CREATE_TASK',
          status: 'FAILED',
          resultVar: 'taskResult',
        },
        outputData: null,
        errorMessage: 'CREATE_TASK invalid target: abc',
      }),
      buildEntry({
        pid: 'log-notify-fail',
        nodeId: 'notify_action',
        nodeType: null,
        eventType: 'node_failure',
        inputData: {
          action: {
            status: 'FAILED',
            actionType: 'NOTIFY',
            channel: 'in_app',
            failureReason: 'notify_delivery_failed',
            targetType: 'ROLE',
            target: 'ROLE:finance',
            resolvedCount: 0,
          },
          actionType: 'NOTIFY',
          status: 'FAILED',
        },
        outputData: null,
        errorMessage: 'notification service unavailable',
      }),
    ]);

    render(<BpmRuleTraceSection processInstanceId="pi-modern-action-fail" />);

    await waitFor(() => {
      expect(screen.getByTestId('bpm-action-trace-item-create_task_action')).toBeInTheDocument();
    });

    const fieldText = screen
      .getAllByTestId('bpm-action-trace-field')
      .map((field: HTMLElement) => field.textContent ?? '')
      .join(' ');
    expect(screen.getAllByTestId('bpm-action-trace-title')[0]).toHaveTextContent('创建任务');
    expect(screen.getAllByTestId('bpm-action-trace-title')[1]).toHaveTextContent('发送通知');
    expect(fieldText).toContain('失败原因=目标格式无效');
    expect(fieldText).toContain('失败原因=通知发送失败');
    expect(fieldText).toContain('投递方式=待办');
    expect(fieldText).toContain('待办类型=待办任务');
    expect(fieldText).toContain('接收类型=用户');
    expect(fieldText).toContain('接收类型=角色');
    expect(fieldText).toContain('字段=负责人');
    expect(fieldText).toContain('必需上下文=负责人, 动作目标');
    expect(fieldText).toContain('模型=wd_leave_request');
    expect(fieldText).toContain('业务记录=REQ-BPM-1');
    expect(fieldText).toContain('解析数量=0');
    expect(screen.queryByText(/target_invalid/)).not.toBeInTheDocument();
    expect(screen.queryByText(/notify_delivery_failed/)).not.toBeInTheDocument();
    expect(screen.queryByText(/payload\.assignee/)).not.toBeInTheDocument();
    expect(screen.queryByText(/CREATE_TASK invalid target/)).not.toBeInTheDocument();
    expect(screen.queryByText(/notification service unavailable/)).not.toBeInTheDocument();
  });
});
