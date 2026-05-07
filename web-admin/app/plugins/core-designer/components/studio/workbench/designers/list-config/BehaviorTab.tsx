import React, { useMemo } from 'react';
import type { ModelCapabilities } from '~/shared/hooks/useModelCapabilities';
import { SchemaBlockConfigPanel } from '~/shared/designer/SchemaBlockConfigPanel';
import { buildBehaviorSchemas } from './schema';
import type { ListViewModel, BehaviorConfig } from './mapper';

export interface BehaviorTabProps {
  vm: ListViewModel;
  setVm: (next: ListViewModel) => void;
  capabilities: ModelCapabilities | undefined;
  readonly?: boolean;
  loading?: boolean;
  capabilityError?: Error;
  fallbackSortFields?: string[];
}

function sectionCardClasses(extra?: string): string {
  return ['rounded-[28px] border border-slate-200 bg-white/90 p-6 shadow-sm', extra]
    .filter(Boolean)
    .join(' ');
}

/**
 * Sentinel used in the defaultSortField <Select.Item> to represent "no sort
 * field selected". Radix Select.Item forbids empty-string values, so we map:
 *   VM undefined/'' → display '__none__'
 *   display '__none__' → VM undefined
 */
const SORT_FIELD_NONE = '__none__';

function rowClickActionLabel(value?: BehaviorConfig['rowClickAction']): string {
  switch (value) {
    case 'drawer':
      return '打开抽屉';
    case 'none':
      return '不响应';
    case 'detail':
    default:
      return '进入详情';
  }
}

/** Convert VM BehaviorConfig → panel display shape (sentinel for empty sort field). */
function behaviorToDisplay(behavior: BehaviorConfig): Record<string, unknown> {
  return {
    ...(behavior as unknown as Record<string, unknown>),
    defaultSortField: behavior.defaultSortField || SORT_FIELD_NONE,
  };
}

/** Convert panel display shape → VM BehaviorConfig (strip sentinel back to undefined). */
function displayToBehavior(display: Record<string, unknown>): BehaviorConfig {
  const next = { ...display } as Record<string, unknown>;
  if (next.defaultSortField === SORT_FIELD_NONE) {
    delete next.defaultSortField;
  }
  return next as unknown as BehaviorConfig;
}

export const BehaviorTab: React.FC<BehaviorTabProps> = ({
  vm,
  setVm,
  capabilities,
  readonly,
  loading,
  capabilityError,
  fallbackSortFields = [],
}) => {
  const sortableFields = capabilities?.sortableFields ?? fallbackSortFields;
  const schemas = useMemo(
    () =>
      buildBehaviorSchemas(
        sortableFields,
        capabilities?.filterableFields ?? [],
      ),
    [capabilities, sortableFields],
  );

  if (!capabilities && loading && sortableFields.length === 0) {
    return (
      <div className="space-y-4" data-testid="behavior-tab">
        <div className={sectionCardClasses()}>
          <div className="text-xs font-semibold uppercase tracking-[0.16em] text-slate-400">
            行为配置
          </div>
          <div className="mt-2 text-sm text-slate-500">正在读取排序与筛选能力。</div>
        </div>
      </div>
    );
  }

  return (
    <div className="space-y-5" data-testid="behavior-tab">
      {capabilityError && (
        <div className="rounded-[28px] border border-amber-200 bg-amber-50 px-5 py-5 text-sm text-amber-800">
          无法读取排序与筛选能力，当前排序字段已回退为页面中已引用的字段。
        </div>
      )}
      <section className={sectionCardClasses()}>
        <div className="flex flex-col gap-4 border-b border-slate-200 pb-4 lg:flex-row lg:items-end lg:justify-between">
          <div>
            <div className="inline-flex items-center gap-2 rounded-full bg-slate-100 px-3 py-1 text-xs font-semibold uppercase tracking-[0.16em] text-slate-500">
              运行规则
            </div>
            <h2 className="mt-4 text-2xl font-semibold tracking-tight text-slate-950">
              定义列表的运行方式
            </h2>
            <p className="mt-2 text-sm leading-6 text-slate-500">
              这里决定默认排序、分页、多选和行点击行为，属于列表体验的底层规则，不应和列配置混在一起。
            </p>
          </div>
          <div className="grid gap-3 sm:grid-cols-3">
            <div className="rounded-2xl border border-slate-200 bg-white px-4 py-3">
              <div className="text-[11px] uppercase tracking-[0.14em] text-slate-400">默认分页</div>
              <div className="mt-2 text-xl font-semibold text-slate-900">{vm.behavior.pageSize}</div>
            </div>
            <div className="rounded-2xl border border-slate-200 bg-white px-4 py-3">
              <div className="text-[11px] uppercase tracking-[0.14em] text-slate-400">行点击</div>
              <div className="mt-2 text-xl font-semibold text-slate-900">
                {rowClickActionLabel(vm.behavior.rowClickAction)}
              </div>
            </div>
            <div className="rounded-2xl border border-slate-200 bg-white px-4 py-3">
              <div className="text-[11px] uppercase tracking-[0.14em] text-slate-400">多选</div>
              <div className="mt-2 text-xl font-semibold text-slate-900">
                {vm.behavior.multiSelect ? '开' : '关'}
              </div>
            </div>
          </div>
        </div>
        <div className="mt-4 flex flex-wrap gap-2">
          <span className="rounded-full bg-white px-3 py-1 text-xs font-medium text-slate-500">
            排序规则影响首屏感知
          </span>
          <span className="rounded-full bg-white px-3 py-1 text-xs font-medium text-slate-500">
            行点击行为要和详情策略一致
          </span>
          <span className="rounded-full bg-white px-3 py-1 text-xs font-medium text-slate-500">
            分页大小不要牺牲可读性
          </span>
        </div>
        <div className="mt-5 rounded-[24px] border border-slate-200 bg-slate-50/70 p-5">
          <div className="mb-4 border-b border-slate-200 pb-4">
            <div className="text-xs font-semibold uppercase tracking-[0.16em] text-slate-400">
              属性面板
            </div>
            <div className="mt-2 text-base font-semibold text-slate-950">运行规则属性</div>
            <div className="mt-1 text-sm text-slate-500">
              先用这里统一默认行为，再回头检查右侧预览是否符合预期阅读节奏。
            </div>
          </div>
          <SchemaBlockConfigPanel
            schemas={schemas}
            value={behaviorToDisplay(vm.behavior)}
            onChange={(next) =>
              setVm({ ...vm, behavior: displayToBehavior(next) })
            }
            readonly={readonly}
          />
        </div>
      </section>
    </div>
  );
};
