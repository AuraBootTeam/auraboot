import { useCallback, useEffect, useMemo, useState } from 'react';
import { useLocation } from 'react-router';
import {
  ArrowPathIcon,
  ArrowTopRightOnSquareIcon,
  ClockIcon,
  ExclamationTriangleIcon,
  MagnifyingGlassIcon,
  ShieldExclamationIcon,
} from '@heroicons/react/24/outline';
import { useI18n } from '~/contexts/I18nContext';
import { useTimezone } from '~/contexts/TimezoneContext';
import { formatInTimezone } from '~/shared/services/dateTimeFormatService';
import { fetchResult } from '~/shared/services/http-client';
import { LoadingSpinner } from '~/ui/LoadingSpinner';

interface PermissionAuditEntry {
  id: number;
  tenantId?: number;
  memberId?: number;
  resourceCode?: string;
  actionCode?: string;
  recordPid?: string;
  result?: boolean;
  reason?: string;
  evaluationTrace?: unknown[];
  createdAt?: string;
}

interface PermissionAuditTabProps {
  initialSearch?: string;
}

interface TraceStepView {
  evaluator: string;
  verdict: string;
  reason: string;
  metadata: TraceMetadataItem[];
  fieldGovernance: TraceKeyValue[];
  permissionContext: TraceKeyValue[];
  decisionOutputs: TraceKeyValue[];
  residualDetails?: string;
}

interface TraceMetadataItem {
  labelKey: string;
  fallback: string;
  value: string;
  href?: string;
  testId?: string;
}

interface TraceKeyValue {
  key: string;
  value: string;
}

const SENSITIVE_KEY_PATTERN = /(password|secret|token|api[_-]?key|access[_-]?token|refresh[_-]?token|private[_-]?key|salary|amount|mobile|phone|email|idcard|identity)/i;
const KEY_VALUE_SECRET_PATTERN = /(password|secret|token|api[_-]?key|access[_-]?token|refresh[_-]?token|private[_-]?key)\s*[:=]\s*([^,\s}\]]+)/gi;
const LONG_NUMBER_PATTERN = /\b\d{6,}\b/g;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

export function sanitizePermissionAuditValue(value: unknown, key = ''): unknown {
  if (SENSITIVE_KEY_PATTERN.test(key)) {
    return '***';
  }
  if (typeof value === 'string') {
    return value
      .replace(KEY_VALUE_SECRET_PATTERN, (_match, field) => `${field}=***`)
      .replace(LONG_NUMBER_PATTERN, '***');
  }
  if (Array.isArray(value)) {
    return value.map((item) => sanitizePermissionAuditValue(item));
  }
  if (isRecord(value)) {
    return Object.fromEntries(
      Object.entries(value).map(([entryKey, entryValue]) => [
        entryKey,
        sanitizePermissionAuditValue(entryValue, entryKey),
      ]),
    );
  }
  return value;
}

function asString(value: unknown, fallback: string): string {
  if (typeof value === 'string' && value.trim()) return value;
  if (typeof value === 'number' || typeof value === 'boolean') return String(value);
  return fallback;
}

function safeJson(value: unknown): string {
  try {
    return JSON.stringify(sanitizePermissionAuditValue(value));
  } catch {
    return String(sanitizePermissionAuditValue(value));
  }
}

function toDisplayValue(value: unknown): string {
  const sanitized = sanitizePermissionAuditValue(value);
  if (typeof sanitized === 'string' && sanitized.trim()) return sanitized;
  if (typeof sanitized === 'number' || typeof sanitized === 'boolean') return String(sanitized);
  if (sanitized === null || sanitized === undefined) return '-';
  return safeJson(sanitized);
}

function optionalDisplay(value: unknown): string | undefined {
  const displayValue = toDisplayValue(value);
  return displayValue === '-' ? undefined : displayValue;
}

function rawTraceId(value: unknown): string | undefined {
  return typeof value === 'string' && value.trim() ? value.trim() : undefined;
}

function decisionTraceHref(traceId: string): string {
  return `/p/decisionops_execution_logs?traceId=${encodeURIComponent(traceId)}`;
}

function recordKeyValues(value: unknown): TraceKeyValue[] {
  if (!isRecord(value)) return [];
  return Object.entries(value)
    .filter(([, entryValue]) => entryValue !== undefined)
    .map(([key, entryValue]) => ({ key, value: toDisplayValue(entryValue) }));
}

function recordArray(value: unknown): Record<string, unknown>[] {
  if (!Array.isArray(value)) return [];
  return value.filter(isRecord);
}

function dedupeKeyValues(items: TraceKeyValue[]): TraceKeyValue[] {
  const seen = new Set<string>();
  return items.filter((item) => {
    const key = `${item.key}:${item.value}`;
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

function appendFieldRefs(items: TraceKeyValue[], value: unknown) {
  if (!Array.isArray(value)) return;
  value.forEach((fieldRef) => {
    const display = optionalDisplay(fieldRef);
    if (display) {
      items.push({ key: 'fieldRef', value: display });
    }
  });
}

function fieldGovernanceKeyValues(details: Record<string, unknown>): TraceKeyValue[] {
  const items: TraceKeyValue[] = [];
  const append = (value: unknown) => {
    if (!isRecord(value)) return;
    items.push(...recordKeyValues(value));
  };

  append(details.fieldGovernance);
  appendFieldRefs(items, details.fieldRefs);
  recordArray(details.ruleCenterFailures).forEach((failure) => {
    const grantId = optionalDisplay(failure.grantId);
    if (grantId) {
      items.push({ key: 'grantId', value: grantId });
    }
    appendFieldRefs(items, failure.fieldRefs);
    append(failure.fieldGovernance);
  });

  return dedupeKeyValues(items);
}

function addMetadataOnce(
  metadata: TraceMetadataItem[],
  seen: Set<string>,
  labelKey: string,
  fallback: string,
  value: unknown,
  options?: Pick<TraceMetadataItem, 'href' | 'testId'>,
) {
  const displayValue = optionalDisplay(value);
  if (!displayValue) return;
  const dedupeKey = `${labelKey}:${displayValue}:${options?.href ?? ''}`;
  if (seen.has(dedupeKey)) return;
  seen.add(dedupeKey);
  metadata.push({ labelKey, fallback, value: displayValue, ...options });
}

function toTraceStepView(raw: unknown, index: number): TraceStepView {
  if (isRecord(raw)) {
    const evaluator = asString(
      raw.evaluatorName ?? raw.evaluator ?? raw.name ?? raw.layer ?? raw.source,
      `Step ${index + 1}`,
    );
    const verdict = asString(raw.verdict ?? raw.result ?? raw.decision, '-');
    const reason = asString(raw.reason ?? raw.message ?? raw.detail, '');
    const rest = Object.fromEntries(
      Object.entries(raw).filter(
        ([key]) => !['evaluatorName', 'evaluator', 'name', 'layer', 'source', 'verdict', 'result', 'decision', 'reason', 'message', 'detail', 'details'].includes(key),
      ),
    );
    const rawDetails = raw.details;
    const structuredDetails = isRecord(rawDetails) ? rawDetails : {
      ...rest,
      ...(rawDetails === undefined ? {} : { details: rawDetails }),
    };
    const metadata: TraceMetadataItem[] = [];
    const seenMetadata = new Set<string>();
    const ruleCenterFailures = recordArray(structuredDetails.ruleCenterFailures);
    const ruleTraceSource =
      structuredDetails.ruleTraceId ??
      ruleCenterFailures.find((failure) => rawTraceId(failure.ruleTraceId))?.ruleTraceId;
    const ruleTraceId = optionalDisplay(ruleTraceSource);
    const traceHrefId = rawTraceId(ruleTraceSource);
    if (ruleTraceId && traceHrefId) {
      addMetadataOnce(metadata, seenMetadata, 'admin.permission.audit.traceId', '统一 Trace', ruleTraceId, {
        href: decisionTraceHref(traceHrefId),
        testId: 'permission-audit-open-decision-trace',
      });
    }
    const metadataSources = [structuredDetails, ...ruleCenterFailures];
    metadataSources.forEach((source) => {
      addMetadataOnce(metadata, seenMetadata, 'admin.permission.audit.decisionCode', '决策', source.decisionCode);
      addMetadataOnce(metadata, seenMetadata, 'admin.permission.audit.decisionVersion', '版本', source.decisionVersion);
      addMetadataOnce(metadata, seenMetadata, 'admin.permission.audit.decisionStatus', '状态', source.decisionStatus);
      addMetadataOnce(metadata, seenMetadata, 'admin.permission.audit.bindingKind', '绑定', source.bindingKind);
      addMetadataOnce(metadata, seenMetadata, 'admin.permission.audit.matched', '命中', source.matched);
      addMetadataOnce(metadata, seenMetadata, 'admin.permission.audit.fallbackApplied', 'Fallback', source.fallbackApplied);
    });
    const decisionOutputs = dedupeKeyValues([
      ...recordKeyValues(structuredDetails.decisionOutputs),
      ...ruleCenterFailures.flatMap((failure) => recordKeyValues(failure.decisionOutputs)),
    ]);

    const knownDetailKeys = new Set([
      'ruleTraceId',
      'bindingKind',
      'decisionCode',
      'decisionVersion',
      'decisionStatus',
      'matched',
      'fallbackApplied',
      'fieldGovernance',
      'ruleCenterFailures',
      'permissionContext',
      'decisionOutputs',
      'fieldRefs',
      'decisionRefs',
      'inputSnapshot',
    ]);
    const residual = Object.fromEntries(
      Object.entries(structuredDetails).filter(([key]) => !knownDetailKeys.has(key)),
    );
    return {
      evaluator,
      verdict,
      reason: asString(sanitizePermissionAuditValue(reason), ''),
      metadata,
      fieldGovernance: fieldGovernanceKeyValues(structuredDetails),
      permissionContext: recordKeyValues(structuredDetails.permissionContext),
      decisionOutputs,
      residualDetails: Object.keys(residual).length > 0 ? safeJson(residual) : undefined,
    };
  }
  return {
    evaluator: `Step ${index + 1}`,
    verdict: '-',
    reason: asString(sanitizePermissionAuditValue(raw), ''),
    metadata: [],
    fieldGovernance: [],
    permissionContext: [],
    decisionOutputs: [],
  };
}

function resultLabel(result?: boolean) {
  if (result === false) return 'DENY';
  if (result === true) return 'ALLOW';
  return '-';
}

function resultClass(result?: boolean) {
  if (result === false) {
    return 'border-red-200 bg-red-50 text-red-700 dark:border-red-900/50 dark:bg-red-950/30 dark:text-red-300';
  }
  if (result === true) {
    return 'border-emerald-200 bg-emerald-50 text-emerald-700 dark:border-emerald-900/50 dark:bg-emerald-950/30 dark:text-emerald-300';
  }
  return 'border-gray-200 bg-gray-50 text-gray-500 dark:border-gray-700 dark:bg-gray-800 dark:text-gray-300';
}

export default function PermissionAuditTab({ initialSearch }: PermissionAuditTabProps = {}) {
  const { t } = useI18n();
  const { timezone, formats } = useTimezone();
  const location = useLocation();
  const searchParams = useMemo(
    () => new URLSearchParams(initialSearch ?? location.search),
    [initialSearch, location.search],
  );
  const [entries, setEntries] = useState<PermissionAuditEntry[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [resourceCode, setResourceCode] = useState(searchParams.get('resourceCode') ?? '');
  const [traceId, setTraceId] = useState(searchParams.get('traceId') ?? '');
  const [memberId, setMemberId] = useState('');

  const queryParams = useMemo(() => {
    const params: Record<string, string | number> = { limit: 50 };
    if (traceId.trim()) params.traceId = traceId.trim();
    if (resourceCode.trim()) params.resourceCode = resourceCode.trim();
    if (memberId.trim()) params.memberId = memberId.trim();
    return params;
  }, [memberId, resourceCode, traceId]);

  useEffect(() => {
    const nextTraceId = searchParams.get('traceId') ?? '';
    const nextResourceCode = searchParams.get('resourceCode') ?? '';
    if (nextTraceId) {
      setTraceId(nextTraceId);
    }
    if (nextResourceCode) {
      setResourceCode(nextResourceCode);
    }
  }, [searchParams]);

  const fetchAuditLogs = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const result = await fetchResult<PermissionAuditEntry[]>('/api/permissions/audit', {
        method: 'get',
        params: queryParams,
      });
      if (result.code === '0') {
        setEntries(Array.isArray(result.data) ? result.data : []);
      } else {
        setEntries([]);
        setError(result.message || result.desc || t('admin.permission.audit.loadFailed', undefined, '权限审计加载失败'));
      }
    } catch (err) {
      setEntries([]);
      setError(err instanceof Error ? err.message : t('admin.permission.audit.loadFailed', undefined, '权限审计加载失败'));
    } finally {
      setLoading(false);
    }
  }, [queryParams, t]);

  useEffect(() => {
    fetchAuditLogs();
  }, [fetchAuditLogs]);

  const formatCreatedAt = useCallback(
    (value?: string) => {
      if (!value) return '-';
      return formatInTimezone(value, formats?.datetime || 'YYYY-MM-DD HH:mm:ss', timezone);
    },
    [formats?.datetime, timezone],
  );

  return (
    <div data-testid="permission-audit-tab" className="space-y-4">
      <div className="flex flex-col gap-3 border-b border-gray-200 pb-4 dark:border-gray-700 lg:flex-row lg:items-end lg:justify-between">
        <div>
          <div className="flex items-center gap-2 text-sm font-semibold text-gray-900 dark:text-gray-100">
            <ShieldExclamationIcon className="h-5 w-5 text-red-500" />
            {t('admin.permission.audit.title', undefined, '权限审计')}
          </div>
          <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">
            {t(
              'admin.permission.audit.subtitle',
              undefined,
              '查看权限拒绝决策、策略命中步骤和已脱敏的字段治理证据',
            )}
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <label className="relative">
            <MagnifyingGlassIcon className="absolute top-1/2 left-2.5 h-4 w-4 -translate-y-1/2 text-gray-400" />
            <input
              data-testid="permission-audit-resource-filter"
              value={resourceCode}
              onChange={(event) => setResourceCode(event.target.value)}
              placeholder={t('admin.permission.audit.resourcePlaceholder', undefined, '资源 code')}
              className="h-9 w-44 rounded-md border border-gray-300 py-1.5 pr-3 pl-8 text-sm focus:border-blue-500 focus:ring-1 focus:ring-blue-500 focus:outline-none dark:border-gray-600 dark:bg-gray-800 dark:text-gray-100"
            />
          </label>
          <input
            data-testid="permission-audit-trace-filter"
            value={traceId}
            onChange={(event) => setTraceId(event.target.value)}
            placeholder={t('admin.permission.audit.tracePlaceholder', undefined, 'Trace ID')}
            className="h-9 w-56 rounded-md border border-gray-300 px-3 py-1.5 text-sm focus:border-blue-500 focus:ring-1 focus:ring-blue-500 focus:outline-none dark:border-gray-600 dark:bg-gray-800 dark:text-gray-100"
          />
          <input
            data-testid="permission-audit-member-filter"
            value={memberId}
            onChange={(event) => setMemberId(event.target.value.replace(/[^\d]/g, ''))}
            placeholder={t('admin.permission.audit.memberPlaceholder', undefined, '成员 ID')}
            inputMode="numeric"
            className="h-9 w-32 rounded-md border border-gray-300 px-3 py-1.5 text-sm focus:border-blue-500 focus:ring-1 focus:ring-blue-500 focus:outline-none dark:border-gray-600 dark:bg-gray-800 dark:text-gray-100"
          />
          <button
            type="button"
            data-testid="permission-audit-refresh"
            onClick={fetchAuditLogs}
            disabled={loading}
            className="inline-flex h-9 items-center gap-1.5 rounded-md border border-gray-300 px-3 text-sm font-medium text-gray-700 hover:bg-gray-50 disabled:opacity-60 dark:border-gray-600 dark:text-gray-200 dark:hover:bg-gray-800"
          >
            <ArrowPathIcon className={`h-4 w-4 ${loading ? 'animate-spin' : ''}`} />
            {t('common.refresh', undefined, '刷新')}
          </button>
        </div>
      </div>

      {error && (
        <div
          data-testid="permission-audit-error"
          className="flex items-start gap-2 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700 dark:border-red-900/50 dark:bg-red-950/30 dark:text-red-300"
        >
          <ExclamationTriangleIcon className="mt-0.5 h-4 w-4 flex-shrink-0" />
          <span>{error}</span>
        </div>
      )}

      {loading && entries.length === 0 ? (
        <div data-testid="permission-audit-loading" className="flex items-center justify-center py-16">
          <LoadingSpinner />
        </div>
      ) : entries.length === 0 ? (
        <div
          data-testid="permission-audit-empty"
          className="flex flex-col items-center justify-center rounded-lg border border-dashed border-gray-300 py-16 text-center dark:border-gray-600"
        >
          <ClockIcon className="mb-3 h-10 w-10 text-gray-300" />
          <p className="text-sm text-gray-500 dark:text-gray-400">
            {t('admin.permission.audit.empty', undefined, '暂无权限拒绝审计记录')}
          </p>
        </div>
      ) : (
        <div className="space-y-3">
          {entries.map((entry) => {
            const traceSteps = (entry.evaluationTrace || []).map(toTraceStepView);
            return (
              <article
                key={entry.id}
                data-testid={`permission-audit-row-${entry.id}`}
                className="rounded-lg border border-gray-200 bg-white p-4 shadow-sm dark:border-gray-700 dark:bg-gray-900"
              >
                <div className="flex flex-col gap-3 md:flex-row md:items-start md:justify-between">
                  <div className="min-w-0">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className={`inline-flex items-center rounded-full border px-2 py-0.5 text-xs font-semibold ${resultClass(entry.result)}`}>
                        {resultLabel(entry.result)}
                      </span>
                      <span className="text-sm font-semibold text-gray-900 dark:text-gray-100">
                        {entry.resourceCode || '-'} / {entry.actionCode || '-'}
                      </span>
                    </div>
                    <div className="mt-1 flex flex-wrap gap-x-4 gap-y-1 text-xs text-gray-500 dark:text-gray-400">
                      <span>{t('admin.permission.audit.member', undefined, '成员')}: {entry.memberId ?? '-'}</span>
                      <span>{t('admin.permission.audit.record', undefined, '记录')}: {entry.recordPid || '-'}</span>
                    </div>
                  </div>
                  <time className="text-xs text-gray-500 dark:text-gray-400">
                    {formatCreatedAt(entry.createdAt)}
                  </time>
                </div>

                <div data-testid={`permission-audit-reason-${entry.id}`} className="mt-3 rounded-md bg-gray-50 px-3 py-2 text-sm text-gray-700 dark:bg-gray-800 dark:text-gray-200">
                  {sanitizePermissionAuditValue(entry.reason || t('admin.permission.audit.noReason', undefined, '未提供拒绝原因')) as string}
                </div>

                {traceSteps.length > 0 && (
                  <ol data-testid={`permission-audit-trace-${entry.id}`} className="mt-3 space-y-2">
                    {traceSteps.map((step, index) => (
                      <li
                        key={`${entry.id}-${index}-${step.evaluator}`}
                        data-testid={`permission-audit-trace-step-${entry.id}-${index}`}
                        className="grid gap-2 rounded-md border border-gray-100 px-3 py-2 text-sm dark:border-gray-800 md:grid-cols-[160px_90px_1fr]"
                      >
                        <span className="font-medium text-gray-800 dark:text-gray-100">{step.evaluator}</span>
                        <span className="text-xs font-semibold uppercase text-gray-500 dark:text-gray-400">{step.verdict}</span>
                        <div className="min-w-0 space-y-2 text-gray-600 dark:text-gray-300">
                          <div>{step.reason || step.residualDetails || '-'}</div>
                          {step.metadata.length > 0 && (
                            <div
                              data-testid={`permission-audit-rule-meta-${entry.id}-${index}`}
                              className="flex flex-wrap gap-1.5"
                            >
                              {step.metadata.map((item) => {
                                const content = (
                                  <>
                                    <span className="font-medium">
                                      {t(item.labelKey, undefined, item.fallback)}
                                    </span>
                                    <span className="truncate">{item.value}</span>
                                    {item.href && <ArrowTopRightOnSquareIcon className="h-3.5 w-3.5 flex-shrink-0" />}
                                  </>
                                );
                                const className = "inline-flex max-w-full items-center gap-1 rounded-md border border-blue-100 bg-blue-50 px-2 py-1 text-xs text-blue-700 dark:border-blue-900/50 dark:bg-blue-950/30 dark:text-blue-200";
                                return item.href ? (
                                  <a
                                    key={`${item.labelKey}-${item.value}`}
                                    href={item.href}
                                    data-testid={item.testId ? `${item.testId}-${entry.id}-${index}` : undefined}
                                    className={`${className} hover:border-blue-300 hover:bg-blue-100 dark:hover:border-blue-700 dark:hover:bg-blue-900/40`}
                                  >
                                    {content}
                                  </a>
                                ) : (
                                  <span
                                    key={`${item.labelKey}-${item.value}`}
                                    className={className}
                                  >
                                    {content}
                                  </span>
                                );
                              })}
                            </div>
                          )}
                          {step.fieldGovernance.length > 0 && (
                            <div
                              data-testid={`permission-audit-field-governance-${entry.id}-${index}`}
                              className="rounded-md border border-amber-100 bg-amber-50 px-2.5 py-2 text-xs text-amber-800 dark:border-amber-900/50 dark:bg-amber-950/30 dark:text-amber-200"
                            >
                              <div className="mb-1 font-semibold">
                                {t('admin.permission.audit.fieldGovernance', undefined, '字段治理')}
                              </div>
                              <div className="flex flex-wrap gap-1.5">
                                {step.fieldGovernance.map((item) => (
                                  <span key={`${item.key}-${item.value}`} className="inline-flex max-w-full items-center gap-1 rounded bg-white/70 px-1.5 py-0.5 dark:bg-gray-950/40">
                                    <span className="font-medium">{item.key}</span>
                                    <span className="truncate">{item.value}</span>
                                  </span>
                                ))}
                              </div>
                            </div>
                          )}
                          {step.permissionContext.length > 0 && (
                            <div
                              data-testid={`permission-audit-permission-context-${entry.id}-${index}`}
                              className="rounded-md border border-emerald-100 bg-emerald-50 px-2.5 py-2 text-xs text-emerald-800 dark:border-emerald-900/50 dark:bg-emerald-950/30 dark:text-emerald-200"
                            >
                              <div className="mb-1 font-semibold">
                                {t('admin.permission.audit.permissionContext', undefined, '权限上下文')}
                              </div>
                              <div className="flex flex-wrap gap-1.5">
                                {step.permissionContext.map((item) => (
                                  <span key={item.key} className="inline-flex max-w-full items-center gap-1 rounded bg-white/70 px-1.5 py-0.5 dark:bg-gray-950/40">
                                    <span className="font-medium">{item.key}</span>
                                    <span className="truncate">{item.value}</span>
                                  </span>
                                ))}
                              </div>
                            </div>
                          )}
                          {step.decisionOutputs.length > 0 && (
                            <div
                              data-testid={`permission-audit-decision-outputs-${entry.id}-${index}`}
                              className="rounded-md border border-violet-100 bg-violet-50 px-2.5 py-2 text-xs text-violet-800 dark:border-violet-900/50 dark:bg-violet-950/30 dark:text-violet-200"
                            >
                              <div className="mb-1 font-semibold">
                                {t('admin.permission.audit.decisionOutputs', undefined, 'DMN 输出')}
                              </div>
                              <div className="flex flex-wrap gap-1.5">
                                {step.decisionOutputs.map((item) => (
                                  <span key={item.key} className="inline-flex max-w-full items-center gap-1 rounded bg-white/70 px-1.5 py-0.5 dark:bg-gray-950/40">
                                    <span className="font-medium">{item.key}</span>
                                    <span className="truncate">{item.value}</span>
                                  </span>
                                ))}
                              </div>
                            </div>
                          )}
                          {step.reason && step.residualDetails && (
                            <span
                              data-testid={`permission-audit-residual-details-${entry.id}-${index}`}
                              className="block break-all text-xs text-gray-400"
                            >
                              {step.residualDetails}
                            </span>
                          )}
                        </div>
                      </li>
                    ))}
                  </ol>
                )}
              </article>
            );
          })}
        </div>
      )}
    </div>
  );
}
