import { z } from 'zod';
import type { ApiClient } from '../../../client/api-client.js';
import { toolErrorFromBackend } from '../../errors.js';
import type { Tool } from '../../registry.js';

/**
 * Mirrors the platform `MetaModelCreateRequest` DTO with the same regex
 * the backend uses for `code`. Optional capabilities + fields blocks are
 * accepted so the wizard / virtual-model flows are reachable from the LLM.
 */
const fieldSchema = z
  .object({
    code: z
      .string()
      .regex(/^[a-zA-Z][a-zA-Z0-9_]*$/, {
        message: 'Field code must start with a letter; letters, digits, underscore only.',
      }),
    displayName: z.string().optional(),
    name: z.string().optional(),
    description: z.string().optional(),
    dataType: z.string().describe('e.g. string, integer, decimal, boolean, date, datetime, json, enum, reference, computed, ai_text, money'),
    columnName: z.string().optional(),
    required: z.boolean().optional(),
    sortable: z.boolean().optional(),
    filterable: z.boolean().optional(),
    searchable: z.boolean().optional(),
    exportable: z.boolean().optional(),
    extension: z.record(z.string(), z.unknown()).optional(),
  })
  .passthrough();

const capabilitiesSchema = z
  .object({
    list: z.boolean().optional(),
    detail: z.boolean().optional(),
    create: z.boolean().optional(),
    update: z.boolean().optional(),
    delete: z.boolean().optional(),
    bulkDelete: z.boolean().optional(),
    export: z.boolean().optional(),
    sort: z.boolean().optional(),
    filter: z.boolean().optional(),
    paginate: z.boolean().optional(),
    sortableFields: z.array(z.string()).optional(),
    filterableFields: z.array(z.string()).optional(),
    detailKeyField: z.string().optional(),
  })
  .passthrough();

const inputSchema = z.object({
  code: z
    .string()
    .regex(/^[a-zA-Z][a-zA-Z0-9_]*$/, {
      message: 'Model code must start with a letter; letters, digits, underscore only.',
    })
    .describe('Unique model code in the tenant. Convention: <plugin_namespace>_<entity>, e.g. crm_lead, hr_leave_request.'),
  displayName: z
    .string()
    .min(1)
    .describe('Human-readable label. Plain string (use $i18n: prefix for i18n keys; LocalizedText objects unsupported here).'),
  description: z.string().optional(),
  modelType: z
    .enum(['entity', 'view', 'aggregate'])
    .optional()
    .default('entity')
    .describe('entity = physical mt_<code> table; view = NamedQuery-backed; aggregate = cross-model join'),
  modelCategory: z
    .enum(['DOCUMENT', 'MASTER', 'TRANSACTION', 'ACTIVITY', 'REFERENCE', 'ENTITY'])
    .optional(),
  tableName: z.string().optional().describe('Override mt_<code> auto-naming. Rare — leave empty.'),
  sourceType: z
    .enum(['physical', 'namedQuery', 'endpoint', 'sqlView'])
    .optional()
    .describe('Defaults to physical (creates mt_<code> table). Non-physical types require sourceRef.'),
  sourceRef: z
    .string()
    .optional()
    .describe('Required when sourceType != physical: namedQuery code / connector endpoint / sql view name.'),
  primaryKey: z.string().optional().describe('Field code used as list rowKey + default detailKeyField.'),
  capabilities: capabilitiesSchema.optional(),
  fields: z.array(fieldSchema).optional(),
  extension: z.record(z.string(), z.unknown()).optional(),
  pluginPid: z.string().optional(),
  versionNote: z.string().optional(),
  autoPublish: z.boolean().optional().default(false),
  dryRun: z
    .boolean()
    .optional()
    .default(false)
    .describe('When true, validate the input only — DO NOT persist. Returns { valid, wouldCreate }.'),
});

type Params = z.infer<typeof inputSchema>;

/**
 * `create_model` — POST /api/meta/models
 *
 * The post-create flow forks server-side based on payload shape:
 *   - Pure physical model → metaModelService.create() → mt_<code> DDL
 *   - Virtual-wizard payload (sourceType / capabilities / fields present) →
 *     additional metaModelService.saveDefinition() pass to persist the
 *     virtual config alongside the row created by the legacy create() path.
 *
 * Both paths are delegated to the backend; the MCP tool is a thin pass-through
 * that adds:
 *   - destructiveHint: true so MCP clients prompt the user before executing
 *   - dryRun=true short-circuit (zod validate, no HTTP) for LLM iteration
 *   - structured isError responses on 409 conflict / 422 validation so the
 *     LLM can re-prompt with a different code or fix the violated field.
 */
export function createModelTool(client: ApiClient): Tool<Params> {
  return {
    name: 'create_model',
    title: 'Create AuraBoot Model',
    description:
      'Create a new model (entity / view / aggregate) in the current tenant. The code MUST be unique. CALL query_existing_models FIRST to avoid collisions, and query_dsl_capabilities to pick valid dataTypes. Use dryRun=true to validate without persisting.',
    inputSchema,
    annotations: { destructiveHint: true, idempotentHint: false, openWorldHint: false },
    handler: async (params) => {
      // Build the backend request body — strip our MCP-only `dryRun` flag.
      const { dryRun, ...body } = params;

      if (dryRun) {
        return {
          content: [
            {
              type: 'text' as const,
              text: JSON.stringify(
                {
                  valid: true,
                  dryRun: true,
                  wouldCreate: body,
                  note:
                    'Input passed local zod validation. Backend uniqueness, permissions, and DDL feasibility are NOT checked in dry-run mode.',
                },
                null,
                2,
              ),
            },
          ],
        };
      }

      try {
        const resp = await client.post('/api/meta/models', body);
        if (!resp.ok) {
          return toolErrorFromBackend(resp);
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
