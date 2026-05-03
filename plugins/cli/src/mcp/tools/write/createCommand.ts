import { z } from 'zod';
import type { ApiClient, ApiResponse } from '../../../client/api-client.js';
import { toolErrorFromBackend } from '../../errors.js';
import type { Tool } from '../../registry.js';

/**
 * BindingRule fields mirror `com.auraboot.framework.meta.dto.BindingRuleDTO`
 * (the controller-side DTO, NOT the plugin-import DTO whose `config` is a
 * Map). At the API level the rule's `config` column is a String — typically
 * a small JSON blob — so we type it as `string` here. LLMs that want to
 * encode structured config should JSON.stringify it before passing.
 */
const bindingRuleSchema = z.object({
  ruleType: z
    .string()
    .min(1)
    .describe('e.g. FIELD_MAPPING / EXPRESSION / HANDLER / EVENT / VALIDATION'),
  expression: z.string().optional(),
  targetModel: z.string().optional(),
  targetField: z.string().optional(),
  sourceField: z.string().optional(),
  handlerClass: z.string().optional(),
  eventType: z.string().optional(),
  config: z.string().optional().describe('Free-form JSON string (rule-specific)'),
  sequence: z.number().int().optional().default(0),
  enabled: z.boolean().optional().default(true),
});

const inputSchema = z.object({
  code: z
    .string()
    .min(1)
    .describe(
      'Unique command code in the tenant. Convention: <model_code>.<verb>, e.g. crm_lead.assign, hr_leave_request.approve.',
    ),
  displayName: z.string().optional(),
  description: z.string().optional(),
  modelCode: z
    .string()
    .min(1)
    .describe('Target model code. Must already exist — call query_existing_models to confirm.'),
  inputSchema: z
    .string()
    .optional()
    .describe('Optional JSON Schema string used by the SCHEMA_VALIDATE pipeline phase.'),
  targetModels: z.string().optional(),
  executionConfig: z
    .string()
    .optional()
    .describe(
      'JSON-encoded ExecutionConfig DSL covering the 80% case (auto-set / state transitions / preActions / postActions). Use describe_command_pipeline first.',
    ),
  cmdRiskLevel: z
    .enum(['L0', 'L1', 'L2', 'L3', 'L4'])
    .optional()
    .describe('Risk level for ENTITLEMENT_CHECK + audit grouping. Defaults server-side.'),
  pluginPid: z.string().optional(),
  extension: z.string().optional().describe('Free-form JSON string for plugin-specific metadata.'),
  bindingRules: z
    .array(bindingRuleSchema)
    .optional()
    .describe(
      'Optional. Each rule is created via a SEPARATE POST /binding-rules call AFTER the command exists; if any rule fails the entire command is rolled back via DELETE.',
    ),
  dryRun: z
    .boolean()
    .optional()
    .default(false)
    .describe('When true, validate the input only — DO NOT persist. Returns { valid, wouldCreate }.'),
});

type Params = z.infer<typeof inputSchema>;

interface CommandDefinitionCreateResponse {
  pid?: string;
  code?: string;
  // backend may include extra fields; we only require pid for the rollback path.
  [k: string]: unknown;
}

/**
 * `create_command` — POST /api/meta/commands then for each bindingRule
 * POST /api/meta/commands/{pid}/binding-rules. Failures during the binding
 * step trigger a best-effort DELETE /api/meta/commands/{pid} so the LLM
 * can re-attempt cleanly without leaving an orphaned command behind.
 *
 * The two-step shape mirrors how plugin-import handles bindings (the
 * commands.json file CAN'T inline bindingRules and the runtime resolves
 * each via the dedicated endpoint — see the BindingRules-separate-file
 * memory). Doing the same here keeps the LLM's mental model aligned with
 * how plugin packages declare commands.
 */
export function createCommandTool(client: ApiClient): Tool<Params> {
  return {
    name: 'create_command',
    title: 'Create AuraBoot Command',
    description:
      'Create a Command (write operation that runs through the 20+4 stage Command Pipeline). Optionally attach BindingRules in one orchestrated call. CALL describe_command_pipeline first to know which phases run automatically — do not duplicate validation/audit/change-tracking logic in your handler. CALL query_existing_models to confirm modelCode exists.',
    inputSchema,
    annotations: { destructiveHint: true, idempotentHint: false, openWorldHint: false },
    handler: async (params) => {
      const { dryRun, bindingRules = [], ...commandBody } = params;

      if (dryRun) {
        return {
          content: [
            {
              type: 'text' as const,
              text: JSON.stringify(
                {
                  valid: true,
                  dryRun: true,
                  wouldCreate: { command: commandBody, bindingRules },
                  note:
                    'Input passed local zod validation. Backend uniqueness of command code, modelCode existence, and bindingRule semantics are NOT checked in dry-run mode.',
                },
                null,
                2,
              ),
            },
          ],
        };
      }

      // ── Step 1: create the command ────────────────────────────────────
      let createResp: ApiResponse<CommandDefinitionCreateResponse>;
      try {
        createResp = await client.post<CommandDefinitionCreateResponse>(
          '/api/meta/commands',
          commandBody,
        );
      } catch (e) {
        return {
          content: [{ type: 'text' as const, text: `Error: ${(e as Error).message}` }],
          isError: true,
        };
      }

      if (!createResp.ok) {
        return toolErrorFromBackend(createResp, { step: 'create_command' });
      }

      const commandPid = createResp.data?.pid;
      if (bindingRules.length === 0 || !commandPid) {
        // No bindings or no pid back — return whatever the create gave us.
        return {
          content: [{ type: 'text' as const, text: JSON.stringify(createResp.data, null, 2) }],
        };
      }

      // ── Step 2: create binding rules sequentially ────────────────────
      const created: unknown[] = [];
      for (let i = 0; i < bindingRules.length; i++) {
        const rule = bindingRules[i];
        let ruleResp: ApiResponse<unknown>;
        try {
          ruleResp = await client.post(
            `/api/meta/commands/${encodeURIComponent(commandPid)}/binding-rules`,
            rule,
          );
        } catch (e) {
          return rollback(client, commandPid, i, created, (e as Error).message);
        }

        if (!ruleResp.ok) {
          const message = ruleResp.message ?? `Status ${ruleResp.status}`;
          return rollback(client, commandPid, i, created, message);
        }
        created.push(ruleResp.data);
      }

      return {
        content: [
          {
            type: 'text' as const,
            text: JSON.stringify(
              { command: createResp.data, bindingRules: created },
              null,
              2,
            ),
          },
        ],
      };
    },
  };
}

/**
 * Best-effort rollback: DELETE the freshly created command so the LLM can
 * retry from a clean slate. Whether the delete succeeds or not, surface an
 * explicit `isError` payload describing which rule failed and the cleanup
 * outcome — never silently leave behind a half-created command.
 */
async function rollback(
  client: ApiClient,
  commandPid: string,
  failedIndex: number,
  created: unknown[],
  errorMessage: string,
) {
  let rollbackOk = false;
  let rollbackError: string | undefined;
  try {
    const del = await client.delete(`/api/meta/commands/${encodeURIComponent(commandPid)}`);
    rollbackOk = del.ok;
    if (!del.ok) {
      rollbackError = del.message ?? `Status ${del.status}`;
    }
  } catch (e) {
    rollbackError = (e as Error).message;
  }

  return {
    content: [
      {
        type: 'text' as const,
        text: JSON.stringify(
          {
            step: 'create_binding_rule',
            failedAtIndex: failedIndex,
            createdSoFar: created.length,
            error: errorMessage,
            rollback: rollbackOk
              ? { status: 'ok', deletedCommandPid: commandPid }
              : {
                  status: 'failed',
                  commandPid,
                  error: rollbackError,
                  manualCleanupHint: `Run: DELETE /api/meta/commands/${commandPid}`,
                },
          },
          null,
          2,
        ),
      },
    ],
    isError: true,
  };
}
