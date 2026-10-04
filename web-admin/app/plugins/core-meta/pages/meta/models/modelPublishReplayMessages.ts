import { getLocalizedText } from '~/utils/i18n';
import TEXT from './modelPublishReplayMessages.i18n.json';
import type { ModelPublishReplayResult } from '~/shared/services/modelService';

const text = (key: keyof typeof TEXT, locale: string) => getLocalizedText(TEXT[key], locale);

export function getReplayStatusLabel(status: string | undefined, locale = 'zh-CN'): string {
  const labels: Record<string, string> = {
    EXECUTED: text('executed', locale),
    READY: text('ready', locale),
    MANUAL_REQUIRED: text('manual', locale),
    NEEDS_SAMPLE_CONTEXT: text('sample', locale),
    AUTOMATION_UNAVAILABLE: text('automationUnavailable', locale),
    PERMISSION_UNAVAILABLE: text('permissionUnavailable', locale),
    WORKFLOW_UNAVAILABLE: text('workflowUnavailable', locale),
    FAILED: text('failed', locale),
  };
  return labels[status || ''] || status || text('pending', locale);
}

export function getReplayResultMessage(result: ModelPublishReplayResult, locale = 'zh-CN'): string | null {
  const consumerType = result.step?.consumerType;
  if (consumerType === 'PERMISSION_POLICY' && result.status === 'NEEDS_SAMPLE_CONTEXT') {
    return text('permissionSample', locale);
  }
  if (consumerType === 'PERMISSION_POLICY' && result.status === 'READY') {
    return text('permissionReady', locale);
  }
  if (consumerType === 'PERMISSION_POLICY' && result.status === 'EXECUTED') {
    if (result.message?.includes('DENY')) {
      return text('permissionDenied', locale);
    }
    if (result.message?.includes('ALLOW')) {
      return text('permissionAllowed', locale);
    }
  }
  if (consumerType === 'DECISION_VERSION') {
    if (result.status === 'FAILED') return text('decisionFailed', locale);
    if (result.status === 'EXECUTED' && result.executed !== false) {
      if (result.matched === false) return text('decisionNotMatched', locale);
      if (result.matched === true) return text('decisionMatched', locale);
      // Legacy responses without matched must use complete status tokens.
      if (/\bNOT_MATCHED\b/.test(result.message || '')) return text('decisionNotMatched', locale);
      if (/\bMATCHED\b/.test(result.message || '')) return text('decisionMatched', locale);
      if (result.message?.includes('Decision replay executed')) return text('decisionExecuted', locale);
    } else if (typeof result.matched === 'boolean' || result.message?.includes('Decision replay executed')) {
      return text('decisionNotExecuted', locale);
    }
  }
  if (consumerType === 'WORKFLOW_PROCESS' && result.status === 'READY') {
    return text('workflowReady', locale);
  }
  if (consumerType === 'WORKFLOW_PROCESS' && result.status === 'NEEDS_SAMPLE_CONTEXT') {
    return text('workflowSample', locale);
  }
  if (consumerType === 'WORKFLOW_PROCESS' && result.status === 'WORKFLOW_UNAVAILABLE') {
    return text('workflowDisabled', locale);
  }
  if (consumerType === 'WORKFLOW_PROCESS' && result.status === 'FAILED') {
    if (result.outputs?.fallbackApplied === true) return text('workflowFallback', locale);
    if (result.outputs?.failClosed === true) {
      return text('workflowClosed', locale);
    }
    return text('workflowFailed', locale);
  }
  if (consumerType === 'WORKFLOW_PROCESS' && result.status === 'EXECUTED') {
    const hasAssignment =
      Array.isArray(result.outputs?.candidateUserIds) ||
      Array.isArray(result.outputs?.candidateGroupIds);
    if (hasAssignment) {
      return result.matched === false
        ? text('assignmentNotMatched', locale)
        : text('assignmentMatched', locale);
    }
    return result.matched === false ? text('workflowNotMatched', locale) : text('workflowMatched', locale);
  }
  if (consumerType === 'SLA_RULE' && result.status === 'READY') {
    return text('slaReady', locale);
  }
  if (consumerType === 'SLA_RULE' && result.status === 'NEEDS_SAMPLE_CONTEXT') {
    return text('slaSample', locale);
  }
  if (
    consumerType === 'SLA_RULE' &&
    result.status === 'EXECUTED' &&
    result.message?.includes('SLA NODE replay')
  ) {
    return text('slaNode', locale);
  }
  if (
    consumerType === 'SLA_RULE' &&
    result.status === 'EXECUTED' &&
    result.message?.includes('SLA RECORD replay')
  ) {
    return text('slaRecord', locale);
  }
  if (typeof result.message === 'string' && result.message.includes('sampleContext')) {
    return text('sampleContext', locale);
  }
  return result.message || null;
}
