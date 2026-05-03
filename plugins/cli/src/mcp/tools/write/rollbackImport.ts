import { z } from 'zod';
import type { ApiClient } from '../../../client/api-client.js';
import { toolErrorFromBackend } from '../../errors.js';
import type { Tool } from '../../registry.js';

const inputSchema = z.object({
  importId: z
    .string()
    .min(1)
    .describe(
      'Import ID returned by a successful import_plugin call (look in the response under `importId`). Window: 24h after the import.',
    ),
});

type Params = z.infer<typeof inputSchema>;

/**
 * `rollback_import` — POST /api/plugins/import/{importId}/rollback
 *
 * Best-effort cleanup tool for an import that succeeded but turned out to
 * be wrong. The backend has a 24h rollback window; outside that window the
 * call returns success=false and the LLM should surface the timeout to the
 * user rather than retrying.
 *
 * Marked idempotent: a repeated rollback against an already-rolled-back
 * import is a no-op server-side, so MCP clients can safely retry on
 * transient network errors.
 */
export function rollbackImportTool(client: ApiClient): Tool<Params> {
  return {
    name: 'rollback_import',
    title: 'Rollback AuraBoot Plugin Import',
    description:
      'Undo a successful import_plugin run. Pass the importId returned by the prior import. The 24h rollback window is enforced server-side; expired imports report success=false in the result.',
    inputSchema,
    annotations: { destructiveHint: true, idempotentHint: true, openWorldHint: false },
    handler: async (params) => {
      try {
        const resp = await client.post(
          `/api/plugins/import/${encodeURIComponent(params.importId)}/rollback`,
        );

        if (!resp.ok) {
          // Inject importId so the LLM can echo it back in the user-facing
          // explanation ("Rollback for import imp-1 failed: …").
          const base = toolErrorFromBackend(resp);
          const parsed = JSON.parse(base.content[0].text) as Record<string, unknown>;
          parsed.importId = params.importId;
          base.content[0].text = JSON.stringify(parsed, null, 2);
          return base;
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
