/**
 * envelopeMapper — translates a `SkillResult` (or an SSE event payload)
 * into one or more `Envelope`s for the renderer (Spec §5).
 *
 * Mapping table (Spec §5):
 *   status=SUCCESS, payload.text                 → TextEnvelope
 *   status=SUCCESS, payload.thinking             → ThinkingEnvelope
 *   status=NEEDS_CONFIRM                         → PreviewEnvelope + ConfirmEnvelope
 *   status=SUCCESS, riskLevel=CRITICAL +
 *     requireTextConfirm                         → ConfirmEnvelope (text-confirm variant)
 *   status=SUCCESS, suggestions[]                → ...primary, then SuggestionEnvelope
 *   status=ERROR                                 → ErrorEnvelope
 *   status=SUCCESS, payload (other)              → ResultEnvelope (fallback)
 *
 *   SSE 'wizard-progress'                        → WizardProgressEnvelope
 *   SSE 'partial-result' with payload.text       → TextEnvelope (append)
 *   SSE 'thinking'                               → ThinkingEnvelope
 */

import type { Envelope } from '../types/envelope';
import type { SkillResult } from '../types/skill';

export function mapSkillResultToEnvelopes(result: SkillResult): Envelope[] {
  const envelopes: Envelope[] = [];

  if (result.status === 'ERROR') {
    const first = result.errors[0];
    envelopes.push({
      kind: 'error',
      code: first?.code ?? 'SKILL_INTERNAL_ERROR',
      message: first?.message ?? 'Skill execution failed',
    });
    return envelopes;
  }

  if (result.status === 'NEEDS_CONFIRM') {
    if (result.preview) {
      envelopes.push({
        kind: 'preview',
        preview: result.preview,
        riskLevel: result.riskLevel,
      });
    }
    if (result.previewToken) {
      envelopes.push({
        kind: 'confirm',
        previewToken: result.previewToken,
        riskLevel: result.riskLevel,
        requireTextConfirm: result.requireTextConfirm,
      });
    }
    return envelopes;
  }

  // SUCCESS: emit primary content envelope first.
  if (
    result.riskLevel === 'CRITICAL' &&
    result.requireTextConfirm != null &&
    result.previewToken != null
  ) {
    envelopes.push({
      kind: 'confirm',
      previewToken: result.previewToken,
      riskLevel: result.riskLevel,
      requireTextConfirm: result.requireTextConfirm,
    });
  }

  if (typeof result.payload?.thinking === 'string') {
    envelopes.push({ kind: 'thinking', text: result.payload.thinking });
  }

  if (typeof result.payload?.text === 'string') {
    envelopes.push({ kind: 'text', text: result.payload.text });
  } else if (
    envelopes.length === 0 &&
    result.payload &&
    Object.keys(result.payload).length > 0
  ) {
    envelopes.push({ kind: 'result', payload: result.payload });
  }

  if (result.suggestions.length > 0) {
    envelopes.push({ kind: 'suggestion', suggestions: result.suggestions });
  }

  return envelopes;
}

export interface SseEventEnvelope {
  /** One of 'thinking' | 'wizard-progress' | 'partial-result' (terminal events handled separately). */
  event: 'thinking' | 'wizard-progress' | 'partial-result';
  data: Record<string, unknown>;
}

export function mapSseEventToEnvelope(input: SseEventEnvelope): Envelope | null {
  if (input.event === 'thinking') {
    const text = typeof input.data.text === 'string' ? input.data.text : '';
    const tokens = typeof input.data.tokens === 'number' ? input.data.tokens : undefined;
    return { kind: 'thinking', text, tokens };
  }
  if (input.event === 'wizard-progress') {
    return {
      kind: 'wizard-progress',
      step: typeof input.data.step === 'number' ? input.data.step : 0,
      total: typeof input.data.total === 'number' ? input.data.total : 0,
      label: typeof input.data.label === 'string' ? input.data.label : '',
    };
  }
  if (input.event === 'partial-result') {
    const payload = (input.data.payload ?? {}) as Record<string, unknown>;
    if (typeof payload.text === 'string') {
      return { kind: 'text', text: payload.text };
    }
    return { kind: 'result', payload };
  }
  return null;
}
