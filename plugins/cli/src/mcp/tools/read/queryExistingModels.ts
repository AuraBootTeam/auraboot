import { z } from 'zod';
import type { ApiClient } from '../../../client/api-client.js';
import type { Tool } from '../../registry.js';

const inputSchema = z.object({
  keyword: z
    .string()
    .optional()
    .describe('Fuzzy search across code / displayName / description'),
  modelType: z
    .enum(['business', 'system'])
    .optional()
    .describe('Filter by model type'),
  limit: z
    .number()
    .int()
    .min(1)
    .max(200)
    .optional()
    .default(50)
    .describe('Max results (1-200, default 50)'),
});

type Params = z.infer<typeof inputSchema>;

/**
 * `query_existing_models` — list models in the current tenant.
 *
 * LLMs SHOULD call this BEFORE create_model to avoid code collisions and
 * to discover the namespace conventions already in use (e.g. crm_*, hr_*).
 *
 * Backed by GET /api/meta/models. Always queries currentOnly=true to skip
 * archived versions. Uses page/size (NOT pageNum/pageSize) per controller
 * signature ModelController.java:213-224.
 */
export function queryExistingModelsTool(client: ApiClient): Tool<Params> {
  return {
    name: 'query_existing_models',
    title: 'Query Existing Models',
    description:
      'List models defined in the current tenant. Use BEFORE create_model to avoid code collisions and to discover existing namespace conventions (e.g. crm_*, hr_*). Returns code, displayName, modelType, status, and (when available) field count.',
    inputSchema,
    annotations: { readOnlyHint: true, idempotentHint: true, openWorldHint: false },
    handler: async (params) => {
      try {
        const query: Record<string, string> = {
          page: '1',
          size: String(params.limit),
          currentOnly: 'true',
        };
        if (params.keyword) query.keyword = params.keyword;
        if (params.modelType) query.modelType = params.modelType;

        const resp = await client.get('/api/meta/models', query);
        if (!resp.ok) {
          return {
            content: [{ type: 'text' as const, text: `Error: ${resp.message ?? resp.status}` }],
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
