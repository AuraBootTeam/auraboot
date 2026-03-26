/**
 * Shared sub-components used by multiple BPMN property editors.
 */

import { useState } from 'react';
import type { MultiInstanceConfig, FormBindingEntry, NodeHookEntry } from '~/bpmn-designer/types';
import { PagePickerSelect } from '~/bpm/components/PagePickerSelect';
import { VariableMappingEditor } from '~/bpm/components/VariableMappingEditor';
import { FieldPermissionMatrix } from '~/bpm/components/FieldPermissionMatrix';

// Multi-instance configuration section
export function MultiInstanceSection({
  config,
  onChange,
}: {
  config?: MultiInstanceConfig;
  onChange: (config: MultiInstanceConfig) => void;
}) {
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
        <span>多实例配置</span>
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
              <span className="text-sm font-medium text-gray-700">启用多实例</span>
            </label>
          </div>

          {config?.enabled && (
            <>
              <div className="mb-3">
                <label className="mb-1 block text-sm font-medium text-gray-700">执行方式</label>
                <div className="flex gap-4">
                  <label className="flex items-center">
                    <input
                      type="radio"
                      name="multiInstanceMode"
                      checked={config?.sequential === true}
                      onChange={() => handleChange('sequential', true)}
                      className="mr-1"
                    />
                    <span className="text-sm text-gray-700">串行</span>
                  </label>
                  <label className="flex items-center">
                    <input
                      type="radio"
                      name="multiInstanceMode"
                      checked={config?.sequential === false}
                      onChange={() => handleChange('sequential', false)}
                      className="mr-1"
                    />
                    <span className="text-sm text-gray-700">并行</span>
                  </label>
                </div>
              </div>

              <div className="mb-3">
                <label className="mb-1 block text-sm font-medium text-gray-700">集合变量</label>
                <input
                  type="text"
                  value={config?.collection || ''}
                  onChange={(e) => handleChange('collection', e.target.value)}
                  className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm"
                  placeholder="${assigneeList}"
                />
              </div>

              <div className="mb-3">
                <label className="mb-1 block text-sm font-medium text-gray-700">元素变量</label>
                <input
                  type="text"
                  value={config?.elementVariable || ''}
                  onChange={(e) => handleChange('elementVariable', e.target.value)}
                  className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm"
                  placeholder="assignee"
                />
              </div>

              <div className="mb-3">
                <label className="mb-1 block text-sm font-medium text-gray-700">完成条件</label>
                <textarea
                  value={config?.completionCondition || ''}
                  onChange={(e) => handleChange('completionCondition', e.target.value)}
                  className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm"
                  rows={2}
                  placeholder="${nrOfCompletedInstances/nrOfInstances >= 0.5}"
                />
              </div>

              <div className="mb-3">
                <label className="mb-1 block text-sm font-medium text-gray-700">循环基数</label>
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
                  placeholder="留空则使用集合变量"
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
        {/* TODO: i18n */}
        <span>Form Binding {hasFormRef ? '(1)' : ''}</span>
        <span className="text-gray-400">{expanded ? '▾' : '▸'}</span>
      </button>
      {expanded && (
        <div className="space-y-3 px-3 pb-3">
          {/* Page Picker */}
          <div>
            {/* TODO: i18n */}
            <label className="mb-1 block text-xs font-medium text-gray-600">Form Page</label>
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
                {/* TODO: i18n */}
                <label className="mb-1 block text-xs font-medium text-gray-600">
                  Save Strategy
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
                  {/* TODO: i18n */}
                  <option value="business_only">Business Only</option>
                  <option value="dual_write">Dual Write (Business + Variables)</option>
                  <option value="variable_only">Variable Only</option>
                </select>
              </div>

              {/* Version Strategy */}
              <div>
                {/* TODO: i18n */}
                <label className="mb-1 block text-xs font-medium text-gray-600">
                  Version Strategy
                </label>
                <div className="flex gap-4">
                  <label className="flex items-center text-sm text-gray-700">
                    <input
                      type="radio"
                      name="versionStrategy"
                      checked={binding.versionStrategy !== 'fixed'}
                      onChange={() => updateBinding({ versionStrategy: 'latest', fixedVersion: undefined })}
                      className="mr-1.5"
                    />
                    {/* TODO: i18n */}
                    Latest
                  </label>
                  <label className="flex items-center text-sm text-gray-700">
                    <input
                      type="radio"
                      name="versionStrategy"
                      checked={binding.versionStrategy === 'fixed'}
                      onChange={() => updateBinding({ versionStrategy: 'fixed' })}
                      className="mr-1.5"
                    />
                    {/* TODO: i18n */}
                    Fixed
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
                    placeholder="Version number" // TODO: i18n
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
                  {/* TODO: i18n */}
                  <span>Variable Mapping</span>
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
                  {/* TODO: i18n */}
                  <span>Field Permissions</span>
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

// Hook configuration section
export function HookConfigSection({
  hooks,
  onChange,
}: {
  hooks: NodeHookEntry[];
  onChange: (hooks: NodeHookEntry[]) => void;
}) {
  const [expanded, setExpanded] = useState(hooks.length > 0);

  const addHook = () => {
    onChange([
      ...hooks,
      {
        hookType: 'pre_execute',
        executionOrder: hooks.length,
        hookConfig: {},
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

  return (
    <div className="mb-4 rounded-md border border-gray-200">
      <button
        type="button"
        onClick={() => setExpanded(!expanded)}
        className="flex w-full items-center justify-between px-3 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50"
      >
        <span>节点钩子 ({hooks.length})</span>
        <span className="text-gray-400">{expanded ? '▾' : '▸'}</span>
      </button>
      {expanded && (
        <div className="px-3 pb-3">
          {hooks.map((hook, index) => (
            <div key={index} className="mb-3 rounded border border-gray-100 bg-gray-50 p-2">
              <div className="mb-2 flex items-center justify-between">
                <span className="text-xs font-medium text-gray-500">钩子 #{index + 1}</span>
                <button
                  type="button"
                  onClick={() => removeHook(index)}
                  className="text-xs text-red-500 hover:text-red-700"
                >
                  移除
                </button>
              </div>
              <div className="mb-2">
                <label className="mb-1 block text-xs text-gray-600">钩子类型</label>
                <select
                  value={hook.hookType}
                  onChange={(e) => updateHook(index, 'hookType', e.target.value)}
                  className="w-full rounded border border-gray-300 px-2 py-1 text-sm"
                >
                  <option value="pre_execute">执行前</option>
                  <option value="post_execute">执行后</option>
                  <option value="pre_complete">完成前</option>
                  <option value="post_complete">完成后</option>
                </select>
              </div>
              <div className="mb-2">
                <label className="mb-1 block text-xs text-gray-600">失败策略</label>
                <select
                  value={hook.failStrategy || 'block'}
                  onChange={(e) => updateHook(index, 'failStrategy', e.target.value)}
                  className="w-full rounded border border-gray-300 px-2 py-1 text-sm"
                >
                  <option value="block">阻断</option>
                  <option value="ignore">忽略</option>
                  <option value="retry">重试</option>
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
                  <span className="text-xs text-gray-600">异步</span>
                </label>
                <label className="flex items-center">
                  <input
                    type="checkbox"
                    checked={hook.enabled !== false}
                    onChange={(e) => updateHook(index, 'enabled', e.target.checked)}
                    className="mr-1"
                  />
                  <span className="text-xs text-gray-600">启用</span>
                </label>
              </div>
              <div className="mb-2">
                <label className="mb-1 block text-xs text-gray-600">执行顺序</label>
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
            + 添加钩子
          </button>
        </div>
      )}
    </div>
  );
}
