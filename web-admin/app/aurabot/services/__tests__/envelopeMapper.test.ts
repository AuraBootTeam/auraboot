import { describe, expect, it } from 'vitest';
import {
  mapSkillResultToEnvelopes,
  mapSseEventToEnvelope,
} from '../envelopeMapper';
import type { SkillResult } from '../../types/skill';

const baseResult: SkillResult = {
  status: 'SUCCESS',
  skillName: 'echo',
  traceId: 't1',
  payload: {},
  preview: null,
  previewToken: null,
  riskLevel: 'LOW',
  requireTextConfirm: null,
  undoToken: null,
  batchId: null,
  suggestions: [],
  streamUrl: null,
  errors: [],
};

describe('mapSkillResultToEnvelopes', () => {
  it('SUCCESS payload.text → TextEnvelope', () => {
    const env = mapSkillResultToEnvelopes({
      ...baseResult,
      payload: { text: 'hello' },
    });
    expect(env).toEqual([{ kind: 'text', text: 'hello' }]);
  });

  it('SUCCESS payload.thinking → ThinkingEnvelope before text', () => {
    const env = mapSkillResultToEnvelopes({
      ...baseResult,
      payload: { thinking: 'reasoning', text: 'done' },
    });
    expect(env).toEqual([
      { kind: 'thinking', text: 'reasoning' },
      { kind: 'text', text: 'done' },
    ]);
  });

  it('NEEDS_CONFIRM emits Preview + Confirm', () => {
    const env = mapSkillResultToEnvelopes({
      ...baseResult,
      status: 'NEEDS_CONFIRM',
      preview: { foo: 'bar' },
      previewToken: 'px_1',
      riskLevel: 'MEDIUM',
    });
    expect(env).toEqual([
      { kind: 'preview', preview: { foo: 'bar' }, riskLevel: 'MEDIUM' },
      {
        kind: 'confirm',
        previewToken: 'px_1',
        riskLevel: 'MEDIUM',
        requireTextConfirm: null,
      },
    ]);
  });

  it('CRITICAL with requireTextConfirm emits Confirm before content', () => {
    const env = mapSkillResultToEnvelopes({
      ...baseResult,
      payload: { text: 'committed' },
      riskLevel: 'CRITICAL',
      requireTextConfirm: 'DELETE',
      previewToken: 'px_2',
    });
    expect(env[0]).toEqual({
      kind: 'confirm',
      previewToken: 'px_2',
      riskLevel: 'CRITICAL',
      requireTextConfirm: 'DELETE',
    });
    expect(env[1]).toEqual({ kind: 'text', text: 'committed' });
  });

  it('SUCCESS with suggestions appends SuggestionEnvelope', () => {
    const env = mapSkillResultToEnvelopes({
      ...baseResult,
      payload: { text: 'ok' },
      suggestions: [{ label: 'Add field', skillName: 'field:add', paramsHint: {} }],
    });
    expect(env[env.length - 1]).toEqual({
      kind: 'suggestion',
      suggestions: [{ label: 'Add field', skillName: 'field:add', paramsHint: {} }],
    });
  });

  it('ERROR uses first error code/message', () => {
    const env = mapSkillResultToEnvelopes({
      ...baseResult,
      status: 'ERROR',
      errors: [{ code: 'PERMISSION_DENIED', message: 'nope' }],
    });
    expect(env).toEqual([
      { kind: 'error', code: 'PERMISSION_DENIED', message: 'nope' },
    ]);
  });

  it('ERROR with empty errors falls back to SKILL_INTERNAL_ERROR', () => {
    const env = mapSkillResultToEnvelopes({
      ...baseResult,
      status: 'ERROR',
      errors: [],
    });
    expect(env[0]).toMatchObject({ kind: 'error', code: 'SKILL_INTERNAL_ERROR' });
  });

  it('SUCCESS opaque payload falls back to ResultEnvelope', () => {
    const env = mapSkillResultToEnvelopes({
      ...baseResult,
      payload: { foo: 1, bar: 'x' },
    });
    expect(env).toEqual([{ kind: 'result', payload: { foo: 1, bar: 'x' } }]);
  });
});

describe('mapSseEventToEnvelope', () => {
  it('thinking event maps to ThinkingEnvelope', () => {
    const env = mapSseEventToEnvelope({
      event: 'thinking',
      data: { text: 'pondering', tokens: 42 },
    });
    expect(env).toEqual({ kind: 'thinking', text: 'pondering', tokens: 42 });
  });

  it('wizard-progress maps to WizardProgressEnvelope', () => {
    const env = mapSseEventToEnvelope({
      event: 'wizard-progress',
      data: { step: 2, total: 5, label: 'Build list page' },
    });
    expect(env).toEqual({
      kind: 'wizard-progress',
      step: 2,
      total: 5,
      label: 'Build list page',
    });
  });

  it('partial-result with text → TextEnvelope', () => {
    const env = mapSseEventToEnvelope({
      event: 'partial-result',
      data: { payload: { text: 'chunk' } },
    });
    expect(env).toEqual({ kind: 'text', text: 'chunk' });
  });

  it('partial-result with non-text payload → ResultEnvelope', () => {
    const env = mapSseEventToEnvelope({
      event: 'partial-result',
      data: { payload: { foo: 1 } },
    });
    expect(env).toEqual({ kind: 'result', payload: { foo: 1 } });
  });
});
