/**
 * ColorPickerField — Smart field wrapper for color selection in DSL forms.
 * Renders a color swatch + popover picker.
 */
import React, { useState, useRef, useEffect } from 'react';

interface ColorPickerFieldProps {
  name?: string;
  label?: string;
  value?: string;
  defaultValue?: string;
  required?: boolean;
  disabled?: boolean;
  readOnly?: boolean;
  onChange?: (value: string) => void;
  context?: any;
}

const PRESET_COLORS = [
  '#000000',
  '#ffffff',
  '#9ca3af',
  '#ef4444',
  '#f97316',
  '#eab308',
  '#22c55e',
  '#06b6d4',
  '#3b82f6',
  '#8b5cf6',
  '#ec4899',
];

export function ColorPickerField({
  value = '',
  defaultValue = '#3b82f6',
  disabled = false,
  readOnly = false,
  onChange,
}: ColorPickerFieldProps) {
  const [open, setOpen] = useState(false);
  const [currentColor, setCurrentColor] = useState(value || defaultValue);
  const containerRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (value !== undefined) setCurrentColor(value || defaultValue);
  }, [value, defaultValue]);

  useEffect(() => {
    if (!open) return;
    const handler = (e: MouseEvent) => {
      if (containerRef.current && !containerRef.current.contains(e.target as Node)) {
        setOpen(false);
      }
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, [open]);

  if (readOnly) {
    return (
      <div className="flex items-center gap-2">
        <div
          className="h-6 w-6 rounded border border-gray-300"
          style={{ backgroundColor: currentColor }}
        />
        <span className="text-sm text-gray-700">{currentColor}</span>
      </div>
    );
  }

  return (
    <div ref={containerRef} className="relative inline-block">
      <button
        type="button"
        disabled={disabled}
        onClick={() => !disabled && setOpen(!open)}
        className="flex items-center gap-2 rounded-md border border-gray-300 px-3 py-1.5 text-sm hover:border-gray-400 disabled:cursor-not-allowed disabled:opacity-50"
      >
        <div
          className="h-5 w-5 rounded border border-gray-200"
          style={{ backgroundColor: currentColor }}
        />
        <span>{currentColor}</span>
      </button>

      {open && (
        <div className="absolute top-full left-0 z-50 mt-1 rounded-lg border border-gray-200 bg-white p-3 shadow-lg">
          <div className="mb-2 grid grid-cols-6 gap-1.5">
            {PRESET_COLORS.map((color) => (
              <button
                key={color}
                type="button"
                className={`h-7 w-7 rounded-md border-2 ${currentColor === color ? 'border-blue-500' : 'border-transparent'} transition-transform hover:scale-110`}
                style={{ backgroundColor: color }}
                onClick={() => {
                  setCurrentColor(color);
                  onChange?.(color);
                }}
              />
            ))}
          </div>
          <input
            type="color"
            value={currentColor}
            onChange={(e) => {
              setCurrentColor(e.target.value);
              onChange?.(e.target.value);
            }}
            className="h-8 w-full cursor-pointer"
          />
        </div>
      )}
    </div>
  );
}

export default ColorPickerField;
