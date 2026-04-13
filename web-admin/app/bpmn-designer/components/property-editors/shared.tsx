/**
 * Shared sub-components used by multiple BPMN property editors.
 */

import { useState } from 'react';
import type { MultiInstanceConfig, FormBindingEntry, NodeHookEntry } from '~/bpmn-designer/types';
import { useI18n } from '~/contexts/I18nContext';
import { PagePickerSelect } from '~/plugins/core-bpm/components/PagePickerSelect';
import { VariableMappingEditor } from '~/plugins/core-bpm/components/VariableMappingEditor';
import { FieldPermissionMatrix } from '~/plugins/core-bpm/components/FieldPermissionMatrix';

// Multi-instance configuration section
export function MultiInstanceSection({
  config,
  onChange,
}: {
  config?: MultiInstanceConfig;
  onChange: (config: MultiInstanceConfig) => void;
}) {
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
    <div className="mb-4 rounded-md border border-gray-200">
      <button
        type="button"
        onClick={() => setExpanded(!expanded)}
        className="flex w-full items-center justify-between px-3 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50"
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
              />
              <span className="text-sm font-medium text-gray-700">{t('bpmn.prop.multiInstance.enable')}</span>
            </label>
          </div>

          {config?.enabled && (
            <>
              <div className="mb-3">
                <label className="mb-1 block text-sm font-medium text-gray-700">{t('bpmn.prop.multiInstance.executionMode')}</label>
                <div className="flex gap-4">
                  <label className="flex items-center">
                    <input
                      type="radio"
                      name="multiInstanceMode"
                      checked={config?.sequential === true}
                      onChange={() => handleChange('sequential', true)}
                      className="mr-1"
                    />
                    <span className="text-sm text-gray-700">{t('bpmn.prop.multiInstance.sequential')}</span>
                  </label>
                  <label className="flex items-center">
                    <input
                      type="radio"
                      name="multiInstanceMode"
                      checked={config?.sequential === false}
                      onChange={() => handleChange('sequential', false)}
                      className="mr-1"
                    />
                    <span className="text-sm text-gray-700">{t('bpmn.prop.multiInstance.parallel')}</span>
                  </label>
                </div>
              </div>

              <div className="mb-3">
                <label className="mb-1 block text-sm font-medium text-gray-700">{t('bpmn.prop.multiInstance.collection')}</label>
                <input
                  type="text"
                  value={config?.collection || ''}
                  onChange={(e) => handleChange('collection', e.target.value)}
                  className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm"
                  placeholder="${assigneeList}"
                />
              </div>

              <div className="mb-3">
                <label className="mb-1 block text-sm font-medium text-gray-700">{t('bpmn.prop.multiInstance.elementVariable')}</label>
                <input
                  type="text"
                  value={config?.elementVariable || ''}
                  onChange={(e) => handleChange('elementVariable', e.target.value)}
                  className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm"
                  placeholder="assignee"
                />
              </div>

              <div className="mb-3">
                <label className="mb-1 block text-sm font-medium text-gray-700">{t('bpmn.prop.multiInstance.completionCondition')}</label>
                <textarea
                  value={config?.completionCondition || ''}
                  onChange={(e) => handleChange('completionCondition', e.target.value)}
                  className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm"
                  rows={2}
                  placeholder="${nrOfCompletedInstances/nrOfInstances >= 0.5}"
                />
              </div>

              <div className="mb-3">
                <label className="mb-1 block text-sm font-medium text-gray-700">{t('bpmn.prop.multiInstance.loopCardinality')}</label>
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
                />
              </div>
            </>
          )}
        </div>
      )}
    </div>
  );
}

// Form binding configuration section (V1: single form per node)
export function FormBindingSection({
  bindings,
  onChange,
}: {
  bindings: FormBindingEntry[];
  onChange: (bindings: FormBindingEntry[]) => void;
}) {
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
    <div className="mb-4 rounded-md border border-gray-200">
      <button
        type="button"
        onClick={() => setExpanded(!expanded)}
        className="flex w-full items-center justify-between px-3 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50"
      >
        <span>{t('bpmn.prop.formBinding.title')} {hasFormRef ? '(1)' : ''}</span>
        <span className="text-gray-400">{expanded ? '▾' : '▸'}</span>
      </button>
      {expanded && (
        <div className="space-y-3 px-3 pb-3">
          {/* Page Picker */}
          <div>
            <label className="mb-1 block text-xs font-medium text-gray-600">{t('bpmn.prop.formBinding.formPage')}</label>
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
                >
                  <option value="business_only">{t('bpmn.prop.formBinding.saveBusinessOnly')}</option>
                  <option value="dual_write">{t('bpmn.prop.formBinding.saveDualWrite')}</option>
                  <option value="variable_only">{t('bpmn.prop.formBinding.saveVariableOnly')}</option>
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

// Hook action type configuration sub-components
function HttpCallbackConfig({
  config,
  onChange,
}: {
  config: Record<string, unknown>;
  onChange: (config: Record<string, unknown>) => void;
}) {
  const { t } = useI18n();
  return (
    <div className="mt-2 space-y-2 rounded border border-gray-200 bg-white p-2">
      <div>
        <label className="mb-0.5 block text-xs text-gray-500">{t('bpmn.hook.httpUrl')}</label>
        <input
          type="text"
          value={(config.url as string) || ''}
          onChange={(e) => onChange({ ...config, url: e.target.value })}
          className="w-full rounded border border-gray-300 px-2 py-1 text-sm"
          placeholder="https://example.com/webhook"
        />
      </div>
      <div>
        <label className="mb-0.5 block text-xs text-gray-500">{t('bpmn.hook.httpMethod')}</label>
        <select
          value={(config.method as string) || 'POST'}
          onChange={(e) => onChange({ ...config, method: e.target.value })}
          className="w-full rounded border border-gray-300 px-2 py-1 text-sm"
        >
          <option value="POST">POST</option>
          <option value="GET">GET</option>
          <option value="PUT">PUT</option>
        </select>
      </div>
      <div>
        <label className="mb-0.5 block text-xs text-gray-500">{t('bpmn.hook.httpHeaders')}</label>
        <textarea
          value={(config.headers as string) || ''}
          onChange={(e) => onChange({ ...config, headers: e.target.value })}
          className="w-full rounded border border-gray-300 px-2 py-1 font-mono text-xs"
          rows={2}
          placeholder='{"Content-Type": "application/json"}'
        />
      </div>
    </div>
  );
}

function ScriptActionConfig({
  config,
  onChange,
}: {
  config: Record<string, unknown>;
  onChange: (config: Record<string, unknown>) => void;
}) {
  const { t } = useI18n();
  return (
    <div className="mt-2 space-y-2 rounded border border-gray-200 bg-white p-2">
      <div>
        <label className="mb-0.5 block text-xs text-gray-500">{t('bpmn.hook.scriptLanguage')}</label>
        <select
          value={(config.language as string) || 'javascript'}
          onChange={(e) => onChange({ ...config, language: e.target.value })}
          className="w-full rounded border border-gray-300 px-2 py-1 text-sm"
        >
          <option value="javascript">JavaScript</option>
          <option value="groovy">Groovy</option>
        </select>
      </div>
      <div>
        <label className="mb-0.5 block text-xs text-gray-500">{t('bpmn.hook.scriptContent')}</label>
        <textarea
          value={(config.script as string) || ''}
          onChange={(e) => onChange({ ...config, script: e.target.value })}
          className="w-full rounded border border-gray-300 px-2 py-1 font-mono text-xs"
          rows={4}
          placeholder="// your script here"
        />
      </div>
    </div>
  );
}

function CommandActionConfig({
  config,
  onChange,
}: {
  config: Record<string, unknown>;
  onChange: (config: Record<string, unknown>) => void;
}) {
  const { t } = useI18n();
  return (
    <div className="mt-2 space-y-2 rounded border border-gray-200 bg-white p-2">
      <div>
        <label className="mb-0.5 block text-xs text-gray-500">{t('bpmn.hook.commandCode')}</label>
        <input
          type="text"
          value={(config.commandCode as string) || ''}
          onChange={(e) => onChange({ ...config, commandCode: e.target.value })}
          className="w-full rounded border border-gray-300 px-2 py-1 text-sm"
          placeholder="namespace:command_code"
        />
      </div>
      <div>
        <label className="mb-0.5 block text-xs text-gray-500">{t('bpmn.hook.commandParams')}</label>
        <textarea
          value={(config.params as string) || ''}
          onChange={(e) => onChange({ ...config, params: e.target.value })}
          className="w-full rounded border border-gray-300 px-2 py-1 font-mono text-xs"
          rows={2}
          placeholder='{"key": "${variable}"}'
        />
      </div>
    </div>
  );
}

// Hook configuration section
export function HookConfigSection({
  hooks,
  onChange,
}: {
  hooks: NodeHookEntry[];
  onChange: (hooks: NodeHookEntry[]) => void;
}) {
  const { t } = useI18n();
  const [expanded, setExpanded] = useState(hooks.length > 0);

  const addHook = () => {
    onChange([
      ...hooks,
      {
        hookType: 'pre_execute',
        executionOrder: hooks.length,
        hookConfig: { actionType: 'http_callback' },
        failStrategy: 'block',
        async: false,
        enabled: true,
      },
    ]);
  };

  const removeHook = (index: number) => {
    onChange(hooks.filter((_, i) => i !== index));
  };

  const updateHook = (index: number, field: keyof NodeHookEntry, value: any) => {
    const updated = [...hooks];
    updated[index] = { ...updated[index], [field]: value };
    onChange(updated);
  };

  const updateHookConfig = (index: number, config: Record<string, unknown>) => {
    const updated = [...hooks];
    updated[index] = { ...updated[index], hookConfig: config };
    onChange(updated);
  };

  const getActionType = (hook: NodeHookEntry): string =>
    (hook.hookConfig?.actionType as string) || 'http_callback';

  return (
    <div className="mb-4 rounded-md border border-gray-200">
      <button
        type="button"
        onClick={() => setExpanded(!expanded)}
        className="flex w-full items-center justify-between px-3 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50"
      >
        <span>{t('bpmn.hook.title')} ({hooks.length})</span>
        <span className="text-gray-400">{expanded ? '▾' : '▸'}</span>
      </button>
      {expanded && (
        <div className="px-3 pb-3">
          {hooks.map((hook, index) => (
            <div key={index} className="mb-3 rounded border border-gray-100 bg-gray-50 p-2">
              <div className="mb-2 flex items-center justify-between">
                <span className="text-xs font-medium text-gray-500">{t('bpmn.hook.hookNumber')} #{index + 1}</span>
                <button
                  type="button"
                  onClick={() => removeHook(index)}
                  className="text-xs text-red-500 hover:text-red-700"
                >
                  {t('bpmn.common.remove')}
                </button>
              </div>
              <div className="mb-2">
                <label className="mb-1 block text-xs text-gray-600">{t('bpmn.hook.hookType')}</label>
                <select
                  value={hook.hookType}
                  onChange={(e) => updateHook(index, 'hookType', e.target.value)}
                  className="w-full rounded border border-gray-300 px-2 py-1 text-sm"
                >
                  <option value="pre_execute">{t('bpmn.hook.typePreExecute')}</option>
                  <option value="post_execute">{t('bpmn.hook.typePostExecute')}</option>
                  <option value="pre_complete">{t('bpmn.hook.typePreComplete')}</option>
                  <option value="post_complete">{t('bpmn.hook.typePostComplete')}</option>
                </select>
              </div>
              <div className="mb-2">
                <label className="mb-1 block text-xs text-gray-600">{t('bpmn.hook.actionType')}</label>
                <select
                  value={getActionType(hook)}
                  onChange={(e) =>
                    updateHookConfig(index, { actionType: e.target.value })
                  }
                  className="w-full rounded border border-gray-300 px-2 py-1 text-sm"
                >
                  <option value="http_callback">{t('bpmn.hook.actionHttpCallback')}</option>
                  <option value="script">{t('bpmn.hook.actionScript')}</option>
                  <option value="command">{t('bpmn.hook.actionCommand')}</option>
                </select>
              </div>

              {/* Action-type-specific config */}
              {getActionType(hook) === 'http_callback' && (
                <HttpCallbackConfig
                  config={hook.hookConfig}
                  onChange={(config) => updateHookConfig(index, { ...config, actionType: 'http_callback' })}
                />
              )}
              {getActionType(hook) === 'script' && (
                <ScriptActionConfig
                  config={hook.hookConfig}
                  onChange={(config) => updateHookConfig(index, { ...config, actionType: 'script' })}
                />
              )}
              {getActionType(hook) === 'command' && (
                <CommandActionConfig
                  config={hook.hookConfig}
                  onChange={(config) => updateHookConfig(index, { ...config, actionType: 'command' })}
                />
              )}

              <div className="mb-2">
                <label className="mb-1 block text-xs text-gray-600">{t('bpmn.hook.failStrategy')}</label>
                <select
                  value={hook.failStrategy || 'block'}
                  onChange={(e) => updateHook(index, 'failStrategy', e.target.value)}
                  className="w-full rounded border border-gray-300 px-2 py-1 text-sm"
                >
                  <option value="block">{t('bpmn.hook.failBlock')}</option>
                  <option value="ignore">{t('bpmn.hook.failIgnore')}</option>
                  <option value="retry">{t('bpmn.hook.failRetry')}</option>
                </select>
              </div>
              <div className="mb-2 flex items-center gap-4">
                <label className="flex items-center">
                  <input
                    type="checkbox"
                    checked={hook.async || false}
                    onChange={(e) => updateHook(index, 'async', e.target.checked)}
                    className="mr-1"
                  />
                  <span className="text-xs text-gray-600">{t('bpmn.hook.async')}</span>
                </label>
                <label className="flex items-center">
                  <input
                    type="checkbox"
                    checked={hook.enabled !== false}
                    onChange={(e) => updateHook(index, 'enabled', e.target.checked)}
                    className="mr-1"
                  />
                  <span className="text-xs text-gray-600">{t('bpmn.hook.enabled')}</span>
                </label>
              </div>
              <div className="mb-2">
                <label className="mb-1 block text-xs text-gray-600">{t('bpmn.hook.executionOrder')}</label>
                <input
                  type="number"
                  value={hook.executionOrder ?? index}
                  onChange={(e) =>
                    updateHook(index, 'executionOrder', parseInt(e.target.value) || 0)
                  }
                  className="w-full rounded border border-gray-300 px-2 py-1 text-sm"
                  min="0"
                />
              </div>
            </div>
          ))}
          <button
            type="button"
            onClick={addHook}
            className="w-full rounded border border-dashed border-blue-300 py-1 text-sm text-blue-600 hover:bg-blue-50 hover:text-blue-800"
          >
            {t('bpmn.hook.addHook')}
          </button>
        </div>
      )}
    </div>
  );
}
