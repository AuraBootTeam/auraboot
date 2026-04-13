/**
 * Property editor for CallActivity nodes.
 * Supports process picker, version selection, and input/output variable mapping.
 */

import { useState } from 'react';
import type { CallActivityConfig } from '~/plugins/core-designer/components/bpmn-designer/types';
import { useI18n } from '~/contexts/I18nContext';
import { ProcessPicker } from './ProcessPicker';

interface VariableMappingRow {
  source: string;
  target: string;
}

function VariableMappingTable({
  label,
  mappings,
  onChange,
  sourcePlaceholder,
  targetPlaceholder,
}: {
  label: string;
  mappings: Record<string, string>;
  onChange: (mappings: Record<string, string>) => void;
  sourcePlaceholder: string;
  targetPlaceholder: string;
}) {
  const { t } = useI18n();
  const rows: VariableMappingRow[] = Object.entries(mappings).map(([source, target]) => ({
    source,
    target,
  }));

  const updateRow = (index: number, field: 'source' | 'target', value: string) => {
    const updated = [...rows];
    updated[index] = { ...updated[index], [field]: value };
    const result: Record<string, string> = {};
    updated.forEach((r) => {
      if (r.source) result[r.source] = r.target;
    });
    onChange(result);
  };

  const addRow = () => {
    const result = { ...mappings, '': '' };
    onChange(result);
  };

  const removeRow = (index: number) => {
    const updated = [...rows];
    updated.splice(index, 1);
    const result: Record<string, string> = {};
    updated.forEach((r) => {
      if (r.source) result[r.source] = r.target;
    });
    onChange(result);
  };

  return (
    <div className="mb-3">
      <label className="mb-1 block text-xs font-medium text-gray-600">{label}</label>
      {rows.length > 0 && (
        <div className="space-y-1">
          {rows.map((row, index) => (
            <div key={index} className="flex items-center gap-1">
              <input
                type="text"
                value={row.source}
                onChange={(e) => updateRow(index, 'source', e.target.value)}
                className="w-[40%] rounded border border-gray-300 px-1.5 py-1 text-xs"
                placeholder={sourcePlaceholder}
              />
              <span className="text-xs text-gray-400">&rarr;</span>
              <input
                type="text"
                value={row.target}
                onChange={(e) => updateRow(index, 'target', e.target.value)}
                className="w-[40%] rounded border border-gray-300 px-1.5 py-1 text-xs"
                placeholder={targetPlaceholder}
              />
              <button
                type="button"
                onClick={() => removeRow(index)}
                className="text-xs text-red-400 hover:text-red-600"
              >
                &times;
              </button>
            </div>
          ))}
        </div>
      )}
      <button
        type="button"
        onClick={addRow}
        className="mt-1 text-xs text-blue-600 hover:text-blue-800"
      >
        {t('bpmn.callactivity.addMapping')}
      </button>
    </div>
  );
}

export function CallActivityEditor({
  config,
  onChange,
}: {
  config?: CallActivityConfig;
  onChange: (config: CallActivityConfig) => void;
}) {
  const { t } = useI18n();
  const [mappingExpanded, setMappingExpanded] = useState(
    Boolean(
      config?.inputMappings && Object.keys(config.inputMappings).length > 0 ||
      config?.outputMappings && Object.keys(config.outputMappings).length > 0,
    ),
  );

  const handleChange = (field: keyof CallActivityConfig, value: any) => {
    onChange({
      ...config,
      calledProcessKey: config?.calledProcessKey || '',
      [field]: value,
    } as CallActivityConfig);
  };

  return (
    <>
      <div className="mb-4">
        <label className="mb-1 block text-sm font-medium text-gray-700">{t('bpmn.common.description')}</label>
        <textarea
          value={config?.description || ''}
          onChange={(e) => handleChange('description', e.target.value)}
          className="w-full rounded-md border border-gray-300 px-3 py-2"
          rows={2}
        />
      </div>

      <div className="mb-4">
        <label className="mb-1 block text-sm font-medium text-gray-700">{t('bpmn.callactivity.calledProcess')}</label>
        <ProcessPicker
          value={config?.calledProcessKey || ''}
          onChange={(processKey) => handleChange('calledProcessKey', processKey)}
          placeholder={t('bpmn.callactivity.selectProcess')}
        />
      </div>

      <div className="mb-4">
        <label className="mb-1 block text-sm font-medium text-gray-700">{t('bpmn.callactivity.calledProcessVersion')}</label>
        <select
          value={config?.calledProcessVersion || 'latest'}
          onChange={(e) => handleChange('calledProcessVersion', e.target.value)}
          className="w-full rounded-md border border-gray-300 px-3 py-2"
        >
          <option value="latest">{t('bpmn.callactivity.versionLatest')}</option>
          <option value="fixed">{t('bpmn.callactivity.versionFixed')}</option>
        </select>
        {config?.calledProcessVersion === 'fixed' && (
          <input
            type="text"
            value={config?.calledProcessVersion || ''}
            onChange={(e) => handleChange('calledProcessVersion', e.target.value)}
            className="mt-1 w-full rounded-md border border-gray-300 px-3 py-2 text-sm"
            placeholder={t('bpmn.callactivity.versionPlaceholder')}
          />
        )}
      </div>

      {/* Variable Mapping */}
      <div className="mb-4 rounded-md border border-gray-200">
        <button
          type="button"
          onClick={() => setMappingExpanded(!mappingExpanded)}
          className="flex w-full items-center justify-between px-3 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50"
        >
          <span>{t('bpmn.callactivity.variableMapping')}</span>
          <span className="text-gray-400">{mappingExpanded ? '▾' : '▸'}</span>
        </button>
        {mappingExpanded && (
          <div className="px-3 pb-3">
            <VariableMappingTable
              label={t('bpmn.callactivity.inputMapping')}
              mappings={config?.inputMappings || {}}
              onChange={(inputMappings) => handleChange('inputMappings', inputMappings)}
              sourcePlaceholder={t('bpmn.callactivity.parentVariable')}
              targetPlaceholder={t('bpmn.callactivity.childVariable')}
            />
            <VariableMappingTable
              label={t('bpmn.callactivity.outputMapping')}
              mappings={config?.outputMappings || {}}
              onChange={(outputMappings) => handleChange('outputMappings', outputMappings)}
              sourcePlaceholder={t('bpmn.callactivity.childVariable')}
              targetPlaceholder={t('bpmn.callactivity.parentVariable')}
            />
          </div>
        )}
      </div>
    </>
  );
}
