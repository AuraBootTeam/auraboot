/**
 * DeviceSelector Component
 *
 * Dropdown selector for device preview presets.
 *
 * @since 3.2.0
 */

import React, { useState, useCallback, useRef, useEffect, useMemo } from 'react';
import { DEVICE_PRESETS, getGroupedPresets, DEVICE_TYPE_LABELS, getDevicePreset } from './presets';
import type { DevicePreset, DeviceOrientation, CustomDevice } from './types';

interface DeviceSelectorProps {
  /** Selected device ID */
  selectedId: string;
  /** Current orientation */
  orientation: DeviceOrientation;
  /** Custom device dimensions */
  customDevice?: CustomDevice;
  /** On device change */
  onDeviceChange: (deviceId: string) => void;
  /** On orientation change */
  onOrientationChange: (orientation: DeviceOrientation) => void;
  /** On custom device change */
  onCustomDeviceChange?: (device: CustomDevice) => void;
  /** Compact mode */
  compact?: boolean;
}

/**
 * DeviceSelector Component
 */
export const DeviceSelector: React.FC<DeviceSelectorProps> = ({
  selectedId,
  orientation,
  customDevice,
  onDeviceChange,
  onOrientationChange,
  onCustomDeviceChange,
  compact = false,
}) => {
  const [isOpen, setIsOpen] = useState(false);
  const [showCustomForm, setShowCustomForm] = useState(false);
  const [customWidth, setCustomWidth] = useState(customDevice?.width || 1024);
  const [customHeight, setCustomHeight] = useState(customDevice?.height || 768);
  const dropdownRef = useRef<HTMLDivElement>(null);

  // Get selected device
  const selectedDevice = useMemo(
    () => getDevicePreset(selectedId) || DEVICE_PRESETS[0],
    [selectedId],
  );

  // Get effective dimensions
  const effectiveDimensions = useMemo(() => {
    let width = selectedDevice.width;
    let height = selectedDevice.height;

    if (selectedId === 'custom' && customDevice) {
      width = customDevice.width;
      height = customDevice.height;
    }

    if (orientation === 'landscape' && height > width) {
      return { width: height, height: width };
    }
    if (orientation === 'portrait' && width > height) {
      return { width: height, height: width };
    }

    return { width, height };
  }, [selectedDevice, selectedId, customDevice, orientation]);

  // Close on outside click
  useEffect(() => {
    if (!isOpen) return;

    const handleClickOutside = (e: MouseEvent) => {
      if (dropdownRef.current && !dropdownRef.current.contains(e.target as Node)) {
        setIsOpen(false);
        setShowCustomForm(false);
      }
    };

    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, [isOpen]);

  // Handle device select
  const handleDeviceSelect = useCallback(
    (deviceId: string) => {
      if (deviceId === 'custom') {
        setShowCustomForm(true);
      } else {
        onDeviceChange(deviceId);
        setIsOpen(false);
        setShowCustomForm(false);
      }
    },
    [onDeviceChange],
  );

  // Handle custom device apply
  const handleCustomApply = useCallback(() => {
    onCustomDeviceChange?.({
      name: '自定义',
      width: customWidth,
      height: customHeight,
    });
    onDeviceChange('custom');
    setIsOpen(false);
    setShowCustomForm(false);
  }, [customWidth, customHeight, onCustomDeviceChange, onDeviceChange]);

  // Toggle orientation
  const handleOrientationToggle = useCallback(() => {
    onOrientationChange(orientation === 'portrait' ? 'landscape' : 'portrait');
  }, [orientation, onOrientationChange]);

  // Grouped presets
  const groupedPresets = useMemo(() => getGroupedPresets(), []);

  return (
    <div className="flex items-center gap-2">
      {/* Device dropdown */}
      <div className="relative" ref={dropdownRef}>
        <button
          type="button"
          onClick={() => setIsOpen(!isOpen)}
          className={`flex items-center gap-2 rounded-md border border-gray-200 bg-white px-3 py-1.5 transition-colors hover:border-gray-300 ${compact ? 'text-xs' : 'text-sm'} `}
        >
          <svg
            className={`${compact ? 'h-3.5 w-3.5' : 'h-4 w-4'} text-gray-500`}
            fill="none"
            stroke="currentColor"
            viewBox="0 0 24 24"
          >
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={2}
              d={selectedDevice.icon}
            />
          </svg>
          <span className="text-gray-700">
            {selectedId === 'custom' && customDevice
              ? `${effectiveDimensions.width}×${effectiveDimensions.height}`
              : selectedDevice.name}
          </span>
          <svg
            className={`${compact ? 'h-3 w-3' : 'h-4 w-4'} text-gray-400 ${
              isOpen ? 'rotate-180' : ''
            } transition-transform`}
            fill="none"
            stroke="currentColor"
            viewBox="0 0 24 24"
          >
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 9l-7 7-7-7" />
          </svg>
        </button>

        {/* Dropdown menu */}
        {isOpen && (
          <div className="absolute z-50 mt-1 w-64 rounded-lg border border-gray-200 bg-white shadow-lg">
            {showCustomForm ? (
              <div className="p-3">
                <div className="mb-2 text-sm font-medium text-gray-700">自定义尺寸</div>
                <div className="space-y-2">
                  <div className="flex items-center gap-2">
                    <label className="w-12 text-xs text-gray-500">宽度</label>
                    <input
                      type="number"
                      value={customWidth}
                      onChange={(e) => setCustomWidth(parseInt(e.target.value) || 0)}
                      className="flex-1 rounded border border-gray-200 px-2 py-1 text-sm focus:ring-1 focus:ring-blue-500 focus:outline-none"
                      min={320}
                      max={3840}
                    />
                    <span className="text-xs text-gray-400">px</span>
                  </div>
                  <div className="flex items-center gap-2">
                    <label className="w-12 text-xs text-gray-500">高度</label>
                    <input
                      type="number"
                      value={customHeight}
                      onChange={(e) => setCustomHeight(parseInt(e.target.value) || 0)}
                      className="flex-1 rounded border border-gray-200 px-2 py-1 text-sm focus:ring-1 focus:ring-blue-500 focus:outline-none"
                      min={240}
                      max={2160}
                    />
                    <span className="text-xs text-gray-400">px</span>
                  </div>
                </div>
                <div className="mt-3 flex justify-end gap-2">
                  <button
                    type="button"
                    onClick={() => setShowCustomForm(false)}
                    className="px-2 py-1 text-xs text-gray-600 hover:text-gray-800"
                  >
                    取消
                  </button>
                  <button
                    type="button"
                    onClick={handleCustomApply}
                    className="rounded bg-blue-600 px-3 py-1 text-xs text-white hover:bg-blue-700"
                  >
                    应用
                  </button>
                </div>
              </div>
            ) : (
              <div className="max-h-80 overflow-auto py-1">
                {Array.from(groupedPresets.entries()).map(([type, presets]) => (
                  <div key={type}>
                    <div className="sticky top-0 bg-gray-50 px-3 py-1.5 text-xs font-medium text-gray-400">
                      {DEVICE_TYPE_LABELS[type]}
                    </div>
                    {presets.map((preset) => (
                      <button
                        key={preset.id}
                        type="button"
                        onClick={() => handleDeviceSelect(preset.id)}
                        className={`flex w-full items-center gap-2 px-3 py-2 text-left transition-colors hover:bg-gray-50 ${selectedId === preset.id ? 'bg-blue-50' : ''} `}
                      >
                        <svg
                          className="h-4 w-4 text-gray-400"
                          fill="none"
                          stroke="currentColor"
                          viewBox="0 0 24 24"
                        >
                          <path
                            strokeLinecap="round"
                            strokeLinejoin="round"
                            strokeWidth={2}
                            d={preset.icon}
                          />
                        </svg>
                        <div className="min-w-0 flex-1">
                          <div className="text-sm text-gray-700">{preset.name}</div>
                          {preset.type !== 'custom' && (
                            <div className="text-xs text-gray-400">
                              {preset.width}×{preset.height}
                              {preset.dpr && preset.dpr > 1 && ` @${preset.dpr}x`}
                            </div>
                          )}
                        </div>
                        {selectedId === preset.id && (
                          <svg
                            className="h-4 w-4 text-blue-600"
                            fill="none"
                            stroke="currentColor"
                            viewBox="0 0 24 24"
                          >
                            <path
                              strokeLinecap="round"
                              strokeLinejoin="round"
                              strokeWidth={2}
                              d="M5 13l4 4L19 7"
                            />
                          </svg>
                        )}
                      </button>
                    ))}
                  </div>
                ))}
              </div>
            )}
          </div>
        )}
      </div>

      {/* Orientation toggle */}
      <button
        type="button"
        onClick={handleOrientationToggle}
        className={`rounded-md border border-gray-200 bg-white p-1.5 transition-colors hover:border-gray-300 ${compact ? '' : ''} `}
        title={orientation === 'portrait' ? '切换为横屏' : '切换为竖屏'}
      >
        <svg
          className={`${compact ? 'h-4 w-4' : 'h-5 w-5'} text-gray-500 ${
            orientation === 'landscape' ? 'rotate-90' : ''
          } transition-transform`}
          fill="none"
          stroke="currentColor"
          viewBox="0 0 24 24"
        >
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth={2}
            d="M12 18h.01M7 21h10a2 2 0 002-2V5a2 2 0 00-2-2H7a2 2 0 00-2 2v14a2 2 0 002 2z"
          />
        </svg>
      </button>

      {/* Dimensions display */}
      {!compact && (
        <span className="text-xs text-gray-400">
          {effectiveDimensions.width}×{effectiveDimensions.height}
        </span>
      )}
    </div>
  );
};

export default DeviceSelector;
