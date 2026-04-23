import React, { useMemo } from 'react';
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
  const fieldLabelMap = useMemo(
    () =>
      new Map(
        (fields ?? []).map((field) => [field.code, field.displayName || field.code]),
      ),
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
          <div className="text-[11px] font-medium uppercase tracking-[0.14em] text-slate-400">
            筛选器
          </div>
          <div className="mt-2 text-xl font-semibold text-slate-900">{vm.filters.length}</div>
        </div>
        <div className="rounded-2xl border border-slate-200 bg-white px-3 py-3">
          <div className="text-[11px] font-medium uppercase tracking-[0.14em] text-slate-400">
            列
          </div>
          <div className="mt-2 text-xl font-semibold text-slate-900">{vm.columns.length}</div>
        </div>
        <div className="rounded-2xl border border-slate-200 bg-white px-3 py-3">
          <div className="text-[11px] font-medium uppercase tracking-[0.14em] text-slate-400">
            动作
          </div>
          <div className="mt-2 text-xl font-semibold text-slate-900">{totalActions}</div>
        </div>
        <div className="rounded-2xl border border-slate-200 bg-white px-3 py-3">
          <div className="text-[11px] font-medium uppercase tracking-[0.14em] text-slate-400">
            每页条数
          </div>
          <div className="mt-2 text-xl font-semibold text-slate-900">{pageSize}</div>
        </div>
      </div>

      {vm.filters.length > 0 && (
        <div className="mb-4 rounded-3xl border border-slate-200 bg-white p-4 text-xs shadow-sm">
          <div className="mb-2 text-[11px] font-semibold uppercase tracking-[0.14em] text-slate-400">
            筛选器
          </div>
          <div className="flex flex-wrap gap-2">
            {vm.filters.map((f) => (
              <span
                key={f.field}
                className="rounded-full bg-slate-100 px-2.5 py-1 text-slate-600"
                data-testid={`preview-filter-${f.field}`}
              >
                {fieldLabelMap.get(f.field) || f.field}
                {f.operator ? ` · ${operatorLabel(f.operator)}` : ''}
                {f.defaultValue !== undefined && f.defaultValue !== ''
                  ? ` · 默认 ${String(f.defaultValue)}`
                  : ''}
              </span>
            ))}
          </div>
        </div>
      )}

      {(vm.toolbar.presets.length > 0 || vm.toolbar.customButtons.length > 0) && (
        <div className="mb-4 rounded-3xl border border-slate-200 bg-white p-4 shadow-sm">
          <div className="mb-2 text-[11px] font-semibold uppercase tracking-[0.14em] text-slate-400">
            工具栏
          </div>
          <div className="flex flex-wrap gap-2">
            {vm.toolbar.presets.map((p) => (
              <span
                key={p}
                className="rounded-full bg-blue-100 px-2.5 py-1 text-xs font-medium text-blue-700"
                data-testid={`preview-toolbar-${p}`}
              >
                {presetLabel(p)}
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
          先选择列，右侧才会出现表格预览
        </div>
      ) : (
        <div
          className="overflow-hidden rounded-3xl border border-slate-200 bg-white text-xs shadow-sm"
          data-testid="preview-table"
        >
          <div className="border-b border-slate-100 bg-slate-50 px-4 py-3">
            <div className="flex flex-wrap items-center gap-2">
              <span className="rounded-full bg-white px-3 py-1 text-[11px] font-medium text-slate-500">
                全部记录
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
                  支持多选
                </span>
              ) : null}
              <span className="rounded-full border border-slate-200 bg-white px-3 py-1 text-[11px] text-slate-500">
                行点击 · {rowClickActionLabel(vm.behavior.rowClickAction)}
              </span>
              <span className="ml-auto rounded-full bg-slate-900 px-3 py-1 text-[11px] font-medium text-white">
                {vm.toolbar.presets.includes('create') ? '新增' : '主操作'}
              </span>
            </div>
          </div>
          <div className="border-b border-slate-200 px-4 py-3">
            <div className="text-[11px] font-semibold uppercase tracking-[0.14em] text-slate-400">
              表格预览
            </div>
            <div className="mt-1 text-sm font-medium text-slate-700">
              检查列顺序、字段命名和样例值是否易读
            </div>
          </div>
          <div className="overflow-auto">
            <table className="w-full min-w-[320px]">
              <thead className="bg-slate-100/80">
                <tr>
                  {vm.behavior.multiSelect ? (
                    <th className="w-10 px-3 py-2 text-left font-medium text-slate-400">
                      <input type="checkbox" disabled aria-label="select all preview rows" />
                    </th>
                  ) : null}
                  {vm.columns.map((c) => (
                    <th
                      key={c.field}
                      className="whitespace-nowrap px-3 py-2 text-left font-medium text-slate-600"
                    >
                      <div className="flex items-center gap-2">
                        <span>{fieldLabelMap.get(c.field) || c.field}</span>
                        {vm.behavior.defaultSortField === c.field ? (
                          <span className="rounded-full bg-blue-100 px-2 py-0.5 text-[10px] font-medium text-blue-700">
                            默认排序
                          </span>
                        ) : null}
                      </div>
                    </th>
                  ))}
                  <th className="whitespace-nowrap px-3 py-2 text-right font-medium text-slate-400">
                    打开
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
                          aria-label={`select preview row ${i + 1}`}
                        />
                      </td>
                    ) : null}
                    {vm.columns.map((c) => (
                      <td
                        key={c.field}
                        className="whitespace-nowrap px-3 py-3 text-slate-700"
                      >
                        {renderCell(c, row[c.field], fieldLabelMap.get(c.field) || c.field)}
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
              当前页 {pageLabel}
            </span>
            <span className="rounded-full bg-white px-3 py-1 text-[11px] text-slate-500">
              空态文案 · {vm.behavior.emptyStateText || '暂无数据'}
            </span>
            {totalActions > 0 ? (
              <span className="rounded-full bg-white px-3 py-1 text-[11px] text-slate-500">
                工具栏动作 {totalActions} 个
              </span>
            ) : null}
          </div>
        </div>
      )}
    </div>
  );
};

function presetLabel(p: string): string {
  switch (p) {
    case 'create':
      return '新增';
    case 'export':
      return '导出';
    case 'bulkDelete':
      return '批量删除';
    default:
      return p;
  }
}

function operatorLabel(operator: string): string {
  switch (operator) {
    case 'eq':
      return '等于';
    case 'neq':
      return '不等于';
    case 'like':
      return '包含';
    case 'between':
      return '区间';
    case 'gt':
      return '大于';
    case 'gte':
      return '大于等于';
    case 'lt':
      return '小于';
    case 'lte':
      return '小于等于';
    default:
      return operator;
  }
}

function rowClickActionLabel(action?: ListViewModel['behavior']['rowClickAction']): string {
  switch (action) {
    case 'drawer':
      return '打开抽屉';
    case 'none':
      return '不响应';
    case 'detail':
    default:
      return '进入详情';
  }
}

function renderCell(
  column: ListViewModel['columns'][number],
  value: unknown,
  label: string,
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
          {text || `${label} 链接`}
        </span>
      );
    case 'image':
      return (
        <span className="inline-flex items-center gap-2">
          <span className="h-7 w-7 rounded-lg bg-slate-200" />
          <span>{text || 'image'}</span>
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
      return new Date(2026, 3, seed + 1, 10, 30)
        .toISOString()
        .slice(0, 16)
        .replace('T', ' ');
    default:
      return `value-${seed}`;
  }
}

export default StructuralPreview;
