import { ApiClient } from './api-client.js';

/**
 * Raised when the backend refuses a query.
 *
 * <p>These helpers are shared between one-shot CLI commands and the long-lived
 * `aura mcp serve` process. Exiting the process here would take the whole MCP
 * server down mid-session — a mistyped model code or a permission error would
 * disconnect the agent — so the failure is raised instead and handled by the
 * caller: MCP tool handlers turn it into an `isError` result, and the CLI entry
 * point prints it and exits (see `handleCliError`).
 */
export class QueryFailedError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'QueryFailedError';
  }
}

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
    throw new QueryFailedError(`Query failed: ${resp.message}`);
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
    throw new QueryFailedError(`Query failed: ${resp.message}`);
  }

  return resp.data?.records || resp.data || [];
}
