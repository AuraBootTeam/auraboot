/**
 * B2b batch3 — FormBindingSection
 *
 * Extracted from bpmn-designer/components/property-editors/shared.tsx (lines
 * 147-330). V1 semantics: operates on bindings[0] only (single form per node).
 *
 * Dependencies on core-bpm components (`PagePickerSelect`,
 * `VariableMappingEditor`, `FieldPermissionMatrix`) are imported directly
 * rather than re-ported — they are mature, app-scoped UI helpers that this
 * section composes. Re-porting them would duplicate code with no benefit.
 *
 * Hosting editor wires this section by translating G2 NodePropertyEditorProps
 * `(config, onChange)` into a (bindings, onChange(bindings)) prop pair.
 */

import { useState } from 'react';
import type { FormBindingEntry } from '~/plugins/core-designer/components/bpmn-designer/types';
import { useI18n } from '~/contexts/I18nContext';
import { PagePickerSelect } from '~/plugins/core-bpm/components/PagePickerSelect';
import { VariableMappingEditor } from '~/plugins/core-bpm/components/VariableMappingEditor';
import { FieldPermissionMatrix } from '~/plugins/core-bpm/components/FieldPermissionMatrix';

export interface FormBindingSectionProps {
  bindings: FormBindingEntry[];
  onChange: (bindings: FormBindingEntry[]) => void;
}

export function FormBindingSection({ bindings, onChange }: FormBindingSectionProps) {
  const { t } = useI18n();
  const [expanded, setExpanded] = useState(bindings.length > 0);
  const [mappingExpanded, setMappingExpanded] = useState(false);
  const [permissionExpanded, setPermissionExpanded] = useState(false);

  // V1: operate on bindings[0] only
  const binding: FormBindingEntry = bindings[0] || {
    formRef: '',
    formType: 'page',
    saveStrategy: 'business_only',
    versionStrategy: 'latest',
    permissionMode: 'merge',
    builtinVariables: { decision: 'decision', comment: 'comment' },
  };

  const updateBinding = (patch: Partial<FormBindingEntry>) => {
    const updated: FormBindingEntry = { ...binding, ...patch };
    // If formRef is cleared, remove the binding entirely
    if (!updated.formRef) {
      onChange([]);
    } else {
      onChange([updated]);
    }
  };

  const hasFormRef = Boolean(binding.formRef);

  return (
    <div
      className="mb-4 rounded-md border border-gray-200"
      data-testid="bpm-sdk-form-binding-section"
    >
      <button
        type="button"
        onClick={() => setExpanded(!expanded)}
        className="flex w-full items-center justify-between px-3 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50"
        data-testid="form-binding-toggle"
      >
        <span>
          {t('bpmn.prop.formBinding.title')} {hasFormRef ? '(1)' : ''}
        </span>
        <span className="text-gray-400">{expanded ? '▾' : '▸'}</span>
      </button>
      {expanded && (
        <div className="space-y-3 px-3 pb-3">
          {/* Page Picker */}
          <div>
            <label className="mb-1 block text-xs font-medium text-gray-600">
              {t('bpmn.prop.formBinding.formPage')}
            </label>
            <PagePickerSelect
              value={binding.formRef || ''}
              onChange={(pageKey) =>
                updateBinding({
                  formRef: pageKey,
                  formType: 'page',
                  saveStrategy: binding.saveStrategy || 'business_only',
                  versionStrategy: binding.versionStrategy || 'latest',
                  permissionMode: binding.permissionMode || 'merge',
                  builtinVariables: binding.builtinVariables || {
                    decision: 'decision',
                    comment: 'comment',
                  },
                })
              }
            />
          </div>

          {hasFormRef && (
            <>
              {/* Save Strategy */}
              <div>
                <label className="mb-1 block text-xs font-medium text-gray-600">
                  {t('bpmn.prop.formBinding.saveStrategy')}
                </label>
                <select
                  value={binding.saveStrategy || 'business_only'}
                  onChange={(e) =>
                    updateBinding({
                      saveStrategy: e.target.value as FormBindingEntry['saveStrategy'],
                    })
                  }
                  className="w-full rounded-md border border-gray-300 px-2 py-1.5 text-sm"
                  data-testid="bpm-sdk-form-binding-save-strategy"
                >
                  <option value="business_only">
                    {t('bpmn.prop.formBinding.saveBusinessOnly')}
                  </option>
                  <option value="dual_write">{t('bpmn.prop.formBinding.saveDualWrite')}</option>
                  <option value="variable_only">
                    {t('bpmn.prop.formBinding.saveVariableOnly')}
                  </option>
                </select>
              </div>

              {/* Version Strategy */}
              <div>
                <label className="mb-1 block text-xs font-medium text-gray-600">
                  {t('bpmn.prop.formBinding.versionStrategy')}
                </label>
                <div className="flex gap-4">
                  <label className="flex items-center text-sm text-gray-700">
                    <input
                      type="radio"
                      name="versionStrategy"
                      checked={binding.versionStrategy !== 'fixed'}
                      onChange={() =>
                        updateBinding({ versionStrategy: 'latest', fixedVersion: undefined })
                      }
                      className="mr-1.5"
                    />
                    {t('bpmn.prop.formBinding.versionLatest')}
                  </label>
                  <label className="flex items-center text-sm text-gray-700">
                    <input
                      type="radio"
                      name="versionStrategy"
                      checked={binding.versionStrategy === 'fixed'}
                      onChange={() => updateBinding({ versionStrategy: 'fixed' })}
                      className="mr-1.5"
                    />
                    {t('bpmn.prop.formBinding.versionFixed')}
                  </label>
                </div>
                {binding.versionStrategy === 'fixed' && (
                  <input
                    type="number"
                    value={binding.fixedVersion ?? ''}
                    onChange={(e) =>
                      updateBinding({
                        fixedVersion: e.target.value ? parseInt(e.target.value) : undefined,
                      })
                    }
                    className="mt-1 w-full rounded border border-gray-300 px-2 py-1 text-sm"
                    placeholder={t('bpmn.prop.formBinding.versionPlaceholder')}
                    min="1"
                  />
                )}
              </div>

              {/* Variable Mapping (collapsible) */}
              <div className="rounded border border-gray-100">
                <button
                  type="button"
                  onClick={() => setMappingExpanded(!mappingExpanded)}
                  className="flex w-full items-center justify-between px-2 py-1.5 text-xs font-medium text-gray-600 hover:bg-gray-50"
                >
                  <span>{t('bpmn.prop.formBinding.variableMapping')}</span>
                  <span className="text-gray-400">{mappingExpanded ? '▾' : '▸'}</span>
                </button>
                {mappingExpanded && (
                  <div className="px-2 pb-2">
                    <VariableMappingEditor
                      pageKey={binding.formRef}
                      bindings={binding.variableBindings || {}}
                      onChange={(variableBindings) => updateBinding({ variableBindings })}
                    />
                  </div>
                )}
              </div>

              {/* Field Permissions (collapsible) */}
              <div className="rounded border border-gray-100">
                <button
                  type="button"
                  onClick={() => setPermissionExpanded(!permissionExpanded)}
                  className="flex w-full items-center justify-between px-2 py-1.5 text-xs font-medium text-gray-600 hover:bg-gray-50"
                >
                  <span>{t('bpmn.prop.formBinding.fieldPermissions')}</span>
                  <span className="text-gray-400">{permissionExpanded ? '▾' : '▸'}</span>
                </button>
                {permissionExpanded && (
                  <div className="px-2 pb-2">
                    <FieldPermissionMatrix
                      pageKey={binding.formRef}
                      permissions={binding.fieldPermissions || {}}
                      onChange={(fieldPermissions) => updateBinding({ fieldPermissions })}
                    />
                  </div>
                )}
              </div>
            </>
          )}
        </div>
      )}
    </div>
  );
}
