/**
 * RowHeightSelector — Dropdown to select table row height preset.
 *
 * Renders as a small toolbar button with a dropdown showing 4 height options.
 * Each option shows a visual indicator (horizontal lines with different spacing).
 */

import React, { useState, useRef, useEffect, useCallback } from 'react';
import type { RowHeight } from '~/smart/types/savedView';
import { ROW_HEIGHT_CONFIG, DEFAULT_ROW_HEIGHT } from '~/smart/types/savedView';

export interface RowHeightSelectorProps {
  /** Current row height value */
  value?: RowHeight;
  /** Callback when row height changes */
  onChange: (height: RowHeight) => void;
}

const ROW_HEIGHT_OPTIONS: RowHeight[] = ['short', 'medium', 'tall', 'extra-tall'];

/** Visual indicator: horizontal lines with varying gaps */
function HeightIcon({ height }: { height: RowHeight }) {
  const gap = height === 'short' ? 2 : height === 'medium' ? 3 : height === 'tall' ? 4 : 5;
  return (
    <svg
      className="h-4 w-4"
      viewBox="0 0 16 16"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.5}
    >
      <line x1="2" y1={8 - gap} x2="14" y2={8 - gap} />
      <line x1="2" y1="8" x2="14" y2="8" />
      <line x1="2" y1={8 + gap} x2="14" y2={8 + gap} />
    </svg>
  );
}

export const RowHeightSelector: React.FC<RowHeightSelectorProps> = ({ value, onChange }) => {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  const current = value || DEFAULT_ROW_HEIGHT;

  // Close on click outside
  useEffect(() => {
    if (!open) return;
    const handler = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) {
        setOpen(false);
      }
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, [open]);

  const handleSelect = useCallback(
    (h: RowHeight) => {
      onChange(h);
      setOpen(false);
    },
    [onChange],
  );

  return (
    <div className="relative" ref={ref}>
      <button
        type="button"
        onClick={() => setOpen((p) => !p)}
        title="Row height"
        className="rounded-md p-1.5 text-gray-400 transition-colors hover:bg-gray-100 hover:text-gray-600"
        data-testid="row-height-btn"
      >
        <HeightIcon height={current} />
      </button>

      {open && (
        <div className="absolute top-full right-0 z-50 mt-1 w-40 rounded-lg border border-gray-200 bg-white py-1 shadow-lg">
          {ROW_HEIGHT_OPTIONS.map((h) => (
            <button
              key={h}
              type="button"
              onClick={() => handleSelect(h)}
              data-testid={`row-height-option-${h}`}
              className={`flex w-full items-center gap-2.5 px-3 py-1.5 text-sm transition-colors ${
                h === current
                  ? 'bg-blue-50 font-medium text-blue-700'
                  : 'text-gray-700 hover:bg-gray-50'
              }`}
            >
              <HeightIcon height={h} />
              <span>{ROW_HEIGHT_CONFIG[h].label}</span>
              <span className="ml-auto text-xs text-gray-400">{ROW_HEIGHT_CONFIG[h].px}px</span>
            </button>
          ))}
        </div>
      )}
    </div>
  );
};

export default RowHeightSelector;
