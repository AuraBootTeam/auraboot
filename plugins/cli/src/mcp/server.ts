import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import { z } from 'zod';
import { ApiClient } from '../client/api-client.js';
import { queryDynamicList, queryNamedQuery, type FilterItem } from '../client/dynamic-query.js';

/**
 * Aura MCP Server — exposes AuraBoot data as tools for AI agents.
 *
 * Usage:
 *   aura mcp-server              # start stdio server
 *
 * Claude Code config (~/.claude/mcp_servers.json):
 *   { "aura": { "command": "aura", "args": ["mcp-server"] } }
 */
export async function startMcpServer(options: { token?: string; env?: string }): Promise<void> {
  const client = new ApiClient(options);

  const server = new McpServer({
    name: 'aura',
    version: '2.0.0',
  });

  // ──────────────────────────────────────────────────────────────────────
  // Tool: query_entity — Generic Dynamic CRUD query
  // ──────────────────────────────────────────────────────────────────────
  server.registerTool(
    'query_entity',
    {
      title: 'Query Entity Data',
      description: 'Query any AuraBoot entity (model) with filters. Use model code as entityCode (e.g. crm_lead, pm_project, crm_account).',
      inputSchema: z.object({
        entityCode: z.string().describe('Model code, e.g. crm_lead, pm_project, crm_account'),
        keyword: z.string().optional().describe('Search keyword'),
        filters: z.array(z.object({
          fieldName: z.string(),
          operator: z.enum(['EQ', 'neq', 'like', 'GT', 'gte', 'LT', 'lte', 'IN']),
          value: z.string(),
        })).optional().describe('Filter conditions'),
        limit: z.number().optional().default(20).describe('Max results (default 20)'),
        sortField: z.string().optional().describe('Sort by field'),
        sortOrder: z.enum(['asc', 'desc']).optional().default('desc'),
      }),
    },
    async (params) => {
      try {
        const filters: FilterItem[] = params.filters?.map(f => ({
          fieldName: f.fieldName,
          operator: f.operator as FilterItem['operator'],
          value: f.value,
        })) || [];

        const records = await queryDynamicList(client, params.entityCode, {
          pageSize: params.limit,
          keyword: params.keyword,
          filters,
          sortField: params.sortField,
          sortOrder: params.sortOrder,
        });

        return {
          content: [{ type: 'text' as const, text: JSON.stringify(records, null, 2) }],
        };
      } catch (e) {
        return {
          content: [{ type: 'text' as const, text: `Error: ${(e as Error).message}` }],
          isError: true,
        };
      }
    },
  );

  // ──────────────────────────────────────────────────────────────────────
  // Tool: run_named_query — Execute a NamedQuery
  // ──────────────────────────────────────────────────────────────────────
  server.registerTool(
    'run_named_query',
    {
      title: 'Run Named Query',
      description: 'Execute a NamedQuery for aggregations, dashboards, and analytics. Common NQs: crm_dashboard_kpi, crm_opportunity_pipeline_stats, pm_dashboard_kpi, acp_agent_stats.',
      inputSchema: z.object({
        queryCode: z.string().describe('Named query code, e.g. crm_dashboard_kpi'),
        params: z.record(z.string(), z.string()).optional().describe('Additional query parameters'),
        limit: z.number().optional().default(200).describe('Max results'),
      }),
    },
    async (params) => {
      try {
        const records = await queryNamedQuery(client, params.queryCode, {
          maxItems: String(params.limit),
          ...params.params,
        });

        return {
          content: [{ type: 'text' as const, text: JSON.stringify(records, null, 2) }],
        };
      } catch (e) {
        return {
          content: [{ type: 'text' as const, text: `Error: ${(e as Error).message}` }],
          isError: true,
        };
      }
    },
  );

  // ──────────────────────────────────────────────────────────────────────
  // Tool: list_agents — List AI agents
  // ──────────────────────────────────────────────────────────────────────
  server.registerTool(
    'list_agents',
    {
      title: 'List AI Agents',
      description: 'List all configured AI agents with their status, model, and run statistics.',
      inputSchema: z.object({}),
    },
    async () => {
      try {
        const records = await queryNamedQuery(client, 'acp_agent_stats');
        return {
          content: [{ type: 'text' as const, text: JSON.stringify(records, null, 2) }],
        };
      } catch (e) {
        return {
          content: [{ type: 'text' as const, text: `Error: ${(e as Error).message}` }],
          isError: true,
        };
      }
    },
  );

  // ──────────────────────────────────────────────────────────────────────
  // Tool: list_tools — List agent tools
  // ──────────────────────────────────────────────────────────────────────
  server.registerTool(
    'list_tools',
    {
      title: 'List Agent Tools',
      description: 'List all active agent tools with their type and source.',
      inputSchema: z.object({}),
    },
    async () => {
      try {
        const records = await queryNamedQuery(client, 'acp_agent_tools_active');
        return {
          content: [{ type: 'text' as const, text: JSON.stringify(records, null, 2) }],
        };
      } catch (e) {
        return {
          content: [{ type: 'text' as const, text: `Error: ${(e as Error).message}` }],
          isError: true,
        };
      }
    },
  );

  // ──────────────────────────────────────────────────────────────────────
  // Tool: dispatch_agent — Dispatch a task to an agent
  // ──────────────────────────────────────────────────────────────────────
  server.registerTool(
    'dispatch_agent',
    {
      title: 'Dispatch Agent Task',
      description: 'Dispatch a task to an AI agent for execution. Requires Professional license.',
      inputSchema: z.object({
        taskPid: z.string().describe('Task PID to dispatch'),
      }),
    },
    async (params) => {
      try {
        const resp = await client.post('/api/agent/dispatch', { taskPid: params.taskPid });
        if (!resp.ok) {
          return {
            content: [{ type: 'text' as const, text: `Dispatch failed: ${resp.message}` }],
            isError: true,
          };
        }
        return {
          content: [{ type: 'text' as const, text: JSON.stringify(resp.data, null, 2) }],
        };
      } catch (e) {
        return {
          content: [{ type: 'text' as const, text: `Error: ${(e as Error).message}` }],
          isError: true,
        };
      }
    },
  );

  // ──────────────────────────────────────────────────────────────────────
  // Tool: ask_aurabot — Ask AuraBot a question
  // ──────────────────────────────────────────────────────────────────────
  server.registerTool(
    'ask_aurabot',
    {
      title: 'Ask AuraBot',
      description: 'Ask the AuraBot AI assistant a question about company data. Returns AI-generated response.',
      inputSchema: z.object({
        question: z.string().describe('Natural language question'),
      }),
    },
    async (params) => {
      try {
        const resp = await client.post('/api/ai/aurabot/chat/stream', {
          messages: [{ role: 'user', content: params.question }],
        });
        // For MCP we don't stream — just return whatever we get
        return {
          content: [{ type: 'text' as const, text: resp.ok ? JSON.stringify(resp.data) : `Error: ${resp.message}` }],
          isError: !resp.ok,
        };
      } catch (e) {
        return {
          content: [{ type: 'text' as const, text: `Error: ${(e as Error).message}` }],
          isError: true,
        };
      }
    },
  );

  // ──────────────────────────────────────────────────────────────────────
  // Connect via stdio transport
  // ──────────────────────────────────────────────────────────────────────
  const transport = new StdioServerTransport();
  await server.connect(transport);
  console.error('[aura-mcp] Server ready — 6 tools available');
}
