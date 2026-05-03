import { z } from 'zod';
import type { ApiClient } from '../../../client/api-client.js';
import { toolErrorFromBackend } from '../../errors.js';
import type { Tool } from '../../registry.js';

/**
 * The V2 flat layout primitive. We narrow the backend's free-form
 * `Map<String,Object>` into a discriminated union so LLMs cannot accidentally
 * emit legacy nested-areas / dslSchema shapes that the runtime no longer
 * understands.
 */
const layoutSchema = z.discriminatedUnion('type', [
  z.object({ type: z.literal('stack') }).passthrough(),
  z
    .object({
      type: z.literal('grid'),
      cols: z.number().int().min(1).max(24).optional(),
    })
    .passthrough(),
]);

/**
 * Each block is a freeform JSON object on the backend, but it MUST carry a
 * `blockType` matching the closed registry. Anything else is allowed via
 * `passthrough()` so widget/config-specific fields flow through unchanged.
 */
const blockSchema = z
  .object({
    blockType: z.enum([
      'table',
      'filters',
      'toolbar',
      'form-section',
      'chart',
      'tabs',
      'sub-table',
      'stat-card',
      'custom',
    ]),
    blockId: z.string().optional(),
  })
  .passthrough();

const inputSchema = z.object({
  pageKey: z
    .string()
    .min(2, 'pageKey must be at least 2 characters.')
    .max(100, 'pageKey must be at most 100 characters.')
    .regex(/^[a-zA-Z][a-zA-Z0-9_-]*$/, {
      message:
        'pageKey must start with a letter; letters, digits, underscore, hyphen only. Convention: <model_code>_<kind>, e.g. crm_lead_list / hr_leave_request_form.',
    }),
  modelCode: z.string().max(100).optional(),
  name: z
    .string()
    .min(2)
    .max(100)
    .describe('Internal page name (2-100 chars). Often equal to pageKey or its humanized form.'),
  title: z
    .string()
    .min(1)
    .max(200)
    .describe('User-visible page title (1-200 chars). May be a $i18n: prefixed key.'),
  description: z.string().max(1000).optional(),
  kind: z
    .enum(['list', 'form', 'detail'])
    .describe(
      'V2 flat kind. ONLY list / form / detail are accepted by /api/pages — dashboards live in ab_dashboard via the Dashboard Designer, and the legacy composite kind has been removed.',
    ),
  profile: z.enum(['admin', 'report']).optional(),
  layout: layoutSchema
    .optional()
    .describe('Layout discriminator. Defaults to {type:"stack"} server-side when omitted.'),
  blocks: z
    .array(blockSchema)
    .min(1, 'A V2 page must contain at least one block.')
    .describe(
      'Ordered block list. Required for all kinds. Use describe_command_pipeline + query_dsl_capabilities to discover supported blockType values.',
    ),
  metaInfo: z.record(z.string(), z.unknown()).optional(),
  isTemplate: z.boolean().optional().default(false),
  templateCategory: z.string().max(50).optional(),
  sortWeight: z.number().int().min(0).max(9999).optional().default(0),
  tags: z.record(z.string(), z.unknown()).optional(),
  semver: z
    .string()
    .regex(
      /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-((?:0|[1-9]\d*|\d*[a-zA-Z-][0-9a-zA-Z-]*)(?:\.(?:0|[1-9]\d*|\d*[a-zA-Z-][0-9a-zA-Z-]*))*))?(?:\+([0-9a-zA-Z-]+(?:\.[0-9a-zA-Z-]+)*))?$/,
      'semver must follow SemVer 2.0.0 (e.g. 1.0.0, 2.1.3-beta.1).',
    )
    .optional(),
  extension: z.record(z.string(), z.unknown()).optional(),
  pluginPid: z.string().optional(),
  dryRun: z
    .boolean()
    .optional()
    .default(false)
    .describe('When true, validate the input only — DO NOT persist. Returns { valid, wouldCreate }.'),
});

type Params = z.infer<typeof inputSchema>;

/**
 * `create_page_schema` — POST /api/pages
 *
 * Creates a V2 flat page schema (kind=list|form|detail). The MCP tool adds:
 *   - destructiveHint: true so MCP clients prompt the user before executing
 *   - dryRun=true short-circuit (zod validate, no HTTP)
 *   - structured isError on conflict ("already exists") so the LLM can rename
 *
 * Removed concepts (`pageType`, `pageCategory`, `dslSchema`, `kind=dashboard`,
 * `kind=composite`) are rejected at the zod layer — the handler is never
 * reached with these values, mirroring AGENTS.md's V2 hard rule.
 */
export function createPageSchemaTool(client: ApiClient): Tool<Params> {
  return {
    name: 'create_page_schema',
    title: 'Create AuraBoot Page Schema (V2 flat)',
    description:
      'Create a V2 page (kind=list|form|detail) in the current tenant. CALL query_existing_models to confirm modelCode, query_page_schemas to avoid pageKey collisions, and query_dsl_capabilities to pick valid blockType / widgetType. NEVER use removed concepts (pageType, dslSchema, kind=dashboard, kind=composite).',
    inputSchema,
    annotations: { destructiveHint: true, idempotentHint: false, openWorldHint: false },
    handler: async (params) => {
      // Build the backend body — strip the MCP-only `dryRun` flag.
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
                    'Input passed local zod validation. Backend pageKey uniqueness, permissions, and blockType registry validity are NOT checked in dry-run mode.',
                },
                null,
                2,
              ),
            },
          ],
        };
      }

      try {
        const resp = await client.post('/api/pages', body);
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
