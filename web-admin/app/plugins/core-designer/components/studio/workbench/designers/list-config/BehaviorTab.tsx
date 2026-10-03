import React, { useMemo } from 'react';
import { useI18n } from '~/contexts/I18nContext';
import type { ModelCapabilities } from '~/shared/hooks/useModelCapabilities';
import { SchemaBlockConfigPanel } from '~/shared/designer/SchemaBlockConfigPanel';
import { buildBehaviorSchemas } from './schema';
import type { ListViewModel, BehaviorConfig } from './mapper';

export interface BehaviorTabProps {
  vm: ListViewModel;
  setVm: (next: ListViewModel) => void;
  capabilities: ModelCapabilities | undefined;
  readonly?: boolean;
  loading?: boolean;
  capabilityError?: Error;
  fallbackSortFields?: string[];
}

function sectionCardClasses(extra?: string): string {
  return ['rounded-[28px] border border-slate-200 bg-white/90 p-6 shadow-sm', extra]
    .filter(Boolean)
    .join(' ');
}

/**
 * Sentinel used in the defaultSortField <Select.Item> to represent "no sort
 * field selected". Radix Select.Item forbids empty-string values, so we map:
 *   VM undefined/'' → display '__none__'
 *   display '__none__' → VM undefined
 */
const SORT_FIELD_NONE = '__none__';

function rowClickActionLabel(
  value: BehaviorConfig['rowClickAction'] | undefined,
  t: (key: string) => string,
): string {
  switch (value) {
    case 'drawer':
      return t('list_behavior.open_drawer');
    case 'none':
      return t('list_behavior.no_action');
    case 'detail':
    default:
      return t('list_behavior.open_detail');
  }
}

/** Convert VM BehaviorConfig → panel display shape (sentinel for empty sort field). */
function behaviorToDisplay(behavior: BehaviorConfig): Record<string, unknown> {
  return {
    ...(behavior as unknown as Record<string, unknown>),
    defaultSortField: behavior.defaultSortField || SORT_FIELD_NONE,
  };
}

/** Convert panel display shape → VM BehaviorConfig (strip sentinel back to undefined). */
function displayToBehavior(display: Record<string, unknown>): BehaviorConfig {
  const next = { ...display } as Record<string, unknown>;
  if (next.defaultSortField === SORT_FIELD_NONE) {
    delete next.defaultSortField;
  }
  return next as unknown as BehaviorConfig;
}

export const BehaviorTab: React.FC<BehaviorTabProps> = ({
  vm,
  setVm,
  capabilities,
  readonly,
  loading,
  capabilityError,
  fallbackSortFields = [],
}) => {
  const { t } = useI18n();
  const sortableFields = capabilities?.sortableFields ?? fallbackSortFields;
  const schemas = useMemo(
    () => buildBehaviorSchemas(sortableFields, capabilities?.filterableFields ?? [], t),
    [capabilities, sortableFields, t],
  );

  if (!capabilities && loading && sortableFields.length === 0) {
    return (
      <div className="space-y-4" data-testid="behavior-tab">
        <div className={sectionCardClasses()}>
          <div className="text-xs font-semibold tracking-[0.16em] text-slate-400 uppercase">
            {t('list_behavior.configuration')}
          </div>
          <div className="mt-2 text-sm text-slate-500">{t('list_behavior.loading')}</div>
        </div>
      </div>
    );
  }

  return (
    <div className="space-y-5" data-testid="behavior-tab">
      {capabilityError && (
        <div className="rounded-[28px] border border-amber-200 bg-amber-50 px-5 py-5 text-sm text-amber-800">
          {t('list_behavior.capability_error')}
        </div>
      )}
      <section className={sectionCardClasses()}>
        <div
          className="list-config-tab-header border-b border-slate-200 pb-4"
          data-testid="list-tab-summary-behavior"
        >
          <div className="min-w-0 flex-1">
            <div className="inline-flex items-center gap-2 rounded-full bg-slate-100 px-3 py-1 text-xs font-semibold tracking-[0.16em] text-slate-500 uppercase">
              {t('list_behavior.rules')}
            </div>
            <h2 className="mt-4 text-2xl font-semibold tracking-tight text-slate-950">
              {t('list_behavior.heading')}
            </h2>
            <p className="mt-2 text-sm leading-6 text-slate-500">
              {t('list_behavior.description')}
            </p>
          </div>
          <div className="list-config-tab-stats" data-testid="list-tab-statistics-behavior">
            <div className="rounded-2xl border border-slate-200 bg-white px-4 py-3">
              <div className="text-[11px] tracking-[0.14em] text-slate-400 uppercase">
                {t('list_behavior.default_pagination')}
              </div>
              <div className="mt-2 text-xl font-semibold text-slate-900">
                {vm.behavior.pageSize}
              </div>
            </div>
            <div className="rounded-2xl border border-slate-200 bg-white px-4 py-3">
              <div className="text-[11px] tracking-[0.14em] text-slate-400 uppercase">
                {t('list_behavior.row_click')}
              </div>
              <div className="mt-2 text-xl font-semibold text-slate-900">
                {rowClickActionLabel(vm.behavior.rowClickAction, t)}
              </div>
            </div>
            <div className="rounded-2xl border border-slate-200 bg-white px-4 py-3">
              <div className="text-[11px] tracking-[0.14em] text-slate-400 uppercase">
                {t('list_behavior.multi_select')}
              </div>
              <div className="mt-2 text-xl font-semibold text-slate-900">
                {vm.behavior.multiSelect ? t('list_behavior.on') : t('list_behavior.off')}
              </div>
            </div>
          </div>
        </div>
        <div className="mt-4 flex flex-wrap gap-2">
          <span className="rounded-full bg-white px-3 py-1 text-xs font-medium text-slate-500">
            {t('list_behavior.sort_tip')}
          </span>
          <span className="rounded-full bg-white px-3 py-1 text-xs font-medium text-slate-500">
            {t('list_behavior.row_tip')}
          </span>
          <span className="rounded-full bg-white px-3 py-1 text-xs font-medium text-slate-500">
            {t('list_behavior.pagination_tip')}
          </span>
        </div>
        <div className="mt-5 rounded-[24px] border border-slate-200 bg-slate-50/70 p-5">
          <div className="mb-4 border-b border-slate-200 pb-4">
            <div className="text-xs font-semibold tracking-[0.16em] text-slate-400 uppercase">
              {t('list_behavior.properties')}
            </div>
            <div className="mt-2 text-base font-semibold text-slate-950">
              {t('list_behavior.properties_heading')}
            </div>
            <div className="mt-1 text-sm text-slate-500">
              {t('list_behavior.properties_description')}
            </div>
          </div>
          <SchemaBlockConfigPanel
            schemas={schemas}
            value={behaviorToDisplay(vm.behavior)}
            onChange={(next) => setVm({ ...vm, behavior: displayToBehavior(next) })}
            readonly={readonly}
          />
        </div>
      </section>
    </div>
  );
};
