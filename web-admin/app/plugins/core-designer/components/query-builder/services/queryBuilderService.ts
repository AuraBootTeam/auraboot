/**
 * Query Builder API Service
 */

import { get, post } from '~/services/http-client';
import type { Result } from '~/services/http-client';

export interface FilterCondition {
  fieldName: string;
  operator: string;
  value: string;
}

export interface AggregationConfig {
  fieldCode: string;
  function: 'count' | 'sum' | 'avg' | 'min' | 'max';
  alias?: string;
}

export interface QueryBuilderRequest {
  modelCode: string;
  fields?: string[];
  filters?: FilterCondition[];
  groupBy?: string[];
  aggregations?: AggregationConfig[];
  sortField?: string;
  sortOrder?: 'asc' | 'desc';
  limit?: number;
}

export interface ModelInfo {
  code: string;
  name: string;
  tableName?: string;
  description?: string;
}

export interface FieldInfo {
  code: string;
  name: string;
  dataType: string;
  columnName?: string;
}

export const queryBuilderService = {
  async execute(request: QueryBuilderRequest): Promise<Result<Record<string, unknown>[]>> {
    return post<Record<string, unknown>[]>('/api/query-builder/execute', request);
  },

  async getModels(keyword?: string): Promise<Result<ModelInfo[]>> {
    return get<ModelInfo[]>('/api/query-builder/models', { keyword });
  },

  async getFields(modelCode: string): Promise<Result<FieldInfo[]>> {
    return get<FieldInfo[]>(`/api/query-builder/models/${modelCode}/fields`);
  },
};
