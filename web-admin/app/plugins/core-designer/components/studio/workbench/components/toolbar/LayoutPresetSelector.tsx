import React, { useState, useRef, useEffect } from 'react';
import { FORM_LAYOUT_PRESETS } from '~/plugins/core-designer/components/studio/domain/schema/layout-presets';
import type { LayoutPreset } from '~/plugins/core-designer/components/studio/domain/schema/layout-presets';

interface LayoutPresetSelectorProps {
  currentColumns?: number;
  onSelect: (preset: LayoutPreset) => void;
}

/**
 * Toolbar button for quick layout column switching.
 * Displays a dropdown with 1/2/3/4 column options.
 *
 * @since 3.2.0
 */
export const LayoutPresetSelector: React.FC<LayoutPresetSelectorProps> = ({
  currentColumns = 2,
  onSelect,
}) => {
  const [isOpen, setIsOpen] = useState(false);
  const dropdownRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const handleClickOutside = (event: MouseEvent) => {
      if (dropdownRef.current && !dropdownRef.current.contains(event.target as Node)) {
        setIsOpen(false);
      }
    };
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, []);

  return (
    <div className="relative" ref={dropdownRef}>
      <button
        onClick={() => setIsOpen(!isOpen)}
        className="flex items-center gap-1 rounded px-2 py-1.5 text-sm text-gray-600 hover:bg-gray-100 hover:text-gray-900"
        title="Layout Preset"
      >
        <ColumnsIcon columns={currentColumns} />
        <span className="text-xs">{currentColumns}col</span>
        <svg className="h-3 w-3" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 9l-7 7-7-7" />
        </svg>
      </button>

      {isOpen && (
        <div className="absolute top-full left-0 z-50 mt-1 w-48 rounded-lg border border-gray-200 bg-white shadow-lg">
          <div className="py-1">
            {FORM_LAYOUT_PRESETS.map((preset) => (
              <button
                key={preset.code}
                onClick={() => {
                  onSelect(preset);
                  setIsOpen(false);
                }}
                className={`flex w-full items-center gap-3 px-3 py-2 text-sm hover:bg-gray-50 ${
                  preset.formLayout.columns === currentColumns
                    ? 'bg-blue-50 text-blue-600'
                    : 'text-gray-700'
                }`}
              >
                <ColumnsIcon columns={preset.formLayout.columns} />
                <div className="text-left">
                  <div className="font-medium">{preset.name}</div>
                  <div className="text-xs text-gray-500">{preset.description}</div>
                </div>
              </button>
            ))}
          </div>
        </div>
      )}
    </div>
  );
};

/**
 * Simple column visualization icon.
 */
const ColumnsIcon: React.FC<{ columns: number }> = ({ columns }) => {
  const cols = Math.min(columns, 4);
  const gap = 1;
  const totalWidth = 16;
  const colWidth = (totalWidth - gap * (cols - 1)) / cols;

  return (
    <svg width="16" height="14" viewBox={`0 0 ${totalWidth} 14`} className="flex-shrink-0">
      {Array.from({ length: cols }).map((_, i) => (
        <rect
          key={i}
          x={i * (colWidth + gap)}
          y="1"
          width={colWidth}
          height="12"
          rx="1"
          fill="currentColor"
          opacity="0.6"
        />
      ))}
    </svg>
  );
};
