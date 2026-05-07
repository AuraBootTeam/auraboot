/**
 * AuraBot V3 message envelope types (Spec §5, §6.1).
 *
 * Each kind maps to one presentational component under
 * `components/envelopes/`. No business logic lives in envelopes — they only
 * render their slice.
 */

import type { RiskLevel, SkillSuggestion } from './skill';

export interface TextEnvelope {
  kind: 'text';
  text: string;
}

export interface ThinkingEnvelope {
  kind: 'thinking';
  text: string;
  /** Optional token count surfaced for collapsed-state hint. */
  tokens?: number;
}

export interface PreviewEnvelope {
  kind: 'preview';
  /** Free-form preview payload — each skill defines its own shape. */
  preview: Record<string, unknown>;
  riskLevel: RiskLevel;
}

export interface ResultEnvelope {
  kind: 'result';
  payload: Record<string, unknown>;
}

export interface ConfirmEnvelope {
  kind: 'confirm';
  /** Token returned from dry-run; required to commit. */
  previewToken: string;
  riskLevel: RiskLevel;
  /**
   * When non-null the user must type the literal value before the
   * commit button activates (RiskLevel CRITICAL).
   */
  requireTextConfirm: string | null;
  /** Localized prompt shown above the confirm button. */
  prompt?: string;
}

export interface SuggestionEnvelope {
  kind: 'suggestion';
  suggestions: SkillSuggestion[];
}

export interface WizardProgressEnvelope {
  kind: 'wizard-progress';
  step: number;
  total: number;
  label: string;
}

export interface ErrorEnvelope {
  kind: 'error';
  /** Stable error code from SPI §11. */
  code: string;
  /** Already-localized message for display. */
  message: string;
  /** Optional retry disposer — present only when the call site supports retry. */
  retry?: () => void;
}

export interface CodeEnvelope {
  kind: 'code';
  language: string;
  code: string;
}

export type Envelope =
  | TextEnvelope
  | ThinkingEnvelope
  | PreviewEnvelope
  | ResultEnvelope
  | ConfirmEnvelope
  | SuggestionEnvelope
  | WizardProgressEnvelope
  | ErrorEnvelope
  | CodeEnvelope;

export type EnvelopeKind = Envelope['kind'];

/** A single message in the AuraBot V3 panel scroll buffer. */
export interface Message {
  id: string;
  /**
   * Wire-level traceId from {@link SkillResult.traceId}; used to replace
   * envelopes in place during SSE streaming.
   */
  traceId?: string;
  role: 'user' | 'assistant';
  envelopes: Envelope[];
}
