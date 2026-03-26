/**
 * API DataSource Editor
 *
 * Editor for configuring API-based data sources.
 *
 * @since 3.2.0
 */

import React, { useCallback, useState } from 'react';
import type { ApiDataSourceConfig, HttpMethod, ParamValue, DataSourceEditorProps } from '../types';

const HTTP_METHODS: HttpMethod[] = ['get', 'post', 'put', 'delete'];

/**
 * API DataSource Editor Component
 */
export const ApiDataSourceEditor: React.FC<DataSourceEditorProps<ApiDataSourceConfig>> = ({
  value,
  onChange,
  context,
}) => {
  const [showAdvanced, setShowAdvanced] = useState(false);

  // Handle endpoint change
  const handleEndpointChange = useCallback(
    (e: React.ChangeEvent<HTMLInputElement>) => {
      onChange({
        ...value,
        endpoint: e.target.value,
      });
    },
    [value, onChange],
  );

  // Handle method change
  const handleMethodChange = useCallback(
    (method: HttpMethod) => {
      onChange({
        ...value,
        method,
      });
    },
    [value, onChange],
  );

  // Handle response path change
  const handleResponsePathChange = useCallback(
    (e: React.ChangeEvent<HTMLInputElement>) => {
      onChange({
        ...value,
        responsePath: e.target.value,
      });
    },
    [value, onChange],
  );

  // Add parameter
  const addParam = useCallback(
    (type: 'params' | 'headers') => {
      const params = { ...(value[type] || {}) };
      const newKey = `param${Object.keys(params).length + 1}`;
      params[newKey] = { type: 'static', value: '' };
      onChange({
        ...value,
        [type]: params,
      });
    },
    [value, onChange],
  );

  // Update parameter
  const updateParam = useCallback(
    (type: 'params' | 'headers', oldKey: string, newKey: string, paramValue: ParamValue) => {
      const params = { ...(value[type] || {}) };
      if (oldKey !== newKey) {
        delete params[oldKey];
      }
      params[newKey] = paramValue;
      onChange({
        ...value,
        [type]: params,
      });
    },
    [value, onChange],
  );

  // Remove parameter
  const removeParam = useCallback(
    (type: 'params' | 'headers', key: string) => {
      const params = { ...(value[type] || {}) };
      delete params[key];
      onChange({
        ...value,
        [type]: params,
      });
    },
    [value, onChange],
  );

  return (
    <div className="space-y-4">
      {/* Endpoint */}
      <div>
        <label className="mb-1 block text-xs font-medium text-gray-700">API 端点</label>
        <div className="flex gap-2">
          {/* Method Selector */}
          <select
            value={value.method}
            onChange={(e) => handleMethodChange(e.target.value as HttpMethod)}
            className="rounded-md border border-gray-200 bg-gray-50 px-2 py-1.5 text-xs focus:ring-1 focus:ring-blue-500 focus:outline-none"
          >
            {HTTP_METHODS.map((method) => (
              <option key={method} value={method}>
                {method}
              </option>
            ))}
          </select>

          {/* Endpoint Input */}
          <input
            type="text"
            value={value.endpoint}
            onChange={handleEndpointChange}
            className="flex-1 rounded-md border border-gray-200 px-2 py-1.5 font-mono text-xs focus:ring-1 focus:ring-blue-500 focus:outline-none"
            placeholder="/api/endpoint"
          />
        </div>
      </div>

      {/* Query Parameters */}
      <div>
        <div className="mb-2 flex items-center justify-between">
          <label className="text-xs font-medium text-gray-700">请求参数</label>
          <button
            type="button"
            onClick={() => addParam('params')}
            className="text-[10px] text-blue-600 hover:text-blue-700"
          >
            + 添加参数
          </button>
        </div>

        {value.params && Object.keys(value.params).length > 0 ? (
          <div className="space-y-2">
            {Object.entries(value.params).map(([key, param]) => (
              <ParamRow
                key={key}
                paramKey={key}
                param={param}
                onChange={(newKey, newParam) => updateParam('params', key, newKey, newParam)}
                onRemove={() => removeParam('params', key)}
              />
            ))}
          </div>
        ) : (
          <div className="rounded-md border border-dashed border-gray-200 py-2 text-center text-[10px] text-gray-400">
            暂无参数
          </div>
        )}
      </div>

      {/* Response Path */}
      <div>
        <label className="mb-1 block text-xs font-medium text-gray-700">响应路径</label>
        <input
          type="text"
          value={value.responsePath || ''}
          onChange={handleResponsePathChange}
          className="w-full rounded-md border border-gray-200 px-2 py-1.5 font-mono text-xs focus:ring-1 focus:ring-blue-500 focus:outline-none"
          placeholder="data.items （留空则使用整个响应）"
        />
        <p className="mt-1 text-[10px] text-gray-500">指定从响应中提取数据的路径，例如 data.list</p>
      </div>

      {/* Advanced Settings */}
      <div>
        <button
          type="button"
          onClick={() => setShowAdvanced(!showAdvanced)}
          className="flex items-center gap-1 text-xs text-gray-500 hover:text-gray-700"
        >
          <svg
            className={`h-3 w-3 transition-transform ${showAdvanced ? 'rotate-90' : ''}`}
            fill="none"
            stroke="currentColor"
            viewBox="0 0 24 24"
          >
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 5l7 7-7 7" />
          </svg>
          高级设置
        </button>

        {showAdvanced && (
          <div className="mt-3 space-y-3 border-l-2 border-gray-100 pl-4">
            {/* Headers */}
            <div>
              <div className="mb-2 flex items-center justify-between">
                <label className="text-xs font-medium text-gray-700">请求头</label>
                <button
                  type="button"
                  onClick={() => addParam('headers')}
                  className="text-[10px] text-blue-600 hover:text-blue-700"
                >
                  + 添加
                </button>
              </div>

              {value.headers && Object.keys(value.headers).length > 0 ? (
                <div className="space-y-2">
                  {Object.entries(value.headers).map(([key, param]) => (
                    <ParamRow
                      key={key}
                      paramKey={key}
                      param={param}
                      onChange={(newKey, newParam) => updateParam('headers', key, newKey, newParam)}
                      onRemove={() => removeParam('headers', key)}
                    />
                  ))}
                </div>
              ) : (
                <div className="rounded-md border border-dashed border-gray-200 py-2 text-center text-[10px] text-gray-400">
                  暂无请求头
                </div>
              )}
            </div>
          </div>
        )}
      </div>
    </div>
  );
};

/**
 * Parameter Row Component
 */
interface ParamRowProps {
  paramKey: string;
  param: ParamValue;
  onChange: (key: string, param: ParamValue) => void;
  onRemove: () => void;
}

const ParamRow: React.FC<ParamRowProps> = ({ paramKey, param, onChange, onRemove }) => {
  const [key, setKey] = useState(paramKey);

  const handleKeyBlur = useCallback(() => {
    if (key !== paramKey) {
      onChange(key, param);
    }
  }, [key, paramKey, param, onChange]);

  const handleValueChange = useCallback(
    (e: React.ChangeEvent<HTMLInputElement>) => {
      onChange(key, { ...param, value: e.target.value });
    },
    [key, param, onChange],
  );

  const handleTypeToggle = useCallback(() => {
    onChange(key, {
      ...param,
      type: param.type === 'static' ? 'expression' : 'static',
    });
  }, [key, param, onChange]);

  return (
    <div className="flex items-center gap-2">
      {/* Key */}
      <input
        type="text"
        value={key}
        onChange={(e) => setKey(e.target.value)}
        onBlur={handleKeyBlur}
        className="w-24 rounded border border-gray-200 px-2 py-1 text-[11px] focus:ring-1 focus:ring-blue-500 focus:outline-none"
        placeholder="key"
      />

      <span className="text-gray-400">=</span>

      {/* Value */}
      <div className="flex flex-1 items-center gap-1">
        <input
          type="text"
          value={param.value}
          onChange={handleValueChange}
          className={`flex-1 rounded border border-gray-200 px-2 py-1 text-[11px] focus:ring-1 focus:ring-blue-500 focus:outline-none ${
            param.type === 'expression' ? 'bg-blue-50 font-mono' : ''
          }`}
          placeholder={param.type === 'expression' ? '{{ expression }}' : 'value'}
        />

        {/* Type Toggle */}
        <button
          type="button"
          onClick={handleTypeToggle}
          className={`rounded px-1.5 py-1 font-mono text-[10px] transition-colors ${
            param.type === 'expression'
              ? 'bg-blue-100 text-blue-600'
              : 'bg-gray-100 text-gray-500 hover:bg-gray-200'
          }`}
          title={param.type === 'expression' ? '切换为静态值' : '切换为表达式'}
        >
          fx
        </button>
      </div>

      {/* Remove */}
      <button
        type="button"
        onClick={onRemove}
        className="p-1 text-gray-400 transition-colors hover:text-red-500"
      >
        <svg className="h-3.5 w-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth={2}
            d="M6 18L18 6M6 6l12 12"
          />
        </svg>
      </button>
    </div>
  );
};

export default ApiDataSourceEditor;
