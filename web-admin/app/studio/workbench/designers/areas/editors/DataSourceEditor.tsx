import React, { useState, useCallback } from 'react';

interface PageDataSourceConfig {
  type?: 'table' | 'namedQuery' | 'api';
  endpoint?: string;
  method?: 'get' | 'post';
  pagination?: boolean;
  queryCode?: string;
  [key: string]: unknown;
}

export interface DataSourceEditorProps {
  dataSource: PageDataSourceConfig;
  onChange: (ds: PageDataSourceConfig) => void;
  onTestDetect?: () => void;
  testStatus?: { connected: boolean; recordCount: number | null; error: string | null };
  readonly?: boolean;
}

export function DataSourceEditor({
  dataSource,
  onChange,
  onTestDetect,
  testStatus,
  readonly,
}: DataSourceEditorProps) {
  const [mode, setMode] = useState<'form' | 'code'>('form');
  const [codeValue, setCodeValue] = useState('');
  const [codeError, setCodeError] = useState<string | null>(null);

  const handleSwitchToCode = useCallback(() => {
    setCodeValue(JSON.stringify(dataSource, null, 2));
    setCodeError(null);
    setMode('code');
  }, [dataSource]);

  const handleSwitchToForm = useCallback(() => {
    try {
      const parsed = JSON.parse(codeValue);
      onChange(parsed);
      setCodeError(null);
      setMode('form');
    } catch {
      setCodeError('Invalid JSON — fix before switching to Form mode');
    }
  }, [codeValue, onChange]);

  const handleCodeApply = useCallback(() => {
    try {
      const parsed = JSON.parse(codeValue);
      onChange(parsed);
      setCodeError(null);
    } catch {
      setCodeError('Invalid JSON');
    }
  }, [codeValue, onChange]);

  const updateField = useCallback(
    (field: string, value: unknown) => {
      onChange({ ...dataSource, [field]: value });
    },
    [dataSource, onChange]
  );

  if (mode === 'code') {
    return (
      <div className="space-y-2" data-testid="ds-editor">
        <div className="flex items-center justify-between">
          <span className="text-xs font-medium text-gray-600">Data Source</span>
          <div className="flex overflow-hidden rounded border text-xs">
            <button
              onClick={handleSwitchToForm}
              className="bg-gray-100 px-2 py-0.5 text-gray-600"
              data-testid="ds-form-btn"
            >
              Form
            </button>
            <button className="bg-blue-500 px-2 py-0.5 text-white" data-testid="ds-code-btn">
              Code
            </button>
          </div>
        </div>
        <textarea
          className="h-48 w-full rounded border bg-gray-900 p-2 font-mono text-xs text-gray-100"
          value={codeValue}
          onChange={(e) => {
            setCodeValue(e.target.value);
            setCodeError(null);
          }}
          disabled={readonly}
          spellCheck={false}
        />
        {codeError && <div className="text-xs text-red-500">{codeError}</div>}
        <button
          onClick={handleCodeApply}
          className="rounded bg-blue-500 px-3 py-1 text-xs text-white"
          disabled={readonly}
        >
          Apply
        </button>
      </div>
    );
  }

  return (
    <div className="space-y-3" data-testid="ds-editor">
      {/* Header with mode toggle */}
      <div className="flex items-center justify-between">
        <span className="text-xs font-medium text-gray-600">Data Source</span>
        <div className="flex overflow-hidden rounded border text-xs">
          <button className="bg-blue-500 px-2 py-0.5 text-white" data-testid="ds-form-btn">
            Form
          </button>
          <button
            onClick={handleSwitchToCode}
            className="bg-gray-100 px-2 py-0.5 text-gray-600"
            data-testid="ds-code-btn"
          >
            Code
          </button>
        </div>
      </div>

      {/* Endpoint */}
      <div>
        <label className="mb-1 block text-[10px] font-semibold text-gray-500">API Endpoint</label>
        <div className="flex gap-1">
          <select
            className="rounded border bg-gray-50 px-1 py-1 text-xs"
            value={dataSource.method || 'get'}
            onChange={(e) => updateField('method', e.target.value)}
            disabled={readonly}
          >
            <option value="get">GET</option>
            <option value="post">POST</option>
          </select>
          <input
            className="flex-1 rounded border px-2 py-1 text-xs"
            value={dataSource.endpoint || ''}
            onChange={(e) => updateField('endpoint', e.target.value)}
            placeholder="/api/..."
            disabled={readonly}
            data-testid="ds-endpoint-input"
          />
        </div>
      </div>

      {/* Response Adaptor */}
      <div>
        <label className="mb-1 block text-[10px] font-semibold text-gray-500">Response Adaptor</label>
        <select
          className="w-full rounded border bg-gray-50 px-2 py-1 text-xs"
          value={(dataSource as any).adaptor || 'table'}
          onChange={(e) => updateField('adaptor', e.target.value)}
          disabled={readonly}
        >
          <option value="table">table — {"{ records: [], total }"}</option>
          <option value="form">form — single object</option>
          <option value="raw">raw — passthrough</option>
        </select>
      </div>

      {/* Pagination */}
      <div>
        <label className="mb-1 block text-[10px] font-semibold text-gray-500">Pagination</label>
        <label className="flex items-center gap-1 text-xs">
          <input
            type="checkbox"
            checked={dataSource.pagination !== false}
            onChange={(e) => updateField('pagination', e.target.checked)}
            disabled={readonly}
          />
          Enabled
        </label>
      </div>

      {/* Query Parameters */}
      <div>
        <div className="mb-1 flex items-center justify-between">
          <label className="text-[10px] font-semibold text-gray-500">Query Parameters</label>
          <button
            onClick={() => {
              const params = (dataSource as any).params || {};
              updateField('params', { ...params, ['param_' + Date.now()]: '' });
            }}
            className="text-[10px] text-blue-500"
            disabled={readonly}
          >
            + Add
          </button>
        </div>
        {Object.keys((dataSource as any).params || {}).length > 0 ? (
          <div className="space-y-1">
            {Object.entries((dataSource as any).params || {}).map(([key, value]) => (
              <div key={key} className="flex items-center gap-1">
                <input
                  className="flex-1 rounded border px-1.5 py-0.5 text-xs"
                  value={key}
                  onChange={(e) => {
                    const params = { ...(dataSource as any).params };
                    const val = params[key];
                    delete params[key];
                    params[e.target.value] = val;
                    updateField('params', params);
                  }}
                  placeholder="key"
                  disabled={readonly}
                />
                <input
                  className="flex-1 rounded border px-1.5 py-0.5 text-xs"
                  value={String(value ?? '')}
                  onChange={(e) => {
                    const params = { ...(dataSource as any).params };
                    params[key] = e.target.value;
                    updateField('params', params);
                  }}
                  placeholder="value"
                  disabled={readonly}
                />
                <button
                  onClick={() => {
                    const params = { ...(dataSource as any).params };
                    delete params[key];
                    updateField('params', params);
                  }}
                  className="text-xs text-red-400"
                  disabled={readonly}
                >
                  ×
                </button>
              </div>
            ))}
          </div>
        ) : (
          <div className="rounded border border-dashed bg-gray-50 px-2 py-1.5 text-center text-[10px] text-gray-400">
            No custom parameters
          </div>
        )}
      </div>

      {/* Test & Detect */}
      <div className="border-t pt-2">
        <button
          onClick={onTestDetect}
          className="rounded bg-blue-500 px-3 py-1 text-xs text-white"
          disabled={readonly}
        >
          Test & Detect Fields
        </button>
        {testStatus?.connected && (
          <span className="ml-2 text-xs text-green-600">
            ✓ {testStatus.recordCount ?? '?'} records
          </span>
        )}
        {testStatus?.error && !testStatus.connected && (
          <span className="ml-2 text-xs text-red-500">{testStatus.error}</span>
        )}
      </div>
    </div>
  );
}
