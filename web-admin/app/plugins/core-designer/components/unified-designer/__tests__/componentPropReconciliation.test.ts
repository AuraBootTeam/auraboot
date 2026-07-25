import { describe, it, expect } from 'vitest';
import {
  pruneStaleFieldComponentProps,
  defaultInspectorSchemaRegistry,
} from '../registry/InspectorSchemaRegistry';
import type { DslBlockV3 } from '../types';

/**
 * Reconciliation of a field block's props when its `component` changes.
 *
 * The bug this guards: the inspector only renders the control for the CURRENT
 * component, so a prop authored for the OLD component (upload's multiple/accept/
 * maxFiles, a picker's pickerSource/displayField, …) survives invisibly and keeps
 * altering the new control's runtime behaviour. pruneStaleFieldComponentProps drops
 * exactly those component-specific-but-not-for-the-new-component props while keeping
 * shared props and props valid for the new component.
 *
 * The pruning rule is SCHEMA-DERIVED: these tests also assert the rule matches the
 * per-component inspector schemas (getFieldsForBlock), so it can't silently drift
 * from InspectorSchemaRegistry.
 */

/** Prop keys the inspector actually renders for a given component, minus `props.`. */
function inspectorPropKeysFor(component: string): Set<string> {
  const block = {
    id: 'probe',
    blockType: 'field',
    props: { component },
  } as unknown as DslBlockV3;
  return new Set(
    defaultInspectorSchemaRegistry
      .getFieldsForBlock(block)
      .map((field) => field.key)
      .filter((key) => key.startsWith('props.'))
      .map((key) => key.slice('props.'.length)),
  );
}

describe('pruneStaleFieldComponentProps — component switch reconciliation', () => {
  const SHARED = {
    label: 'My field',
    required: true,
    readOnly: false,
    helpText: 'Some help',
    placeholder: 'Enter…',
    visibleWhen: { field: 'x', operator: 'equals', value: 'y' },
    validationRules: [{ type: 'min', value: 1 }],
    options: [{ label: 'A', value: 'a' }],
    dataType: 'string',
    dictCode: 'STATUS',
  };

  it('upload → select drops upload-specific props, keeps shared props', () => {
    const props = {
      component: 'select',
      ...SHARED,
      // upload leftovers
      accept: '.pdf,.docx',
      multiple: true,
      maxFiles: 2,
    };
    const next = pruneStaleFieldComponentProps(props, 'select');

    // dropped
    expect(next).not.toHaveProperty('accept');
    expect(next).not.toHaveProperty('multiple');
    expect(next).not.toHaveProperty('maxFiles');
    // kept — shared props survive untouched
    expect(next).toMatchObject({ component: 'select', ...SHARED });
    // a new object is returned (does not mutate the input)
    expect(next).not.toBe(props);
    expect(props).toHaveProperty('multiple', true);
  });

  it('upload → input drops upload-specific props', () => {
    const next = pruneStaleFieldComponentProps(
      { component: 'input', label: 'L', accept: '*', multiple: true, maxFiles: 3 },
      'input',
    );
    expect(next).toEqual({ component: 'input', label: 'L' });
  });

  it('select → picker keeps picker-specific props authored for the new component', () => {
    const props = {
      component: 'picker',
      label: 'Owner',
      // picker-specific — valid for the NEW component, must be kept
      pickerDataSource: 'model',
      pickerSource: 'sys_user',
      displayField: 'name',
      valueField: 'id',
      searchable: true,
    };
    const next = pruneStaleFieldComponentProps(props, 'picker');
    // nothing to prune → same reference returned
    expect(next).toBe(props);
    expect(next).toMatchObject(props);
  });

  it('picker → input drops every picker-specific prop, keeps shared', () => {
    const props = {
      component: 'input',
      label: 'Owner',
      required: true,
      pickerDataSource: 'model',
      pickerSource: 'sys_user',
      pickerQueryCode: 'q1',
      displayField: 'name',
      valueField: 'id',
      searchable: true,
      searchPlaceholder: 'Search…',
      pageSize: 20,
      pickerParameters: { a: 1 },
    };
    const next = pruneStaleFieldComponentProps(props, 'input');
    expect(next).toEqual({ component: 'input', label: 'Owner', required: true });
  });

  it('picker → upload swaps specific props: picker ones drop, upload one survives', () => {
    const props = {
      component: 'upload',
      label: 'Attachment',
      // picker leftovers — must drop
      pickerDataSource: 'model',
      displayField: 'name',
      // upload prop authored after switching — valid for upload, must keep
      accept: '.pdf',
      multiple: true,
    };
    const next = pruneStaleFieldComponentProps(props, 'upload');
    expect(next).not.toHaveProperty('pickerDataSource');
    expect(next).not.toHaveProperty('displayField');
    expect(next).toMatchObject({ component: 'upload', label: 'Attachment', accept: '.pdf', multiple: true });
  });

  it('rich-text → input drops the rich-text toolbar prop', () => {
    const next = pruneStaleFieldComponentProps(
      { component: 'input', label: 'Notes', richTextToolbar: ['bold', 'italic'] },
      'input',
    );
    expect(next).toEqual({ component: 'input', label: 'Notes' });
  });

  it('input → rich-text keeps the rich-text toolbar prop', () => {
    const props = { component: 'rich-text', label: 'Notes', richTextToolbar: ['bold'] };
    const next = pruneStaleFieldComponentProps(props, 'rich-text');
    expect(next).toBe(props);
    expect(next).toMatchObject(props);
  });

  it('is case-insensitive on the new component name (matches getComponentName)', () => {
    const next = pruneStaleFieldComponentProps(
      { component: 'UPLOAD', accept: '.pdf', multiple: true, label: 'L' },
      'UPLOAD',
    );
    // accept/multiple are valid for upload → kept even though value is upper-cased
    expect(next).toMatchObject({ accept: '.pdf', multiple: true, label: 'L' });
  });

  it('never prunes shared field props regardless of target component', () => {
    const shared = { component: 'input', ...SHARED };
    for (const target of ['input', 'select', 'multiselect', 'date', 'number', 'checkbox']) {
      const next = pruneStaleFieldComponentProps({ ...shared, component: target }, target);
      expect(next).toMatchObject(SHARED);
    }
  });

  it('returns the same reference (no rebuild) when there is nothing to prune', () => {
    const props = { component: 'input', label: 'L', required: true };
    expect(pruneStaleFieldComponentProps(props, 'input')).toBe(props);
  });

  it('tolerates undefined props', () => {
    expect(pruneStaleFieldComponentProps(undefined, 'input')).toBeUndefined();
  });

  // ── Mutation guard: the rule must actually be schema-derived ──────────────────
  // If someone edits a per-component schema (adds/removes a control) the prune
  // universe must move with it. These assertions fail if the two ever diverge.

  it('every prop the inspector renders for the NEW component is preserved', () => {
    // For each authored component that HAS specific controls, build props containing
    // one leftover from EVERY other component, then prune to this component. Nothing
    // the inspector shows for this component may be dropped.
    const componentsWithSpecificControls = ['picker', 'upload', 'rich-text'];
    for (const target of componentsWithSpecificControls) {
      const targetKeys = inspectorPropKeysFor(target);
      // author every specific key across all components, then prune to `target`
      const kitchenSink: Record<string, unknown> = { component: target };
      for (const other of componentsWithSpecificControls) {
        for (const key of inspectorPropKeysFor(other)) kitchenSink[key] = `v_${key}`;
      }
      const next = pruneStaleFieldComponentProps(kitchenSink, target) as Record<string, unknown>;
      for (const key of targetKeys) {
        expect(next, `prune to '${target}' must keep its own control '${key}'`).toHaveProperty(key);
      }
    }
  });

  it("drops another component's specific prop that the new component does not render", () => {
    // upload does not render pickerDataSource; switching to upload must drop it.
    expect(inspectorPropKeysFor('upload').has('pickerDataSource')).toBe(false);
    const next = pruneStaleFieldComponentProps(
      { component: 'upload', pickerDataSource: 'model' },
      'upload',
    );
    expect(next).not.toHaveProperty('pickerDataSource');
  });
});
