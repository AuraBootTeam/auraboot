/**
 * Data Source Configuration Panel
 * Allows users to configure data source for dashboard widgets.
 * Uses shared ModelPicker, NamedQueryPicker, FieldSelector, and FilterBuilder.
 */

import React, { useCallback } from 'react';
import type {
  ChartDataSource,
  MetricConfig as ChartMetricConfig,
  FilterConfig,
} from '~/framework/smart/types/chart';
import {
  ModelPicker,
  NamedQueryPicker,
  FieldSelector,
  FilterBuilder,
  MetricEditor,
  SortEditor,
  TimeGrainPicker,
  SemanticMetricPicker,
  SemanticDimensionPicker,
  isDateField,
  parseGrainDimension,
  useModelFields,
  useSemanticModels,
  type FilterCondition,
  type MetricConfig,
  type SortOption,
} from '~/shared/designer/datasource';
import type { SortCondition } from '~/shared/designer/datasource';

interface DataSourceConfigProps {
  value: ChartDataSource;
  onChange: (value: ChartDataSource) => void;
}

export const DataSourceConfig: React.FC<DataSourceConfigProps> = ({ value, onChange }) => {
  const { fields } = useModelFields(value.type === 'aggregate' ? value.modelCode : undefined);
  const { models: semanticModels } = useSemanticModels();

  // Semantic mode is engaged when the config carries a semanticModelCode key
  // (even an empty string — "semantic mode, model not yet chosen"). Raw mode
  // and semantic mode are mutually exclusive (switch-style, PRD 16 W4 D4).
  const isSemantic = value.semanticModelCode !== undefined;

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

  // ---- G1: time bucketing --------------------------------------------------
  // A bucketed dimension lives in `dimensions` as `field__grain`. The grain picker
  // owns at most one; plain dimensions come from the FieldSelector alongside it.
  const dateFields = fields.filter(isDateField);
  const bucketedDim = (value.dimensions || []).find((d) => d.includes('__'));
  const parsedGrain = bucketedDim
    ? parseGrainDimension(bucketedDim)
    : { field: '', grain: '' };

  const handleGrainChange = useCallback(
    (field: string, grain: string) => {
      const plain = (value.dimensions || []).filter((d) => !d.includes('__'));
      const next = field ? [...plain, `${field}__${grain}`] : plain;
      onChange({ ...value, dimensions: next });
    },
    [value, onChange],
  );

  // ---- G2: ordering --------------------------------------------------------
  // Sort keys are the columns the query returns: its dimensions and its metric
  // aliases. Ordering by a metric alias is what turns a `limit` into a real top-N.
  const sortOptions: SortOption[] = [
    ...(value.dimensions || []).map((d) => ({ value: d, label: d })),
    ...(value.metrics || []).map((m) => {
      const alias = m.alias || `${m.field}_${m.aggregation}`;
      return { value: alias, label: `${alias} (指标)` };
    }),
  ];
  const sortValue: SortCondition[] = (value.orderBy || []).map((o) => ({
    field: o.field,
    order: o.direction,
  }));

  const handleSortChange = useCallback(
    (sorts: SortCondition[]) => {
      onChange({
        ...value,
        orderBy: sorts.length
          ? sorts.map((s) => ({ field: s.field, direction: s.order }))
          : undefined,
      });
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

  // -- semantic mode handlers ------------------------------------------------

  const handleModeChange = useCallback(
    (mode: 'raw' | 'semantic') => {
      if (mode === 'semantic') {
        // Enter semantic mode: drop the raw model selection, start with an
        // empty semanticModelCode so the picker prompts for a model.
        onChange({
          ...value,
          modelCode: undefined,
          semanticModelCode: '',
          dimensions: [],
          metrics: [],
        });
      } else {
        // Back to raw mode: remove semanticModelCode entirely.
        const { semanticModelCode: _omit, ...rest } = value;
        onChange({ ...rest, dimensions: [], metrics: [{ field: 'id', aggregation: 'count' }] });
      }
    },
    [value, onChange],
  );

  const handleSemanticModelChange = useCallback(
    (semanticModelCode: string) => {
      // Switching model invalidates previously picked metrics/dimensions.
      onChange({ ...value, semanticModelCode, dimensions: [], metrics: [] });
    },
    [value, onChange],
  );

  const handleSemanticMetricsChange = useCallback(
    (codes: string[]) => {
      // The semantic compiler resolves the code; aggregation is baked into the
      // metric definition, so a placeholder keeps the {field, aggregation} shape.
      onChange({
        ...value,
        // Semantic metrics carry no raw aggregation (it lives in the *.semantic.yml
        // definition); the backend ignores it on the semantic path. The placeholder
        // keeps the {field, aggregation} shape, hence the cast through unknown.
        metrics: codes.map((code) => ({
          field: code,
          aggregation: 'none',
        })) as unknown as ChartMetricConfig[],
      });
    },
    [value, onChange],
  );

  const semanticMetricCodes = (value.metrics || []).map((m) => m.field);

  return (
    <div className="space-y-4">
      {/* Data Source Type */}
      <div>
        <label className="mb-1 block text-sm font-medium text-gray-700">
          数据源类型 <span className="text-red-500">*</span>
        </label>
        <select
          data-testid="dashboard-datasource-type-select"
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
          {/* Raw model vs. governed semantic layer (switch-style) */}
          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700">数据源模式</label>
            <div className="inline-flex rounded-md border border-gray-300 p-0.5 text-sm">
              <button
                type="button"
                data-testid="datasource-mode-raw"
                onClick={() => handleModeChange('raw')}
                className={`rounded px-3 py-1 ${
                  !isSemantic ? 'bg-blue-600 text-white' : 'text-gray-600'
                }`}
              >
                原始模型
              </button>
              <button
                type="button"
                data-testid="datasource-mode-semantic"
                onClick={() => handleModeChange('semantic')}
                className={`rounded px-3 py-1 ${
                  isSemantic ? 'bg-blue-600 text-white' : 'text-gray-600'
                }`}
              >
                语义模型
              </button>
            </div>
          </div>

          {!isSemantic && (
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

              {value.modelCode && dateFields.length > 0 && (
                <TimeGrainPicker
                  dateFields={dateFields}
                  field={parsedGrain.field}
                  grain={parsedGrain.grain || 'month'}
                  onChange={handleGrainChange}
                  label="时间分桶"
                />
              )}

              {value.modelCode && (
                <MetricEditor
                  metrics={value.metrics || []}
                  onChange={handleMetricsChange}
                  modelCode={value.modelCode}
                  label="聚合指标"
                  required
                />
              )}

              {value.modelCode && sortOptions.length > 0 && (
                <SortEditor
                  value={sortValue}
                  onChange={handleSortChange}
                  options={sortOptions}
                  label="排序"
                />
              )}
            </>
          )}

          {isSemantic && (
            <>
              <div>
                <label className="mb-1 block text-sm font-medium text-gray-700">
                  语义模型 <span className="text-red-500">*</span>
                </label>
                <select
                  data-testid="semantic-model-select"
                  value={value.semanticModelCode || ''}
                  onChange={(e) => handleSemanticModelChange(e.target.value)}
                  className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
                >
                  <option value="">请选择语义模型</option>
                  {semanticModels.map((m) => (
                    <option key={m.code} value={m.code}>
                      {m.name}
                    </option>
                  ))}
                </select>
              </div>

              {value.semanticModelCode && (
                <SemanticDimensionPicker
                  semanticModelCode={value.semanticModelCode}
                  value={value.dimensions || []}
                  onChange={handleDimensionsChange}
                  label="语义维度"
                />
              )}

              {value.semanticModelCode && (
                <SemanticMetricPicker
                  semanticModelCode={value.semanticModelCode}
                  value={semanticMetricCodes}
                  onChange={handleSemanticMetricsChange}
                  label="语义指标"
                  required
                />
              )}
            </>
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
            data-testid="dashboard-datasource-static-json"
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
