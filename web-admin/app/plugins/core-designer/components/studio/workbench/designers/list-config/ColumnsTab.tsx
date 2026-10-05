import React, { useMemo, useState } from 'react';
import { useI18n } from '~/contexts/I18nContext';
import { SchemaBlockConfigPanel } from '~/shared/designer/SchemaBlockConfigPanel';
import { buildColumnDetailSchemas } from './schema';
import type { ListViewModel, ColumnConfig } from './mapper';

export interface ResolvedFieldLite {
  code: string;
  displayName?: string;
  dataType?: string;
}

export interface ColumnsTabProps {
  vm: ListViewModel;
  setVm: (next: ListViewModel) => void;
  fields: ResolvedFieldLite[] | undefined;
  readonly?: boolean;
  loading?: boolean;
  capabilityError?: Error;
}

function sectionCardClasses(extra?: string): string {
  return ['rounded-3xl border border-slate-200 bg-slate-50/70 p-5', extra]
    .filter(Boolean)
    .join(' ');
}

export const ColumnsTab: React.FC<ColumnsTabProps> = ({
  vm,
  setVm,
  fields,
  readonly,
  loading,
  capabilityError,
}) => {
  const { t } = useI18n();
  const schemas = useMemo(() => buildColumnDetailSchemas(t), [t]);
  const [selectedIdx, setSelectedIdx] = useState<number | null>(null);
  const [search, setSearch] = useState('');
  const fieldMap = useMemo(
    () => new Map((fields ?? []).map((field) => [field.code, field])),
    [fields],
  );

  const selectedCodes = useMemo(() => new Set(vm.columns.map((c) => c.field)), [vm.columns]);
  const filteredFields = useMemo(() => {
    const keyword = search.trim().toLowerCase();
    if (!keyword) return fields ?? [];
    return (fields ?? []).filter((field) => {
      const haystacks = [field.code, field.displayName, field.dataType]
        .filter(Boolean)
        .map((value) => String(value).toLowerCase());
      return haystacks.some((value) => value.includes(keyword));
    });
  }, [fields, search]);

  if (!fields && loading) {
    return (
      <div className="space-y-4" data-testid="columns-tab">
        <div className={sectionCardClasses()}>
          <div className="text-xs font-semibold tracking-[0.16em] text-slate-400 uppercase">
            {t('list_editor.column_configuration')}
          </div>
          <div className="mt-2 text-sm text-slate-500">{t('list_editor.columns_loading')}</div>
        </div>
      </div>
    );
  }

  const availableFields = fields ?? [];

  const toggleField = (code: string) => {
    if (readonly) return;
    if (selectedCodes.has(code)) {
      const idx = vm.columns.findIndex((c) => c.field === code);
      const next = vm.columns.filter((_, i) => i !== idx);
      setVm({ ...vm, columns: next });
      if (selectedIdx === idx) setSelectedIdx(null);
    } else {
      setVm({ ...vm, columns: [...vm.columns, { field: code }] });
    }
  };

  const updateColumn = (idx: number, patch: Partial<ColumnConfig>) => {
    const next = vm.columns.map((c, i) => (i === idx ? { ...c, ...patch } : c));
    setVm({ ...vm, columns: next });
  };

  const move = (idx: number, delta: number) => {
    const target = idx + delta;
    if (target < 0 || target >= vm.columns.length) return;
    const next = [...vm.columns];
    [next[idx], next[target]] = [next[target], next[idx]];
    setVm({ ...vm, columns: next });
    setSelectedIdx(target);
  };

  const selected = selectedIdx !== null ? vm.columns[selectedIdx] : null;

  return (
    <div className="space-y-5" data-testid="columns-tab">
      <section className={sectionCardClasses()}>
        <div className="flex flex-col gap-3 border-b border-slate-200 pb-4 sm:flex-row sm:items-end sm:justify-between">
          <div>
            <div className="text-xs font-semibold tracking-[0.16em] text-slate-400 uppercase">
              {t('list_editor.field_pool')}
            </div>
            <h2 className="mt-2 text-lg font-semibold text-slate-900">
              {t('list_editor.columns_heading')}
            </h2>
            <p className="mt-2 text-sm leading-6 text-slate-500">
              {t('list_editor.columns_description')}
            </p>
          </div>
          <div className="rounded-2xl border border-slate-200 bg-white px-4 py-3 text-sm text-slate-600">
            {t('list_editor.selected')}
            <span className="font-semibold text-slate-900">{vm.columns.length}</span> /{' '}
            {availableFields.length}
          </div>
        </div>
        {capabilityError && (
          <div className="mt-4 rounded-2xl border border-amber-200 bg-amber-50 px-4 py-4 text-sm text-amber-800">
            {t('list_editor.columns_capability_error')}
          </div>
        )}
        <div className="mt-4 max-h-72 overflow-auto rounded-2xl border border-slate-200 bg-white p-3">
          <div className="mb-3 flex items-center gap-3 rounded-2xl border border-slate-200 bg-slate-50 px-3 py-2">
            <span className="text-xs font-semibold tracking-[0.16em] text-slate-400 uppercase">
              {t('list_editor.search')}
            </span>
            <input
              type="text"
              value={search}
              onChange={(event) => setSearch(event.target.value)}
              placeholder={t('list_editor.columns_search_placeholder')}
              className="w-full bg-transparent text-sm text-slate-700 outline-none placeholder:text-slate-400"
              data-testid="column-search-input"
            />
          </div>
          <div className="space-y-2">
            {filteredFields.map((f) => (
              <label
                key={f.code}
                className="flex items-center gap-3 rounded-2xl px-3 py-2 text-sm transition hover:bg-slate-50"
              >
                <input
                  type="checkbox"
                  checked={selectedCodes.has(f.code)}
                  onChange={() => toggleField(f.code)}
                  disabled={readonly}
                  data-testid={`column-toggle-${f.code}`}
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
            {filteredFields.length === 0 && (
              <div className="rounded-2xl border border-dashed border-slate-300 px-4 py-6 text-center text-sm text-slate-400">
                {capabilityError
                  ? t('list_editor.columns_no_fallback')
                  : t('list_editor.columns_no_match')}
              </div>
            )}
          </div>
        </div>
      </section>

      <section className={sectionCardClasses()}>
        <div className="mb-4 flex items-center justify-between">
          <div>
            <div className="text-xs font-semibold tracking-[0.16em] text-slate-400 uppercase">
              {t('list_editor.reading_order')}
            </div>
            <h3 className="mt-2 text-base font-semibold text-slate-900">
              {t('list_editor.selected_columns')}
            </h3>
          </div>
          <div className="text-sm text-slate-500">{t('list_editor.column_order_hint')}</div>
        </div>

        {vm.columns.length === 0 ? (
          <div className="rounded-2xl border border-dashed border-slate-300 bg-white px-4 py-6 text-sm text-slate-400">
            {t('list_editor.columns_empty')}
          </div>
        ) : (
          <div className="space-y-3">
            <div className="flex flex-wrap gap-2">
              <span className="rounded-full bg-white px-3 py-1 text-xs font-medium text-slate-500">
                {t('list_editor.first_column_hint')}
              </span>
              <span className="rounded-full bg-white px-3 py-1 text-xs font-medium text-slate-500">
                {t('list_editor.middle_columns_hint')}
              </span>
              <span className="rounded-full bg-white px-3 py-1 text-xs font-medium text-slate-500">
                {t('list_editor.infrequent_column_hint')}
              </span>
            </div>
            <ol className="space-y-2">
              {vm.columns.map((c, i) => (
                <li
                  key={`${c.field}-${i}`}
                  className={`rounded-2xl border bg-white px-4 py-4 text-sm transition ${
                    selectedIdx === i ? 'border-blue-200 bg-blue-50/70' : 'border-slate-200'
                  }`}
                >
                  <div className="flex items-start justify-between gap-3">
                    <button
                      type="button"
                      className="flex min-w-0 flex-1 items-start gap-3 text-left"
                      onClick={() => setSelectedIdx(selectedIdx === i ? null : i)}
                      data-testid={`column-item-${i}`}
                    >
                      <span className="inline-flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-slate-100 text-xs font-semibold text-slate-500">
                        {i + 1}
                      </span>
                      <span className="min-w-0 flex-1">
                        <span className="block truncate font-medium text-slate-800">
                          {fieldMap.get(c.field)?.displayName ?? c.field}
                        </span>
                        <span className="mt-1 block text-xs text-slate-400">{c.field}</span>
                        <span className="mt-3 flex flex-wrap gap-2">
                          <span className="rounded-full bg-slate-100 px-2.5 py-1 text-[11px] text-slate-600">
                            {fieldMap.get(c.field)?.dataType ?? t('list_editor.unknown_type')}
                          </span>
                          <span className="rounded-full bg-slate-100 px-2.5 py-1 text-[11px] text-slate-600">
                            {c.width ? `${c.width}px` : t('list_editor.auto_width')}
                          </span>
                          <span className="rounded-full bg-slate-100 px-2.5 py-1 text-[11px] text-slate-600">
                            {rendererLabel(c.renderer, t)}
                          </span>
                          <span className="rounded-full bg-slate-100 px-2.5 py-1 text-[11px] text-slate-600">
                            {alignLabel(c.align, t)}
                          </span>
                        </span>
                      </span>
                    </button>
                    <div className="ml-3 flex gap-2">
                      <button
                        type="button"
                        onClick={() => move(i, -1)}
                        disabled={i === 0 || readonly}
                        className="rounded-xl border border-slate-200 px-3 py-1.5 text-xs text-slate-600 disabled:opacity-30"
                        aria-label={t('list_editor.move_up')}
                      >
                        {t('list_editor.move_up')}
                      </button>
                      <button
                        type="button"
                        onClick={() => move(i, 1)}
                        disabled={i === vm.columns.length - 1 || readonly}
                        className="rounded-xl border border-slate-200 px-3 py-1.5 text-xs text-slate-600 disabled:opacity-30"
                        aria-label={t('list_editor.move_down')}
                      >
                        {t('list_editor.move_down')}
                      </button>
                      <button
                        type="button"
                        onClick={() => toggleField(c.field)}
                        disabled={readonly}
                        className="rounded-xl border border-red-200 px-3 py-1.5 text-xs text-red-600 disabled:opacity-30"
                        aria-label={t('list_editor.remove_column')}
                      >
                        {t('list_editor.remove')}
                      </button>
                    </div>
                  </div>
                  <div className="mt-4 flex flex-wrap gap-2 border-t border-slate-100 pt-3">
                    <button
                      type="button"
                      onClick={() =>
                        updateColumn(i, {
                          renderer: nextRenderer(c.renderer),
                        })
                      }
                      disabled={readonly}
                      className="rounded-full border border-slate-200 bg-slate-50 px-3 py-1 text-[11px] font-medium text-slate-600 disabled:opacity-30"
                    >
                      {t('list_editor.renderer_prefix')}
                      {rendererLabel(c.renderer, t)}
                    </button>
                    <button
                      type="button"
                      onClick={() =>
                        updateColumn(i, {
                          align: nextAlign(c.align),
                        })
                      }
                      disabled={readonly}
                      className="rounded-full border border-slate-200 bg-slate-50 px-3 py-1 text-[11px] font-medium text-slate-600 disabled:opacity-30"
                    >
                      {t('list_editor.align_prefix')}
                      {alignLabel(c.align, t)}
                    </button>
                  </div>
                </li>
              ))}
            </ol>
          </div>
        )}
      </section>

      <section className={sectionCardClasses()} data-testid="column-detail-editor">
        <div className="mb-4 border-b border-slate-200 pb-4">
          <div className="text-xs font-semibold tracking-[0.16em] text-slate-400 uppercase">
            {t('list_editor.column_properties')}
          </div>
          <h3 className="mt-2 text-base font-semibold text-slate-900">
            {selected
              ? t('list_editor.column_properties_for', { field: selected.field })
              : t('list_editor.column_properties')}
          </h3>
          <p className="mt-2 text-sm text-slate-500">{t('list_editor.column_properties_hint')}</p>
        </div>
        {selected && selectedIdx !== null ? (
          <SchemaBlockConfigPanel
            schemas={schemas}
            value={selected as unknown as Record<string, unknown>}
            onChange={(next) => updateColumn(selectedIdx, next as Partial<ColumnConfig>)}
            readonly={readonly}
          />
        ) : (
          <div className="rounded-2xl border border-dashed border-slate-300 bg-white px-4 py-6 text-sm text-slate-400">
            {t('list_editor.column_properties_empty')}
          </div>
        )}
      </section>
    </div>
  );
};

function rendererLabel(renderer: string | undefined, t: (key: string) => string): string {
  switch (renderer) {
    case 'badge':
      return t('list_editor.badge');
    case 'link':
      return t('list_editor.link');
    case 'image':
      return t('list_editor.image');
    case 'richtext':
      return t('list_editor.richtext');
    case 'text':
    default:
      return t('list_editor.text');
  }
}

function alignLabel(align: ColumnConfig['align'] | undefined, t: (key: string) => string): string {
  switch (align) {
    case 'center':
      return t('list_editor.center_align');
    case 'right':
      return t('list_editor.right_align');
    case 'left':
    default:
      return t('list_editor.left_align');
  }
}

function nextRenderer(renderer?: string): ColumnConfig['renderer'] {
  const order: Array<ColumnConfig['renderer']> = ['text', 'badge', 'link'];
  const current = order.indexOf((renderer as ColumnConfig['renderer']) || 'text');
  return order[(current + 1) % order.length];
}

function nextAlign(align?: ColumnConfig['align']): ColumnConfig['align'] {
  const order: ColumnConfig['align'][] = ['left', 'center', 'right'];
  const current = order.indexOf(align || 'left');
  return order[(current + 1) % order.length];
}
