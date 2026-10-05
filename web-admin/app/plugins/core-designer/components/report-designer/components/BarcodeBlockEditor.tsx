/**
 * BarcodeBlockEditor — property editor for barcode blocks
 */

import React from 'react';
import { useSmartText } from '~/utils/i18n';
import type { BarcodeBlock, BarcodeFormat, ReportDataSource } from '../types';

interface BarcodeBlockEditorProps {
  block: BarcodeBlock;
  dataSources: Record<string, ReportDataSource>;
  onChange: (updates: Partial<BarcodeBlock>) => void;
}

const BARCODE_FORMATS: { value: BarcodeFormat; label: string }[] = [
  { value: 'code128', label: 'code128' },
  { value: 'code39', label: 'code39' },
  { value: 'ean13', label: 'EAN-13' },
  { value: 'ean8', label: 'EAN-8' },
  { value: 'upc', label: 'upc' },
  { value: 'itf14', label: 'ITF-14' },
];

export const BarcodeBlockEditor: React.FC<BarcodeBlockEditorProps> = ({
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
          placeholder={text({ zh: '条码标题', en: 'Barcode Title' })}
        />
      </div>

      <div>
        <label className="mb-1 block text-sm font-medium text-gray-700">
          {text({ zh: '格式', en: 'Format' })}
        </label>
        <select
          value={block.format}
          onChange={(e) => onChange({ format: e.target.value as BarcodeFormat })}
          className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
        >
          {BARCODE_FORMATS.map((f) => (
            <option key={f.value} value={f.value}>
              {f.label}
            </option>
          ))}
        </select>
      </div>

      <div className="space-y-2 border-t border-gray-200 pt-4">
        <h3 className="text-xs font-medium tracking-wider text-gray-500 uppercase">
          {text({ zh: '取值来源', en: 'Value Source' })}
        </h3>

        <div>
          <label className="mb-1 block text-sm font-medium text-gray-700">
            {text({ zh: '固定值', en: 'Static Value' })}
          </label>
          <input
            type="text"
            value={block.staticValue || ''}
            onChange={(e) => onChange({ staticValue: e.target.value })}
            className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
            placeholder={text({ zh: '例如：ABC-12345', en: 'e.g. ABC-12345' })}
          />
          <p className="mt-1 text-xs text-gray-400">
            {text({ zh: '或绑定下方数据源字段', en: 'Or bind to a data source field below' })}
          </p>
        </div>

        <div>
          <label className="mb-1 block text-sm font-medium text-gray-700">
            {text({ zh: '数据源', en: 'Data Source' })}
          </label>
          <select
            value={block.dataSource || ''}
            onChange={(e) => onChange({ dataSource: e.target.value || undefined })}
            className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
          >
            <option value="">
              {text({ zh: '无（使用固定值）', en: 'None (use static value)' })}
            </option>
            {dsKeys.map((k) => (
              <option key={k} value={k}>
                {k}
              </option>
            ))}
          </select>
        </div>

        {block.dataSource && (
          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700">
              {text({ zh: '字段', en: 'Field' })}
            </label>
            <input
              type="text"
              value={block.field || ''}
              onChange={(e) => onChange({ field: e.target.value })}
              className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
              placeholder={text({ zh: '条码值所在字段', en: 'Field name for barcode value' })}
            />
          </div>
        )}
      </div>

      <div className="space-y-2 border-t border-gray-200 pt-4">
        <h3 className="text-xs font-medium tracking-wider text-gray-500 uppercase">
          {text({ zh: '外观', en: 'Appearance' })}
        </h3>
        <div className="grid grid-cols-2 gap-2">
          <div>
            <label className="mb-1 block text-xs text-gray-500">
              {text({ zh: '条码线宽', en: 'Bar Width' })}
            </label>
            <input
              type="number"
              value={block.width || 2}
              onChange={(e) => onChange({ width: Number(e.target.value) })}
              className="w-full rounded border border-gray-300 px-2 py-1 text-sm"
              min={1}
              max={4}
            />
          </div>
          <div>
            <label className="mb-1 block text-xs text-gray-500">
              {text({ zh: '高度', en: 'Height' })}
            </label>
            <input
              type="number"
              value={block.height || 60}
              onChange={(e) => onChange({ height: Number(e.target.value) })}
              className="w-full rounded border border-gray-300 px-2 py-1 text-sm"
              min={20}
              max={200}
            />
          </div>
        </div>

        <div>
          <label className="mb-1 block text-xs text-gray-500">
            {text({ zh: '字号', en: 'Font Size' })}
          </label>
          <input
            type="number"
            value={block.fontSize || 14}
            onChange={(e) => onChange({ fontSize: Number(e.target.value) })}
            className="w-full rounded border border-gray-300 px-2 py-1 text-sm"
            min={8}
            max={24}
          />
        </div>

        <div className="flex items-center gap-2">
          <input
            type="checkbox"
            id="displayValue"
            checked={block.displayValue !== false}
            onChange={(e) => onChange({ displayValue: e.target.checked })}
            className="rounded border-gray-300 text-blue-600 focus:ring-blue-500"
          />
          <label htmlFor="displayValue" className="text-sm text-gray-700">
            {text({ zh: '显示条码文字', en: 'Show value text' })}
          </label>
        </div>
      </div>
    </div>
  );
};
