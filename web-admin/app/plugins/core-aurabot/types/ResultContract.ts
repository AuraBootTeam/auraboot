/**
 * ResultContract — external output protocol for ACP Skill results.
 *
 * Mirrors the backend DTO at
 * auraboot/platform/src/main/java/com/auraboot/framework/agent/dto/ResultContract.java
 * (source of truth). Hides engine internals (actionPids, cost, etc).
 *
 * When the backend emits a `result_contract` SSE event, its JSON payload
 * conforms to this shape.
 */

export type ResultContractOutputType =
  | 'text'
  | 'structured_result'
  | 'action_proposal'
  | 'artifact';

/** Hint to the frontend about which renderer to pick. */
export type ResultContractRenderHint =
  | 'chart_table'
  | 'table'
  | 'summary'
  | 'form'
  | 'card'
  | 'timeline';

export type ResultContractActionability = 'read_only' | 'propose' | 'execute';

export type ResultContractStatus = 'success' | 'partial_success' | 'failed' | 'unknown';

export interface ResultContractSuggestedAction {
  label: string;
  skillCode: string;
  prefillInput?: Record<string, unknown>;
}

export interface ResultContract {
  outputType: ResultContractOutputType;
  renderHint?: ResultContractRenderHint;
  actionability: ResultContractActionability;

  data?: Record<string, unknown>;
  textSummary?: string;
  table?: Array<Record<string, unknown>>;
  chart?: Record<string, unknown>;

  suggestedActions?: ResultContractSuggestedAction[];
  canContinueFrom?: boolean;

  skillCode?: string;
  durationMs?: number;
  status: ResultContractStatus;
}
