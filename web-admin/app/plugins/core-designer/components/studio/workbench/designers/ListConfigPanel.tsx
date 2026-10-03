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
import { useI18n } from '~/contexts/I18nContext';
import '../styles/list-config.css';
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
  labelKey: string;
  descriptionKey: string;
  icon: React.ComponentType<React.SVGProps<SVGSVGElement>>;
}> = [
  {
    id: 'columns',
    labelKey: 'list_designer.columns',
    descriptionKey: 'list_designer.columns_description',
    icon: QueueListIcon,
  },
  {
    id: 'filters',
    labelKey: 'list_designer.filters',
    descriptionKey: 'list_designer.filters_description',
    icon: AdjustmentsHorizontalIcon,
  },
  {
    id: 'toolbar',
    labelKey: 'list_designer.toolbar',
    descriptionKey: 'list_designer.toolbar_description',
    icon: RectangleGroupIcon,
  },
  {
    id: 'behavior',
    labelKey: 'list_designer.behavior',
    descriptionKey: 'list_designer.behavior_description',
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
 * When the panel is wide enough, a right-side pane shows `StructuralPreview` plus
 * `SampleDataLoader` so the designer can eyeball the result without leaving
 * the panel. Narrow panels place the preview below the editors.
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
  const { t } = useI18n();
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

  // Loading a projection is not an edit. Publish only subsequent VM changes.
  const lastPushedRef = useRef<string | null>(null);
  useEffect(() => {
    const nextBlocks = viewModelToBlocks(vm);
    const serialized = JSON.stringify(nextBlocks);
    if (lastPushedRef.current === null) {
      lastPushedRef.current = serialized;
      return;
    }
    if (readonly || serialized === lastPushedRef.current) return;
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
  const capabilityWarning = capabilitiesError ? t('list_designer.capability_warning') : null;
  const summaryStats = [
    { label: t('list_designer.selected_columns'), value: vm.columns.length, tone: 'slate' as const },
    { label: t('list_designer.filter_count'), value: vm.filters.length, tone: 'blue' as const },
    { label: t('list_designer.action_count'), value: toolbarActionCount, tone: 'emerald' as const },
  ];

  return (
    <div className="list-config-container h-full min-h-0 overflow-auto bg-slate-50" data-testid="list-config-panel">
      <div className={cn('list-config-layout', previewMode && 'list-config-preview-only')}>
      {!previewMode && (
        <aside className="list-config-navigation border-r border-slate-200 bg-white/90 px-4 py-6">
          <div className="list-config-intro rounded-[28px] border border-slate-200 bg-white p-5 shadow-[0_16px_40px_rgba(15,23,42,0.06)]">
            <div className="text-xs font-semibold uppercase tracking-[0.18em] text-slate-400">
              {t('list_designer.heading')}
            </div>
            <div className="mt-3 text-lg font-semibold text-slate-950">
              {t('list_designer.intro_heading')}
            </div>
            <div className="mt-2 text-sm leading-6 text-slate-500">
              {t('list_designer.intro_description')}
            </div>
            <div className="mt-5 grid grid-cols-2 gap-3 rounded-2xl border border-slate-200 bg-slate-50 p-3 text-sm text-slate-600">
              <div>
                <div className="text-2xl font-semibold text-slate-950">{vm.columns.length}</div>
                <div>{t('list_designer.selected_columns')}</div>
              </div>
              <div>
                <div className="text-2xl font-semibold text-slate-950">{vm.filters.length}</div>
                <div>{t('list_designer.filter_count')}</div>
              </div>
            </div>
          </div>
          <nav className="list-config-navigation-tabs mt-6 rounded-[28px] border border-slate-200 bg-white p-3 shadow-sm">
            {TABS.map((tabItem) => {
              const count =
                tabItem.id === 'columns'
                  ? vm.columns.length
                  : tabItem.id === 'filters'
                    ? vm.filters.length
                    : tabItem.id === 'toolbar'
                      ? toolbarActionCount
                      : Number(
                          Boolean(vm.behavior.enableSorting) ||
                            Boolean(vm.behavior.enablePagination) ||
                            Boolean(vm.behavior.enableMultiView),
                        );

              return (
                <button
                  key={tabItem.id}
                  type="button"
                  onClick={() => setTab(tabItem.id)}
                  className={cn(
                    'mb-2 flex w-full items-start gap-3 rounded-2xl border px-3 py-3 text-left transition last:mb-0',
                    tab === tabItem.id
                      ? 'border-blue-200 bg-blue-50/80 text-blue-700 shadow-[0_10px_24px_rgba(59,130,246,0.10)]'
                      : 'border-transparent text-slate-500 hover:border-slate-200 hover:bg-slate-50',
                  )}
                  data-testid={`list-tab-${tabItem.id}`}
                >
                  <span
                    className={cn(
                      'inline-flex h-10 w-10 shrink-0 items-center justify-center rounded-xl border',
                      tab === tabItem.id
                        ? 'border-blue-100 bg-white text-blue-600'
                        : 'border-slate-200 bg-slate-50 text-slate-400',
                    )}
                  >
                    <tabItem.icon className="h-5 w-5" />
                  </span>
                  <span className="min-w-0 flex-1">
                    <span className="flex items-center gap-2">
                      <span
                        className={cn(
                          'text-sm font-medium',
                          tab === tabItem.id ? 'text-slate-900' : 'text-slate-700',
                        )}
                      >
                        {t(tabItem.labelKey)}
                      </span>
                      <span
                        className={cn(
                          'rounded-full px-2 py-0.5 text-[11px] font-medium',
                          tab === tabItem.id
                            ? 'bg-blue-100 text-blue-700'
                            : 'bg-slate-100 text-slate-500',
                        )}
                      >
                        {count}
                      </span>
                    </span>
                    <span className="mt-1 block text-xs leading-5 text-slate-500">
                      {t(tabItem.descriptionKey)}
                    </span>
                  </span>
                </button>
              );
            })}
          </nav>
        </aside>
      )}

      <main className="min-w-0 px-4 py-6 lg:px-6" data-testid="list-config-main">
        <div className="mx-auto max-w-6xl space-y-6">
          <section
            className="rounded-[28px] border border-slate-200 bg-white/90 p-6 shadow-[0_16px_50px_rgba(15,23,42,0.08)]"
            data-testid="list-designer-summary"
          >
            <div className="flex min-w-0 flex-col gap-6">
              <div className="max-w-2xl">
                <div className="inline-flex items-center gap-2 rounded-full bg-blue-50 px-3 py-1 text-xs font-semibold uppercase tracking-[0.16em] text-blue-700">
                  {t('list_designer.editing')}
                </div>
                <h1 className="mt-4 text-3xl font-semibold tracking-tight text-slate-950">
                  {t(activeTabMeta.labelKey)}
                </h1>
                <p className="mt-3 text-sm leading-6 text-slate-500">
                  {t(activeTabMeta.descriptionKey)}
                </p>
                <div className="mt-4 flex flex-wrap gap-2">
                  <span className="rounded-full bg-slate-100 px-3 py-1 text-xs font-medium text-slate-600">
                    {t('list_designer.stage', { label: t(activeTabMeta.labelKey) })}
                  </span>
                  <span className="rounded-full bg-slate-100 px-3 py-1 text-xs font-medium text-slate-600">
                    {t('list_designer.preview_sync')}
                  </span>
                </div>
              </div>
              <div className="grid gap-3 sm:grid-cols-3">
                {summaryStats.map((item) => (
                  <div
                    key={item.label}
                    className={cn(
                      'min-w-0 rounded-2xl border px-3 py-4',
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
                <div className="font-semibold text-slate-900">{t('list_designer.capability_failure')}</div>
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
                {t('list_designer.validation')}
              </div>
              <div className="mb-2 font-semibold text-slate-900">
                {hasBlockingErrors(errors) ? t('list_designer.conflict') : t('list_designer.validation_hint')}
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
            className="list-config-workspace rounded-[28px] border border-slate-200 bg-white/90 p-6 shadow-sm"
            data-testid="list-designer-workspace"
          >
            <div className="mb-6 flex flex-col gap-3 border-b border-slate-100 pb-5">
              <div className="text-xs font-semibold uppercase tracking-[0.18em] text-slate-400">
                {t('list_designer.workspace')}
              </div>
              <div className="text-lg font-semibold text-slate-950">
                {t(activeTabMeta.labelKey)}
              </div>
              <div className="text-sm text-slate-500">
                {t('list_designer.workspace_description')}
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
          className="list-config-preview min-w-0 border-l border-slate-200 bg-white/90 px-4 py-6"
          data-testid="list-preview-pane"
        >
          <div className="rounded-[28px] border border-slate-200 bg-white/95 shadow-[0_16px_40px_rgba(15,23,42,0.08)] backdrop-blur">
            <div className="border-b border-slate-200 px-5 py-5">
              <div className="text-xs font-semibold uppercase tracking-[0.18em] text-slate-400">
                {t('list_designer.preview')}
              </div>
              <div className="mt-2 text-xl font-semibold text-slate-950">{t('list_designer.preview_heading')}</div>
              <div className="mt-2 text-sm leading-6 text-slate-500">
                {t('list_designer.preview_description')}
              </div>
              <div className="mt-4 flex flex-wrap gap-2">
                <span className="rounded-full bg-slate-100 px-3 py-1 text-xs font-medium text-slate-600">
                  {t('list_designer.preview_columns', { count: vm.columns.length })}
                </span>
                <span className="rounded-full bg-slate-100 px-3 py-1 text-xs font-medium text-slate-600">
                  {t('list_designer.preview_filters', { count: vm.filters.length })}
                </span>
                <span className="rounded-full bg-slate-100 px-3 py-1 text-xs font-medium text-slate-600">
                  {t('list_designer.preview_actions', { count: toolbarActionCount })}
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
    </div>
  );
};

export default ListConfigPanel;
