import { z } from 'zod';
import type { ApiClient } from '../../../client/api-client.js';
import { queryDynamicList, type FilterItem } from '../../../client/dynamic-query.js';
import type { Tool } from '../../registry.js';

const inputSchema = z.object({
  entityCode: z.string().describe('Model code, e.g. crm_lead, pm_project, crm_account'),
  keyword: z.string().optional().describe('Search keyword'),
  filters: z
    .array(
      z.object({
        fieldName: z.string(),
        operator: z.enum(['EQ', 'neq', 'like', 'GT', 'gte', 'LT', 'lte', 'IN']),
        value: z.string(),
      }),
    )
    .optional()
    .describe('Filter conditions'),
  limit: z.number().optional().default(20).describe('Max results (default 20)'),
  sortField: z.string().optional().describe('Sort by field'),
  sortOrder: z.enum(['asc', 'desc']).optional().default('desc'),
});

type Params = z.infer<typeof inputSchema>;

export function queryEntityTool(client: ApiClient): Tool<Params> {
  return {
    name: 'query_entity',
    title: 'Query Entity Data',
    description:
      'Query any AuraBoot entity (model) with filters. Use model code as entityCode (e.g. crm_lead, pm_project, crm_account).',
    inputSchema,
    annotations: { readOnlyHint: true, idempotentHint: true, openWorldHint: false },
    handler: async (params) => {
      try {
        const filters: FilterItem[] =
          params.filters?.map((f) => ({
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
  };
}
