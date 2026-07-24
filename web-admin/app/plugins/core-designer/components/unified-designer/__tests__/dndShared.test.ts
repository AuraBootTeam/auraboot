import { describe, expect, it } from 'vitest';
import {
  buildDesignerCollisionCandidates,
  prioritizeNestedDropCollisions,
  resolveBlockDropIntent,
  resolveCanvasBlockAncestorDropAction,
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

  it('falls back from a descendant drop target to the nearest movable ancestor target', () => {
    expect(
      resolveCanvasBlockAncestorDropAction(
        'sub_table_move_candidate',
        ['form_root', 'section_target', 'sub_table_target', 'target_col_status'],
        caps({
          canMoveBlockBeforeTarget: (_movingBlockId, targetBlockId) =>
            targetBlockId === 'sub_table_target',
        }),
      ),
    ).toEqual({
      type: 'move-before',
      movingBlockId: 'sub_table_move_candidate',
      targetBlockId: 'sub_table_target',
    });
  });

  it('prefers a same-type ancestor over a descendant that can also accept the moving block', () => {
    const blockTypes = new Map([
      ['subform_move_candidate', 'subform'],
      ['subform_target', 'subform'],
      ['target_section_details', 'form-section'],
      ['target_field_name', 'field'],
    ]);

    expect(
      resolveCanvasBlockAncestorDropAction(
        'subform_move_candidate',
        [
          'form_root',
          'section_target',
          'subform_target',
          'target_section_details',
          'target_field_name',
        ],
        caps({
          canMoveBlockBeforeTarget: (_movingBlockId, targetBlockId) =>
            targetBlockId === 'target_field_name' || targetBlockId === 'subform_target',
        }),
        { getBlockType: (blockId) => blockTypes.get(blockId) },
      ),
    ).toEqual({
      type: 'move-before',
      movingBlockId: 'subform_move_candidate',
      targetBlockId: 'subform_target',
    });
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
  /**
   * Geometry of a real long form page: `form_root` has grown past the viewport
   * (measured 1651px tall), so its own center is ~800px away from a pointer at
   * its header — which is exactly why `closestCenter` (a proximity metric that
   * never checks containment) nominates a small descendant instead.
   */
  const tallFormRects = new Map([
    ['drop-block:form_root', { width: 360, height: 1651 }],
    ['drop-block:section_target', { width: 320, height: 96 }],
    ['drop-block:field_email', { width: 300, height: 40 }],
    ['drop-block:far_action_button', { width: 88, height: 32 }],
  ]);

  it('targets a tall container the pointer is inside, not a closestCenter descendant guess', () => {
    // Pointer on `form_root`'s own header: inside the container, inside nothing else.
    const pointerHits = [{ id: 'drop-block:form_root' }];
    // closestCenter always nominates a small descendant for an over-tall container.
    const closestHits = [{ id: 'drop-block:section_target' }, { id: 'drop-block:form_root' }];

    // The container the user aimed at must win; the proximity guess is kept only
    // as a trailing last resort (dnd-kit reads `over` from candidates[0]).
    expect(
      buildDesignerCollisionCandidates(pointerHits, closestHits, tallFormRects).map(
        (collision) => collision.id,
      ),
    ).toEqual(['drop-block:form_root', 'drop-block:section_target']);
  });

  it('never lets a proximity guess outrank a containment hit, even when it is smaller', () => {
    // Pointer genuinely inside the section; closestCenter nominates a tiny block
    // elsewhere on the canvas that the pointer is nowhere near.
    const pointerHits = [{ id: 'drop-block:form_root' }, { id: 'drop-block:section_target' }];
    const closestHits = [
      { id: 'drop-block:far_action_button' },
      { id: 'drop-block:section_target' },
    ];

    expect(
      buildDesignerCollisionCandidates(pointerHits, closestHits, tallFormRects).map(
        (collision) => collision.id,
      ),
    ).toEqual([
      'drop-block:section_target',
      'drop-block:form_root',
      'drop-block:far_action_button',
    ]);
  });

  it('still resolves to the innermost droppable the pointer is genuinely inside', () => {
    // Containment hits are fed OUTERMOST-first (pointerWithin's own corner-distance
    // sort does not guarantee innermost-first) so only the area rule can produce
    // the nested answer — the nested preference this helper exists for.
    const pointerHits = [
      { id: 'drop-block:form_root' },
      { id: 'drop-block:section_target' },
      { id: 'drop-block:field_email' },
    ];
    const closestHits = [{ id: 'drop-block:field_email' }];

    expect(
      buildDesignerCollisionCandidates(pointerHits, closestHits, tallFormRects).map(
        (collision) => collision.id,
      ),
    ).toEqual([
      'drop-block:field_email',
      'drop-block:section_target',
      'drop-block:form_root',
    ]);
  });

  it('falls back to closestCenter only when the pointer is inside no droppable', () => {
    const closestHits = [{ id: 'drop-block:section_target' }, { id: 'drop-block:form_root' }];

    expect(
      buildDesignerCollisionCandidates([], closestHits, tallFormRects).map(
        (collision) => collision.id,
      ),
    ).toEqual(['drop-block:section_target', 'drop-block:form_root']);
  });

  it('does not duplicate the fallback when it already is a containment hit', () => {
    const pointerHits = [{ id: 'drop-block:form_root' }, { id: 'drop-block:section_target' }];
    const closestHits = [{ id: 'drop-block:section_target' }];

    expect(
      buildDesignerCollisionCandidates(pointerHits, closestHits, tallFormRects).map(
        (collision) => collision.id,
      ),
    ).toEqual(['drop-block:section_target', 'drop-block:form_root']);
  });
});
