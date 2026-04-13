/**
 * Integration test: SchemaRuntime + LinkageEngine
 *
 * Verifies that when a UnifiedSchema includes linkageRules,
 * the SchemaRuntime correctly initializes the LinkageEngine
 * and triggerFieldLinkage() updates FieldMeta in the state manager.
 */
import { describe, it, expect } from 'vitest';
import { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import { DataSourceManager } from '~/framework/meta/runtime/data-pipeline/DataSourceManager';
import { createExpressionContext, type GlobalState } from '~/framework/meta/runtime/expression/context';
import type { UnifiedSchema } from '~/framework/meta/schemas/types';

const createGlobalState = (): GlobalState => ({
  locale: 'zh-CN',
  theme: 'light',
  t: (key: string) => key,
});

const createManager = () => new DataSourceManager(createExpressionContext());

function buildSchemaWithLinkage(): UnifiedSchema {
  return {
    kind: 'form',
    version: '1.0.0',
    id: 'linkage-integration-test',
    title: 'Linkage Integration Test',
    layout: {
      type: 'grid',
      cols: 2,
      rowGap: 8,
      colGap: 8,
    },
    blocks: [
      {
        id: 'block_form',
        blockType: 'form-section',
        fields: [
          { field: 'category', label: 'Category', component: 'SmartSelect' },
          { field: 'subcategory', label: 'Sub Category', component: 'SmartSelect' },
          { field: 'details', label: 'Details', component: 'SmartInput' },
          { field: 'reason', label: 'Reason', component: 'SmartInput' },
        ],
      },
    ],
    linkageRules: [
      {
        id: 'rule-show-sub',
        trigger: { fieldCode: 'category', event: 'change' },
        actions: [{ type: 'show', targets: ['subcategory'] }],
        enabled: true,
      },
      {
        id: 'rule-hide-details',
        trigger: {
          fieldCode: 'category',
          event: 'change',
          condition: 'form.category === "other"',
        },
        actions: [{ type: 'hide', targets: ['details'] }],
        enabled: true,
      },
      {
        id: 'rule-disable-reason',
        trigger: { fieldCode: 'category', event: 'change' },
        actions: [{ type: 'disable', targets: ['reason'] }],
        enabled: true,
      },
      {
        id: 'rule-disabled-skip',
        trigger: { fieldCode: 'category', event: 'change' },
        actions: [{ type: 'enable', targets: ['subcategory'] }],
        enabled: false, // disabled rule — should be ignored
      },
    ],
  };
}

describe('SchemaRuntime + LinkageEngine integration', () => {
  it('initializes LinkageEngine from schema.linkageRules', () => {
    const runtime = new SchemaRuntime({
      schema: buildSchemaWithLinkage(),
      globalState: createGlobalState(),
      dataSourceManager: createManager(),
      disableAutoFetch: true,
    });

    // After construction, the engine should be ready
    // Trigger a change on "category"
    const sm = runtime.getStateManager();
    const scopeId = runtime.getScopeId();

    // Set form value first so condition can check it
    sm.updateField(scopeId, 'category', 'electronics');

    runtime.triggerFieldLinkage('category', 'change');

    // rule-show-sub: subcategory → hidden=false
    expect(sm.getFieldMeta(scopeId, 'subcategory')?.hidden).toBe(false);

    // rule-hide-details: condition is form.category==="other" → false for "electronics"
    expect(sm.getFieldMeta(scopeId, 'details')?.hidden).toBeUndefined();

    // rule-disable-reason: reason → disabled=true
    expect(sm.getFieldMeta(scopeId, 'reason')?.disabled).toBe(true);

    runtime.destroy();
  });

  it('evaluates condition and applies action when condition is met', () => {
    const runtime = new SchemaRuntime({
      schema: buildSchemaWithLinkage(),
      globalState: createGlobalState(),
      dataSourceManager: createManager(),
      disableAutoFetch: true,
    });

    const sm = runtime.getStateManager();
    const scopeId = runtime.getScopeId();

    sm.updateField(scopeId, 'category', 'other');
    runtime.triggerFieldLinkage('category', 'change');

    // rule-hide-details: condition form.category==="other" is TRUE → details hidden
    expect(sm.getFieldMeta(scopeId, 'details')?.hidden).toBe(true);

    runtime.destroy();
  });

  it('does not initialize LinkageEngine when no linkageRules', () => {
    const schema: UnifiedSchema = {
      kind: 'form',
      version: '1.0.0',
      id: 'no-linkage-test',
      title: 'No Linkage',
      layout: {
        type: 'grid',
        cols: 1,
      },
      blocks: [],
    };

    const runtime = new SchemaRuntime({
      schema,
      globalState: createGlobalState(),
      dataSourceManager: createManager(),
      disableAutoFetch: true,
    });

    // triggerFieldLinkage should be a no-op (no engine)
    runtime.triggerFieldLinkage('anyField', 'change');

    runtime.destroy();
  });

  it('cleans up LinkageEngine on destroy', () => {
    const runtime = new SchemaRuntime({
      schema: buildSchemaWithLinkage(),
      globalState: createGlobalState(),
      dataSourceManager: createManager(),
      disableAutoFetch: true,
    });

    runtime.destroy();

    // After destroy, triggerFieldLinkage should not crash
    runtime.triggerFieldLinkage('category', 'change');
  });
});
