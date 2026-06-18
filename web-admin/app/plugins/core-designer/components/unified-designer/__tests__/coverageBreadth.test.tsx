import { describe, expect, it } from 'vitest';
import {
  resolveBlockDropIntent,
  type DropCapabilities,
  type DragData,
} from '../dnd/dndShared';
import { defaultInspectorSchemaRegistry } from '../registry/InspectorSchemaRegistry';
import type { DslBlockV3 } from '../types';

/**
 * A7 + A12 coverage breadth (roadmap tail).
 *  - A7: the drop-intent logic (`resolveBlockDropIntent`) is the testable core of
 *    the mid-drag drop indicator (before / inside / null). Palette + model-field
 *    drags are covered in dndShared.test; this adds the MOVE-BLOCK branch and the
 *    same-target guard. The transient mid-gesture *visual* itself is geometry and
 *    is exercised by the drag E2E, not jsdom.
 *  - A12: `getFieldInspectorFields` swaps the inspector schema by the field's
 *    component (picker / upload / rich-text / default) — verify each branch.
 *  - A11 (chart-type breadth) is covered by e1WidgetCharts.test (all chart types).
 */

const NO_CAPS: DropCapabilities = {
  canAddBlockBeforeTarget: () => false,
  canAddBlockToParent: () => false,
  canAddModelFieldBeforeTarget: () => false,
  canAddModelFieldToParent: () => false,
  canMoveBlockBeforeTarget: () => false,
  canMoveBlockToParent: () => false,
};
const caps = (overrides: Partial<DropCapabilities> = {}): DropCapabilities => ({
  ...NO_CAPS,
  ...overrides,
});

describe('A7 — resolveBlockDropIntent: move-block branch', () => {
  const drag: DragData = { kind: 'canvas-block', blockId: 'moving' };

  it('prefers before, falls back to inside, else null when moving a canvas block', () => {
    expect(resolveBlockDropIntent(drag, 'target', caps({ canMoveBlockBeforeTarget: () => true }))).toBe(
      'before',
    );
    expect(resolveBlockDropIntent(drag, 'target', caps({ canMoveBlockToParent: () => true }))).toBe(
      'inside',
    );
    expect(resolveBlockDropIntent(drag, 'target', caps())).toBeNull();
  });

  it('returns null when a block is dragged over itself (no self-drop indicator)', () => {
    expect(
      resolveBlockDropIntent(
        { kind: 'canvas-block', blockId: 'same' },
        'same',
        caps({ canMoveBlockBeforeTarget: () => true, canMoveBlockToParent: () => true }),
      ),
    ).toBeNull();
  });
});

describe('A12 — getFieldInspectorFields: per-component inspector schema', () => {
  const fieldsFor = (component: string | undefined): string[] => {
    const block = { id: 'f', blockType: 'field', props: { component } } as unknown as DslBlockV3;
    return defaultInspectorSchemaRegistry.getFieldsForBlock(block).map((field) => field.key);
  };

  it('picker component surfaces the picker data-source controls', () => {
    const keys = fieldsFor('picker');
    expect(keys).toContain('props.pickerDataSource');
    expect(keys).toContain('props.valueField');
    expect(keys).toContain('props.displayField');
  });

  it('upload component surfaces the upload controls', () => {
    const keys = fieldsFor('upload');
    expect(keys).toContain('props.accept');
    expect(keys).toContain('props.maxFiles');
    expect(keys).not.toContain('props.pickerDataSource');
  });

  it('rich-text component surfaces the rich-text toolbar control', () => {
    const keys = fieldsFor('rich-text');
    expect(keys).toContain('props.richTextToolbar');
    expect(keys).not.toContain('props.accept');
  });

  it('a plain input component surfaces neither picker nor upload extras', () => {
    const keys = fieldsFor('input');
    expect(keys).not.toContain('props.pickerDataSource');
    expect(keys).not.toContain('props.accept');
    expect(keys).not.toContain('props.richTextToolbar');
    // but always carries the base field controls.
    expect(keys).toContain('props.label');
    expect(keys).toContain('props.dataType');
  });

  it('filter-field adds the operator selector on top of the field schema', () => {
    const block = {
      id: 'ff',
      blockType: 'filter-field',
      props: { component: 'input' },
    } as unknown as DslBlockV3;
    const keys = defaultInspectorSchemaRegistry.getFieldsForBlock(block).map((field) => field.key);
    expect(keys).toContain('props.operator');
  });
});
