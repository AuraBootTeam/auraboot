/**
 * SortIndicator — SVG-based column header sort arrows.
 * Replaces Unicode arrows with clean small triangles.
 *
 * - No sort: hidden (shown on hover via parent CSS)
 * - Ascending: top arrow accent, bottom arrow muted
 * - Descending: top arrow muted, bottom arrow accent
 * - Multi-sort: shows priority number badge
 */
import React from 'react';

// Semantic design-system tokens (SVG fill resolves CSS variables).
const ACCENT = 'var(--color-accent)';
const MUTED = 'var(--color-text-3)';

export interface SortIndicatorProps {
  direction?: 'asc' | 'desc';
  priority?: number;
}

export function SortIndicator({ direction, priority }: SortIndicatorProps) {
  const isActive = !!direction;
  const upColor = direction === 'asc' ? ACCENT : MUTED;
  const downColor = direction === 'desc' ? ACCENT : MUTED;

  return (
    <span className="relative inline-flex items-center">
      <svg width="8" height="12" viewBox="0 0 8 12" className="flex-shrink-0" aria-hidden="true">
        <path d="M4 0.5L7 4.5H1Z" fill={upColor} />
        <path d="M4 11.5L1 7.5H7Z" fill={downColor} />
      </svg>
      {isActive && priority != null && priority > 0 && (
        <span className="rounded-pill bg-accent absolute -top-1.5 -right-2 flex h-3.5 w-3.5 items-center justify-center text-[9px] font-bold text-white">
          {priority}
        </span>
      )}
    </span>
  );
}
