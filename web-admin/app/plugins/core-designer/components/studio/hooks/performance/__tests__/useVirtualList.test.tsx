/**
 * Unit tests for useVirtualList and useVariableVirtualList hooks.
 *
 * These hooks are computation-driven (pure math on item positions); no DOM
 * geometry measurements are needed — jsdom is sufficient.
 */

import { renderHook, act } from '@testing-library/react';
import { describe, it, expect } from 'vitest';

import { useVirtualList, useVariableVirtualList } from '../useVirtualList';

// ---------------------------------------------------------------------------
// useVirtualList — fixed item height
// ---------------------------------------------------------------------------
describe('useVirtualList', () => {
  const baseOptions = {
    itemCount: 100,
    itemHeight: 40,
    containerHeight: 200,
    overscan: 2,
  };

  it('renders the correct initial virtual items (scroll=0)', () => {
    const { result } = renderHook(() => useVirtualList(baseOptions));
    // visible = ceil(200/40) = 5, overscan=2 → items 0..6 (indices 0-6, 7 items)
    const { virtualItems } = result.current;
    expect(virtualItems[0].index).toBe(0);
    // start index 0 - 2 clamped to 0; end = 0 + 5 + 2 = 7 but ≤99 → 7
    const lastItem = virtualItems[virtualItems.length - 1];
    expect(lastItem.index).toBe(6 + 1); // 7 (0+5+2 = end 7)
    expect(virtualItems.length).toBeGreaterThan(0);
  });

  it('totalHeight equals itemCount * itemHeight', () => {
    const { result } = renderHook(() => useVirtualList(baseOptions));
    expect(result.current.totalHeight).toBe(100 * 40);
  });

  it('containerStyle includes containerHeight and overflow:auto', () => {
    const { result } = renderHook(() => useVirtualList(baseOptions));
    expect(result.current.containerStyle.height).toBe(200);
    expect(result.current.containerStyle.overflow).toBe('auto');
  });

  it('contentStyle height equals totalHeight', () => {
    const { result } = renderHook(() => useVirtualList(baseOptions));
    expect(result.current.contentStyle.height).toBe(100 * 40);
  });

  it('each virtual item has correct start and size', () => {
    const { result } = renderHook(() => useVirtualList(baseOptions));
    const { virtualItems } = result.current;
    for (const item of virtualItems) {
      expect(item.start).toBe(item.index * 40);
      expect(item.size).toBe(40);
      expect(item.style.top).toBe(item.index * 40);
      expect(item.style.height).toBe(40);
    }
  });

  it('onScroll updates scrollOffset and shifts virtual window', () => {
    const { result } = renderHook(() => useVirtualList(baseOptions));

    // Simulate scrolling to item 10 (offset 400)
    act(() => {
      result.current.onScroll({
        currentTarget: { scrollTop: 400 },
      } as React.UIEvent<HTMLElement>);
    });

    expect(result.current.scrollOffset).toBe(400);
    // start = floor(400/40)=10; startIndex = max(0, 10-2)=8
    const indices = result.current.virtualItems.map((i) => i.index);
    expect(indices[0]).toBe(8);
  });

  it('scrollToIndex updates scrollOffset to index * itemHeight', () => {
    const { result } = renderHook(() => useVirtualList(baseOptions));
    act(() => {
      result.current.scrollToIndex(5);
    });
    expect(result.current.scrollOffset).toBe(5 * 40);
  });

  it('handles empty list (itemCount=0)', () => {
    const { result } = renderHook(() =>
      useVirtualList({ ...baseOptions, itemCount: 0 }),
    );
    // start = 0, endIndex = max(0, 0+5+2) clamped by itemCount-1 = -1 → loop skipped
    expect(result.current.virtualItems).toHaveLength(0);
    expect(result.current.totalHeight).toBe(0);
  });

  it('uses default overscan of 3 when not specified', () => {
    const { result } = renderHook(() =>
      useVirtualList({ itemCount: 20, itemHeight: 40, containerHeight: 200 }),
    );
    // visible=5, overscan=3 → endIndex = 5+3=8 (0-indexed)
    const indices = result.current.virtualItems.map((i) => i.index);
    expect(indices).toContain(0);
    expect(indices[indices.length - 1]).toBeGreaterThanOrEqual(7);
  });
});

// ---------------------------------------------------------------------------
// useVariableVirtualList — variable item heights
// ---------------------------------------------------------------------------
describe('useVariableVirtualList', () => {
  // items: indices 0-9, heights alternating 30 and 60
  const getItemHeight = (i: number) => (i % 2 === 0 ? 30 : 60);
  // offsets: 0,30,90,120,180,210,270,300,360,390 → total=420
  const baseOptions = {
    itemCount: 10,
    getItemHeight,
    containerHeight: 100,
    overscan: 1,
  };

  it('returns correct totalHeight', () => {
    const { result } = renderHook(() => useVariableVirtualList(baseOptions));
    // sum of heights: 5*30 + 5*60 = 150+300 = 450
    // last item start = 390, size = 60 → total = 450
    expect(result.current.totalHeight).toBe(450);
  });

  it('returns virtual items for initial scroll 0', () => {
    const { result } = renderHook(() => useVariableVirtualList(baseOptions));
    const { virtualItems } = result.current;
    expect(virtualItems.length).toBeGreaterThan(0);
    expect(virtualItems[0].index).toBe(0);
  });

  it('virtual item styles reflect variable sizes', () => {
    const { result } = renderHook(() => useVariableVirtualList(baseOptions));
    const { virtualItems } = result.current;
    for (const item of virtualItems) {
      const expectedSize = getItemHeight(item.index);
      expect(item.size).toBe(expectedSize);
      expect(item.style.height).toBe(expectedSize);
    }
  });

  it('returns empty array for itemCount=0', () => {
    const { result } = renderHook(() =>
      useVariableVirtualList({ ...baseOptions, itemCount: 0 }),
    );
    expect(result.current.virtualItems).toHaveLength(0);
    expect(result.current.totalHeight).toBe(0);
  });

  it('scrollToIndex moves offset to item start position', () => {
    const { result } = renderHook(() => useVariableVirtualList(baseOptions));
    // item 3 starts at offset 0+30+60+30 = 120
    act(() => {
      result.current.scrollToIndex(3);
    });
    expect(result.current.scrollOffset).toBe(120);
  });

  it('scrollToIndex out of bounds does nothing', () => {
    const { result } = renderHook(() => useVariableVirtualList(baseOptions));
    act(() => {
      result.current.scrollToIndex(-1);
    });
    expect(result.current.scrollOffset).toBe(0);
    act(() => {
      result.current.scrollToIndex(100);
    });
    expect(result.current.scrollOffset).toBe(0);
  });

  it('onScroll updates scrollOffset', () => {
    const { result } = renderHook(() => useVariableVirtualList(baseOptions));
    act(() => {
      result.current.onScroll({
        currentTarget: { scrollTop: 120 },
      } as React.UIEvent<HTMLElement>);
    });
    expect(result.current.scrollOffset).toBe(120);
  });

  it('containerStyle includes containerHeight', () => {
    const { result } = renderHook(() => useVariableVirtualList(baseOptions));
    expect(result.current.containerStyle.height).toBe(100);
  });
});
