import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  BoltIcon,
  CheckCircleIcon,
  ChevronRightIcon,
  CommandLineIcon,
  PencilSquareIcon,
  PlusIcon,
  TrashIcon,
} from '@heroicons/react/24/outline';
import { cn } from '~/utils/cn';
import { commandActionService } from '~/plugins/core-designer/components/studio/services/command/CommandActionService';
import { SchemaBlockConfigPanel } from '~/shared/designer/SchemaBlockConfigPanel';
import { CommandSelector } from '../../panels/actions/CommandSelector';
import { detailCustomButtonSchemas } from './schema';
import type { DetailViewModel, CustomButton } from './mapper';
import type { ModelCapabilities } from '~/shared/hooks/useModelCapabilities';
import type { CommandDefinitionDTO } from '../../panels/actions/types';

export interface ActionsTabProps {
  vm: DetailViewModel;
  setVm: React.Dispatch<React.SetStateAction<DetailViewModel>>;
  capabilities: ModelCapabilities | undefined;
  modelCode?: string;
  readonly?: boolean;
}

const PRESET_COPY: Record<'edit' | 'delete', { label: string; description: string }> = {
  edit: {
    label: '编辑',
    description: '进入当前记录的编辑流程，适合详情页主操作。',
  },
  delete: {
    label: '删除',
    description: '执行删除动作，建议只在具备明确权限和确认机制时开启。',
  },
};

export const ActionsTab: React.FC<ActionsTabProps> = ({
  vm,
  setVm,
  capabilities,
  modelCode,
  readonly,
}) => {
  const [selectedBtnIdx, setSelectedBtnIdx] = useState<number | null>(null);
  const [commands, setCommands] = useState<CommandDefinitionDTO[]>([]);
  const [loadingCommands, setLoadingCommands] = useState(false);
  const [commandsError, setCommandsError] = useState<string | null>(null);

  const loadCommands = useCallback(async () => {
    if (!modelCode) {
      setCommands([]);
      setCommandsError(null);
      return;
    }
    setLoadingCommands(true);
    setCommandsError(null);
    try {
      setCommands(await commandActionService.listByModelCode(modelCode));
    } catch (error) {
      setCommandsError(error instanceof Error ? error.message : '加载命令失败');
    } finally {
      setLoadingCommands(false);
    }
  }, [modelCode]);

  useEffect(() => {
    void loadCommands();
  }, [loadCommands]);

  const togglePreset = (preset: 'edit' | 'delete') => {
    if (readonly) return;
    const has = vm.actions.presets.includes(preset);
    const presets = has
      ? vm.actions.presets.filter((p) => p !== preset)
      : [...vm.actions.presets, preset];
    setVm((prev) => ({ ...prev, actions: { ...prev.actions, presets } }));
  };

  const addButton = () => {
    if (readonly) return;
    const newBtn: CustomButton = { label: '新按钮', command: '' };
    const next = [...vm.actions.customButtons, newBtn];
    setVm((prev) => ({
      ...prev,
      actions: { ...prev.actions, customButtons: [...prev.actions.customButtons, newBtn] },
    }));
    setSelectedBtnIdx(next.length - 1);
  };

  const removeButton = (idx: number) => {
    if (readonly) return;
    setVm((prev) => ({
      ...prev,
      actions: {
        ...prev.actions,
        customButtons: prev.actions.customButtons.filter((_, i) => i !== idx),
      },
    }));
    if (selectedBtnIdx === idx) setSelectedBtnIdx(null);
  };

  const updateButton = (idx: number, patch: Partial<CustomButton>) => {
    setVm((prev) => ({
      ...prev,
      actions: {
        ...prev.actions,
        customButtons: prev.actions.customButtons.map((b, i) =>
          i === idx ? { ...b, ...patch } : b,
        ),
      },
    }));
  };

  const bindCommand = (idx: number, commandCode: string, command?: CommandDefinitionDTO) => {
    setVm((prev) => ({
      ...prev,
      actions: {
        ...prev.actions,
        customButtons: prev.actions.customButtons.map((button, i) => {
          if (i !== idx) return button;
          return {
            ...button,
            command: commandCode,
            label:
              !button.label || button.label === '新按钮'
                ? command?.displayName || commandCode
                : button.label,
          };
        }),
      },
    }));
  };

  const editEnabled = capabilities?.update ?? false;
  const deleteEnabled = capabilities?.delete ?? false;
  const selectedBtn = selectedBtnIdx !== null ? vm.actions.customButtons[selectedBtnIdx] : null;
  const availableCommands = useMemo(() => commands ?? [], [commands]);
  const selectedCommand = useMemo(
    () => availableCommands.find((cmd) => cmd.code === selectedBtn?.command),
    [availableCommands, selectedBtn?.command],
  );
  const presets: Array<{
    key: 'edit' | 'delete';
    enabled: boolean;
    icon: React.ComponentType<React.SVGProps<SVGSVGElement>>;
  }> = [
    { key: 'edit', enabled: editEnabled, icon: PencilSquareIcon },
    { key: 'delete', enabled: deleteEnabled, icon: TrashIcon },
  ];

  return (
    <div className="grid gap-6 xl:grid-cols-[minmax(0,1fr)_340px]">
      <section className="space-y-6">
        <section className="rounded-[28px] border border-slate-200 bg-white/90 shadow-sm">
          <div className="border-b border-slate-200 px-6 py-4">
            <div className="inline-flex items-center gap-2 rounded-full bg-slate-100 px-3 py-1 text-xs font-semibold uppercase tracking-[0.16em] text-slate-500">
              <BoltIcon className="h-4 w-4" />
              操作按钮
            </div>
            <h2 className="mt-4 text-2xl font-semibold tracking-tight text-slate-950">操作按钮</h2>
            <p className="mt-1 text-sm text-slate-500">
              管理详情页中的标准动作和自定义业务命令。
            </p>
            <div className="mt-4 grid gap-3 sm:grid-cols-3">
              <div className="rounded-2xl border border-slate-200 bg-slate-50 px-4 py-3">
                <div className="text-[11px] uppercase tracking-[0.14em] text-slate-400">预设动作</div>
                <div className="mt-2 text-xl font-semibold text-slate-900">{vm.actions.presets.length}</div>
              </div>
              <div className="rounded-2xl border border-slate-200 bg-slate-50 px-4 py-3">
                <div className="text-[11px] uppercase tracking-[0.14em] text-slate-400">自定义按钮</div>
                <div className="mt-2 text-xl font-semibold text-slate-900">{vm.actions.customButtons.length}</div>
              </div>
              <div className="rounded-2xl border border-slate-200 bg-slate-50 px-4 py-3">
                <div className="text-[11px] uppercase tracking-[0.14em] text-slate-400">已加载命令</div>
                <div className="mt-2 text-xl font-semibold text-slate-900">{availableCommands.length}</div>
              </div>
            </div>
          </div>

          <div className="px-6 py-5">
            <div className="flex items-center justify-between gap-4">
              <div>
                <h3 className="text-base font-semibold text-slate-950">系统预设</h3>
                <p className="mt-1 text-sm text-slate-500">基于模型能力启用标准详情页动作。</p>
              </div>
              <span className="rounded-full bg-slate-100 px-2.5 py-1 text-xs font-medium text-slate-500">
                {vm.actions.presets.length} / {presets.length} 已启用
              </span>
            </div>
          </div>

          <div className="px-6 pb-6">
            {presets.map((preset) => {
              const active = vm.actions.presets.includes(preset.key);
              const detail = PRESET_COPY[preset.key];
              const Icon = preset.icon;

              return (
                <div
                  key={preset.key}
                  className={cn(
                    'mb-3 rounded-xl border last:mb-0',
                    active ? 'border-blue-200 bg-blue-50/60' : 'border-slate-200 bg-white',
                    !preset.enabled && 'opacity-60',
                  )}
                  data-testid={`detail-action-preset-${preset.key}`}
                >
                  <div className="grid grid-cols-[auto_minmax(0,1fr)_auto] items-center gap-4 px-4 py-4">
                    <span className={cn(
                      'inline-flex h-10 w-10 items-center justify-center rounded-xl border',
                      active
                        ? 'border-blue-100 bg-white text-blue-600'
                        : 'border-slate-200 bg-slate-50 text-slate-500',
                    )}>
                      <Icon className="h-5 w-5" />
                    </span>

                    <div className="min-w-0 flex-1">
                      <div className="flex flex-wrap items-center gap-2">
                        <div className="text-sm font-semibold text-slate-900">{detail.label}</div>
                        <span className={cn(
                          'rounded-full px-2 py-0.5 text-[11px] font-medium',
                          preset.enabled
                            ? 'bg-slate-100 text-slate-500'
                            : 'bg-amber-100 text-amber-700',
                        )}>
                          {preset.key === 'edit' ? '依赖 update 能力' : '依赖 delete 能力'}
                        </span>
                      </div>
                      <p className="mt-1 text-sm leading-6 text-slate-500">{detail.description}</p>
                    </div>

                    <div className="flex items-center gap-3">
                      <span className={cn(
                        'hidden rounded-full px-2.5 py-1 text-xs font-medium md:inline-flex',
                        active
                          ? 'bg-blue-100 text-blue-700'
                          : preset.enabled
                            ? 'bg-slate-100 text-slate-500'
                            : 'bg-amber-100 text-amber-700',
                      )}>
                        {preset.enabled ? (active ? '已启用' : '未启用') : '能力不可用'}
                      </span>
                      <button
                        type="button"
                        onClick={() => togglePreset(preset.key)}
                        disabled={!preset.enabled || readonly}
                        title={preset.enabled ? '' : `Model 不支持 ${preset.key === 'edit' ? 'update' : 'delete'}`}
                        className={cn(
                          'relative inline-flex h-6 w-11 items-center rounded-full border transition',
                          active ? 'border-blue-600 bg-blue-600' : 'border-slate-300 bg-slate-200',
                          (!preset.enabled || readonly) && 'cursor-not-allowed opacity-60',
                        )}
                      >
                        <span
                          className={cn(
                            'inline-block h-4 w-4 rounded-full bg-white transition',
                            active ? 'translate-x-6' : 'translate-x-1',
                          )}
                        />
                      </button>
                    </div>
                  </div>
                </div>
              );
            })}
          </div>
        </section>

        <section className="rounded-[28px] border border-slate-200 bg-white/90 shadow-sm">
          <div className="flex flex-col gap-4 border-b border-slate-200 px-6 py-4 lg:flex-row lg:items-center lg:justify-between">
            <div>
              <h3 className="text-base font-semibold text-slate-950">自定义按钮</h3>
              <p className="mt-1 text-sm text-slate-500">按业务命令补充详情页动作入口。</p>
            </div>
            <button
              type="button"
              onClick={addButton}
              disabled={readonly}
              className="inline-flex items-center justify-center gap-2 rounded-lg bg-slate-900 px-3.5 py-2 text-sm font-medium text-white transition hover:bg-slate-800 disabled:cursor-not-allowed disabled:opacity-40"
              data-testid="detail-actions-add-custom-button"
            >
              <PlusIcon className="h-4 w-4" />
              新增按钮
            </button>
          </div>

          {vm.actions.customButtons.length === 0 ? (
            <div className="px-6 py-12 text-center">
              <div className="mx-auto inline-flex h-12 w-12 items-center justify-center rounded-xl border border-slate-200 bg-slate-50 text-slate-400">
                <CheckCircleIcon className="h-7 w-7" />
              </div>
              <div className="mt-4 text-base font-semibold text-slate-900">还没有自定义动作</div>
              <p className="mx-auto mt-2 max-w-md text-sm leading-6 text-slate-500">
                适合放审批流、跳转、批处理或命令触发类动作。先建一个，再在右侧补齐命令绑定。
              </p>
            </div>
          ) : (
            <ol className="px-4 py-3">
              {vm.actions.customButtons.map((b, i) => {
                const active = selectedBtnIdx === i;
                return (
                  <li
                    key={i}
                    className={cn(
                      'mb-2 rounded-xl border p-3 transition last:mb-0',
                      active
                        ? 'border-blue-200 bg-blue-50/80'
                        : 'border-slate-200 bg-white hover:border-slate-300',
                    )}
                  >
                    <div className="flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
                      <button
                        type="button"
                        onClick={() => setSelectedBtnIdx(active ? null : i)}
                        className="flex min-w-0 flex-1 items-center gap-4 text-left"
                        data-testid={`detail-custom-button-${i}`}
                      >
                        <span className={cn(
                          'inline-flex h-10 w-10 shrink-0 items-center justify-center rounded-xl border text-sm font-semibold',
                          active
                            ? 'border-blue-200 bg-white text-blue-700'
                            : 'border-slate-200 bg-slate-50 text-slate-500',
                        )}>
                          {String(i + 1).padStart(2, '0')}
                        </span>
                        <span className="min-w-0 flex-1">
                          <span className="flex items-center gap-2">
                            <span className="truncate text-sm font-semibold text-slate-950">
                              {b.label || '(未命名)'}
                            </span>
                            {active && (
                              <span className="rounded-full bg-blue-100 px-2 py-0.5 text-[11px] font-medium text-blue-700">
                                当前编辑
                              </span>
                            )}
                          </span>
                          <span className="mt-1 flex flex-wrap items-center gap-2 text-xs text-slate-500">
                            <span className="rounded-full bg-slate-100 px-2 py-1 font-medium text-slate-600">
                              {b.command || '未绑定命令'}
                            </span>
                            <span className="rounded-full bg-slate-50 px-2 py-1 text-slate-500">
                              {b.icon ? `图标 ${b.icon}` : '无图标'}
                            </span>
                          </span>
                        </span>
                      </button>

                      <div className="flex items-center gap-2">
                        <button
                          type="button"
                          onClick={() => setSelectedBtnIdx(active ? null : i)}
                          className="inline-flex items-center gap-1 rounded-lg border border-slate-200 px-3 py-2 text-sm font-medium text-slate-600 transition hover:bg-slate-50"
                        >
                          配置
                          <ChevronRightIcon className="h-4 w-4" />
                        </button>
                        <button
                          type="button"
                          onClick={() => removeButton(i)}
                          disabled={readonly}
                          className="inline-flex items-center gap-2 rounded-lg border border-red-200 px-3 py-2 text-sm text-red-600 transition hover:bg-red-50 disabled:cursor-not-allowed disabled:opacity-40"
                          data-testid={`detail-custom-remove-${i}`}
                        >
                          <TrashIcon className="h-4 w-4" />
                          删除
                        </button>
                      </div>
                    </div>
                  </li>
                );
              })}
            </ol>
          )}
        </section>
      </section>

      <aside className="min-w-0">
        <div className="sticky top-0 rounded-[28px] border border-slate-200 bg-white/95 shadow-[0_16px_40px_rgba(15,23,42,0.08)] backdrop-blur">
          <div className="flex items-center justify-between border-b border-slate-200 px-5 py-4">
            <div>
              <div className="text-xs font-semibold uppercase tracking-[0.16em] text-slate-400">属性面板</div>
              <h3 className="mt-1 text-base font-semibold text-slate-950">按钮属性</h3>
              <p className="mt-2 max-w-[220px] text-sm leading-6 text-slate-500">
                先选一个按钮，再补齐命令、文案和可见性策略，避免详情页动作语义混乱。
              </p>
            </div>
            <span className="rounded-full bg-slate-100 px-3 py-1 text-xs font-medium text-slate-500">
              {selectedBtn ? '编辑中' : '待选择'}
            </span>
          </div>

          {selectedBtn && selectedBtnIdx !== null ? (
            <>
              <div className="border-b border-slate-200 bg-slate-50/70 px-5 py-4">
                <div className="flex items-center justify-between gap-3">
                  <div className="text-sm font-semibold text-slate-900">{selectedBtn.label || '(未命名)'}</div>
                  <span className="rounded-full bg-blue-100 px-2 py-0.5 text-[11px] font-medium text-blue-700">
                    按钮 #{selectedBtnIdx + 1}
                  </span>
                </div>
                <div className="mt-1 text-xs leading-5 text-slate-500">
                  配置文案、图标和命令绑定。保存后会写入 toolbar button 的 action handler。
                </div>
              </div>
              <div className="px-5 py-5">
                <div className="mb-4 rounded-lg border border-slate-200 bg-slate-50/80 px-4 py-3">
                  <div className="text-xs font-semibold uppercase tracking-[0.14em] text-slate-400">
                    快速检查
                  </div>
                  <div className="mt-2 space-y-2 text-sm">
                    <div className="flex items-center justify-between gap-3">
                      <span className="text-slate-500">按钮名称</span>
                      <span className="max-w-[180px] truncate font-medium text-slate-900">
                        {selectedBtn.label || '未填写'}
                      </span>
                    </div>
                    <div className="flex items-center justify-between gap-3">
                      <span className="text-slate-500">命令标识</span>
                      <span className="max-w-[180px] truncate font-medium text-slate-900">
                        {selectedBtn.command || '未绑定'}
                      </span>
                    </div>
                  </div>
                </div>
                <div className="mb-4 rounded-lg border border-slate-200 bg-white p-4">
                  <div className="mb-3 flex items-center gap-2 text-xs font-semibold uppercase tracking-[0.14em] text-slate-400">
                    <CommandLineIcon className="h-4 w-4" />
                    Action Handler
                  </div>
                  <label className="mb-3 block">
                    <span className="mb-1.5 block text-xs font-medium text-slate-500">命令标识</span>
                    <input
                      type="text"
                      value={selectedBtn.command}
                      onChange={(e) => updateButton(selectedBtnIdx, { command: e.target.value })}
                      placeholder="showcase:approve_record"
                      disabled={readonly}
                      className="w-full rounded-lg border border-slate-200 bg-white px-3 py-2 text-sm text-slate-900 outline-none transition focus:border-blue-300 focus:ring-2 focus:ring-blue-100 disabled:cursor-not-allowed disabled:bg-slate-50 disabled:text-slate-400"
                      data-testid="detail-command-code-input"
                    />
                  </label>
                  <CommandSelector
                    commands={availableCommands}
                    loading={loadingCommands}
                    error={commandsError}
                    value={selectedBtn.command}
                    onChange={(commandCode, command) => {
                      bindCommand(selectedBtnIdx, commandCode, command);
                    }}
                    onRefresh={() => {
                      void loadCommands();
                    }}
                  />
                  <div className="mt-3 text-xs leading-5 text-slate-500">
                    {selectedCommand ? (
                      <>
                        当前命令会在运行时通过
                        {' '}
                        <code className="rounded bg-slate-100 px-1 py-0.5">
                          /api/meta/commands/execute/{selectedCommand.code}
                        </code>
                        {' '}
                        执行。
                      </>
                    ) : (
                      '选择一个模型命令后，设计器会同步生成可被运行时 action handler 识别的 command action。'
                    )}
                  </div>
                </div>
                <SchemaBlockConfigPanel
                  schemas={detailCustomButtonSchemas}
                  value={selectedBtn as unknown as Record<string, unknown>}
                  onChange={(next, changedKey) => {
                    if (!changedKey) {
                      updateButton(selectedBtnIdx, next as Partial<CustomButton>);
                      return;
                    }
                    setVm((prev) => ({
                      ...prev,
                      actions: {
                        ...prev.actions,
                        customButtons: prev.actions.customButtons.map((button, i) =>
                          i === selectedBtnIdx ? { ...button, [changedKey]: next[changedKey] } : button,
                        ),
                      },
                    }));
                  }}
                  readonly={readonly}
                  className="[&>section]:rounded-xl [&>section]:border [&>section]:border-slate-200 [&>section]:bg-slate-50/50 [&>section]:p-4 [&>section>h3]:mb-4 [&>section>h3]:text-[11px] [&>section>h3]:font-semibold [&>section>h3]:uppercase [&>section>h3]:tracking-[0.16em] [&>section>h3]:text-slate-400 [&>section>div]:space-y-4 [&_.field-label]:text-sm [&_.field-label]:font-medium [&_.field-label]:text-slate-700"
                />
              </div>
            </>
          ) : (
            <div className="px-5 py-10 text-center">
              <div className="mx-auto inline-flex h-12 w-12 items-center justify-center rounded-xl border border-slate-200 bg-slate-50 text-slate-400">
                <BoltIcon className="h-7 w-7" />
              </div>
              <div className="mt-4 text-base font-semibold text-slate-900">选择一个按钮开始编辑</div>
              <p className="mt-2 text-sm leading-6 text-slate-500">
                左侧点击自定义按钮条目后，这里会显示详细属性。当前页不再把配置埋在列表底部。
              </p>
            </div>
          )}
        </div>
      </aside>
    </div>
  );
};
