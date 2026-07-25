import { describe, it, expect } from 'vitest';
import type { ApiClient } from '../../src/client/api-client.js';
import {
  queryDynamicList,
  queryNamedQuery,
  type FilterItem,
} from '../../src/client/dynamic-query.js';

/**
 * Minimal ApiClient stand-in returning one canned response. Only `get` is
 * exercised by the query helpers, so nothing else needs to exist.
 */
function clientReturning(resp: {
  ok: boolean;
  status?: number;
  data?: unknown;
  message?: string;
}): ApiClient {
  return { get: async () => resp } as unknown as ApiClient;
}

describe('dynamic-query error handling', () => {
  // These helpers are shared between one-shot CLI commands and the long-lived
  // `aura mcp serve` process. Exiting the process on a backend error takes the
  // whole MCP server down mid-session, so failures must surface as exceptions
  // the caller can catch and turn into an MCP error result.
  it('queryDynamicList rejects when the backend refuses the query', async () => {
    const client = clientReturning({
      ok: false,
      status: 404,
      message: 'Model not found: no_such_model',
    });

    await expect(queryDynamicList(client, 'no_such_model')).rejects.toThrow(
      /Model not found: no_such_model/,
    );
  });

  it('queryNamedQuery rejects when the backend refuses the query', async () => {
    const client = clientReturning({
      ok: false,
      status: 403,
      message: 'Access denied',
    });

    await expect(queryNamedQuery(client, 'nq_forbidden')).rejects.toThrow(/Access denied/);
  });

  it('queryDynamicList returns records on success', async () => {
    const client = clientReturning({ ok: true, data: { records: [{ id: 1 }] } });

    await expect(queryDynamicList(client, 'crm_lead')).resolves.toEqual([{ id: 1 }]);
  });
});

describe('dynamic-query', () => {
  describe('filter construction', () => {
    it('should build EQ filter', () => {
      const filter: FilterItem = { fieldName: 'crm_lead_status', operator: 'EQ', value: 'new' };
      expect(filter.fieldName).toBe('crm_lead_status');
      expect(filter.operator).toBe('EQ');
      expect(filter.value).toBe('new');
    });

    it('should serialize filters as JSON', () => {
      const filters: FilterItem[] = [
        { fieldName: 'crm_lead_status', operator: 'EQ', value: 'qualified' },
        { fieldName: 'crm_lead_source', operator: 'EQ', value: 'website' },
      ];
      const json = JSON.stringify(filters);
      const parsed = JSON.parse(json);
      expect(parsed).toHaveLength(2);
      expect(parsed[0].fieldName).toBe('crm_lead_status');
      expect(parsed[1].value).toBe('website');
    });

    it('should handle empty filters', () => {
      const filters: FilterItem[] = [];
      expect(JSON.stringify(filters)).toBe('[]');
    });
  });

  describe('query params construction', () => {
    it('should build dynamic CRUD params', () => {
      const params: Record<string, string> = {
        pageNum: '1',
        pageSize: '50',
        keyword: 'acme',
        sortField: 'created_at',
        sortOrder: 'desc',
      };
      expect(params.pageSize).toBe('50');
      expect(params.keyword).toBe('acme');
    });

    it('should build NamedQuery params', () => {
      const params = {
        datasourceId: 'nq:crm_dashboard_kpi',
        maxItems: '200',
        format: 'records',
      };
      expect(params.datasourceId).toBe('nq:crm_dashboard_kpi');
      expect(params.format).toBe('records');
    });
  });
});
