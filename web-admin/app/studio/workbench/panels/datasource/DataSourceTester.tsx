/**
 * DataSource Tester Component
 *
 * Tests data source configuration and displays results.
 *
 * @since 3.2.0
 */

import React, { useState, useCallback } from 'react';
import { expressionParser } from '~/studio/services/runtime/expression/expression-parser';
import type { DataSourceConfig, DataSourceTestResult } from './types';

interface DataSourceTesterProps {
  config: DataSourceConfig;
  context?: Record<string, any>;
}

/**
 * DataSource Tester Component
 */
export const DataSourceTester: React.FC<DataSourceTesterProps> = ({ config, context }) => {
  const [loading, setLoading] = useState(false);
  const [result, setResult] = useState<DataSourceTestResult | null>(null);

  // Execute test
  const handleTest = useCallback(async () => {
    setLoading(true);
    setResult(null);

    const startTime = Date.now();

    try {
      let data: any[] = [];

      switch (config.type) {
        case 'api':
          // API test
          if (!config.api?.endpoint) {
            throw new Error('API 端点未配置');
          }

          // Build URL with params
          let url = config.api.endpoint;
          if (config.api.params && Object.keys(config.api.params).length > 0) {
            const params = new URLSearchParams();
            Object.entries(config.api.params).forEach(([key, param]) => {
              const value =
                param.type === 'expression'
                  ? expressionParser.execute(param.value, context || {})
                  : param.value;
              params.append(key, String(value));
            });
            url += `?${params.toString()}`;
          }

          // Build headers
          const headers: Record<string, string> = {
            'Content-Type': 'application/json',
          };
          if (config.api.headers) {
            Object.entries(config.api.headers).forEach(([key, param]) => {
              const value =
                param.type === 'expression'
                  ? expressionParser.execute(param.value, context || {})
                  : param.value;
              headers[key] = String(value);
            });
          }

          // Make request
          const response = await fetch(url, {
            method: config.api.method,
            headers,
          });

          if (!response.ok) {
            throw new Error(`HTTP ${response.status}: ${response.statusText}`);
          }

          let responseData = await response.json();

          // Extract data from response path
          if (config.api.responsePath) {
            const paths = config.api.responsePath.split('.');
            for (const path of paths) {
              responseData = responseData?.[path];
            }
          }

          data = Array.isArray(responseData) ? responseData : [responseData];
          break;

        case 'static':
          // Static data test
          data = config.static?.data || [];
          break;

        case 'expression':
          // Expression test
          if (!config.expression?.expression) {
            throw new Error('表达式未配置');
          }

          const exprResult = expressionParser.execute(config.expression.expression, context || {});

          if (!Array.isArray(exprResult)) {
            throw new Error('表达式结果必须是数组');
          }

          data = exprResult;
          break;
      }

      // Apply mapping
      const mappedData = data.map((item) => ({
        value: item[config.mapping?.valueField || 'value'],
        label: item[config.mapping?.labelField || 'label'],
      }));

      setResult({
        success: true,
        data,
        mappedData,
        duration: Date.now() - startTime,
      });
    } catch (error) {
      setResult({
        success: false,
        error: error instanceof Error ? error.message : '未知错误',
        duration: Date.now() - startTime,
      });
    } finally {
      setLoading(false);
    }
  }, [config, context]);

  return (
    <div className="space-y-3">
      {/* Header */}
      <div className="flex items-center justify-between">
        <h4 className="text-xs font-medium text-gray-700">测试数据源</h4>
        <button
          type="button"
          onClick={handleTest}
          disabled={loading}
          className={`rounded-md px-3 py-1 text-xs transition-colors ${
            loading
              ? 'cursor-wait bg-gray-100 text-gray-400'
              : 'bg-blue-600 text-white hover:bg-blue-700'
          }`}
        >
          {loading ? (
            <span className="flex items-center gap-1">
              <svg className="h-3 w-3 animate-spin" fill="none" viewBox="0 0 24 24">
                <circle
                  className="opacity-25"
                  cx="12"
                  cy="12"
                  r="10"
                  stroke="currentColor"
                  strokeWidth="4"
                />
                <path
                  className="opacity-75"
                  fill="currentColor"
                  d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"
                />
              </svg>
              测试中...
            </span>
          ) : (
            '执行测试'
          )}
        </button>
      </div>

      {/* Result */}
      {result && (
        <div
          className={`rounded-md border p-3 ${
            result.success ? 'border-green-200 bg-green-50' : 'border-red-200 bg-red-50'
          }`}
        >
          {/* Status */}
          <div className="mb-2 flex items-center justify-between">
            <div className="flex items-center gap-2">
              {result.success ? (
                <svg className="h-4 w-4 text-green-500" fill="currentColor" viewBox="0 0 20 20">
                  <path
                    fillRule="evenodd"
                    d="M10 18a8 8 0 100-16 8 8 0 000 16zm3.707-9.293a1 1 0 00-1.414-1.414L9 10.586 7.707 9.293a1 1 0 00-1.414 1.414l2 2a1 1 0 001.414 0l4-4z"
                    clipRule="evenodd"
                  />
                </svg>
              ) : (
                <svg className="h-4 w-4 text-red-500" fill="currentColor" viewBox="0 0 20 20">
                  <path
                    fillRule="evenodd"
                    d="M10 18a8 8 0 100-16 8 8 0 000 16zM8.707 7.293a1 1 0 00-1.414 1.414L8.586 10l-1.293 1.293a1 1 0 101.414 1.414L10 11.414l1.293 1.293a1 1 0 001.414-1.414L11.414 10l1.293-1.293a1 1 0 00-1.414-1.414L10 8.586 8.707 7.293z"
                    clipRule="evenodd"
                  />
                </svg>
              )}
              <span
                className={`text-xs font-medium ${result.success ? 'text-green-700' : 'text-red-700'}`}
              >
                {result.success ? '成功' : '失败'}
              </span>
            </div>
            <span className="text-[10px] text-gray-500">{result.duration}ms</span>
          </div>

          {/* Error */}
          {result.error && <p className="mb-2 text-xs text-red-600">{result.error}</p>}

          {/* Data Preview */}
          {result.success && result.mappedData && (
            <div>
              <div className="mb-1 text-[10px] text-gray-500">
                返回 {result.mappedData.length} 条数据
              </div>
              <div className="max-h-40 overflow-auto rounded border border-gray-200 bg-white">
                <table className="w-full text-[11px]">
                  <thead className="bg-gray-50">
                    <tr>
                      <th className="px-2 py-1 text-left font-medium text-gray-600">Value</th>
                      <th className="px-2 py-1 text-left font-medium text-gray-600">Label</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-gray-100">
                    {result.mappedData.slice(0, 10).map((item, index) => (
                      <tr key={index}>
                        <td className="px-2 py-1 font-mono text-gray-700">{String(item.value)}</td>
                        <td className="px-2 py-1 text-gray-600">{String(item.label)}</td>
                      </tr>
                    ))}
                    {result.mappedData.length > 10 && (
                      <tr>
                        <td colSpan={2} className="px-2 py-1 text-center text-gray-400">
                          ... 还有 {result.mappedData.length - 10} 条
                        </td>
                      </tr>
                    )}
                  </tbody>
                </table>
              </div>
            </div>
          )}
        </div>
      )}

      {/* Help */}
      {!result && (
        <p className="py-2 text-center text-[10px] text-gray-500">点击"执行测试"验证数据源配置</p>
      )}
    </div>
  );
};

export default DataSourceTester;
