import React, { useState } from 'react';
import type { ModelCapabilities } from '~/shared/hooks/useModelCapabilities';
import { SchemaBlockConfigPanel } from '../SchemaBlockConfigPanel';
import { customButtonSchemas } from './schema';
import type {
  ListViewModel,
  CustomButton,
  ToolbarPresetKey,
} from './mapper';

export interface ToolbarTabProps {
  vm: ListViewModel;
  setVm: (next: ListViewModel) => void;
  capabilities: ModelCapabilities | undefined;
  readonly?: boolean;
}

interface PresetDescriptor {
  key: ToolbarPresetKey;
  label: string;
  /** Capability gate: preset is disabled when this capability is false. */
  capability: keyof ModelCapabilities;
}

const PRESETS: PresetDescriptor[] = [
  { key: 'create', label: '新增', capability: 'create' },
  { key: 'export', label: '导出', capability: 'export' },
  { key: 'bulkDelete', label: '批量删除', capability: 'bulkDelete' },
];

export const ToolbarTab: React.FC<ToolbarTabProps> = ({
  vm,
  setVm,
  capabilities,
  readonly,
}) => {
  const [selectedBtnIdx, setSelectedBtnIdx] = useState<number | null>(null);

  if (!capabilities) {
    return <div className="text-sm text-gray-400">加载 capabilities 中...</div>;
  }

  const activePresets = new Set(vm.toolbar.presets);

  const togglePreset = (key: ToolbarPresetKey, allowed: boolean) => {
    if (readonly || !allowed) return;
    const next = activePresets.has(key)
      ? vm.toolbar.presets.filter((p) => p !== key)
      : [...vm.toolbar.presets, key];
    setVm({ ...vm, toolbar: { ...vm.toolbar, presets: next } });
  };

  const addCustomButton = () => {
    if (readonly) return;
    const next: CustomButton = { label: '', command: '' };
    const nextButtons = [...vm.toolbar.customButtons, next];
    setVm({ ...vm, toolbar: { ...vm.toolbar, customButtons: nextButtons } });
    setSelectedBtnIdx(nextButtons.length - 1);
  };

  const removeCustomButton = (idx: number) => {
    if (readonly) return;
    const nextButtons = vm.toolbar.customButtons.filter((_, i) => i !== idx);
    setVm({ ...vm, toolbar: { ...vm.toolbar, customButtons: nextButtons } });
    setSelectedBtnIdx(null);
  };

  const updateCustomButton = (idx: number, patch: Partial<CustomButton>) => {
    const nextButtons = vm.toolbar.customButtons.map((b, i) =>
      i === idx ? { ...b, ...patch } : b,
    );
    setVm({ ...vm, toolbar: { ...vm.toolbar, customButtons: nextButtons } });
  };

  const selected =
    selectedBtnIdx !== null ? vm.toolbar.customButtons[selectedBtnIdx] : null;

  return (
    <div data-testid="toolbar-tab">
      <h2 className="mb-4 text-lg font-medium">预设按钮</h2>
      <div className="mb-8 space-y-2">
        {PRESETS.map((preset) => {
          const allowed = !!capabilities[preset.capability];
          const active = activePresets.has(preset.key);
          return (
            <label
              key={preset.key}
              className={`flex items-center gap-3 text-sm ${
                allowed ? '' : 'opacity-50'
              }`}
            >
              <input
                type="checkbox"
                checked={active}
                onChange={() => togglePreset(preset.key, allowed)}
                disabled={readonly || !allowed}
                data-testid={`toolbar-preset-${preset.key}`}
              />
              <span className="font-medium">{preset.label}</span>
              {!allowed && (
                <span className="text-xs text-gray-400">
                  (capability.{String(preset.capability)} = false)
                </span>
              )}
            </label>
          );
        })}
      </div>

      <div className="mb-4 flex items-center justify-between">
        <h2 className="text-lg font-medium">自定义按钮</h2>
        <button
          type="button"
          onClick={addCustomButton}
          disabled={readonly}
          className="rounded bg-blue-500 px-3 py-1 text-sm text-white disabled:opacity-30"
          data-testid="toolbar-add-custom-button"
        >
          + 添加
        </button>
      </div>

      {vm.toolbar.customButtons.length === 0 ? (
        <p className="text-sm text-gray-500">尚未添加自定义按钮。</p>
      ) : (
        <ol className="mb-6 space-y-2">
          {vm.toolbar.customButtons.map((b, i) => (
            <li
              key={i}
              className={`flex items-center justify-between rounded border p-2 text-sm ${
                selectedBtnIdx === i ? 'border-blue-400 bg-blue-50' : 'border-gray-200'
              }`}
            >
              <button
                type="button"
                className="flex-1 text-left"
                onClick={() => setSelectedBtnIdx(selectedBtnIdx === i ? null : i)}
                data-testid={`toolbar-custom-item-${i}`}
              >
                {b.icon && <span className="mr-1">{b.icon}</span>}
                <span className="font-medium">{b.label || '(未命名)'}</span>
                {b.command && (
                  <span className="ml-2 text-xs text-gray-500">{b.command}</span>
                )}
              </button>
              <button
                type="button"
                onClick={() => removeCustomButton(i)}
                disabled={readonly}
                className="px-2 text-xs text-red-500 disabled:opacity-30"
                data-testid={`toolbar-custom-remove-${i}`}
              >
                删除
              </button>
            </li>
          ))}
        </ol>
      )}

      {selected && selectedBtnIdx !== null && (
        <section className="rounded border p-4" data-testid="toolbar-custom-editor">
          <h3 className="mb-3 text-sm font-medium text-gray-700">按钮属性</h3>
          <SchemaBlockConfigPanel
            schemas={customButtonSchemas}
            value={selected as unknown as Record<string, unknown>}
            onChange={(next) =>
              updateCustomButton(selectedBtnIdx, next as Partial<CustomButton>)
            }
            readonly={readonly}
          />
        </section>
      )}
    </div>
  );
};
