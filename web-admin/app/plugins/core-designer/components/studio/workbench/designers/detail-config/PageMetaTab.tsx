import React, { useEffect, useRef } from 'react';
import { DocumentTextIcon, InformationCircleIcon } from '@heroicons/react/24/outline';
import type { PageSchema } from '~/plugins/core-designer/components/studio/domain/dsl/types';
import { LocalizedTextInput, type LocalizedTextValue } from '~/shared/designer';

export interface PageMetaTabProps {
  schema: PageSchema;
  onSchemaChange: (schema: PageSchema) => void;
  readonly?: boolean;
}

function normalizeTitleValue(
  next: LocalizedTextValue,
  current: PageSchema['title'],
): PageSchema['title'] {
  if (next == null || next === '') {
    return undefined;
  }

  const currentObject =
    current && typeof current === 'object' && !Array.isArray(current)
      ? { ...(current as Record<string, string>) }
      : undefined;

  if (typeof next !== 'string') {
    if (!currentObject || !next) {
      return next as PageSchema['title'];
    }
    return {
      ...currentObject,
      ...(next as Record<string, string>),
    } as PageSchema['title'];
  }

  if (next.startsWith('$i18n:')) {
    return next as PageSchema['title'];
  }

  if (currentObject) {
    currentObject['zh-CN'] = next;
    return currentObject as PageSchema['title'];
  }

  const hasNonAscii = /[^\u0000-\u007f]/.test(next);
  if (hasNonAscii) {
    return { 'zh-CN': next } as PageSchema['title'];
  }

  return next as PageSchema['title'];
}

export const PageMetaTab: React.FC<PageMetaTabProps> = ({ schema, onSchemaChange, readonly }) => {
  const latestSchemaRef = useRef(schema);

  useEffect(() => {
    latestSchemaRef.current = schema;
  }, [schema]);

  const updateSchema = (patch: Partial<PageSchema>) => {
    const nextSchema = { ...latestSchemaRef.current, ...patch };
    latestSchemaRef.current = nextSchema;
    onSchemaChange(nextSchema);
  };

  return (
    <div className="grid gap-6 xl:grid-cols-[minmax(0,1fr)_340px]">
      <section className="space-y-6">
        <section className="rounded-[28px] border border-slate-200 bg-white/90 shadow-sm">
          <div className="border-b border-slate-200 px-6 py-4">
            <div className="inline-flex items-center gap-2 rounded-full bg-slate-100 px-3 py-1 text-xs font-semibold uppercase tracking-[0.16em] text-slate-500">
              <DocumentTextIcon className="h-4 w-4" />
              页面信息
            </div>
            <h2 className="mt-4 text-2xl font-semibold tracking-tight text-slate-950">页面信息</h2>
            <p className="mt-1 text-sm text-slate-500">
              配置详情页标题和基础标识。动作按钮会基于当前页面绑定的模型解析命令和内置行为。
            </p>
            <div className="mt-4 grid gap-3 sm:grid-cols-3">
              <div className="rounded-2xl border border-slate-200 bg-slate-50 px-4 py-3">
                <div className="text-[11px] uppercase tracking-[0.14em] text-slate-400">页面标题</div>
                <div className="mt-2 truncate text-sm font-semibold text-slate-900">
                  {typeof schema.title === 'string'
                    ? schema.title
                    : (schema.title as Record<string, string> | undefined)?.['zh-CN'] || '未设置'}
                </div>
              </div>
              <div className="rounded-2xl border border-slate-200 bg-slate-50 px-4 py-3">
                <div className="text-[11px] uppercase tracking-[0.14em] text-slate-400">页面 Key</div>
                <div className="mt-2 truncate text-sm font-semibold text-slate-900">
                  {schema.pageKey || '未设置'}
                </div>
              </div>
              <div className="rounded-2xl border border-slate-200 bg-slate-50 px-4 py-3">
                <div className="text-[11px] uppercase tracking-[0.14em] text-slate-400">模型编码</div>
                <div className="mt-2 truncate text-sm font-semibold text-slate-900">
                  {schema.modelCode || '未绑定'}
                </div>
              </div>
            </div>
          </div>

          <div className="space-y-6 px-6 py-5">
            <section className="rounded-[24px] border border-slate-200 bg-slate-50/70 p-5">
              <h3 className="mb-4 text-[11px] font-semibold uppercase tracking-[0.16em] text-slate-400">
                展示信息
              </h3>
              <label className="block">
                <span className="mb-2 block text-sm font-medium text-slate-700">页面标题</span>
                <LocalizedTextInput
                  value={schema.title as LocalizedTextValue}
                  onChange={(next) =>
                    updateSchema({
                      title: normalizeTitleValue(next, latestSchemaRef.current.title),
                    })}
                  disabled={readonly}
                  placeholder="输入详情页标题"
                  testId="detail-page-title-input"
                />
              </label>
            </section>

            <section className="rounded-[24px] border border-slate-200 bg-white p-5">
              <h3 className="mb-4 text-[11px] font-semibold uppercase tracking-[0.16em] text-slate-400">
                标识信息
              </h3>
              <div className="grid gap-4 md:grid-cols-2">
                <label className="block">
                  <span className="mb-2 block text-sm font-medium text-slate-700">页面 Key</span>
                  <input
                    type="text"
                    value={schema.pageKey ?? ''}
                    onChange={(e) => updateSchema({ pageKey: e.target.value || undefined })}
                    disabled={readonly}
                    className="w-full rounded-lg border border-slate-200 bg-white px-3 py-2 text-sm text-slate-900 outline-none transition focus:border-blue-300 focus:ring-2 focus:ring-blue-100 disabled:cursor-not-allowed disabled:bg-slate-50 disabled:text-slate-400"
                    placeholder="wd_leave_request_detail"
                    data-testid="detail-page-key-input"
                  />
                </label>

                <label className="block">
                  <span className="mb-2 block text-sm font-medium text-slate-700">模型编码</span>
                  <input
                    type="text"
                    value={schema.modelCode ?? ''}
                    readOnly
                    className="w-full rounded-lg border border-slate-200 bg-slate-50 px-3 py-2 text-sm text-slate-500 outline-none"
                    data-testid="detail-model-code-display"
                  />
                </label>
              </div>
            </section>
          </div>
        </section>
      </section>

      <aside className="min-w-0">
        <div className="sticky top-0 rounded-[28px] border border-slate-200 bg-white/95 shadow-[0_16px_40px_rgba(15,23,42,0.08)] backdrop-blur">
          <div className="border-b border-slate-200 px-5 py-4">
            <div className="text-xs font-semibold uppercase tracking-[0.16em] text-slate-400">
              属性面板
            </div>
            <h3 className="mt-1 text-base font-semibold text-slate-950">联动说明</h3>
            <p className="mt-2 text-sm leading-6 text-slate-500">
              标题、页面 Key 和模型编码会一起决定详情页被如何识别、路由和联动。
            </p>
          </div>

          <div className="space-y-4 px-5 py-5 text-sm text-slate-600">
            <div className="rounded-2xl border border-slate-200 bg-slate-50/80 px-4 py-3">
              <div className="flex items-start gap-3">
                <InformationCircleIcon className="mt-0.5 h-5 w-5 shrink-0 text-blue-500" />
                <div className="space-y-2">
                  <p className="font-medium text-slate-900">页面标题会直接影响运行时详情页头部。</p>
                  <p>
                    自定义按钮的命令列表按当前 <span className="font-medium text-slate-900">{schema.modelCode || '未绑定模型'}</span>{' '}
                    加载，保存后会写入 toolbar button 的 <code className="rounded bg-slate-100 px-1 py-0.5 text-xs">action.command</code>。
                  </p>
                </div>
              </div>
            </div>

            <dl className="space-y-3 rounded-2xl border border-slate-200 bg-white px-4 py-4">
              <div className="flex items-center justify-between gap-3">
                <dt className="text-slate-500">页面类型</dt>
                <dd className="font-medium text-slate-900">{schema.kind}</dd>
              </div>
              <div className="flex items-center justify-between gap-3">
                <dt className="text-slate-500">布局</dt>
                <dd className="font-medium text-slate-900">{schema.layout.type}</dd>
              </div>
              <div className="flex items-center justify-between gap-3">
                <dt className="text-slate-500">路由预览</dt>
                <dd className="truncate font-medium text-slate-900">/p/{schema.pageKey || 'page_key'}</dd>
              </div>
            </dl>
          </div>
        </div>
      </aside>
    </div>
  );
};

export default PageMetaTab;
export { normalizeTitleValue };
