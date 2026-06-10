import { renderHook, act } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { useTreeData } from '../useTreeData';
import type { TreeConfig } from '~/framework/meta/schemas/types';

const treeConfig: TreeConfig = { parentField: 'parent_id' };

describe('useTreeData', () => {
  it('returns empty flatTree for empty rows', () => {
    const { result } = renderHook(() => useTreeData([], treeConfig));
    expect(result.current.flatTree).toEqual([]);
    expect(result.current.visibleRows).toEqual([]);
  });

  it('builds a single root node', () => {
    const rows = [{ id: '1', name: 'root', parent_id: null }];
    const { result } = renderHook(() => useTreeData(rows, treeConfig));
    expect(result.current.flatTree).toHaveLength(1);
    expect(result.current.flatTree[0]._depth).toBe(0);
    expect(result.current.flatTree[0]._parentId).toBeNull();
    expect(result.current.flatTree[0]._hasChildren).toBe(false);
  });

  it('assigns _depth and _parentId for nested nodes', () => {
    const rows = [
      { id: '1', parent_id: null },
      { id: '2', parent_id: '1' },
      { id: '3', parent_id: '2' },
    ];
    const { result } = renderHook(() => useTreeData(rows, treeConfig));
    const flat = result.current.flatTree;
    expect(flat).toHaveLength(3);
    expect(flat[0]._depth).toBe(0);
    expect(flat[1]._depth).toBe(1);
    expect(flat[2]._depth).toBe(2);
  });

  it('marks nodes with children as _hasChildren=true', () => {
    const rows = [
      { id: '1', parent_id: null },
      { id: '2', parent_id: '1' },
    ];
    const { result } = renderHook(() => useTreeData(rows, treeConfig));
    expect(result.current.flatTree[0]._hasChildren).toBe(true);
    expect(result.current.flatTree[1]._hasChildren).toBe(false);
  });

  it('toggleExpand collapses a node and hides its children', () => {
    const rows = [
      { id: '1', parent_id: null },
      { id: '2', parent_id: '1' },
    ];
    const { result } = renderHook(() => useTreeData(rows, treeConfig));
    // Both nodes visible by default (root is expanded)
    expect(result.current.visibleRows).toHaveLength(2);

    act(() => {
      result.current.toggleExpand('1');
    });
    // After collapse, child should be hidden
    expect(result.current.visibleRows).toHaveLength(1);
    expect(result.current.visibleRows[0].id).toBe('1');
  });

  it('toggleExpand re-expands a collapsed node', () => {
    const rows = [
      { id: '1', parent_id: null },
      { id: '2', parent_id: '1' },
    ];
    const { result } = renderHook(() => useTreeData(rows, treeConfig));

    act(() => result.current.toggleExpand('1'));
    expect(result.current.visibleRows).toHaveLength(1);

    act(() => result.current.toggleExpand('1'));
    expect(result.current.visibleRows).toHaveLength(2);
  });

  it('getChildren returns direct children of a parent', () => {
    const rows = [
      { id: '1', parent_id: null },
      { id: '2', parent_id: '1' },
      { id: '3', parent_id: '1' },
    ];
    const { result } = renderHook(() => useTreeData(rows, treeConfig));
    const children = result.current.getChildren('1');
    expect(children).toHaveLength(2);
    expect(children.map((c) => c.id)).toEqual(expect.arrayContaining(['2', '3']));
  });

  it('getChildren returns empty array for leaf node', () => {
    const rows = [{ id: '1', parent_id: null }];
    const { result } = renderHook(() => useTreeData(rows, treeConfig));
    expect(result.current.getChildren('1')).toEqual([]);
  });

  it('respects defaultExpanded=false — no children shown initially', () => {
    const rows = [
      { id: '1', parent_id: null },
      { id: '2', parent_id: '1' },
    ];
    const config: TreeConfig = { parentField: 'parent_id', defaultExpanded: false };
    const { result } = renderHook(() => useTreeData(rows, config));
    expect(result.current.visibleRows).toHaveLength(1);
  });

  it('sorts siblings by sort_order', () => {
    const rows = [
      { id: 'b', parent_id: null, sort_order: 2 },
      { id: 'a', parent_id: null, sort_order: 1 },
    ];
    const { result } = renderHook(() => useTreeData(rows, treeConfig));
    expect(result.current.flatTree[0].id).toBe('a');
    expect(result.current.flatTree[1].id).toBe('b');
  });

  it('handles undefined treeConfig (uses parent_id default)', () => {
    const rows = [{ id: '1', parent_id: null }];
    const { result } = renderHook(() => useTreeData(rows, undefined));
    expect(result.current.flatTree).toHaveLength(1);
  });
});
