import React, { useMemo, useState } from 'react';
import type { ModelCapabilities } from '~/shared/hooks/useModelCapabilities';
import { SchemaBlockConfigPanel } from '../SchemaBlockConfigPanel';
import { filterDetailSchemas } from './schema';
import type { ListViewModel, FilterConfig } from './mapper';
import type { ResolvedFieldLite } from './ColumnsTab';

export interface FiltersTabProps {
  vm: ListViewModel;
  setVm: (next: ListViewModel) => void;
  fields: ResolvedFieldLite[] | undefined;
  capabilities: ModelCapabilities | undefined;
  readonly?: boolean;
}

export const FiltersTab: React.FC<FiltersTabProps> = ({
  vm,
  setVm,
  fields,
  capabilities,
  readonly,
}) => {
  const [selectedIdx, setSelectedIdx] = useState<number | null>(null);

  // Whitelist: only fields in capabilities.filterableFields are allowed.
  const allowedFields = useMemo(() => {
    if (!fields || !capabilities) return [] as ResolvedFieldLite[];
    const whitelist = new Set(capabilities.filterableFields);
    return fields.filter((f) => whitelist.has(f.code));
  }, [fields, capabilities]);

  const selectedCodes = useMemo(
    () => new Set(vm.filters.map((f) => f.field)),
    [vm.filters],
  );

  if (!fields || !capabilities) {
    return <div className="text-sm text-gray-400">加载字段中...</div>;
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
    <div data-testid="filters-tab">
      <h2 className="mb-4 text-lg font-medium">筛选字段</h2>

      {allowedFields.length === 0 ? (
        <p className="text-sm text-gray-500">
          当前模型未声明可筛选字段（capabilities.filterableFields 为空）。
        </p>
      ) : (
        <div className="mb-6 max-h-60 overflow-auto rounded border p-3">
          <div className="space-y-2">
            {allowedFields.map((f) => (
              <label key={f.code} className="flex items-center gap-3 text-sm">
                <input
                  type="checkbox"
                  checked={selectedCodes.has(f.code)}
                  onChange={() => toggleField(f.code)}
                  disabled={readonly}
                  data-testid={`filter-toggle-${f.code}`}
                />
                <span className="font-medium">{f.displayName ?? f.code}</span>
                <span className="text-xs text-gray-400">({f.dataType ?? 'unknown'})</span>
              </label>
            ))}
          </div>
        </div>
      )}

      {vm.filters.length > 0 && (
        <>
          <h3 className="mb-3 text-sm font-medium text-gray-700">已选筛选项</h3>
          <ol className="mb-6 space-y-2">
            {vm.filters.map((f, i) => (
              <li
                key={`${f.field}-${i}`}
                className={`rounded border p-2 text-sm ${
                  selectedIdx === i ? 'border-blue-400 bg-blue-50' : 'border-gray-200'
                }`}
              >
                <button
                  type="button"
                  className="w-full text-left"
                  onClick={() => setSelectedIdx(selectedIdx === i ? null : i)}
                  data-testid={`filter-item-${i}`}
                >
                  {f.field}
                  {f.operator && (
                    <span className="ml-2 text-xs text-gray-500">{f.operator}</span>
                  )}
                  {f.displayMode && (
                    <span className="ml-2 text-xs text-gray-500">{f.displayMode}</span>
                  )}
                </button>
              </li>
            ))}
          </ol>

          {selected && selectedIdx !== null && (
            <section className="rounded border p-4" data-testid="filter-detail-editor">
              <h3 className="mb-3 text-sm font-medium text-gray-700">
                筛选属性 — {selected.field}
              </h3>
              <SchemaBlockConfigPanel
                schemas={filterDetailSchemas}
                value={selected as unknown as Record<string, unknown>}
                onChange={(next) =>
                  updateFilter(selectedIdx, next as Partial<FilterConfig>)
                }
                readonly={readonly}
              />
            </section>
          )}
        </>
      )}
    </div>
  );
};
