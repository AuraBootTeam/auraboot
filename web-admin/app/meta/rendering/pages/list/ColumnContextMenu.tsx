/**
 * ColumnContextMenu — Right-click / ⋮ context menu for column headers.
 * Provides sort, freeze, hide, filter, and group-by actions.
 * Extracted from ListPageContent.tsx (behavior-preserving refactor).
 */

import React, { useEffect, useRef } from 'react';
import { createPortal } from 'react-dom';
import type { ColumnConfig } from '~/meta/schemas/types';

// Column context menu component — appears on right-click or ⋮ click on column headers
export function ColumnContextMenu({
  x,
  y,
  column,
  currentSortDir,
  onSort,
  onFreeze,
  onHide,
  onFilterByColumn,
  onGroupBy,
  onClose,
}: {
  x: number;
  y: number;
  column: ColumnConfig;
  currentSortDir?: 'asc' | 'desc';
  onSort: (dir: 'asc' | 'desc' | 'clear') => void;
  onFreeze: (pos: 'left' | 'right' | 'none') => void;
  onHide: () => void;
  onFilterByColumn: () => void;
  onGroupBy: () => void;
  onClose: () => void;
}) {
  const menuRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const handler = (e: MouseEvent) => {
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) onClose();
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, [onClose]);

  const frozenPos = column.fixed || (column as any).frozenPosition;
  const menuItems = [
    {
      icon: '↑',
      label: 'Sort Ascending',
      active: currentSortDir === 'asc',
      onClick: () => onSort('asc'),
    },
    {
      icon: '↓',
      label: 'Sort Descending',
      active: currentSortDir === 'desc',
      onClick: () => onSort('desc'),
    },
    currentSortDir ? { icon: '✕', label: 'Clear Sort', onClick: () => onSort('clear') } : null,
    'divider' as const,
    {
      icon: '📌',
      label: 'Freeze Left',
      active: frozenPos === 'left',
      onClick: () => onFreeze(frozenPos === 'left' ? 'none' : 'left'),
    },
    {
      icon: '📌',
      label: 'Freeze Right',
      active: frozenPos === 'right',
      onClick: () => onFreeze(frozenPos === 'right' ? 'none' : 'right'),
    },
    'divider' as const,
    { icon: '👁', label: 'Hide Column', onClick: onHide },
    { icon: '🔍', label: 'Filter by Column', onClick: onFilterByColumn },
    'divider' as const,
    { icon: '☰', label: 'Group by Column', onClick: onGroupBy },
  ].filter(Boolean);

  return createPortal(
    <div
      ref={menuRef}
      className="fixed z-[1000] min-w-[180px] rounded-lg border border-gray-200 bg-white py-1 shadow-lg"
      style={{
        left: Math.min(x, window.innerWidth - 200),
        top: Math.min(y, window.innerHeight - 300),
      }}
    >
      {menuItems.map((item, idx) => {
        if (item === 'divider') return <div key={idx} className="my-1 h-px bg-gray-100" />;
        if (!item || typeof item === 'string') return null;
        return (
          <button
            key={idx}
            type="button"
            className={`flex w-full items-center gap-2 px-3 py-1.5 text-left text-sm hover:bg-gray-50 ${item.active ? 'font-medium text-blue-600' : 'text-gray-700'}`}
            onClick={() => {
              item.onClick();
              onClose();
            }}
          >
            <span className="w-4 text-center text-xs">{item.icon}</span>
            <span className="flex-1">{item.label}</span>
            {item.active && <span className="text-xs text-blue-500">✓</span>}
          </button>
        );
      })}
    </div>,
    document.body,
  );
}
