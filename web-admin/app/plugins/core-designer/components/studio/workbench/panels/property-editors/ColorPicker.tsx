/**
 * ColorPicker Component
 *
 * Advanced color picker with multiple format support.
 *
 * @since 3.2.0
 */

import React, { useState, useCallback, useMemo, useRef, useEffect } from 'react';
import type { BaseEditorProps, ColorFormat, ColorPreset } from './types';

interface ColorPickerProps extends BaseEditorProps<string> {
  /** Output format */
  format?: ColorFormat;
  /** Show alpha slider */
  showAlpha?: boolean;
  /** Color presets */
  presets?: ColorPreset[];
  /** Show input field */
  showInput?: boolean;
  /** Inline mode (no popover) */
  inline?: boolean;
}

/**
 * Default color presets
 */
const DEFAULT_PRESETS: ColorPreset[] = [
  { name: '黑色', color: '#000000' },
  { name: '白色', color: '#ffffff' },
  { name: '灰色', color: '#9ca3af' },
  { name: '红色', color: '#ef4444' },
  { name: '橙色', color: '#f97316' },
  { name: '黄色', color: '#eab308' },
  { name: '绿色', color: '#22c55e' },
  { name: '青色', color: '#06b6d4' },
  { name: '蓝色', color: '#3b82f6' },
  { name: '紫色', color: '#8b5cf6' },
  { name: '粉色', color: '#ec4899' },
  { name: '透明', color: 'transparent' },
];

/**
 * Parse color string to components
 */
function parseColor(color: string): { h: number; s: number; v: number; a: number } {
  if (!color || color === 'transparent') {
    return { h: 0, s: 0, v: 100, a: 0 };
  }

  // Parse hex
  let r = 0,
    g = 0,
    b = 0,
    a = 1;

  if (color.startsWith('#')) {
    const hex = color.slice(1);
    if (hex.length === 3) {
      r = parseInt(hex[0] + hex[0], 16);
      g = parseInt(hex[1] + hex[1], 16);
      b = parseInt(hex[2] + hex[2], 16);
    } else if (hex.length === 6) {
      r = parseInt(hex.slice(0, 2), 16);
      g = parseInt(hex.slice(2, 4), 16);
      b = parseInt(hex.slice(4, 6), 16);
    } else if (hex.length === 8) {
      r = parseInt(hex.slice(0, 2), 16);
      g = parseInt(hex.slice(2, 4), 16);
      b = parseInt(hex.slice(4, 6), 16);
      a = parseInt(hex.slice(6, 8), 16) / 255;
    }
  }

  // Parse rgba
  const rgbaMatch = color.match(/rgba?\((\d+),\s*(\d+),\s*(\d+)(?:,\s*([\d.]+))?\)/);
  if (rgbaMatch) {
    r = parseInt(rgbaMatch[1]);
    g = parseInt(rgbaMatch[2]);
    b = parseInt(rgbaMatch[3]);
    a = rgbaMatch[4] ? parseFloat(rgbaMatch[4]) : 1;
  }

  // RGB to HSV
  r /= 255;
  g /= 255;
  b /= 255;

  const max = Math.max(r, g, b);
  const min = Math.min(r, g, b);
  const d = max - min;

  let h = 0;
  const s = max === 0 ? 0 : d / max;
  const v = max;

  if (d !== 0) {
    switch (max) {
      case r:
        h = ((g - b) / d + (g < b ? 6 : 0)) / 6;
        break;
      case g:
        h = ((b - r) / d + 2) / 6;
        break;
      case b:
        h = ((r - g) / d + 4) / 6;
        break;
    }
  }

  return { h: h * 360, s: s * 100, v: v * 100, a };
}

/**
 * Convert HSV to hex color
 */
function hsvToHex(h: number, s: number, v: number, a: number = 1): string {
  h /= 360;
  s /= 100;
  v /= 100;

  let r = 0,
    g = 0,
    b = 0;

  const i = Math.floor(h * 6);
  const f = h * 6 - i;
  const p = v * (1 - s);
  const q = v * (1 - f * s);
  const t = v * (1 - (1 - f) * s);

  switch (i % 6) {
    case 0:
      r = v;
      g = t;
      b = p;
      break;
    case 1:
      r = q;
      g = v;
      b = p;
      break;
    case 2:
      r = p;
      g = v;
      b = t;
      break;
    case 3:
      r = p;
      g = q;
      b = v;
      break;
    case 4:
      r = t;
      g = p;
      b = v;
      break;
    case 5:
      r = v;
      g = p;
      b = q;
      break;
  }

  const toHex = (n: number) => {
    const hex = Math.round(n * 255).toString(16);
    return hex.length === 1 ? '0' + hex : hex;
  };

  const hex = `#${toHex(r)}${toHex(g)}${toHex(b)}`;

  if (a < 1) {
    return `${hex}${toHex(a)}`;
  }

  return hex;
}

/**
 * ColorPicker Component
 */
export const ColorPicker: React.FC<ColorPickerProps> = ({
  value = '#000000',
  onChange,
  disabled = false,
  label,
  error,
  format = 'hex',
  showAlpha = false,
  presets = DEFAULT_PRESETS,
  showInput = true,
  inline = false,
  className = '',
}) => {
  const [isOpen, setIsOpen] = useState(inline);
  const [hsv, setHsv] = useState(() => parseColor(value));
  const [inputValue, setInputValue] = useState(value);
  const pickerRef = useRef<HTMLDivElement>(null);
  const saturationRef = useRef<HTMLDivElement>(null);

  // Update HSV when value changes
  useEffect(() => {
    setHsv(parseColor(value));
    setInputValue(value);
  }, [value]);

  // Close on outside click
  useEffect(() => {
    if (!isOpen || inline) return;

    const handleClickOutside = (e: MouseEvent) => {
      if (pickerRef.current && !pickerRef.current.contains(e.target as Node)) {
        setIsOpen(false);
      }
    };

    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, [isOpen, inline]);

  // Emit color change
  const emitChange = useCallback(
    (newHsv: typeof hsv) => {
      const hex = hsvToHex(newHsv.h, newHsv.s, newHsv.v, showAlpha ? newHsv.a : 1);
      onChange(hex);
    },
    [onChange, showAlpha],
  );

  // Handle saturation/value drag
  const handleSaturationDrag = useCallback(
    (e: React.MouseEvent) => {
      if (!saturationRef.current || disabled) return;

      const rect = saturationRef.current.getBoundingClientRect();
      const updateColor = (clientX: number, clientY: number) => {
        const x = Math.max(0, Math.min(1, (clientX - rect.left) / rect.width));
        const y = Math.max(0, Math.min(1, (clientY - rect.top) / rect.height));
        const newHsv = { ...hsv, s: x * 100, v: (1 - y) * 100 };
        setHsv(newHsv);
        emitChange(newHsv);
      };

      updateColor(e.clientX, e.clientY);

      const handleMouseMove = (e: MouseEvent) => updateColor(e.clientX, e.clientY);
      const handleMouseUp = () => {
        document.removeEventListener('mousemove', handleMouseMove);
        document.removeEventListener('mouseup', handleMouseUp);
      };

      document.addEventListener('mousemove', handleMouseMove);
      document.addEventListener('mouseup', handleMouseUp);
    },
    [hsv, emitChange, disabled],
  );

  // Handle hue change
  const handleHueChange = useCallback(
    (e: React.ChangeEvent<HTMLInputElement>) => {
      if (disabled) return;
      const newHsv = { ...hsv, h: parseFloat(e.target.value) };
      setHsv(newHsv);
      emitChange(newHsv);
    },
    [hsv, emitChange, disabled],
  );

  // Handle alpha change
  const handleAlphaChange = useCallback(
    (e: React.ChangeEvent<HTMLInputElement>) => {
      if (disabled) return;
      const newHsv = { ...hsv, a: parseFloat(e.target.value) };
      setHsv(newHsv);
      emitChange(newHsv);
    },
    [hsv, emitChange, disabled],
  );

  // Handle input change
  const handleInputChange = useCallback(
    (e: React.ChangeEvent<HTMLInputElement>) => {
      const val = e.target.value;
      setInputValue(val);

      // Validate and update
      if (/^#([0-9A-Fa-f]{3}|[0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})$/.test(val)) {
        const newHsv = parseColor(val);
        setHsv(newHsv);
        onChange(val);
      }
    },
    [onChange],
  );

  // Handle preset click
  const handlePresetClick = useCallback(
    (color: string) => {
      if (disabled) return;
      const newHsv = parseColor(color);
      setHsv(newHsv);
      setInputValue(color);
      onChange(color);
    },
    [onChange, disabled],
  );

  // Current color hex
  const currentHex = useMemo(
    () => hsvToHex(hsv.h, hsv.s, hsv.v, showAlpha ? hsv.a : 1),
    [hsv, showAlpha],
  );

  const pickerContent = (
    <div className="space-y-3 p-3">
      {/* Saturation/Value panel */}
      <div
        ref={saturationRef}
        className="relative h-36 w-full cursor-crosshair rounded"
        style={{
          background: `linear-gradient(to right, #fff, hsl(${hsv.h}, 100%, 50%))`,
        }}
        onMouseDown={handleSaturationDrag}
      >
        <div
          className="absolute inset-0 rounded"
          style={{
            background: 'linear-gradient(to bottom, transparent, #000)',
          }}
        />
        {/* Cursor */}
        <div
          className="absolute h-3 w-3 -translate-x-1/2 -translate-y-1/2 rounded-full border-2 border-white shadow"
          style={{
            left: `${hsv.s}%`,
            top: `${100 - hsv.v}%`,
            backgroundColor: currentHex,
          }}
        />
      </div>

      {/* Hue slider */}
      <div className="space-y-1">
        <div className="text-xs text-gray-500">色相</div>
        <input
          type="range"
          min="0"
          max="360"
          value={hsv.h}
          onChange={handleHueChange}
          disabled={disabled}
          className="h-2 w-full cursor-pointer appearance-none rounded-lg"
          style={{
            background: `linear-gradient(to right,
              hsl(0, 100%, 50%),
              hsl(60, 100%, 50%),
              hsl(120, 100%, 50%),
              hsl(180, 100%, 50%),
              hsl(240, 100%, 50%),
              hsl(300, 100%, 50%),
              hsl(360, 100%, 50%)
            )`,
          }}
        />
      </div>

      {/* Alpha slider */}
      {showAlpha && (
        <div className="space-y-1">
          <div className="text-xs text-gray-500">透明度</div>
          <input
            type="range"
            min="0"
            max="1"
            step="0.01"
            value={hsv.a}
            onChange={handleAlphaChange}
            disabled={disabled}
            className="h-2 w-full cursor-pointer appearance-none rounded-lg"
            style={{
              background: `linear-gradient(to right, transparent, ${hsvToHex(hsv.h, hsv.s, hsv.v)})`,
            }}
          />
        </div>
      )}

      {/* Input */}
      {showInput && (
        <div className="flex items-center gap-2">
          <div
            className="h-8 w-8 rounded border border-gray-200"
            style={{ backgroundColor: currentHex }}
          />
          <input
            type="text"
            value={inputValue}
            onChange={handleInputChange}
            disabled={disabled}
            className="flex-1 rounded border border-gray-200 px-2 py-1.5 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
            placeholder="#000000"
          />
        </div>
      )}

      {/* Presets */}
      {presets.length > 0 && (
        <div className="space-y-1">
          <div className="text-xs text-gray-500">预设颜色</div>
          <div className="grid grid-cols-6 gap-1">
            {presets.map((preset) => (
              <button
                key={preset.name}
                type="button"
                onClick={() => handlePresetClick(preset.color)}
                disabled={disabled}
                className={`h-6 w-6 cursor-pointer rounded border border-gray-200 transition-shadow hover:ring-2 hover:ring-blue-500 ${preset.color === 'transparent' ? 'bg-checkered' : ''} `}
                style={{
                  backgroundColor: preset.color === 'transparent' ? undefined : preset.color,
                }}
                title={preset.name}
              />
            ))}
          </div>
        </div>
      )}
    </div>
  );

  if (inline) {
    return (
      <div className={`rounded-lg border border-gray-200 bg-white ${className}`}>
        {label && <div className="px-3 pt-2 text-sm font-medium text-gray-700">{label}</div>}
        {pickerContent}
        {error && <div className="px-3 pb-2 text-xs text-red-500">{error}</div>}
      </div>
    );
  }

  return (
    <div className={`relative ${className}`} ref={pickerRef}>
      {label && <label className="mb-1 block text-sm font-medium text-gray-700">{label}</label>}

      {/* Trigger button */}
      <button
        type="button"
        onClick={() => !disabled && setIsOpen(!isOpen)}
        disabled={disabled}
        className={`flex w-full items-center gap-2 rounded-md border px-3 py-2 ${disabled ? 'cursor-not-allowed bg-gray-100' : 'bg-white hover:border-gray-400'} ${error ? 'border-red-300' : 'border-gray-200'} `}
      >
        <div
          className="h-6 w-6 rounded border border-gray-200"
          style={{ backgroundColor: value || '#000000' }}
        />
        <span className="flex-1 truncate text-left text-sm text-gray-600">
          {value || '选择颜色'}
        </span>
        <svg
          className="h-4 w-4 text-gray-400"
          fill="none"
          stroke="currentColor"
          viewBox="0 0 24 24"
        >
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 9l-7 7-7-7" />
        </svg>
      </button>

      {/* Popover */}
      {isOpen && (
        <div className="absolute z-50 mt-1 min-w-[240px] rounded-lg border border-gray-200 bg-white shadow-lg">
          {pickerContent}
        </div>
      )}

      {error && <div className="mt-1 text-xs text-red-500">{error}</div>}
    </div>
  );
};

export default ColorPicker;
