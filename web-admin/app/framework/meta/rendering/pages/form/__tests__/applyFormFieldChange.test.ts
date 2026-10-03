import { describe, expect, it } from 'vitest';
import { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import { DataSourceManager } from '~/framework/meta/runtime/data-pipeline/DataSourceManager';
import { createExpressionContext } from '~/framework/meta/runtime/expression/context';
import { applyFormFieldChange } from '../applyFormFieldChange';

function makeRuntime(enabled = true) {
  return new SchemaRuntime({
    schema: {
      kind: 'form',
      version: '1.0.0',
      id: 'controlled-dependent-form',
      title: 'Dependent Form',
      layout: { type: 'grid', cols: 2 },
      blocks: [],
      linkageRules: [
        {
          id: 'clear-on-parent',
          enabled,
          trigger: {
            fieldCode: 'warehouse',
            event: 'change',
            condition: 'form.warehouse === "new-warehouse"',
          },
          actions: [{ type: 'setValue', target: 'zone', value: 'null' }],
        },
        {
          id: 'clear-child',
          enabled: true,
          trigger: { fieldCode: 'zone', event: 'change' },
          actions: [{ type: 'setValue', target: 'location', value: 'null' }],
        },
      ],
    },
    globalState: { locale: 'zh-CN', theme: 'light', t: (key: string) => key },
    initialContext: {
      form: {
        warehouse: 'old-warehouse',
        zone: 'old-zone',
        location: 'old-location',
        description: 'keep this draft',
      },
    },
    dataSourceManager: new DataSourceManager(createExpressionContext()),
    disableAutoFetch: true,
  });
}

describe('controlled form linkage', () => {
  it('uses the new parent for conditions, cascades clears and preserves unrelated draft fields', () => {
    const runtime = makeRuntime();
    try {
      const patch = applyFormFieldChange(runtime, 'warehouse', 'new-warehouse');
      expect(patch).toEqual({ warehouse: 'new-warehouse', zone: null, location: null });
      expect(runtime.getStateManager().getContext(runtime.getScopeId()).form).toEqual({
        warehouse: 'new-warehouse',
        zone: null,
        location: null,
        description: 'keep this draft',
      });
    } finally {
      runtime.destroy();
    }
  });

  it('does not clear children for an unmet condition or a disabled rule', () => {
    for (const [enabled, value] of [
      [true, 'other-warehouse'],
      [false, 'new-warehouse'],
    ] as const) {
      const runtime = makeRuntime(enabled);
      try {
        expect(applyFormFieldChange(runtime, 'warehouse', value)).toEqual({ warehouse: value });
        expect(runtime.getStateManager().getContext(runtime.getScopeId()).form?.zone).toBe(
          'old-zone',
        );
      } finally {
        runtime.destroy();
      }
    }
  });

  it('keeps controlled forms without runtime usable', () => {
    expect(applyFormFieldChange(null, 'name', 'draft')).toEqual({ name: 'draft' });
  });
});
