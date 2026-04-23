import React, { useEffect, useRef, useState } from 'react';
import { BoltIcon, DocumentTextIcon } from '@heroicons/react/24/outline';
import type { PageSchema } from '~/plugins/core-designer/components/studio/domain/dsl/types';
import { useModelCapabilities } from '~/shared/hooks/useModelCapabilities';
import { cn } from '~/utils/cn';
import { blocksToDetailVm, detailVmToBlocks, type DetailViewModel } from './detail-config/mapper';
import { ActionsTab } from './detail-config/ActionsTab';
import { PageMetaTab } from './detail-config/PageMetaTab';
import {
  validateDetailVm,
  hasBlockingErrors,
  type ValidationError,
} from './validation/capabilityValidator';

export interface DetailConfigPanelProps {
  schema: PageSchema;
  onSchemaChange: (schema: PageSchema) => void;
  onSave?: (schema: PageSchema) => Promise<void>;
  modelCode?: string;
  readonly?: boolean;
  previewMode?: boolean;
}

const NAV_ITEMS: Array<{
  id: 'actions' | 'page-meta';
  label: string;
  icon: React.ComponentType<React.SVGProps<SVGSVGElement>>;
}> = [
  { id: 'actions', label: '操作按钮', icon: BoltIcon },
  { id: 'page-meta', label: '页面信息', icon: DocumentTextIcon },
];

export const DetailConfigPanel: React.FC<DetailConfigPanelProps> = ({
  schema, onSchemaChange, modelCode, readonly, previewMode,
}) => {
  const effectiveModelCode = modelCode ?? schema.modelCode;
  const { data: capabilities } = useModelCapabilities(effectiveModelCode);
  const [activeTab, setActiveTab] = useState<'actions' | 'page-meta'>('actions');
  const [vm, setVm] = useState<DetailViewModel>(() => blocksToDetailVm(schema.blocks ?? []));
  const lastPushedRef = useRef<string>(JSON.stringify(schema.blocks ?? []));

  useEffect(() => {
    const currentSerialized = JSON.stringify(schema.blocks ?? []);
    lastPushedRef.current = currentSerialized;
    setVm(blocksToDetailVm(schema.blocks ?? []));
  }, [schema.blocks]);

  useEffect(() => {
    const nextBlocks = detailVmToBlocks(vm);
    const nextSerialized = JSON.stringify(nextBlocks);
    const currentSerialized = JSON.stringify(schema.blocks ?? []);
    if (nextSerialized !== currentSerialized && nextSerialized !== lastPushedRef.current) {
      lastPushedRef.current = nextSerialized;
      onSchemaChange({ ...schema, blocks: nextBlocks });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [vm]);

  const errors: ValidationError[] = validateDetailVm(vm, capabilities);
  const activeItem = NAV_ITEMS.find((item) => item.id === activeTab) ?? NAV_ITEMS[0];
  const summaryStats = [
    { label: '预设动作', value: vm.actions.presets.length, tone: 'blue' as const },
    { label: '自定义按钮', value: vm.actions.customButtons.length, tone: 'emerald' as const },
    { label: '详情分组', value: vm.sections.length, tone: 'slate' as const },
  ];

  return (
    <div className="flex h-full bg-slate-50" data-testid="detail-config-panel">
      {!previewMode && (
        <aside className="w-72 shrink-0 border-r border-slate-200 bg-white/90 px-4 py-6">
          <div className="rounded-[28px] border border-slate-200 bg-white p-5 shadow-[0_16px_40px_rgba(15,23,42,0.06)]">
            <div className="text-xs font-semibold uppercase tracking-[0.18em] text-slate-400">
              配置
            </div>
            <div className="mt-3 text-lg font-semibold text-slate-950">
              详情页的主要可编辑项
            </div>
            <div className="mt-2 text-sm leading-6 text-slate-500">
              统一管理标准动作、页面标题和和详情页基础识别信息。
            </div>
            <div className="mt-5 grid grid-cols-2 gap-3 rounded-2xl border border-slate-200 bg-slate-50 p-3 text-sm text-slate-600">
              <div>
                <div className="text-2xl font-semibold text-slate-950">{vm.actions.presets.length}</div>
                <div>预设动作</div>
              </div>
              <div>
                <div className="text-2xl font-semibold text-slate-950">{vm.actions.customButtons.length}</div>
                <div>自定义按钮</div>
              </div>
            </div>
          </div>
          <nav className="mt-6 rounded-[28px] border border-slate-200 bg-white p-3 shadow-sm">
            {NAV_ITEMS.map((item) => (
              <button
                type="button"
                key={item.id}
                onClick={() => setActiveTab(item.id)}
                className={cn(
                  'mb-2 flex w-full items-center gap-3 rounded-2xl border px-3 py-3 text-left text-sm transition last:mb-0',
                  activeTab === item.id
                    ? 'border-blue-200 bg-blue-50/80 text-blue-700 shadow-[0_10px_24px_rgba(59,130,246,0.10)]'
                    : 'border-transparent text-slate-500 hover:border-slate-200 hover:bg-slate-50',
                )}
                data-testid={`detail-tab-${item.id}`}
              >
                <span
                  className={cn(
                    'inline-flex h-10 w-10 shrink-0 items-center justify-center rounded-xl border',
                    activeTab === item.id
                      ? 'border-blue-100 bg-white text-blue-600'
                      : 'border-slate-200 bg-slate-50 text-slate-400',
                  )}
                >
                  <item.icon className="h-5 w-5" />
                </span>
                <span className="min-w-0 flex-1">
                  <span
                    className={cn(
                      'block font-medium',
                      activeTab === item.id ? 'text-slate-900' : 'text-slate-600',
                    )}
                  >
                    {item.label}
                  </span>
                </span>
                {activeTab === item.id && (
                  <span
                    className="rounded-full bg-blue-100 px-2 py-0.5 text-[11px] font-medium text-blue-700"
                  >
                    当前
                  </span>
                )}
              </button>
            ))}
          </nav>
        </aside>
      )}

      <main className="flex-1 overflow-auto px-6 py-6">
        <section
          className="mx-auto mb-6 max-w-6xl rounded-[28px] border border-slate-200 bg-white/90 p-6 shadow-[0_16px_50px_rgba(15,23,42,0.08)]"
          data-testid="detail-designer-summary"
        >
          <div className="flex flex-col gap-6 lg:flex-row lg:items-start lg:justify-between">
            <div className="max-w-2xl">
              <div className="inline-flex items-center gap-2 rounded-full bg-blue-50 px-3 py-1 text-xs font-semibold uppercase tracking-[0.16em] text-blue-700">
                当前编辑
              </div>
              <h1 className="mt-4 text-3xl font-semibold tracking-tight text-slate-950">
                {activeItem.label}
              </h1>
              <p className="mt-3 text-sm leading-6 text-slate-500">
                {activeTab === 'actions'
                  ? '管理详情页顶部动作，确保主操作清晰且和模型能力一致。'
                  : '维护页面标题、路由标识和基础元信息，保证详情页识别稳定。'}
              </p>
              <div className="mt-4 flex flex-wrap gap-2">
                <span className="rounded-full bg-slate-100 px-3 py-1 text-xs font-medium text-slate-600">
                  当前阶段：{activeItem.label}
                </span>
                <span className="rounded-full bg-slate-100 px-3 py-1 text-xs font-medium text-slate-600">
                  详情分组 {vm.sections.length}
                </span>
              </div>
            </div>
            <div className="grid gap-3 sm:grid-cols-3">
              {summaryStats.map((item) => (
                <div
                  key={item.label}
                  className={cn(
                    'min-w-[140px] rounded-2xl border px-4 py-4',
                    item.tone === 'blue' && 'border-blue-100 bg-blue-50',
                    item.tone === 'emerald' && 'border-emerald-100 bg-emerald-50',
                    item.tone === 'slate' && 'border-slate-200 bg-slate-50',
                  )}
                >
                  <div className="text-xs font-medium uppercase tracking-[0.14em] text-slate-500">
                    {item.label}
                  </div>
                  <div className="mt-2 text-2xl font-semibold text-slate-900">
                    {item.value}
                  </div>
                </div>
              ))}
            </div>
          </div>
        </section>

        {errors.length > 0 && (
          <div
            className={`mx-auto mb-6 max-w-6xl rounded-[28px] border px-5 py-5 text-sm ${
              hasBlockingErrors(errors)
                ? 'border-red-200 bg-red-50'
                : 'border-amber-200 bg-amber-50'
            }`}
            data-testid="validation-banner"
          >
            <div className="mb-2 text-xs font-semibold uppercase tracking-[0.16em] text-slate-500">
              配置校验
            </div>
            <div className="mb-2 font-semibold">
              {hasBlockingErrors(errors) ? '配置存在冲突' : '配置提示'}
            </div>
            <ul className="space-y-1">
              {errors.map((e, i) => (
                <li
                  key={i}
                  className={e.severity === 'error' ? 'text-red-700' : 'text-amber-700'}
                  data-testid={`validation-${e.severity}`}
                >
                  [{e.tab}] {e.message}
                </li>
              ))}
            </ul>
          </div>
        )}

        <div
          className="mx-auto max-w-6xl rounded-[28px] border border-slate-200 bg-white/90 p-6 shadow-sm"
          data-testid="detail-designer-workspace"
        >
          <div className="mb-6 flex flex-col gap-3 border-b border-slate-100 pb-5">
            <div className="text-xs font-semibold uppercase tracking-[0.18em] text-slate-400">
              工作区
            </div>
            <div className="text-lg font-semibold text-slate-950">
              {activeItem.label}
            </div>
            <div className="text-sm text-slate-500">
              在这里完成当前阶段配置，保持详情页动作、标题和结构识别一致。
            </div>
          </div>
          {activeTab === 'actions' ? (
            <ActionsTab
              vm={vm}
              setVm={setVm}
              capabilities={capabilities}
              modelCode={effectiveModelCode}
              readonly={readonly}
            />
          ) : (
            <PageMetaTab schema={schema} onSchemaChange={onSchemaChange} readonly={readonly} />
          )}
        </div>
      </main>
    </div>
  );
};

export default DetailConfigPanel;
