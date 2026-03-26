/**
 * LinkageEngine Cascade Tests
 *
 * Tests for multi-level linkage propagation (P0-2):
 * - setValue triggers cascading rules on the changed field
 * - Depth limit prevents infinite loops
 * - Context refreshes between cascade levels
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { LinkageEngine } from '~/meta/runtime/linkage/LinkageEngine';
import { ScopedStateManager } from '~/meta/runtime/state/scoped-state';
import type { ExpressionContext, GlobalState } from '~/meta/runtime/expression/context';
import type { LinkageRule } from '~/studio/workbench/panels/linkage/types';

const SCOPE_ID = 'cascade-test';

const createGlobalState = (): GlobalState => ({
  locale: 'zh-CN',
  theme: 'light',
  t: (key: string) => key,
});

function createStateManager(initialForm: Record<string, any> = {}): ScopedStateManager {
  const sm = new ScopedStateManager(createGlobalState());
  sm.createScope(SCOPE_ID, {
    form: { province: '', city: '', district: '', address: '', ...initialForm },
  });
  return sm;
}

describe('LinkageEngine — Multi-level Cascade', () => {
  let sm: ScopedStateManager;
  let onFieldValueChange: ReturnType<typeof vi.fn>;
  let onError: ReturnType<typeof vi.fn>;
  let engine: LinkageEngine;

  beforeEach(() => {
    sm = createStateManager();
    onFieldValueChange = vi.fn((fieldCode, value) => {
      // Simulate actual form update (so getContext returns fresh data)
      sm.updateField(SCOPE_ID, fieldCode, value);
    });
    onError = vi.fn();
    engine = new LinkageEngine({
      stateManager: sm,
      scopeId: SCOPE_ID,
      onFieldValueChange,
      onError,
      getContext: () => sm.getContext(SCOPE_ID),
    });
  });

  it('cascades setValue: A → B → C (2 levels)', () => {
    const rules: LinkageRule[] = [
      {
        id: 'r1',
        trigger: { fieldCode: 'province', event: 'change' },
        actions: [{ type: 'setValue', target: 'city', value: '"default_city"' }],
        enabled: true,
      },
      {
        id: 'r2',
        trigger: { fieldCode: 'city', event: 'change' },
        actions: [{ type: 'show', targets: ['district'] }],
        enabled: true,
      },
    ];

    engine.register(rules);
    engine.onFieldEvent('province', 'change', sm.getContext(SCOPE_ID));

    // r1: province → city = "default_city"
    expect(onFieldValueChange).toHaveBeenCalledWith('city', 'default_city');

    // r2: city change cascaded → district shown
    const districtMeta = sm.getFieldMeta(SCOPE_ID, 'district');
    expect(districtMeta?.hidden).toBe(false);
  });

  it('cascades 3 levels: A → B → C → D', () => {
    const rules: LinkageRule[] = [
      {
        id: 'r1',
        trigger: { fieldCode: 'province', event: 'change' },
        actions: [{ type: 'setValue', target: 'city', value: '"city_val"' }],
        enabled: true,
      },
      {
        id: 'r2',
        trigger: { fieldCode: 'city', event: 'change' },
        actions: [{ type: 'setValue', target: 'district', value: '"dist_val"' }],
        enabled: true,
      },
      {
        id: 'r3',
        trigger: { fieldCode: 'district', event: 'change' },
        actions: [{ type: 'show', targets: ['address'] }],
        enabled: true,
      },
    ];

    engine.register(rules);
    engine.onFieldEvent('province', 'change', sm.getContext(SCOPE_ID));

    // All 3 levels should have fired
    expect(onFieldValueChange).toHaveBeenCalledWith('city', 'city_val');
    expect(onFieldValueChange).toHaveBeenCalledWith('district', 'dist_val');
    expect(sm.getFieldMeta(SCOPE_ID, 'address')?.hidden).toBe(false);
  });

  it('respects maxDepth and stops propagation', () => {
    // Create a circular dependency: A → B → A → B → ...
    const rules: LinkageRule[] = [
      {
        id: 'r1',
        trigger: { fieldCode: 'province', event: 'change' },
        actions: [{ type: 'setValue', target: 'city', value: '"x"' }],
        enabled: true,
      },
      {
        id: 'r2',
        trigger: { fieldCode: 'city', event: 'change' },
        actions: [{ type: 'setValue', target: 'province', value: '"y"' }],
        enabled: true,
      },
    ];

    // Set maxDepth to 3 to limit cycles
    const limitedEngine = new LinkageEngine({
      stateManager: sm,
      scopeId: SCOPE_ID,
      onFieldValueChange,
      onError,
      maxDepth: 3,
      getContext: () => sm.getContext(SCOPE_ID),
    });

    limitedEngine.register(rules);

    // Should not hang — depth limit prevents infinite loop
    const consoleSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    limitedEngine.onFieldEvent('province', 'change', sm.getContext(SCOPE_ID));
    consoleSpy.mockRestore();

    // Should have been called multiple times but not infinitely
    expect(onFieldValueChange.mock.calls.length).toBeLessThanOrEqual(4);
    expect(onFieldValueChange.mock.calls.length).toBeGreaterThanOrEqual(2);
  });

  it('maxDepth=1 disables cascading entirely', () => {
    const rules: LinkageRule[] = [
      {
        id: 'r1',
        trigger: { fieldCode: 'province', event: 'change' },
        actions: [{ type: 'setValue', target: 'city', value: '"val"' }],
        enabled: true,
      },
      {
        id: 'r2',
        trigger: { fieldCode: 'city', event: 'change' },
        actions: [{ type: 'show', targets: ['district'] }],
        enabled: true,
      },
    ];

    const noCascadeEngine = new LinkageEngine({
      stateManager: sm,
      scopeId: SCOPE_ID,
      onFieldValueChange,
      onError,
      maxDepth: 1,
      getContext: () => sm.getContext(SCOPE_ID),
    });

    noCascadeEngine.register(rules);
    noCascadeEngine.onFieldEvent('province', 'change', sm.getContext(SCOPE_ID));

    // r1 fires (province → city)
    expect(onFieldValueChange).toHaveBeenCalledWith('city', 'val');

    // r2 does NOT fire (cascade blocked at depth 1)
    expect(sm.getFieldMeta(SCOPE_ID, 'district')).toBeUndefined();
  });

  it('cascades with conditions — only propagates when condition is met', () => {
    const rules: LinkageRule[] = [
      {
        id: 'r1',
        trigger: { fieldCode: 'province', event: 'change' },
        actions: [{ type: 'setValue', target: 'city', value: '"shanghai"' }],
        enabled: true,
      },
      {
        id: 'r2',
        trigger: {
          fieldCode: 'city',
          event: 'change',
          condition: 'form.city === "shanghai"',
        },
        actions: [{ type: 'setRequired', targets: ['district'], required: true }],
        enabled: true,
      },
    ];

    engine.register(rules);
    engine.onFieldEvent('province', 'change', sm.getContext(SCOPE_ID));

    // r2 condition should be evaluated with fresh context (city = "shanghai")
    expect(sm.getFieldMeta(SCOPE_ID, 'district')?.required).toBe(true);
  });

  it('does not cascade when setValue has no matching rules', () => {
    const rules: LinkageRule[] = [
      {
        id: 'r1',
        trigger: { fieldCode: 'province', event: 'change' },
        actions: [{ type: 'setValue', target: 'city', value: '"val"' }],
        enabled: true,
      },
      // No rule for city:change
    ];

    engine.register(rules);
    engine.onFieldEvent('province', 'change', sm.getContext(SCOPE_ID));

    expect(onFieldValueChange).toHaveBeenCalledTimes(1);
    expect(onFieldValueChange).toHaveBeenCalledWith('city', 'val');
  });

  it('multiple setValue in one rule cascade independently', () => {
    const rules: LinkageRule[] = [
      {
        id: 'r1',
        trigger: { fieldCode: 'province', event: 'change' },
        actions: [
          { type: 'setValue', target: 'city', value: '"c"' },
          { type: 'setValue', target: 'district', value: '"d"' },
        ],
        enabled: true,
      },
      {
        id: 'r2',
        trigger: { fieldCode: 'city', event: 'change' },
        actions: [{ type: 'show', targets: ['address'] }],
        enabled: true,
      },
      {
        id: 'r3',
        trigger: { fieldCode: 'district', event: 'change' },
        actions: [{ type: 'disable', targets: ['address'] }],
        enabled: true,
      },
    ];

    engine.register(rules);
    engine.onFieldEvent('province', 'change', sm.getContext(SCOPE_ID));

    // Both cascades should fire
    const addressMeta = sm.getFieldMeta(SCOPE_ID, 'address');
    expect(addressMeta?.hidden).toBe(false); // from r2
    expect(addressMeta?.disabled).toBe(true); // from r3
  });

  it('works without getContext (fallback to original context)', () => {
    const engineNoContext = new LinkageEngine({
      stateManager: sm,
      scopeId: SCOPE_ID,
      onFieldValueChange,
      onError,
      // No getContext provided
    });

    const rules: LinkageRule[] = [
      {
        id: 'r1',
        trigger: { fieldCode: 'province', event: 'change' },
        actions: [{ type: 'setValue', target: 'city', value: '"val"' }],
        enabled: true,
      },
      {
        id: 'r2',
        trigger: { fieldCode: 'city', event: 'change' },
        actions: [{ type: 'show', targets: ['district'] }],
        enabled: true,
      },
    ];

    engineNoContext.register(rules);
    // Should still cascade (using original context as fallback)
    engineNoContext.onFieldEvent('province', 'change', sm.getContext(SCOPE_ID));

    expect(onFieldValueChange).toHaveBeenCalledWith('city', 'val');
    expect(sm.getFieldMeta(SCOPE_ID, 'district')?.hidden).toBe(false);
  });

  // Backward compatibility: existing single-level behavior unchanged
  it('single-level behavior unchanged from original', () => {
    engine.register([
      {
        id: 'r1',
        trigger: { fieldCode: 'city', event: 'change' },
        actions: [{ type: 'hide', targets: ['district'] }],
        enabled: true,
      },
    ]);

    engine.onFieldEvent('city', 'change', sm.getContext(SCOPE_ID));
    expect(sm.getFieldMeta(SCOPE_ID, 'district')?.hidden).toBe(true);
  });
});
