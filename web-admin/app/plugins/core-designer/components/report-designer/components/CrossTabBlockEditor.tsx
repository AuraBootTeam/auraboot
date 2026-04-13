/**
 * CrossTabBlockEditor — property editor for cross-tab blocks
 */

import React from 'react';
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
          placeholder="Cross Tab Title"
        />
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
        <label className="mb-1 block text-sm font-medium text-gray-700">Row Field</label>
        <input
          type="text"
          value={block.rowField || ''}
          onChange={(e) => onChange({ rowField: e.target.value })}
          className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
          placeholder="Field for row grouping"
        />
      </div>

      <div>
        <label className="mb-1 block text-sm font-medium text-gray-700">Column Field</label>
        <input
          type="text"
          value={block.columnField || ''}
          onChange={(e) => onChange({ columnField: e.target.value })}
          className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
          placeholder="Field for column pivot"
        />
      </div>

      <div>
        <label className="mb-1 block text-sm font-medium text-gray-700">Value Field</label>
        <input
          type="text"
          value={block.valueField || ''}
          onChange={(e) => onChange({ valueField: e.target.value })}
          className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
          placeholder="Field to aggregate"
        />
      </div>

      <div>
        <label className="mb-1 block text-sm font-medium text-gray-700">Aggregation</label>
        <select
          value={block.aggregation}
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

      <div className="space-y-2 border-t border-gray-200 pt-4">
        <h3 className="text-xs font-medium tracking-wider text-gray-500 uppercase">Totals</h3>
        <label className="flex cursor-pointer items-center gap-2">
          <input
            type="checkbox"
            checked={block.showRowTotal ?? true}
            onChange={(e) => onChange({ showRowTotal: e.target.checked })}
            className="h-4 w-4 rounded border-gray-300 text-blue-600"
          />
          <span className="text-sm text-gray-700">Row totals</span>
        </label>
        <label className="flex cursor-pointer items-center gap-2">
          <input
            type="checkbox"
            checked={block.showColumnTotal ?? true}
            onChange={(e) => onChange({ showColumnTotal: e.target.checked })}
            className="h-4 w-4 rounded border-gray-300 text-blue-600"
          />
          <span className="text-sm text-gray-700">Column totals</span>
        </label>
      </div>
    </div>
  );
};
