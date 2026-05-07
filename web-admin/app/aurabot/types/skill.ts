/**
 * Wire types mirroring the AuraBot Skill SPI contract
 * (docs/superpowers/specs/2026-05-08-aurabot-skill-spi-contract.md §3-§4).
 *
 * Field names are FROZEN — any rename here is a contract violation.
 */

export type RiskLevel = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';

export type SkillStatus = 'SUCCESS' | 'NEEDS_CONFIRM' | 'ERROR';

/** AgentContext per SPI §3 — collected by the FE before each request. */
export interface AgentContext {
  route: string;
  modelCode: string | null;
  pageId: string | null;
  selectedElement: unknown | null;
  recentOperations: unknown[];
  lastCreatedResources: unknown[];
}

/** SPI §3 SkillRequest. */
export interface SkillRequest {
  skillName: string;
  params: Record<string, unknown>;
  context: AgentContext;
  idempotencyKey: string;
  previewToken: string | null;
}

/** Suggestion entry under SkillResult.suggestions per SPI §4. */
export interface SkillSuggestion {
  label: string;
  skillName: string;
  paramsHint: Record<string, unknown>;
}

/** SPI §4 error entry (loose because backend may carry varying detail). */
export interface SkillError {
  code: string;
  message?: string;
  details?: Record<string, unknown>;
}

/** SPI §4 SkillResult — same shape returned sync OR via SSE terminal `done`. */
export interface SkillResult {
  status: SkillStatus;
  skillName: string;
  traceId: string;
  payload: Record<string, unknown>;
  preview: Record<string, unknown> | null;
  previewToken: string | null;
  riskLevel: RiskLevel;
  requireTextConfirm: string | null;
  undoToken: string | null;
  batchId: string | null;
  suggestions: SkillSuggestion[];
  streamUrl: string | null;
  errors: SkillError[];
}

/** SPI §10 GET /skills response entry. */
export interface SkillMeta {
  name: string;
  displayName: string;
  category: string;
  riskLevel: RiskLevel;
  paramsSchema: Record<string, unknown>;
  requiredPermissions: string[];
  supportsUndo: boolean;
  supportsDryRun: boolean;
  supportsStreaming: boolean;
}
