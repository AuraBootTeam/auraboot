import React, { useMemo, useState } from 'react';
import type { ModelCapabilities } from '~/shared/hooks/useModelCapabilities';
import { SchemaBlockConfigPanel } from '~/shared/designer/SchemaBlockConfigPanel';
import { filterDetailSchemas } from './schema';
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

  const selectedCodes = useMemo(
    () => new Set(vm.filters.map((f) => f.field)),
    [vm.filters],
  );
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
          <div className="text-xs font-semibold uppercase tracking-[0.16em] text-slate-400">
            筛选配置
          </div>
          <div className="mt-2 text-sm text-slate-500">正在读取可筛选字段与能力约束。</div>
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
            <div className="text-xs font-semibold uppercase tracking-[0.16em] text-slate-400">
              筛选池
            </div>
            <h2 className="mt-2 text-lg font-semibold text-slate-900">只保留高频筛选</h2>
            <p className="mt-2 text-sm leading-6 text-slate-500">
              列表筛选越多，页头越拥挤。优先保留业务最常用、最能快速缩小范围的条件。
            </p>
          </div>
          <div className="rounded-2xl border border-slate-200 bg-white px-4 py-3 text-sm text-slate-600">
            已选 <span className="font-semibold text-slate-900">{vm.filters.length}</span> / {allowedFields.length}
          </div>
        </div>

        {allowedFields.length === 0 ? (
          <div className="mt-4 rounded-2xl border border-dashed border-slate-300 bg-white px-4 py-6 text-sm text-slate-400">
            {capabilityError
              ? '模型能力读取失败，当前页也没有可回退的筛选字段。'
              : '当前模型没有可筛选字段，无法在列表头部生成筛选器。'}
          </div>
        ) : (
          <div className="mt-4 max-h-72 overflow-auto rounded-2xl border border-slate-200 bg-white p-3">
            {capabilityError && (
              <div className="mb-3 rounded-2xl border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-800">
                无法读取筛选白名单，当前已回退为页面内已有字段。
              </div>
            )}
            <div className="mb-3 flex items-center gap-3 rounded-2xl border border-slate-200 bg-slate-50 px-3 py-2">
              <span className="text-xs font-semibold uppercase tracking-[0.16em] text-slate-400">
                搜索
              </span>
              <input
                type="text"
                value={search}
                onChange={(event) => setSearch(event.target.value)}
                placeholder="按字段名筛选"
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
                    {f.dataType ?? 'unknown'}
                  </span>
                </label>
              ))}
              {filteredAllowedFields.length === 0 && (
                <div className="rounded-2xl border border-dashed border-slate-300 px-4 py-6 text-center text-sm text-slate-400">
                  没有匹配的筛选字段。
                </div>
              )}
            </div>
          </div>
        )}
      </section>

      <section className={sectionCardClasses()}>
        <div className="mb-4 flex items-center justify-between">
          <div>
            <div className="text-xs font-semibold uppercase tracking-[0.16em] text-slate-400">
              已选筛选
            </div>
            <h3 className="mt-2 text-base font-semibold text-slate-900">已选筛选项</h3>
          </div>
          <div className="text-sm text-slate-500">控制显示模式、默认值和操作符</div>
        </div>

        {vm.filters.length === 0 ? (
          <div className="rounded-2xl border border-dashed border-slate-300 bg-white px-4 py-6 text-sm text-slate-400">
            还没有筛选项。建议控制在 3 到 5 个高频条件。
          </div>
        ) : (
          <div className="space-y-3">
            <div className="flex flex-wrap gap-2">
              <span className="rounded-full bg-white px-3 py-1 text-xs font-medium text-slate-500">
                优先状态类
              </span>
              <span className="rounded-full bg-white px-3 py-1 text-xs font-medium text-slate-500">
                其次时间范围
              </span>
              <span className="rounded-full bg-white px-3 py-1 text-xs font-medium text-slate-500">
                避免低频条件挤占页头
              </span>
            </div>
            <ol className="space-y-2">
            {vm.filters.map((f, i) => (
              <li
                key={`${f.field}-${i}`}
                className={`rounded-2xl border bg-white px-4 py-4 text-sm transition ${
                  selectedIdx === i
                    ? 'border-blue-200 bg-blue-50/70'
                    : 'border-slate-200'
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
                        {operatorLabel(f.operator)}
                      </span>
                      <span className="rounded-full bg-slate-100 px-2.5 py-1 text-[11px] text-slate-600">
                        {displayModeLabel(f.displayMode)}
                      </span>
                      {f.defaultValue !== undefined && f.defaultValue !== '' ? (
                        <span className="rounded-full bg-blue-50 px-2.5 py-1 text-[11px] text-blue-700">
                          默认 {String(f.defaultValue)}
                        </span>
                      ) : (
                        <span className="rounded-full bg-slate-100 px-2.5 py-1 text-[11px] text-slate-600">
                          无默认值
                        </span>
                      )}
                    </span>
                  </button>
                  <button
                    type="button"
                    onClick={() => toggleField(f.field)}
                    disabled={readonly}
                    className="rounded-xl border border-red-200 px-3 py-1.5 text-xs text-red-600 disabled:opacity-30"
                    aria-label="移除筛选项"
                  >
                    移除
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
                    展示：{displayModeLabel(f.displayMode)}
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
                    条件：{operatorLabel(f.operator)}
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
          <div className="text-xs font-semibold uppercase tracking-[0.16em] text-slate-400">
            筛选属性
          </div>
          <h3 className="mt-2 text-base font-semibold text-slate-900">
            {selected ? `筛选属性 · ${selected.field}` : '筛选属性'}
          </h3>
          <p className="mt-2 text-sm text-slate-500">
            在这里决定默认操作符和展示方式，避免在列表头部生成难以理解的筛选器。
          </p>
        </div>
        {selected && selectedIdx !== null ? (
          <SchemaBlockConfigPanel
            schemas={filterDetailSchemas}
            value={selected as unknown as Record<string, unknown>}
            onChange={(next) =>
              updateFilter(selectedIdx, next as Partial<FilterConfig>)
            }
            readonly={readonly}
          />
        ) : (
          <div className="rounded-2xl border border-dashed border-slate-300 bg-white px-4 py-6 text-sm text-slate-400">
            先从“已选筛选项”中选一个条件，再编辑默认值和显示模式。
          </div>
        )}
      </section>
    </div>
  );
};

function operatorLabel(operator?: string): string {
  switch (operator) {
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
    case 'eq':
    default:
      return '等于';
  }
}

function displayModeLabel(mode?: FilterConfig['displayMode']): string {
  switch (mode) {
    case 'drawer':
      return '抽屉';
    case 'top-bar':
      return '顶部栏';
    case 'inline':
    default:
      return '内联';
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
