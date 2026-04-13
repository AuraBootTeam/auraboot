/**
 * Viewport Toolbar Component
 *
 * Toolbar for zoom controls and device preview selection.
 *
 * @since 3.2.0
 */

import React, { useState, useCallback } from 'react';
import { ZOOM_PRESETS, DEVICE_PRESETS, getZoomLabel } from './devices';
import type { UseViewportResult } from './types';

interface ViewportToolbarProps {
  viewport: UseViewportResult;
  className?: string;
}

/**
 * Viewport Toolbar Component
 */
export const ViewportToolbar: React.FC<ViewportToolbarProps> = ({ viewport, className = '' }) => {
  const [showZoomMenu, setShowZoomMenu] = useState(false);
  const [showDeviceMenu, setShowDeviceMenu] = useState(false);

  const handleZoomSelect = useCallback(
    (zoom: number) => {
      viewport.setZoom(zoom);
      setShowZoomMenu(false);
    },
    [viewport],
  );

  const handleDeviceSelect = useCallback(
    (deviceId: string | null) => {
      viewport.setDevice(deviceId);
      setShowDeviceMenu(false);
    },
    [viewport],
  );

  return (
    <div className={`viewport-toolbar flex items-center gap-2 ${className}`}>
      {/* Zoom Controls */}
      <div className="flex items-center rounded-md border border-gray-200 bg-white shadow-sm">
        {/* Zoom Out */}
        <button
          type="button"
          onClick={viewport.zoomOut}
          className="rounded-l-md px-2 py-1.5 text-gray-600 transition-colors hover:bg-gray-100"
          title="缩小 (Ctrl + -)"
        >
          <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M20 12H4" />
          </svg>
        </button>

        {/* Zoom Level */}
        <div className="relative">
          <button
            type="button"
            onClick={() => setShowZoomMenu(!showZoomMenu)}
            className="min-w-[60px] border-x border-gray-200 px-3 py-1.5 text-xs font-medium text-gray-700 hover:bg-gray-100"
          >
            {getZoomLabel(viewport.zoom)}
          </button>

          {/* Zoom Menu */}
          {showZoomMenu && (
            <>
              <div className="fixed inset-0 z-10" onClick={() => setShowZoomMenu(false)} />
              <div className="absolute top-full left-1/2 z-20 mt-1 min-w-[120px] -translate-x-1/2 rounded-md border border-gray-200 bg-white py-1 shadow-lg">
                {ZOOM_PRESETS.map((preset) => (
                  <button
                    key={preset.value}
                    type="button"
                    onClick={() => handleZoomSelect(preset.value)}
                    className={`w-full px-3 py-1.5 text-left text-xs hover:bg-gray-100 ${
                      Math.abs(viewport.zoom - preset.value) < 0.01
                        ? 'bg-blue-50 text-blue-600'
                        : 'text-gray-700'
                    }`}
                  >
                    {preset.label}
                  </button>
                ))}
                <div className="my-1 border-t border-gray-100" />
                <button
                  type="button"
                  onClick={() => {
                    viewport.zoomToFit();
                    setShowZoomMenu(false);
                  }}
                  className="w-full px-3 py-1.5 text-left text-xs text-gray-700 hover:bg-gray-100"
                >
                  适应画布
                </button>
                <button
                  type="button"
                  onClick={() => {
                    viewport.resetZoom();
                    setShowZoomMenu(false);
                  }}
                  className="w-full px-3 py-1.5 text-left text-xs text-gray-700 hover:bg-gray-100"
                >
                  重置 (100%)
                </button>
              </div>
            </>
          )}
        </div>

        {/* Zoom In */}
        <button
          type="button"
          onClick={viewport.zoomIn}
          className="rounded-r-md px-2 py-1.5 text-gray-600 transition-colors hover:bg-gray-100"
          title="放大 (Ctrl + +)"
        >
          <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 4v16m8-8H4" />
          </svg>
        </button>
      </div>

      {/* Divider */}
      <div className="h-6 w-px bg-gray-200" />

      {/* Device Preview */}
      <div className="relative">
        <button
          type="button"
          onClick={() => setShowDeviceMenu(!showDeviceMenu)}
          className="flex items-center gap-1.5 rounded-md border border-gray-200 bg-white px-3 py-1.5 text-xs font-medium text-gray-700 shadow-sm transition-colors hover:bg-gray-50"
        >
          <span>{viewport.deviceName ? `${viewport.deviceName}` : '🖥️ 自适应'}</span>
          <svg className="h-3 w-3" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 9l-7 7-7-7" />
          </svg>
        </button>

        {/* Device Menu */}
        {showDeviceMenu && (
          <>
            <div className="fixed inset-0 z-10" onClick={() => setShowDeviceMenu(false)} />
            <div className="absolute top-full right-0 z-20 mt-1 min-w-[160px] rounded-md border border-gray-200 bg-white py-1 shadow-lg">
              <button
                type="button"
                onClick={() => handleDeviceSelect(null)}
                className={`flex w-full items-center gap-2 px-3 py-1.5 text-left text-xs hover:bg-gray-100 ${
                  !viewport.deviceWidth ? 'bg-blue-50 text-blue-600' : 'text-gray-700'
                }`}
              >
                <span>🖥️</span>
                <span>自适应</span>
              </button>
              <div className="my-1 border-t border-gray-100" />
              {DEVICE_PRESETS.map((device) => (
                <button
                  key={device.id}
                  type="button"
                  onClick={() => handleDeviceSelect(device.id)}
                  className={`flex w-full items-center justify-between px-3 py-1.5 text-left text-xs hover:bg-gray-100 ${
                    viewport.deviceName === device.name
                      ? 'bg-blue-50 text-blue-600'
                      : 'text-gray-700'
                  }`}
                >
                  <span className="flex items-center gap-2">
                    <span>{device.icon}</span>
                    <span>{device.name}</span>
                  </span>
                  <span className="text-gray-400">{device.width}px</span>
                </button>
              ))}
            </div>
          </>
        )}
      </div>

      {/* Pan indicator */}
      {viewport.isPanning && (
        <div className="flex items-center gap-1 rounded-md bg-blue-100 px-2 py-1 text-xs text-blue-700">
          <svg className="h-3 w-3" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={2}
              d="M7 11.5V14m0-2.5v-6a1.5 1.5 0 113 0m-3 6a1.5 1.5 0 00-3 0v2a7.5 7.5 0 0015 0v-5a1.5 1.5 0 00-3 0m-6-3V11m0-5.5v-1a1.5 1.5 0 013 0v1m0 0V11m0-5.5a1.5 1.5 0 013 0v3m0 0V11"
            />
          </svg>
          拖动中
        </div>
      )}
    </div>
  );
};

export default ViewportToolbar;
