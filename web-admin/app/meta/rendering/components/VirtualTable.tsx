/**
 * VirtualTable — virtual scrolling for large data-table blocks
 *
 * When row count > threshold (default 50), enables virtual scrolling
 * to only render visible rows. Falls back to regular rendering for
 * small datasets.
 */

import React, { useRef } from 'react';
import { useVirtualizer } from '@tanstack/react-virtual';

export interface VirtualTableProps {
  /** Total row data */
  rows: any[];
  /** Row height in pixels */
  rowHeight?: number;
  /** Container height in pixels */
  containerHeight?: number;
  /** Minimum rows before enabling virtualization */
  threshold?: number;
  /** Overscan count (rows to render outside viewport) */
  overscan?: number;
  /** Render a single row */
  renderRow: (row: any, index: number, style?: React.CSSProperties) => React.ReactNode;
  /** Table header content */
  header: React.ReactNode;
  /** CSS class for the container */
  className?: string;
}

export const VirtualTable: React.FC<VirtualTableProps> = ({
  rows,
  rowHeight = 48,
  containerHeight = 600,
  threshold = 50,
  overscan = 5,
  renderRow,
  header,
  className = '',
}) => {
  const parentRef = useRef<HTMLDivElement>(null);

  const useVirtual = rows.length > threshold;

  const virtualizer = useVirtualizer({
    count: rows.length,
    getScrollElement: () => parentRef.current,
    estimateSize: () => rowHeight,
    overscan,
    enabled: useVirtual,
  });

  if (!useVirtual) {
    // Regular rendering for small datasets
    return (
      <div className={className}>
        <table className="min-w-full divide-y divide-gray-200">
          {header}
          <tbody className="divide-y divide-gray-200 bg-white">
            {rows.map((row, index) => renderRow(row, index))}
          </tbody>
        </table>
      </div>
    );
  }

  // Virtual rendering for large datasets
  const virtualItems = virtualizer.getVirtualItems();
  const totalSize = virtualizer.getTotalSize();

  return (
    <div className={className}>
      <table className="min-w-full divide-y divide-gray-200">{header}</table>
      <div ref={parentRef} className="overflow-auto" style={{ height: containerHeight }}>
        <div style={{ height: totalSize, position: 'relative' }}>
          {virtualItems.map((virtualItem) => {
            const row = rows[virtualItem.index];
            return (
              <div
                key={virtualItem.key}
                style={{
                  position: 'absolute',
                  top: 0,
                  left: 0,
                  width: '100%',
                  height: `${virtualItem.size}px`,
                  transform: `translateY(${virtualItem.start}px)`,
                }}
              >
                {renderRow(row, virtualItem.index)}
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
};
