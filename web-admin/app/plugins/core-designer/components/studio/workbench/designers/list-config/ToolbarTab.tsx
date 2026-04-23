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
  loading?: boolean;
  capabilityError?: Error;
}

function sectionCardClasses(extra?: string): string {
  return ['rounded-3xl border border-slate-200 bg-slate-50/70 p-5', extra]
    .filter(Boolean)
    .join(' ');
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
  loading,
  capabilityError,
}) => {
  const [selectedBtnIdx, setSelectedBtnIdx] = useState<number | null>(null);

  if (!capabilities && loading) {
    return (
      <div className="space-y-4" data-testid="toolbar-tab">
        <div className={sectionCardClasses()}>
          <div className="text-xs font-semibold uppercase tracking-[0.16em] text-slate-400">
            工具栏配置
          </div>
          <div className="mt-2 text-sm text-slate-500">正在读取模型能力，确定哪些动作可用。</div>
        </div>
      </div>
    );
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
  const activeActionCount =
    vm.toolbar.presets.length + vm.toolbar.customButtons.length;

  return (
    <div className="space-y-5" data-testid="toolbar-tab">
      <section className={sectionCardClasses()}>
        <div className="flex flex-col gap-4 border-b border-slate-200 pb-4 lg:flex-row lg:items-end lg:justify-between">
          <div>
            <div className="text-xs font-semibold uppercase tracking-[0.16em] text-slate-400">
              预设动作
            </div>
            <h2 className="mt-2 text-lg font-semibold text-slate-900">先用预设动作占住主操作</h2>
            <p className="mt-2 text-sm leading-6 text-slate-500">
              创建、导出、批量删除是最常见的列表工具栏动作，先确认能力，再决定是否补自定义按钮。
            </p>
          </div>
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-3">
            <div className="rounded-2xl border border-slate-200 bg-white px-4 py-3">
              <div className="text-[11px] uppercase tracking-[0.14em] text-slate-400">启用预设</div>
              <div className="mt-2 text-xl font-semibold text-slate-900">{vm.toolbar.presets.length}</div>
            </div>
            <div className="rounded-2xl border border-slate-200 bg-white px-4 py-3">
              <div className="text-[11px] uppercase tracking-[0.14em] text-slate-400">自定义按钮</div>
              <div className="mt-2 text-xl font-semibold text-slate-900">{vm.toolbar.customButtons.length}</div>
            </div>
            <div className="rounded-2xl border border-slate-200 bg-white px-4 py-3">
              <div className="text-[11px] uppercase tracking-[0.14em] text-slate-400">总动作数</div>
              <div className="mt-2 text-xl font-semibold text-slate-900">{activeActionCount}</div>
            </div>
          </div>
        </div>
        <div className="space-y-2">
          {capabilityError && (
            <div className="rounded-2xl border border-amber-200 bg-amber-50 px-4 py-4 text-sm text-amber-800">
              无法确认模型支持哪些预设动作，预设开关已暂时锁定；自定义按钮仍可继续调整。
            </div>
          )}
          {PRESETS.map((preset) => {
            const allowed = capabilities ? !!capabilities[preset.capability] : false;
            const active = activePresets.has(preset.key);
            return (
              <label
                key={preset.key}
                className={`flex items-center gap-3 rounded-2xl border bg-white px-4 py-3 text-sm ${
                  allowed ? 'border-slate-200' : 'border-slate-100 opacity-60'
                }`}
              >
                <input
                  type="checkbox"
                  checked={active}
                  onChange={() => togglePreset(preset.key, allowed)}
                  disabled={readonly || !allowed}
                  data-testid={`toolbar-preset-${preset.key}`}
                />
                <span className="min-w-0 flex-1">
                  <span className="block font-medium text-slate-800">{preset.label}</span>
                  <span className="mt-1 block text-xs text-slate-500">
                    {capabilityError
                      ? '当前无法读取模型能力，请修复模型绑定后再切换预设动作'
                      : allowed
                      ? '当前模型支持该预设动作'
                      : `当前模型未开启 ${String(preset.capability)} 能力`}
                  </span>
                </span>
                <span
                  className={`rounded-full px-2.5 py-1 text-[11px] font-medium ${
                    active
                      ? 'bg-blue-100 text-blue-700'
                      : 'bg-slate-100 text-slate-500'
                  }`}
                >
                  {active ? '已启用' : '未启用'}
                </span>
              </label>
            );
          })}
        </div>
      </section>

      <section className={sectionCardClasses()}>
        <div className="mb-4 flex flex-col gap-3 border-b border-slate-200 pb-4 sm:flex-row sm:items-end sm:justify-between">
          <div>
            <div className="text-xs font-semibold uppercase tracking-[0.16em] text-slate-400">
              自定义动作
            </div>
            <h3 className="mt-2 text-base font-semibold text-slate-900">自定义按钮</h3>
            <p className="mt-2 text-sm text-slate-500">
              只添加真正补充业务流程的动作，不要把低频操作全部塞进工具栏。
            </p>
          </div>
          <button
            type="button"
            onClick={addCustomButton}
            disabled={readonly}
            className="rounded-2xl bg-slate-900 px-4 py-2 text-sm font-medium text-white disabled:opacity-30"
            data-testid="toolbar-add-custom-button"
          >
            添加按钮
          </button>
        </div>

        {vm.toolbar.customButtons.length === 0 ? (
          <div className="rounded-2xl border border-dashed border-slate-300 bg-white px-4 py-6 text-sm text-slate-400">
            还没有自定义按钮。优先用预设按钮，确有业务动作再补这里。
          </div>
        ) : (
          <div className="space-y-3">
            <div className="flex flex-wrap gap-2">
              <span className="rounded-full bg-white px-3 py-1 text-xs font-medium text-slate-500">
                主工具栏不要超过 4 个动作
              </span>
              <span className="rounded-full bg-white px-3 py-1 text-xs font-medium text-slate-500">
                文案尽量是动词
              </span>
              <span className="rounded-full bg-white px-3 py-1 text-xs font-medium text-slate-500">
                避免和行内动作重复
              </span>
            </div>
            <ol className="space-y-2">
            {vm.toolbar.customButtons.map((b, i) => (
              <li
                key={i}
                className={`rounded-2xl border bg-white px-4 py-4 text-sm transition ${
                  selectedBtnIdx === i
                    ? 'border-blue-200 bg-blue-50/70'
                    : 'border-slate-200'
                }`}
              >
                <div className="flex items-start justify-between gap-3">
                  <button
                    type="button"
                    className="flex min-w-0 flex-1 items-start gap-3 text-left"
                    onClick={() => setSelectedBtnIdx(selectedBtnIdx === i ? null : i)}
                    data-testid={`toolbar-custom-item-${i}`}
                  >
                    <span className="inline-flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-slate-100 text-xs font-semibold text-slate-500">
                      {b.icon || `B${i + 1}`}
                    </span>
                    <span className="min-w-0 flex-1">
                      <span className="block truncate font-medium text-slate-800">
                        {b.label || '(未命名)'}
                      </span>
                      <span className="mt-1 block truncate text-xs text-slate-500">
                        {b.command || '尚未绑定 command'}
                      </span>
                      <span className="mt-3 flex flex-wrap gap-2">
                        <span className="rounded-full bg-slate-100 px-2.5 py-1 text-[11px] text-slate-600">
                          {b.icon ? `图标 ${b.icon}` : '无图标'}
                        </span>
                        <span
                          className={`rounded-full px-2.5 py-1 text-[11px] ${
                            b.requiresSelection
                              ? 'bg-amber-50 text-amber-700'
                              : 'bg-emerald-50 text-emerald-700'
                          }`}
                        >
                          {b.requiresSelection ? '依赖选中行' : '页面级动作'}
                        </span>
                      </span>
                    </span>
                  </button>
                  <button
                    type="button"
                    onClick={() => removeCustomButton(i)}
                    disabled={readonly}
                    className="ml-3 rounded-xl border border-red-200 px-3 py-1.5 text-xs text-red-600 disabled:opacity-30"
                    data-testid={`toolbar-custom-remove-${i}`}
                  >
                    删除
                  </button>
                </div>
                <div className="mt-4 flex flex-wrap gap-2 border-t border-slate-100 pt-3">
                  <button
                    type="button"
                    onClick={() =>
                      updateCustomButton(i, {
                        requiresSelection: !b.requiresSelection,
                      })
                    }
                    disabled={readonly}
                    className="rounded-full border border-slate-200 bg-slate-50 px-3 py-1 text-[11px] font-medium text-slate-600 disabled:opacity-30"
                  >
                    {b.requiresSelection ? '切到页面级动作' : '切到依赖选中行'}
                  </button>
                  <button
                    type="button"
                    onClick={() =>
                      updateCustomButton(i, {
                        icon: nextButtonIcon(b.icon),
                      })
                    }
                    disabled={readonly}
                    className="rounded-full border border-slate-200 bg-slate-50 px-3 py-1 text-[11px] font-medium text-slate-600 disabled:opacity-30"
                  >
                    图标：{b.icon || '无'}
                  </button>
                </div>
              </li>
            ))}
            </ol>
          </div>
        )}
      </section>

      <section className={sectionCardClasses()} data-testid="toolbar-custom-editor">
        <div className="mb-4 border-b border-slate-200 pb-4">
          <div className="text-xs font-semibold uppercase tracking-[0.16em] text-slate-400">
            动作属性
          </div>
          <h3 className="mt-2 text-base font-semibold text-slate-900">
            {selected ? `按钮属性 · ${selected.label || '未命名按钮'}` : '按钮属性'}
          </h3>
          <p className="mt-2 text-sm text-slate-500">
            在这里定义按钮文案、命令和是否依赖选中行，避免按钮语义含糊。
          </p>
        </div>
        {selected && selectedBtnIdx !== null ? (
          <SchemaBlockConfigPanel
            schemas={customButtonSchemas}
            value={selected as unknown as Record<string, unknown>}
            onChange={(next) =>
              updateCustomButton(selectedBtnIdx, next as Partial<CustomButton>)
            }
            readonly={readonly}
          />
        ) : (
          <div className="rounded-2xl border border-dashed border-slate-300 bg-white px-4 py-6 text-sm text-slate-400">
            先从“自定义按钮”里选一个按钮，再编辑它的行为。
          </div>
        )}
      </section>
    </div>
  );
};

function nextButtonIcon(icon?: string): string | undefined {
  const order = [undefined, 'plus', 'download', 'bolt'];
  const current = order.indexOf(icon);
  return order[(current + 1) % order.length];
}
