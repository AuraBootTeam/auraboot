import React, { useMemo, useState } from 'react';
import { SchemaBlockConfigPanel } from '../SchemaBlockConfigPanel';
import { columnDetailSchemas } from './schema';
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
}

export const ColumnsTab: React.FC<ColumnsTabProps> = ({ vm, setVm, fields, readonly }) => {
  const [selectedIdx, setSelectedIdx] = useState<number | null>(null);

  const selectedCodes = useMemo(
    () => new Set(vm.columns.map((c) => c.field)),
    [vm.columns],
  );

  if (!fields) {
    return <div className="text-sm text-gray-400">加载字段中...</div>;
  }

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
    <div data-testid="columns-tab">
      <h2 className="mb-4 text-lg font-medium">选择列</h2>
      <div className="mb-6 max-h-60 overflow-auto rounded border p-3">
        <div className="space-y-2">
          {fields.map((f) => (
            <label key={f.code} className="flex items-center gap-3 text-sm">
              <input
                type="checkbox"
                checked={selectedCodes.has(f.code)}
                onChange={() => toggleField(f.code)}
                disabled={readonly}
                data-testid={`column-toggle-${f.code}`}
              />
              <span className="font-medium">{f.displayName ?? f.code}</span>
              <span className="text-xs text-gray-400">({f.dataType ?? 'unknown'})</span>
            </label>
          ))}
        </div>
      </div>

      {vm.columns.length > 0 && (
        <>
          <h3 className="mb-3 text-sm font-medium text-gray-700">已选列 (按顺序)</h3>
          <ol className="mb-6 space-y-2">
            {vm.columns.map((c, i) => (
              <li
                key={`${c.field}-${i}`}
                className={`flex items-center justify-between rounded border p-2 text-sm ${
                  selectedIdx === i ? 'border-blue-400 bg-blue-50' : 'border-gray-200'
                }`}
              >
                <button
                  type="button"
                  className="flex-1 text-left"
                  onClick={() => setSelectedIdx(selectedIdx === i ? null : i)}
                  data-testid={`column-item-${i}`}
                >
                  {c.field}
                  {c.width && (
                    <span className="ml-2 text-xs text-gray-500">{c.width}px</span>
                  )}
                  {c.renderer && (
                    <span className="ml-2 text-xs text-gray-500">{c.renderer}</span>
                  )}
                </button>
                <div className="flex gap-1">
                  <button
                    type="button"
                    onClick={() => move(i, -1)}
                    disabled={i === 0 || readonly}
                    className="px-2 text-xs disabled:opacity-30"
                    aria-label="上移"
                  >
                    ↑
                  </button>
                  <button
                    type="button"
                    onClick={() => move(i, 1)}
                    disabled={i === vm.columns.length - 1 || readonly}
                    className="px-2 text-xs disabled:opacity-30"
                    aria-label="下移"
                  >
                    ↓
                  </button>
                </div>
              </li>
            ))}
          </ol>

          {selected && selectedIdx !== null && (
            <section className="rounded border p-4" data-testid="column-detail-editor">
              <h3 className="mb-3 text-sm font-medium text-gray-700">
                列属性 — {selected.field}
              </h3>
              <SchemaBlockConfigPanel
                schemas={columnDetailSchemas}
                value={selected as unknown as Record<string, unknown>}
                onChange={(next) =>
                  updateColumn(selectedIdx, next as Partial<ColumnConfig>)
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
