/**
 * Domain Config Service
 * API service for managing BPM domain configurations
 */

import { get, post, put, del } from '~/services/http-client';

// ==================== Types ====================

export interface DomainConfig {
  pid: string;
  domainCode: string;
  domainName: string;
  modelCode?: string;
  processKeys?: string[];
  listFields?: Record<string, unknown>[];
  filterFields?: Record<string, unknown>[];
  sortFields?: Record<string, unknown>[];
  enabled: boolean;
  createdAt?: string;
  updatedAt?: string;
}

export interface CreateDomainConfigRequest {
  domainCode: string;
  domainName: string;
  modelCode?: string;
  processKeys?: string[];
  listFields?: Record<string, unknown>[];
  filterFields?: Record<string, unknown>[];
  sortFields?: Record<string, unknown>[];
  enabled?: boolean;
}

export interface UpdateDomainConfigRequest {
  domainName?: string;
  modelCode?: string;
  processKeys?: string[];
  listFields?: Record<string, unknown>[];
  filterFields?: Record<string, unknown>[];
  sortFields?: Record<string, unknown>[];
  enabled?: boolean;
}

// ==================== API ====================

const BASE = '/api/bpm/domain-configs';

export async function listDomainConfigs(): Promise<DomainConfig[]> {
  const result = await get<DomainConfig[]>(BASE);
  return result.data ?? [];
}

export async function getDomainConfig(pid: string): Promise<DomainConfig> {
  const result = await get<DomainConfig>(`${BASE}/${pid}`);
  return result.data!;
}

export async function createDomainConfig(data: CreateDomainConfigRequest): Promise<DomainConfig> {
  const result = await post<DomainConfig>(BASE, data);
  return result.data!;
}

export async function updateDomainConfig(
  pid: string,
  data: UpdateDomainConfigRequest,
): Promise<DomainConfig> {
  const result = await put<DomainConfig>(`${BASE}/${pid}`, data);
  return result.data!;
}

export async function deleteDomainConfig(pid: string): Promise<void> {
  await del(`${BASE}/${pid}`);
}
