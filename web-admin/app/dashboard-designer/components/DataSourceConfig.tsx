/**
 * Data Source Configuration Panel
 * Allows users to configure data source for dashboard widgets.
 * Uses shared ModelPicker, NamedQueryPicker, FieldSelector, and FilterBuilder.
 */

import React, { useCallback } from 'react';
import type { ChartDataSource, MetricConfig as ChartMetricConfig, FilterConfig } from '~/smart/types/chart';
import {
  ModelPicker,
  NamedQueryPicker,
  FieldSelector,
  FilterBuilder,
  MetricEditor,
  useModelFields,
} from '~/shared/designer/datasource';
import type { FilterCondition, MetricConfig } from '~/shared/designer/datasource';

interface DataSourceConfigProps {
  value: ChartDataSource;
  onChange: (value: ChartDataSource) => void;
}

export const DataSourceConfig: React.FC<DataSourceConfigProps> = ({ value, onChange }) => {
  const { fields } = useModelFields(value.type === 'aggregate' ? value.modelCode : undefined);

  const handleTypeChange = useCallback(
    (type: 'aggregate' | 'namedQuery' | 'static') => {
      onChange({
        type,
        modelCode: type === 'aggregate' ? value.modelCode : undefined,
        queryCode: type === 'namedQuery' ? value.queryCode : undefined,
        dimensions: type === 'static' ? undefined : value.dimensions,
        metrics: type === 'static' ? undefined : value.metrics,
        filters: value.filters,
        parameters: type === 'namedQuery' ? {} : undefined,
        staticData: type === 'static' ? [] : undefined,
      });
    },
    [value, onChange],
  );

  const handleModelChange = useCallback(
    (modelCode: string) => {
      onChange({
        ...value,
        modelCode,
        dimensions: [],
        metrics: [{ field: 'id', aggregation: 'count' }],
      });
    },
    [value, onChange],
  );

  const handleDimensionsChange = useCallback(
    (dimensions: string[]) => {
      onChange({ ...value, dimensions });
    },
    [value, onChange],
  );

  const handleMetricsChange = useCallback(
    (metrics: MetricConfig[]) => {
      onChange({ ...value, metrics: metrics as ChartMetricConfig[] });
    },
    [value, onChange],
  );

  const handleFiltersChange = useCallback(
    (filters: FilterCondition[]) => {
      onChange({ ...value, filters: filters as FilterConfig[] });
    },
    [value, onChange],
  );

  return (
    <div className="space-y-4">
      {/* Data Source Type */}
      <div>
        <label className="mb-1 block text-sm font-medium text-gray-700">
          数据源类型 <span className="text-red-500">*</span>
        </label>
        <select
          value={value.type}
          onChange={(e) =>
            handleTypeChange(e.target.value as 'aggregate' | 'namedQuery' | 'static')
          }
          className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
        >
          <option value="aggregate">聚合查询</option>
          <option value="namedQuery">命名查询</option>
          <option value="static">静态数据</option>
        </select>
      </div>

      {/* Aggregate Query Config */}
      {value.type === 'aggregate' && (
        <>
          <ModelPicker
            value={value.modelCode}
            onChange={handleModelChange}
            label="数据模型"
            required
            placeholder="请选择模型"
          />

          {value.modelCode && (
            <FieldSelector
              modelCode={value.modelCode}
              value={value.dimensions || []}
              onChange={handleDimensionsChange}
              label="分组维度"
              placeholder="选择分组字段"
            />
          )}

          {/* Metrics */}
          {value.modelCode && (
            <MetricEditor
              metrics={value.metrics || []}
              onChange={handleMetricsChange}
              modelCode={value.modelCode}
              label="聚合指标"
              required
            />
          )}
        </>
      )}

      {/* Named Query Config */}
      {value.type === 'namedQuery' && (
        <NamedQueryPicker
          value={value.queryCode}
          onChange={(queryCode) => onChange({ ...value, queryCode, parameters: {} })}
          label="命名查询"
          required
          placeholder="请选择查询"
        />
      )}

      {/* Static Data Config */}
      {value.type === 'static' && (
        <div>
          <label className="mb-1 block text-sm font-medium text-gray-700">静态数据 (JSON)</label>
          <textarea
            value={JSON.stringify(value.staticData || [], null, 2)}
            onChange={(e) => {
              try {
                const data = JSON.parse(e.target.value);
                onChange({ ...value, staticData: data });
              } catch {
                // Keep current value if JSON is invalid
              }
            }}
            className="h-32 w-full rounded-md border border-gray-300 px-3 py-2 font-mono text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
            placeholder='[{"name": "A", "value": 100}]'
          />
        </div>
      )}

      {/* Filters */}
      <FilterBuilder
        value={(value.filters || []) as FilterCondition[]}
        onChange={handleFiltersChange}
        fields={value.type === 'aggregate' ? fields : undefined}
        label="筛选条件"
      />

      {/* Limit */}
      <div>
        <label className="mb-1 block text-sm font-medium text-gray-700">返回行数限制</label>
        <input
          type="number"
          value={value.limit || ''}
          onChange={(e) =>
            onChange({ ...value, limit: e.target.value ? parseInt(e.target.value, 10) : undefined })
          }
          placeholder="不限制"
          min={1}
          max={10000}
          className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
        />
      </div>
    </div>
  );
};

export default DataSourceConfig;
