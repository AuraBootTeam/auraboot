import { z } from 'zod';
import type { ApiClient } from '../../../client/api-client.js';
import type { Tool } from '../../registry.js';

const inputSchema = z.object({
  kind: z
    .enum(['list', 'form', 'detail'])
    .optional()
    .describe('Filter by page kind. Note: dashboard uses ab_dashboard table, not /api/pages.'),
  isTemplate: z
    .boolean()
    .optional()
    .describe('Filter to template pages only (true) or instance pages only (false)'),
  keyword: z
    .string()
    .optional()
    .describe('Fuzzy search across pageKey / title / description'),
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
 * `query_page_schemas` — list V2 page schemas in the current tenant.
 *
 * LLMs SHOULD call this BEFORE create_page_schema to avoid pageKey
 * collisions and to discover existing list/form/detail naming patterns.
 *
 * Backed by GET /api/pages (PageSchemaController.java:66-81). Returns
 * PageSchemaListDTO entries WITHOUT blocks (lighter payload). Use the
 * later get_page_schema tool when full block layout is needed.
 *
 * Note: dashboards live in ab_dashboard, not ab_page_schema, and are
 * not surfaced here — that route is reserved for the dashboard tool.
 */
export function queryPageSchemasTool(client: ApiClient): Tool<Params> {
  return {
    name: 'query_page_schemas',
    title: 'Query Page Schemas',
    description:
      'List V2 page schemas (kind=list/form/detail) in the current tenant. Use BEFORE create_page_schema to avoid pageKey collisions. Returns lightweight rows without block bodies.',
    inputSchema,
    annotations: { readOnlyHint: true, idempotentHint: true, openWorldHint: false },
    handler: async (params) => {
      try {
        const query: Record<string, string> = {
          page: '1',
          size: String(params.limit),
        };
        if (params.kind) query.kind = params.kind;
        if (typeof params.isTemplate === 'boolean') {
          query.isTemplate = String(params.isTemplate);
        }
        if (params.keyword) query.keyword = params.keyword;

        const resp = await client.get('/api/pages', query);
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
