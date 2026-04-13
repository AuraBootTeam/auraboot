import React, { useState, useCallback, useMemo } from 'react';
import type { FormSchema } from '~/plugins/core-designer/components/studio/domain/schema/types';
import type { PreviewMode, PreviewFieldDef } from './types';
import { generateMockData } from './MockDataGenerator';
import { MockDataPanel } from './MockDataPanel';

interface DesignerPreviewEnhancedProps {
  schema: FormSchema;
}

/**
 * DesignerPreviewEnhanced - enhanced preview with empty form and mock data modes.
 * Renders a form-like preview of the schema with optional mock data filling.
 *
 * @since 3.6.0
 */
export const DesignerPreviewEnhanced: React.FC<DesignerPreviewEnhancedProps> = ({ schema }) => {
  const [previewMode, setPreviewMode] = useState<PreviewMode>('empty');
  const [showDataPanel, setShowDataPanel] = useState(false);
  const [mockData, setMockData] = useState<Record<string, any>>({});

  // Extract field definitions from schema components
  const fields: PreviewFieldDef[] = useMemo(() => {
    return extractFieldsFromSchema(schema);
  }, [schema]);

  const handleGenerateMock = useCallback(() => {
    const data = generateMockData(fields);
    setMockData(data);
    setPreviewMode('mock');
  }, [fields]);

  const handleClearMock = useCallback(() => {
    setMockData({});
    setPreviewMode('empty');
  }, []);

  const handleRegenerate = useCallback(() => {
    const data = generateMockData(fields);
    setMockData(data);
  }, [fields]);

  return (
    <div className="flex h-full">
      {/* Main preview area */}
      <div className="flex flex-1 flex-col">
        {/* Preview toolbar */}
        <div className="flex items-center justify-between border-b border-gray-200 bg-gray-50 px-4 py-2">
          <div className="flex items-center gap-2">
            <span className="text-xs text-gray-500">预览模式:</span>
            <div className="flex items-center rounded border border-gray-200 bg-white p-0.5">
              <button
                onClick={handleClearMock}
                className={`rounded px-2.5 py-1 text-xs ${
                  previewMode === 'empty'
                    ? 'bg-blue-500 text-white'
                    : 'text-gray-600 hover:bg-gray-100'
                }`}
              >
                空表单
              </button>
              <button
                onClick={handleGenerateMock}
                className={`rounded px-2.5 py-1 text-xs ${
                  previewMode === 'mock'
                    ? 'bg-blue-500 text-white'
                    : 'text-gray-600 hover:bg-gray-100'
                }`}
              >
                模拟数据
              </button>
            </div>
          </div>

          <div className="flex items-center gap-2">
            {previewMode === 'mock' && (
              <button
                onClick={handleRegenerate}
                className="rounded px-2 py-1 text-xs text-blue-600 hover:bg-blue-50"
              >
                重新生成
              </button>
            )}
            <button
              onClick={() => setShowDataPanel(!showDataPanel)}
              className={`rounded px-2 py-1 text-xs ${
                showDataPanel ? 'bg-blue-50 text-blue-600' : 'text-gray-600 hover:bg-gray-100'
              }`}
            >
              数据面板
            </button>
          </div>
        </div>

        {/* Form preview */}
        <div className="flex-1 overflow-auto p-6">
          <div className="mx-auto max-w-2xl">
            <h2 className="mb-4 text-lg font-medium text-gray-800">
              {schema.title || '未命名页面'}
            </h2>

            {fields.length === 0 && (
              <div className="py-8 text-center text-gray-400">
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
                    d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z"
                  />
                </svg>
                <p className="text-sm">暂无字段，请在设计态添加组件</p>
              </div>
            )}

            <div className="space-y-4">
              {fields.map((field) => (
                <PreviewField
                  key={field.code}
                  field={field}
                  value={previewMode === 'mock' ? mockData[field.code] : undefined}
                />
              ))}
            </div>
          </div>
        </div>
      </div>

      {/* Side data panel */}
      {showDataPanel && (
        <div className="w-64 border-l border-gray-200 bg-white">
          <MockDataPanel
            data={mockData}
            fields={fields}
            onRegenerate={handleRegenerate}
            onDataChange={setMockData}
          />
        </div>
      )}
    </div>
  );
};

/**
 * PreviewField - renders a single field in preview mode.
 */
const PreviewField: React.FC<{
  field: PreviewFieldDef;
  value?: any;
}> = ({ field, value }) => {
  const displayValue = value !== undefined && value !== null ? String(value) : '';

  return (
    <div className="space-y-1">
      <label className="flex items-center gap-1 text-sm text-gray-700">
        {field.label || field.code}
        {field.required && <span className="text-red-500">*</span>}
      </label>
      {renderFieldInput(field, displayValue)}
    </div>
  );
};

function renderFieldInput(field: PreviewFieldDef, value: string) {
  const baseClass =
    'w-full px-3 py-2 text-sm border border-gray-300 rounded-md bg-gray-50 text-gray-700';
  const dt = (field.dataType ?? 'string').toLowerCase();

  switch (dt) {
    case 'text':
      return (
        <textarea
          value={value}
          readOnly
          rows={3}
          className={`${baseClass} resize-none`}
          placeholder={`请输入${field.label || field.code}`}
        />
      );

    case 'boolean':
      return (
        <div className="flex items-center gap-2 py-1">
          <input type="checkbox" checked={value === 'true'} readOnly className="rounded" />
          <span className="text-sm text-gray-600">{value === 'true' ? '是' : '否'}</span>
        </div>
      );

    case 'enum':
      return (
        <select value={value} disabled className={baseClass}>
          <option value="">{value || `请选择${field.label || ''}`}</option>
          {field.options?.map((opt) => (
            <option key={opt.value} value={opt.value}>
              {opt.label}
            </option>
          ))}
        </select>
      );

    case 'date':
      return <input type="date" value={value} readOnly className={baseClass} />;

    case 'datetime':
      return (
        <input
          type="datetime-local"
          value={value ? value.replace('Z', '').slice(0, 16) : ''}
          readOnly
          className={baseClass}
        />
      );

    default:
      return (
        <input
          type="text"
          value={value}
          readOnly
          className={baseClass}
          placeholder={`请输入${field.label || field.code}`}
        />
      );
  }
}

/**
 * Extract field definitions from schema components.
 */
function extractFieldsFromSchema(schema: FormSchema): PreviewFieldDef[] {
  const fields: PreviewFieldDef[] = [];

  function walkComponents(components: any[]) {
    for (const comp of components) {
      if (comp.props?.fieldCode || comp.props?.code) {
        fields.push({
          code: comp.props.fieldCode || comp.props.code,
          label: comp.props.label || comp.name || comp.props.fieldCode || comp.props.code,
          dataType: comp.props.dataType || comp.props.fieldType || 'string',
          semanticType: comp.props.semanticType,
          required: comp.props.required,
          options: comp.props.options,
        });
      }
      if (comp.children) {
        walkComponents(comp.children);
      }
      if (comp.components) {
        walkComponents(comp.components);
      }
    }
  }

  if (schema.components) {
    walkComponents(schema.components);
  }

  return fields;
}
