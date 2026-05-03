import { z } from 'zod';
import type { ApiClient } from '../../../client/api-client.js';
import { queryNamedQuery } from '../../../client/dynamic-query.js';
import type { Tool } from '../../registry.js';

const inputSchema = z.object({
  queryCode: z.string().describe('Named query code, e.g. crm_dashboard_kpi'),
  params: z
    .record(z.string(), z.string())
    .optional()
    .describe('Additional query parameters'),
  limit: z.number().optional().default(200).describe('Max results'),
});

type Params = z.infer<typeof inputSchema>;

export function runNamedQueryTool(client: ApiClient): Tool<Params> {
  return {
    name: 'run_named_query',
    title: 'Run Named Query',
    description:
      'Execute a NamedQuery for aggregations, dashboards, and analytics. Common NQs: crm_dashboard_kpi, crm_opportunity_pipeline_stats, pm_dashboard_kpi, acp_agent_stats.',
    inputSchema,
    annotations: { readOnlyHint: true, idempotentHint: true, openWorldHint: false },
    handler: async (params) => {
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
  };
}
