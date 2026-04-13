/**
 * SLA Service
 * API service for managing SLA configurations and monitoring
 */

import { get, post, put, del } from '~/shared/services/http-client';

// ==================== Types ====================

export interface SlaConfig {
  pid: string;
  name: string;
  targetType: 'process' | 'node' | 'task';
  targetKey: string;
  domainCode?: string;
  deadlineMode: 'fixed' | 'expression' | 'field';
  deadlineValue: string;
  businessCalendar?: boolean;
  warningRules?: Record<string, unknown>[];
  modelCode?: string;
  deadlineField?: string;
  priorityField?: string;
  suspendPolicy: 'pause' | 'continue' | 'cancel';
  enabled: boolean;
  createdAt?: string;
  updatedAt?: string;
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

// ==================== SLA Record Drill-down API ====================

export async function listSlaRecords(params?: { status?: string }): Promise<SlaRecord[]> {
  const result = await get<SlaRecord[]>('/api/bpm/monitor/sla-records', { params });
  return result.data ?? [];
}

export async function getSlaRecordDetail(pid: string): Promise<SlaRecord> {
  const result = await get<SlaRecord>(`/api/bpm/monitor/sla-records/${pid}`);
  return result.data!;
}
