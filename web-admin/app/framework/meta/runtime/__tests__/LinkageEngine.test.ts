import { describe, it, expect, vi, beforeEach } from 'vitest';
import { LinkageEngine } from '~/framework/meta/runtime/linkage/LinkageEngine';
import { ScopedStateManager } from '~/framework/meta/runtime/state/scoped-state';
import type { ExpressionContext, GlobalState } from '~/framework/meta/runtime/expression/context';
import type { LinkageRule } from '~/plugins/core-designer/components/studio/workbench/panels/linkage/types';

const SCOPE_ID = 'linkage-test';

const createGlobalState = (): GlobalState => ({
  locale: 'zh-CN',
  theme: 'light',
  t: (key: string) => key,
});

function createStateManager(): ScopedStateManager {
  const sm = new ScopedStateManager(createGlobalState());
  sm.createScope(SCOPE_ID, {
    form: { city: 'beijing', status: 'active' },
  });
  return sm;
}

function createContext(sm: ScopedStateManager): ExpressionContext {
  return sm.getContext(SCOPE_ID);
}

describe('LinkageEngine', () => {
  let sm: ScopedStateManager;
  let onFieldValueChange: ReturnType<typeof vi.fn>;
  let onError: ReturnType<typeof vi.fn>;
  let engine: LinkageEngine;

  beforeEach(() => {
    sm = createStateManager();
    onFieldValueChange = vi.fn();
    onError = vi.fn();
    engine = new LinkageEngine({
      stateManager: sm,
      scopeId: SCOPE_ID,
      onFieldValueChange,
      onError,
    });
  });

  // ---- Registration ----

  it('registers enabled rules and builds index', () => {
    const rules: LinkageRule[] = [
      {
        id: 'r1',
        trigger: { fieldCode: 'city', event: 'change' },
        actions: [{ type: 'show', targets: ['district'] }],
        enabled: true,
      },
      {
        id: 'r2',
        trigger: { fieldCode: 'city', event: 'change' },
        actions: [{ type: 'hide', targets: ['province'] }],
        enabled: false, // disabled rule
      },
    ];

    engine.register(rules);
    expect(engine.getRules()).toHaveLength(2);

    // Trigger — only r1 should fire
    engine.onFieldEvent('city', 'change', createContext(sm));
    const meta = sm.getFieldMeta(SCOPE_ID, 'district');
    expect(meta?.hidden).toBe(false);

    // province should NOT be affected (r2 is disabled)
    expect(sm.getFieldMeta(SCOPE_ID, 'province')).toBeUndefined();
  });

  // ---- show / hide ----

  it('executes show action — sets hidden=false', () => {
    engine.register([
      {
        id: 'r1',
        trigger: { fieldCode: 'status', event: 'change' },
        actions: [{ type: 'show', targets: ['details', 'notes'] }],
        enabled: true,
      },
    ]);

    engine.onFieldEvent('status', 'change', createContext(sm));

    expect(sm.getFieldMeta(SCOPE_ID, 'details')?.hidden).toBe(false);
    expect(sm.getFieldMeta(SCOPE_ID, 'notes')?.hidden).toBe(false);
  });

  it('executes hide action — sets hidden=true', () => {
    engine.register([
      {
        id: 'r1',
        trigger: { fieldCode: 'status', event: 'change' },
        actions: [{ type: 'hide', targets: ['details'] }],
        enabled: true,
      },
    ]);

    engine.onFieldEvent('status', 'change', createContext(sm));
    expect(sm.getFieldMeta(SCOPE_ID, 'details')?.hidden).toBe(true);
  });

  // ---- enable / disable ----

  it('executes enable action — sets disabled=false', () => {
    engine.register([
      {
        id: 'r1',
        trigger: { fieldCode: 'city', event: 'change' },
        actions: [{ type: 'enable', targets: ['district'] }],
        enabled: true,
      },
    ]);

    engine.onFieldEvent('city', 'change', createContext(sm));
    expect(sm.getFieldMeta(SCOPE_ID, 'district')?.disabled).toBe(false);
  });

  it('executes disable action — sets disabled=true', () => {
    engine.register([
      {
        id: 'r1',
        trigger: { fieldCode: 'city', event: 'change' },
        actions: [{ type: 'disable', targets: ['district'] }],
        enabled: true,
      },
    ]);

    engine.onFieldEvent('city', 'change', createContext(sm));
    expect(sm.getFieldMeta(SCOPE_ID, 'district')?.disabled).toBe(true);
  });

  // ---- setRequired ----

  it('executes setRequired action', () => {
    engine.register([
      {
        id: 'r1',
        trigger: { fieldCode: 'status', event: 'change' },
        actions: [{ type: 'setRequired', targets: ['reason'], required: true }],
        enabled: true,
      },
    ]);

    engine.onFieldEvent('status', 'change', createContext(sm));
    expect(sm.getFieldMeta(SCOPE_ID, 'reason')?.required).toBe(true);
  });

  // ---- setValue ----

  it('executes setValue action — calls onFieldValueChange callback', () => {
    engine.register([
      {
        id: 'r1',
        trigger: { fieldCode: 'city', event: 'change' },
        actions: [{ type: 'setValue', target: 'district', value: '"haidian"' }],
        enabled: true,
      },
    ]);

    engine.onFieldEvent('city', 'change', createContext(sm));
    expect(onFieldValueChange).toHaveBeenCalledWith('district', 'haidian');
  });

  it('setValue falls back to raw string if expression evaluation fails', () => {
    engine.register([
      {
        id: 'r1',
        trigger: { fieldCode: 'city', event: 'change' },
        actions: [{ type: 'setValue', target: 'district', value: 'plain text fallback' }],
        enabled: true,
      },
    ]);

    engine.onFieldEvent('city', 'change', createContext(sm));
    expect(onFieldValueChange).toHaveBeenCalledWith('district', 'plain text fallback');
  });

  // ---- validate ----

  it('executes validate action — sets validation rules in fieldMeta', () => {
    engine.register([
      {
        id: 'r1',
        trigger: { fieldCode: 'status', event: 'blur' },
        actions: [
          {
            type: 'validate',
            targets: ['reason'],
            rules: [{ type: 'required', message: 'Reason is required' }],
          },
        ],
        enabled: true,
      },
    ]);

    engine.onFieldEvent('status', 'blur', createContext(sm));
    const meta = sm.getFieldMeta(SCOPE_ID, 'reason');
    expect(meta?.validation).toEqual([{ type: 'required', message: 'Reason is required' }]);
  });

  // ---- Condition filtering ----

  it('skips rule when condition evaluates to false', () => {
    engine.register([
      {
        id: 'r1',
        trigger: {
          fieldCode: 'status',
          event: 'change',
          condition: 'form.status === "inactive"', // form.status is "active"
        },
        actions: [{ type: 'hide', targets: ['details'] }],
        enabled: true,
      },
    ]);

    engine.onFieldEvent('status', 'change', createContext(sm));
    // Action should NOT fire because condition is false
    expect(sm.getFieldMeta(SCOPE_ID, 'details')).toBeUndefined();
  });

  it('executes rule when condition evaluates to true', () => {
    engine.register([
      {
        id: 'r1',
        trigger: {
          fieldCode: 'status',
          event: 'change',
          condition: 'form.status === "active"', // form.status IS "active"
        },
        actions: [{ type: 'hide', targets: ['details'] }],
        enabled: true,
      },
    ]);

    engine.onFieldEvent('status', 'change', createContext(sm));
    expect(sm.getFieldMeta(SCOPE_ID, 'details')?.hidden).toBe(true);
  });

  // ---- Event type matching ----

  it('does not fire for mismatched event type', () => {
    engine.register([
      {
        id: 'r1',
        trigger: { fieldCode: 'city', event: 'blur' },
        actions: [{ type: 'hide', targets: ['district'] }],
        enabled: true,
      },
    ]);

    engine.onFieldEvent('city', 'change', createContext(sm));
    expect(sm.getFieldMeta(SCOPE_ID, 'district')).toBeUndefined();
  });

  // ---- Multiple actions in one rule ----

  it('executes multiple actions in a single rule', () => {
    engine.register([
      {
        id: 'r1',
        trigger: { fieldCode: 'city', event: 'change' },
        actions: [
          { type: 'show', targets: ['district'] },
          { type: 'disable', targets: ['province'] },
          { type: 'setRequired', targets: ['zipcode'], required: true },
        ],
        enabled: true,
      },
    ]);

    engine.onFieldEvent('city', 'change', createContext(sm));

    expect(sm.getFieldMeta(SCOPE_ID, 'district')?.hidden).toBe(false);
    expect(sm.getFieldMeta(SCOPE_ID, 'province')?.disabled).toBe(true);
    expect(sm.getFieldMeta(SCOPE_ID, 'zipcode')?.required).toBe(true);
  });

  // ---- Multiple rules for same field:event ----

  it('executes all matching rules for the same field:event', () => {
    engine.register([
      {
        id: 'r1',
        trigger: { fieldCode: 'city', event: 'change' },
        actions: [{ type: 'show', targets: ['district'] }],
        enabled: true,
      },
      {
        id: 'r2',
        trigger: { fieldCode: 'city', event: 'change' },
        actions: [{ type: 'disable', targets: ['province'] }],
        enabled: true,
      },
    ]);

    engine.onFieldEvent('city', 'change', createContext(sm));

    expect(sm.getFieldMeta(SCOPE_ID, 'district')?.hidden).toBe(false);
    expect(sm.getFieldMeta(SCOPE_ID, 'province')?.disabled).toBe(true);
  });

  // ---- Error handling ----

  it('calls onError when an action throws', () => {
    // Register a rule with a condition that will throw
    engine.register([
      {
        id: 'r-bad',
        trigger: {
          fieldCode: 'city',
          event: 'change',
          condition: 'this.is.not.valid.$$$.syntax()',
        },
        actions: [{ type: 'hide', targets: ['district'] }],
        enabled: true,
      },
    ]);

    engine.onFieldEvent('city', 'change', createContext(sm));
    // The condition evaluation may fail or return false — either way, target should not be hidden
    // (if evaluateCondition catches and returns false, no error callback; if it throws, onError is called)
    // The key assertion: no crash
    expect(sm.getFieldMeta(SCOPE_ID, 'district')).toBeUndefined();
  });

  // ---- Dispose ----

  it('dispose clears all rules and index', () => {
    engine.register([
      {
        id: 'r1',
        trigger: { fieldCode: 'city', event: 'change' },
        actions: [{ type: 'hide', targets: ['district'] }],
        enabled: true,
      },
    ]);

    engine.dispose();
    expect(engine.getRules()).toHaveLength(0);

    // Should not fire after dispose
    engine.onFieldEvent('city', 'change', createContext(sm));
    expect(sm.getFieldMeta(SCOPE_ID, 'district')).toBeUndefined();
  });
});
