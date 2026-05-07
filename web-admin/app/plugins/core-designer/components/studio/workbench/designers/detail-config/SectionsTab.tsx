import React, { useState } from 'react';
import {
  ArrowDownIcon,
  ArrowUpIcon,
  RectangleGroupIcon,
  Squares2X2Icon,
  TrashIcon,
} from '@heroicons/react/24/outline';
import { cn } from '~/utils/cn';
import { SchemaBlockConfigPanel } from '~/shared/designer/SchemaBlockConfigPanel';
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
    <div className="grid gap-6 xl:grid-cols-[minmax(0,1.2fr)_minmax(320px,0.85fr)]">
      <section className="space-y-6">
        <div className="rounded-[28px] border border-slate-200 bg-white/90 p-6 shadow-[0_16px_50px_rgba(15,23,42,0.08)]">
          <div className="flex flex-col gap-4 lg:flex-row lg:items-end lg:justify-between">
            <div>
              <div className="inline-flex items-center gap-2 rounded-full bg-emerald-50 px-3 py-1 text-xs font-semibold uppercase tracking-[0.18em] text-emerald-700">
                <RectangleGroupIcon className="h-4 w-4" />
                分组配置
              </div>
              <h2 className="mt-4 text-3xl font-semibold tracking-tight text-slate-950">字段分组</h2>
              <p className="mt-2 max-w-2xl text-sm leading-6 text-slate-500">
                把详情字段组织成更清晰的阅读节奏。先定义分组，再在右侧补字段和布局。
              </p>
            </div>
            <div className="grid grid-cols-2 gap-3 rounded-2xl border border-slate-200 bg-slate-50 p-3 text-sm text-slate-600">
              <div>
                <div className="text-2xl font-semibold text-slate-950">{vm.sections.length}</div>
                <div>分组数量</div>
              </div>
              <div>
                <div className="text-2xl font-semibold text-slate-950">
                  {vm.sections.reduce((sum, section) => sum + section.fields.length, 0)}
                </div>
                <div>已分配字段</div>
              </div>
            </div>
          </div>
        </div>

        <div className="rounded-[28px] border border-slate-200 bg-white/90 p-6 shadow-sm">
          <div className="mb-5 flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
            <div>
              <h3 className="text-base font-semibold text-slate-950">分组列表</h3>
              <p className="mt-1 text-sm text-slate-500">拖动能力还没接入前，先用顺序操作保持结构可控。</p>
            </div>
            <button
              onClick={addSection}
              disabled={readonly}
              className="inline-flex items-center justify-center gap-2 rounded-2xl bg-slate-950 px-4 py-2.5 text-sm font-medium text-white transition hover:bg-slate-800 disabled:cursor-not-allowed disabled:opacity-40 sm:self-auto"
              data-testid="add-section-btn"
            >
              <Squares2X2Icon className="h-4 w-4" />
              新增分组
            </button>
          </div>

          {vm.sections.length === 0 ? (
            <div className="rounded-3xl border border-dashed border-slate-300 bg-slate-50 px-6 py-12 text-center text-sm text-slate-500">
              暂无分组。点击“新增分组”开始组织详情页结构。
            </div>
          ) : (
            <ol className="space-y-3">
              {vm.sections.map((s, i) => (
                <li
                  key={s.id}
                  className={cn(
                    'rounded-3xl border p-4 text-sm transition-all',
                    selectedIdx === i
                      ? 'border-blue-200 bg-blue-50/80 shadow-[0_10px_30px_rgba(59,130,246,0.10)]'
                      : 'border-slate-200 bg-white hover:border-slate-300',
                  )}
                  data-testid={`section-item-${i}`}
                >
                  <div className="flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
                    <button
                      className="flex min-w-0 flex-1 items-center gap-4 text-left"
                      onClick={() => setSelectedIdx(selectedIdx === i ? null : i)}
                    >
                      <span className={cn(
                        'inline-flex h-11 w-11 shrink-0 items-center justify-center rounded-2xl border text-sm font-semibold',
                        selectedIdx === i
                          ? 'border-blue-200 bg-white text-blue-700'
                          : 'border-slate-200 bg-slate-50 text-slate-500',
                      )}>
                        {String(i + 1).padStart(2, '0')}
                      </span>
                      <span className="min-w-0">
                        <span className="block truncate font-semibold text-slate-950">{s.title || '(未命名)'}</span>
                        <span className="mt-1 block text-xs text-slate-500">
                          {s.fields.length} 个字段 · {s.columns} 列{s.collapsible ? ' · 可折叠' : ''}
                        </span>
                      </span>
                    </button>
                    <div className="flex flex-wrap gap-2">
                      <button
                        onClick={() => moveSection(i, -1)}
                        disabled={i === 0 || readonly}
                        className="inline-flex items-center gap-1 rounded-2xl border border-slate-200 px-3 py-2 text-xs text-slate-600 disabled:cursor-not-allowed disabled:opacity-30"
                      >
                        <ArrowUpIcon className="h-4 w-4" />
                        上移
                      </button>
                      <button
                        onClick={() => moveSection(i, 1)}
                        disabled={i === vm.sections.length - 1 || readonly}
                        className="inline-flex items-center gap-1 rounded-2xl border border-slate-200 px-3 py-2 text-xs text-slate-600 disabled:cursor-not-allowed disabled:opacity-30"
                      >
                        <ArrowDownIcon className="h-4 w-4" />
                        下移
                      </button>
                      <button
                        onClick={() => removeSection(i)}
                        disabled={readonly}
                        className="inline-flex items-center gap-1 rounded-2xl border border-red-200 px-3 py-2 text-xs text-red-600 disabled:cursor-not-allowed disabled:opacity-30"
                      >
                        <TrashIcon className="h-4 w-4" />
                        删除
                      </button>
                    </div>
                  </div>
                </li>
              ))}
            </ol>
          )}
        </div>
      </section>

      <aside className="min-w-0">
        <section className="rounded-[28px] border border-slate-200 bg-white/95 p-6 shadow-[0_16px_40px_rgba(15,23,42,0.08)] backdrop-blur xl:sticky xl:top-0">
          <div className="mb-5 flex items-center justify-between">
            <div>
              <div className="text-xs font-semibold uppercase tracking-[0.2em] text-slate-400">属性面板</div>
              <h3 className="mt-2 text-xl font-semibold text-slate-950">分组属性</h3>
            </div>
            <span className="rounded-full bg-slate-100 px-3 py-1 text-xs font-medium text-slate-500">
              {selected ? '编辑中' : '待选择'}
            </span>
          </div>

          {selected && selectedIdx !== null ? (
            <>
              <div className="mb-5 rounded-2xl bg-slate-50 p-4">
                <div className="text-sm font-semibold text-slate-900">{selected.title || '(未命名)'}</div>
                <div className="mt-2 text-xs text-slate-500">
                  这里控制标题、列数和折叠行为，再给当前分组分配字段。
                </div>
              </div>
              <SchemaBlockConfigPanel
                schemas={sectionDetailSchemas}
                value={selected as unknown as Record<string, unknown>}
                onChange={(next) => updateSection(selectedIdx, next as Partial<SectionConfig>)}
                readonly={readonly}
              />

              <h4 className="mb-3 mt-6 text-sm font-semibold text-slate-900">字段分配</h4>
              {!fields ? (
                <div className="text-xs text-slate-400">加载字段中...</div>
              ) : (
                <div className="max-h-72 overflow-auto rounded-3xl border border-slate-200 bg-slate-50 p-3">
                  {fields.map((f) => (
                    <label
                      key={f.code}
                      className="mb-2 flex cursor-pointer items-center gap-3 rounded-2xl border border-transparent bg-white px-3 py-2.5 text-sm last:mb-0 hover:border-slate-200"
                    >
                      <input
                        type="checkbox"
                        checked={selected.fields.includes(f.code)}
                        onChange={() => toggleFieldInSection(selectedIdx, f.code)}
                        disabled={readonly}
                      />
                      <span className="min-w-0">
                        <span className="block font-medium text-slate-900">{f.displayName ?? f.code}</span>
                        <span className="block text-xs text-slate-500">{f.code}</span>
                      </span>
                    </label>
                  ))}
                </div>
              )}
            </>
          ) : (
            <div className="rounded-3xl border border-dashed border-slate-300 bg-slate-50 px-5 py-10 text-center">
              <div className="mt-4 text-base font-semibold text-slate-900">选择一个分组开始编辑</div>
              <p className="mt-2 text-sm leading-6 text-slate-500">
                左侧选中分组后，这里会显示分组属性和字段分配面板。
              </p>
            </div>
          )}
        </section>
      </aside>
    </div>
  );
};
