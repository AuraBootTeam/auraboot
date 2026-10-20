import { describe, it, expect } from 'vitest';
import { buildToolbarConditionContext } from '~/framework/meta/rendering/pages/list/listSchemaPolicy';
import { evaluateCondition } from '~/framework/meta/runtime/expression/evaluator';
import { createExpressionContext } from '~/framework/meta/runtime/expression/context';
import { ScopedStateManager } from '~/framework/meta/runtime/state/scoped-state';

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


describe('toolbar runtime draft conditions', () => {
  it('shows confirmation only for a current exact draft preview while preserving list context', () => {
    const state = new ScopedStateManager({} as any);
    const allocations = [{ quantity: '60.000001' }];
    state.createScope('plan', { form: { allocations }, state: { routeContext: { taskPid: 'task-a' } } });
    const context = () => buildToolbarConditionContext({ total: 7, records: [{}, {}] },
      createExpressionContext({ state: { filters: { category: 'IC' } } }), state.getContext('plan'));
    const condition = '${form.previewAllocations === form.allocations && !fn.ISBLANK(form.previewLines)}';
    expect(evaluateCondition(condition, context() as any)).toBe(false);
    state.updateField('plan', 'previewAllocations', allocations);
    state.updateField('plan', 'previewLines', [{ quantity: '60.000001' }]);
    expect(evaluateCondition(condition, context() as any)).toBe(true);
    expect(context().state).toEqual({ routeContext: { taskPid: 'task-a' }, filters: { category: 'IC' } });
    expect(context()).toMatchObject({ recordCount: 2, total: 7 });
    state.updateField('plan', 'allocations', [{ quantity: '60' }]);
    expect(evaluateCondition(condition, context() as any)).toBe(false);
    state.updateField('plan', 'previewAllocations', state.getFieldValue('plan', 'allocations'));
    expect(evaluateCondition(condition, context() as any)).toBe(true);
  });
});
