import React, { useEffect, useState } from 'react';
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

const toneClass: Record<Tone, string> = {
  green: 'bg-emerald-50 text-emerald-700',
  amber: 'bg-amber-50 text-amber-700',
  red: 'bg-rose-50 text-rose-700',
  blue: 'bg-blue-50 text-blue-700',
  purple: 'bg-violet-50 text-violet-700',
  gray: 'bg-gray-100 text-gray-700',
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

function sectionLabel(config: any, locale: string, t: (key: string) => string, fallback: string) {
  return getLocalizedText(config?.label || config?.title || fallback, locale, t);
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
  const text = `${label} ${formatValue(value)}${badge.unit ? String(badge.unit) : ''}`;
  const tone = (badge.tone || 'gray') as Tone;

  return (
    <span
      data-testid={`review-drawer-badge-${key}`}
      className={`inline-flex rounded-md px-2 py-1 text-xs font-semibold ${toneClass[tone] || toneClass.gray}`}
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
        const value = formatValue(readFieldValue(record, field, fallbackRecord), field.emptyText);
        const isMultiline = value.includes('\n') || value.length > 90;
        return (
          <div key={key} className="grid grid-cols-[110px_minmax(0,1fr)] gap-3 px-3 py-2 text-sm">
            <dt className="text-xs text-gray-500">{label}</dt>
            <dd className={`break-words text-gray-900 ${isMultiline ? 'whitespace-pre-wrap' : ''}`}>
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
  const contextRecord = resolveRuntimeValue(runtime, (block as any).context);
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
  const [activeTab, setActiveTab] = useState('compare');
  const [selectedCandidateKey, setSelectedCandidateKey] = useState('');
  const [isMinimized, setIsMinimized] = useState(false);
  const [runningAction, setRunningAction] = useState<string | null>(null);

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
      ? contextRows.find((row: any) => String(readPath(row, contextKeyField)) === String(contextRecordKey)) || contextRecord
      : contextRecord;
  const selectedRecordKey = record ? String(record.pid ?? record.id ?? record.bom_std_row_no ?? '') : '';
  useEffect(() => {
    setSelectedCandidateKey('');
    setIsMinimized(false);
    if (candidatesConfig.selection?.bind) {
      writeRuntimeState(runtime, candidatesConfig.selection.bind, {});
    }
  }, [selectedRecordKey, candidatesConfig.selection?.bind, runtime]);

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
  const summaryBadges = Array.isArray((block as any).summaryBadges) ? (block as any).summaryBadges : [];
  const candidates = readDataSourceRows(runtime, candidateDataSource);
  const exportRows = readDataSourceRows(runtime, exportDataSource);
  const selectedCandidate = candidates.find((row: any, index: number) => {
    const rowKey = String(row?.pid ?? row?.id ?? index);
    return rowKey === selectedCandidateKey;
  });
  const actionContext = {
    ...context,
    record,
    row: record,
    selectedRecord: record,
    selectedCandidate,
  };
  const isActionVisible = (actionConfig: any) =>
    !actionConfig.visibleWhen || evaluator.evaluateCondition(actionConfig.visibleWhen, actionContext);
  const isActionDisabledByCondition = (actionConfig: any) =>
    actionConfig.disabledWhen ? evaluator.evaluateCondition(actionConfig.disabledWhen, actionContext) : false;

  const tabLabels = (block as any).tabLabels || {};
  const configuredTabs = [
    { key: 'compare', label: tabLabels.compare || { 'zh-CN': '原始 vs 转换', en: 'Raw vs Canonical' } },
    { key: 'source', label: tabLabels.source || { 'zh-CN': '解析来源', en: 'Parse Source' } },
    { key: 'candidates', label: tabLabels.candidates || { 'zh-CN': '候选与决策', en: 'Candidates' } },
    { key: 'export', label: tabLabels.export || { 'zh-CN': '导出影响', en: 'Export Impact' } },
  ];

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

  if (isMinimized) {
    return (
      <div className="rounded-lg border border-blue-200 bg-blue-50/60 p-3">
        <button
          type="button"
          data-testid="review-drawer-minimized"
          aria-label={getLocalizedText({ 'zh-CN': '展开复核浮层', en: 'Expand review drawer' }, locale, t)}
          onClick={() => setIsMinimized(false)}
          className="rounded-lg border border-blue-300 bg-blue-600 px-3 py-2 text-sm font-semibold text-white shadow-sm hover:bg-blue-700"
        >
          {getLocalizedText({ 'zh-CN': '复核', en: 'Review' }, locale, t)}
        </button>
      </div>
    );
  }

  return (
    <section
      data-testid="review-drawer"
      className="grid min-h-[520px] max-h-[calc(100vh-220px)] grid-rows-[auto_auto_minmax(0,1fr)] overflow-hidden rounded-lg border border-blue-300 bg-white shadow-xl"
    >
      <div className="flex min-h-12 items-center justify-between gap-3 bg-blue-600 px-4 text-white">
        <h2 className="min-w-0 truncate text-base font-semibold">{title}</h2>
        <div className="flex shrink-0 items-center gap-2">
          <span className="rounded-md bg-white/15 px-2 py-1 text-xs font-medium">
            {getLocalizedText((block as any).stateLabel || { 'zh-CN': '行级复核', en: 'Review' }, locale, t)}
          </span>
          <button
            type="button"
            aria-label={getLocalizedText({ 'zh-CN': '收起复核浮层', en: 'Minimize review drawer' }, locale, t)}
            onClick={() => setIsMinimized(true)}
            className="inline-flex h-7 w-7 items-center justify-center rounded-md text-lg leading-none text-white hover:bg-white/15"
          >
            -
          </button>
        </div>
      </div>

      <div className="flex flex-wrap items-center gap-2 border-b border-gray-200 px-4 py-3">
        {summaryBadges.map((badge: any) => (
          <Badge key={String(badge.key || badge.valueField || badge.label)} badge={badge} record={record} locale={locale} t={t} />
        ))}
      </div>

      <div className="grid min-h-0 grid-cols-[176px_minmax(0,1fr)]">
        <div className="border-r border-gray-200 bg-gray-50 p-2">
          <div role="tablist" aria-label="review drawer tabs" className="space-y-1">
            {configuredTabs.map((tab) => {
              const label = getLocalizedText(tab.label, locale, t);
              const active = activeTab === tab.key;
              return (
                <button
                  key={tab.key}
                  type="button"
                  role="tab"
                  aria-selected={active}
                  onClick={() => setActiveTab(tab.key)}
                  className={`flex w-full items-center justify-between rounded-md px-3 py-2 text-left text-sm font-medium ${
                    active ? 'bg-blue-100 text-blue-700' : 'text-gray-600 hover:bg-gray-100'
                  }`}
                >
                  {label}
                </button>
              );
            })}
          </div>
        </div>

        <div className="min-h-0 overflow-auto p-4">
          {activeTab === 'compare' && (
            <div data-testid="review-drawer-tab-compare" className="space-y-3">
              <div className="grid gap-3 lg:grid-cols-[minmax(0,1fr)_minmax(0,1fr)]">
                <section className="rounded-lg border border-gray-200 bg-gray-50">
                  <header className="border-b border-gray-200 px-3 py-2 text-sm font-semibold text-gray-700">
                    {sectionLabel(compareConfig.rawTitle ? { title: compareConfig.rawTitle } : null, locale, t, 'Raw')}
                  </header>
                  <FieldRows fields={compareConfig.rawFields || []} record={rawRecord} locale={locale} t={t} />
                </section>
                <section className="rounded-lg border border-blue-100 bg-blue-50/40">
                  <header className="border-b border-blue-100 px-3 py-2 text-sm font-semibold text-gray-700">
                    {sectionLabel(compareConfig.canonicalTitle ? { title: compareConfig.canonicalTitle } : null, locale, t, 'Canonical')}
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
            </div>
          )}

          {activeTab === 'source' && (
            <div data-testid="review-drawer-tab-source" className="space-y-3">
              <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-4">
                {(sourceConfig.cards || []).map((card: any) => {
                  const key = String(card.key || card.title || card.valueField);
                  const value = `${formatValue(readFieldValue(sourceRecord, card), card.emptyText)}${card.unit ? String(card.unit) : ''}`;
                  return (
                    <section key={key} className="rounded-lg border border-gray-200 bg-white p-3">
                      <h3 className="text-sm font-semibold text-gray-900">{getLocalizedText(card.title || key, locale, t)}</h3>
                      <div className="mt-2 text-2xl font-semibold text-gray-900">{value}</div>
                      {card.description && (
                        <p className="mt-1 text-xs text-gray-500">{getLocalizedText(card.description, locale, t)}</p>
                      )}
                    </section>
                  );
                })}
              </div>
              {(sourceConfig.policies || []).length > 0 && (
                <section className="rounded-lg border border-gray-200 bg-white p-3">
                  <h3 className="mb-3 text-sm font-semibold text-gray-900">
                    {getLocalizedText(sourceConfig.policyTitle || { 'zh-CN': 'Profile Policy', en: 'Profile Policy' }, locale, t)}
                  </h3>
                  <div className="grid gap-3 md:grid-cols-3">
                    {sourceConfig.policies.map((policy: any) => (
                      <div key={String(policy.key || policy.title)} className="rounded-md border border-gray-200 bg-gray-50 p-3">
                        <h4 className="text-sm font-medium text-gray-900">{getLocalizedText(policy.title || policy.key, locale, t)}</h4>
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
                <pre className="max-h-72 overflow-auto rounded-lg border border-gray-200 bg-gray-50 p-3 text-xs text-gray-700">
                  {JSON.stringify(parseJsonValue(readPath(sourceRecord, sourceConfig.jsonField)), null, 2)}
                </pre>
              )}
            </div>
          )}

          {activeTab === 'candidates' && (
            <div data-testid="review-drawer-tab-candidates" className="space-y-3">
              <div className="grid gap-3 xl:grid-cols-[minmax(0,1fr)_320px]">
                <section className="rounded-lg border border-gray-200 bg-white">
                  <header className="border-b border-gray-100 px-3 py-2 text-sm font-semibold text-gray-900">
                    {getLocalizedText(candidatesConfig.title || { 'zh-CN': '候选物料', en: 'Candidates' }, locale, t)}
                  </header>
                  <div className="space-y-2 p-3">
                    {candidates.length === 0 ? (
                      <div data-testid="review-drawer-candidates-empty" className="text-sm text-gray-500">
                        {getLocalizedText(candidatesConfig.empty?.title || { 'zh-CN': '暂无候选', en: 'No candidates' }, locale, t)}
                      </div>
                    ) : (
                      candidates.map((candidate: any, index: number) => {
                        const rowKey = String(candidate.pid ?? candidate.id ?? index);
                        const active = rowKey === selectedCandidateKey;
                        const item = candidatesConfig.item || {};
                        const title = formatValue(readPath(candidate, item.titleField), rowKey);
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
                              active ? 'border-blue-400 bg-blue-50' : 'border-gray-200 bg-white hover:bg-gray-50'
                            }`}
                          >
                            <div className="flex items-start justify-between gap-3">
                              <div className="min-w-0">
                                <div className="font-mono text-sm font-semibold text-gray-900">{title}</div>
                                <dl className="mt-2 grid gap-2 text-xs sm:grid-cols-2">
                                  {(item.detailFields || []).map((field: any) => {
                                    const key = String(field.key || field.field || field.label);
                                    const label = getLocalizedText(field.label || key, locale, t);
                                    const value = formatValue(readFieldValue(candidate, field));
                                    return (
                                      <div key={key} className={field.span === 2 ? 'sm:col-span-2' : undefined}>
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
                </section>
                <section className="rounded-lg border border-gray-200 bg-white p-3">
                  <h3 className="text-sm font-semibold text-gray-900">
                    {getLocalizedText(candidatesConfig.decisionTitle || { 'zh-CN': '当前决策状态', en: 'Decision' }, locale, t)}
                  </h3>
                  <dl className="mt-3 space-y-2 text-sm">
                    <div className="grid grid-cols-[90px_minmax(0,1fr)] gap-2">
                      <dt className="text-xs text-gray-500">{getLocalizedText({ 'zh-CN': '标准编码', en: 'Standard Code' }, locale, t)}</dt>
                      <dd className="font-mono text-gray-900">{formatValue(readPath(record, 'bom_std_material_code'), getLocalizedText({ 'zh-CN': '确认候选后写入', en: 'Pending confirmation' }, locale, t))}</dd>
                    </div>
                    <div className="grid grid-cols-[90px_minmax(0,1fr)] gap-2">
                      <dt className="text-xs text-gray-500">Reason</dt>
                      <dd className="break-words text-gray-900">{formatValue(readPath(record, 'bom_std_reason_code'))}</dd>
                    </div>
                  </dl>
                  {selectedCandidate && (candidatesConfig.selectedFields || []).length > 0 && (
                    <section className="mt-4 rounded-md border border-gray-200 bg-gray-50">
                      <header className="border-b border-gray-200 px-3 py-2 text-xs font-semibold text-gray-700">
                        {getLocalizedText(
                          candidatesConfig.selectedTitle || { 'zh-CN': '匹配证据', en: 'Match Evidence' },
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
                        const requiresSelection = actionConfig.requiresSelection !== false && actionConfig.code !== 'undo_decision';
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
              </div>
            </div>
          )}

          {activeTab === 'export' && (
            <div data-testid="review-drawer-tab-export" className="space-y-3">
              <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-4">
                {(exportConfig.fields || []).map((field: any) => {
                  const key = String(field.key || field.field || field.label);
                  return (
                    <section key={key} className="rounded-lg border border-gray-200 bg-white p-3">
                      <div className="text-xs text-gray-500">{getLocalizedText(field.label || key, locale, t)}</div>
                      <div className="mt-1 break-words text-sm font-semibold text-gray-900">
                        {formatValue(readFieldValue(record, field))}
                      </div>
                    </section>
                  );
                })}
              </div>
              {exportRows.length > 0 && (
                <ol className="divide-y divide-gray-100 rounded-lg border border-gray-200 bg-white">
                  {exportRows.map((row: any, index: number) => (
                    <li key={String(row.pid ?? row.id ?? index)} className="px-3 py-2 text-sm text-gray-700">
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
                    const code = String(actionConfig.code || actionConfig.id || actionConfig.label);
                    const disabled = Boolean(isActionDisabledByCondition(actionConfig) || runningAction);
                    return (
                      <button
                        key={code}
                        type="button"
                        data-testid={`review-drawer-export-action-${code}`}
                        disabled={disabled}
                        onClick={() => void runAction(actionConfig, 'export')}
                        className={`rounded-md px-3 py-2 text-sm font-medium ${
                          buttonClass[actionConfig.variant || 'secondary'] || buttonClass.secondary
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
          )}
        </div>
      </div>
    </section>
  );
};

export default ReviewDrawerBlockRenderer;
