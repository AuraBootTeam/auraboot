import { z } from 'zod';
import type { Tool } from '../../registry.js';

const inputSchema = z.object({});

type Params = z.infer<typeof inputSchema>;

/**
 * Stage data mirrors the canonical declarations in
 *   auraboot/platform/.../meta/constant/CommandStage.java
 *
 * AGENTS.md hard rule: 命令管线阶段必须以 CommandStage.java 为准。
 * Update this list whenever CommandStage.java changes — keep numbers,
 * names, and descriptions byte-for-byte aligned with the Java source.
 *
 * Note Stage 15 is intentionally absent in CommandStage.java (number is
 * reserved/merged), so the in-tx phase count is 19 declared values
 * spanning numbers 1..20 with TOTAL_TRANSACTIONAL_STAGES = 20.
 */
const PIPELINE = {
  totalTransactionalStages: 20,
  totalAfterCommitStages: 4,
  inTransaction: [
    { stage: 1, name: 'load', description: 'Load command definition from database' },
    { stage: 2, name: 'schema_validate', description: 'Basic payload schema validation' },
    { stage: 3, name: 'idempotency_check', description: 'Check for duplicate request replay' },
    { stage: 4, name: 'entitlement_check', description: 'Verify plugin/feature entitlements' },
    { stage: 5, name: 'sod_check', description: 'Separation of Duties enforcement' },
    { stage: 6, name: 'state_check', description: 'Validate state transitions' },
    { stage: 7, name: 'assert', description: 'Preconditions, assertions, and field validation' },
    { stage: 8, name: 'pre_invariant', description: 'Pre-execution invariant evaluation' },
    { stage: 9, name: 'cross_field_validation', description: 'Cross-field dependency rule evaluation' },
    { stage: 10, name: 'auto_set', description: 'Inject auto-generated values (codes, timestamps)' },
    { stage: 11, name: 'field_map', description: 'Map payload to database columns and persist' },
    { stage: 12, name: 'computed_fields', description: 'Calculate SpEL formula fields' },
    { stage: 13, name: 'change_tracking', description: 'Record field-level changes for audit trail' },
    { stage: 14, name: 'handler', description: 'Execute custom command handlers' },
    { stage: 16, name: 'side_effect', description: 'Create/update related records' },
    { stage: 17, name: 'roll_up', description: 'Recalculate parent summary fields' },
    { stage: 18, name: 'post_action', description: 'Post-processing (child records, etc.)' },
    { stage: 19, name: 'effect', description: 'Write events to outbox/store, record audit' },
    { stage: 20, name: 'post_invariant', description: 'Post-execution invariant evaluation' },
  ],
  afterCommit: [
    { stage: 21, name: 'domain_event', description: 'Publish domain events for in-process listeners' },
    { stage: 22, name: 'api_call', description: 'Execute external API calls' },
    { stage: 23, name: 'webhook', description: 'Dispatch webhooks to external systems' },
    { stage: 24, name: 'governance_snapshot', description: 'Governance snapshot capture' },
  ],
  notes: [
    'Stage 15 is reserved/merged in CommandStage.java — the number is intentionally absent.',
    'After-commit stages run OUTSIDE the main transaction. Failures there do NOT roll back the commit.',
    'When generating a command, AVOID re-implementing logic the platform applies automatically (validation, audit, change tracking, etc.). Place custom logic in HANDLER (stage 14) or SIDE_EFFECT (stage 16).',
    'BindingRule entries written via add_binding_rule attach to a specific stage; default is EFFECT.',
  ],
} as const;

/**
 * `describe_command_pipeline` — static documentation tool that returns
 * the canonical 20-stage in-transaction + 4-stage after-commit pipeline.
 *
 * LLMs use this output to understand which logic the platform applies
 * automatically (so they don't duplicate it inside generated handlers)
 * and to choose which stage a BindingRule should attach to.
 */
export function describeCommandPipelineTool(): Tool<Params> {
  return {
    name: 'describe_command_pipeline',
    title: 'Describe Command Pipeline',
    description:
      'Describe the 20 in-transaction stages + 4 after-commit stages of the AuraBoot Command Pipeline. CALL BEFORE create_command so generated handlers do not duplicate logic the platform already applies (e.g. validation, audit, change tracking).',
    inputSchema,
    annotations: { readOnlyHint: true, idempotentHint: true, openWorldHint: false },
    handler: async () => ({
      content: [{ type: 'text' as const, text: JSON.stringify(PIPELINE, null, 2) }],
    }),
  };
}
