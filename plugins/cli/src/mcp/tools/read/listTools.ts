import { z } from 'zod';
import type { ApiClient } from '../../../client/api-client.js';
import { queryNamedQuery } from '../../../client/dynamic-query.js';
import type { Tool } from '../../registry.js';

const inputSchema = z.object({});

type Params = z.infer<typeof inputSchema>;

export function listToolsTool(client: ApiClient): Tool<Params> {
  return {
    name: 'list_tools',
    title: 'List Agent Tools',
    description: 'List all active agent tools with their type and source.',
    inputSchema,
    annotations: { readOnlyHint: true, idempotentHint: true, openWorldHint: false },
    handler: async () => {
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
  };
}
