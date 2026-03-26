/**
 * Refresh Configuration Component
 * Configure auto-refresh interval for chart data
 */

import React from 'react';

/** Predefined refresh intervals (in seconds) */
const REFRESH_INTERVALS = [
  { value: 0, label: '手动刷新' },
  { value: 10, label: '10 秒' },
  { value: 30, label: '30 秒' },
  { value: 60, label: '1 分钟' },
  { value: 300, label: '5 分钟' },
  { value: 600, label: '10 分钟' },
  { value: 1800, label: '30 分钟' },
  { value: 3600, label: '1 小时' },
];

interface RefreshConfigProps {
  /** Current refresh interval in seconds (0 = manual refresh) */
  value: number;
  /** Callback when interval changes */
  onChange: (interval: number) => void;
  /** Optional: Callback to trigger manual refresh */
  onRefresh?: () => void;
}

export const RefreshConfig: React.FC<RefreshConfigProps> = ({ value, onChange, onRefresh }) => {
  const currentInterval = REFRESH_INTERVALS.find((i) => i.value === value) ? value : 0;

  return (
    <div className="space-y-3">
      {/* Refresh Interval Selector */}
      <div>
        <label className="mb-1 block text-sm font-medium text-gray-700">自动刷新间隔</label>
        <select
          value={currentInterval}
          onChange={(e) => onChange(Number(e.target.value))}
          className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
        >
          {REFRESH_INTERVALS.map((interval) => (
            <option key={interval.value} value={interval.value}>
              {interval.label}
            </option>
          ))}
        </select>
        <p className="mt-1 text-xs text-gray-500">
          {currentInterval > 0
            ? `数据将每 ${REFRESH_INTERVALS.find((i) => i.value === currentInterval)?.label} 自动刷新`
            : '需要手动刷新数据'}
        </p>
      </div>

      {/* Manual Refresh Button */}
      {onRefresh && (
        <button
          type="button"
          onClick={onRefresh}
          className="flex w-full items-center justify-center gap-2 rounded-md border border-blue-200 bg-blue-50 px-4 py-2 text-sm font-medium text-blue-600 transition-colors hover:bg-blue-100"
        >
          <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={2}
              d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15"
            />
          </svg>
          立即刷新
        </button>
      )}

      {/* Refresh Status Indicator */}
      {currentInterval > 0 && (
        <div className="flex items-center gap-2 text-xs text-gray-500">
          <span className="relative flex h-2 w-2">
            <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-green-400 opacity-75" />
            <span className="relative inline-flex h-2 w-2 rounded-full bg-green-500" />
          </span>
          自动刷新已启用
        </div>
      )}
    </div>
  );
};

export default RefreshConfig;
