import { ApiClient, EXIT } from './api-client.js';
import chalk from 'chalk';

export interface QueryOptions {
  pageNum?: number;
  pageSize?: number;
  filters?: FilterItem[];
  keyword?: string;
  sortField?: string;
  sortOrder?: 'asc' | 'desc';
}

export interface FilterItem {
  fieldName: string;
  operator: 'EQ' | 'neq' | 'like' | 'GT' | 'gte' | 'LT' | 'lte' | 'IN' | 'is_null' | 'is_not_null';
  value?: string | number | string[];
}

/**
 * Query a Dynamic CRUD list endpoint.
 * All business domain commands use this as their data source.
 */
export async function queryDynamicList(
  client: ApiClient,
  pageKey: string,
  options: QueryOptions = {},
): Promise<any[]> {
  const params: Record<string, string> = {
    pageNum: String(options.pageNum || 1),
    pageSize: String(options.pageSize || 50),
  };

  if (options.keyword) {
    params.keyword = options.keyword;
  }
  if (options.sortField) {
    params.sortField = options.sortField;
    params.sortOrder = options.sortOrder || 'desc';
  }
  if (options.filters && options.filters.length > 0) {
    params.filters = JSON.stringify(options.filters);
  }

  const resp = await client.get(`/api/dynamic/${pageKey}/list`, params);

  if (!resp.ok) {
    console.error(chalk.red(`Query failed: ${resp.message}`));
    process.exit(EXIT.FAILURE);
  }

  return resp.data?.records || resp.data || [];
}

/**
 * Query a NamedQuery datasource.
 */
export async function queryNamedQuery(
  client: ApiClient,
  nqCode: string,
  params: Record<string, string> = {},
): Promise<any[]> {
  const resp = await client.get('/api/datasource/list', {
    datasourceId: `nq:${nqCode}`,
    maxItems: params.maxItems || '200',
    format: 'records',
    ...params,
  });

  if (!resp.ok) {
    console.error(chalk.red(`Query failed: ${resp.message}`));
    process.exit(EXIT.FAILURE);
  }

  return resp.data?.records || resp.data || [];
}
