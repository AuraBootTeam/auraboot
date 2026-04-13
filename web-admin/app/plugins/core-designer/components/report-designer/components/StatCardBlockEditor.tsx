/**
 * StatCardBlockEditor — property editor for stat-card blocks
 */

import React from 'react';
import type { StatCardBlock, ReportDataSource } from '../types';

interface StatCardBlockEditorProps {
  block: StatCardBlock;
  dataSources: Record<string, ReportDataSource>;
  onChange: (updates: Partial<StatCardBlock>) => void;
}

export const StatCardBlockEditor: React.FC<StatCardBlockEditorProps> = ({
  block,
  dataSources,
  onChange,
}) => {
  const dsKeys = Object.keys(dataSources);

  return (
    <div className="space-y-4">
      <div>
        <label className="mb-1 block text-sm font-medium text-gray-700">Label</label>
        <input
          type="text"
          value={block.label || ''}
          onChange={(e) => onChange({ label: e.target.value })}
          className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
          placeholder="e.g. Total Revenue"
        />
      </div>

      <div>
        <label className="mb-1 block text-sm font-medium text-gray-700">Data Source</label>
        <select
          value={block.dataSource}
          onChange={(e) => onChange({ dataSource: e.target.value })}
          className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
        >
          <option value="">Select data source</option>
          {dsKeys.map((key) => (
            <option key={key} value={key}>
              {key}
            </option>
          ))}
        </select>
      </div>

      <div>
        <label className="mb-1 block text-sm font-medium text-gray-700">Value Field</label>
        <input
          type="text"
          value={block.valueField || ''}
          onChange={(e) => onChange({ valueField: e.target.value })}
          className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
          placeholder="Field name"
        />
      </div>

      <div>
        <label className="mb-1 block text-sm font-medium text-gray-700">Aggregation</label>
        <select
          value={block.aggregation}
          onChange={(e) =>
            onChange({ aggregation: e.target.value as StatCardBlock['aggregation'] })
          }
          className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
        >
          <option value="sum">SUM</option>
          <option value="avg">AVG</option>
          <option value="count">COUNT</option>
          <option value="min">MIN</option>
          <option value="max">MAX</option>
        </select>
      </div>

      <div>
        <label className="mb-1 block text-sm font-medium text-gray-700">Format</label>
        <select
          value={block.format || ''}
          onChange={(e) => onChange({ format: e.target.value || undefined })}
          className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
        >
          <option value="">Number</option>
          <option value="currency">Currency (¥)</option>
          <option value="percent">Percent (%)</option>
        </select>
      </div>

      <div>
        <label className="mb-1 block text-sm font-medium text-gray-700">Color</label>
        <div className="flex flex-wrap gap-2">
          {['blue', 'green', 'orange', 'red', 'purple', 'gray'].map((color) => (
            <button
              key={color}
              onClick={() => onChange({ color })}
              className={`h-8 w-8 rounded-full border-2 ${block.color === color ? 'border-gray-900 ring-2 ring-gray-400 ring-offset-1' : 'border-gray-300'}`}
              style={{
                backgroundColor:
                  color === 'gray' ? '#9ca3af' : color === 'orange' ? '#f97316' : color,
              }}
            />
          ))}
        </div>
      </div>
    </div>
  );
};
