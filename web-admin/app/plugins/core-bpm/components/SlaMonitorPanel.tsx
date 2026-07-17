/**
 * SLA Monitor Panel
 * Dashboard for monitoring SLA status and active records.
 * Supports drill-down: click a StatCard to expand the SlaRecordListPanel filtered by status.
 */

import { useState, useEffect, useCallback } from 'react';
import { Button } from '~/ui/ui/button';
import { useSmartText } from '~/utils/i18n';
import { useToastContext } from '~/contexts/ToastContext';
import {
  RefreshCw,
  Activity,
  AlertTriangle,
  Clock,
  PauseCircle,
  XCircle,
  BarChart3,
  Bell,
  ExternalLink,
  GitBranch,
  ListChecks,
} from 'lucide-react';
import type {
  DashboardData,
  RuleInputMapping,
  RuleOutputMapping,
  RuleValueRef,
  SlaAction,
  SlaActionLog,
  SlaConfig,
  SlaDecisionLog,
} from '../services/slaService';
import * as slaService from '../services/slaService';
import { SlaRecordListPanel } from './SlaRecordListPanel';

// ==================== Stat Card Component ====================

function StatCard({
  title,
  value,
  icon: Icon,
  color,
  onClick,
  active,
  testId,
}: {
  title: string;
  value: number;
  icon: React.ElementType;
  color: string;
  onClick?: () => void;
  active?: boolean;
  testId?: string;
}) {
  const colorClasses: Record<string, string> = {
    blue: 'bg-blue-50 text-blue-700 border-blue-200',
    green: 'bg-green-50 text-green-700 border-green-200',
    yellow: 'bg-yellow-50 text-yellow-700 border-yellow-200',
    red: 'bg-red-50 text-red-700 border-red-200',
    gray: 'bg-gray-50 text-gray-700 border-gray-200',
    purple: 'bg-purple-50 text-purple-700 border-purple-200',
  };

  return (
    <div
      className={`rounded-lg border p-4 transition-all ${colorClasses[color] || colorClasses.gray} ${
        onClick ? 'cursor-pointer hover:scale-[1.02] hover:shadow-md' : ''
      } ${active ? 'shadow-md ring-2 ring-blue-400' : ''}`}
      onClick={onClick}
      role={onClick ? 'button' : undefined}
      tabIndex={onClick ? 0 : undefined}
      onKeyDown={
        onClick
          ? (e) => {
              if (e.key === 'Enter' || e.key === ' ') onClick();
            }
          : undefined
      }
      data-testid={testId}
    >
      <div className="flex items-center justify-between">
        <div>
          <p className="text-sm font-medium opacity-80">{title}</p>
          <p className="mt-1 text-2xl font-bold">{value}</p>
        </div>
        <Icon className="h-8 w-8 opacity-60" />
      </div>
    </div>
  );
}

const ACTION_LABELS: Record<string, string> = {
  NOTIFY: '站内通知',
  SEND_SMS: '短信',
  SEND_IM: '发送 IM',
  WEBHOOK: 'Webhook',
  START_PROCESS: '启动流程',
  CREATE_TASK: '创建任务',
  CC_TASK: '抄送任务',
  ADD_COMMENT: '评论',
  UPDATE_RECORD: '更新记录',
  WRITE_AUDIT: '审计',
};

const TARGET_TYPE_LABELS: Record<string, string> = {
  PROCESS: '流程',
  NODE: '节点',
  TASK: '任务',
  RECORD: '记录',
};

const TARGET_KEY_LABELS: Record<string, string> = {
  task_manager_approve: '主管审批节点',
  task_hr_approve: 'HR 审批节点',
};

const TRIGGER_LABELS: Record<string, string> = {
  SLA_TIMEOUT: 'SLA 超时',
  SLA_WARNING: 'SLA 预警',
};

const FAILURE_STRATEGY_LABELS: Record<string, string> = {
  CONTINUE_ON_ERROR: '失败后继续',
  FAIL_FAST: '失败即停止',
  ALL_OR_NOTHING: '全部成功才提交',
  RETRY_ASYNC: '异步重试',
  DEAD_LETTER: '进入死信',
};

const LOG_STATUS_LABELS: Record<string, string> = {
  MATCHED: '命中',
  NOT_MATCHED: '未命中',
  SUCCESS: '成功',
  FAILED: '失败',
  NO_HANDLER: '处理器缺失',
  RETRY_PENDING: '等待重试',
  DEAD_LETTER: '进入死信',
  NOT_EXECUTED: '未执行',
  ERROR: '错误',
  SKIPPED: '跳过',
  UNKNOWN: '未知',
};

const DECISION_LABELS: Record<string, string> = {
  complaint_sla_deadline: '请假审批 SLA 截止时间',
  approval_routing: '请假审批分派',
  leave_request_automation: '请假申请自动化策略',
};

const SLA_FIELD_LABELS: Record<string, string> = {
  targetKey: 'SLA 节点',
  'data.targetKey': 'SLA 节点',
  deadlineMinutes: '截止分钟数',
  warningBeforeMinutes: '预警提前分钟数',
};

const VALUE_SCOPE_LABELS: Record<string, string> = {
  record: '当前记录',
  process: '流程',
  sla: 'SLA',
  decision: '规则输出',
};

function decisionCodeOf(config: SlaConfig): string {
  return config.ruleBinding?.decisionBinding?.decisionCode || '-';
}

function decisionLabelOf(config: SlaConfig): string {
  const binding = config.ruleBinding?.decisionBinding;
  const code = decisionCodeOf(config);
  const explicitLabel = binding?.decisionName || binding?.name || binding?.label;
  if (explicitLabel?.trim()) return explicitLabel.trim();
  return DECISION_LABELS[code] ?? code;
}

function ruleValueLabel(ref?: RuleValueRef, target = false): string {
  if (!ref?.path) return '-';
  const fieldLabel = slaFieldLabel(ref.path);
  if (target && String(ref.kind || '').toUpperCase() === 'SLA_FIELD') {
    return `SLA.${fieldLabel}`;
  }
  const scopeLabel = ref.scope ? VALUE_SCOPE_LABELS[String(ref.scope).toLowerCase()] : '';
  return scopeLabel ? `${scopeLabel}.${fieldLabel}` : fieldLabel;
}

function inputMappingLabel(mapping: RuleInputMapping): string {
  return `${slaFieldLabel(mapping.input)} ← ${ruleValueLabel(mapping.source)}`;
}

function outputMappingLabel(mapping: RuleOutputMapping): string {
  return `${slaFieldLabel(mapping.output)} → ${ruleValueLabel(mapping.target, true)}`;
}

function slaFieldLabel(path?: string): string {
  if (!path) return '-';
  const normalized = path.trim();
  const tail = normalized.split('.').filter(Boolean).at(-1) ?? normalized;
  return SLA_FIELD_LABELS[normalized] ?? SLA_FIELD_LABELS[tail] ?? normalized.replace(/^data\./, '');
}

function actionLabel(action: SlaAction): string {
  const type = action.type || '-';
  return ACTION_LABELS[type] || type;
}

function actionTitle(action: SlaAction): string {
  const payload = action.payload ?? {};
  const title = payload.title ?? payload.taskTitle ?? payload.content ?? payload.message;
  return typeof title === 'string' && title.trim() ? title : action.target || '-';
}

function targetTypeLabel(value?: string): string {
  if (!value) return '-';
  const normalized = value.trim().toUpperCase();
  return TARGET_TYPE_LABELS[normalized] ?? value;
}

function targetKeyLabel(config: SlaConfig): string {
  const explicitLabel = config.targetLabel?.trim();
  if (explicitLabel) return explicitLabel;
  const key = config.targetKey?.trim();
  if (!key) return '-';
  return TARGET_KEY_LABELS[key] ?? key;
}

function targetKeyTitle(config: SlaConfig): string | undefined {
  const key = config.targetKey?.trim();
  const label = targetKeyLabel(config);
  if (!key || key === label) return undefined;
  return key;
}

function triggerLabel(value?: string): string {
  if (!value) return 'SLA 超时';
  const normalized = value.trim().toUpperCase();
  return TRIGGER_LABELS[normalized] ?? value;
}

function failureStrategyLabel(value?: string): string {
  if (!value) return FAILURE_STRATEGY_LABELS.CONTINUE_ON_ERROR;
  const normalized = value.trim().toUpperCase();
  return FAILURE_STRATEGY_LABELS[normalized] ?? value;
}

function logStatusLabel(value?: string): string {
  if (!value) return '-';
  const normalized = value.trim().toUpperCase();
  return LOG_STATUS_LABELS[normalized] ?? value;
}

function logRowsForConfig(logs: SlaDecisionLog[], config: SlaConfig): SlaDecisionLog[] {
  const decisionCode = decisionCodeOf(config);
  return logs
    .filter((log) => log.callerRef === config.pid || log.decisionCode === decisionCode)
    .slice(0, 3);
}

function actionLogsForConfig(logs: SlaActionLog[] = [], config: SlaConfig): SlaActionLog[] {
  const policyCode = `SLA_TIMEOUT:${config.pid}`;
  return logs.filter((log) => log.policyCode === policyCode).slice(0, 12);
}

function logUrl(config: SlaConfig): string {
  return `/p/decisionops_execution_logs?callerType=SLA&callerRef=${encodeURIComponent(config.pid)}`;
}

function actionLogTraceUrl(log: SlaActionLog): string {
  const params = new URLSearchParams();
  const policyCode = log.policyCode?.trim();
  if (log.decisionTraceId) params.set('traceId', log.decisionTraceId);
  if (policyCode) params.set('policyCode', policyCode);
  if (log.correlationId) params.set('correlationId', log.correlationId);
  const slaCallerRef = policyCode?.match(/^SLA_TIMEOUT:(.+)$/)?.[1];
  if (slaCallerRef) {
    params.set('callerType', 'SLA');
    params.set('callerRef', slaCallerRef);
  }
  if (!params.has('traceId') && !params.has('policyCode') && log.idempotencyKey) {
    params.set('keyword', log.idempotencyKey);
  }
  const query = params.toString();
  return query ? `/p/decisionops_execution_logs?${query}` : '/p/decisionops_execution_logs';
}

function decisionDefinitionUrl(decisionCode: string): string {
  return `/p/decisionops_definitions/view/${encodeURIComponent(decisionCode)}`;
}

function slaConfigUrl(config: SlaConfig): string {
  return `/p/sla_config/view/${encodeURIComponent(config.pid)}`;
}

function formatLogTime(value?: string): string {
  if (!value) return '-';
  return value.replace('T', ' ').replace(/\.\d+Z?$/, '').replace(/Z$/, '');
}

function actionErrorLabel(error?: string): string {
  if (!error) return '';
  if (/No real SMS sender available/i.test(error)) return '短信 provider 不可用';
  if (/no handler for action type/i.test(error)) return '动作处理器不可用';
  return error;
}

function actionLogSummary(log: SlaActionLog): string {
  const payload = log.resultPayload ?? {};
  const parts: string[] = [];
  const status = log.status?.trim().toUpperCase();
  const sentCount = payload.sentCount ?? payload.recipientCount;
  const createdCount = payload.createdCount;
  const ccCount = payload.ccCount;
  const provider = payload.provider ?? (Array.isArray(payload.providers) ? payload.providers[0] : undefined);
  const phones = Array.isArray(payload.targetPhones) ? payload.targetPhones.join(', ') : undefined;
  const userCount = Array.isArray(payload.targetUserIds)
    ? payload.targetUserIds.length
    : Array.isArray(payload.assigneeUserIds)
      ? payload.assigneeUserIds.length
      : undefined;
  const inboxCount = Array.isArray(payload.inboxItemIds) ? payload.inboxItemIds.length : undefined;
  const messageCount = Array.isArray(payload.messageIds) ? payload.messageIds.length : undefined;
  const auditCount = payload.auditPid ? 1 : undefined;
  const commentCount = payload.commentPid ? 1 : undefined;
  const deliveryLogCount = Array.isArray(payload.deliveryLogPids) ? payload.deliveryLogPids.length : undefined;
  const deliveryStatuses = Array.isArray(payload.deliveryReceipts)
    ? payload.deliveryReceipts
        .map((receipt) => {
          if (!receipt || typeof receipt !== 'object') return undefined;
          return String((receipt as Record<string, unknown>).deliveryStatus ?? '').trim();
        })
        .filter(Boolean)
    : [];
  const eventType = typeof payload.eventType === 'string' ? payload.eventType : undefined;
  const recordPid = typeof payload.recordPid === 'string' ? payload.recordPid : undefined;
  if (sentCount !== undefined) parts.push(`发送 ${sentCount}`);
  if (createdCount !== undefined) parts.push(`创建任务 ${String(createdCount)}`);
  if (ccCount !== undefined) parts.push(`抄送 ${String(ccCount)}`);
  if (auditCount !== undefined) parts.push(`审计 ${auditCount} 条`);
  if (commentCount !== undefined) parts.push(`评论 ${commentCount} 条`);
  if (eventType) parts.push(`事件 ${eventType}`);
  if (deliveryLogCount !== undefined) parts.push(`投递日志 ${deliveryLogCount} 条`);
  if (deliveryStatuses.length) parts.push(`投递 ${deliveryStatuses.join(', ')}`);
  if (provider) parts.push(`provider ${String(provider)}`);
  if (phones) parts.push(`号码 ${phones}`);
  if (userCount !== undefined) parts.push(`用户 ${userCount} 人`);
  if (inboxCount !== undefined) parts.push(`待办 ${inboxCount} 条`);
  if (messageCount !== undefined) parts.push(`消息 ${messageCount} 条`);
  if (recordPid) parts.push(`记录 ${recordPid}`);
  if (typeof payload.message === 'string' && payload.message.trim()) {
    parts.push(`内容 ${payload.message.trim()}`);
  } else if (typeof payload.content === 'string' && payload.content.trim()) {
    parts.push(`内容 ${payload.content.trim()}`);
  }
  if (status === 'NOT_EXECUTED') parts.push('前序失败已阻断');
  if (status === 'RETRY_PENDING') parts.push('等待重试');
  if (status === 'DEAD_LETTER') parts.push('已进入死信');
  const error = actionErrorLabel(log.errorMessage);
  if (error) parts.push(error);
  return parts.join(' · ') || formatLogTime(log.executedAt);
}

function retryTimelineItems(log: SlaActionLog): string[] {
  const parts: string[] = [];
  const attempt = Number(log.attemptCount ?? 0);
  const maxAttempts = Number(log.maxAttempts ?? 0);
  const status = String(log.status ?? '').trim().toUpperCase();
  const retryState = ['FAILED', 'NO_HANDLER', 'RETRY_PENDING', 'DEAD_LETTER'].includes(status)
    || Boolean(log.nextRetryAt || log.deadLetteredAt || log.resultPayload?.retryExhausted);
  const attemptLabel = retryState ? '重试' : '尝试';
  if (attempt > 0 && maxAttempts > 0) {
    parts.push(`${attemptLabel} ${attempt}/${maxAttempts}`);
  } else if (attempt > 0) {
    parts.push(`${attemptLabel} ${attempt}`);
  }
  if (retryState) {
    if (log.lastRetryAt) parts.push(`上次 ${formatLogTime(log.lastRetryAt)}`);
    if (log.nextRetryAt) parts.push(`下次 ${formatLogTime(log.nextRetryAt)}`);
    if (log.deadLetteredAt) parts.push(`死信 ${formatLogTime(log.deadLetteredAt)}`);
    if (log.resultPayload?.retryExhausted === true) parts.push('重试已耗尽');
  }
  return parts;
}

function isReplayableActionLog(log: SlaActionLog): boolean {
  if (!log.pid) return false;
  const status = String(log.status ?? '').trim().toUpperCase();
  return ['DEAD_LETTER', 'RETRY_PENDING', 'FAILED', 'NO_HANDLER'].includes(status);
}

function SlaStrategyChainSection({
  configs,
  logs,
  actionLogs,
  loading,
  error,
  st,
  replayingActionLogPid,
  onReplayActionLog,
}: {
  configs: SlaConfig[];
  logs: SlaDecisionLog[];
  actionLogs: SlaActionLog[];
  loading: boolean;
  error: string;
  st: ReturnType<typeof useSmartText>;
  replayingActionLogPid: string | null;
  onReplayActionLog: (log: SlaActionLog) => void;
}) {
  return (
    <section className="space-y-3" data-testid="sla-strategy-chain">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h2 className="text-lg font-semibold">
            {st('$i18n:bpm.sla.monitor.strategyChain', 'SLA 策略链路')}
          </h2>
          <p className="text-muted-foreground text-sm">
            {st(
              '$i18n:bpm.sla.monitor.strategyChain.subtitle',
              '规则绑定、输入输出映射、超时动作和执行日志',
            )}
          </p>
        </div>
        <span className="rounded-full border border-gray-200 bg-gray-50 px-3 py-1 text-xs text-gray-600">
          {configs.length} {st('$i18n:bpm.sla.monitor.strategyChain.configs', '条 SLA')}
        </span>
      </div>

      {loading ? (
        <div className="text-muted-foreground rounded-lg border bg-white py-8 text-center text-sm">
          {st('$i18n:common.loading', '加载中...')}
        </div>
      ) : configs.length === 0 ? (
        <div className="text-muted-foreground rounded-lg border bg-white py-8 text-center text-sm">
          {st('$i18n:bpm.sla.monitor.strategyChain.empty', '暂无已启用的 SLA 策略')}
        </div>
      ) : (
        <div className="grid gap-4 xl:grid-cols-2">
          {configs.map((config) => {
            const binding = config.ruleBinding?.decisionBinding;
            const decisionCode = decisionCodeOf(config);
            const decisionLabel = decisionLabelOf(config);
            const decisionTitle = decisionLabel === decisionCode
              ? decisionCode
              : `${decisionLabel} (${decisionCode})`;
            const inputMappings = binding?.inputMappings ?? [];
            const outputMappings = binding?.outputMappings ?? [];
            const actions = config.actionPolicy?.actions ?? [];
            const matchedLogs = logRowsForConfig(logs, config);
            const matchedActionLogs = actionLogsForConfig(actionLogs, config);
            return (
              <article key={config.pid} className="rounded-lg border bg-white p-4 shadow-sm">
                <div className="flex items-start justify-between gap-4">
                  <div className="min-w-0">
                    <h3 className="truncate text-base font-semibold text-gray-950">
                      <a
                        className="inline-flex max-w-full items-center gap-1 truncate hover:text-blue-700"
                        data-testid={`sla-config-link-${config.pid}`}
                        href={slaConfigUrl(config)}
                        title={config.name}
                      >
                        <span className="truncate">{config.name}</span>
                        <ExternalLink className="h-3 w-3 shrink-0 text-gray-400" />
                      </a>
                    </h3>
                    <p className="mt-1 text-xs text-gray-500" title={targetKeyTitle(config)}>
                      {targetTypeLabel(config.targetType)} / {targetKeyLabel(config)}
                    </p>
                  </div>
                  <span
                    className={`rounded-full px-2 py-0.5 text-xs font-medium ${
                      config.enabled
                        ? 'bg-green-50 text-green-700'
                        : 'bg-gray-100 text-gray-500'
                    }`}
                  >
                    {config.enabled
                      ? st('$i18n:common.enabled', '启用')
                      : st('$i18n:common.disabled', '停用')}
                  </span>
                </div>

                <div className="mt-4 grid gap-4 border-t pt-4 md:grid-cols-[1.2fr_1fr]">
                  <div className="space-y-3">
                    <div className="flex items-center gap-2 text-sm font-medium text-gray-900">
                      <GitBranch className="h-4 w-4 text-blue-600" />
                      {decisionCode === '-' ? (
                        <code className="rounded bg-blue-50 px-2 py-0.5 text-blue-700">-</code>
                      ) : (
                        <a
                          className="inline-grid min-w-0 grid-cols-[minmax(0,1fr)_auto] items-center gap-x-1 gap-y-0.5 rounded bg-blue-50 px-2 py-1 text-blue-700 hover:bg-blue-100 hover:text-blue-800"
                          data-testid={`sla-decision-link-${decisionCode}`}
                          href={decisionDefinitionUrl(decisionCode)}
                          title={decisionTitle}
                        >
                          <span className="truncate">{decisionLabel}</span>
                          <ExternalLink className="h-3 w-3 shrink-0" />
                        </a>
                      )}
                    </div>
                    <div className="space-y-1 text-xs text-gray-600">
                      {inputMappings.length ? (
                        inputMappings.map((mapping, index) => (
                          <div key={`in-${index}`}>{inputMappingLabel(mapping)}</div>
                        ))
                      ) : (
                        <div>{st('$i18n:bpm.sla.monitor.strategyChain.noInput', '未配置输入映射')}</div>
                      )}
                      {outputMappings.length ? (
                        outputMappings.map((mapping, index) => (
                          <div key={`out-${index}`}>{outputMappingLabel(mapping)}</div>
                        ))
                      ) : (
                        <div>{st('$i18n:bpm.sla.monitor.strategyChain.noOutput', '未配置输出映射')}</div>
                      )}
                    </div>
                  </div>

                  <div className="space-y-2">
                    <div className="flex items-center gap-2 text-sm font-medium text-gray-900">
                      <Bell className="h-4 w-4 text-amber-600" />
                      {triggerLabel(config.actionPolicy?.trigger)}
                    </div>
                    <div
                      className="inline-flex w-fit items-center gap-1 rounded-full border border-amber-200 bg-amber-50 px-2 py-0.5 text-xs font-medium text-amber-800"
                      data-testid={`sla-failure-strategy-${config.pid}`}
                    >
                      <span>失败策略</span>
                      <strong>{failureStrategyLabel(config.actionPolicy?.failureStrategy)}</strong>
                    </div>
                    <div className="space-y-1 text-xs text-gray-600">
                      {actions.length ? (
                        actions.slice(0, 4).map((action, index) => (
                          <div className="flex items-center justify-between gap-2" key={index}>
                            <span>{actionLabel(action)}</span>
                            <span className="truncate text-gray-500">{actionTitle(action)}</span>
                          </div>
                        ))
                      ) : (
                        <div>{st('$i18n:bpm.sla.monitor.strategyChain.noActions', '未配置动作')}</div>
                      )}
                    </div>
                    <div className="mt-3 rounded-md border border-amber-100 bg-amber-50/60 p-2">
                      <div className="mb-1 text-xs font-medium text-amber-900">
                        {st('$i18n:bpm.sla.monitor.strategyChain.actionEvidence', '动作执行证据')}
                      </div>
                      {matchedActionLogs.length ? (
                        <div className="space-y-1">
                          {matchedActionLogs.map((log) => {
                            const actionLogKey =
                              log.pid ?? log.idempotencyKey ?? `${log.policyCode}-${log.actionType}`;
                            const retryItems = retryTimelineItems(log);
                            return (
                              <div
                                key={actionLogKey}
                                className="rounded border border-amber-100 bg-white/70 px-2 py-1 text-xs text-amber-950"
                                data-testid={`sla-action-log-${actionLogKey}`}
                              >
                                <div className="grid grid-cols-[auto_auto_minmax(0,1fr)] items-center gap-2">
                                  <span>{actionLabel({ type: log.actionType })}</span>
                                  <span className="rounded bg-white px-1.5 py-0.5 font-medium">
                                    {logStatusLabel(log.status)}
                                  </span>
                                  <span className="truncate text-amber-800" title={actionLogSummary(log)}>
                                    {actionLogSummary(log)}
                                  </span>
                                </div>
                                {retryItems.length ? (
                                  <div
                                    className="mt-1 flex flex-wrap gap-1 text-[11px] text-amber-700"
                                    data-testid={`sla-action-retry-${actionLogKey}`}
                                  >
                                    {retryItems.map((item) => (
                                      <span
                                        key={item}
                                        className="rounded border border-amber-200 bg-amber-50 px-1.5 py-0.5"
                                      >
                                        {item}
                                      </span>
                                    ))}
                                  </div>
                                ) : null}
                                <div className="mt-1 flex flex-wrap items-center gap-1">
                                  {isReplayableActionLog(log) ? (
                                    <Button
                                      type="button"
                                      variant="ghost"
                                      size="sm"
                                      className="h-7 border border-amber-200 bg-white px-2 text-[11px] font-medium text-amber-800 hover:bg-amber-50"
                                      disabled={replayingActionLogPid === log.pid}
                                      data-testid={`sla-action-replay-${actionLogKey}`}
                                      onClick={() => onReplayActionLog(log)}
                                    >
                                      <RefreshCw
                                        className={`mr-1 h-3 w-3 ${
                                          replayingActionLogPid === log.pid ? 'animate-spin' : ''
                                        }`}
                                      />
                                      {replayingActionLogPid === log.pid
                                        ? st('$i18n:bpm.sla.monitor.strategyChain.replaying', '重放中')
                                        : st('$i18n:bpm.sla.monitor.strategyChain.replay', '重放')}
                                    </Button>
                                  ) : null}
                                  <a
                                    className="inline-flex h-7 items-center gap-1 rounded border border-amber-200 bg-white px-2 text-[11px] font-medium text-amber-800 hover:bg-amber-50"
                                    href={actionLogTraceUrl(log)}
                                    data-testid={`sla-action-trace-${actionLogKey}`}
                                  >
                                    <ExternalLink className="h-3 w-3" />
                                    {st('$i18n:bpm.sla.monitor.strategyChain.trace', '统一 Trace')}
                                  </a>
                                </div>
                              </div>
                            );
                          })}
                        </div>
                      ) : (
                        <div className="text-xs text-amber-800">
                          {st('$i18n:bpm.sla.monitor.strategyChain.noActionEvidence', '暂无动作执行证据')}
                        </div>
                      )}
                    </div>
                  </div>
                </div>

                <div className="mt-4 border-t pt-3">
                  <div className="mb-2 flex items-center justify-between gap-3">
                    <div className="flex items-center gap-2 text-sm font-medium text-gray-900">
                      <ListChecks className="h-4 w-4 text-gray-600" />
                      {st('$i18n:bpm.sla.monitor.strategyChain.logs', '最近日志')}
                    </div>
                    <a
                      className="inline-flex items-center gap-1 text-xs font-medium text-blue-600 hover:text-blue-700"
                      href={logUrl(config)}
                    >
                      {st('$i18n:common.view', '查看')}
                      <ExternalLink className="h-3 w-3" />
                    </a>
                  </div>
                  {error && (
                    <div className="mb-2 rounded border border-amber-200 bg-amber-50 px-2 py-1 text-xs text-amber-700">
                      {error}
                    </div>
                  )}
                  {matchedLogs.length ? (
                    <div className="space-y-1 text-xs text-gray-600">
                      {matchedLogs.map((log) => (
                        <div className="flex items-center justify-between gap-3" key={log.pid ?? log.traceId}>
                          <span className="font-medium text-gray-800" title={log.status || '-'}>
                            {logStatusLabel(log.status)}
                          </span>
                          <span>{formatLogTime(log.createdAt)}</span>
                        </div>
                      ))}
                    </div>
                  ) : (
                    <div className="text-xs text-gray-500">
                      {st('$i18n:bpm.sla.monitor.strategyChain.noLogs', '暂无执行日志')}
                    </div>
                  )}
                </div>
              </article>
            );
          })}
        </div>
      )}
    </section>
  );
}

// ==================== Main Component ====================

export function SlaMonitorPanel() {
  const st = useSmartText();
  const { showErrorToast } = useToastContext();
  const [dashboard, setDashboard] = useState<DashboardData | null>(null);
  const [slaConfigs, setSlaConfigs] = useState<SlaConfig[]>([]);
  const [slaDecisionLogs, setSlaDecisionLogs] = useState<SlaDecisionLog[]>([]);
  const [slaActionLogs, setSlaActionLogs] = useState<SlaActionLog[]>([]);
  const [loading, setLoading] = useState(true);
  const [strategyLoading, setStrategyLoading] = useState(true);
  const [strategyError, setStrategyError] = useState('');
  const [drillFilter, setDrillFilter] = useState<string | null>(null);
  const [replayingActionLogPid, setReplayingActionLogPid] = useState<string | null>(null);
  const loadErrorMessage =
    st('$i18n:bpm.sla.monitor.loadError') || 'Failed to load SLA monitor data';
  const strategyLoadErrorMessage = st(
    '$i18n:bpm.sla.monitor.strategyChain.loadError',
    '策略链路数据加载不完整',
  );

  const loadDashboard = useCallback(async () => {
    setLoading(true);
    try {
      const data = await slaService.getDashboard();
      setDashboard(data);
    } catch {
      showErrorToast(loadErrorMessage);
    } finally {
      setLoading(false);
    }
  }, [loadErrorMessage, showErrorToast]);

  const loadStrategyChain = useCallback(async () => {
    setStrategyLoading(true);
    setStrategyError('');
    const [configsResult, logsResult, actionLogsResult] = await Promise.allSettled([
      slaService.listSlaConfigs(),
      slaService.listSlaDecisionLogs({ callerType: 'SLA', size: 20 }),
      slaService.listSlaActionLogs({ policyCodePrefix: 'SLA_TIMEOUT:', size: 50 }),
    ]);
    if (configsResult.status === 'fulfilled') {
      setSlaConfigs(configsResult.value);
    } else {
      setSlaConfigs([]);
      setStrategyError(strategyLoadErrorMessage);
      showErrorToast(strategyLoadErrorMessage);
    }
    if (logsResult.status === 'fulfilled') {
      setSlaDecisionLogs(logsResult.value);
    } else {
      setSlaDecisionLogs([]);
      setStrategyError(strategyLoadErrorMessage);
    }
    if (actionLogsResult.status === 'fulfilled') {
      setSlaActionLogs(actionLogsResult.value);
    } else {
      setSlaActionLogs([]);
      setStrategyError(strategyLoadErrorMessage);
    }
    setStrategyLoading(false);
  }, [showErrorToast, strategyLoadErrorMessage]);

  useEffect(() => {
    loadDashboard();
  }, [loadDashboard]);

  useEffect(() => {
    loadStrategyChain();
  }, [loadStrategyChain]);

  const handleRefresh = () => {
    loadDashboard();
    loadStrategyChain();
  };

  const handleReplayActionLog = useCallback(
    async (log: SlaActionLog) => {
      if (!log.pid) return;
      setReplayingActionLogPid(log.pid);
      try {
        await slaService.replaySlaActionLog(log.pid);
        await loadStrategyChain();
      } catch (error) {
        const detail = error instanceof Error ? error.message : String(error);
        showErrorToast(`${st('$i18n:bpm.sla.monitor.strategyChain.replayFailed', '动作重放失败')}: ${detail}`);
      } finally {
        setReplayingActionLogPid(null);
      }
    },
    [loadStrategyChain, showErrorToast, st],
  );

  const handleCardClick = (status: string) => {
    setDrillFilter((prev) => (prev === status ? null : status));
  };

  return (
    <div className="space-y-5 p-4 sm:p-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="flex items-center gap-2 text-2xl font-bold">
            <BarChart3 className="h-6 w-6" />
            {st('$i18n:bpm.sla.monitor.title') || 'SLA Monitor'}
          </h1>
          <p className="text-muted-foreground mt-1 text-sm">
            {st('$i18n:bpm.sla.monitor.subtitle') || 'Real-time SLA status overview'}
          </p>
        </div>
        <Button variant="outline" size="sm" onClick={handleRefresh} data-testid="sla-refresh">
          <RefreshCw className="mr-1 h-4 w-4" />
          {st('$i18n:bpm.sla.monitor.refresh') || 'Refresh'}
        </Button>
      </div>

      {loading ? (
        <div className="text-muted-foreground py-12 text-center">
          {st('$i18n:common.loading') || 'Loading...'}
        </div>
      ) : dashboard ? (
        <>
          <SlaStrategyChainSection
            configs={slaConfigs}
            logs={slaDecisionLogs}
            actionLogs={slaActionLogs}
            loading={strategyLoading}
            error={strategyError}
            st={st}
            replayingActionLogPid={replayingActionLogPid}
            onReplayActionLog={handleReplayActionLog}
          />

          {/* Process Definition Stats */}
          <div data-testid="sla-dashboard-process-definitions">
            <h2 className="mb-3 text-lg font-semibold">
              {st('$i18n:bpm.sla.monitor.processDefinitions') || 'Process Definitions'}
            </h2>
            <div className="grid grid-cols-2 gap-4 md:grid-cols-4">
              <StatCard
                title={st('$i18n:bpm.sla.monitor.total') || 'Total'}
                value={dashboard.processDefinitions.total}
                icon={BarChart3}
                color="blue"
              />
              <StatCard
                title={st('$i18n:bpm.sla.monitor.draft') || 'Draft'}
                value={dashboard.processDefinitions.draft}
                icon={Clock}
                color="gray"
              />
              <StatCard
                title={st('$i18n:bpm.sla.monitor.deployed') || 'Deployed'}
                value={dashboard.processDefinitions.deployed}
                icon={Activity}
                color="green"
              />
              <StatCard
                title={st('$i18n:bpm.sla.monitor.suspended') || 'Suspended'}
                value={dashboard.processDefinitions.suspended}
                icon={PauseCircle}
                color="yellow"
              />
            </div>
          </div>

          {/* SLA Record Stats — clickable for drill-down */}
          <div data-testid="sla-dashboard-active-records">
            <h2 className="mb-3 text-lg font-semibold">
              {st('$i18n:bpm.sla.monitor.activeRecords') || 'Active SLA Records'}
            </h2>
            <div className="grid grid-cols-2 gap-4 md:grid-cols-5">
              <StatCard
                title={st('$i18n:bpm.sla.monitor.active') || 'Active'}
                value={dashboard.sla.active}
                icon={Activity}
                color="blue"
                onClick={() => handleCardClick('all')}
                active={drillFilter === 'all'}
                testId="sla-stat-ALL"
              />
              <StatCard
                title={st('$i18n:bpm.sla.monitor.running') || 'Running'}
                value={dashboard.sla.running}
                icon={Clock}
                color="green"
                onClick={() => handleCardClick('running')}
                active={drillFilter === 'running'}
                testId="sla-stat-running"
              />
              <StatCard
                title={st('$i18n:bpm.sla.monitor.warning') || 'Warning'}
                value={dashboard.sla.warning}
                icon={AlertTriangle}
                color="yellow"
                onClick={() => handleCardClick('warning')}
                active={drillFilter === 'warning'}
                testId="sla-stat-WARNING"
              />
              <StatCard
                title={st('$i18n:bpm.sla.monitor.overdue') || 'Overdue'}
                value={dashboard.sla.overdue}
                icon={XCircle}
                color="red"
                onClick={() => handleCardClick('overdue')}
                active={drillFilter === 'overdue'}
                testId="sla-stat-OVERDUE"
              />
              <StatCard
                title={st('$i18n:bpm.sla.monitor.paused') || 'Paused'}
                value={dashboard.sla.paused}
                icon={PauseCircle}
                color="purple"
                onClick={() => handleCardClick('paused')}
                active={drillFilter === 'paused'}
                testId="sla-stat-paused"
              />
            </div>
          </div>

          {/* Drill-down Panel */}
          {drillFilter && (
            <SlaRecordListPanel
              statusFilter={drillFilter === 'all' ? undefined : drillFilter}
              onClose={() => setDrillFilter(null)}
            />
          )}

          {/* SLA Config Stats */}
          <div data-testid="sla-dashboard-configs">
            <h2 className="mb-3 text-lg font-semibold">
              {st('$i18n:bpm.sla.monitor.configs') || 'SLA Configurations'}
            </h2>
            <div className="grid grid-cols-2 gap-4 md:grid-cols-4">
              <StatCard
                title={st('$i18n:bpm.sla.monitor.configTotal') || 'Total Configs'}
                value={dashboard.slaConfigs.total}
                icon={BarChart3}
                color="blue"
              />
              <StatCard
                title={st('$i18n:bpm.sla.monitor.configEnabled') || 'Enabled'}
                value={dashboard.slaConfigs.enabled}
                icon={Activity}
                color="green"
              />
            </div>
          </div>

        </>
      ) : (
        <div className="text-muted-foreground py-12 text-center">
          {st('$i18n:bpm.sla.monitor.noData') || 'No monitoring data available'}
        </div>
      )}
    </div>
  );
}
