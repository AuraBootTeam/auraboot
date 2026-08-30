import { describe, expect, it } from 'vitest';
import {
  resolveInsertTarget,
  type InsertTargetInput,
} from '../dnd/insertTarget';

function baseInput(overrides: Partial<InsertTargetInput> = {}): InsertTargetInput {
  return {
    selectedBlockId: null,
    selectedBlockType: null,
    kindRootContainerId: null,
    kindRootContainerType: null,
    canContain: (parentBlockType, blockType) =>
      (parentBlockType === 'section' && blockType === 'field')
      || (parentBlockType === 'list' && ['table', 'filter-bar', 'action-bar', 'section'].includes(blockType))
      || (parentBlockType === 'form' && blockType === 'field'),
    canInsertBeforeSelected: false,
    canAddBlockToRoot: false,
    ...overrides,
  };
}

describe('resolveInsertTarget (click-insert priority chain)', () => {
  it('appends inside the selected block when it can contain the type', () => {
    expect(
      resolveInsertTarget(
        baseInput({
          selectedBlockId: 'section_basic',
          selectedBlockType: 'section',
        }),
        'field',
      ),
    ).toEqual({ placement: 'inside-selected', parentBlockId: 'section_basic', targetBlockId: null });
  });

  it('falls back to the kind-root container when the selection cannot contain the type', () => {
    expect(
      resolveInsertTarget(
        baseInput({
          selectedBlockId: 'field_seed_title',
          selectedBlockType: 'field',
          kindRootContainerId: 'list_root',
          kindRootContainerType: 'list',
        }),
        'table',
      ),
    ).toEqual({ placement: 'inside-kind-root', parentBlockId: 'list_root', targetBlockId: null });
  });

  it('prefers the kind-root fallback over insert-before so palette additions stay in the root container', () => {
    // With the page root selected (default state), a container-accepting type
    // goes into the kind root even though the root could also take it as a
    // sibling of the selection — matching the handleAddBlock contract.
    const resolution = resolveInsertTarget(
      baseInput({
        selectedBlockId: 'table_customers',
        selectedBlockType: 'table',
        kindRootContainerId: 'list_root',
        kindRootContainerType: 'list',
        canInsertBeforeSelected: true,
      }),
      'filter-bar',
    );
    expect(resolution?.placement).toBe('inside-kind-root');
  });

  it('inserts before the selected block when neither it nor the kind root can contain the type', () => {
    expect(
      resolveInsertTarget(
        baseInput({
          selectedBlockId: 'column_name',
          selectedBlockType: 'column',
          kindRootContainerId: 'list_root',
          kindRootContainerType: 'list',
          canInsertBeforeSelected: true,
        }),
        'tabs',
      ),
    ).toEqual({
      placement: 'before-selected',
      parentBlockId: null,
      targetBlockId: 'column_name',
    });
  });

  it('appends at the page root as the last resort', () => {
    expect(
      resolveInsertTarget(baseInput({ canAddBlockToRoot: true }), 'list'),
    ).toEqual({ placement: 'page-root', parentBlockId: null, targetBlockId: null });
  });

  it('returns null when no placement is possible (palette disabled)', () => {
    expect(resolveInsertTarget(baseInput(), 'field')).toBeNull();
  });

  it('treats a dangling selection id (block not found) as no selection', () => {
    expect(
      resolveInsertTarget(
        baseInput({
          selectedBlockId: 'ghost_block',
          selectedBlockType: null,
          kindRootContainerId: 'list_root',
          kindRootContainerType: 'list',
        }),
        'table',
      ),
    ).toEqual({ placement: 'inside-kind-root', parentBlockId: 'list_root', targetBlockId: null });
  });

  it('skips the kind-root fallback when the page has no root container', () => {
    expect(
      resolveInsertTarget(
        baseInput({
          selectedBlockId: 'widget_one',
          selectedBlockType: 'widget',
          canInsertBeforeSelected: true,
        }),
        'table',
      ),
    ).toEqual({
      placement: 'before-selected',
      parentBlockId: null,
      targetBlockId: 'widget_one',
    });
  });
});
