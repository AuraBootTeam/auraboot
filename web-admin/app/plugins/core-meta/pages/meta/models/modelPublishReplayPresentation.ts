import { getLocalizedText } from '~/utils/i18n';
import type { ModelPublishReplayResult } from '~/shared/services/modelService';
import TEXT from './modelPublishReplayPresentation.i18n.json';

export const replayText = (key: keyof typeof TEXT, locale: string) => getLocalizedText(TEXT[key], locale);

const OUTPUT_LABEL_KEYS = new Set(["permissionCode", "memberId", "granted", "resource", "action", "recordPid", "permissionPolicyPid", "roleId", "grantType", "status", "truth", "matched", "reason", "stepCount", "steps", "fieldRefs", "ruleTraceId", "traceId", "deadlineMinutes", "processPid", "slaConfigPid", "processInstanceId", "taskId", "processKey", "processName", "processVersion", "processStatus", "targetType", "targetKey", "nodeId", "nodeType", "edgeId", "edgeSource", "edgeTarget", "bindingSurface", "bindingKind", "decisionCode", "decisionStatus", "conditionResult", "fallbackApplied", "durationMs", "errorCode", "inputs", "outputs", "decisionRefs", "candidateUserIds", "candidateGroupIds", "failClosed", "slaRecordPid", "slaRecordStatus", "actionCount", "actionPolicyTrigger", "deadlineMode", "deadlineValue", "deadlineTime", "startTime", "enabled", "modelCode", "affectedFieldRef", "fieldRiskLevel", "fieldRiskSummary", "fieldMasked", "fieldPermissionChange", "fieldPermission", "requiresLowPermissionSample"]);

export function hasReplayOutputLabel(key: string): boolean {
  return OUTPUT_LABEL_KEYS.has(key);
}

export function formatReplayOutputLabel(key: string, locale = 'zh-CN'): string {
  return OUTPUT_LABEL_KEYS.has(key) ? replayText(key as keyof typeof TEXT, locale) : replayText('otherResult', locale);
}

const TECHNICAL_OUTPUT_KEYS = new Set([
  'permissionCode', 'memberId', 'resource', 'action', 'recordPid', 'permissionPolicyPid',
  'roleId', 'fieldRefs', 'ruleTraceId', 'traceId', 'processPid', 'slaConfigPid',
  'processInstanceId', 'taskId', 'processKey', 'targetKey', 'nodeId', 'edgeId',
  'edgeSource', 'edgeTarget', 'decisionCode', 'decisionRefs', 'candidateUserIds',
  'candidateGroupIds', 'slaRecordPid', 'modelCode', 'affectedFieldRef',
]);

export function isTechnicalReplayOutput(key: string): boolean {
  return TECHNICAL_OUTPUT_KEYS.has(key);
}

export function getReplayConsumerLabel(type: string | undefined, locale = 'zh-CN'): string {
  const keys = { DECISION_VERSION: 'consumerDecision', PERMISSION_POLICY: 'consumerPermission',
    WORKFLOW_PROCESS: 'consumerWorkflow', SLA_RULE: 'consumerSla', AUTOMATION: 'consumerAutomation',
    EVENT_POLICY: 'consumerEvent', NAMED_QUERY: 'consumerQuery' } as const;
  return replayText(keys[type as keyof typeof keys] ?? 'consumerRule', locale);
}

export function getReplayStatusClass(status?: string): string {
  if (status === 'EXECUTED') return 'bg-emerald-50 text-emerald-700';
  if (status === 'READY') return 'bg-blue-50 text-blue-700';
  if (status === 'MANUAL_REQUIRED' || status === 'NEEDS_SAMPLE_CONTEXT') {
    return 'bg-amber-50 text-amber-700';
  }
  if (
    status === 'FAILED' ||
    status === 'AUTOMATION_UNAVAILABLE' ||
    status === 'PERMISSION_UNAVAILABLE' ||
    status === 'WORKFLOW_UNAVAILABLE'
  ) {
    return 'bg-red-50 text-red-700';
  }
  return 'bg-gray-100 text-gray-700';
}

export function permissionReplayTraceHref(result: ModelPublishReplayResult): string | null {
  if (result.step?.consumerType !== 'PERMISSION_POLICY') {
    return null;
  }
  const traceId = typeof result.traceId === 'string' ? result.traceId.trim() : '';
  if (!traceId) {
    return null;
  }
  const params = new URLSearchParams({ traceId, callerType: 'PERMISSION' });
  const callerRef = typeof result.outputs?.permissionCode === 'string'
    ? result.outputs.permissionCode
    : result.step?.sourceCode;
  if (callerRef) {
    params.set('callerRef', callerRef);
  }
  return `/p/decisionops_execution_logs?${params.toString()}`;
}

export function formatReplayOutputValue(key: string, value: unknown, locale = 'zh-CN'): string {
  if (value === null || value === undefined) return '-';
  if (typeof value === 'string') {
    const normalized = value.toLowerCase();
    if ((key === 'truth' || key === 'matched') && ['true', 'yes', 'matched'].includes(normalized)) {
      return replayText('valueYes', locale);
    }
    if ((key === 'truth' || key === 'matched') && ['false', 'no', 'not_matched'].includes(normalized)) {
      return replayText('valueNo', locale);
    }
    if (key === 'grantType') {
      if (normalized === 'grant') return replayText('valueGrant', locale);
      if (normalized === 'deny') return replayText('valueDeny', locale);
    }
    if (key === 'status') {
      if (normalized === 'active') return replayText('valueActive', locale);
      if (normalized === 'inactive') return replayText('valueInactive', locale);
      if (normalized === 'disabled') return replayText('valueDisabled', locale);
      if (normalized === 'pending') return replayText('valuePending', locale);
    }
    if (key === 'targetType' && normalized === 'node') return replayText('valueWorkflowNode', locale);
    if (key === 'targetType' && normalized === 'record') return replayText('valueBusinessRecord', locale);
    if (key === 'nodeType' && normalized === 'usertask') return replayText('valueApprovalTask', locale);
    if (key === 'nodeType' && normalized === 'sequenceflow') return replayText('valueSequenceFlow', locale);
    if (key === 'nodeType' && normalized === 'exclusivegateway') return replayText('valueExclusiveGateway', locale);
    if (key === 'nodeType' && normalized === 'inclusivegateway') return replayText('valueInclusiveGateway', locale);
    if (key === 'bindingSurface' && normalized === 'node rulebinding') return replayText('valueNodeBinding', locale);
    if (key === 'bindingSurface' && normalized === 'edge conditionspec') return replayText('valueEdgeCondition', locale);
    if (key === 'bindingKind' && normalized === 'decision_ref') return replayText('valueDecisionReference', locale);
    if (key === 'bindingKind' && normalized === 'condition') return replayText('valueConditionExpression', locale);
    if (key === 'decisionStatus') {
      if (normalized === 'matched') return replayText('valueMatched', locale);
      if (normalized === 'not_matched') return replayText('valueNotMatched', locale);
      if (normalized === 'unknown') return replayText('valueUnknown', locale);
      if (normalized === 'error') return replayText('valueError', locale);
      if (normalized === 'skipped') return replayText('valueSkipped', locale);
    }
    if (key === 'errorCode' && normalized === 'decision_evaluation_failed') {
      return replayText('valueDecisionFailed', locale);
    }
    if (key === 'conditionResult') {
      if (normalized === 'true') return replayText('valueSatisfied', locale);
      if (normalized === 'false') return replayText('valueNotSatisfied', locale);
      if (normalized === 'unknown') return replayText('valueUnknown', locale);
    }
    if (key === 'processStatus') {
      if (normalized === 'deployed') return replayText('valueDeployed', locale);
      if (normalized === 'draft') return replayText('valueDraft', locale);
      if (normalized === 'suspended') return replayText('valueSuspended', locale);
      if (normalized === 'archived') return replayText('valueArchived', locale);
    }
    if (key === 'actionPolicyTrigger' && normalized === 'sla_timeout') return replayText('valueSlaTimeout', locale);
    if (key === 'actionPolicyTrigger' && normalized === 'sla_warning') return replayText('valueSlaWarning', locale);
    if (key === 'deadlineMode' && normalized === 'fixed') return replayText('valueFixedDuration', locale);
    if (key === 'deadlineMode' && normalized === 'rule') return replayText('valueRuleCalculation', locale);
    if (key === 'slaRecordStatus') {
      if (normalized === 'running') return replayText('valueRunning', locale);
      if (normalized === 'completed') return replayText('valueCompleted', locale);
      if (normalized === 'breached') return replayText('valueOverdue', locale);
      if (normalized === 'cancelled' || normalized === 'canceled') return replayText('valueCancelled', locale);
    }
    if (key === 'reason') {
      if (normalized === 'granted') return replayText('valueGranted', locale);
      if (normalized === 'denied') return replayText('valueDenied', locale);
      if (normalized === 'rejected') return replayText('valueDenied', locale);
      if (normalized.includes('condition guard not satisfied')) return replayText('valueGuardNotSatisfied', locale);
    }
    if (key === 'fieldRiskLevel') {
      if (normalized === 'field_permission_change') return replayText('valueFieldPermissionChanged', locale);
      if (normalized === 'field_masked') return replayText('valueFieldMasked', locale);
      if (normalized === 'field_governance_review') return replayText('valueFieldGovernance', locale);
    }
    if (key === 'fieldRiskSummary') {
      if (normalized === 'masked_permission_change') {
        return replayText('valueMaskedPermissionChange', locale);
      }
      if (normalized === 'permission_change') {
        return replayText('valuePermissionChanged', locale);
      }
      if (normalized === 'masked_field') {
        return replayText('valueMaskedFieldSummary', locale);
      }
    }
    return value;
  }
  if (typeof value === 'boolean') return value ? replayText('valueYes', locale) : replayText('valueNo', locale);
  if (typeof value === 'number') return String(value);
  if (Array.isArray(value)) {
    if (key === 'candidateUserIds' || key === 'candidateGroupIds') {
      return value.length > 0 ? String(value.length) + ' ' + replayText(key === 'candidateUserIds' ? 'reviewers' : 'groups', locale) : replayText('valueNone', locale);
    }
    return String(value.length) + ' ' + replayText('items', locale);
  }
  if (typeof value === 'object') {
    return replayText('valueRecorded', locale);
  }
  return String(value);
}
