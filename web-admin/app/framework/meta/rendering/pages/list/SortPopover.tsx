/**
 * SortPopover — Multi-field sort management popover.
 *
 * Uses @radix-ui/react-popover for positioning.
 * Supports drag-reorder of sort rules via native HTML drag.
 *
 * Direction labels adapt by valueType:
 * - number/currency/integer/decimal/percent -> "1-9" / "9-1"
 * - date/datetime/time -> "Old-New" / "New-Old"
 * - default (text) -> "A-Z" / "Z-A"
 */
import React, { useCallback, useRef, useState } from 'react';
import * as Popover from '@radix-ui/react-popover';
import type { SortConfig } from '~/smart/types/savedView';

export interface SortableColumn {
  field: string;
  label: string;
  valueType?: string;
}

export interface SortPopoverProps {
  activeSorts: SortConfig[];
  onSortsChange: (sorts: SortConfig[]) => void;
  sortableColumns: SortableColumn[];
  children: React.ReactNode;
}

type DirectionLabels = { asc: string; desc: string };

export function getDirectionLabels(valueType?: string): DirectionLabels {
  switch (valueType) {
    case 'number':
    case 'currency':
    case 'integer':
    case 'decimal':
    case 'percent':
      return { asc: '1-9', desc: '9-1' };
    case 'date':
    case 'datetime':
    case 'time':
      return { asc: 'Old-New', desc: 'New-Old' };
    default:
      return { asc: 'A-Z', desc: 'Z-A' };
  }
}

export function SortPopover({
  activeSorts,
  onSortsChange,
  sortableColumns,
  children,
}: SortPopoverProps) {
  const [dragIdx, setDragIdx] = useState<number | null>(null);
  const dragOverIdx = useRef<number | null>(null);

  const handleAddRule = useCallback(() => {
    // Find first column not already sorted
    const usedFields = new Set(activeSorts.map((s) => s.fieldCode));
    const available = sortableColumns.find((c) => !usedFields.has(c.field));
    if (!available) return;
    onSortsChange([
      ...activeSorts,
      { fieldCode: available.field, direction: 'asc', priority: activeSorts.length + 1 },
    ]);
  }, [activeSorts, onSortsChange, sortableColumns]);

  const handleRemoveRule = useCallback(
    (idx: number) => {
      const next = activeSorts.filter((_, i) => i !== idx);
      onSortsChange(next.map((s, i) => ({ ...s, priority: i + 1 })));
    },
    [activeSorts, onSortsChange],
  );

  const handleFieldChange = useCallback(
    (idx: number, fieldCode: string) => {
      const next = [...activeSorts];
      next[idx] = { ...next[idx], fieldCode };
      onSortsChange(next);
    },
    [activeSorts, onSortsChange],
  );

  const handleToggleDirection = useCallback(
    (idx: number) => {
      const next = [...activeSorts];
      next[idx] = {
        ...next[idx],
        direction: next[idx].direction === 'asc' ? 'desc' : 'asc',
      };
      onSortsChange(next);
    },
    [activeSorts, onSortsChange],
  );

  const handleClearAll = useCallback(() => {
    onSortsChange([]);
  }, [onSortsChange]);

  // Drag handlers
  const handleDragStart = useCallback((idx: number) => {
    setDragIdx(idx);
  }, []);

  const handleDragOver = useCallback(
    (e: React.DragEvent, idx: number) => {
      e.preventDefault();
      dragOverIdx.current = idx;
    },
    [],
  );

  const handleDrop = useCallback(
    (e: React.DragEvent) => {
      e.preventDefault();
      if (dragIdx == null || dragOverIdx.current == null || dragIdx === dragOverIdx.current) {
        setDragIdx(null);
        return;
      }
      const next = [...activeSorts];
      const [moved] = next.splice(dragIdx, 1);
      next.splice(dragOverIdx.current, 0, moved);
      onSortsChange(next.map((s, i) => ({ ...s, priority: i + 1 })));
      setDragIdx(null);
      dragOverIdx.current = null;
    },
    [dragIdx, activeSorts, onSortsChange],
  );

  const handleDragEnd = useCallback(() => {
    setDragIdx(null);
    dragOverIdx.current = null;
  }, []);

  const usedFields = new Set(activeSorts.map((s) => s.fieldCode));
  const canAddMore = sortableColumns.some((c) => !usedFields.has(c.field));

  return (
    <Popover.Root>
      <Popover.Trigger asChild>{children}</Popover.Trigger>
      <Popover.Portal>
        <Popover.Content
          className="z-50 w-80 rounded-lg border border-gray-200 bg-white shadow-lg"
          sideOffset={4}
          align="start"
        >
          {/* Header */}
          <div className="flex items-center justify-between border-b border-gray-100 px-3 py-2">
            <span className="text-sm font-medium text-gray-700">Sort</span>
            {activeSorts.length > 0 && (
              <button
                type="button"
                onClick={handleClearAll}
                className="text-xs text-gray-400 hover:text-red-500"
              >
                Clear all
              </button>
            )}
          </div>

          {/* Sort rules */}
          <div className="max-h-60 overflow-y-auto p-2">
            {activeSorts.length === 0 && (
              <div className="py-3 text-center text-xs text-gray-400">No sort rules</div>
            )}
            {activeSorts.map((sort, idx) => {
              const colMeta = sortableColumns.find((c) => c.field === sort.fieldCode);
              const labels = getDirectionLabels(colMeta?.valueType);
              return (
                <div
                  key={`${sort.fieldCode}-${idx}`}
                  className={`mb-1 flex items-center gap-1.5 rounded px-1.5 py-1 ${
                    dragIdx === idx ? 'opacity-50' : ''
                  }`}
                  draggable
                  onDragStart={() => handleDragStart(idx)}
                  onDragOver={(e) => handleDragOver(e, idx)}
                  onDrop={handleDrop}
                  onDragEnd={handleDragEnd}
                >
                  {/* Drag handle */}
                  <span className="cursor-grab text-gray-300 hover:text-gray-500" aria-hidden>
                    &#x2807;
                  </span>

                  {/* Field selector */}
                  <select
                    value={sort.fieldCode}
                    onChange={(e) => handleFieldChange(idx, e.target.value)}
                    className="min-w-0 flex-1 truncate rounded border border-gray-200 bg-white px-1.5 py-1 text-xs text-gray-700 focus:border-blue-400 focus:outline-none"
                  >
                    {sortableColumns
                      .filter((c) => c.field === sort.fieldCode || !usedFields.has(c.field))
                      .map((c) => (
                        <option key={c.field} value={c.field}>
                          {c.label}
                        </option>
                      ))}
                  </select>

                  {/* Direction toggle */}
                  <button
                    type="button"
                    onClick={() => handleToggleDirection(idx)}
                    className="shrink-0 rounded border border-gray-200 px-2 py-1 text-xs text-gray-600 hover:bg-gray-50"
                  >
                    {sort.direction === 'asc' ? labels.asc : labels.desc}
                  </button>

                  {/* Remove */}
                  <button
                    type="button"
                    onClick={() => handleRemoveRule(idx)}
                    className="shrink-0 rounded p-0.5 text-gray-300 hover:bg-red-50 hover:text-red-500"
                    aria-label="Remove sort rule"
                  >
                    <svg className="h-3.5 w-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
                    </svg>
                  </button>
                </div>
              );
            })}
          </div>

          {/* Footer: add rule */}
          {canAddMore && (
            <div className="border-t border-gray-100 px-3 py-2">
              <button
                type="button"
                onClick={handleAddRule}
                className="flex items-center gap-1 text-xs text-blue-600 hover:text-blue-700"
              >
                <svg className="h-3.5 w-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 4v16m8-8H4" />
                </svg>
                Add sort rule
              </button>
            </div>
          )}

          <Popover.Arrow className="fill-white" />
        </Popover.Content>
      </Popover.Portal>
    </Popover.Root>
  );
}
