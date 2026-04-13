/**
 * useVirtualList Hook
 *
 * Provides virtualized list rendering for large lists.
 *
 * @since 3.2.0
 */

import { useState, useCallback, useMemo, useRef, useEffect } from 'react';

export interface VirtualListOptions {
  /** Total number of items */
  itemCount: number;
  /** Height of each item in pixels */
  itemHeight: number;
  /** Height of the container */
  containerHeight: number;
  /** Overscan count (extra items to render) */
  overscan?: number;
}

export interface VirtualListResult {
  /** Items to render */
  virtualItems: VirtualItem[];
  /** Total height of all items */
  totalHeight: number;
  /** Container style */
  containerStyle: React.CSSProperties;
  /** Content style */
  contentStyle: React.CSSProperties;
  /** Scroll handler */
  onScroll: (e: React.UIEvent<HTMLElement>) => void;
  /** Scroll to index */
  scrollToIndex: (index: number) => void;
  /** Current scroll offset */
  scrollOffset: number;
}

export interface VirtualItem {
  /** Item index */
  index: number;
  /** Start position (top offset) */
  start: number;
  /** Item size */
  size: number;
  /** Style to apply */
  style: React.CSSProperties;
}

/**
 * Virtual list hook for efficient rendering of large lists
 */
export function useVirtualList(options: VirtualListOptions): VirtualListResult {
  const { itemCount, itemHeight, containerHeight, overscan = 3 } = options;

  const [scrollOffset, setScrollOffset] = useState(0);
  const scrollContainerRef = useRef<HTMLElement | null>(null);

  // Calculate visible range
  const { startIndex, endIndex, virtualItems } = useMemo(() => {
    const start = Math.floor(scrollOffset / itemHeight);
    const visibleCount = Math.ceil(containerHeight / itemHeight);

    const startIndex = Math.max(0, start - overscan);
    const endIndex = Math.min(itemCount - 1, start + visibleCount + overscan);

    const items: VirtualItem[] = [];
    for (let i = startIndex; i <= endIndex; i++) {
      items.push({
        index: i,
        start: i * itemHeight,
        size: itemHeight,
        style: {
          position: 'absolute',
          top: i * itemHeight,
          left: 0,
          right: 0,
          height: itemHeight,
        },
      });
    }

    return { startIndex, endIndex, virtualItems: items };
  }, [scrollOffset, itemHeight, containerHeight, itemCount, overscan]);

  // Total height
  const totalHeight = itemCount * itemHeight;

  // Handle scroll
  const onScroll = useCallback((e: React.UIEvent<HTMLElement>) => {
    const target = e.currentTarget;
    setScrollOffset(target.scrollTop);
    scrollContainerRef.current = target;
  }, []);

  // Scroll to specific index
  const scrollToIndex = useCallback(
    (index: number) => {
      const offset = index * itemHeight;
      if (scrollContainerRef.current) {
        scrollContainerRef.current.scrollTop = offset;
      }
      setScrollOffset(offset);
    },
    [itemHeight],
  );

  // Container style
  const containerStyle: React.CSSProperties = useMemo(
    () => ({
      height: containerHeight,
      overflow: 'auto',
      position: 'relative',
    }),
    [containerHeight],
  );

  // Content style
  const contentStyle: React.CSSProperties = useMemo(
    () => ({
      height: totalHeight,
      position: 'relative',
    }),
    [totalHeight],
  );

  return {
    virtualItems,
    totalHeight,
    containerStyle,
    contentStyle,
    onScroll,
    scrollToIndex,
    scrollOffset,
  };
}

/**
 * Variable height virtual list hook
 */
export interface VariableVirtualListOptions {
  /** Total number of items */
  itemCount: number;
  /** Function to get item height */
  getItemHeight: (index: number) => number;
  /** Height of the container */
  containerHeight: number;
  /** Overscan count */
  overscan?: number;
}

export function useVariableVirtualList(options: VariableVirtualListOptions): VirtualListResult {
  const { itemCount, getItemHeight, containerHeight, overscan = 3 } = options;

  const [scrollOffset, setScrollOffset] = useState(0);
  const scrollContainerRef = useRef<HTMLElement | null>(null);

  // Cache item positions
  const itemPositions = useMemo(() => {
    const positions: { start: number; size: number }[] = [];
    let offset = 0;

    for (let i = 0; i < itemCount; i++) {
      const size = getItemHeight(i);
      positions.push({ start: offset, size });
      offset += size;
    }

    return positions;
  }, [itemCount, getItemHeight]);

  const totalHeight = itemPositions[itemCount - 1]
    ? itemPositions[itemCount - 1].start + itemPositions[itemCount - 1].size
    : 0;

  // Find visible range using binary search
  const { virtualItems } = useMemo(() => {
    if (itemCount === 0) {
      return { virtualItems: [] };
    }

    // Binary search for start index
    let low = 0;
    let high = itemCount - 1;
    while (low < high) {
      const mid = Math.floor((low + high) / 2);
      if (itemPositions[mid].start + itemPositions[mid].size < scrollOffset) {
        low = mid + 1;
      } else {
        high = mid;
      }
    }

    const startIndex = Math.max(0, low - overscan);
    const visibleEnd = scrollOffset + containerHeight;

    let endIndex = startIndex;
    while (endIndex < itemCount && itemPositions[endIndex].start < visibleEnd) {
      endIndex++;
    }
    endIndex = Math.min(itemCount - 1, endIndex + overscan);

    const items: VirtualItem[] = [];
    for (let i = startIndex; i <= endIndex; i++) {
      const pos = itemPositions[i];
      items.push({
        index: i,
        start: pos.start,
        size: pos.size,
        style: {
          position: 'absolute',
          top: pos.start,
          left: 0,
          right: 0,
          height: pos.size,
        },
      });
    }

    return { virtualItems: items };
  }, [scrollOffset, containerHeight, itemCount, itemPositions, overscan]);

  const onScroll = useCallback((e: React.UIEvent<HTMLElement>) => {
    const target = e.currentTarget;
    setScrollOffset(target.scrollTop);
    scrollContainerRef.current = target;
  }, []);

  const scrollToIndex = useCallback(
    (index: number) => {
      if (index >= 0 && index < itemCount) {
        const offset = itemPositions[index].start;
        if (scrollContainerRef.current) {
          scrollContainerRef.current.scrollTop = offset;
        }
        setScrollOffset(offset);
      }
    },
    [itemCount, itemPositions],
  );

  const containerStyle: React.CSSProperties = useMemo(
    () => ({
      height: containerHeight,
      overflow: 'auto',
      position: 'relative',
    }),
    [containerHeight],
  );

  const contentStyle: React.CSSProperties = useMemo(
    () => ({
      height: totalHeight,
      position: 'relative',
    }),
    [totalHeight],
  );

  return {
    virtualItems,
    totalHeight,
    containerStyle,
    contentStyle,
    onScroll,
    scrollToIndex,
    scrollOffset,
  };
}

export default useVirtualList;
