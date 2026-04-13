/**
 * SmartMapChart Component
 *
 * Placeholder map component for dashboard.
 * Actual ECharts map requires geo JSON which is too large for bundling.
 * Provides a proper props structure for future implementation.
 */

import React from 'react';
import type { ChartDataSource, FilterConfig, LinkageConfig } from '~/framework/smart/types/chart';
import { cn } from '~/utils/cn';

/**
 * Props for SmartMapChart component
 */
export interface SmartMapChartProps {
  /** Chart title */
  title?: string;
  /** Data source configuration */
  dataSource: ChartDataSource;
  /** Map region (e.g., 'china', 'world') */
  mapRegion?: string;
  /** Field for region names */
  regionField?: string;
  /** Field for values */
  valueField?: string;
  /** Linkage configuration */
  linkage?: LinkageConfig;
  /** Linkage filters from other charts */
  linkageFilters?: FilterConfig[];
  /** Auto-refresh interval in milliseconds (0 = disabled) */
  refreshInterval?: number;
  /** Custom CSS class */
  className?: string;
  /** Custom inline styles */
  style?: React.CSSProperties;
}

export const SmartMapChart: React.FC<SmartMapChartProps> = ({ title, className, style }) => {
  return (
    <div
      className={cn(
        'flex h-full flex-col items-center justify-center rounded-lg border border-gray-200 bg-white p-4',
        className,
      )}
      style={{ minHeight: 0, ...style }}
    >
      <div className="text-center">
        <svg
          className="mx-auto mb-4 h-16 w-16 text-gray-300"
          fill="none"
          stroke="currentColor"
          viewBox="0 0 24 24"
        >
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth={1.5}
            d="M9 6.75V15m6-6v8.25m.503 3.498l4.875-2.437c.381-.19.622-.58.622-1.006V4.82c0-.836-.88-1.38-1.628-1.006l-3.869 1.934c-.317.159-.69.159-1.006 0L9.503 3.252a1.125 1.125 0 00-1.006 0L3.622 5.689C3.24 5.88 3 6.27 3 6.695V19.18c0 .836.88 1.38 1.628 1.006l3.869-1.934c.317-.159.69-.159 1.006 0l4.994 2.497c.317.158.69.158 1.006 0z"
          />
        </svg>
        <div className="mb-2 text-base font-medium text-gray-500">{title || '地图可视化'}</div>
        <div className="max-w-xs text-sm text-gray-400">
          地图可视化需要加载地理数据配置，请联系管理员启用此功能
        </div>
      </div>
    </div>
  );
};

export default SmartMapChart;
