import { describe, expect, it } from 'vitest';
import {
  buildDesignerCollisionCandidates,
  prioritizeNestedDropCollisions,
  resolveBlockDropIntent,
  resolveDragEndAction,
  type DragData,
  type DragEndCapabilities,
} from '../dnd/dndShared';
import type { ModelFieldDefinition } from '../types';

const field: ModelFieldDefinition = { modelCode: 'm', code: 'email', label: 'Email', type: 'string' };

function caps(overrides: Partial<DragEndCapabilities> = {}): DragEndCapabilities {
  return {
    canAddBlockBeforeTarget: () => false,
    canAddBlockToParent: () => false,
    canAddModelFieldBeforeTarget: () => false,
    canAddModelFieldToParent: () => false,
    canAddBlockToRoot: () => false,
    canMoveBlockBeforeTarget: () => false,
    canMoveBlockToParent: () => false,
    ...overrides,
  };
}

describe('resolveBlockDropIntent', () => {
  it('prefers before, falls back to inside, else null for palette blocks', () => {
    const drag: DragData = { kind: 'palette-block', blockType: 'field' };
    expect(resolveBlockDropIntent(drag, 't', caps({ canAddBlockBeforeTarget: () => true }))).toBe(
      'before',
    );
    expect(resolveBlockDropIntent(drag, 't', caps({ canAddBlockToParent: () => true }))).toBe(
      'inside',
    );
    expect(resolveBlockDropIntent(drag, 't', caps())).toBeNull();
  });

  it('prefers before, falls back to inside, else null for model fields', () => {
    const drag: DragData = { kind: 'model-field', field };
    expect(
      resolveBlockDropIntent(drag, 't', caps({ canAddModelFieldBeforeTarget: () => true })),
    ).toBe('before');
    expect(resolveBlockDropIntent(drag, 't', caps({ canAddModelFieldToParent: () => true }))).toBe(
      'inside',
    );
    expect(resolveBlockDropIntent(drag, 't', caps())).toBeNull();
  });

  it('moves a canvas block before another block, or inside a compatible container', () => {
    expect(
      resolveBlockDropIntent(
        { kind: 'canvas-block', blockId: 'a' },
        'b',
        caps({ canMoveBlockBeforeTarget: () => true }),
      ),
    ).toBe('before');
    expect(
      resolveBlockDropIntent(
        { kind: 'canvas-block', blockId: 'a' },
        'section',
        caps({ canMoveBlockToParent: () => true }),
      ),
    ).toBe('inside');
    expect(resolveBlockDropIntent({ kind: 'canvas-block', blockId: 'a' }, 'b', caps())).toBeNull();
    expect(resolveBlockDropIntent({ kind: 'canvas-block', blockId: 'a' }, 'a', caps())).toBeNull();
  });
});

describe('resolveDragEndAction', () => {
  it('returns null without drag or drop', () => {
    expect(resolveDragEndAction(null, { kind: 'root' }, caps())).toBeNull();
    expect(resolveDragEndAction({ kind: 'palette-block', blockType: 'form' }, null, caps())).toBeNull();
  });

  it('adds a page block to root only when accepted', () => {
    const drag: DragData = { kind: 'palette-block', blockType: 'form' };
    expect(resolveDragEndAction(drag, { kind: 'root' }, caps({ canAddBlockToRoot: () => true }))).toEqual({
      type: 'add-block-root',
      blockType: 'form',
    });
    expect(resolveDragEndAction(drag, { kind: 'root' }, caps())).toBeNull();
  });

  it('adds a palette block before / inside a target block', () => {
    const drag: DragData = { kind: 'palette-block', blockType: 'field' };
    expect(
      resolveDragEndAction(drag, { kind: 'block', blockId: 't' }, caps({ canAddBlockBeforeTarget: () => true })),
    ).toEqual({ type: 'add-block-before', targetBlockId: 't', blockType: 'field' });
    expect(
      resolveDragEndAction(drag, { kind: 'block', blockId: 't' }, caps({ canAddBlockToParent: () => true })),
    ).toEqual({ type: 'add-block-inside', parentBlockId: 't', blockType: 'field' });
  });

  it('adds a model field before / inside a target block', () => {
    const drag: DragData = { kind: 'model-field', field };
    expect(
      resolveDragEndAction(drag, { kind: 'block', blockId: 't' }, caps({ canAddModelFieldBeforeTarget: () => true })),
    ).toEqual({ type: 'add-field-before', targetBlockId: 't', field });
    expect(
      resolveDragEndAction(drag, { kind: 'block', blockId: 't' }, caps({ canAddModelFieldToParent: () => true })),
    ).toEqual({ type: 'add-field-inside', parentBlockId: 't', field });
  });

  it('moves a canvas block before another block', () => {
    expect(
      resolveDragEndAction(
        { kind: 'canvas-block', blockId: 'a' },
        { kind: 'block', blockId: 'b' },
        caps({ canMoveBlockBeforeTarget: () => true }),
      ),
    ).toEqual({ type: 'move-before', movingBlockId: 'a', targetBlockId: 'b' });
    expect(
      resolveDragEndAction({ kind: 'canvas-block', blockId: 'a' }, { kind: 'block', blockId: 'b' }, caps()),
    ).toBeNull();
    expect(
      resolveDragEndAction({ kind: 'canvas-block', blockId: 'a' }, { kind: 'block', blockId: 'a' }, caps()),
    ).toBeNull();
  });

  it('moves a canvas block inside a compatible target container', () => {
    expect(
      resolveDragEndAction(
        { kind: 'canvas-block', blockId: 'field_a' },
        { kind: 'block', blockId: 'section_target' },
        caps({ canMoveBlockToParent: () => true }),
      ),
    ).toEqual({ type: 'move-inside', movingBlockId: 'field_a', parentBlockId: 'section_target' });
  });
});

describe('prioritizeNestedDropCollisions', () => {
  it('prefers the smallest nested droppable hit over an outer canvas block', () => {
    const collisions = [
      { id: 'drop-block:form_root' },
      { id: 'drop-block:section_target' },
    ];
    const droppableRects = new Map([
      ['drop-block:form_root', { width: 360, height: 520 }],
      ['drop-block:section_target', { width: 320, height: 96 }],
    ]);

    expect(
      prioritizeNestedDropCollisions(collisions, droppableRects).map((collision) => collision.id),
    ).toEqual(['drop-block:section_target', 'drop-block:form_root']);
  });
});

describe('buildDesignerCollisionCandidates', () => {
  it('adds the closest-center candidate when pointerWithin only reports an outer block', () => {
    const pointerHits = [{ id: 'drop-block:form_root' }];
    const closestHits = [
      { id: 'drop-block:section_target' },
      { id: 'drop-block:form_root' },
    ];
    const droppableRects = new Map([
      ['drop-block:form_root', { width: 360, height: 520 }],
      ['drop-block:section_target', { width: 320, height: 96 }],
    ]);

    expect(
      buildDesignerCollisionCandidates(pointerHits, closestHits, droppableRects).map(
        (collision) => collision.id,
      ),
    ).toEqual(['drop-block:section_target', 'drop-block:form_root']);
  });
});
