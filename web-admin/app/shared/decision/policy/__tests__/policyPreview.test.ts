import { describe, it, expect } from 'vitest';
import { evaluatePolicyPreview, type PolicyPreviewInput, type MatchMode } from '../policyPreview';
import { group, cmp, path, lit, type ScopedContext } from '../../ast/conditionAst';

const rec = (data: Record<string, unknown>): ScopedContext => ({ record: { data } });

const r = (code: string, priority: number, cond: ReturnType<typeof group>, enabled = true) =>
  ({ ruleCode: code, priority, enabled, condition: cond });

// mockup s1: 3 rules (priority==HIGH / amount>10000 / customerLevel==VIP)
const policy = (matchMode: MatchMode): PolicyPreviewInput => ({
  matchMode,
  rules: [
    r('R-101', 100, group('AND', [cmp(path('record', 'data.priority', 'enum'), 'EQ', lit('HIGH', 'enum'))])),
    r('R-102', 200, group('AND', [cmp(path('record', 'data.amount', 'decimal'), 'GT', lit(10000, 'decimal'))])),
    r('R-103', 300, group('AND', [cmp(path('record', 'data.customerLevel', 'enum'), 'EQ', lit('VIP', 'enum'))])),
  ],
});

describe('evaluatePolicyPreview (mirrors EventPolicyEvaluator matchMode)', () => {
  it('COLLECT_ALL collects every matching rule', () => {
    const res = evaluatePolicyPreview(policy('COLLECT_ALL'), rec({ priority: 'HIGH', amount: 20000, customerLevel: 'VIP' }));
    expect(res.status).toBe('MATCHED');
    expect(res.matchedRuleCodes).toEqual(['R-101', 'R-102', 'R-103']);
  });

  it('FIRST_MATCH stops at the first matching rule (priority order)', () => {
    const res = evaluatePolicyPreview(policy('FIRST_MATCH'), rec({ priority: 'HIGH', amount: 20000, customerLevel: 'VIP' }));
    expect(res.matchedRuleCodes).toEqual(['R-101']);
  });

  it('UNIQUE with multiple matches is ERROR', () => {
    const res = evaluatePolicyPreview(policy('UNIQUE'), rec({ priority: 'HIGH', amount: 20000, customerLevel: 'VIP' }));
    expect(res.status).toBe('ERROR');
    expect(res.error).toContain('UNIQUE');
  });

  it('NOT_MATCHED when no rule matches', () => {
    const res = evaluatePolicyPreview(policy('COLLECT_ALL'), rec({ priority: 'LOW', amount: 1, customerLevel: 'Std' }));
    expect(res.status).toBe('NOT_MATCHED');
    expect(res.matchedRuleCodes).toHaveLength(0);
  });

  it('disabled rule is skipped, not matched', () => {
    const p: PolicyPreviewInput = {
      matchMode: 'COLLECT_ALL',
      rules: [
        r('R-D', 50, group('AND', [cmp(path('record', 'data.priority', 'enum'), 'EQ', lit('HIGH', 'enum'))]), false),
        r('R-1', 100, group('AND', [cmp(path('record', 'data.amount', 'decimal'), 'GT', lit(10, 'decimal'))])),
      ],
    };
    const res = evaluatePolicyPreview(p, rec({ priority: 'HIGH', amount: 100 }));
    expect(res.skippedRuleCodes).toContain('R-D');
    expect(res.matchedRuleCodes).toEqual(['R-1']);
  });
});
