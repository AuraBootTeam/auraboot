import { describe, expect, it } from 'vitest';
import { runtimeAdapterLabel, traceFieldValueLabels, traceLabel, traceSemanticValue } from '../tracePresentation';
import { decisionStatusLabel } from '../statusLabels';
import TEXT from '../tracePresentation.i18n.json';

describe('trace presentation', () => {
  it.each([
    ['matched', false, 'Not matched'], ['matched', 'FALSE', 'Not matched'],
    ['matched', 'NOT_MATCHED', 'Not matched'], ['matched', true, 'Matched'],
    ['truth', false, 'Not satisfied'], ['truth', 'FALSE', 'Not satisfied'],
    ['truth', 'true', 'Satisfied'], ['conditionResult', 'unknown', 'Unknown'],
    ['enabled', false, 'No'], ['enabled', true, 'Yes'],
  ])('preserves the meaning of %s=%s', (key, value, expected) => {
    expect(traceSemanticValue(key as string, value, 'en-US')).toBe(expected);
  });

  it('does not invent a result from unknown, numeric or empty values', () => {
    expect(traceSemanticValue('matched', 'future_result', 'en-US')).toBeUndefined();
    expect(traceSemanticValue('matched', 0, 'en-US')).toBeUndefined();
    expect(traceSemanticValue('truth', null, 'en-US')).toBeUndefined();
  });

  it.each([
    ['AST_EVALUATOR', 'Condition evaluation'], ['SAFE_SPEL', 'Safe expression'],
    ['CROSS_FIELD_ENGINE', 'Cross-field evaluation'], ['PLATFORM_DECISION_TABLE', 'Decision table'],
    ['DROOLS_DMN', 'DMN decision'], ['DROOLS_DRL', 'Rule engine'],
  ])('labels the actual backend adapter %s', (code, expected) => {
    expect(runtimeAdapterLabel(code, 'en-US')).toBe(expected);
  });

  it('keeps an unsupported evaluator distinct from a supported evaluator', () => {
    expect(runtimeAdapterLabel('FUTURE_ENGINE', 'en-US')).toBe('Other evaluator');
    expect(runtimeAdapterLabel(undefined, 'en-US')).toBe('-');
  });

  it('resolves Chinese and English from the supplied locale', () => {
    expect(traceSemanticValue('matched', false, 'zh-CN')).toBe(TEXT.semantic.matchedFalse['zh-CN']);
    expect(traceSemanticValue('matched', false, 'en-US')).toBe('Not matched');
    expect(traceLabel('decision', 'sla_deadline', 'en-US')).toBe('SLA deadline');
    expect(traceLabel('payload', 'recordPid', 'en-US')).toBe('Business record');
  });

  it('retains canonical leave aliases and rejects unrelated field names', () => {
    for (const key of ['wd_req_type', 'leaveType', 'leave_type', 'reqType']) {
      expect(traceFieldValueLabels(key, 'en-US')?.annual).toBe('Annual leave');
    }
    expect(traceFieldValueLabels('someOtherField', 'en-US')).toBeUndefined();
  });

  it('distinguishes matched from not-matched and preserves future status codes', () => {
    expect(decisionStatusLabel('MATCHED', 'en-US')).toBe('Matched');
    expect(decisionStatusLabel('NOT_MATCHED', 'en-US')).toBe('Not matched');
    expect(decisionStatusLabel('FUTURE_STATUS', 'en-US')).toBe('FUTURE_STATUS');
  });
});
