import React, { useState } from 'react';
import { SchemaBlockConfigPanel } from '../SchemaBlockConfigPanel';
import { sectionDetailSchemas } from './schema';
import { makeSectionId, type DetailViewModel, type SectionConfig } from './mapper';

export interface ResolvedFieldLite {
  code: string;
  displayName?: string;
  dataType?: string;
}

export interface SectionsTabProps {
  vm: DetailViewModel;
  setVm: (next: DetailViewModel) => void;
  fields: ResolvedFieldLite[] | undefined;
  readonly?: boolean;
}

export const SectionsTab: React.FC<SectionsTabProps> = ({ vm, setVm, fields, readonly }) => {
  const [selectedIdx, setSelectedIdx] = useState<number | null>(null);

  const addSection = () => {
    const next: SectionConfig = {
      id: makeSectionId(),
      title: `分组 ${vm.sections.length + 1}`,
      columns: 2,
      fields: [],
    };
    setVm({ ...vm, sections: [...vm.sections, next] });
    setSelectedIdx(vm.sections.length);
  };

  const removeSection = (idx: number) => {
    if (readonly) return;
    setVm({ ...vm, sections: vm.sections.filter((_, i) => i !== idx) });
    if (selectedIdx === idx) setSelectedIdx(null);
  };

  const moveSection = (idx: number, delta: number) => {
    const target = idx + delta;
    if (target < 0 || target >= vm.sections.length) return;
    const next = [...vm.sections];
    [next[idx], next[target]] = [next[target], next[idx]];
    setVm({ ...vm, sections: next });
    setSelectedIdx(target);
  };

  const updateSection = (idx: number, patch: Partial<SectionConfig>) => {
    const next = vm.sections.map((s, i) => (i === idx ? { ...s, ...patch } : s));
    setVm({ ...vm, sections: next });
  };

  const toggleFieldInSection = (sectionIdx: number, fieldCode: string) => {
    if (readonly) return;
    const section = vm.sections[sectionIdx];
    const nextFields = section.fields.includes(fieldCode)
      ? section.fields.filter((f) => f !== fieldCode)
      : [...section.fields, fieldCode];
    updateSection(sectionIdx, { fields: nextFields });
  };

  const selected = selectedIdx !== null ? vm.sections[selectedIdx] : null;

  return (
    <div>
      <div className="mb-4 flex items-center justify-between">
        <h2 className="text-lg font-medium">字段分组</h2>
        <button
          onClick={addSection}
          disabled={readonly}
          className="rounded bg-blue-600 px-3 py-1.5 text-sm text-white hover:bg-blue-700 disabled:opacity-50"
          data-testid="add-section-btn"
        >
          + 新增分组
        </button>
      </div>

      {vm.sections.length === 0 ? (
        <div className="rounded border border-dashed p-8 text-center text-sm text-gray-400">
          暂无分组。点击"新增分组"开始。
        </div>
      ) : (
        <ol className="space-y-2">
          {vm.sections.map((s, i) => (
            <li
              key={s.id}
              className={`rounded border p-3 text-sm ${selectedIdx === i ? 'border-blue-400 bg-blue-50' : 'border-gray-200'}`}
              data-testid={`section-item-${i}`}
            >
              <div className="flex items-center justify-between">
                <button
                  className="flex-1 text-left"
                  onClick={() => setSelectedIdx(selectedIdx === i ? null : i)}
                >
                  <span className="font-medium">{s.title || '(未命名)'}</span>
                  <span className="ml-2 text-xs text-gray-500">{s.fields.length} 个字段 · {s.columns} 列</span>
                </button>
                <div className="flex gap-1">
                  <button onClick={() => moveSection(i, -1)} disabled={i === 0 || readonly} className="px-2 text-xs disabled:opacity-30">↑</button>
                  <button onClick={() => moveSection(i, 1)} disabled={i === vm.sections.length - 1 || readonly} className="px-2 text-xs disabled:opacity-30">↓</button>
                  <button onClick={() => removeSection(i)} disabled={readonly} className="px-2 text-xs text-red-600 disabled:opacity-30">删除</button>
                </div>
              </div>
            </li>
          ))}
        </ol>
      )}

      {selected && selectedIdx !== null && (
        <section className="mt-6 rounded border p-4">
          <h3 className="mb-3 text-sm font-medium text-gray-700">分组属性 — {selected.title || '(未命名)'}</h3>
          <SchemaBlockConfigPanel
            schemas={sectionDetailSchemas}
            value={selected as unknown as Record<string, unknown>}
            onChange={(next) => updateSection(selectedIdx, next as Partial<SectionConfig>)}
            readonly={readonly}
          />

          <h4 className="mt-6 mb-2 text-sm font-medium text-gray-700">字段</h4>
          {!fields ? (
            <div className="text-xs text-gray-400">加载字段中...</div>
          ) : (
            <div className="max-h-48 overflow-auto rounded border p-2">
              {fields.map((f) => (
                <label key={f.code} className="flex items-center gap-2 py-1 text-sm">
                  <input
                    type="checkbox"
                    checked={selected.fields.includes(f.code)}
                    onChange={() => toggleFieldInSection(selectedIdx, f.code)}
                    disabled={readonly}
                  />
                  <span>{f.displayName ?? f.code}</span>
                </label>
              ))}
            </div>
          )}
        </section>
      )}
    </div>
  );
};
