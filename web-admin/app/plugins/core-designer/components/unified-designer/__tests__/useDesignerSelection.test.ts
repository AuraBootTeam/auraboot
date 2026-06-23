/**
 * useDesignerSelection — the reusable block-tree selection kernel.
 *
 * Extracted verbatim (behavior-preserving) from UnifiedDesignerWorkbench so the
 * page designer and the report designer (block-tree family, B1 Phase 2) share
 * ONE selection model — primary `selectedBlockId` (inspector + drop context)
 * plus an independent additive `multiSelectedIds` set — instead of each
 * reinventing the modifier-click / marquee folding rules.
 *
 * These tests pin the exact transitions the Workbench relied on.
 */
import { describe, expect, it } from 'vitest';
import { act, renderHook } from '@testing-library/react';
import { useDesignerSelection } from '../selection/useDesignerSelection';

describe('useDesignerSelection', () => {
  it('starts with no primary and an empty multi-selection', () => {
    const { result } = renderHook(() => useDesignerSelection());
    expect(result.current.selectedBlockId).toBeNull();
    expect(result.current.multiSelectedIds.size).toBe(0);
  });

  it('plain canvas select sets the primary and clears any multi-selection', () => {
    const { result } = renderHook(() => useDesignerSelection());
    act(() => result.current.setMultiSelectedIds(new Set(['x', 'y'])));

    act(() => result.current.selectFromCanvas('a'));
    expect(result.current.selectedBlockId).toBe('a');
    expect(result.current.multiSelectedIds.size).toBe(0);
  });

  it('first additive click folds the existing primary into the set, then adds the clicked id', () => {
    const { result } = renderHook(() => useDesignerSelection());
    act(() => result.current.setSelectedBlockId('A'));

    act(() => result.current.selectFromCanvas('B', { additive: true }));
    expect([...result.current.multiSelectedIds].sort()).toEqual(['A', 'B']);
    expect(result.current.selectedBlockId).toBe('B');
  });

  it('additive click toggles an already-selected id back out of the set', () => {
    const { result } = renderHook(() => useDesignerSelection());
    act(() => result.current.setSelectedBlockId('A'));
    act(() => result.current.selectFromCanvas('B', { additive: true })); // {A,B}
    act(() => result.current.selectFromCanvas('B', { additive: true })); // toggle B out
    expect([...result.current.multiSelectedIds]).toEqual(['A']);
    expect(result.current.selectedBlockId).toBe('B');
  });

  it('marquee with zero ids clears the multi-selection but leaves the primary', () => {
    const { result } = renderHook(() => useDesignerSelection());
    act(() => result.current.setSelectedBlockId('keep'));
    act(() => result.current.setMultiSelectedIds(new Set(['x', 'y'])));

    act(() => result.current.selectFromMarquee([]));
    expect(result.current.multiSelectedIds.size).toBe(0);
    expect(result.current.selectedBlockId).toBe('keep');
  });

  it('marquee with one id behaves like a single select', () => {
    const { result } = renderHook(() => useDesignerSelection());
    act(() => result.current.setMultiSelectedIds(new Set(['x', 'y'])));

    act(() => result.current.selectFromMarquee(['only']));
    expect(result.current.selectedBlockId).toBe('only');
    expect(result.current.multiSelectedIds.size).toBe(0);
  });

  it('marquee with many ids sets the set and makes the last id primary', () => {
    const { result } = renderHook(() => useDesignerSelection());
    act(() => result.current.selectFromMarquee(['x', 'y', 'z']));
    expect([...result.current.multiSelectedIds].sort()).toEqual(['x', 'y', 'z']);
    expect(result.current.selectedBlockId).toBe('z');
  });

  it('clearMultiSelection empties the set without touching the primary', () => {
    const { result } = renderHook(() => useDesignerSelection());
    act(() => result.current.setSelectedBlockId('p'));
    act(() => result.current.setMultiSelectedIds(new Set(['x'])));

    act(() => result.current.clearMultiSelection());
    expect(result.current.multiSelectedIds.size).toBe(0);
    expect(result.current.selectedBlockId).toBe('p');
  });
});
