/**
 * B2b batch3 — MultiInstanceSection
 *
 * Extracted from bpmn-designer/components/property-editors/shared.tsx (627 LOC,
 * see report §8 split rationale). This sub-section is a single-purpose,
 * controlled component that operates on a partial slice of a node config —
 * specifically `config.multiInstance: MultiInstanceConfig`. Hosting editors
 * (UserTaskEditor in batch3 / future batches) wire it up by translating the
 * G2 NodePropertyEditorProps `(config, onChange)` patch contract into a
 * (multiInstance, onChange(multiInstance)) prop pair.
 *
 * Behaviour byte-equivalent to the legacy shared.tsx implementation — same
 * data-testids, same i18n keys, same default values, same collapsible UX.
 */

import { useState } from 'react';
import type { MultiInstanceConfig } from '~/plugins/core-designer/components/bpmn-designer/types';
import { useI18n } from '~/contexts/I18nContext';

export interface MultiInstanceSectionProps {
  config?: MultiInstanceConfig;
  onChange: (config: MultiInstanceConfig) => void;
}

export function MultiInstanceSection({ config, onChange }: MultiInstanceSectionProps) {
  const { t } = useI18n();
  const [expanded, setExpanded] = useState(config?.enabled || false);

  const handleChange = (field: keyof MultiInstanceConfig, value: any) => {
    onChange({
      enabled: config?.enabled || false,
      sequential: config?.sequential || false,
      ...config,
      [field]: value,
    } as MultiInstanceConfig);
  };

  return (
    <div className="mb-4 rounded-md border border-gray-200" data-testid="bpm-sdk-mi-section">
      <button
        type="button"
        onClick={() => setExpanded(!expanded)}
        className="flex w-full items-center justify-between px-3 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50"
        data-testid="bpm-sdk-mi-toggle"
      >
        <span>{t('bpmn.prop.multiInstance.title')}</span>
        <span className="text-gray-400">{expanded ? '▾' : '▸'}</span>
      </button>
      {expanded && (
        <div className="px-3 pb-3">
          <div className="mb-3">
            <label className="flex items-center">
              <input
                type="checkbox"
                checked={config?.enabled || false}
                onChange={(e) => handleChange('enabled', e.target.checked)}
                className="mr-2"
                data-testid="multiinstance-enabled"
              />
              <span className="text-sm font-medium text-gray-700">
                {t('bpmn.prop.multiInstance.enable')}
              </span>
            </label>
          </div>

          {config?.enabled && (
            <>
              <div className="mb-3">
                <label className="mb-1 block text-sm font-medium text-gray-700">
                  {t('bpmn.prop.multiInstance.executionMode')}
                </label>
                <div className="flex gap-4">
                  <label className="flex items-center">
                    <input
                      type="radio"
                      name="multiInstanceMode"
                      checked={config?.sequential === true}
                      onChange={() => handleChange('sequential', true)}
                      className="mr-1"
                      data-testid="multiinstance-sequential"
                    />
                    <span className="text-sm text-gray-700">
                      {t('bpmn.prop.multiInstance.sequential')}
                    </span>
                  </label>
                  <label className="flex items-center">
                    <input
                      type="radio"
                      name="multiInstanceMode"
                      checked={config?.sequential === false}
                      onChange={() => handleChange('sequential', false)}
                      className="mr-1"
                    />
                    <span className="text-sm text-gray-700">
                      {t('bpmn.prop.multiInstance.parallel')}
                    </span>
                  </label>
                </div>
              </div>

              <div className="mb-3">
                <label className="mb-1 block text-sm font-medium text-gray-700">
                  {t('bpmn.prop.multiInstance.collection')}
                </label>
                <input
                  type="text"
                  value={config?.collection || ''}
                  onChange={(e) => handleChange('collection', e.target.value)}
                  className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm"
                  placeholder="${assigneeList}"
                  data-testid="multiinstance-collection"
                />
              </div>

              <div className="mb-3">
                <label className="mb-1 block text-sm font-medium text-gray-700">
                  {t('bpmn.prop.multiInstance.elementVariable')}
                </label>
                <input
                  type="text"
                  value={config?.elementVariable || ''}
                  onChange={(e) => handleChange('elementVariable', e.target.value)}
                  className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm"
                  placeholder="assignee"
                  data-testid="multiinstance-element-variable"
                />
              </div>

              <div className="mb-3">
                <label className="mb-1 block text-sm font-medium text-gray-700">
                  {t('bpmn.prop.multiInstance.completionCondition')}
                </label>
                <textarea
                  value={config?.completionCondition || ''}
                  onChange={(e) => handleChange('completionCondition', e.target.value)}
                  className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm"
                  rows={2}
                  placeholder="${nrOfCompletedInstances/nrOfInstances >= 0.5}"
                  data-testid="multiinstance-completion-condition"
                />
              </div>

              <div className="mb-3">
                <label className="mb-1 block text-sm font-medium text-gray-700">
                  {t('bpmn.prop.multiInstance.loopCardinality')}
                </label>
                <input
                  type="number"
                  value={config?.loopCardinality ?? ''}
                  onChange={(e) =>
                    handleChange(
                      'loopCardinality',
                      e.target.value ? parseInt(e.target.value) : undefined,
                    )
                  }
                  className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm"
                  min="1"
                  placeholder={t('bpmn.prop.multiInstance.loopCardinalityPlaceholder')}
                  data-testid="multiinstance-cardinality"
                />
              </div>
            </>
          )}
        </div>
      )}
    </div>
  );
}
