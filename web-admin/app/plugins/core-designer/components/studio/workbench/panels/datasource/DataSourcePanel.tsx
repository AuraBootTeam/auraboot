/**
 * DataSource Configuration Panel
 *
 * Main panel for configuring component data sources.
 * Supports API, Static, and Expression data source types.
 *
 * @since 3.2.0
 */

import React, { useState, useCallback } from 'react';
import type {
  DataSourceConfig,
  DataSourceType,
  DataSourcePanelProps,
  ApiDataSourceConfig,
  StaticDataSourceConfig,
  ExpressionDataSourceConfig,
} from './types';
import { ApiDataSourceEditor } from './editors/ApiDataSourceEditor';
import { StaticDataSourceEditor } from './editors/StaticDataSourceEditor';
import { ExpressionDataSourceEditor } from './editors/ExpressionDataSourceEditor';
import { DataSourceTester } from './DataSourceTester';

/**
 * Data source type options
 */
const DATA_SOURCE_TYPES: {
  value: DataSourceType;
  label: string;
  icon: string;
  description: string;
}[] = [
  {
    value: 'api',
    label: 'API 接口',
    icon: '🌐',
    description: '从后端 API 获取数据',
  },
  {
    value: 'static',
    label: '静态数据',
    icon: '📋',
    description: '使用固定的数据列表',
  },
  {
    value: 'expression',
    label: '表达式',
    icon: 'fx',
    description: '通过表达式计算数据',
  },
];

/**
 * Create default config for each type
 */
function createDefaultConfig(type: DataSourceType): DataSourceConfig {
  const base: DataSourceConfig = {
    id: `ds_${Date.now()}`,
    type,
    mapping: {
      valueField: 'value',
      labelField: 'label',
    },
  };

  switch (type) {
    case 'api':
      return {
        ...base,
        api: {
          endpoint: '/api/',
          method: 'get',
          params: {},
        },
        cache: {
          enabled: true,
          ttl: 300000, // 5 minutes
        },
      };
    case 'static':
      return {
        ...base,
        static: {
          data: [
            { value: 'option1', label: '选项 1' },
            { value: 'option2', label: '选项 2' },
          ],
        },
      };
    case 'expression':
      return {
        ...base,
        expression: {
          expression: '{{ [] }}',
          dependencies: [],
        },
      };
    default:
      return base;
  }
}

/**
 * DataSource Configuration Panel
 */
export const DataSourcePanel: React.FC<DataSourcePanelProps> = ({
  value,
  onChange,
  context,
  title = '数据源配置',
}) => {
  const [showTester, setShowTester] = useState(false);

  // Initialize with default config if not provided
  const config = value || createDefaultConfig('static');

  // Handle type change
  const handleTypeChange = useCallback(
    (newType: DataSourceType) => {
      if (newType === config.type) return;
      const newConfig = createDefaultConfig(newType);
      // Preserve mapping if exists
      if (config.mapping) {
        newConfig.mapping = config.mapping;
      }
      onChange(newConfig);
    },
    [config, onChange],
  );

  // Handle API config change
  const handleApiChange = useCallback(
    (apiConfig: ApiDataSourceConfig) => {
      onChange({
        ...config,
        api: apiConfig,
      });
    },
    [config, onChange],
  );

  // Handle static config change
  const handleStaticChange = useCallback(
    (staticConfig: StaticDataSourceConfig) => {
      onChange({
        ...config,
        static: staticConfig,
      });
    },
    [config, onChange],
  );

  // Handle expression config change
  const handleExpressionChange = useCallback(
    (expressionConfig: ExpressionDataSourceConfig) => {
      onChange({
        ...config,
        expression: expressionConfig,
      });
    },
    [config, onChange],
  );

  // Handle mapping change
  const handleMappingChange = useCallback(
    (field: 'valueField' | 'labelField', value: string) => {
      onChange({
        ...config,
        mapping: {
          ...config.mapping,
          [field]: value,
        },
      });
    },
    [config, onChange],
  );

  // Handle cache change
  const handleCacheChange = useCallback(
    (enabled: boolean, ttl?: number) => {
      onChange({
        ...config,
        cache: {
          enabled,
          ttl: ttl ?? config.cache?.ttl ?? 300000,
        },
      });
    },
    [config, onChange],
  );

  return (
    <div className="datasource-panel space-y-4">
      {/* Header */}
      <div className="flex items-center justify-between">
        <h3 className="text-sm font-medium text-gray-900">{title}</h3>
        <button
          type="button"
          onClick={() => setShowTester(!showTester)}
          className="flex items-center gap-1 text-xs text-blue-600 hover:text-blue-700"
        >
          <svg className="h-3.5 w-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={2}
              d="M14.752 11.168l-3.197-2.132A1 1 0 0010 9.87v4.263a1 1 0 001.555.832l3.197-2.132a1 1 0 000-1.664z"
            />
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={2}
              d="M21 12a9 9 0 11-18 0 9 9 0 0118 0z"
            />
          </svg>
          测试
        </button>
      </div>

      {/* Type Selector */}
      <div className="space-y-2">
        <label className="text-xs font-medium text-gray-700">数据源类型</label>
        <div className="grid grid-cols-3 gap-2">
          {DATA_SOURCE_TYPES.map((type) => (
            <button
              key={type.value}
              type="button"
              onClick={() => handleTypeChange(type.value)}
              className={`rounded-lg border p-2 text-center transition-all ${
                config.type === type.value
                  ? 'border-blue-500 bg-blue-50 ring-1 ring-blue-200'
                  : 'border-gray-200 hover:border-gray-300 hover:bg-gray-50'
              } `}
            >
              <div className={`text-lg ${type.value === 'expression' ? 'font-mono text-sm' : ''}`}>
                {type.icon}
              </div>
              <div className="mt-1 text-xs font-medium text-gray-700">{type.label}</div>
            </button>
          ))}
        </div>
        <p className="text-[10px] text-gray-500">
          {DATA_SOURCE_TYPES.find((t) => t.value === config.type)?.description}
        </p>
      </div>

      {/* Type-specific Editor */}
      <div className="border-t border-gray-100 pt-4">
        {config.type === 'api' && config.api && (
          <ApiDataSourceEditor value={config.api} onChange={handleApiChange} context={context} />
        )}

        {config.type === 'static' && config.static && (
          <StaticDataSourceEditor value={config.static} onChange={handleStaticChange} />
        )}

        {config.type === 'expression' && config.expression && (
          <ExpressionDataSourceEditor
            value={config.expression}
            onChange={handleExpressionChange}
            context={context}
          />
        )}
      </div>

      {/* Mapping Configuration */}
      <div className="space-y-3 border-t border-gray-100 pt-4">
        <h4 className="text-xs font-medium text-gray-700">字段映射</h4>
        <div className="grid grid-cols-2 gap-3">
          <div>
            <label className="mb-1 block text-[10px] text-gray-500">值字段</label>
            <input
              type="text"
              value={config.mapping?.valueField || 'value'}
              onChange={(e) => handleMappingChange('valueField', e.target.value)}
              className="w-full rounded-md border border-gray-200 px-2 py-1.5 text-xs focus:ring-1 focus:ring-blue-500 focus:outline-none"
              placeholder="value"
            />
          </div>
          <div>
            <label className="mb-1 block text-[10px] text-gray-500">标签字段</label>
            <input
              type="text"
              value={config.mapping?.labelField || 'label'}
              onChange={(e) => handleMappingChange('labelField', e.target.value)}
              className="w-full rounded-md border border-gray-200 px-2 py-1.5 text-xs focus:ring-1 focus:ring-blue-500 focus:outline-none"
              placeholder="label"
            />
          </div>
        </div>
      </div>

      {/* Cache Configuration (for API type) */}
      {config.type === 'api' && (
        <div className="space-y-3 border-t border-gray-100 pt-4">
          <div className="flex items-center justify-between">
            <h4 className="text-xs font-medium text-gray-700">缓存设置</h4>
            <label className="flex cursor-pointer items-center gap-2">
              <input
                type="checkbox"
                checked={config.cache?.enabled ?? true}
                onChange={(e) => handleCacheChange(e.target.checked)}
                className="h-3.5 w-3.5 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
              />
              <span className="text-xs text-gray-600">启用缓存</span>
            </label>
          </div>
          {config.cache?.enabled && (
            <div>
              <label className="mb-1 block text-[10px] text-gray-500">缓存时间（秒）</label>
              <input
                type="number"
                value={Math.round((config.cache?.ttl ?? 300000) / 1000)}
                onChange={(e) => handleCacheChange(true, parseInt(e.target.value) * 1000)}
                className="w-24 rounded-md border border-gray-200 px-2 py-1.5 text-xs focus:ring-1 focus:ring-blue-500 focus:outline-none"
                min={0}
                step={30}
              />
            </div>
          )}
        </div>
      )}

      {/* Tester Panel */}
      {showTester && (
        <div className="border-t border-gray-100 pt-4">
          <DataSourceTester config={config} context={context} />
        </div>
      )}
    </div>
  );
};

export default DataSourcePanel;
