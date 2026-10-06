/**
 * ParameterEditor — design-time editor for report parameters
 * Used in the property panel when no block is selected
 */

import React, { useState } from 'react';
import { useSmartText } from '~/utils/i18n';
import type { ReportParameter, ParameterType, ReportDataSource } from '../types';

interface ParameterEditorProps {
  parameters: ReportParameter[];
  dataSources: Record<string, ReportDataSource>;
  onChange: (parameters: ReportParameter[]) => void;
}

export const ParameterEditor: React.FC<ParameterEditorProps> = ({
  parameters,
  dataSources,
  onChange,
}) => {
  const text = useSmartText();
  const [showAdd, setShowAdd] = useState(false);
  const [newName, setNewName] = useState('');
  const [newLabel, setNewLabel] = useState('');
  const [newType, setNewType] = useState<ParameterType>('text');

  const handleAdd = () => {
    if (!newName.trim() || !newLabel.trim()) return;
    onChange([...parameters, { name: newName.trim(), label: newLabel.trim(), type: newType }]);
    setNewName('');
    setNewLabel('');
    setNewType('text');
    setShowAdd(false);
  };

  const handleRemove = (idx: number) => {
    const updated = [...parameters];
    updated.splice(idx, 1);
    onChange(updated);
  };

  const handleUpdate = (idx: number, updates: Partial<ReportParameter>) => {
    const updated = [...parameters];
    updated[idx] = { ...updated[idx], ...updates };
    onChange(updated);
  };

  const dsKeys = Object.keys(dataSources);

  return (
    <div className="space-y-3">
      <div className="flex items-center justify-between">
        <label className="text-sm font-medium text-gray-700">{text({ zh: '参数', en: 'Parameters' })}</label>
        <button
          onClick={() => setShowAdd(true)}
          className="text-xs text-blue-600 hover:text-blue-700"
        >
          {text({ zh: '+ 添加', en: '+ Add' })}
        </button>
      </div>

      {parameters.map((param, idx) => (
        <div key={idx} className="space-y-2 rounded-md bg-gray-50 p-3">
          <div className="flex items-center justify-between">
            <span className="text-xs font-medium text-gray-700">
              {param.label} ({text({ text: { zh: '文本', en: 'Text' }, number: { zh: '数字', en: 'Number' }, date: { zh: '日期', en: 'Date' }, 'date-range': { zh: '日期范围', en: 'Date Range' }, select: { zh: '选择', en: 'Select' } }[param.type])})
            </span>
            <button aria-label={text({ zh: '删除参数', en: 'Remove parameter' })} onClick={() => handleRemove(idx)} className="text-gray-400 hover:text-red-500">
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
          <div className="grid grid-cols-2 gap-2">
            <input
              type="text"
              value={param.name}
              onChange={(e) => handleUpdate(idx, { name: e.target.value })}
              className="rounded border border-gray-300 px-2 py-1 text-xs"
              placeholder={text({ zh: '参数名', en: 'name' })}
            />
            <input
              type="text"
              value={param.label}
              onChange={(e) => handleUpdate(idx, { label: e.target.value })}
              className="rounded border border-gray-300 px-2 py-1 text-xs"
              placeholder={text({ zh: '显示名称', en: 'label' })}
            />
          </div>
          <div className="flex gap-2">
            <select
              value={param.type}
              onChange={(e) => handleUpdate(idx, { type: e.target.value as ParameterType })}
              className="flex-1 rounded border border-gray-300 px-2 py-1 text-xs"
            >
              <option value="text">{text({ zh: '文本', en: 'Text' })}</option>
              <option value="number">{text({ zh: '数字', en: 'Number' })}</option>
              <option value="date">{text({ zh: '日期', en: 'Date' })}</option>
              <option value="date-range">{text({ zh: '日期范围', en: 'Date Range' })}</option>
              <option value="select">{text({ zh: '选择', en: 'Select' })}</option>
            </select>
            <label className="flex items-center gap-1 text-xs">
              <input
                type="checkbox"
                checked={param.required ?? false}
                onChange={(e) => handleUpdate(idx, { required: e.target.checked })}
                className="h-3 w-3 rounded border-gray-300 text-blue-600"
              />
              {text({ zh: '必填', en: 'Req' })}
            </label>
          </div>
          {/* Bind to data source filter */}
          {dsKeys.length > 0 && (
            <div className="space-y-1">
              <label className="text-[10px] text-gray-500 uppercase">{text({ zh: '绑定筛选条件', en: 'Bind to filter' })}</label>
              <div className="flex gap-1">
                <select
                  value={param.bindTo?.dataSource || ''}
                  onChange={(e) =>
                    handleUpdate(idx, {
                      bindTo: e.target.value
                        ? {
                            dataSource: e.target.value,
                            field: param.bindTo?.field || param.name,
                            operator: param.bindTo?.operator || 'EQ',
                          }
                        : undefined,
                    })
                  }
                  className="flex-1 rounded border border-gray-300 px-1 py-0.5 text-[10px]"
                >
                  <option value="">{text({ zh: '无', en: 'None' })}</option>
                  {dsKeys.map((k) => (
                    <option key={k} value={k}>
                      {k}
                    </option>
                  ))}
                </select>
                {param.bindTo && (
                  <>
                    <input
                      type="text"
                      value={param.bindTo.field}
                      onChange={(e) =>
                        handleUpdate(idx, { bindTo: { ...param.bindTo!, field: e.target.value } })
                      }
                      className="flex-1 rounded border border-gray-300 px-1 py-0.5 text-[10px]"
                      placeholder={text({ zh: '字段', en: 'field' })}
                    />
                    <select
                      value={param.bindTo.operator}
                      onChange={(e) =>
                        handleUpdate(idx, {
                          bindTo: { ...param.bindTo!, operator: e.target.value },
                        })
                      }
                      className="rounded border border-gray-300 px-1 py-0.5 text-[10px]"
                    >
                      <option value="EQ">=</option>
                      <option value="gte">≥</option>
                      <option value="lte">≤</option>
                      <option value="like">{text({ zh: '包含', en: 'LIKE' })}</option>
                    </select>
                  </>
                )}
              </div>
            </div>
          )}
        </div>
      ))}

      {showAdd && (
        <div className="space-y-2 rounded-md bg-blue-50 p-3">
          <input
            type="text"
            value={newName}
            onChange={(e) => setNewName(e.target.value)}
            placeholder={text({ zh: '参数名', en: 'Parameter name' })}
            className="w-full rounded border border-gray-300 px-2 py-1 text-xs"
          />
          <input
            type="text"
            value={newLabel}
            onChange={(e) => setNewLabel(e.target.value)}
            placeholder={text({ zh: '显示名称', en: 'Display label' })}
            className="w-full rounded border border-gray-300 px-2 py-1 text-xs"
          />
          <select
            value={newType}
            onChange={(e) => setNewType(e.target.value as ParameterType)}
            className="w-full rounded border border-gray-300 px-2 py-1 text-xs"
          >
            <option value="text">{text({ zh: '文本', en: 'Text' })}</option>
            <option value="number">{text({ zh: '数字', en: 'Number' })}</option>
            <option value="date">{text({ zh: '日期', en: 'Date' })}</option>
            <option value="date-range">{text({ zh: '日期范围', en: 'Date Range' })}</option>
            <option value="select">{text({ zh: '选择', en: 'Select' })}</option>
          </select>
          <div className="flex gap-2">
            <button
              onClick={handleAdd}
              className="rounded bg-blue-600 px-3 py-1 text-xs text-white hover:bg-blue-700"
            >
              {text({ zh: '添加', en: 'Add' })}
            </button>
            <button
              onClick={() => setShowAdd(false)}
              className="rounded border border-gray-300 bg-white px-3 py-1 text-xs text-gray-600 hover:bg-gray-50"
            >
              {text({ zh: '取消', en: 'Cancel' })}
            </button>
          </div>
        </div>
      )}

      {parameters.length === 0 && !showAdd && (
        <p className="text-xs text-gray-400">
          {text({ zh: '暂无参数。添加参数以供运行时筛选报表数据。', en: 'No parameters. Add parameters to let users filter report data at runtime.' })}
        </p>
      )}
    </div>
  );
};
