/**
 * SortIndicator — SVG-based column header sort arrows.
 * Replaces Unicode arrows with clean small triangles.
 *
 * - No sort: hidden (shown on hover via parent CSS)
 * - Ascending: top arrow blue (#2563eb), bottom arrow gray (#c0c4cc)
 * - Descending: top arrow gray, bottom arrow blue
 * - Multi-sort: shows priority number badge
 */
import React from 'react';

export interface SortIndicatorProps {
  direction?: 'asc' | 'desc';
  priority?: number;
}

export function SortIndicator({ direction, priority }: SortIndicatorProps) {
  const isActive = !!direction;
  const upColor = direction === 'asc' ? '#2563eb' : '#c0c4cc';
  const downColor = direction === 'desc' ? '#2563eb' : '#c0c4cc';

  return (
    <span className="relative inline-flex items-center">
      <svg width="8" height="12" viewBox="0 0 8 12" className="flex-shrink-0" aria-hidden="true">
        <path d="M4 0.5L7 4.5H1Z" fill={upColor} />
        <path d="M4 11.5L1 7.5H7Z" fill={downColor} />
      </svg>
      {isActive && priority != null && priority > 0 && (
        <span className="absolute -top-1.5 -right-2 flex h-3.5 w-3.5 items-center justify-center rounded-full bg-blue-600 text-[9px] font-bold text-white">
          {priority}
        </span>
      )}
    </span>
  );
}
