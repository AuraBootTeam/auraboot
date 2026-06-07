import React, { useEffect, useRef, useState } from 'react';
import type { BlockConfig } from '~/framework/meta/schemas/types';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';
import {
  executeSimpleWorkbenchAction,
  readDataSourceRows,
  readPath,
  resolveRuntimeValue,
  useDataSourceSubscription,
  useRuntimeStateSubscription,
  writeRuntimeState,
} from './workbenchBlockUtils';

export interface ReviewDrawerBlockRendererProps {
  block: BlockConfig;
  runtime: SchemaRuntime;
}

type Tone = 'green' | 'amber' | 'red' | 'blue' | 'purple' | 'gray';

type PointerState = {
  x: number;
  y: number;
  left?: number;
  top?: number;
  width?: number;
  height?: number;
};

const toneClass: Record<Tone, string> = {
  green: 'bg-emerald-50 text-emerald-700 border-emerald-200',
  amber: 'bg-amber-50 text-amber-700 border-amber-200',
  red: 'bg-rose-50 text-rose-700 border-rose-200',
  blue: 'bg-blue-50 text-blue-700 border-blue-200',
  purple: 'bg-violet-50 text-violet-700 border-violet-200',
  gray: 'bg-gray-100 text-gray-700 border-gray-200',
};

const buttonClass: Record<string, string> = {
  primary: 'bg-blue-600 text-white hover:bg-blue-700',
  secondary: 'border border-gray-300 bg-white text-gray-700 hover:bg-gray-50',
  danger: 'bg-rose-600 text-white hover:bg-rose-700',
};

function parseJsonValue(value: unknown): unknown {
  if (typeof value !== 'string') return value;
  const trimmed = value.trim();
  if (!trimmed || (!trimmed.startsWith('{') && !trimmed.startsWith('['))) return value;
  try {
    return JSON.parse(trimmed);
  } catch {
    return value;
  }
}

function formatValue(value: unknown, emptyText = '-'): string {
  if (value === undefined || value === null || value === '') return emptyText;
  if (typeof value === 'object') return JSON.stringify(value, null, 2);
  return String(value);
}

function applyValueMap(
  value: unknown,
  config: any,
  locale: string,
  t: (key: string) => string,
): unknown {
  const valueMap = config?.valueMap;
  if (!valueMap || typeof valueMap !== 'object') return value;
  const key = String(value ?? '');
  if (!Object.prototype.hasOwnProperty.call(valueMap, key)) return value;
  return getLocalizedText(valueMap[key], locale, t);
}

function formatConfiguredValue(
  value: unknown,
  config: any,
  locale: string,
  t: (key: string) => string,
): string {
  return formatValue(applyValueMap(value, config, locale, t), config?.emptyText);
}

function isEmptyValue(value: unknown): boolean {
  return value === undefined || value === null || value === '';
}

function readFieldValue(record: any, config: any, fallbackRecord?: any): unknown {
  if (Object.prototype.hasOwnProperty.call(config, 'value')) return config.value;
  const source = config.sourceField ? readPath(record, config.sourceField) : record;
  const value = readPath(parseJsonValue(source), config.field || config.valueField);
  if (!isEmptyValue(value) || !config.fallbackField || !fallbackRecord) return value;
  const fallbackSource = config.fallbackSourceField
    ? readPath(fallbackRecord, config.fallbackSourceField)
    : fallbackRecord;
  return readPath(parseJsonValue(fallbackSource), config.fallbackField);
}

function findRelatedRecord(runtime: SchemaRuntime, config: any, selectedRecord: any): any {
  if (!config?.dataSource) return selectedRecord;
  const rows = readDataSourceRows(runtime, config.dataSource);
  if (rows.length === 0) return {};
  const recordField = config.recordField || config.selectedField;
  const matchField = config.matchField;
  if (!recordField || !matchField) return rows[0] || {};
  const expected = readPath(selectedRecord, recordField);
  if (expected === undefined || expected === null || String(expected) === '') return rows[0] || {};
  return rows.find((row: any) => String(readPath(row, matchField)) === String(expected)) || {};
}

function fillTemplate(template: string, runtime: SchemaRuntime, record: any): string {
  return template.replace(/\$\{([^}]+)\}/g, (_match, expression: string) => {
    const path = String(expression).trim();
    if (path.startsWith('record.')) return formatValue(readPath(record, path.slice(7)), '');
    return formatValue(readPath(runtime.getContext(), path), '');
  });
}

function stateBindingFromExpression(expression: unknown): string | undefined {
  if (typeof expression !== 'string') return undefined;
  const match = expression.trim().match(/^\$\{state\.([A-Za-z0-9_]+)\}$/);
  return match?.[1];
}

function sectionLabel(config: any, locale: string, t: (key: string) => string, fallback: string) {
  return getLocalizedText(config?.label || config?.title || fallback, locale, t);
}

function localized(locale: string, t: (key: string) => string, zh: string, en: string) {
  return getLocalizedText({ 'zh-CN': zh, en }, locale, t);
}

function candidateKey(candidate: any, index: number): string {
  return String(candidate?.pid ?? candidate?.id ?? index);
}

function Badge({
  badge,
  record,
  locale,
  t,
}: {
  badge: any;
  record: any;
  locale: string;
  t: (key: string) => string;
}) {
  const key = String(badge.key || badge.valueField || badge.label);
  const label = getLocalizedText(badge.label || key, locale, t);
  const value = readFieldValue(record, badge);
  const text = `${label} ${formatConfiguredValue(value, badge, locale, t)}${badge.unit ? String(badge.unit) : ''}`;
  const tone = (badge.tone || 'gray') as Tone;

  return (
    <span
      data-testid={`review-drawer-badge-${key}`}
      className={`inline-flex rounded-full border px-2.5 py-1 text-xs font-semibold ${
        toneClass[tone] || toneClass.gray
      }`}
    >
      {text}
    </span>
  );
}

function FieldRows({
  fields,
  record,
  fallbackRecord,
  locale,
  t,
}: {
  fields: any[];
  record: any;
  fallbackRecord?: any;
  locale: string;
  t: (key: string) => string;
}) {
  return (
    <div className="divide-y divide-gray-100">
      {fields.map((field) => {
        const key = String(field.key || field.field || field.label);
        const label = getLocalizedText(field.label || key, locale, t);
        const value = formatConfiguredValue(
          readFieldValue(record, field, fallbackRecord),
          field,
          locale,
          t,
        );
        const isMultiline = value.includes('\n') || value.length > 86;
        return (
          <div key={key} className="grid grid-cols-[118px_minmax(0,1fr)] gap-3 px-3 py-2.5 text-sm">
            <dt className="text-xs text-gray-500">{label}</dt>
            <dd
              className={`min-w-0 break-words text-gray-900 ${
                isMultiline ? 'whitespace-pre-wrap' : ''
              }`}
            >
              {value}
            </dd>
          </div>
        );
      })}
    </div>
  );
}

export const ReviewDrawerBlockRenderer: React.FC<ReviewDrawerBlockRendererProps> = ({
  block,
  runtime,
}) => {
  const context = runtime.getContext();
  const locale = context.locale || 'zh-CN';
  const t = context.t || ((key: string) => key);
  const evaluator = runtime.getEvaluator();
  const contextExpression = (block as any).context;
  const contextRecord = resolveRuntimeValue(runtime, contextExpression);
  const compareConfig = (block as any).compare || {};
  const candidatesConfig = (block as any).candidates || {};
  const exportConfig = (block as any).exportImpact || {};
  const sourceConfig = (block as any).source || {};
  const sourceRecordConfig = sourceConfig.record || {};
  const contextDataSource = (block as any).contextDataSource;
  const contextKeyField = (block as any).contextKeyField || 'pid';
  const rawRecordConfig = compareConfig.rawRecord || {};
  const canonicalRecordConfig = compareConfig.canonicalRecord || {};
  const rawDataSource = rawRecordConfig.dataSource;
  const canonicalDataSource = canonicalRecordConfig.dataSource;
  const sourceDataSource = sourceRecordConfig.dataSource;
  const candidateDataSource = candidatesConfig.dataSource;
  const exportDataSource = exportConfig.dataSource;
  const contextStateBinding = stateBindingFromExpression(contextExpression);

  const [selectedCandidateKey, setSelectedCandidateKey] = useState('');
  const [isMinimized, setIsMinimized] = useState(false);
  const [isMaximized, setIsMaximized] = useState(false);
  const [position, setPosition] = useState({ left: 284, top: 112 });
  const [size, setSize] = useState({ width: 1100, height: 680 });
  const [runningAction, setRunningAction] = useState<string | null>(null);
  const dragRef = useRef<PointerState | null>(null);
  const resizeRef = useRef<PointerState | null>(null);

  useRuntimeStateSubscription(runtime);
  useDataSourceSubscription(runtime, contextDataSource);
  useDataSourceSubscription(runtime, rawDataSource);
  useDataSourceSubscription(runtime, canonicalDataSource);
  useDataSourceSubscription(runtime, sourceDataSource);
  useDataSourceSubscription(runtime, candidateDataSource);
  useDataSourceSubscription(runtime, exportDataSource);

  const contextRows = readDataSourceRows(runtime, contextDataSource);
  const contextRecordKey = readPath(contextRecord, contextKeyField);
  const record =
    contextDataSource && contextRecordKey !== undefined && contextRecordKey !== null
      ? contextRows.find(
          (row: any) => String(readPath(row, contextKeyField)) === String(contextRecordKey),
        ) || contextRecord
      : contextRecord;
  const selectedRecordKey = record
    ? String(record.pid ?? record.id ?? record.bom_std_row_no ?? '')
    : '';
  const candidates = readDataSourceRows(runtime, candidateDataSource);
  const exportRows = readDataSourceRows(runtime, exportDataSource);
  const selectedCandidate = candidates.find((row: any, index: number) => {
    return candidateKey(row, index) === selectedCandidateKey;
  });

  useEffect(() => {
    setSelectedCandidateKey('');
    setIsMinimized(false);
    if (candidatesConfig.selection?.bind) {
      writeRuntimeState(runtime, candidatesConfig.selection.bind, {});
    }
  }, [selectedRecordKey, candidatesConfig.selection?.bind, runtime]);

  useEffect(() => {
    const handleMouseMove = (event: MouseEvent) => {
      if (dragRef.current) {
        const nextLeft = (dragRef.current.left || 0) + event.clientX - dragRef.current.x;
        const nextTop = (dragRef.current.top || 0) + event.clientY - dragRef.current.y;
        setPosition({
          left: Math.max(12, Math.min(window.innerWidth - 180, nextLeft)),
          top: Math.max(12, Math.min(window.innerHeight - 84, nextTop)),
        });
      }
      if (resizeRef.current) {
        const nextWidth = (resizeRef.current.width || 0) + event.clientX - resizeRef.current.x;
        const nextHeight = (resizeRef.current.height || 0) + event.clientY - resizeRef.current.y;
        setSize({
          width: Math.max(760, Math.min(window.innerWidth - 24, nextWidth)),
          height: Math.max(500, Math.min(window.innerHeight - 24, nextHeight)),
        });
      }
    };
    const handleMouseUp = () => {
      dragRef.current = null;
      resizeRef.current = null;
    };
    window.addEventListener('mousemove', handleMouseMove);
    window.addEventListener('mouseup', handleMouseUp);
    return () => {
      window.removeEventListener('mousemove', handleMouseMove);
      window.removeEventListener('mouseup', handleMouseUp);
    };
  }, []);

  if (!record || Object.keys(record).length === 0) {
    const emptyTitle = getLocalizedText((block as any).empty?.title || 'Select a row', locale, t);
    return (
      <div
        className="rounded-md border border-gray-200 bg-white p-4 text-sm text-gray-500"
        data-testid="review-drawer-empty"
      >
        {emptyTitle}
      </div>
    );
  }

  const titleTemplate = (block as any).titleTemplate;
  const title = titleTemplate
    ? fillTemplate(String(titleTemplate), runtime, record)
    : getLocalizedText(block.title || 'Review', locale, t);
  const rawRecord = findRelatedRecord(runtime, rawRecordConfig, record);
  const canonicalRecord = findRelatedRecord(runtime, canonicalRecordConfig, record);
  const sourceRecord = sourceRecordConfig.dataSource
    ? findRelatedRecord(runtime, sourceRecordConfig, record)
    : record;
  const summaryBadges = Array.isArray((block as any).summaryBadges)
    ? (block as any).summaryBadges
    : [];

  const actionContext = {
    ...context,
    record,
    row: record,
    selectedRecord: record,
    selectedCandidate,
  };
  const isActionVisible = (actionConfig: any) =>
    !actionConfig.visibleWhen ||
    evaluator.evaluateCondition(actionConfig.visibleWhen, actionContext);
  const isActionDisabledByCondition = (actionConfig: any) =>
    actionConfig.disabledWhen
      ? evaluator.evaluateCondition(actionConfig.disabledWhen, actionContext)
      : false;

  const runAction = async (actionConfig: any, source: 'candidate' | 'export') => {
    const code = String(actionConfig.code || actionConfig.id || actionConfig.label);
    setRunningAction(`${source}:${code}`);
    try {
      await executeSimpleWorkbenchAction(runtime, actionConfig.onClick);
    } catch (error) {
      console.error('[ReviewDrawerBlockRenderer] action failed:', error);
    } finally {
      setRunningAction(null);
    }
  };

  const jumpRow = (offset: number) => {
    if (!contextStateBinding || contextRows.length <= 1) return;
    const currentIndex = contextRows.findIndex((row: any) => {
      const key = readPath(row, contextKeyField);
      return String(key) === String(contextRecordKey);
    });
    if (currentIndex < 0) return;
    const nextIndex = (currentIndex + offset + contextRows.length) % contextRows.length;
    writeRuntimeState(runtime, contextStateBinding, contextRows[nextIndex]);
  };

  if (isMinimized) {
    return (
      <button
        type="button"
        data-testid="review-drawer-minimized"
        aria-label={localized(locale, t, '展开复核浮层', 'Expand review drawer')}
        onClick={() => setIsMinimized(false)}
        className="fixed right-6 bottom-6 z-50 rounded-full border border-blue-300 bg-blue-600 px-4 py-2 text-sm font-semibold text-white shadow-xl hover:bg-blue-700"
      >
        {localized(locale, t, '展开行级复核', 'Open row review')}
      </button>
    );
  }

  const drawerStyle: React.CSSProperties = isMaximized
    ? {
        left: 16,
        top: 16,
        width: 'calc(100vw - 32px)',
        height: 'calc(100vh - 32px)',
      }
    : {
        left: position.left,
        top: position.top,
        width: size.width,
        height: size.height,
        maxWidth: 'calc(100vw - 24px)',
        maxHeight: 'calc(100vh - 24px)',
      };

  return (
    <section
      data-testid="review-drawer"
      style={drawerStyle}
      className="fixed z-50 grid min-h-[500px] min-w-[760px] grid-rows-[auto_auto_minmax(0,1fr)] overflow-hidden rounded-lg border border-blue-300 bg-white shadow-2xl"
    >
      <div
        className="flex min-h-12 cursor-move items-center justify-between gap-3 bg-blue-600 px-4 text-white"
        onMouseDown={(event) => {
          if ((event.target as HTMLElement).closest('button') || isMaximized) return;
          dragRef.current = {
            x: event.clientX,
            y: event.clientY,
            left: position.left,
            top: position.top,
          };
          event.preventDefault();
        }}
      >
        <h2 className="min-w-0 truncate text-base font-semibold">{title}</h2>
        <div className="flex shrink-0 items-center gap-1.5">
          <button
            type="button"
            aria-label={localized(locale, t, '上一行', 'Previous row')}
            onClick={() => jumpRow(-1)}
            className="inline-flex h-7 w-7 items-center justify-center rounded-md text-sm text-white hover:bg-white/15"
          >
            ↑
          </button>
          <button
            type="button"
            aria-label={localized(locale, t, '下一行', 'Next row')}
            onClick={() => jumpRow(1)}
            className="inline-flex h-7 w-7 items-center justify-center rounded-md text-sm text-white hover:bg-white/15"
          >
            ↓
          </button>
          <button
            type="button"
            aria-label={localized(locale, t, '收起复核浮层', 'Minimize review drawer')}
            onClick={() => setIsMinimized(true)}
            className="inline-flex h-7 w-7 items-center justify-center rounded-md text-lg leading-none text-white hover:bg-white/15"
          >
            -
          </button>
          <button
            type="button"
            aria-label={localized(locale, t, '切换最大化', 'Toggle maximize')}
            onClick={() => setIsMaximized((value) => !value)}
            className="inline-flex h-7 w-7 items-center justify-center rounded-md text-sm text-white hover:bg-white/15"
          >
            □
          </button>
          <button
            type="button"
            aria-label={localized(locale, t, '关闭复核浮层', 'Close review drawer')}
            onClick={() => setIsMinimized(true)}
            className="inline-flex h-7 w-7 items-center justify-center rounded-md text-sm text-white hover:bg-white/15"
          >
            ×
          </button>
        </div>
      </div>

      <div className="flex flex-wrap items-center gap-2 border-b border-gray-200 bg-white px-4 py-3">
        {summaryBadges.map((badge: any) => (
          <Badge
            key={String(badge.key || badge.valueField || badge.label)}
            badge={badge}
            record={record}
            locale={locale}
            t={t}
          />
        ))}
      </div>

      <div className="min-h-0 overflow-auto bg-gray-50 p-4">
        <div className="grid gap-3 xl:grid-cols-[minmax(0,1fr)_380px]">
          <div className="space-y-3">
            <div data-testid="review-drawer-tab-compare" className="grid gap-3 lg:grid-cols-2">
              <section className="overflow-hidden rounded-lg border border-gray-200 bg-white">
                <header className="flex items-center justify-between gap-3 border-b border-gray-200 bg-white px-3 py-2 text-sm font-semibold text-gray-700">
                  {sectionLabel(
                    compareConfig.rawTitle ? { title: compareConfig.rawTitle } : null,
                    locale,
                    t,
                    'Raw',
                  )}
                  <span className="rounded-full border border-blue-200 bg-blue-50 px-2 py-0.5 text-xs font-medium text-blue-700">
                    {localized(locale, t, '只读证据', 'Read-only evidence')}
                  </span>
                </header>
                <FieldRows
                  fields={compareConfig.rawFields || []}
                  record={rawRecord}
                  locale={locale}
                  t={t}
                />
              </section>
              <section className="overflow-hidden rounded-lg border border-blue-100 bg-blue-50/40">
                <header className="flex items-center justify-between gap-3 border-b border-blue-100 bg-white/75 px-3 py-2 text-sm font-semibold text-gray-700">
                  {sectionLabel(
                    compareConfig.canonicalTitle ? { title: compareConfig.canonicalTitle } : null,
                    locale,
                    t,
                    'Canonical',
                  )}
                  <span className="rounded-full border border-violet-200 bg-violet-50 px-2 py-0.5 text-xs font-medium text-violet-700">
                    {localized(locale, t, '转换结果', 'Canonical result')}
                  </span>
                </header>
                <FieldRows
                  fields={compareConfig.canonicalFields || []}
                  record={canonicalRecord}
                  fallbackRecord={record}
                  locale={locale}
                  t={t}
                />
              </section>
            </div>

            <section className="rounded-lg border border-gray-200 bg-white p-3">
              <div className="mb-3 flex items-center justify-between gap-3">
                <h3 className="text-sm font-semibold text-gray-900">
                  {localized(locale, t, '可信链路', 'Trust chain')}
                </h3>
                <span className="text-xs text-gray-500">
                  {localized(
                    locale,
                    t,
                    '每个值从哪里来、由谁决定、导出会如何变化',
                    'Source, decision, and export impact',
                  )}
                </span>
              </div>
              <div className="grid gap-2 md:grid-cols-3 xl:grid-cols-6">
                {[
                  [
                    localized(locale, t, '1. Profile 命中', '1. Profile matched'),
                    localized(
                      locale,
                      t,
                      '识别 sheet、表头、字段组合策略',
                      'Sheet, header, composition',
                    ),
                  ],
                  [
                    localized(locale, t, '2. 程序归一', '2. Normalized'),
                    localized(locale, t, '单位、位号、封装、数值规范化', 'Unit, refdes, package'),
                  ],
                  [
                    localized(locale, t, '3. LLM 辅助', '3. LLM assisted'),
                    localized(locale, t, '仅按 Profile Policy 触发', 'Profile-policy controlled'),
                  ],
                  [
                    localized(locale, t, '4. 规则匹配', '4. Rule matched'),
                    localized(
                      locale,
                      t,
                      'MPN / 品牌 / 规格 / AML 打分',
                      'MPN, brand, spec, AML score',
                    ),
                  ],
                  [
                    localized(locale, t, '5. 用户决策', '5. User decision'),
                    localized(locale, t, '确认写入，撤销清空', 'Confirm writes, undo clears'),
                  ],
                  [
                    localized(locale, t, '6. 导出版本', '6. Export revision'),
                    localized(locale, t, '下载前强制重新生成', 'Regenerate before download'),
                  ],
                ].map(([titleText, body]) => (
                  <div
                    key={titleText}
                    className="rounded-lg border border-blue-100 bg-blue-50/60 p-2.5"
                  >
                    <div className="text-xs font-semibold text-gray-900">{titleText}</div>
                    <div className="mt-1 text-xs text-gray-500">{body}</div>
                  </div>
                ))}
              </div>
            </section>

            <details
              open
              data-testid="review-drawer-tab-source"
              className="overflow-hidden rounded-lg border border-gray-200 bg-white"
            >
              <summary className="cursor-pointer bg-white px-3 py-2 text-sm font-semibold text-gray-900">
                {localized(
                  locale,
                  t,
                  '解析证据与 Profile / LLM Policy',
                  'Parse evidence and Profile / LLM policy',
                )}
              </summary>
              <div className="space-y-3 border-t border-gray-100 p-3">
                <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-4">
                  {(sourceConfig.cards || []).map((card: any) => {
                    const key = String(card.key || card.title || card.valueField);
                    const value = `${formatValue(readFieldValue(sourceRecord, card), card.emptyText)}${
                      card.unit ? String(card.unit) : ''
                    }`;
                    return (
                      <section
                        key={key}
                        className="rounded-lg border border-gray-200 bg-gray-50 p-3"
                      >
                        <h3 className="text-xs font-medium text-gray-500">
                          {getLocalizedText(card.title || key, locale, t)}
                        </h3>
                        <div className="mt-2 text-sm font-semibold break-words text-gray-900">
                          {value}
                        </div>
                        {card.description && (
                          <p className="mt-1 text-xs text-gray-500">
                            {getLocalizedText(card.description, locale, t)}
                          </p>
                        )}
                      </section>
                    );
                  })}
                </div>
                {(sourceConfig.policies || []).length > 0 && (
                  <section className="rounded-lg border border-gray-200 bg-white p-3">
                    <h3 className="mb-3 text-sm font-semibold text-gray-900">
                      {getLocalizedText(
                        sourceConfig.policyTitle || {
                          'zh-CN': 'LLM 行为由 Profile Policy 控制',
                          en: 'LLM behavior is controlled by Profile Policy',
                        },
                        locale,
                        t,
                      )}
                    </h3>
                    <div className="grid gap-3 md:grid-cols-3">
                      {sourceConfig.policies.map((policy: any) => (
                        <div
                          key={String(policy.key || policy.title)}
                          className="rounded-md border border-gray-200 bg-gray-50 p-3"
                        >
                          <h4 className="text-sm font-medium text-gray-900">
                            {getLocalizedText(policy.title || policy.key, locale, t)}
                          </h4>
                          <ul className="mt-2 list-disc space-y-1 pl-5 text-xs text-gray-600">
                            {(policy.items || []).map((item: any) => (
                              <li key={String(item)}>{String(item)}</li>
                            ))}
                          </ul>
                        </div>
                      ))}
                    </div>
                  </section>
                )}
                {sourceConfig.jsonField && (
                  <pre className="max-h-64 overflow-auto rounded-lg border border-slate-700 bg-slate-950 p-3 text-xs text-blue-100">
                    {JSON.stringify(
                      parseJsonValue(readPath(sourceRecord, sourceConfig.jsonField)),
                      null,
                      2,
                    )}
                  </pre>
                )}
              </div>
            </details>

            <details
              data-testid="review-drawer-tab-export"
              className="overflow-hidden rounded-lg border border-gray-200 bg-white"
            >
              <summary className="cursor-pointer bg-white px-3 py-2 text-sm font-semibold text-gray-900">
                {localized(locale, t, '决策历史与导出影响', 'Decision history and export impact')}
              </summary>
              <div className="space-y-3 border-t border-gray-100 p-3">
                <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-4">
                  {(exportConfig.fields || []).map((field: any) => {
                    const key = String(field.key || field.field || field.label);
                    return (
                      <section
                        key={key}
                        className="rounded-lg border border-gray-200 bg-gray-50 p-3"
                      >
                        <div className="text-xs text-gray-500">
                          {getLocalizedText(field.label || key, locale, t)}
                        </div>
                        <div className="mt-1 text-sm font-semibold break-words text-gray-900">
                          {formatConfiguredValue(readFieldValue(record, field), field, locale, t)}
                        </div>
                      </section>
                    );
                  })}
                </div>
                {exportRows.length > 0 && (
                  <ol className="divide-y divide-gray-100 rounded-lg border border-gray-200 bg-white">
                    {exportRows.map((row: any, index: number) => (
                      <li
                        key={String(row.pid ?? row.id ?? index)}
                        className="px-3 py-2 text-sm text-gray-700"
                      >
                        <span className="font-mono font-semibold">
                          {formatValue(readPath(row, 'bom_er_filename'), String(row.pid ?? index))}
                        </span>
                        <span className="ml-2 text-xs text-gray-500">
                          Rev {formatValue(readPath(row, 'bom_er_revision_no'))}
                        </span>
                      </li>
                    ))}
                  </ol>
                )}
                {(exportConfig.actions || []).length > 0 && (
                  <div className="flex justify-end gap-2">
                    {exportConfig.actions.filter(isActionVisible).map((actionConfig: any) => {
                      const code = String(
                        actionConfig.code || actionConfig.id || actionConfig.label,
                      );
                      const disabled = Boolean(
                        isActionDisabledByCondition(actionConfig) || runningAction,
                      );
                      return (
                        <button
                          key={code}
                          type="button"
                          data-testid={`review-drawer-export-action-${code}`}
                          disabled={disabled}
                          onClick={() => void runAction(actionConfig, 'export')}
                          className={`rounded-md px-3 py-2 text-sm font-medium ${
                            buttonClass[actionConfig.variant || 'secondary'] ||
                            buttonClass.secondary
                          } disabled:cursor-not-allowed disabled:opacity-50`}
                        >
                          {runningAction === `export:${code}`
                            ? t('common.loading')
                            : getLocalizedText(actionConfig.label || code, locale, t)}
                        </button>
                      );
                    })}
                  </div>
                )}
              </div>
            </details>
          </div>

          <aside
            data-testid="review-drawer-tab-candidates"
            className="overflow-hidden rounded-lg border border-gray-200 bg-white"
          >
            <header className="flex items-center justify-between gap-3 border-b border-gray-100 px-3 py-2">
              <h3 className="text-sm font-semibold text-gray-900">
                {getLocalizedText(
                  candidatesConfig.title || {
                    'zh-CN': '候选物料与用户决策',
                    en: 'Candidates and decision',
                  },
                  locale,
                  t,
                )}
              </h3>
            </header>
            <div className="max-h-[360px] space-y-2 overflow-auto p-3">
              {candidates.length === 0 ? (
                <div
                  data-testid="review-drawer-candidates-empty"
                  className="rounded-lg border border-dashed border-gray-300 p-4 text-sm text-gray-500"
                >
                  {getLocalizedText(
                    candidatesConfig.empty?.title || { 'zh-CN': '暂无候选', en: 'No candidates' },
                    locale,
                    t,
                  )}
                </div>
              ) : (
                candidates.map((candidate: any, index: number) => {
                  const rowKey = candidateKey(candidate, index);
                  const active = rowKey === selectedCandidateKey;
                  const item = candidatesConfig.item || {};
                  const titleText = formatValue(readPath(candidate, item.titleField), rowKey);
                  const score = item.scoreField ? readPath(candidate, item.scoreField) : undefined;
                  return (
                    <button
                      key={rowKey}
                      type="button"
                      data-testid={`review-drawer-candidate-${rowKey}`}
                      onClick={() => {
                        setSelectedCandidateKey(rowKey);
                        if (candidatesConfig.selection?.bind) {
                          writeRuntimeState(runtime, candidatesConfig.selection.bind, candidate);
                        }
                      }}
                      className={`w-full rounded-lg border p-3 text-left ${
                        active
                          ? 'border-blue-400 bg-blue-50'
                          : 'border-gray-200 bg-white hover:bg-gray-50'
                      }`}
                    >
                      <div className="flex items-start justify-between gap-3">
                        <div className="min-w-0">
                          <div className="font-mono text-sm font-semibold text-gray-900">
                            {titleText}
                          </div>
                          <dl className="mt-2 grid gap-2 text-xs sm:grid-cols-2">
                            {(item.detailFields || []).map((field: any) => {
                              const key = String(field.key || field.field || field.label);
                              const label = getLocalizedText(field.label || key, locale, t);
                              const value = formatConfiguredValue(
                                readFieldValue(candidate, field),
                                field,
                                locale,
                                t,
                              );
                              return (
                                <div
                                  key={key}
                                  className={field.span === 2 ? 'sm:col-span-2' : undefined}
                                >
                                  <dt className="text-gray-500">{label}</dt>
                                  <dd className="mt-0.5 break-words text-gray-800">{value}</dd>
                                </div>
                              );
                            })}
                          </dl>
                        </div>
                        {score !== undefined && (
                          <span className="rounded-full bg-emerald-50 px-2 py-1 text-xs font-semibold text-emerald-700">
                            {String(score)}
                          </span>
                        )}
                      </div>
                    </button>
                  );
                })
              )}
            </div>
            <section className="border-t border-gray-100 bg-gray-50 p-3">
              <h3 className="text-sm font-semibold text-gray-900">
                {getLocalizedText(
                  candidatesConfig.decisionTitle || { 'zh-CN': '当前决策状态', en: 'Decision' },
                  locale,
                  t,
                )}
              </h3>
              <dl className="mt-3 space-y-2 text-sm">
                <div className="grid grid-cols-[96px_minmax(0,1fr)] gap-2">
                  <dt className="text-xs text-gray-500">
                    {localized(locale, t, '标准编码', 'Standard Code')}
                  </dt>
                  <dd className="font-mono text-gray-900">
                    {formatValue(
                      readPath(record, 'bom_std_material_code'),
                      localized(locale, t, '确认候选后写入', 'Pending confirmation'),
                    )}
                  </dd>
                </div>
                <div className="grid grid-cols-[96px_minmax(0,1fr)] gap-2">
                  <dt className="text-xs text-gray-500">
                    {localized(locale, t, '当前状态', 'Reason')}
                  </dt>
                  <dd className="break-words text-gray-900">
                    {formatConfiguredValue(
                      readPath(record, 'bom_std_reason_code'),
                      candidatesConfig.reasonField || {},
                      locale,
                      t,
                    )}
                  </dd>
                </div>
              </dl>
              {selectedCandidate && (candidatesConfig.selectedFields || []).length > 0 && (
                <section className="mt-4 rounded-md border border-gray-200 bg-white">
                  <header className="border-b border-gray-200 px-3 py-2 text-xs font-semibold text-gray-700">
                    {getLocalizedText(
                      candidatesConfig.selectedTitle || {
                        'zh-CN': '匹配证据',
                        en: 'Match Evidence',
                      },
                      locale,
                      t,
                    )}
                  </header>
                  <FieldRows
                    fields={candidatesConfig.selectedFields || []}
                    record={selectedCandidate}
                    locale={locale}
                    t={t}
                  />
                </section>
              )}
              {(candidatesConfig.actions || []).length > 0 && (
                <div className="mt-4 flex flex-wrap justify-end gap-2">
                  {candidatesConfig.actions.filter(isActionVisible).map((actionConfig: any) => {
                    const code = String(actionConfig.code || actionConfig.id || actionConfig.label);
                    const requiresSelection =
                      actionConfig.requiresSelection !== false &&
                      actionConfig.code !== 'undo_decision';
                    const disabled = Boolean(
                      (requiresSelection && !selectedCandidate) ||
                      isActionDisabledByCondition(actionConfig) ||
                      runningAction,
                    );
                    return (
                      <button
                        key={code}
                        type="button"
                        data-testid={`review-drawer-candidate-action-${code}`}
                        disabled={disabled}
                        onClick={() => void runAction(actionConfig, 'candidate')}
                        className={`rounded-md px-3 py-2 text-sm font-medium ${
                          buttonClass[actionConfig.variant || 'primary'] || buttonClass.primary
                        } disabled:cursor-not-allowed disabled:opacity-50`}
                      >
                        {runningAction === `candidate:${code}`
                          ? t('common.loading')
                          : getLocalizedText(actionConfig.label || code, locale, t)}
                      </button>
                    );
                  })}
                </div>
              )}
            </section>
          </aside>
        </div>
      </div>

      {!isMaximized && (
        <button
          type="button"
          aria-label={localized(locale, t, '调整复核浮层大小', 'Resize review drawer')}
          className="absolute right-0 bottom-0 h-5 w-5 cursor-nwse-resize"
          onMouseDown={(event) => {
            resizeRef.current = {
              x: event.clientX,
              y: event.clientY,
              width: size.width,
              height: size.height,
            };
            event.preventDefault();
          }}
        >
          <span className="absolute right-1 bottom-1 h-2 w-2 border-r-2 border-b-2 border-blue-400" />
        </button>
      )}
    </section>
  );
};

export default ReviewDrawerBlockRenderer;
