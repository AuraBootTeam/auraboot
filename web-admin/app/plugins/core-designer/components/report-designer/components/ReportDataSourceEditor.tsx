/**
 * DataTableBlockEditor — property editor for table blocks
 * Configures data source, columns, and table style.
 * Uses shared ModelPicker and NamedQueryPicker for data source creation.
 */

import React, { useState } from 'react';
import { useSmartText } from '~/utils/i18n';
import { useReportDocument } from '../state/ReportDocumentProvider';
import type { ReportDataSource } from '../types';
import { ModelPicker, NamedQueryPicker } from '~/shared/designer/datasource';

import {
  propertyEditorRegistry,
  type RegisteredPropertyEditorProps,
} from '~/shared/designer/PropertyEditorRegistry';

const ReportDataSourceEditor = ({ adapter }: RegisteredPropertyEditorProps) => {
  const text = useSmartText();
  const { report } = useReportDocument();
  const dataSources = report?.dataSources ?? {};
  const block = { dataSource: typeof adapter.value === 'string' ? adapter.value : '' };
  const onChange = (patch: { dataSource: string }) => adapter.setValue(patch.dataSource);
  const { addDataSource } = useReportDocument();
  const [newDsKey, setNewDsKey] = useState('');
  const [newDsType, setNewDsType] = useState<'model' | 'namedQuery' | 'api'>('model');
  const [newDsValue, setNewDsValue] = useState('');
  const [showAddDs, setShowAddDs] = useState(false);

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

  return (
    <fieldset disabled={adapter.disabled} className="min-w-0 space-y-3">
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
    </fieldset>
  );
};

const disposeDataSourceEditor = propertyEditorRegistry.register(
  'report.data-source',
  ReportDataSourceEditor,
);
import.meta.hot?.dispose(disposeDataSourceEditor);
