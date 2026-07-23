import type { ValidationMessage, ValidationResult } from './types.js';

/**
 * Agent-friendly aggregated validation report.
 *
 * Mirrors NocoBase's aggregated error shape: instead of a human-oriented
 * multi-line console dump, emit a flat list an AI agent can consume to fix a
 * whole batch of problems in one pass, then retry the same write operation.
 * Each entry carries a machine `code`, the `expected` value, and an imperative
 * `agentInstruction` where the validator can provide one.
 */

export interface AgentValidationEntry {
  code: string;
  message: string;
  path?: string;
  expected?: string;
  agentInstruction?: string;
}

export interface AgentValidationReport {
  ok: boolean;
  errorCount: number;
  warningCount: number;
  errors: AgentValidationEntry[];
  warnings: AgentValidationEntry[];
}

function toEntry(msg: ValidationMessage): AgentValidationEntry {
  const entry: AgentValidationEntry = { code: msg.code, message: msg.message };
  if (msg.path !== undefined) entry.path = msg.path;
  if (msg.expected !== undefined) entry.expected = msg.expected;
  if (msg.agentInstruction !== undefined) entry.agentInstruction = msg.agentInstruction;
  return entry;
}

export function toAgentErrorReport(result: ValidationResult): AgentValidationReport {
  return {
    ok: result.errorCount === 0,
    errorCount: result.errorCount,
    warningCount: result.warningCount,
    errors: result.messages.filter((m) => m.severity === 'error').map(toEntry),
    warnings: result.messages.filter((m) => m.severity === 'warning').map(toEntry),
  };
}
