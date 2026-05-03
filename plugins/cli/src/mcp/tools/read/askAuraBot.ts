import { z } from 'zod';
import type { ApiClient } from '../../../client/api-client.js';
import type { Tool } from '../../registry.js';

const inputSchema = z.object({
  question: z.string().describe('Natural language question'),
});

type Params = z.infer<typeof inputSchema>;

export function askAuraBotTool(client: ApiClient): Tool<Params> {
  return {
    name: 'ask_aurabot',
    title: 'Ask AuraBot',
    description:
      'Ask the AuraBot AI assistant a question about company data. Returns AI-generated response.',
    inputSchema,
    annotations: { readOnlyHint: true, openWorldHint: true },
    handler: async (params) => {
      try {
        const resp = await client.post('/api/ai/aurabot/chat/stream', {
          messages: [{ role: 'user', content: params.question }],
        });
        // For MCP we don't stream — just return whatever we get
        return {
          content: [
            {
              type: 'text' as const,
              text: resp.ok ? JSON.stringify(resp.data) : `Error: ${resp.message}`,
            },
          ],
          isError: !resp.ok,
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
