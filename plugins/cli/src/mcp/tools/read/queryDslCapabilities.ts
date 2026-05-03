import { z } from 'zod';
import type { ApiClient } from '../../../client/api-client.js';
import type { Tool } from '../../registry.js';

const inputSchema = z.object({});

type Params = z.infer<typeof inputSchema>;

/**
 * `query_dsl_capabilities` — returns the canonical map of supported kinds,
 * blockTypes, widgetTypes, dataTypes, operators, and removed/deprecated
 * concepts. LLMs SHOULD call this BEFORE generating any model or page
 * schema so they target the current DSL contract instead of stale memory.
 *
 * Backed by GET /api/dsl/registry which already aggregates:
 *   - enums:        BlockType / WidgetType / DataType / Operator / ChartType ...
 *   - extensions:   commandHandlers / sideEffectHandlers / automationActions ...
 *   - mappings:     dataTypeDefaults
 *
 * No new backend endpoint is required.
 */
export function queryDslCapabilitiesTool(client: ApiClient): Tool<Params> {
  return {
    name: 'query_dsl_capabilities',
    title: 'Query AuraBoot DSL Capabilities',
    description:
      'Returns the canonical map of supported kinds, blockTypes, widgetTypes, dataTypes, operators, command handlers, side-effect handlers, automation actions, and chart types. CALL FIRST before generating any model/page schema so AI output stays aligned with the current DSL contract.',
    inputSchema,
    annotations: { readOnlyHint: true, idempotentHint: true, openWorldHint: false },
    handler: async () => {
      try {
        const resp = await client.get('/api/dsl/registry');
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
