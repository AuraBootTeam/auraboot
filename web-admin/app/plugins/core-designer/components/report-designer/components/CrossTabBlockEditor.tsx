/**
 * CrossTabBlockEditor — property editor for cross-tab blocks
 */

import React from 'react';
import { useSmartText } from '~/utils/i18n';
import type { CrossTabBlock, ReportDataSource } from '../types';

interface CrossTabBlockEditorProps {
  block: CrossTabBlock;
  dataSources: Record<string, ReportDataSource>;
  onChange: (updates: Partial<CrossTabBlock>) => void;
}

export const CrossTabBlockEditor: React.FC<CrossTabBlockEditorProps> = ({
  block,
  dataSources,
  onChange,
}) => {
  const text = useSmartText();
  const dsKeys = Object.keys(dataSources);

  return (
    <div className="space-y-4">
      <div>
        <label className="mb-1 block text-sm font-medium text-gray-700">
          {text({ zh: '标题', en: 'Title' })}
        </label>
        <input
          type="text"
          value={block.title || ''}
          onChange={(e) => onChange({ title: e.target.value })}
          className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
          placeholder={text({ zh: '交叉表标题', en: 'Cross Tab Title' })}
        />
      </div>

      <div>
        <label className="mb-1 block text-sm font-medium text-gray-700">
          {text({ zh: '数据源', en: 'Data Source' })}
        </label>
        <select
          value={block.dataSource}
          onChange={(e) => onChange({ dataSource: e.target.value })}
          className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
        >
          <option value="">{text({ zh: '选择', en: 'Select' })}</option>
          {dsKeys.map((k) => (
            <option key={k} value={k}>
              {k}
            </option>
          ))}
        </select>
      </div>

      <div>
        <label className="mb-1 block text-sm font-medium text-gray-700">
          {text({ zh: '行分组字段', en: 'Row Field' })}
        </label>
        <input
          type="text"
          value={block.rowField || ''}
          onChange={(e) => onChange({ rowField: e.target.value })}
          className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
          placeholder={text({ zh: '用于行分组的字段', en: 'Field for row grouping' })}
        />
      </div>

      <div>
        <label className="mb-1 block text-sm font-medium text-gray-700">
          {text({ zh: '列透视字段', en: 'Column Field' })}
        </label>
        <input
          type="text"
          value={block.columnField || ''}
          onChange={(e) => onChange({ columnField: e.target.value })}
          className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
          placeholder={text({ zh: '用于列透视的字段', en: 'Field for column pivot' })}
        />
      </div>

      <div>
        <label className="mb-1 block text-sm font-medium text-gray-700">
          {text({ zh: '数值字段', en: 'Value Field' })}
        </label>
        <input
          type="text"
          value={block.valueField || ''}
          onChange={(e) => onChange({ valueField: e.target.value })}
          className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
          placeholder={text({ zh: '用于聚合的字段', en: 'Field to aggregate' })}
        />
      </div>

      <div>
        <label className="mb-1 block text-sm font-medium text-gray-700">
          {text({ zh: '聚合方式', en: 'Aggregation' })}
        </label>
        <select
          value={block.aggregation}
          onChange={(e) => onChange({ aggregation: e.target.value as any })}
          className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
        >
          <option value="sum">{text({ zh: '求和', en: 'SUM' })}</option>
          <option value="avg">{text({ zh: '平均值', en: 'AVG' })}</option>
          <option value="count">{text({ zh: '计数', en: 'COUNT' })}</option>
          <option value="min">{text({ zh: '最小值', en: 'MIN' })}</option>
          <option value="max">{text({ zh: '最大值', en: 'MAX' })}</option>
        </select>
      </div>

      <div>
        <label className="mb-1 block text-sm font-medium text-gray-700">
          {text({ zh: '格式', en: 'Format' })}
        </label>
        <select
          value={block.format || ''}
          onChange={(e) => onChange({ format: e.target.value || undefined })}
          className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
        >
          <option value="">{text({ zh: '数字', en: 'Number' })}</option>
          <option value="currency">{text({ zh: '金额（¥）', en: 'Currency (¥)' })}</option>
          <option value="percent">{text({ zh: '百分比（%）', en: 'Percent (%)' })}</option>
        </select>
      </div>

      <div className="space-y-2 border-t border-gray-200 pt-4">
        <h3 className="text-xs font-medium tracking-wider text-gray-500 uppercase">
          {text({ zh: '合计', en: 'Totals' })}
        </h3>
        <label className="flex cursor-pointer items-center gap-2">
          <input
            type="checkbox"
            checked={block.showRowTotal ?? true}
            onChange={(e) => onChange({ showRowTotal: e.target.checked })}
            className="h-4 w-4 rounded border-gray-300 text-blue-600"
          />
          <span className="text-sm text-gray-700">{text({ zh: '行合计', en: 'Row totals' })}</span>
        </label>
        <label className="flex cursor-pointer items-center gap-2">
          <input
            type="checkbox"
            checked={block.showColumnTotal ?? true}
            onChange={(e) => onChange({ showColumnTotal: e.target.checked })}
            className="h-4 w-4 rounded border-gray-300 text-blue-600"
          />
          <span className="text-sm text-gray-700">
            {text({ zh: '列合计', en: 'Column totals' })}
          </span>
        </label>
      </div>
    </div>
  );
};
