import { z } from 'zod';
import type { ApiClient } from '../../../client/api-client.js';
import { queryNamedQuery } from '../../../client/dynamic-query.js';
import type { Tool } from '../../registry.js';

const inputSchema = z.object({});

type Params = z.infer<typeof inputSchema>;

export function listAgentsTool(client: ApiClient): Tool<Params> {
  return {
    name: 'list_agents',
    title: 'List AI Agents',
    description: 'List all configured AI agents with their status, model, and run statistics.',
    inputSchema,
    annotations: { readOnlyHint: true, idempotentHint: true, openWorldHint: false },
    handler: async () => {
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
  };
}
