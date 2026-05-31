import { describe, it, expect } from 'vitest';
import { buildToolbarConditionContext } from '~/framework/meta/rendering/pages/ListPageContent';
import { evaluateCondition } from '~/framework/meta/runtime/expression/evaluator';

describe('toolbar condition context', () => {
  it('exposes recordCount and total from the loaded list', () => {
    const ctx = buildToolbarConditionContext({ total: 0, records: [] }, { user: { id: '1' } } as any);
    expect(ctx.recordCount).toBe(0);
    expect(ctx.total).toBe(0);
  });
  it('reflects a non-empty list', () => {
    const ctx = buildToolbarConditionContext({ total: 3, records: [{}, {}, {}] }, {} as any);
    expect(ctx.recordCount).toBe(3);
  });
});

describe('evaluator supports recordCount == 0 for visibleWhen', () => {
  it('is truthy when recordCount is 0', () => {
    expect(evaluateCondition('recordCount == 0', { recordCount: 0 } as any)).toBe(true);
  });
  it('is falsy when recordCount is 1', () => {
    expect(evaluateCondition('recordCount == 0', { recordCount: 1 } as any)).toBe(false);
  });
});
