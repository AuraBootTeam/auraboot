import { z } from 'zod';
import type { ApiClient } from '../../../client/api-client.js';
import type { Tool } from '../../registry.js';

const inputSchema = z.object({
  taskPid: z.string().describe('Task PID to dispatch'),
});

type Params = z.infer<typeof inputSchema>;

export function dispatchAgentTool(client: ApiClient): Tool<Params> {
  return {
    name: 'dispatch_agent',
    title: 'Dispatch Agent Task',
    description: 'Dispatch a task to an AI agent for execution. Requires Professional license.',
    inputSchema,
    // dispatch_agent triggers backend orchestration but is idempotent at the
    // taskPid level — re-dispatching the same task is a no-op.
    annotations: { idempotentHint: true, openWorldHint: true },
    handler: async (params) => {
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
  };
}
