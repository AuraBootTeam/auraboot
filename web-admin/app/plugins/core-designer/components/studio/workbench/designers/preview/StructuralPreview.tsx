import React, { useMemo } from 'react';
import { useI18n } from '~/contexts/I18nContext';
import type { ListViewModel } from '../list-config/mapper';

export interface StructuralPreviewProps {
  vm: ListViewModel;
  fields?: Array<{ code: string; dataType?: string; displayName?: string }>;
  overrideRows?: Array<Record<string, unknown>>;
}

/**
 * Structural preview of a list page: filter chips + toolbar chips + mock
 * table driven by the current ViewModel. Mock data is generated from field
 * metadata; pass `overrideRows` to render real sample data fetched by
 * `SampleDataLoader`.
 *
 * Part of P3-T6 (virtual model backend plan).
 */
export const StructuralPreview: React.FC<StructuralPreviewProps> = ({
  vm,
  fields,
  overrideRows,
}) => {
  const { t } = useI18n();
  const fieldLabelMap = useMemo(
    () => new Map((fields ?? []).map((field) => [field.code, field.displayName || field.code])),
    [fields],
  );
  const rows = useMemo(() => {
    if (overrideRows && overrideRows.length > 0) return overrideRows;
    return generateMockRows(
      vm.columns.map((c) => c.field),
      fields ?? [],
      3,
    );
  }, [vm.columns, fields, overrideRows]);
  const totalActions = vm.toolbar.presets.length + vm.toolbar.customButtons.length;
  const pageSize = vm.behavior.pageSize || 20;
  const pageLabel = `${Math.max(rows.length, 1)} / ${pageSize}`;

  return (
    <div className="flex-1 overflow-auto bg-slate-50 px-5 py-5" data-testid="structural-preview">
      <div className="mb-4 grid grid-cols-2 gap-2 xl:grid-cols-4">
        <div className="rounded-2xl border border-slate-200 bg-white px-3 py-3">
          <div className="text-[11px] font-medium tracking-[0.14em] text-slate-400 uppercase">
            {t('list_editor.filters')}
          </div>
          <div className="mt-2 text-xl font-semibold text-slate-900">{vm.filters.length}</div>
        </div>
        <div className="rounded-2xl border border-slate-200 bg-white px-3 py-3">
          <div className="text-[11px] font-medium tracking-[0.14em] text-slate-400 uppercase">
            {t('list_editor.columns')}
          </div>
          <div className="mt-2 text-xl font-semibold text-slate-900">{vm.columns.length}</div>
        </div>
        <div className="rounded-2xl border border-slate-200 bg-white px-3 py-3">
          <div className="text-[11px] font-medium tracking-[0.14em] text-slate-400 uppercase">
            {t('list_editor.actions')}
          </div>
          <div className="mt-2 text-xl font-semibold text-slate-900">{totalActions}</div>
        </div>
        <div className="rounded-2xl border border-slate-200 bg-white px-3 py-3">
          <div className="text-[11px] font-medium tracking-[0.14em] text-slate-400 uppercase">
            {t('list_editor.page_size')}
          </div>
          <div className="mt-2 text-xl font-semibold text-slate-900">{pageSize}</div>
        </div>
      </div>

      {vm.filters.length > 0 && (
        <div className="mb-4 rounded-3xl border border-slate-200 bg-white p-4 text-xs shadow-sm">
          <div className="mb-2 text-[11px] font-semibold tracking-[0.14em] text-slate-400 uppercase">
            {t('list_editor.filters')}
          </div>
          <div className="flex flex-wrap gap-2">
            {vm.filters.map((f) => (
              <span
                key={f.field}
                className="rounded-full bg-slate-100 px-2.5 py-1 text-slate-600"
                data-testid={`preview-filter-${f.field}`}
              >
                {fieldLabelMap.get(f.field) || f.field}
                {f.operator ? ` · ${operatorLabel(f.operator, t)}` : ''}
                {f.defaultValue !== undefined && f.defaultValue !== ''
                  ? t('list_editor.filter_default_suffix', { value: String(f.defaultValue) })
                  : ''}
              </span>
            ))}
          </div>
        </div>
      )}

      {(vm.toolbar.presets.length > 0 || vm.toolbar.customButtons.length > 0) && (
        <div className="mb-4 rounded-3xl border border-slate-200 bg-white p-4 shadow-sm">
          <div className="mb-2 text-[11px] font-semibold tracking-[0.14em] text-slate-400 uppercase">
            {t('list_editor.toolbar')}
          </div>
          <div className="flex flex-wrap gap-2">
            {vm.toolbar.presets.map((p) => (
              <span
                key={p}
                className="rounded-full bg-blue-100 px-2.5 py-1 text-xs font-medium text-blue-700"
                data-testid={`preview-toolbar-${p}`}
              >
                {presetLabel(p, t)}
              </span>
            ))}
            {vm.toolbar.customButtons.map((b, i) => (
              <span
                key={`${b.command}-${i}`}
                className="rounded-full bg-violet-100 px-2.5 py-1 text-xs font-medium text-violet-700"
              >
                {b.label}
              </span>
            ))}
          </div>
        </div>
      )}

      {vm.columns.length === 0 ? (
        <div
          className="rounded-3xl border border-dashed border-slate-300 bg-white p-8 text-center text-xs text-slate-400"
          data-testid="preview-empty"
        >
          {t('list_editor.preview_empty')}
        </div>
      ) : (
        <div
          className="overflow-hidden rounded-3xl border border-slate-200 bg-white text-xs shadow-sm"
          data-testid="preview-table"
        >
          <div className="border-b border-slate-100 bg-slate-50 px-4 py-3">
            <div className="flex flex-wrap items-center gap-2">
              <span className="rounded-full bg-white px-3 py-1 text-[11px] font-medium text-slate-500">
                {t('list_editor.all_records')}
              </span>
              {vm.filters.slice(0, 2).map((filter) => (
                <span
                  key={`top-${filter.field}`}
                  className="rounded-full border border-slate-200 bg-white px-3 py-1 text-[11px] text-slate-500"
                >
                  {fieldLabelMap.get(filter.field) || filter.field}
                </span>
              ))}
              {vm.behavior.multiSelect ? (
                <span className="rounded-full border border-emerald-200 bg-emerald-50 px-3 py-1 text-[11px] font-medium text-emerald-700">
                  {t('list_editor.multi_select')}
                </span>
              ) : null}
              <span className="rounded-full border border-slate-200 bg-white px-3 py-1 text-[11px] text-slate-500">
                {t('list_editor.row_click_prefix')}
                {rowClickActionLabel(vm.behavior.rowClickAction, t)}
              </span>
              <span className="ml-auto rounded-full bg-slate-900 px-3 py-1 text-[11px] font-medium text-white">
                {vm.toolbar.presets.includes('create')
                  ? t('list_editor.create')
                  : t('list_editor.primary_action')}
              </span>
            </div>
          </div>
          <div className="border-b border-slate-200 px-4 py-3">
            <div className="text-[11px] font-semibold tracking-[0.14em] text-slate-400 uppercase">
              {t('list_editor.table_preview')}
            </div>
            <div className="mt-1 text-sm font-medium text-slate-700">
              {t('list_editor.table_preview_hint')}
            </div>
          </div>
          <div className="overflow-auto">
            <table className="w-full min-w-[320px]">
              <thead className="bg-slate-100/80">
                <tr>
                  {vm.behavior.multiSelect ? (
                    <th className="w-10 px-3 py-2 text-left font-medium text-slate-400">
                      <input
                        type="checkbox"
                        disabled
                        aria-label={t('list_editor.select_all_preview')}
                      />
                    </th>
                  ) : null}
                  {vm.columns.map((c) => (
                    <th
                      key={c.field}
                      className="px-3 py-2 text-left font-medium whitespace-nowrap text-slate-600"
                    >
                      <div className="flex items-center gap-2">
                        <span>{fieldLabelMap.get(c.field) || c.field}</span>
                        {vm.behavior.defaultSortField === c.field ? (
                          <span className="rounded-full bg-blue-100 px-2 py-0.5 text-[10px] font-medium text-blue-700">
                            {t('list_editor.default_sort')}
                          </span>
                        ) : null}
                      </div>
                    </th>
                  ))}
                  <th className="px-3 py-2 text-right font-medium whitespace-nowrap text-slate-400">
                    {t('list_editor.open')}
                  </th>
                </tr>
              </thead>
              <tbody>
                {rows.map((row, i) => (
                  <tr key={i} className="border-t border-slate-100">
                    {vm.behavior.multiSelect ? (
                      <td className="px-3 py-3">
                        <input
                          type="checkbox"
                          disabled
                          aria-label={t('list_editor.select_preview_row', { index: i + 1 })}
                        />
                      </td>
                    ) : null}
                    {vm.columns.map((c) => (
                      <td key={c.field} className="px-3 py-3 whitespace-nowrap text-slate-700">
                        {renderCell(c, row[c.field], fieldLabelMap.get(c.field) || c.field, t)}
                      </td>
                    ))}
                    <td className="px-3 py-3 text-right text-slate-400">→</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div className="flex flex-wrap items-center gap-2 border-t border-slate-100 bg-slate-50 px-4 py-3">
            <span className="rounded-full bg-white px-3 py-1 text-[11px] font-medium text-slate-500">
              {t('list_editor.current_page_prefix')}
              {pageLabel}
            </span>
            <span className="rounded-full bg-white px-3 py-1 text-[11px] text-slate-500">
              {t('list_editor.empty_message_prefix')}
              {vm.behavior.emptyStateText || t('list_editor.no_data')}
            </span>
            {totalActions > 0 ? (
              <span className="rounded-full bg-white px-3 py-1 text-[11px] text-slate-500">
                {t('list_editor.toolbar_actions_count', { count: totalActions })}
              </span>
            ) : null}
          </div>
        </div>
      )}
    </div>
  );
};

function presetLabel(p: string, t: (key: string) => string): string {
  switch (p) {
    case 'create':
      return t('list_editor.create');
    case 'refresh':
      return t('list_editor.refresh');
    case 'export':
      return t('list_editor.export');
    case 'bulkDelete':
      return t('list_editor.bulk_delete');
    default:
      return p;
  }
}

function operatorLabel(operator: string, t: (key: string) => string): string {
  switch (operator) {
    case 'eq':
      return t('list_editor.equal');
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
    default:
      return operator;
  }
}

function rowClickActionLabel(
  action: ListViewModel['behavior']['rowClickAction'] | undefined,
  t: (key: string) => string,
): string {
  switch (action) {
    case 'drawer':
      return t('list_editor.open_drawer');
    case 'none':
      return t('list_editor.no_action');
    case 'detail':
    default:
      return t('list_editor.open_detail');
  }
}

function renderCell(
  column: ListViewModel['columns'][number],
  value: unknown,
  label: string,
  t: (key: string, params?: Record<string, unknown>) => string,
): React.ReactNode {
  const text = String(value ?? '');
  switch (column.renderer) {
    case 'badge':
      return (
        <span className="rounded-full bg-blue-50 px-2.5 py-1 text-[11px] font-medium text-blue-700">
          {text}
        </span>
      );
    case 'link':
      return (
        <span className="font-medium text-blue-600 underline decoration-blue-200 underline-offset-2">
          {text || t('list_editor.link_placeholder', { label })}
        </span>
      );
    case 'image':
      return (
        <span className="inline-flex items-center gap-2">
          <span className="h-7 w-7 rounded-lg bg-slate-200" />
          <span>{text || t('list_editor.image')}</span>
        </span>
      );
    case 'richtext':
      return <span className="text-slate-600">{text}</span>;
    default:
      return text;
  }
}

function generateMockRows(
  columnFields: string[],
  fields: Array<{ code: string; dataType?: string }>,
  count: number,
): Array<Record<string, unknown>> {
  const typeByCode = new Map(fields.map((f) => [f.code, f.dataType ?? 'string']));
  return Array.from({ length: count }, (_, i) => {
    const row: Record<string, unknown> = {};
    for (const code of columnFields) {
      row[code] = mockValue(typeByCode.get(code) ?? 'string', i);
    }
    return row;
  });
}

function mockValue(type: string, seed: number): unknown {
  switch (type.toLowerCase()) {
    case 'string':
    case 'text':
      return `sample-${seed + 1}`;
    case 'integer':
    case 'long':
    case 'bigint':
      return seed * 100 + 42;
    case 'decimal':
    case 'number':
      return Number(((seed + 1) * 12.34).toFixed(2));
    case 'boolean':
      return seed % 2 === 0;
    case 'date':
      return new Date(2026, 3, seed + 1).toISOString().slice(0, 10);
    case 'datetime':
      return new Date(2026, 3, seed + 1, 10, 30).toISOString().slice(0, 16).replace('T', ' ');
    default:
      return `value-${seed}`;
  }
}

export default StructuralPreview;
