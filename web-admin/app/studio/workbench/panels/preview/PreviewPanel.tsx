/**
 * PreviewPanel Component
 *
 * Page preview panel with mock data and interaction support.
 *
 * @since 3.2.0
 */

import React, { useState, useCallback, useEffect, useMemo, useRef } from 'react';
import { DeviceSelector, useViewport } from '../../canvas/devices';
import type { PreviewState, PreviewEventLog, PreviewMode } from './types';

interface PreviewPanelProps {
  /** Page schema */
  pageSchema: unknown;
  /** View model fields */
  viewModelFields?: Array<{ path: string; type: string; label: string }>;
  /** On close */
  onClose?: () => void;
  /** Initial mode */
  initialMode?: PreviewMode;
  /** Initial mock data */
  initialMockData?: Record<string, unknown>;
}

/**
 * PreviewPanel Component
 */
export const PreviewPanel: React.FC<PreviewPanelProps> = ({
  pageSchema,
  viewModelFields = [],
  onClose,
  initialMode = 'panel',
  initialMockData = {},
}) => {
  const [state, setState] = useState<PreviewState>({
    isActive: true,
    mode: initialMode,
    mockData: initialMockData,
    showOutlines: false,
    showBindings: false,
    enableInteractions: true,
    deviceId: 'desktop-1920',
    zoom: 100,
  });

  const [eventLogs, setEventLogs] = useState<PreviewEventLog[]>([]);
  const [showMockEditor, setShowMockEditor] = useState(false);
  const [showEventLog, setShowEventLog] = useState(false);
  const iframeRef = useRef<HTMLIFrameElement>(null);

  const viewport = useViewport({
    initialDeviceId: state.deviceId,
    initialZoom: state.zoom,
  });

  // Add event log
  const addEventLog = useCallback((log: Omit<PreviewEventLog, 'id' | 'timestamp'>) => {
    setEventLogs((prev) => [
      {
        ...log,
        id: `log-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`,
        timestamp: Date.now(),
      },
      ...prev.slice(0, 99),
    ]);
  }, []);

  // Handle mock data change
  const handleMockDataChange = useCallback(
    (path: string, value: unknown) => {
      setState((prev) => ({
        ...prev,
        mockData: {
          ...prev.mockData,
          [path]: value,
        },
      }));
      addEventLog({
        type: 'binding',
        name: '数据更新',
        data: { path, value },
      });
    },
    [addEventLog],
  );

  // Handle mode change
  const handleModeChange = useCallback((mode: PreviewMode) => {
    setState((prev) => ({ ...prev, mode }));
  }, []);

  // Toggle options
  const toggleOption = useCallback((key: keyof PreviewState) => {
    setState((prev) => ({
      ...prev,
      [key]: !prev[key],
    }));
  }, []);

  // Generate random mock data
  const generateMockData = useCallback(() => {
    const mockData: Record<string, unknown> = {};

    viewModelFields.forEach((field) => {
      switch (field.type.toLowerCase()) {
        case 'string':
          mockData[field.path] = `测试${field.label}`;
          break;
        case 'number':
        case 'integer':
          mockData[field.path] = Math.floor(Math.random() * 1000);
          break;
        case 'boolean':
          mockData[field.path] = Math.random() > 0.5;
          break;
        case 'date':
          mockData[field.path] = new Date().toISOString().split('T')[0];
          break;
        case 'datetime':
          mockData[field.path] = new Date().toISOString();
          break;
        default:
          mockData[field.path] = null;
      }
    });

    setState((prev) => ({
      ...prev,
      mockData: { ...prev.mockData, ...mockData },
    }));

    addEventLog({
      type: 'action',
      name: '生成模拟数据',
      data: mockData,
    });
  }, [viewModelFields, addEventLog]);

  // Clear mock data
  const clearMockData = useCallback(() => {
    setState((prev) => ({ ...prev, mockData: {} }));
    addEventLog({
      type: 'action',
      name: '清空模拟数据',
      data: {},
    });
  }, [addEventLog]);

  // Refresh preview
  const refreshPreview = useCallback(() => {
    if (iframeRef.current) {
      iframeRef.current.src = iframeRef.current.src;
    }
    addEventLog({
      type: 'action',
      name: '刷新预览',
      data: {},
    });
  }, [addEventLog]);

  const renderToolbar = () => (
    <div className="flex items-center justify-between border-b border-gray-200 bg-gray-50 px-4 py-2">
      {/* Left: Device selector */}
      <div className="flex items-center gap-3">
        <DeviceSelector
          selectedId={viewport.state.deviceId}
          orientation={viewport.state.orientation}
          onDeviceChange={viewport.setDevice}
          onOrientationChange={viewport.setOrientation}
          onCustomDeviceChange={viewport.setCustomDevice}
          compact
        />

        {/* Zoom controls */}
        <div className="flex items-center gap-1 border-l border-gray-200 pl-3">
          <button
            type="button"
            onClick={viewport.zoomOut}
            className="rounded p-1 text-gray-500 hover:bg-gray-100 hover:text-gray-700"
            title="缩小"
          >
            <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M20 12H4" />
            </svg>
          </button>
          <span className="w-12 text-center text-xs text-gray-600">{viewport.state.zoom}%</span>
          <button
            type="button"
            onClick={viewport.zoomIn}
            className="rounded p-1 text-gray-500 hover:bg-gray-100 hover:text-gray-700"
            title="放大"
          >
            <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M12 6v6m0 0v6m0-6h6m-6 0H6"
              />
            </svg>
          </button>
        </div>
      </div>

      {/* Center: Options */}
      <div className="flex items-center gap-2">
        <label className="flex cursor-pointer items-center gap-1.5 text-xs text-gray-600">
          <input
            type="checkbox"
            checked={state.showOutlines}
            onChange={() => toggleOption('showOutlines')}
            className="h-3.5 w-3.5 rounded border-gray-300 text-blue-600"
          />
          边框
        </label>
        <label className="flex cursor-pointer items-center gap-1.5 text-xs text-gray-600">
          <input
            type="checkbox"
            checked={state.showBindings}
            onChange={() => toggleOption('showBindings')}
            className="h-3.5 w-3.5 rounded border-gray-300 text-blue-600"
          />
          绑定
        </label>
        <label className="flex cursor-pointer items-center gap-1.5 text-xs text-gray-600">
          <input
            type="checkbox"
            checked={state.enableInteractions}
            onChange={() => toggleOption('enableInteractions')}
            className="h-3.5 w-3.5 rounded border-gray-300 text-blue-600"
          />
          交互
        </label>
      </div>

      {/* Right: Actions */}
      <div className="flex items-center gap-2">
        <button
          type="button"
          onClick={() => setShowMockEditor(!showMockEditor)}
          className={`rounded px-2 py-1 text-xs ${
            showMockEditor
              ? 'bg-blue-100 text-blue-700'
              : 'bg-gray-100 text-gray-600 hover:bg-gray-200'
          }`}
        >
          模拟数据
        </button>
        <button
          type="button"
          onClick={() => setShowEventLog(!showEventLog)}
          className={`rounded px-2 py-1 text-xs ${
            showEventLog
              ? 'bg-blue-100 text-blue-700'
              : 'bg-gray-100 text-gray-600 hover:bg-gray-200'
          }`}
        >
          事件日志
          {eventLogs.length > 0 && (
            <span className="ml-1 rounded bg-gray-200 px-1 text-[10px]">{eventLogs.length}</span>
          )}
        </button>
        <button
          type="button"
          onClick={refreshPreview}
          className="rounded p-1.5 text-gray-500 hover:bg-gray-100 hover:text-gray-700"
          title="刷新预览"
        >
          <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={2}
              d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15"
            />
          </svg>
        </button>
        {state.mode !== 'fullscreen' && onClose && (
          <button
            type="button"
            onClick={onClose}
            className="rounded p-1.5 text-gray-500 hover:bg-gray-100 hover:text-gray-700"
            title="关闭预览"
          >
            <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M6 18L18 6M6 6l12 12"
              />
            </svg>
          </button>
        )}
        <button
          type="button"
          onClick={() => handleModeChange(state.mode === 'fullscreen' ? 'panel' : 'fullscreen')}
          className="rounded p-1.5 text-gray-500 hover:bg-gray-100 hover:text-gray-700"
          title={state.mode === 'fullscreen' ? '退出全屏' : '全屏预览'}
        >
          <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            {state.mode === 'fullscreen' ? (
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M4 8V4m0 0h4M4 4l5 5m11-1V4m0 0h-4m4 0l-5 5M4 16v4m0 0h4m-4 0l5-5m11 5l-5-5m5 5v-4m0 4h-4"
              />
            ) : (
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M4 8V4m0 0h4M4 4l5 5m11-1V4m0 0h-4m4 0l-5 5M4 16v4m0 0h4m-4 0l5-5m11 5l-5-5m5 5v-4m0 4h-4"
              />
            )}
          </svg>
        </button>
      </div>
    </div>
  );

  const renderMockEditor = () => (
    <div className="flex w-72 flex-col border-r border-gray-200 bg-white">
      <div className="flex items-center justify-between border-b border-gray-100 px-3 py-2">
        <span className="text-sm font-medium text-gray-700">模拟数据</span>
        <div className="flex gap-1">
          <button
            type="button"
            onClick={generateMockData}
            className="px-2 py-0.5 text-xs text-blue-600 hover:text-blue-700"
          >
            自动生成
          </button>
          <button
            type="button"
            onClick={clearMockData}
            className="px-2 py-0.5 text-xs text-gray-500 hover:text-gray-700"
          >
            清空
          </button>
        </div>
      </div>
      <div className="flex-1 space-y-2 overflow-auto p-3">
        {viewModelFields.length === 0 ? (
          <div className="py-4 text-center text-xs text-gray-400">没有可用字段</div>
        ) : (
          viewModelFields.map((field) => (
            <div key={field.path} className="space-y-1">
              <label className="text-xs text-gray-600">{field.label}</label>
              <input
                type={
                  field.type.toLowerCase() === 'number' || field.type.toLowerCase() === 'integer'
                    ? 'number'
                    : field.type.toLowerCase() === 'boolean'
                      ? 'checkbox'
                      : 'text'
                }
                value={
                  field.type.toLowerCase() === 'boolean'
                    ? undefined
                    : (state.mockData[field.path] as string) || ''
                }
                checked={
                  field.type.toLowerCase() === 'boolean'
                    ? Boolean(state.mockData[field.path])
                    : undefined
                }
                onChange={(e) =>
                  handleMockDataChange(
                    field.path,
                    field.type.toLowerCase() === 'boolean'
                      ? e.target.checked
                      : field.type.toLowerCase() === 'number' ||
                          field.type.toLowerCase() === 'integer'
                        ? parseFloat(e.target.value)
                        : e.target.value,
                  )
                }
                className={
                  field.type.toLowerCase() === 'boolean'
                    ? 'h-4 w-4 rounded border-gray-300 text-blue-600'
                    : 'w-full rounded border border-gray-200 px-2 py-1 text-xs focus:ring-1 focus:ring-blue-500 focus:outline-none'
                }
                placeholder={field.type}
              />
            </div>
          ))
        )}
      </div>
    </div>
  );

  const renderEventLog = () => (
    <div className="flex w-72 flex-col border-l border-gray-200 bg-white">
      <div className="flex items-center justify-between border-b border-gray-100 px-3 py-2">
        <span className="text-sm font-medium text-gray-700">事件日志</span>
        <button
          type="button"
          onClick={() => setEventLogs([])}
          className="px-2 py-0.5 text-xs text-gray-500 hover:text-gray-700"
        >
          清空
        </button>
      </div>
      <div className="flex-1 overflow-auto">
        {eventLogs.length === 0 ? (
          <div className="py-4 text-center text-xs text-gray-400">暂无事件</div>
        ) : (
          <div className="divide-y divide-gray-100">
            {eventLogs.map((log) => (
              <div key={log.id} className="px-3 py-2">
                <div className="flex items-center justify-between">
                  <span
                    className={`rounded px-1.5 py-0.5 text-xs ${
                      log.type === 'action'
                        ? 'bg-blue-100 text-blue-700'
                        : log.type === 'binding'
                          ? 'bg-green-100 text-green-700'
                          : log.type === 'validation'
                            ? 'bg-yellow-100 text-yellow-700'
                            : 'bg-gray-100 text-gray-700'
                    }`}
                  >
                    {log.type}
                  </span>
                  <span className="text-[10px] text-gray-400">
                    {new Date(log.timestamp).toLocaleTimeString()}
                  </span>
                </div>
                <div className="mt-1 text-xs text-gray-700">{log.name}</div>
                {log.data != null && (
                  <pre className="mt-1 overflow-hidden text-[10px] text-ellipsis text-gray-500">
                    {JSON.stringify(log.data, null, 2).slice(0, 100)}
                  </pre>
                )}
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );

  const renderPreviewContent = () => (
    <div
      className="flex flex-1 items-center justify-center overflow-auto bg-gray-100 p-4"
      style={{
        backgroundImage: `
          linear-gradient(45deg, #f0f0f0 25%, transparent 25%),
          linear-gradient(-45deg, #f0f0f0 25%, transparent 25%),
          linear-gradient(45deg, transparent 75%, #f0f0f0 75%),
          linear-gradient(-45deg, transparent 75%, #f0f0f0 75%)
        `,
        backgroundSize: '20px 20px',
        backgroundPosition: '0 0, 0 10px, 10px -10px, -10px 0px',
      }}
    >
      <div
        className="overflow-hidden rounded-lg bg-white shadow-lg"
        style={{
          width: viewport.dimensions.width * viewport.dimensions.scale,
          height: viewport.dimensions.height * viewport.dimensions.scale,
          transform: `translate(${viewport.state.panX}px, ${viewport.state.panY}px)`,
        }}
      >
        {/* Preview frame */}
        <div
          className={`h-full w-full ${state.showOutlines ? 'preview-outlines' : ''}`}
          style={{
            transform: `scale(${viewport.dimensions.scale})`,
            transformOrigin: 'top left',
            width: viewport.dimensions.width,
            height: viewport.dimensions.height,
          }}
        >
          {/* Placeholder for actual page render */}
          <div className="flex h-full w-full items-center justify-center text-gray-400">
            <div className="text-center">
              <svg
                className="mx-auto mb-2 h-12 w-12 text-gray-300"
                fill="none"
                stroke="currentColor"
                viewBox="0 0 24 24"
              >
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={1.5}
                  d="M9.75 17L9 20l-1 1h8l-1-1-.75-3M3 13h18M5 17h14a2 2 0 002-2V5a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z"
                />
              </svg>
              <div className="text-sm">页面预览区域</div>
              <div className="mt-1 text-xs">
                {viewport.dimensions.width} × {viewport.dimensions.height}
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  );

  // Fullscreen mode
  if (state.mode === 'fullscreen') {
    return (
      <div className="fixed inset-0 z-50 flex flex-col bg-white">
        {renderToolbar()}
        <div className="flex flex-1 overflow-hidden">
          {showMockEditor && renderMockEditor()}
          {renderPreviewContent()}
          {showEventLog && renderEventLog()}
        </div>
      </div>
    );
  }

  // Panel mode
  return (
    <div className="flex h-full flex-col overflow-hidden rounded-lg border border-gray-200 bg-white">
      {renderToolbar()}
      <div className="flex flex-1 overflow-hidden">
        {showMockEditor && renderMockEditor()}
        {renderPreviewContent()}
        {showEventLog && renderEventLog()}
      </div>
    </div>
  );
};

export default PreviewPanel;
