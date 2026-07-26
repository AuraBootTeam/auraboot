import { describe, it, expect } from 'vitest';
import type { ApiClient } from '../../src/client/api-client.js';
import {
  queryDynamicList,
  queryNamedQuery,
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
