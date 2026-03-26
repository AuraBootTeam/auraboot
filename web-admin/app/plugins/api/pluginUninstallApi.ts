/**
 * Plugin Uninstall API Service
 *
 * Provides API calls for plugin uninstall operations and resource ownership management.
 */

import { get, post } from '~/services/http-client';
import type { Result } from '~/services/http-client';

// ==================== Types ====================

export type OwnershipType = 'plugin_owned' | 'shared' | 'user_claimed';

export type UninstallDecision = 'delete' | 'keep_and_detach' | 'skip';

export type ResourceType =
  | 'model'
  | 'field'
  | 'model_field_binding'
  | 'command'
  | 'binding_rule'
  | 'permission'
  | 'role'
  | 'role_permission'
  | 'menu'
  | 'process'
  | 'page'
  | 'dict'
  | 'dict_item';

export interface ResourceDiff {
  field: string;
  original: unknown;
  current: unknown;
  description: string;
}

export interface ResourceUninstallInfo {
  pid: string;
  type: ResourceType;
  code: string;
  name: string;
  ownershipType: OwnershipType;
  modified: boolean;
  claimed: boolean;
  diffs?: ResourceDiff[];
  suggestedDecision?: UninstallDecision;
}

export interface UninstallPreviewResult {
  pluginPid: string;
  pluginId: string;
  pluginName: string;
  pluginVersion: string;
  willDelete: ResourceUninstallInfo[];
  needsDecision: ResourceUninstallInfo[];
  willKeep: ResourceUninstallInfo[];
  summaryCounts: Record<string, number>;
  hasConflicts: boolean;
  totalResources: number;
}

export interface UninstallRequest {
  removeData?: boolean;
  decisions?: Record<string, UninstallDecision>;
  force?: boolean;
}

export interface UninstallResult {
  success: boolean;
  pluginPid: string;
  pluginId: string;
  deletedCount: number;
  detachedCount: number;
  keptCount: number;
  deletedResources: string[];
  detachedResources: string[];
  errorMessage?: string;
  uninstalledAt?: string;
}

export interface PluginResource {
  id: number;
  pid: string;
  tenantId: number;
  pluginPid: string;
  importId: string;
  resourceType: ResourceType;
  resourcePid: string;
  resourceId?: number;
  resourceCode: string;
  resourceName: string;
  action: string;
  ownershipType: OwnershipType;
  userModified: boolean;
  userModifiedAt?: string;
  lastSyncVersion?: string;
  sequence: number;
  createdAt: string;
  updatedAt: string;
}

export interface ResourceOwnershipInfo {
  resourceType: ResourceType;
  resourceCode: string;
  managed: boolean;
  pluginPid?: string;
  ownershipType?: OwnershipType;
  userModified?: boolean;
  canModify?: boolean;
}

// ==================== API Functions ====================

/**
 * Get uninstall preview for a plugin.
 * Shows what resources will be deleted, need user decision, or will be kept.
 */
export async function getUninstallPreview(pluginPid: string): Promise<Result<UninstallPreviewResult>> {
  return get<UninstallPreviewResult>(`/api/plugins/{pluginPid}/uninstall/preview`, { pluginPid });
}

/**
 * Execute plugin uninstall with user decisions.
 */
export async function executeUninstall(
  pluginPid: string,
  request: UninstallRequest
): Promise<Result<UninstallResult>> {
  return post<UninstallResult>(`/api/plugins/{pluginPid}/uninstall`, { pluginPid, ...request });
}

/**
 * Get all resources for a plugin.
 */
export async function getPluginResources(pluginPid: string): Promise<Result<PluginResource[]>> {
  return get<PluginResource[]>(`/api/plugins/{pluginPid}/resources`, { pluginPid });
}

/**
 * Get modified resources for a plugin.
 */
export async function getModifiedResources(pluginPid: string): Promise<Result<PluginResource[]>> {
  return get<PluginResource[]>(`/api/plugins/{pluginPid}/resources/modified`, { pluginPid });
}

/**
 * Get user-claimed resources for a plugin.
 */
export async function getClaimedResources(pluginPid: string): Promise<Result<PluginResource[]>> {
  return get<PluginResource[]>(`/api/plugins/{pluginPid}/resources/claimed`, { pluginPid });
}

/**
 * Get resource ownership statistics for a plugin.
 */
export async function getResourceStats(pluginPid: string): Promise<Result<Record<OwnershipType, number>>> {
  return get<Record<OwnershipType, number>>(`/api/plugins/{pluginPid}/resources/stats`, { pluginPid });
}

/**
 * Check ownership info for a specific resource.
 */
export async function getResourceOwnership(
  resourceType: ResourceType,
  resourceCode: string
): Promise<Result<ResourceOwnershipInfo>> {
  return get<ResourceOwnershipInfo>('/api/plugins/resources/ownership', { resourceType, resourceCode });
}

/**
 * Mark a resource as modified by user.
 */
export async function markResourceAsModified(
  resourceType: ResourceType,
  resourceCode: string
): Promise<Result<void>> {
  return post<void>('/api/plugins/resources/mark-modified', { resourceType, resourceCode });
}

/**
 * Claim resource ownership (detach from plugin).
 */
export async function claimResource(
  resourceType: ResourceType,
  resourceCode: string
): Promise<Result<void>> {
  return post<void>('/api/plugins/resources/claim', { resourceType, resourceCode });
}

/**
 * Get diff between import snapshot and current state.
 */
export async function getResourceDiff(
  resourceType: ResourceType,
  resourceCode: string
): Promise<Result<ResourceDiff[]>> {
  return get<ResourceDiff[]>('/api/plugins/resources/diff', { resourceType, resourceCode });
}

// ==================== Display Helpers ====================

export const RESOURCE_TYPE_LABELS: Record<ResourceType, string> = {
  model: '模型',
  field: '字段',
  model_field_binding: '模型字段绑定',
  command: '命令',
  binding_rule: '绑定规则',
  permission: '权限',
  role: '角色',
  role_permission: '角色权限',
  menu: '菜单',
  process: '流程',
  page: '页面',
  dict: '字典',
  dict_item: '字典项',
};

export const OWNERSHIP_TYPE_LABELS: Record<OwnershipType, string> = {
  plugin_owned: '插件控制',
  shared: '共享所有权',
  user_claimed: '用户接管',
};

export const DECISION_LABELS: Record<UninstallDecision, string> = {
  delete: '删除',
  keep_and_detach: '保留并脱离插件',
  skip: '跳过',
};

export function getResourceTypeLabel(type: ResourceType): string {
  return RESOURCE_TYPE_LABELS[type] || type;
}

export function getOwnershipTypeLabel(type: OwnershipType): string {
  return OWNERSHIP_TYPE_LABELS[type] || type;
}

export function getDecisionLabel(decision: UninstallDecision): string {
  return DECISION_LABELS[decision] || decision;
}
