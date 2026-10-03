import { describe, expect, it } from 'vitest';
import { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import { DataSourceManager } from '~/framework/meta/runtime/data-pipeline/DataSourceManager';
import { createExpressionContext } from '~/framework/meta/runtime/expression/context';
import { applyFormFieldChange } from '../applyFormFieldChange';
import { canonicalizePageSchemaDto } from '~/framework/meta/utils/canonicalizePageDsl';

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

  it('keeps server DTO linkage through the real page canonicalizer before field changes', () => {
    const runtime = new SchemaRuntime({
      schema: canonicalizePageSchemaDto({
        pid: 'server-owned-form',
        pageKey: 'inv_warehouse_location_form',
        kind: 'form',
        linkageRules: [
          {
            id: 'clear-location-ownership-on-warehouse-change',
            enabled: true,
            trigger: { fieldCode: 'inv_wl_warehouse_id', event: 'change' },
            actions: [
              { type: 'setValue', target: 'inv_loc_zone_id', value: 'null' },
              { type: 'setValue', target: 'inv_loc_parent_id', value: 'null' },
            ],
          },
        ],
      }),
      globalState: { locale: 'zh-CN', theme: 'light', t: (key: string) => key },
      dataSourceManager: new DataSourceManager(createExpressionContext()),
      initialContext: {
        form: {
          inv_wl_warehouse_id: 'main',
          inv_loc_zone_id: 'main-zone',
          inv_loc_parent_id: 'main-location',
          inv_wl_name: 'keep draft',
        },
      },
      disableAutoFetch: true,
    });
    try {
      expect(applyFormFieldChange(runtime, 'inv_wl_warehouse_id', 'ecommerce')).toEqual({
        inv_wl_warehouse_id: 'ecommerce',
        inv_loc_zone_id: null,
        inv_loc_parent_id: null,
      });
      expect(runtime.getStateManager().getContext(runtime.getScopeId()).form?.inv_wl_name).toBe(
        'keep draft',
      );
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
