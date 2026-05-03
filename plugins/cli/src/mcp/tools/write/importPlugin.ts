import { z } from 'zod';
import type { ApiClient } from '../../../client/api-client.js';
import { toolErrorFromBackend } from '../../errors.js';
import type { Tool } from '../../registry.js';

/**
 * `import_plugin` is the most powerful write tool exposed by the MCP server:
 * a single call can persist dozens of models / fields / commands / pages /
 * permissions / roles / menus / dictionaries in one transactional batch.
 *
 * Two safety nets on top of the standard write-tool pattern:
 *
 *   1. **`dryRun` defaults to `true`** (NOT `false` like the other write
 *      tools). LLMs that just want to "see what this manifest does" get
 *      preview output by default. To actually persist, the caller must
 *      explicitly pass `dryRun: false`.
 *
 *   2. The dry-run response carries the backend's full
 *      `{ valid, errors[], conflicts[], dependencies }` map verbatim so the
 *      LLM can self-correct before the destructive call.
 *
 * Conflict resolution:
 *   - `conflictStrategy: 'error'` (DEFAULT) — abort on first conflict
 *   - `conflictStrategy: 'overwrite'` — replace existing resources
 *   - `conflictStrategy: 'skip'` — keep existing, skip new
 *
 * Use `rollback_import` (separate tool) to undo a successful execute call.
 */
const inputSchema = z.object({
  manifest: z
    .record(z.string(), z.unknown())
    .describe(
      'Full PluginManifestExtended object (pluginId, namespace, version, models, fields, commands, pages, permissions, roles, menus, dictionaries, ...). See plugins/schemas/plugin-manifest.schema.json for the complete shape.',
    ),
  dryRun: z
    .boolean()
    .optional()
    .default(true)
    .describe(
      'DEFAULTS to true (preview only — DOES NOT persist). Pass false to actually import.',
    ),
  conflictStrategy: z
    .enum(['error', 'overwrite', 'skip'])
    .optional()
    .default('error')
    .describe('How to handle existing resources with the same code.'),
  autoDeployProcesses: z.boolean().optional().default(true),
  autoPublishModels: z.boolean().optional().default(true),
  autoPublishFields: z.boolean().optional().default(true),
  autoPublishCommands: z.boolean().optional().default(true),
  autoPublishPages: z.boolean().optional().default(true),
});

type Params = z.infer<typeof inputSchema>;

export function importPluginTool(client: ApiClient): Tool<Params> {
  return {
    name: 'import_plugin',
    title: 'Import AuraBoot Plugin Manifest',
    description:
      'Atomically import a full PluginManifestExtended (models + fields + commands + pages + permissions + roles + menus + dicts + i18n + processes) for a complete module. dryRun DEFAULTS to true — pass dryRun=false explicitly to persist. Always inspect the dry-run output (errors[], conflicts[], dependencies) BEFORE executing. Use rollback_import (separate tool) to undo a successful run within 24h.',
    inputSchema,
    annotations: { destructiveHint: true, idempotentHint: false, openWorldHint: false },
    handler: async (params) => {
      const { manifest, dryRun, conflictStrategy, ...flags } = params;

      const query: Record<string, string> = {
        dryRun: String(dryRun),
        conflictStrategy: conflictStrategy ?? 'error',
        autoDeployProcesses: String(flags.autoDeployProcesses ?? true),
        autoPublishModels: String(flags.autoPublishModels ?? true),
        autoPublishFields: String(flags.autoPublishFields ?? true),
        autoPublishCommands: String(flags.autoPublishCommands ?? true),
        autoPublishPages: String(flags.autoPublishPages ?? true),
      };

      try {
        const resp = await client.post(
          '/api/plugins/import/execute-direct',
          manifest,
          query,
        );

        if (!resp.ok) {
          // Surface the dryRun flag too so the LLM knows whether the failed
          // call was the preview pass or the destructive one.
          const base = toolErrorFromBackend(resp);
          const text = base.content[0].text;
          const parsed = JSON.parse(text) as Record<string, unknown>;
          parsed.dryRun = dryRun;
          base.content[0].text = JSON.stringify(parsed, null, 2);
          return base;
        }

        // Dry-run response is { dryRun, valid, errors, conflicts, dependencies }.
        // Execute response is ImportExecuteResult { importId, pluginPid, success,
        // status, errorMessage, warnings, resourceCounts, ... }.
        // Pass through verbatim — the LLM can read either shape.
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
