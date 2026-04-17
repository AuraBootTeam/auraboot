/**
 * bpm-schema.test.ts
 *
 * Verifies Designer PropertySchema wiring for the BPM closure feature
 * (OSS BPM Closure Spec 1, Task 15):
 *
 *   1. action.type === 'bpm' option is present on toolbar + form-buttons
 *      blocks, with 3 conditional fields (processDefinitionKey / businessKeyField
 *      / variables) gated on dependsOn.
 *   2. bpm-panel block is registered in BlockRegistry with a 3-field schema
 *      (processDefinitionKey / businessKeyField / sections multiselect).
 *
 * These are schema-structure assertions — the full render pipeline is
 * covered by existing E2E tests for SchemaBlockConfigPanel / ButtonConfigPanel.
 */

import { describe, it, expect, beforeAll } from 'vitest';

import { BlockRegistry } from '../../block-registry';
import { registerAllBlocks } from '../index';
import { toolbarBlock, formButtonsBlock, bpmPanelBlock } from '../index';
import type { PropertySchema } from '~/shared/designer/types';

beforeAll(() => {
  // Idempotent: BlockRegistry.register() overwrites on dup, so safe to call twice.
  registerAllBlocks();
});

// ─────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────

function findField(schema: PropertySchema<string>[], key: string) {
  return schema.find((f) => f.key === key);
}

function actionTypeOptions(schema: PropertySchema<string>[]) {
  const field = findField(schema, 'action.type');
  if (!field || !field.options) return [];
  return field.options.map((o) => o.value);
}

// ─────────────────────────────────────────────────────────────────────────
// action.type = 'bpm' on toolbar / form-buttons
// ─────────────────────────────────────────────────────────────────────────

describe('toolbar block — action.type=bpm schema', () => {
  it('exposes "bpm" as an action.type option alongside existing types', () => {
    const values = actionTypeOptions(toolbarBlock.schema);
    expect(values).toContain('bpm');
    // Pre-existing options must not regress
    expect(values).toEqual(
      expect.arrayContaining(['command', 'navigate', 'builtin', 'flow', 'flow_steps', 'bpm']),
    );
  });

  it('defines 3 BPM-specific fields gated on action.type=bpm', () => {
    const pdk = findField(toolbarBlock.schema, 'action.processDefinitionKey');
    const bkf = findField(toolbarBlock.schema, 'action.businessKeyField');
    const vars = findField(toolbarBlock.schema, 'action.variables');

    expect(pdk, 'processDefinitionKey field should be present').toBeDefined();
    expect(pdk?.type).toBe('process-select');
    expect(pdk?.required).toBe(true);
    expect(pdk?.dependsOn).toEqual({ field: 'action.type', value: 'bpm' });

    expect(bkf, 'businessKeyField field should be present').toBeDefined();
    expect(bkf?.type).toBe('field-select');
    expect(bkf?.required).toBe(true);
    expect(bkf?.dependsOn).toEqual({ field: 'action.type', value: 'bpm' });

    expect(vars, 'variables field should be present').toBeDefined();
    expect(vars?.type).toBe('json');
    expect(vars?.dependsOn).toEqual({ field: 'action.type', value: 'bpm' });
  });
});

describe('form-buttons block — action.type=bpm schema', () => {
  it('exposes "bpm" as an action.type option', () => {
    const values = actionTypeOptions(formButtonsBlock.schema);
    expect(values).toContain('bpm');
  });

  it('defines the same 3 BPM fields as toolbar, all gated on action.type=bpm', () => {
    const pdk = findField(formButtonsBlock.schema, 'action.processDefinitionKey');
    const bkf = findField(formButtonsBlock.schema, 'action.businessKeyField');
    const vars = findField(formButtonsBlock.schema, 'action.variables');

    expect(pdk?.type).toBe('process-select');
    expect(pdk?.dependsOn).toEqual({ field: 'action.type', value: 'bpm' });
    expect(bkf?.type).toBe('field-select');
    expect(bkf?.dependsOn).toEqual({ field: 'action.type', value: 'bpm' });
    expect(vars?.type).toBe('json');
    expect(vars?.dependsOn).toEqual({ field: 'action.type', value: 'bpm' });
  });
});

// ─────────────────────────────────────────────────────────────────────────
// bpm-panel block registration
// ─────────────────────────────────────────────────────────────────────────

describe('bpm-panel block — BlockRegistry entry', () => {
  it('is registered under type "bpm-panel" after registerAllBlocks()', () => {
    const def = BlockRegistry.get('bpm-panel');
    expect(def, 'bpm-panel must be in BlockRegistry').toBeDefined();
    expect(def).toBe(bpmPanelBlock);
    expect(def?.category).toBe('display');
    expect(def?.defaultColSpan).toBe(12);
  });

  it('defines processDefinitionKey (process-select, required)', () => {
    const pdk = findField(bpmPanelBlock.schema, 'processDefinitionKey');
    expect(pdk, 'processDefinitionKey field should be present').toBeDefined();
    expect(pdk?.type).toBe('process-select');
    expect(pdk?.required).toBe(true);
  });

  it('defines businessKeyField (field-select, optional, defaults to record.pid)', () => {
    const bkf = findField(bpmPanelBlock.schema, 'businessKeyField');
    expect(bkf, 'businessKeyField field should be present').toBeDefined();
    expect(bkf?.type).toBe('field-select');
    // Optional — no required flag
    expect(bkf?.required).not.toBe(true);
  });

  it('defines sections multiselect with 4 options and full default', () => {
    const sections = findField(bpmPanelBlock.schema, 'sections');
    expect(sections, 'sections field should be present').toBeDefined();
    expect(sections?.type).toBe('multiselect');

    const values = sections?.options?.map((o) => o.value) ?? [];
    expect(values).toEqual(['status', 'diagram', 'operations', 'history']);

    expect(sections?.defaultValue).toEqual(['status', 'diagram', 'operations', 'history']);
  });
});
