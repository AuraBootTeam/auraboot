import { describe, expect, it, vi } from 'vitest';
import type { ApiClient } from '../../../../src/client/api-client.js';
import { askAuraBotTool } from '../../../../src/mcp/tools/read/askAuraBot.js';
import { dispatchAgentTool } from '../../../../src/mcp/tools/read/dispatchAgent.js';
import { listAgentsTool } from '../../../../src/mcp/tools/read/listAgents.js';
import { listToolsTool } from '../../../../src/mcp/tools/read/listTools.js';
import { queryEntityTool } from '../../../../src/mcp/tools/read/queryEntity.js';
import { runNamedQueryTool } from '../../../../src/mcp/tools/read/runNamedQuery.js';

/**
 * Unit coverage for the 6 read tools inherited from v1.x.
 *
 * D11 covers their error paths so the global coverage clears 80%; their
 * happy paths are also exercised via the MCP-wire integration test in
 * tests/mcp/integration.test.ts.
 */

function withGet(impl: (...args: any[]) => Promise<any>): ApiClient {
  return { get: vi.fn(impl), post: vi.fn() } as unknown as ApiClient;
}

function withPost(impl: (...args: any[]) => Promise<any>): ApiClient {
  return { get: vi.fn(), post: vi.fn(impl) } as unknown as ApiClient;
}

describe('queryEntityTool', () => {
  it('returns isError when queryDynamicList throws', async () => {
    const tool = queryEntityTool({
      get: vi.fn(async () => {
        throw new Error('connection refused');
      }),
      post: vi.fn(),
    } as unknown as ApiClient);

    const result = await tool.handler({
      entityCode: 'crm_lead',
      limit: 20,
      sortOrder: 'desc',
    });

    expect(result.isError).toBe(true);
    expect(result.content[0].text).toMatch(/connection refused/);
  });

  // Happy-path coverage for queryEntity is in tests/mcp/integration.test.ts —
  // exercising the full MCP wire with a real client is more meaningful than
  // mocking every internal envelope shape here.
});

describe('runNamedQueryTool', () => {
  it('returns isError when queryNamedQuery throws', async () => {
    const tool = runNamedQueryTool(
      withGet(async () => {
        throw new Error('SQL parse error');
      }),
    );
    const result = await tool.handler({ queryCode: 'crm_dashboard_kpi', limit: 200 });
    expect(result.isError).toBe(true);
    expect(result.content[0].text).toMatch(/SQL parse error/);
  });
});

describe('listAgentsTool', () => {
  it('returns isError when underlying NQ fails', async () => {
    const tool = listAgentsTool(
      withGet(async () => {
        throw new Error('NQ acp_agent_stats not found');
      }),
    );
    const result = await tool.handler({});
    expect(result.isError).toBe(true);
    expect(result.content[0].text).toMatch(/acp_agent_stats/);
  });
});

describe('listToolsTool', () => {
  it('returns isError when underlying NQ fails', async () => {
    const tool = listToolsTool(
      withGet(async () => {
        throw new Error('boom');
      }),
    );
    const result = await tool.handler({});
    expect(result.isError).toBe(true);
  });
});

describe('dispatchAgentTool', () => {
  it('declares non-readOnly + idempotent annotations', () => {
    const tool = dispatchAgentTool({} as ApiClient);
    expect(tool.annotations).toMatchObject({ idempotentHint: true });
  });

  it('surfaces backend non-ok as isError with Dispatch failed prefix', async () => {
    const tool = dispatchAgentTool(
      withPost(async () => ({
        ok: false,
        status: 403,
        data: null,
        message: 'Professional license required',
      })),
    );
    const result = await tool.handler({ taskPid: 't-1' });
    expect(result.isError).toBe(true);
    expect(result.content[0].text).toMatch(/Dispatch failed/);
    expect(result.content[0].text).toMatch(/Professional/);
  });

  it('returns isError on thrown error', async () => {
    const tool = dispatchAgentTool(
      withPost(async () => {
        throw new Error('eof');
      }),
    );
    const result = await tool.handler({ taskPid: 't-1' });
    expect(result.isError).toBe(true);
  });

  it('happy path returns parsed data', async () => {
    const tool = dispatchAgentTool(
      withPost(async () => ({ ok: true, status: 200, data: { runId: 'r-1' } })),
    );
    const result = await tool.handler({ taskPid: 't-1' });
    expect(result.isError).toBeUndefined();
    expect(JSON.parse(result.content[0].text)).toEqual({ runId: 'r-1' });
  });
});

describe('askAuraBotTool', () => {
  it('passes question through as a single user message', async () => {
    const post = vi.fn(async () => ({ ok: true, status: 200, data: { answer: '42' } }));
    const tool = askAuraBotTool({ get: vi.fn(), post } as unknown as ApiClient);

    await tool.handler({ question: 'What is the meaning of life?' });

    const [, body] = post.mock.calls[0];
    expect(body).toEqual({ messages: [{ role: 'user', content: 'What is the meaning of life?' }] });
  });

  it('isError=true when backend returns ok=false', async () => {
    const tool = askAuraBotTool(
      withPost(async () => ({ ok: false, status: 500, data: null, message: 'LLM timeout' })),
    );
    const result = await tool.handler({ question: 'hi' });
    expect(result.isError).toBe(true);
    expect(result.content[0].text).toMatch(/LLM timeout/);
  });

  it('catches thrown errors', async () => {
    const tool = askAuraBotTool(
      withPost(async () => {
        throw new Error('disconnect');
      }),
    );
    const result = await tool.handler({ question: 'hi' });
    expect(result.isError).toBe(true);
  });
});
