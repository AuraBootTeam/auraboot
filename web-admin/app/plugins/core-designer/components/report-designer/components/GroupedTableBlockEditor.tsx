/**
 * GroupedTableBlockEditor — property editor for grouped-table blocks
 */

import React, { useState } from 'react';
import { useSmartText } from '~/utils/i18n';
import { useReportDocument } from '../state/ReportDocumentProvider';
import type {
  GroupedTableBlock,
  ReportColumn,
  ReportDataSource,
  SummaryConfig,
  SummaryColumnConfig,
} from '../types';

interface GroupedTableBlockEditorProps {
  block: GroupedTableBlock;
  dataSources: Record<string, ReportDataSource>;
  onChange: (updates: Partial<GroupedTableBlock>) => void;
}

const SummaryEditor: React.FC<{
  config: SummaryConfig | undefined;
  columns: ReportColumn[];
  label: string;
  onChange: (config: SummaryConfig) => void;
}> = ({ config, columns, label, onChange }) => {
  const text = useSmartText();
  const enabled = config?.enabled ?? false;

  const handleToggle = (checked: boolean) => {
    onChange({ enabled: checked, label: config?.label || label, columns: config?.columns || [] });
  };

  const handleAddCol = (field: string) => {
    const cols = [...(config?.columns || [])];
    if (cols.find((c) => c.field === field)) return;
    cols.push({ field, aggregation: 'sum' });
    onChange({ ...config!, enabled: true, columns: cols });
  };

  const handleRemoveCol = (field: string) => {
    const cols = (config?.columns || []).filter((c) => c.field !== field);
    onChange({ ...config!, columns: cols });
  };

  const handleUpdateCol = (field: string, agg: SummaryColumnConfig['aggregation']) => {
    const cols = (config?.columns || []).map((c) =>
      c.field === field ? { ...c, aggregation: agg } : c,
    );
    onChange({ ...config!, columns: cols });
  };

  return (
    <div className="space-y-2">
      <label className="flex cursor-pointer items-center gap-2">
        <input
          type="checkbox"
          checked={enabled}
          onChange={(e) => handleToggle(e.target.checked)}
          className="h-4 w-4 rounded border-gray-300 text-blue-600"
        />
        <span className="text-sm text-gray-700">{label}</span>
      </label>
      {enabled && (
        <div className="space-y-1 pl-6">
          {(config?.columns || []).map((sc) => (
            <div key={sc.field} className="flex items-center gap-2">
              <span className="flex-1 text-xs text-gray-600">{sc.field}</span>
              <select
                value={sc.aggregation}
                onChange={(e) =>
                  handleUpdateCol(sc.field, e.target.value as SummaryColumnConfig['aggregation'])
                }
                className="rounded border border-gray-300 px-1 py-0.5 text-xs"
              >
                <option value="sum">{text({ zh: '求和', en: 'SUM' })}</option>
                <option value="avg">{text({ zh: '平均值', en: 'AVG' })}</option>
                <option value="count">{text({ zh: '计数', en: 'COUNT' })}</option>
                <option value="min">{text({ zh: '最小值', en: 'MIN' })}</option>
                <option value="max">{text({ zh: '最大值', en: 'MAX' })}</option>
              </select>
              <button
                onClick={() => handleRemoveCol(sc.field)}
                className="text-gray-400 hover:text-red-500"
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
          ))}
          {columns.filter((c) => !(config?.columns || []).find((sc) => sc.field === c.field))
            .length > 0 && (
            <select
              value=""
              onChange={(e) => {
                if (e.target.value) handleAddCol(e.target.value);
              }}
              className="w-full rounded border border-gray-300 px-2 py-1 text-xs"
            >
              <option value="">{text({ zh: '+ 添加列', en: '+ Add column' })}</option>
              {columns
                .filter((c) => !(config?.columns || []).find((sc) => sc.field === c.field))
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
  );
};

export const GroupedTableBlockEditor: React.FC<GroupedTableBlockEditorProps> = ({
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

  const handleAddColumn = () => {
    if (!newColField.trim()) return;
    onChange({
      columns: [...block.columns, { field: newColField.trim(), label: newColField.trim() }],
    });
    setNewColField('');
  };

  const handleRemoveColumn = (idx: number) => {
    const cols = [...block.columns];
    cols.splice(idx, 1);
    onChange({ columns: cols });
  };

  return (
    <div className="space-y-5">
      {/* Title */}
      <div>
        <label className="mb-1 block text-sm font-medium text-gray-700">
          {text({ zh: '标题', en: 'Title' })}
        </label>
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
        <label className="mb-1 block text-sm font-medium text-gray-700">
          {text({ zh: '数据源', en: 'Data Source' })}
        </label>
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
              placeholder={text({ zh: '数据源标识', en: 'Key' })}
              className="w-full rounded-md border border-gray-300 px-2 py-1.5 text-sm"
            />
            <select
              value={newDsType}
              onChange={(e) => setNewDsType(e.target.value as any)}
              className="w-full rounded-md border border-gray-300 px-2 py-1.5 text-sm"
            >
              <option value="model">{text({ zh: '模型', en: 'Model' })}</option>
              <option value="namedQuery">{text({ zh: '命名查询', en: 'Named Query' })}</option>
              <option value="api">{text({ zh: 'API', en: 'API' })}</option>
            </select>
            <input
              type="text"
              value={newDsValue}
              onChange={(e) => setNewDsValue(e.target.value)}
              placeholder={
                newDsType === 'model'
                  ? text({ zh: '模型代码', en: 'Model code' })
                  : newDsType === 'namedQuery'
                    ? text({ zh: '查询代码', en: 'Query code' })
                    : text({ zh: 'API 地址', en: 'API URL' })
              }
              className="w-full rounded-md border border-gray-300 px-2 py-1.5 text-sm"
            />
            <div className="flex gap-2">
              <button
                onClick={() => {
                  if (newDsKey && newDsValue) {
                    const ds: any = { type: newDsType };
                    if (newDsType === 'model') ds.modelCode = newDsValue;
                    else if (newDsType === 'namedQuery') ds.queryCode = newDsValue;
                    else ds.url = newDsValue;
                    addDataSource(newDsKey, ds);
                    onChange({ dataSource: newDsKey });
                    setNewDsKey('');
                    setNewDsValue('');
                    setShowAddDs(false);
                  }
                }}
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

      {/* Group By Field */}
      <div>
        <label className="mb-1 block text-sm font-medium text-gray-700">
          {text({ zh: '分组字段', en: 'Group By Field' })}
        </label>
        <input
          type="text"
          value={block.groupByField || ''}
          onChange={(e) => onChange({ groupByField: e.target.value })}
          className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
          placeholder={text({ zh: '用于分组的字段', en: 'Field name to group by' })}
        />
      </div>

      {/* Columns */}
      <div>
        <label className="mb-2 block text-sm font-medium text-gray-700">
          {text({ zh: '列', en: 'Columns' })}
        </label>
        <div className="space-y-1">
          {block.columns.map((col, idx) => (
            <div key={idx} className="flex items-center gap-2 rounded bg-gray-50 px-2 py-1">
              <span className="flex-1 text-xs text-gray-700">{col.label || col.field}</span>
              <button
                onClick={() => handleRemoveColumn(idx)}
                className="text-gray-400 hover:text-red-500"
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

      {/* Subtotals */}
      <div className="space-y-3 border-t border-gray-200 pt-4">
        <h3 className="text-xs font-medium tracking-wider text-gray-500 uppercase">
          {text({ zh: '汇总', en: 'Subtotals' })}
        </h3>
        <SummaryEditor
          config={block.groupSubtotal}
          columns={block.columns}
          label={text({ zh: '分组小计', en: 'Group Subtotal' })}
          onChange={(c) => onChange({ groupSubtotal: c })}
        />
        <SummaryEditor
          config={block.grandTotal}
          columns={block.columns}
          label={text({ zh: '总计', en: 'Grand Total' })}
          onChange={(c) => onChange({ grandTotal: c })}
        />
      </div>

      {/* Style */}
      <div className="space-y-2 border-t border-gray-200 pt-4">
        <h3 className="text-xs font-medium tracking-wider text-gray-500 uppercase">
          {text({ zh: '样式', en: 'Style' })}
        </h3>
        <label className="flex cursor-pointer items-center gap-2">
          <input
            type="checkbox"
            checked={block.showHeader !== false}
            onChange={(e) => onChange({ showHeader: e.target.checked })}
            className="h-4 w-4 rounded border-gray-300 text-blue-600"
          />
          <span className="text-sm text-gray-700">
            {text({ zh: '显示表头', en: 'Show header row' })}
          </span>
        </label>
        <label className="flex cursor-pointer items-center gap-2">
          <input
            type="checkbox"
            checked={block.border !== false}
            onChange={(e) => onChange({ border: e.target.checked })}
            className="h-4 w-4 rounded border-gray-300 text-blue-600"
          />
          <span className="text-sm text-gray-700">
            {text({ zh: '单元格边框', en: 'Cell borders' })}
          </span>
        </label>
      </div>
    </div>
  );
};
