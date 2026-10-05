/**
 * ChartBlockEditor — property editor for chart blocks
 */

import React from 'react';
import { useSmartText } from '~/utils/i18n';
import type { ChartBlock, ChartType, ReportDataSource } from '../types';

interface ChartBlockEditorProps {
  block: ChartBlock;
  dataSources: Record<string, ReportDataSource>;
  onChange: (updates: Partial<ChartBlock>) => void;
}

export const ChartBlockEditor: React.FC<ChartBlockEditorProps> = ({
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
          placeholder={text({ zh: '图表标题', en: 'Chart Title' })}
        />
      </div>

      <div>
        <label className="mb-1 block text-sm font-medium text-gray-700">
          {text({ zh: '图表类型', en: 'Chart Type' })}
        </label>
        <div className="flex gap-1">
          {(['bar', 'horizontal-bar', 'pie'] as ChartType[]).map((type) => (
            <button
              key={type}
              onClick={() => onChange({ chartType: type })}
              className={`flex-1 rounded border px-3 py-1.5 text-sm ${block.chartType === type ? 'border-blue-300 bg-blue-50 text-blue-700' : 'border-gray-300 bg-white text-gray-600 hover:bg-gray-50'}`}
            >
              {text(
                {
                  bar: { zh: '柱状图', en: 'Bar' },
                  'horizontal-bar': { zh: '横向柱状图', en: 'H-Bar' },
                  pie: { zh: '饼图', en: 'Pie' },
                }[type],
              )}
            </button>
          ))}
        </div>
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
          {text({ zh: '分类字段', en: 'Category Field' })}
        </label>
        <input
          type="text"
          value={block.categoryField || ''}
          onChange={(e) => onChange({ categoryField: e.target.value })}
          className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
          placeholder={text({ zh: '分类字段（X 轴）', en: 'Field for categories (X axis)' })}
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
          placeholder={text({ zh: '数值字段（Y 轴）', en: 'Field for values (Y axis)' })}
        />
      </div>

      <div>
        <label className="mb-1 block text-sm font-medium text-gray-700">
          {text({ zh: '聚合方式', en: 'Aggregation' })}
        </label>
        <select
          value={block.aggregation || 'sum'}
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

      <div className="space-y-2 border-t border-gray-200 pt-4">
        <h3 className="text-xs font-medium tracking-wider text-gray-500 uppercase">
          {text({ zh: '尺寸', en: 'Size' })}
        </h3>
        <div className="grid grid-cols-2 gap-2">
          <div>
            <label className="mb-1 block text-xs text-gray-500">
              {text({ zh: '宽度', en: 'Width' })}
            </label>
            <input
              type="number"
              value={block.width || 400}
              onChange={(e) => onChange({ width: Number(e.target.value) })}
              className="w-full rounded border border-gray-300 px-2 py-1 text-sm"
              min={200}
              max={800}
            />
          </div>
          <div>
            <label className="mb-1 block text-xs text-gray-500">
              {text({ zh: '高度', en: 'Height' })}
            </label>
            <input
              type="number"
              value={block.height || 240}
              onChange={(e) => onChange({ height: Number(e.target.value) })}
              className="w-full rounded border border-gray-300 px-2 py-1 text-sm"
              min={120}
              max={600}
            />
          </div>
        </div>
      </div>
    </div>
  );
};
