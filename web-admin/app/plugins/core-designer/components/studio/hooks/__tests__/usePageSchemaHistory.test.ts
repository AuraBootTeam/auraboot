/**
 * Unit tests for usePageSchemaHistory hook
 *
 * Covers: pushState, undo, redo, resetHistory, and flag consistency.
 */

import { renderHook, act } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import { usePageSchemaHistory } from '../usePageSchemaHistory';
import type { PageSchema } from '~/plugins/core-designer/components/studio/domain/dsl/types';

function makeSchema(id: string, blockCount = 0): PageSchema {
  return {
    schemaVersion: 2 as const,
    id,
    kind: 'list' as const,
    modelCode: 'test_model',
    layout: { type: 'grid' as const, cols: 12 },
    blocks: Array.from({ length: blockCount }, (_, i) => ({
      blockType: 'table' as const,
      id: `block-${i}`,
      fields: [],
    })),
  };
}

describe('usePageSchemaHistory', () => {
  describe('initial state', () => {
    it('starts with canUndo=false and canRedo=false', () => {
      const { result } = renderHook(() => usePageSchemaHistory(makeSchema('initial')));
      expect(result.current.canUndo).toBe(false);
      expect(result.current.canRedo).toBe(false);
    });
  });

  describe('pushState', () => {
    it('enables canUndo after pushing a new state', () => {
      const { result } = renderHook(() => usePageSchemaHistory(makeSchema('s0')));
      act(() => {
        result.current.pushState(makeSchema('s1', 1));
      });
      expect(result.current.canUndo).toBe(true);
      expect(result.current.canRedo).toBe(false);
    });

    it('skips push when serialized state is identical', () => {
      const schema = makeSchema('s0');
      const { result } = renderHook(() => usePageSchemaHistory(schema));
      act(() => {
        result.current.pushState({ ...schema }); // structurally identical
      });
      expect(result.current.canUndo).toBe(false);
    });
  });

  describe('undo / redo', () => {
    it('undo returns previous state and enables redo', () => {
      const s0 = makeSchema('s0', 0);
      const s1 = makeSchema('s1', 1);
      const { result } = renderHook(() => usePageSchemaHistory(s0));

      act(() => {
        result.current.pushState(s1);
      });

      let prev: unknown = null;
      act(() => {
        prev = result.current.undo();
      });

      expect(prev).not.toBeNull();
      expect((prev as PageSchema).id).toBe('s0');
      expect(result.current.canUndo).toBe(false);
      expect(result.current.canRedo).toBe(true);
    });

    it('redo returns next state', () => {
      const s0 = makeSchema('s0', 0);
      const s1 = makeSchema('s1', 1);
      const { result } = renderHook(() => usePageSchemaHistory(s0));

      act(() => {
        result.current.pushState(s1);
        result.current.undo();
      });

      let next: unknown = null;
      act(() => {
        next = result.current.redo();
      });

      expect(next).not.toBeNull();
      expect((next as PageSchema).id).toBe('s1');
      expect(result.current.canRedo).toBe(false);
    });

    it('undo returns null at history start', () => {
      const { result } = renderHook(() => usePageSchemaHistory(makeSchema('s0')));
      let out: unknown = makeSchema('dummy');
      act(() => {
        out = result.current.undo();
      });
      expect(out).toBeNull();
    });
  });

  describe('resetHistory', () => {
    it('clears past and future stacks on reset', () => {
      const s0 = makeSchema('s0', 0);
      const s1 = makeSchema('s1', 1);
      const s2 = makeSchema('s2', 2);
      const newInitial = makeSchema('new-initial', 5);

      const { result } = renderHook(() => usePageSchemaHistory(s0));

      // Build up history: s0 → s1 → s2
      act(() => {
        result.current.pushState(s1);
        result.current.pushState(s2);
      });
      expect(result.current.canUndo).toBe(true);

      // Now reset
      act(() => {
        result.current.resetHistory(newInitial);
      });

      // canUndo and canRedo must both be false
      expect(result.current.canUndo).toBe(false);
      expect(result.current.canRedo).toBe(false);
    });

    it('present after reset equals the newInitial schema', () => {
      const s0 = makeSchema('s0', 0);
      const newInitial = makeSchema('new-initial', 5);

      const { result } = renderHook(() => usePageSchemaHistory(s0));

      act(() => {
        result.current.pushState(makeSchema('s1', 1));
        result.current.pushState(makeSchema('s2', 2));
        result.current.resetHistory(newInitial);
      });

      // undo should return null (nothing before newInitial)
      let undoResult: unknown = makeSchema('dummy');
      act(() => {
        undoResult = result.current.undo();
      });
      expect(undoResult).toBeNull();

      // pushing a new state after reset should allow undo back to newInitial
      act(() => {
        result.current.pushState(makeSchema('after-reset', 6));
      });

      let prev: unknown = null;
      act(() => {
        prev = result.current.undo();
      });
      expect(prev).not.toBeNull();
      expect((prev as PageSchema).id).toBe('new-initial');
      expect((prev as PageSchema).blocks.length).toBe(5);
    });

    it('undo after reset with subsequent edits cannot go beyond newInitial', () => {
      const placeholder = makeSchema('placeholder', 0);
      const loaded = makeSchema('loaded', 3);
      const edit1 = makeSchema('edit1', 4);
      const edit2 = makeSchema('edit2', 5);

      const { result } = renderHook(() => usePageSchemaHistory(placeholder));

      // Simulate: load real schema → do edits → undo to bottom
      act(() => {
        result.current.resetHistory(loaded);
      });
      act(() => {
        result.current.pushState(edit1);
        result.current.pushState(edit2);
      });

      // Undo twice → should land at 'loaded', not 'placeholder'
      let prev: unknown = null;
      act(() => {
        result.current.undo(); // edit2 → edit1
        prev = result.current.undo(); // edit1 → loaded
      });
      expect(result.current.canUndo).toBe(false);
      expect(prev).not.toBeNull();
      expect((prev as PageSchema).id).toBe('loaded');
      expect((prev as PageSchema).blocks.length).toBe(3);
    });
  });
});
