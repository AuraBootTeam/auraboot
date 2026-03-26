/**
 * ChartBlockEditor — property editor for chart blocks
 */

import React from 'react';
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
  const dsKeys = Object.keys(dataSources);

  return (
    <div className="space-y-4">
      <div>
        <label className="mb-1 block text-sm font-medium text-gray-700">Title</label>
        <input
          type="text"
          value={block.title || ''}
          onChange={(e) => onChange({ title: e.target.value })}
          className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
          placeholder="Chart Title"
        />
      </div>

      <div>
        <label className="mb-1 block text-sm font-medium text-gray-700">Chart Type</label>
        <div className="flex gap-1">
          {(
            [
              ['bar', 'Bar'],
              ['horizontal-bar', 'H-Bar'],
              ['pie', 'Pie'],
            ] as [ChartType, string][]
          ).map(([type, label]) => (
            <button
              key={type}
              onClick={() => onChange({ chartType: type })}
              className={`flex-1 rounded border px-3 py-1.5 text-sm ${block.chartType === type ? 'border-blue-300 bg-blue-50 text-blue-700' : 'border-gray-300 bg-white text-gray-600 hover:bg-gray-50'}`}
            >
              {label}
            </button>
          ))}
        </div>
      </div>

      <div>
        <label className="mb-1 block text-sm font-medium text-gray-700">Data Source</label>
        <select
          value={block.dataSource}
          onChange={(e) => onChange({ dataSource: e.target.value })}
          className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
        >
          <option value="">Select</option>
          {dsKeys.map((k) => (
            <option key={k} value={k}>
              {k}
            </option>
          ))}
        </select>
      </div>

      <div>
        <label className="mb-1 block text-sm font-medium text-gray-700">Category Field</label>
        <input
          type="text"
          value={block.categoryField || ''}
          onChange={(e) => onChange({ categoryField: e.target.value })}
          className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
          placeholder="Field for categories (X axis)"
        />
      </div>

      <div>
        <label className="mb-1 block text-sm font-medium text-gray-700">Value Field</label>
        <input
          type="text"
          value={block.valueField || ''}
          onChange={(e) => onChange({ valueField: e.target.value })}
          className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
          placeholder="Field for values (Y axis)"
        />
      </div>

      <div>
        <label className="mb-1 block text-sm font-medium text-gray-700">Aggregation</label>
        <select
          value={block.aggregation || 'sum'}
          onChange={(e) => onChange({ aggregation: e.target.value as any })}
          className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
        >
          <option value="sum">SUM</option>
          <option value="avg">AVG</option>
          <option value="count">COUNT</option>
          <option value="min">MIN</option>
          <option value="max">MAX</option>
        </select>
      </div>

      <div className="space-y-2 border-t border-gray-200 pt-4">
        <h3 className="text-xs font-medium tracking-wider text-gray-500 uppercase">Size</h3>
        <div className="grid grid-cols-2 gap-2">
          <div>
            <label className="mb-1 block text-xs text-gray-500">Width</label>
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
            <label className="mb-1 block text-xs text-gray-500">Height</label>
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
