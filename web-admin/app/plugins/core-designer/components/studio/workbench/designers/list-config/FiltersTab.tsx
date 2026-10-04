import React, { useMemo, useState } from 'react';
import { useI18n } from '~/contexts/I18nContext';
import type { ModelCapabilities } from '~/shared/hooks/useModelCapabilities';
import { SchemaBlockConfigPanel } from '~/shared/designer/SchemaBlockConfigPanel';
import { buildFilterDetailSchemas } from './schema';
import type { ListViewModel, FilterConfig } from './mapper';
import type { ResolvedFieldLite } from './ColumnsTab';

export interface FiltersTabProps {
  vm: ListViewModel;
  setVm: (next: ListViewModel) => void;
  fields: ResolvedFieldLite[] | undefined;
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

export const FiltersTab: React.FC<FiltersTabProps> = ({
  vm,
  setVm,
  fields,
  capabilities,
  readonly,
  loading,
  capabilityError,
}) => {
  const { t } = useI18n();
  const schemas = useMemo(() => buildFilterDetailSchemas(t), [t]);
  const [selectedIdx, setSelectedIdx] = useState<number | null>(null);
  const [search, setSearch] = useState('');
  const fieldMap = useMemo(
    () => new Map((fields ?? []).map((field) => [field.code, field])),
    [fields],
  );

  // Whitelist: only fields in capabilities.filterableFields are allowed.
  // If capability lookup fails, fall back to the fields already referenced by
  // the current page so the designer can still repair the schema.
  const allowedFields = useMemo(() => {
    if (!fields) return [] as ResolvedFieldLite[];
    if (!capabilities) return fields;
    const whitelist = new Set(capabilities.filterableFields);
    return fields.filter((f) => whitelist.has(f.code));
  }, [fields, capabilities]);

  const selectedCodes = useMemo(() => new Set(vm.filters.map((f) => f.field)), [vm.filters]);
  const filteredAllowedFields = useMemo(() => {
    const keyword = search.trim().toLowerCase();
    if (!keyword) return allowedFields;
    return allowedFields.filter((field) => {
      const haystacks = [field.code, field.displayName, field.dataType]
        .filter(Boolean)
        .map((value) => String(value).toLowerCase());
      return haystacks.some((value) => value.includes(keyword));
    });
  }, [allowedFields, search]);

  if (!fields && loading) {
    return (
      <div className="space-y-4" data-testid="filters-tab">
        <div className={sectionCardClasses()}>
          <div className="text-xs font-semibold tracking-[0.16em] text-slate-400 uppercase">
            {t('list_editor.filter_configuration')}
          </div>
          <div className="mt-2 text-sm text-slate-500">{t('list_editor.filters_loading')}</div>
        </div>
      </div>
    );
  }

  const toggleField = (code: string) => {
    if (readonly) return;
    if (selectedCodes.has(code)) {
      const idx = vm.filters.findIndex((f) => f.field === code);
      const next = vm.filters.filter((_, i) => i !== idx);
      setVm({ ...vm, filters: next });
      if (selectedIdx === idx) setSelectedIdx(null);
    } else {
      setVm({ ...vm, filters: [...vm.filters, { field: code }] });
    }
  };

  const updateFilter = (idx: number, patch: Partial<FilterConfig>) => {
    const next = vm.filters.map((f, i) => (i === idx ? { ...f, ...patch } : f));
    setVm({ ...vm, filters: next });
  };

  const selected = selectedIdx !== null ? vm.filters[selectedIdx] : null;

  return (
    <div className="space-y-5" data-testid="filters-tab">
      <section className={sectionCardClasses()}>
        <div className="flex flex-col gap-3 border-b border-slate-200 pb-4 sm:flex-row sm:items-end sm:justify-between">
          <div>
            <div className="text-xs font-semibold tracking-[0.16em] text-slate-400 uppercase">
              {t('list_editor.filter_pool')}
            </div>
            <h2 className="mt-2 text-lg font-semibold text-slate-900">
              {t('list_editor.filters_heading')}
            </h2>
            <p className="mt-2 text-sm leading-6 text-slate-500">
              {t('list_editor.filters_description')}
            </p>
          </div>
          <div className="rounded-2xl border border-slate-200 bg-white px-4 py-3 text-sm text-slate-600">
            {t('list_editor.selected')}
            <span className="font-semibold text-slate-900">{vm.filters.length}</span> /{' '}
            {allowedFields.length}
          </div>
        </div>

        {allowedFields.length === 0 ? (
          <div className="mt-4 rounded-2xl border border-dashed border-slate-300 bg-white px-4 py-6 text-sm text-slate-400">
            {capabilityError
              ? t('list_editor.filters_no_fallback')
              : t('list_editor.filters_unavailable')}
          </div>
        ) : (
          <div className="mt-4 max-h-72 overflow-auto rounded-2xl border border-slate-200 bg-white p-3">
            {capabilityError && (
              <div className="mb-3 rounded-2xl border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-800">
                {t('list_editor.filters_capability_error')}
              </div>
            )}
            <div className="mb-3 flex items-center gap-3 rounded-2xl border border-slate-200 bg-slate-50 px-3 py-2">
              <span className="text-xs font-semibold tracking-[0.16em] text-slate-400 uppercase">
                {t('list_editor.search')}
              </span>
              <input
                type="text"
                value={search}
                onChange={(event) => setSearch(event.target.value)}
                placeholder={t('list_editor.filters_search_placeholder')}
                className="w-full bg-transparent text-sm text-slate-700 outline-none placeholder:text-slate-400"
                data-testid="filter-search-input"
              />
            </div>
            <div className="space-y-2">
              {filteredAllowedFields.map((f) => (
                <label
                  key={f.code}
                  className="flex items-center gap-3 rounded-2xl px-3 py-2 text-sm transition hover:bg-slate-50"
                >
                  <input
                    type="checkbox"
                    checked={selectedCodes.has(f.code)}
                    onChange={() => toggleField(f.code)}
                    disabled={readonly}
                    data-testid={`filter-toggle-${f.code}`}
                  />
                  <span className="min-w-0 flex-1">
                    <span className="block font-medium text-slate-800">
                      {f.displayName ?? f.code}
                    </span>
                    <span className="block text-xs text-slate-400">{f.code}</span>
                  </span>
                  <span className="rounded-full bg-slate-100 px-2 py-1 text-[11px] text-slate-500">
                    {f.dataType ?? t('list_editor.unknown_type')}
                  </span>
                </label>
              ))}
              {filteredAllowedFields.length === 0 && (
                <div className="rounded-2xl border border-dashed border-slate-300 px-4 py-6 text-center text-sm text-slate-400">
                  {t('list_editor.filters_no_match')}
                </div>
              )}
            </div>
          </div>
        )}
      </section>

      <section className={sectionCardClasses()}>
        <div className="mb-4 flex items-center justify-between">
          <div>
            <div className="text-xs font-semibold tracking-[0.16em] text-slate-400 uppercase">
              {t('list_editor.selected_filters')}
            </div>
            <h3 className="mt-2 text-base font-semibold text-slate-900">
              {t('list_editor.selected_filter_items')}
            </h3>
          </div>
          <div className="text-sm text-slate-500">{t('list_editor.filter_settings_hint')}</div>
        </div>

        {vm.filters.length === 0 ? (
          <div className="rounded-2xl border border-dashed border-slate-300 bg-white px-4 py-6 text-sm text-slate-400">
            {t('list_editor.filters_empty')}
          </div>
        ) : (
          <div className="space-y-3">
            <div className="flex flex-wrap gap-2">
              <span className="rounded-full bg-white px-3 py-1 text-xs font-medium text-slate-500">
                {t('list_editor.status_filter_hint')}
              </span>
              <span className="rounded-full bg-white px-3 py-1 text-xs font-medium text-slate-500">
                {t('list_editor.time_filter_hint')}
              </span>
              <span className="rounded-full bg-white px-3 py-1 text-xs font-medium text-slate-500">
                {t('list_editor.infrequent_filter_hint')}
              </span>
            </div>
            <ol className="space-y-2">
              {vm.filters.map((f, i) => (
                <li
                  key={`${f.field}-${i}`}
                  className={`rounded-2xl border bg-white px-4 py-4 text-sm transition ${
                    selectedIdx === i ? 'border-blue-200 bg-blue-50/70' : 'border-slate-200'
                  }`}
                >
                  <div className="flex items-start justify-between gap-3">
                    <button
                      type="button"
                      className="min-w-0 flex-1 text-left"
                      onClick={() => setSelectedIdx(selectedIdx === i ? null : i)}
                      data-testid={`filter-item-${i}`}
                    >
                      <span className="block font-medium text-slate-800">
                        {fieldMap.get(f.field)?.displayName ?? f.field}
                      </span>
                      <span className="mt-1 block text-xs text-slate-400">{f.field}</span>
                      <span className="mt-3 flex flex-wrap gap-2">
                        <span className="rounded-full bg-slate-100 px-2.5 py-1 text-[11px] text-slate-600">
                          {operatorLabel(f.operator, t)}
                        </span>
                        <span className="rounded-full bg-slate-100 px-2.5 py-1 text-[11px] text-slate-600">
                          {displayModeLabel(f.displayMode, t)}
                        </span>
                        {f.defaultValue !== undefined && f.defaultValue !== '' ? (
                          <span className="rounded-full bg-blue-50 px-2.5 py-1 text-[11px] text-blue-700">
                            {t('list_editor.default_prefix')}
                            {String(f.defaultValue)}
                          </span>
                        ) : (
                          <span className="rounded-full bg-slate-100 px-2.5 py-1 text-[11px] text-slate-600">
                            {t('list_editor.no_default')}
                          </span>
                        )}
                      </span>
                    </button>
                    <button
                      type="button"
                      onClick={() => toggleField(f.field)}
                      disabled={readonly}
                      className="rounded-xl border border-red-200 px-3 py-1.5 text-xs text-red-600 disabled:opacity-30"
                      aria-label={t('list_editor.remove_filter')}
                    >
                      {t('list_editor.remove')}
                    </button>
                  </div>
                  <div className="mt-4 flex flex-wrap gap-2 border-t border-slate-100 pt-3">
                    <button
                      type="button"
                      onClick={() =>
                        updateFilter(i, {
                          displayMode: nextDisplayMode(f.displayMode),
                        })
                      }
                      disabled={readonly}
                      className="rounded-full border border-slate-200 bg-slate-50 px-3 py-1 text-[11px] font-medium text-slate-600 disabled:opacity-30"
                    >
                      {t('list_editor.display_prefix')}
                      {displayModeLabel(f.displayMode, t)}
                    </button>
                    <button
                      type="button"
                      onClick={() =>
                        updateFilter(i, {
                          operator: nextOperator(f.operator),
                        })
                      }
                      disabled={readonly}
                      className="rounded-full border border-slate-200 bg-slate-50 px-3 py-1 text-[11px] font-medium text-slate-600 disabled:opacity-30"
                    >
                      {t('list_editor.condition_prefix')}
                      {operatorLabel(f.operator, t)}
                    </button>
                  </div>
                </li>
              ))}
            </ol>
          </div>
        )}
      </section>

      <section className={sectionCardClasses()} data-testid="filter-detail-editor">
        <div className="mb-4 border-b border-slate-200 pb-4">
          <div className="text-xs font-semibold tracking-[0.16em] text-slate-400 uppercase">
            {t('list_editor.filter_properties')}
          </div>
          <h3 className="mt-2 text-base font-semibold text-slate-900">
            {selected
              ? t('list_editor.filter_properties_for', { field: selected.field })
              : t('list_editor.filter_properties')}
          </h3>
          <p className="mt-2 text-sm text-slate-500">{t('list_editor.filter_properties_hint')}</p>
        </div>
        {selected && selectedIdx !== null ? (
          <SchemaBlockConfigPanel
            schemas={schemas}
            value={selected as unknown as Record<string, unknown>}
            onChange={(next) => updateFilter(selectedIdx, next as Partial<FilterConfig>)}
            readonly={readonly}
          />
        ) : (
          <div className="rounded-2xl border border-dashed border-slate-300 bg-white px-4 py-6 text-sm text-slate-400">
            {t('list_editor.filter_properties_empty')}
          </div>
        )}
      </section>
    </div>
  );
};

function operatorLabel(operator: string | undefined, t: (key: string) => string): string {
  switch (operator) {
    case 'neq':
      return t('list_editor.not_equal');
    case 'like':
      return t('list_editor.contains');
    case 'between':
      return t('list_editor.range');
    case 'gt':
      return t('list_editor.greater_than');
    case 'gte':
      return t('list_editor.greater_or_equal');
    case 'lt':
      return t('list_editor.less_than');
    case 'lte':
      return t('list_editor.less_or_equal');
    case 'eq':
    default:
      return t('list_editor.equal');
  }
}

function displayModeLabel(
  mode: FilterConfig['displayMode'] | undefined,
  t: (key: string) => string,
): string {
  switch (mode) {
    case 'drawer':
      return t('list_editor.drawer');
    case 'top-bar':
      return t('list_editor.top_bar');
    case 'inline':
    default:
      return t('list_editor.inline');
  }
}

function nextDisplayMode(mode?: FilterConfig['displayMode']): FilterConfig['displayMode'] {
  const order: FilterConfig['displayMode'][] = ['inline', 'top-bar', 'drawer'];
  const current = order.indexOf(mode || 'inline');
  return order[(current + 1) % order.length];
}

function nextOperator(operator?: string): string {
  const order = ['eq', 'like', 'between'];
  const current = order.indexOf(operator || 'eq');
  return order[(current + 1) % order.length];
}
