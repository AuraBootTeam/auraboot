import { get, post, put, del } from '~/shared/services/http-client';

export interface ConsistencyRule {
  id: number;
  pid: string;
  code: string;
  name: string;
  ruleType: string;
  severity: string;
  sourceModel: string;
  sourceField: string;
  targetModel: string;
  targetField: string;
  linkField: string;
  aggregation: string;
  operator: string;
  messageTemplate: string;
  enabled: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface ConsistencyRuleRequest {
  code: string;
  name: string;
  ruleType: string;
  severity: string;
  sourceModel: string;
  sourceField: string;
  targetModel: string;
  targetField: string;
  linkField: string;
  aggregation: string;
  operator: string;
  messageTemplate: string;
  enabled: boolean;
}

export interface ConsistencyViolation {
  ruleCode: string;
  ruleName: string;
  severity: string;
  message: string;
  sourceModel: string;
  targetModel: string;
  sourceAggregatedValue: number;
  targetValue: number;
}

export interface PaginatedRules {
  current: number;
  pageSize: number;
  total: number;
  totalPages: number;
  records: ConsistencyRule[];
}

/**
 * List consistency rules with optional source model filter.
 */
export async function listConsistencyRules(page = 1, size = 10, sourceModel?: string) {
  const params: Record<string, any> = { page, size };
  if (sourceModel) params.sourceModel = sourceModel;
  return get<PaginatedRules>('/api/meta/consistency-rules', params);
}

/**
 * Get a single consistency rule by ID.
 */
export async function getConsistencyRule(id: number) {
  return get<ConsistencyRule>(`/api/meta/consistency-rules/${id}`);
}

/**
 * Create a new consistency rule.
 */
export async function createConsistencyRule(rule: ConsistencyRuleRequest) {
  return post<ConsistencyRule>('/api/meta/consistency-rules', rule as any);
}

/**
 * Update an existing consistency rule.
 */
export async function updateConsistencyRule(id: number, rule: ConsistencyRuleRequest) {
  return put<ConsistencyRule>(`/api/meta/consistency-rules/${id}`, rule as any);
}

/**
 * Delete a consistency rule.
 */
export async function deleteConsistencyRule(id: number) {
  return del<boolean>(`/api/meta/consistency-rules/${id}`);
}

/**
 * Manually trigger validation for a model record.
 */
export async function validateConsistency(modelCode: string, recordId: string) {
  return post<ConsistencyViolation[]>('/api/meta/consistency-rules/validate', {
    modelCode,
    recordId,
  } as any);
}

/**
 * Check if an API response contains consistency violations.
 */
export function isConsistencyViolationError(responseData: any): boolean {
  return responseData?.type === 'consistency_violation' && Array.isArray(responseData?.violations);
}

/**
 * Extract violations from a consistency error response.
 */
export function extractViolations(responseData: any): ConsistencyViolation[] {
  if (isConsistencyViolationError(responseData)) {
    return responseData.violations;
  }
  return [];
}
