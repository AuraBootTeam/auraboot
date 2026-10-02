/**
 * DataTableBlockEditor — property editor for table blocks
 * Configures data source, columns, and table style.
 * Uses shared ModelPicker and NamedQueryPicker for data source creation.
 */

import React, { useState } from 'react';
import { useSmartText } from '~/utils/i18n';
import { useReportDocument } from '../state/ReportDocumentProvider';
import type { DataTableBlock, ReportColumn, ReportDataSource } from '../types';
import { ModelPicker, NamedQueryPicker } from '~/shared/designer/datasource';

interface DataTableBlockEditorProps {
  block: DataTableBlock;
  dataSources: Record<string, ReportDataSource>;
  onChange: (updates: Partial<DataTableBlock>) => void;
}

export const DataTableBlockEditor: React.FC<DataTableBlockEditorProps> = ({
  block,
  dataSources,
  onChange,
}) => {
  const text = useSmartText();
  const { addDataSource } = useReportDocument();
  const [newDsKey, setNewDsKey] = useState('');
  const [newDsType, setNewDsType] = useState<'model' | 'namedQuery' | 'api'>('model');
  const [newDsValue, setNewDsValue] = useState('');
  const [showAddDs, setShowAddDs] = useState(false);

  const [newColField, setNewColField] = useState('');

  const dsKeys = Object.keys(dataSources);

  const handleAddDataSource = () => {
    if (!newDsKey || !newDsValue) return;
    const ds: ReportDataSource = { type: newDsType };
    if (newDsType === 'model') ds.modelCode = newDsValue;
    else if (newDsType === 'namedQuery') ds.queryCode = newDsValue;
    else ds.url = newDsValue;

    addDataSource(newDsKey, ds);
    onChange({ dataSource: newDsKey });
    setNewDsKey('');
    setNewDsValue('');
    setShowAddDs(false);
  };

  const handleAddColumn = () => {
    if (!newColField.trim()) return;
    const newCol: ReportColumn = { field: newColField.trim(), label: newColField.trim() };
    onChange({ columns: [...block.columns, newCol] });
    setNewColField('');
  };

  const handleRemoveColumn = (idx: number) => {
    const cols = [...block.columns];
    cols.splice(idx, 1);
    onChange({ columns: cols });
  };

  const handleUpdateColumn = (idx: number, updates: Partial<ReportColumn>) => {
    const cols = [...block.columns];
    cols[idx] = { ...cols[idx], ...updates };
    onChange({ columns: cols });
  };

  return (
    <div className="space-y-5">
      {/* Title */}
      <div>
        <label className="mb-1 block text-sm font-medium text-gray-700">{text({ zh: '标题', en: 'Title' })}</label>
        <input
          type="text"
          value={block.title || ''}
          onChange={(e) => onChange({ title: e.target.value })}
          className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
          placeholder={text({ zh: '表格标题', en: 'Table title' })}
        />
      </div>

      {/* Data Source */}
      <div>
        <label className="mb-1 block text-sm font-medium text-gray-700">{text({ zh: '数据源', en: 'Data Source' })}</label>
        <select
          value={block.dataSource}
          onChange={(e) => onChange({ dataSource: e.target.value })}
          className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
        >
          <option value="">{text({ zh: '选择数据源', en: 'Select data source' })}</option>
          {dsKeys.map((key) => (
            <option key={key} value={key}>
              {key} ({dataSources[key].type})
            </option>
          ))}
        </select>

        {!showAddDs ? (
          <button
            onClick={() => setShowAddDs(true)}
            className="mt-2 text-xs text-blue-600 hover:text-blue-700"
          >
            {text({ zh: '+ 添加数据源', en: '+ Add new data source' })}
          </button>
        ) : (
          <div className="mt-2 space-y-2 rounded-md bg-gray-50 p-3">
            <input
              type="text"
              value={newDsKey}
              onChange={(e) => setNewDsKey(e.target.value)}
              placeholder={text({ zh: '名称（例如 main）', en: 'Key (e.g. main)' })}
              className="w-full rounded-md border border-gray-300 px-2 py-1.5 text-sm"
            />
            <select
              value={newDsType}
              onChange={(e) => setNewDsType(e.target.value as 'model' | 'namedQuery' | 'api')}
              className="w-full rounded-md border border-gray-300 px-2 py-1.5 text-sm"
            >
              <option value="model">{text({ zh: '模型', en: 'Model' })}</option>
              <option value="namedQuery">{text({ zh: '命名查询', en: 'Named Query' })}</option>
              <option value="api">API</option>
            </select>
            {newDsType === 'model' && (
              <ModelPicker
                value={newDsValue}
                onChange={setNewDsValue}
                label=""
                placeholder={text({ zh: '选择模型', en: 'Select model' })}
              />
            )}
            {newDsType === 'namedQuery' && (
              <NamedQueryPicker
                value={newDsValue}
                onChange={setNewDsValue}
                label=""
                placeholder={text({ zh: '选择命名查询', en: 'Select named query' })}
              />
            )}
            {newDsType === 'api' && (
              <input
                type="text"
                value={newDsValue}
                onChange={(e) => setNewDsValue(e.target.value)}
                placeholder={text({ zh: 'API 地址', en: 'API URL' })}
                className="w-full rounded-md border border-gray-300 px-2 py-1.5 text-sm"
              />
            )}
            <div className="flex gap-2">
              <button
                onClick={handleAddDataSource}
                className="rounded bg-blue-600 px-3 py-1 text-xs text-white hover:bg-blue-700"
              >
                {text({ zh: '添加', en: 'Add' })}
              </button>
              <button
                onClick={() => setShowAddDs(false)}
                className="rounded border border-gray-300 bg-white px-3 py-1 text-xs text-gray-600 hover:bg-gray-50"
              >
                {text({ zh: '取消', en: 'Cancel' })}
              </button>
            </div>
          </div>
        )}
      </div>

      {/* Columns */}
      <div>
        <label className="mb-2 block text-sm font-medium text-gray-700">{text({ zh: '列', en: 'Columns' })}</label>
        <div className="space-y-2">
          {block.columns.map((col, idx) => (
            <div key={idx} className="flex items-center gap-2 rounded bg-gray-50 p-2">
              <div className="flex-1 space-y-1">
                <input
                  type="text"
                  value={col.field}
                  onChange={(e) => handleUpdateColumn(idx, { field: e.target.value })}
                  className="w-full rounded border border-gray-300 px-2 py-1 text-xs"
                  placeholder={text({ zh: '字段名称', en: 'Field name' })}
                />
                <input
                  type="text"
                  value={col.label || ''}
                  onChange={(e) => handleUpdateColumn(idx, { label: e.target.value })}
                  className="w-full rounded border border-gray-300 px-2 py-1 text-xs"
                  placeholder={text({ zh: '显示名称', en: 'Display label' })}
                />
                <div className="flex gap-1">
                  <select
                    value={col.align || 'left'}
                    onChange={(e) =>
                      handleUpdateColumn(idx, {
                        align: e.target.value as 'left' | 'center' | 'right',
                      })
                    }
                    className="flex-1 rounded border border-gray-300 px-2 py-1 text-xs"
                  >
                    <option value="left">{text({ zh: '左对齐', en: 'Left' })}</option>
                    <option value="center">{text({ zh: '居中', en: 'Center' })}</option>
                    <option value="right">{text({ zh: '右对齐', en: 'Right' })}</option>
                  </select>
                  <select
                    value={col.format || ''}
                    onChange={(e) =>
                      handleUpdateColumn(idx, { format: e.target.value || undefined })
                    }
                    className="flex-1 rounded border border-gray-300 px-2 py-1 text-xs"
                  >
                    <option value="">{text({ zh: '默认', en: 'Default' })}</option>
                    <option value="number">{text({ zh: '数字', en: 'Number' })}</option>
                    <option value="currency">{text({ zh: '货币', en: 'Currency' })}</option>
                    <option value="percent">{text({ zh: '百分比', en: 'Percent' })}</option>
                    <option value="date">{text({ zh: '日期', en: 'Date' })}</option>
                  </select>
                </div>
              </div>
              <button
                onClick={() => handleRemoveColumn(idx)}
                className="p-1 text-gray-400 hover:text-red-500"
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
            </div>
          ))}
        </div>

        <div className="mt-2 flex gap-2">
          <input
            type="text"
            value={newColField}
            onChange={(e) => setNewColField(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') handleAddColumn();
            }}
            placeholder={text({ zh: '字段名称', en: 'Field name' })}
            className="flex-1 rounded-md border border-gray-300 px-2 py-1.5 text-sm"
          />
          <button
            onClick={handleAddColumn}
            className="rounded-md bg-blue-50 px-3 py-1.5 text-sm text-blue-600 hover:bg-blue-100"
          >
            {text({ zh: '添加', en: 'Add' })}
          </button>
        </div>
      </div>

      {/* Summary row */}
      <div className="space-y-2 border-t border-gray-200 pt-4">
        <h3 className="text-xs font-medium tracking-wider text-gray-500 uppercase">{text({ zh: '汇总行', en: 'Summary Row' })}</h3>
        <label className="flex cursor-pointer items-center gap-2">
          <input
            type="checkbox"
            checked={block.summary?.enabled ?? false}
            onChange={(e) =>
              onChange({
                summary: {
                  enabled: e.target.checked,
                  label: block.summary?.label || 'Total',
                  columns: block.summary?.columns || [],
                },
              })
            }
            className="h-4 w-4 rounded border-gray-300 text-blue-600"
          />
          <span className="text-sm text-gray-700">{text({ zh: '显示汇总行', en: 'Show summary row' })}</span>
        </label>
        {block.summary?.enabled && (
          <div className="space-y-1 pl-6">
            <input
              type="text"
              value={block.summary.label || 'Total'}
              onChange={(e) => onChange({ summary: { ...block.summary!, label: e.target.value } })}
              className="w-full rounded border border-gray-300 px-2 py-1 text-xs"
              placeholder={text({ zh: '名称（例如合计）', en: 'Label (e.g. Total)' })}
            />
            {(block.summary.columns || []).map((sc) => (
              <div key={sc.field} className="flex items-center gap-2">
                <span className="flex-1 text-xs text-gray-600">{sc.field}</span>
                <select
                  value={sc.aggregation}
                  onChange={(e) => {
                    const cols = block.summary!.columns.map((c) =>
                      c.field === sc.field ? { ...c, aggregation: e.target.value as any } : c,
                    );
                    onChange({ summary: { ...block.summary!, columns: cols } });
                  }}
                  className="rounded border border-gray-300 px-1 py-0.5 text-xs"
                >
                  <option value="sum">{text({ zh: '求和', en: 'SUM' })}</option>
                  <option value="avg">{text({ zh: '平均值', en: 'AVG' })}</option>
                  <option value="count">{text({ zh: '计数', en: 'COUNT' })}</option>
                  <option value="min">{text({ zh: '最小值', en: 'MIN' })}</option>
                  <option value="max">{text({ zh: '最大值', en: 'MAX' })}</option>
                </select>
                <button
                  onClick={() => {
                    const cols = block.summary!.columns.filter((c) => c.field !== sc.field);
                    onChange({ summary: { ...block.summary!, columns: cols } });
                  }}
                  className="text-gray-400 hover:text-red-500"
                >
                  <svg
                    className="h-3.5 w-3.5"
                    fill="none"
                    stroke="currentColor"
                    viewBox="0 0 24 24"
                  >
                    <path
                      strokeLinecap="round"
                      strokeLinejoin="round"
                      strokeWidth={2}
                      d="M6 18L18 6M6 6l12 12"
                    />
                  </svg>
                </button>
              </div>
            ))}
            {block.columns.filter(
              (c) => !(block.summary?.columns || []).find((sc) => sc.field === c.field),
            ).length > 0 && (
              <select
                value=""
                onChange={(e) => {
                  if (!e.target.value) return;
                  const cols = [
                    ...(block.summary?.columns || []),
                    { field: e.target.value, aggregation: 'sum' as const },
                  ];
                  onChange({ summary: { ...block.summary!, columns: cols } });
                }}
                className="w-full rounded border border-gray-300 px-2 py-1 text-xs"
              >
                <option value="">{text({ zh: '+ 添加列', en: '+ Add column' })}</option>
                {block.columns
                  .filter((c) => !(block.summary?.columns || []).find((sc) => sc.field === c.field))
                  .map((c) => (
                    <option key={c.field} value={c.field}>
                      {c.label || c.field}
                    </option>
                  ))}
              </select>
            )}
          </div>
        )}
      </div>

      {/* Table style */}
      <div className="space-y-2 border-t border-gray-200 pt-4">
        <h3 className="text-xs font-medium tracking-wider text-gray-500 uppercase">{text({ zh: '表格样式', en: 'Table Style' })}</h3>
        <label className="flex cursor-pointer items-center gap-2">
          <input
            type="checkbox"
            checked={block.showHeader !== false}
            onChange={(e) => onChange({ showHeader: e.target.checked })}
            className="h-4 w-4 rounded border-gray-300 text-blue-600"
          />
          <span className="text-sm text-gray-700">{text({ zh: '显示表头', en: 'Show header row' })}</span>
        </label>
        <label className="flex cursor-pointer items-center gap-2">
          <input
            type="checkbox"
            checked={block.stripe !== false}
            onChange={(e) => onChange({ stripe: e.target.checked })}
            className="h-4 w-4 rounded border-gray-300 text-blue-600"
          />
          <span className="text-sm text-gray-700">{text({ zh: '交替行底色', en: 'Striped rows' })}</span>
        </label>
        <label className="flex cursor-pointer items-center gap-2">
          <input
            type="checkbox"
            checked={block.border !== false}
            onChange={(e) => onChange({ border: e.target.checked })}
            className="h-4 w-4 rounded border-gray-300 text-blue-600"
          />
          <span className="text-sm text-gray-700">{text({ zh: '单元格边框', en: 'Cell borders' })}</span>
        </label>
      </div>
    </div>
  );
};
