/**
 * SLA Service
 * API service for managing SLA configurations and monitoring
 */

import { get, post, put, del } from '~/shared/services/http-client';
import type {
  DecisionLogRecord,
  DecisionPageResult,
  EventPolicyActionLogRecord,
} from '~/shared/decision/api/decisionApi';

// ==================== Types ====================

export interface SlaConfig {
  pid: string;
  name: string;
  targetType: 'process' | 'node' | 'task' | 'PROCESS' | 'NODE' | 'TASK' | 'RECORD' | string;
  targetKey: string;
  targetLabel?: string;
  domainCode?: string;
  deadlineMode: 'fixed' | 'expression' | 'field' | 'FIXED' | 'EXPRESSION' | 'FIELD' | 'RULE' | string;
  deadlineValue: string;
  businessCalendar?: boolean;
  warningRules?: Record<string, unknown>[];
  ruleBinding?: RuleConsumerBinding;
  actionPolicy?: SlaActionPolicy;
  modelCode?: string;
  deadlineField?: string;
  priorityField?: string;
  suspendPolicy: 'pause' | 'continue' | 'cancel';
  enabled: boolean;
  createdAt?: string;
  updatedAt?: string;
}

export interface RuleValueRef {
  kind?: string;
  scope?: string;
  path?: string;
}

export interface RuleInputMapping {
  input?: string;
  source?: RuleValueRef;
}

export interface RuleOutputMapping {
  output?: string;
  target?: RuleValueRef;
}

export interface RuleDecisionBinding {
  decisionCode?: string;
  decisionName?: string;
  name?: string;
  label?: string;
  versionPolicy?: string;
  inputMappings?: RuleInputMapping[];
  outputMappings?: RuleOutputMapping[];
  fallbackPolicy?: Record<string, unknown>;
  traceMode?: string;
  enabled?: boolean;
}

export interface RuleConsumerBinding {
  consumerType?: string;
  consumerCode?: string;
  consumerNodeId?: string;
  bindingKind?: string;
  decisionBinding?: RuleDecisionBinding;
  enabled?: boolean;
}

export interface SlaAction {
  type?: string;
  target?: string;
  order?: number;
  payload?: Record<string, unknown>;
  idempotencyKeyTemplate?: string;
}

export interface SlaActionPolicy {
  trigger?: string;
  failureStrategy?:
    | 'FAIL_FAST'
    | 'CONTINUE_ON_ERROR'
    | 'ALL_OR_NOTHING'
    | 'RETRY_ASYNC'
    | 'DEAD_LETTER'
    | string;
  actions?: SlaAction[];
  executionEffect?: {
    lastStatus?: string;
    traceId?: string;
    lastRunAt?: string;
    summary?: string;
  };
}

export type SlaDecisionLog = DecisionLogRecord;
export type SlaActionLog = EventPolicyActionLogRecord;

export interface SlaDecisionLogFilters {
  callerType?: string;
  callerRef?: string;
  decisionCode?: string;
  size?: number;
}

export interface SlaActionLogFilters {
  policyCode?: string;
  policyCodePrefix?: string;
  correlationId?: string;
  size?: number;
}

export interface CreateSlaConfigRequest {
  name: string;
  targetType: string;
  targetKey: string;
  domainCode?: string;
  deadlineMode: string;
  deadlineValue: string;
  businessCalendar?: boolean;
  warningRules?: Record<string, unknown>[];
  modelCode?: string;
  deadlineField?: string;
  priorityField?: string;
  enabled?: boolean;
}

export interface UpdateSlaConfigRequest {
  name?: string;
  targetType?: string;
  targetKey?: string;
  domainCode?: string;
  deadlineMode?: string;
  deadlineValue?: string;
  businessCalendar?: boolean;
  warningRules?: Record<string, unknown>[];
  modelCode?: string;
  deadlineField?: string;
  priorityField?: string;
  enabled?: boolean;
}

export interface SlaRecord {
  pid: string;
  processInstanceId: string;
  slaConfigId: string;
  taskId?: string;
  nodeId?: string;
  status: string;
  startTime: string;
  deadlineTime: string;
  completedTime?: string;
  pausedAt?: string;
  totalPausedMs?: number;
  currentWarningLevel?: number;
  warningHistory?: Record<string, unknown>[];
  createdAt?: string;
  updatedAt?: string;
}

export interface DashboardData {
  processDefinitions: {
    total: number;
    draft: number;
    deployed: number;
    suspended: number;
  };
  sla: {
    active: number;
    running: number;
    warning: number;
    overdue: number;
    paused: number;
  };
  slaConfigs: {
    total: number;
    enabled: number;
  };
}

// ==================== SLA Config API ====================

const SLA_BASE = '/api/bpm/sla-configs';

export async function listSlaConfigs(): Promise<SlaConfig[]> {
  const result = await get<SlaConfig[]>(SLA_BASE);
  return result.data ?? [];
}

export async function getSlaConfig(pid: string): Promise<SlaConfig> {
  const result = await get<SlaConfig>(`${SLA_BASE}/${pid}`);
  return result.data!;
}

export async function createSlaConfig(data: CreateSlaConfigRequest): Promise<SlaConfig> {
  const result = await post<SlaConfig>(SLA_BASE, data);
  return result.data!;
}

export async function updateSlaConfig(
  pid: string,
  data: UpdateSlaConfigRequest,
): Promise<SlaConfig> {
  const result = await put<SlaConfig>(`${SLA_BASE}/${pid}`, data);
  return result.data!;
}

export async function deleteSlaConfig(pid: string): Promise<void> {
  await del(`${SLA_BASE}/${pid}`);
}

// ==================== Monitor API ====================

export async function getDashboard(): Promise<DashboardData> {
  const result = await get<DashboardData>('/api/bpm/monitor/dashboard');
  return result.data!;
}

export async function getSlaByInstance(processInstanceId: string): Promise<SlaRecord[]> {
  const result = await get<SlaRecord[]>(`/api/bpm/monitor/instances/${processInstanceId}/sla`);
  return result.data ?? [];
}

export async function listSlaDecisionLogs(
  params: SlaDecisionLogFilters = { callerType: 'SLA', size: 20 },
): Promise<SlaDecisionLog[]> {
  const result = await get<DecisionPageResult<DecisionLogRecord>>('/api/decision/logs/recent', params);
  return result.data?.records ?? [];
}

export async function listSlaActionLogs(
  params: SlaActionLogFilters = { policyCodePrefix: 'SLA_TIMEOUT:', size: 50 },
): Promise<SlaActionLog[]> {
  const result = await get<SlaActionLog[]>('/api/event-policy/action-logs', params);
  return result.data ?? [];
}

export async function replaySlaActionLog(pid: string): Promise<SlaActionLog> {
  const result = await post<SlaActionLog>(
    `/api/event-policy/action-logs/${encodeURIComponent(pid)}/replay`,
  );
  return result.data!;
}

// ==================== SLA Record Drill-down API ====================

export async function listSlaRecords(params?: { status?: string }): Promise<SlaRecord[]> {
  const result = await get<SlaRecord[]>('/api/bpm/monitor/sla-records', params);
  return result.data ?? [];
}

export async function getSlaRecordDetail(pid: string): Promise<SlaRecord> {
  const result = await get<SlaRecord>(`/api/bpm/monitor/sla-records/${pid}`);
  return result.data!;
}
