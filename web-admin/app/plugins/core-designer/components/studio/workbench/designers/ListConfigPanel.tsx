import React, { useEffect, useMemo, useRef, useState } from 'react';
import {
  AdjustmentsHorizontalIcon,
  QueueListIcon,
  RectangleGroupIcon,
  WrenchScrewdriverIcon,
} from '@heroicons/react/24/outline';
import type { PageSchema } from '~/plugins/core-designer/components/studio/domain/dsl/types';
import { useModelCapabilities } from '~/shared/hooks/useModelCapabilities';
import { cn } from '~/utils/cn';
import {
  blocksToViewModel,
  viewModelToBlocks,
  type ListViewModel,
} from './list-config/mapper';
import { ColumnsTab } from './list-config/ColumnsTab';
import { FiltersTab } from './list-config/FiltersTab';
import { ToolbarTab } from './list-config/ToolbarTab';
import { BehaviorTab } from './list-config/BehaviorTab';
import {
  validateListVm,
  hasBlockingErrors,
  type ValidationError,
} from './validation/capabilityValidator';
import { StructuralPreview } from './preview/StructuralPreview';
import { SampleDataLoader } from './preview/SampleDataLoader';

export interface ListConfigPanelProps {
  schema: PageSchema;
  onSchemaChange: (schema: PageSchema) => void;
  onSave?: (schema: PageSchema) => Promise<void>;
  modelCode?: string;
  readonly?: boolean;
  previewMode?: boolean;
}

type Tab = 'columns' | 'filters' | 'toolbar' | 'behavior';

const TABS: Array<{
  id: Tab;
  label: string;
  description: string;
  icon: React.ComponentType<React.SVGProps<SVGSVGElement>>;
}> = [
  {
    id: 'columns',
    label: '列结构',
    description: '决定表格的主信息密度与阅读顺序',
    icon: QueueListIcon,
  },
  {
    id: 'filters',
    label: '筛选器',
    description: '保留高频筛选，避免把查询条件堆满页头',
    icon: AdjustmentsHorizontalIcon,
  },
  {
    id: 'toolbar',
    label: '工具栏',
    description: '整理主操作，突出创建、导出与自定义动作',
    icon: RectangleGroupIcon,
  },
  {
    id: 'behavior',
    label: '交互行为',
    description: '控制排序、分页、多视图等列表运行规则',
    icon: WrenchScrewdriverIcon,
  },
];

/**
 * Structured config panel for kind=list pages.
 *
 * Renders 4 vertical tabs (Columns / Filters / Toolbar / Behavior); each tab
 * is a thin editor over the single `ListViewModel` state, which round-trips
 * to `PageSchema.blocks` via `blocksToViewModel` / `viewModelToBlocks`.
 *
 * A capability-validation banner is rendered at the top of the main pane.
 * On wide viewports (>= xl) a right-side pane shows `StructuralPreview` plus
 * `SampleDataLoader` so the designer can eyeball the result without leaving
 * the panel.
 *
 * All configuration editors go through `SchemaBlockConfigPanel` — no
 * hand-coded panel JSX (Studio red-line).
 */
export const ListConfigPanel: React.FC<ListConfigPanelProps> = ({
  schema,
  onSchemaChange,
  modelCode,
  readonly,
  previewMode,
}) => {
  const effectiveModelCode = modelCode ?? schema.modelCode;
  const {
    data: capabilities,
    loading: capabilitiesLoading,
    error: capabilitiesError,
  } = useModelCapabilities(effectiveModelCode);
  const [tab, setTab] = useState<Tab>('columns');
  const [vm, setVm] = useState<ListViewModel>(() =>
    blocksToViewModel(schema.blocks ?? []),
  );
  const [sampleRows, setSampleRows] = useState<Array<Record<string, unknown>>>();

  const schemaFieldCodes = useMemo(() => {
    const codes = new Set<string>();
    vm.columns.forEach((column) => {
      if (column.field) codes.add(column.field);
    });
    vm.filters.forEach((filter) => {
      if (filter.field) codes.add(filter.field);
    });
    if (vm.behavior.defaultSortField) {
      codes.add(vm.behavior.defaultSortField);
    }
    return Array.from(codes);
  }, [vm]);

  // Fields fallback: derive field list from capabilities (sortable ∪ filterable),
  // but keep the current schema editable even if the model lookup fails.
  const fields = useMemo(() => {
    const set = new Set<string>(schemaFieldCodes);
    if (capabilities) {
      capabilities.sortableFields.forEach((code) => set.add(code));
      capabilities.filterableFields.forEach((code) => set.add(code));
    }
    if (capabilitiesLoading && set.size === 0) return undefined;
    return Array.from(set).map((code) => ({
      code,
      displayName: code,
      dataType: 'unknown',
    }));
  }, [capabilities, capabilitiesLoading, schemaFieldCodes]);

  // Push VM changes out to schema.blocks. Use a ref guard + JSON compare to
  // avoid echo loops when the parent re-pushes the same schema back in.
  const lastPushedRef = useRef<string>('');
  useEffect(() => {
    const nextBlocks = viewModelToBlocks(vm);
    const serialized = JSON.stringify(nextBlocks);
    if (serialized === lastPushedRef.current) return;
    if (JSON.stringify(schema.blocks ?? []) === serialized) {
      lastPushedRef.current = serialized;
      return;
    }
    lastPushedRef.current = serialized;
    onSchemaChange({ ...schema, blocks: nextBlocks });
    // Intentionally narrow deps to `vm` — outward sync only.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [vm]);

  const errors: ValidationError[] = useMemo(
    () => validateListVm(vm, capabilities),
    [vm, capabilities],
  );

  const activeTabMeta = TABS.find((item) => item.id === tab) ?? TABS[0];
  const toolbarActionCount =
    vm.toolbar.presets.length + vm.toolbar.customButtons.length;
  const capabilityWarning = capabilitiesError
    ? `未能读取模型 ${effectiveModelCode ?? '当前模型'} 的能力信息，已回退为仅基于当前页面配置的编辑模式。请检查 modelCode 或重新绑定模型。`
    : null;
  const summaryStats = [
    { label: '已选列', value: vm.columns.length, tone: 'slate' as const },
    { label: '筛选项', value: vm.filters.length, tone: 'blue' as const },
    { label: '工具动作', value: toolbarActionCount, tone: 'emerald' as const },
  ];

  return (
    <div className="flex h-full bg-slate-50" data-testid="list-config-panel">
      {!previewMode && (
        <aside className="w-72 shrink-0 border-r border-slate-200 bg-white/90 px-4 py-6">
          <div className="rounded-[28px] border border-slate-200 bg-white p-5 shadow-[0_16px_40px_rgba(15,23,42,0.06)]">
            <div className="text-xs font-semibold uppercase tracking-[0.18em] text-slate-400">
              列表设计
            </div>
            <div className="mt-3 text-lg font-semibold text-slate-950">
              先定义信息层级，再补充筛选与动作
            </div>
            <div className="mt-2 text-sm leading-6 text-slate-500">
              参考 detail 页的配置节奏，把列表拆成结构、入口操作和运行行为三个层次。
            </div>
            <div className="mt-5 grid grid-cols-2 gap-3 rounded-2xl border border-slate-200 bg-slate-50 p-3 text-sm text-slate-600">
              <div>
                <div className="text-2xl font-semibold text-slate-950">{vm.columns.length}</div>
                <div>已选列</div>
              </div>
              <div>
                <div className="text-2xl font-semibold text-slate-950">{vm.filters.length}</div>
                <div>筛选项</div>
              </div>
            </div>
          </div>
          <nav className="mt-6 rounded-[28px] border border-slate-200 bg-white p-3 shadow-sm">
            {TABS.map((t) => {
              const count =
                t.id === 'columns'
                  ? vm.columns.length
                  : t.id === 'filters'
                    ? vm.filters.length
                    : t.id === 'toolbar'
                      ? toolbarActionCount
                      : Number(
                          Boolean(vm.behavior.enableSorting) ||
                            Boolean(vm.behavior.enablePagination) ||
                            Boolean(vm.behavior.enableMultiView),
                        );

              return (
                <button
                  key={t.id}
                  type="button"
                  onClick={() => setTab(t.id)}
                  className={cn(
                    'mb-2 flex w-full items-start gap-3 rounded-2xl border px-3 py-3 text-left transition last:mb-0',
                    tab === t.id
                      ? 'border-blue-200 bg-blue-50/80 text-blue-700 shadow-[0_10px_24px_rgba(59,130,246,0.10)]'
                      : 'border-transparent text-slate-500 hover:border-slate-200 hover:bg-slate-50',
                  )}
                  data-testid={`list-tab-${t.id}`}
                >
                  <span
                    className={cn(
                      'inline-flex h-10 w-10 shrink-0 items-center justify-center rounded-xl border',
                      tab === t.id
                        ? 'border-blue-100 bg-white text-blue-600'
                        : 'border-slate-200 bg-slate-50 text-slate-400',
                    )}
                  >
                    <t.icon className="h-5 w-5" />
                  </span>
                  <span className="min-w-0 flex-1">
                    <span className="flex items-center gap-2">
                      <span
                        className={cn(
                          'text-sm font-medium',
                          tab === t.id ? 'text-slate-900' : 'text-slate-700',
                        )}
                      >
                        {t.label}
                      </span>
                      <span
                        className={cn(
                          'rounded-full px-2 py-0.5 text-[11px] font-medium',
                          tab === t.id
                            ? 'bg-blue-100 text-blue-700'
                            : 'bg-slate-100 text-slate-500',
                        )}
                      >
                        {count}
                      </span>
                    </span>
                    <span className="mt-1 block text-xs leading-5 text-slate-500">
                      {t.description}
                    </span>
                  </span>
                </button>
              );
            })}
          </nav>
        </aside>
      )}

      <main className="flex-1 overflow-auto px-6 py-6">
        <div className="mx-auto max-w-6xl space-y-6">
          <section
            className="rounded-[28px] border border-slate-200 bg-white/90 p-6 shadow-[0_16px_50px_rgba(15,23,42,0.08)]"
            data-testid="list-designer-summary"
          >
            <div className="flex flex-col gap-6 lg:flex-row lg:items-start lg:justify-between">
              <div className="max-w-2xl">
                <div className="inline-flex items-center gap-2 rounded-full bg-blue-50 px-3 py-1 text-xs font-semibold uppercase tracking-[0.16em] text-blue-700">
                  当前编辑
                </div>
                <h1 className="mt-4 text-3xl font-semibold tracking-tight text-slate-950">
                  {activeTabMeta.label}
                </h1>
                <p className="mt-3 text-sm leading-6 text-slate-500">
                  {activeTabMeta.description}
                </p>
                <div className="mt-4 flex flex-wrap gap-2">
                  <span className="rounded-full bg-slate-100 px-3 py-1 text-xs font-medium text-slate-600">
                    当前阶段：{activeTabMeta.label}
                  </span>
                  <span className="rounded-full bg-slate-100 px-3 py-1 text-xs font-medium text-slate-600">
                    右侧预览同步更新
                  </span>
                </div>
              </div>
              <div className="grid gap-3 sm:grid-cols-3">
                {summaryStats.map((item) => (
                  <div
                    key={item.label}
                    className={cn(
                      'min-w-[140px] rounded-2xl border px-4 py-4',
                      item.tone === 'blue' && 'border-blue-100 bg-blue-50',
                      item.tone === 'emerald' && 'border-emerald-100 bg-emerald-50',
                      item.tone === 'slate' && 'border-slate-200 bg-slate-50',
                    )}
                  >
                    <div className="text-xs font-medium uppercase tracking-[0.14em] text-slate-500">
                      {item.label}
                    </div>
                    <div className="mt-2 text-2xl font-semibold text-slate-900">
                      {item.value}
                    </div>
                  </div>
                ))}
              </div>
            </div>
            {capabilityWarning && (
              <div
                className="mt-5 rounded-2xl border border-amber-200 bg-amber-50 px-4 py-4 text-sm text-amber-800"
                data-testid="capability-fallback-banner"
              >
                <div className="font-semibold text-slate-900">模型能力读取失败</div>
                <div className="mt-1 leading-6">{capabilityWarning}</div>
              </div>
            )}
          </section>

          {errors.length > 0 && (
            <div
              className={`rounded-[28px] border px-5 py-5 text-sm shadow-sm ${
                hasBlockingErrors(errors)
                  ? 'border-red-200 bg-red-50'
                  : 'border-amber-200 bg-amber-50'
              }`}
              data-testid="validation-banner"
            >
              <div className="mb-2 text-xs font-semibold uppercase tracking-[0.16em] text-slate-500">
                配置校验
              </div>
              <div className="mb-2 font-semibold text-slate-900">
                {hasBlockingErrors(errors) ? '配置存在冲突' : '配置提示'}
              </div>
              <ul className="space-y-1">
                {errors.map((e, i) => (
                  <li
                    key={i}
                    className={
                      e.severity === 'error' ? 'text-red-700' : 'text-amber-700'
                    }
                    data-testid={`validation-${e.severity}`}
                  >
                    [{e.tab}] {e.message}
                  </li>
                ))}
              </ul>
            </div>
          )}

          <section
            className="rounded-[28px] border border-slate-200 bg-white/90 p-6 shadow-sm"
            data-testid="list-designer-workspace"
          >
            <div className="mb-6 flex flex-col gap-3 border-b border-slate-100 pb-5">
              <div className="text-xs font-semibold uppercase tracking-[0.18em] text-slate-400">
                工作区
              </div>
              <div className="text-lg font-semibold text-slate-950">
                {activeTabMeta.label}
              </div>
              <div className="text-sm text-slate-500">
                在这里完成当前阶段配置，右侧预览会同步反映结构变化。
              </div>
            </div>
            {tab === 'columns' && (
              <ColumnsTab
                vm={vm}
                setVm={setVm}
                fields={fields}
                readonly={readonly}
                loading={capabilitiesLoading}
                capabilityError={capabilitiesError}
              />
            )}
            {tab === 'filters' && (
              <FiltersTab
                vm={vm}
                setVm={setVm}
                fields={fields}
                capabilities={capabilities}
                readonly={readonly}
                loading={capabilitiesLoading}
                capabilityError={capabilitiesError}
              />
            )}
            {tab === 'toolbar' && (
              <ToolbarTab
                vm={vm}
                setVm={setVm}
                capabilities={capabilities}
                readonly={readonly}
                loading={capabilitiesLoading}
                capabilityError={capabilitiesError}
              />
            )}
            {tab === 'behavior' && (
              <BehaviorTab
                vm={vm}
                setVm={setVm}
                capabilities={capabilities}
                readonly={readonly}
                loading={capabilitiesLoading}
                capabilityError={capabilitiesError}
                fallbackSortFields={fields?.map((field) => field.code) ?? []}
              />
            )}
          </section>
        </div>
      </main>

      {!previewMode && (
        <aside
          className="hidden w-[420px] shrink-0 border-l border-slate-200 bg-white/90 px-4 py-6 xl:flex xl:flex-col"
          data-testid="list-preview-pane"
        >
          <div className="sticky top-0 rounded-[28px] border border-slate-200 bg-white/95 shadow-[0_16px_40px_rgba(15,23,42,0.08)] backdrop-blur">
            <div className="border-b border-slate-200 px-5 py-5">
              <div className="text-xs font-semibold uppercase tracking-[0.18em] text-slate-400">
                实时预览
              </div>
              <div className="mt-2 text-xl font-semibold text-slate-950">列表结构预览</div>
              <div className="mt-2 text-sm leading-6 text-slate-500">
                用来检查筛选密度、操作优先级和表格可读性，不必频繁打开整页预览。
              </div>
              <div className="mt-4 flex flex-wrap gap-2">
                <span className="rounded-full bg-slate-100 px-3 py-1 text-xs font-medium text-slate-600">
                  列 {vm.columns.length}
                </span>
                <span className="rounded-full bg-slate-100 px-3 py-1 text-xs font-medium text-slate-600">
                  筛选 {vm.filters.length}
                </span>
                <span className="rounded-full bg-slate-100 px-3 py-1 text-xs font-medium text-slate-600">
                  动作 {toolbarActionCount}
                </span>
              </div>
            </div>
            <StructuralPreview vm={vm} fields={fields} overrideRows={sampleRows} />
            <div className="px-5 pb-5">
              <SampleDataLoader
                modelCode={effectiveModelCode}
                onLoaded={setSampleRows}
              />
            </div>
          </div>
        </aside>
      )}
    </div>
  );
};

export default ListConfigPanel;
