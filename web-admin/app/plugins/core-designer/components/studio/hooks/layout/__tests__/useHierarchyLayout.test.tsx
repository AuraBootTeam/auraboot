/**
 * Unit tests for useHierarchyLayout hook.
 *
 * No external service dependencies — pure hierarchy state logic.
 */

import { renderHook, act } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import { useHierarchyLayout } from '../useHierarchyLayout';
import type { CanvasSchema } from '~/plugins/core-designer/components/studio/workbench/canvas/types';
import { DEFAULT_HIERARCHY } from '~/plugins/core-designer/components/studio/domain/schema/layout-hierarchy';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function makeSchema(withHierarchy = false): CanvasSchema {
  const base: CanvasSchema = {
    id: 'test-page',
    kind: 'form',
    title: 'Test Page',
    version: '1.0.0',
    components: [],
    layout: { type: 'grid', columns: 12, spacing: 16, padding: 16 },
    metadata: { createdAt: '2024-01-01', updatedAt: '2024-01-01', createdBy: 'test' },
  };
  if (withHierarchy) {
    return { ...base, hierarchy: JSON.parse(JSON.stringify(DEFAULT_HIERARCHY)) };
  }
  return base;
}

function setup(withHierarchy = true) {
  const onSchemaChange = vi.fn();
  const schema = makeSchema(withHierarchy);
  const { result, rerender } = renderHook(
    ({ s }: { s: CanvasSchema }) => useHierarchyLayout({ schema: s, onSchemaChange }),
    { initialProps: { s: schema } },
  );
  return { result, rerender, onSchemaChange, schema };
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

describe('useHierarchyLayout — initial state', () => {
  it('reports isHierarchyMode=true when schema has hierarchy', () => {
    const { result } = setup(true);
    expect(result.current.isHierarchyMode).toBe(true);
  });

  it('reports isHierarchyMode=false when schema lacks hierarchy', () => {
    const { result } = setup(false);
    expect(result.current.isHierarchyMode).toBe(false);
  });

  it('selection starts empty', () => {
    const { result } = setup(true);
    expect(result.current.selection).toEqual({});
  });

  it('hierarchy reflects DEFAULT_HIERARCHY when schema has no hierarchy', () => {
    const { result } = setup(false);
    expect(result.current.hierarchy.type).toBe('tab-container');
  });
});

// ---------------------------------------------------------------------------
// Selection
// ---------------------------------------------------------------------------

describe('useHierarchyLayout — selection', () => {
  it('selectTab sets tabId', () => {
    const { result } = setup();
    act(() => result.current.selectTab('tab-1'));
    expect(result.current.selection.tabId).toBe('tab-1');
    expect(result.current.selection.floorId).toBeUndefined();
  });

  it('selectFloor sets tabId + floorId', () => {
    const { result } = setup();
    act(() => result.current.selectFloor('t1', 'f1'));
    expect(result.current.selection).toEqual({ tabId: 't1', floorId: 'f1' });
  });

  it('selectBlock sets tabId + floorId + blockId', () => {
    const { result } = setup();
    act(() => result.current.selectBlock('t1', 'f1', 'b1'));
    expect(result.current.selection).toEqual({ tabId: 't1', floorId: 'f1', blockId: 'b1' });
  });

  it('selectField sets all four levels', () => {
    const { result } = setup();
    act(() => result.current.selectField('t1', 'f1', 'b1', 'field1'));
    expect(result.current.selection).toEqual({
      tabId: 't1',
      floorId: 'f1',
      blockId: 'b1',
      fieldId: 'field1',
    });
  });

  it('clearSelection resets to empty object', () => {
    const { result } = setup();
    act(() => result.current.selectBlock('t1', 'f1', 'b1'));
    act(() => result.current.clearSelection());
    expect(result.current.selection).toEqual({});
  });
});

// ---------------------------------------------------------------------------
// Tab operations
// ---------------------------------------------------------------------------

describe('useHierarchyLayout — tab operations', () => {
  it('addTab calls onSchemaChange with a new tab added', () => {
    const { result, onSchemaChange } = setup();
    act(() => result.current.addTab('New Tab'));
    expect(onSchemaChange).toHaveBeenCalledOnce();
    const [newSchema] = onSchemaChange.mock.calls[0];
    expect(newSchema.hierarchy.tabs).toHaveLength(2);
    expect(newSchema.hierarchy.tabs[1].label).toBe('New Tab');
  });

  it('removeTab removes the tab with the given ID', () => {
    const onSchemaChange = vi.fn();
    // Build a schema with 2 tabs
    const schema = makeSchema(true);
    schema.hierarchy!.tabs.push({
      id: 'tab-second',
      code: 'second',
      label: 'Second',
      floors: [],
    });
    const { result } = renderHook(() =>
      useHierarchyLayout({ schema, onSchemaChange }),
    );
    act(() => result.current.removeTab('tab-second'));
    const [newSchema] = onSchemaChange.mock.calls[0];
    expect(newSchema.hierarchy.tabs).toHaveLength(1);
    expect(newSchema.hierarchy.tabs[0].id).toBe('tab-default');
  });

  it('removeTab refuses to remove the last remaining tab', () => {
    const { result, onSchemaChange } = setup();
    act(() => result.current.removeTab('tab-default'));
    const [newSchema] = onSchemaChange.mock.calls[0];
    // Still 1 tab — removal was blocked
    expect(newSchema.hierarchy.tabs).toHaveLength(1);
  });

  it('updateTab merges updates into the matching tab', () => {
    const { result, onSchemaChange } = setup();
    act(() => result.current.updateTab('tab-default', { label: 'Renamed' }));
    const [newSchema] = onSchemaChange.mock.calls[0];
    expect(newSchema.hierarchy.tabs[0].label).toBe('Renamed');
  });

  it('moveTab swaps the tab left/right', () => {
    const onSchemaChange = vi.fn();
    const schema = makeSchema(true);
    schema.hierarchy!.tabs.push({
      id: 'tab-b',
      code: 'b',
      label: 'B',
      floors: [],
    });
    const { result } = renderHook(() =>
      useHierarchyLayout({ schema, onSchemaChange }),
    );
    act(() => result.current.moveTab('tab-b', 'left'));
    const [newSchema] = onSchemaChange.mock.calls[0];
    expect(newSchema.hierarchy.tabs[0].id).toBe('tab-b');
    expect(newSchema.hierarchy.tabs[1].id).toBe('tab-default');
  });

  it('moveTab does nothing when already at boundary', () => {
    const { result, onSchemaChange } = setup();
    act(() => result.current.moveTab('tab-default', 'left'));
    const [newSchema] = onSchemaChange.mock.calls[0];
    expect(newSchema.hierarchy.tabs[0].id).toBe('tab-default');
  });
});

// ---------------------------------------------------------------------------
// Floor operations
// ---------------------------------------------------------------------------

describe('useHierarchyLayout — floor operations', () => {
  it('addFloor appends a new floor to the given tab', () => {
    const { result, onSchemaChange } = setup();
    act(() => result.current.addFloor('tab-default', 'Section 2'));
    const [newSchema] = onSchemaChange.mock.calls[0];
    const floors = newSchema.hierarchy.tabs[0].floors;
    expect(floors).toHaveLength(2);
    expect(floors[1].title).toBe('Section 2');
  });

  it('addFloor does nothing for an unknown tabId', () => {
    const { result, onSchemaChange } = setup();
    act(() => result.current.addFloor('no-such-tab', 'X'));
    const [newSchema] = onSchemaChange.mock.calls[0];
    expect(newSchema.hierarchy.tabs[0].floors).toHaveLength(1);
  });

  it('removeFloor removes the floor when >1 floors exist', () => {
    const onSchemaChange = vi.fn();
    const schema = makeSchema(true);
    schema.hierarchy!.tabs[0].floors.push({
      id: 'floor-extra',
      code: 'extra',
      title: 'Extra',
      collapsible: false,
      collapsed: false,
      blocks: [],
    });
    const { result } = renderHook(() =>
      useHierarchyLayout({ schema, onSchemaChange }),
    );
    act(() => result.current.removeFloor('tab-default', 'floor-extra'));
    const [newSchema] = onSchemaChange.mock.calls[0];
    expect(newSchema.hierarchy.tabs[0].floors).toHaveLength(1);
  });

  it('removeFloor refuses to remove the last floor', () => {
    const { result, onSchemaChange } = setup();
    act(() => result.current.removeFloor('tab-default', 'floor-default'));
    const [newSchema] = onSchemaChange.mock.calls[0];
    expect(newSchema.hierarchy.tabs[0].floors).toHaveLength(1);
  });

  it('toggleFloorCollapse inverts the collapsed flag', () => {
    const { result, onSchemaChange } = setup();
    act(() => result.current.toggleFloorCollapse('tab-default', 'floor-default'));
    const [newSchema] = onSchemaChange.mock.calls[0];
    // DEFAULT_HIERARCHY floor collapsed=false → should become true
    expect(newSchema.hierarchy.tabs[0].floors[0].collapsed).toBe(true);
  });

  it('updateFloor merges updates into the matching floor', () => {
    const { result, onSchemaChange } = setup();
    act(() => result.current.updateFloor('tab-default', 'floor-default', { title: 'Renamed Floor' }));
    const [newSchema] = onSchemaChange.mock.calls[0];
    expect(newSchema.hierarchy.tabs[0].floors[0].title).toBe('Renamed Floor');
  });

  it('moveFloor swaps floors up/down', () => {
    const onSchemaChange = vi.fn();
    const schema = makeSchema(true);
    schema.hierarchy!.tabs[0].floors.push({
      id: 'floor-b',
      code: 'b',
      title: 'Floor B',
      collapsible: false,
      collapsed: false,
      blocks: [],
    });
    const { result } = renderHook(() =>
      useHierarchyLayout({ schema, onSchemaChange }),
    );
    act(() => result.current.moveFloor('tab-default', 'floor-b', 'up'));
    const [newSchema] = onSchemaChange.mock.calls[0];
    expect(newSchema.hierarchy.tabs[0].floors[0].id).toBe('floor-b');
  });
});

// ---------------------------------------------------------------------------
// Block operations
// ---------------------------------------------------------------------------

describe('useHierarchyLayout — block operations', () => {
  it('addBlock appends a new block to the given floor', () => {
    const { result, onSchemaChange } = setup();
    act(() => result.current.addBlock('tab-default', 'floor-default'));
    const [newSchema] = onSchemaChange.mock.calls[0];
    expect(newSchema.hierarchy.tabs[0].floors[0].blocks).toHaveLength(2);
  });

  it('removeBlock removes a block when >1 exist', () => {
    const onSchemaChange = vi.fn();
    const schema = makeSchema(true);
    schema.hierarchy!.tabs[0].floors[0].blocks.push({
      id: 'block-extra',
      code: 'extra',
      layout: { type: 'grid', columns: 2, gap: 8 },
      fields: [],
    });
    const { result } = renderHook(() =>
      useHierarchyLayout({ schema, onSchemaChange }),
    );
    act(() => result.current.removeBlock('tab-default', 'floor-default', 'block-extra'));
    const [newSchema] = onSchemaChange.mock.calls[0];
    expect(newSchema.hierarchy.tabs[0].floors[0].blocks).toHaveLength(1);
  });

  it('removeBlock refuses to remove the last block', () => {
    const { result, onSchemaChange } = setup();
    act(() => result.current.removeBlock('tab-default', 'floor-default', 'block-default'));
    const [newSchema] = onSchemaChange.mock.calls[0];
    expect(newSchema.hierarchy.tabs[0].floors[0].blocks).toHaveLength(1);
  });

  it('updateBlock merges updates into the matching block', () => {
    const { result, onSchemaChange } = setup();
    act(() =>
      result.current.updateBlock('tab-default', 'floor-default', 'block-default', {
        title: 'My Block',
      }),
    );
    const [newSchema] = onSchemaChange.mock.calls[0];
    expect(newSchema.hierarchy.tabs[0].floors[0].blocks[0].title).toBe('My Block');
  });
});

// ---------------------------------------------------------------------------
// Field operations
// ---------------------------------------------------------------------------

describe('useHierarchyLayout — field operations', () => {
  it('addField appends a FieldCell to the given block', () => {
    const { result, onSchemaChange } = setup();
    act(() =>
      result.current.addField('tab-default', 'floor-default', 'block-default', 'name', 'TextInput'),
    );
    const [newSchema] = onSchemaChange.mock.calls[0];
    const fields = newSchema.hierarchy.tabs[0].floors[0].blocks[0].fields;
    expect(fields).toHaveLength(1);
    expect(fields[0].fieldCode).toBe('name');
    expect(fields[0].componentType).toBe('TextInput');
  });

  it('removeField removes the matching field', () => {
    const onSchemaChange = vi.fn();
    const schema = makeSchema(true);
    schema.hierarchy!.tabs[0].floors[0].blocks[0].fields.push({
      id: 'field-abc',
      fieldCode: 'qty',
      componentType: 'NumberInput',
      props: {},
    });
    const { result } = renderHook(() =>
      useHierarchyLayout({ schema, onSchemaChange }),
    );
    act(() =>
      result.current.removeField('tab-default', 'floor-default', 'block-default', 'field-abc'),
    );
    const [newSchema] = onSchemaChange.mock.calls[0];
    expect(newSchema.hierarchy.tabs[0].floors[0].blocks[0].fields).toHaveLength(0);
  });

  it('updateField merges updates into the matching field', () => {
    const onSchemaChange = vi.fn();
    const schema = makeSchema(true);
    schema.hierarchy!.tabs[0].floors[0].blocks[0].fields.push({
      id: 'field-xyz',
      fieldCode: 'status',
      componentType: 'Select',
      props: {},
    });
    const { result } = renderHook(() =>
      useHierarchyLayout({ schema, onSchemaChange }),
    );
    act(() =>
      result.current.updateField('tab-default', 'floor-default', 'block-default', 'field-xyz', {
        label: 'Status Label',
      }),
    );
    const [newSchema] = onSchemaChange.mock.calls[0];
    expect(newSchema.hierarchy.tabs[0].floors[0].blocks[0].fields[0].label).toBe('Status Label');
  });

  it('moveField reorders fields up/down', () => {
    const onSchemaChange = vi.fn();
    const schema = makeSchema(true);
    schema.hierarchy!.tabs[0].floors[0].blocks[0].fields = [
      { id: 'f1', fieldCode: 'a', componentType: 'Text', props: {} },
      { id: 'f2', fieldCode: 'b', componentType: 'Text', props: {} },
    ];
    const { result } = renderHook(() =>
      useHierarchyLayout({ schema, onSchemaChange }),
    );
    act(() =>
      result.current.moveField('tab-default', 'floor-default', 'block-default', 'f2', 'up'),
    );
    const [newSchema] = onSchemaChange.mock.calls[0];
    const fields = newSchema.hierarchy.tabs[0].floors[0].blocks[0].fields;
    expect(fields[0].id).toBe('f2');
    expect(fields[1].id).toBe('f1');
  });
});

// ---------------------------------------------------------------------------
// Mode switching
// ---------------------------------------------------------------------------

describe('useHierarchyLayout — hierarchy mode', () => {
  it('enableHierarchyMode sets hierarchy on schema without one', () => {
    const { result, onSchemaChange } = setup(false);
    act(() => result.current.enableHierarchyMode());
    const [newSchema] = onSchemaChange.mock.calls[0];
    expect(newSchema.hierarchy).toBeDefined();
    expect(newSchema.hierarchy.type).toBe('tab-container');
  });

  it('enableHierarchyMode does nothing when hierarchy already set', () => {
    const { result, onSchemaChange } = setup(true);
    act(() => result.current.enableHierarchyMode());
    expect(onSchemaChange).not.toHaveBeenCalled();
  });

  it('disableHierarchyMode removes hierarchy from schema', () => {
    const { result, onSchemaChange } = setup(true);
    act(() => result.current.disableHierarchyMode());
    const [newSchema] = onSchemaChange.mock.calls[0];
    expect(newSchema.hierarchy).toBeUndefined();
  });
});
