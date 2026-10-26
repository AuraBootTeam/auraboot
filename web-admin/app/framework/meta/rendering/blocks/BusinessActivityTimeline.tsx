import React, { useEffect, useMemo, useRef, useState } from 'react';
import type { DataSourceManager } from '~/framework/meta/runtime/data-pipeline/DataSourceManager';
import type {
  BusinessActivityRecordMapping,
  BusinessActivityDetailField,
  BusinessActivitySnapshotField,
  LocalizedText,
} from '~/framework/meta/schemas/types';
import { getLocalizedText } from '~/framework/meta/runtime/expression/i18n-renderer';
import dayjs from 'dayjs';
import { formatInTimezone } from '~/shared/services/dateTimeFormatService';
import { readServerTablePage } from './serverTablePage';

type Manager = Pick<
  DataSourceManager,
  'getConfig' | 'getState' | 'subscribe' | 'fetch' | 'reload' | 'dependenciesReady'
>;
export interface BusinessActivityRuntime {
  getContext(): unknown;
  getDataSourceManager(): Manager;
}
export type BusinessActivityDictionaryReader = (code: string) => ReadonlyArray<{
  value: unknown;
  label: string | LocalizedText;
}>;
type Value = string | number | boolean | null;
type FieldValues = Array<{ field: BusinessActivityDetailField; value: Exclude<Value, null> }>;
type Entry = {
  pid: string;
  at: string;
  subject: string | null;
  note: string | null;
  actor: string | null;
  transition?: { from: Value | undefined; to: Value | undefined };
  details: FieldValues;
  previous: FieldValues;
};
type Data = { rows: Entry[]; total?: number; pageSize?: number; page?: number };
type Reason = 'configuration' | 'denied' | 'unavailable' | 'invalid';
type Snapshot = {
  identity: object;
  phase: 'loading' | 'pending' | 'error' | 'ready';
  reason?: Reason;
  data?: Data;
};
interface Props {
  enabled: boolean;
  modelCode: string;
  recordPid: string;
  source?: string;
  runtime?: BusinessActivityRuntime;
  mapping?: BusinessActivityRecordMapping;
  stateDictCode?: string;
  detailFields?: readonly BusinessActivityDetailField[];
  snapshotFields?: readonly BusinessActivitySnapshotField[];
  getDictItems?: BusinessActivityDictionaryReader;
  timezone?: string;
  compact?: boolean;
  previewLimit?: number;
  locale?: string;
  t?: (key: string) => string;
}
function object(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}
function text(value: unknown): value is string {
  return typeof value === 'string' && value.trim().length > 0;
}
function identityReady(runtime: BusinessActivityRuntime, pid: string, model: string): boolean {
  const context = runtime.getContext();
  if (!text(pid) || !text(model) || !object(context)) return false;
  const page = object(context.$page) ? context.$page : {};
  if (page.modelCode !== undefined && page.modelCode !== model) return false;
  const record = object(context.record) ? context.record : {};
  const form = object(context.form) ? context.form : {};
  const row = object(context.row) ? context.row : {};
  const candidates = [page.recordPid, record.pid, context.recordPid, form.pid, row.pid].filter(
    (value) => value !== undefined && value !== null && value !== '',
  );
  return candidates.length > 0 && candidates.every((value) => value === pid);
}
function optionalText(row: Record<string, unknown>, field?: string): string | null {
  // Public rows may omit null or field-filtered display values. Never recover
  // those values from another endpoint or an older record snapshot.
  if (!field || !Object.hasOwn(row, field)) return null;
  const value = row[field];
  if (value === null || value === '') return null;
  if (typeof value !== 'string') throw new Error('Invalid mapped history text');
  return value;
}
function value(row: Record<string, unknown>, field: string): Value {
  if (!Object.hasOwn(row, field)) throw new Error('Missing mapped history value');
  const actual = row[field];
  if (
    actual === null ||
    typeof actual === 'string' ||
    typeof actual === 'boolean' ||
    (typeof actual === 'number' && Number.isFinite(actual))
  )
    return actual;
  throw new Error('Invalid mapped history value');
}
function optionalValue(row: Record<string, unknown>, field: string): Value | undefined {
  // Missing is unknown/not visible, not evidence of a business null transition.
  return Object.hasOwn(row, field) ? value(row, field) : undefined;
}
function readFields(
  row: Record<string, unknown>,
  fields: readonly BusinessActivityDetailField[],
): FieldValues {
  const present: FieldValues = [];
  for (const field of fields) {
    if (!Object.hasOwn(row, field.field)) continue;
    const actual = value(row, field.field);
    if (actual !== null && actual !== '') present.push({ field, value: actual });
  }
  return present;
}
function readBusinessData(
  raw: unknown,
  pid: string,
  mapping: BusinessActivityRecordMapping,
  fields: readonly BusinessActivitySnapshotField[],
  detailFields: readonly BusinessActivityDetailField[],
): Data {
  const payload = object(raw) ? raw : null;
  const rows: unknown = Array.isArray(raw) ? raw : payload?.records;
  if (!Array.isArray(rows)) throw new Error('Invalid history response');
  let page: { current: number; total: number; pageSize: number } | undefined;
  if (
    payload &&
    ['page', 'pageNum', 'current', 'total', 'pageSize'].some((key) => Object.hasOwn(payload, key))
  ) {
    page = readServerTablePage(
      payload,
      Object.hasOwn(payload, 'page')
        ? 'page'
        : Object.hasOwn(payload, 'pageNum')
          ? 'pageNum'
          : 'current',
    );
  }
  const seen = new Set<string>();
  const entries = rows
    .map((row): Entry => {
      if (!object(row) || row[mapping.objectRecord!] !== pid)
        throw new Error('History belongs to another record');
      const id = row[mapping.pid ?? 'pid'],
        at = row[mapping.occurredAt!];
      if (
        !text(id) ||
        seen.has(id) ||
        !text(at) ||
        !/^\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})?$/.test(at) ||
        !dayjs.utc(at).isValid()
      )
        throw new Error('Invalid history identity or clock');
      seen.add(id);
      const transition =
        mapping.fromState &&
        mapping.toState &&
        (Object.hasOwn(row, mapping.fromState) || Object.hasOwn(row, mapping.toState))
          ? {
              from: optionalValue(row, mapping.fromState),
              to: optionalValue(row, mapping.toState),
            }
          : undefined;
      const previous = readFields(row, fields),
        details = readFields(row, detailFields);
      return {
        pid: id,
        at,
        transition,
        details,
        previous,
        subject: optionalText(row, mapping.subject),
        note: optionalText(row, mapping.content),
        actor: optionalText(row, mapping.actorName),
      };
    })
    .sort((left, right) => dayjs.utc(right.at).valueOf() - dayjs.utc(left.at).valueOf());
  return { rows: entries, total: page?.total, pageSize: page?.pageSize, page: page?.current };
}
function errorReason(error: unknown): Reason {
  return object(error) &&
    [error.status, error.statusCode, error.code].some(
      (code) => String(code) === '403' || String(code) === '401',
    )
    ? 'denied'
    : 'unavailable';
}

/** A view over the owning runtime's real named source, not a second query engine. */
export function BusinessActivityTimeline({
  enabled,
  runtime,
  source,
  modelCode,
  recordPid,
  mapping = {},
  stateDictCode,
  detailFields = [],
  snapshotFields = [],
  getDictItems,
  timezone,
  compact = false,
  previewLimit,
  locale = 'zh-CN',
  t,
}: Props) {
  const mappingKey = JSON.stringify(mapping),
    fieldsKey = JSON.stringify({ detailFields, snapshotFields });
  const identity = useMemo(
    () => ({ runtime, source, modelCode, recordPid, mappingKey, fieldsKey }),
    [runtime, source, modelCode, recordPid, mappingKey, fieldsKey],
  );
  const [snapshot, setSnapshot] = useState<Snapshot | null>(null);
  const retry = useRef<() => void>(() => undefined);
  const label = (key: string, zh: string, en: string) => {
    const translated = t?.(key);
    return translated && translated !== key ? translated : locale.startsWith('zh') ? zh : en;
  };
  useEffect(() => {
    let active = true,
      initialized = false,
      sequence = 0;
    const publish = (state: Omit<Snapshot, 'identity'>) => {
      if (active) setSnapshot({ identity, ...state });
    };
    const configurationError = () => publish({ phase: 'error', reason: 'configuration' });
    retry.current = () => undefined;
    if (
      !enabled ||
      !runtime ||
      !source ||
      !text(mapping.objectRecord) ||
      !text(mapping.occurredAt) ||
      !!mapping.fromState !== !!mapping.toState ||
      ((mapping.fromState || mapping.toState) && !text(stateDictCode)) ||
      (previewLimit !== undefined &&
        (!Number.isSafeInteger(previewLimit) || previewLimit < 1 || previewLimit > 500))
    ) {
      configurationError();
      return () => {
        active = false;
      };
    }
    const manager = runtime.getDataSourceManager(),
      config = manager.getConfig(source);
    if (
      !config ||
      !['api', 'namedQuery'].includes(config.type ?? '') ||
      !config.dependOn?.some((path) =>
        ['$page.recordPid', 'record.pid', 'row.pid', 'form.pid'].includes(path),
      )
    ) {
      configurationError();
      return () => {
        active = false;
      };
    }
    const ready = () =>
      identityReady(runtime, recordPid, modelCode) && manager.dependenciesReady(config);
    const accept = (raw: unknown) => {
      if (!active || !ready()) {
        publish({ phase: 'pending' });
        return false;
      }
      try {
        publish({
          phase: 'ready',
          data: readBusinessData(raw, recordPid, mapping, snapshotFields, detailFields),
        });
        return true;
      } catch {
        publish({ phase: 'error', reason: 'invalid' });
        return false;
      }
    };
    const unsubscribe = manager.subscribe(source, (state) => {
      if (!active) return;
      if (!ready()) {
        initialized = false;
        publish({ phase: 'pending' });
      } else if (state.loading) publish({ phase: 'loading' });
      else if (state.error) publish({ phase: 'error', reason: errorReason(state.error) });
      else if (initialized && state.data !== null && state.data !== undefined) accept(state.data);
    });
    const load = async (force: boolean) => {
      const current = ++sequence;
      initialized = false;
      if (!ready()) {
        publish({ phase: 'pending' });
        return;
      }
      const before = manager.getState(source);
      const refresh =
        force || (before?.data !== null && before?.data !== undefined && !before.loading);
      publish({ phase: 'loading' });
      try {
        if (refresh) await manager.reload(source);
        else await manager.fetch(source);
        if (!active || current !== sequence) return;
        const state = manager.getState(source);
        if (!ready()) {
          publish({ phase: 'pending' });
          return;
        }
        if (state?.error) {
          publish({ phase: 'error', reason: errorReason(state.error) });
          return;
        }
        if (!state || state.loading || state.data === null || state.data === undefined) {
          publish({ phase: 'error', reason: 'unavailable' });
          return;
        }
        // DMS may retain a cache during hard backoff. It is not a completed reread.
        if (
          refresh &&
          before?.data !== null &&
          before?.data !== undefined &&
          state.data === before.data
        ) {
          publish({ phase: 'error', reason: 'unavailable' });
          return;
        }
        initialized = accept(state.data);
      } catch (error) {
        if (active && current === sequence) publish({ phase: 'error', reason: errorReason(error) });
      }
    };
    retry.current = () => {
      void load(true);
    };
    void load(false);
    return () => {
      active = false;
      sequence++;
      retry.current = () => undefined;
      unsubscribe();
    };
  }, [identity, enabled, stateDictCode, previewLimit]);
  const current = snapshot?.identity === identity ? snapshot : null;
  const readyNow = runtime && identityReady(runtime, recordPid, modelCode);
  if ((!readyNow || current?.phase === 'pending') && current?.reason !== 'configuration')
    return (
      <div data-testid="activity-timeline-pending" data-record-pid={recordPid} role="status">
        {label(
          'activity.timeline.pending',
          '请选择记录后查看历史',
          'Select a record to read its history',
        )}
      </div>
    );
  if (!current || current.phase === 'loading')
    return (
      <div
        data-testid="activity-timeline-loading"
        data-record-pid={recordPid}
        aria-busy="true"
        role="status"
      >
        {label('activity.timeline.loading', '正在读取历史记录…', 'Loading history…')}
      </div>
    );
  if (current.phase === 'error')
    return (
      <div
        data-testid="activity-timeline-error"
        data-record-pid={recordPid}
        data-error-reason={current.reason}
        role="alert"
      >
        {current.reason === 'configuration'
          ? label(
              'activity.timeline.configuration',
              '历史记录配置不完整，请联系管理员。',
              'History configuration is incomplete. Contact an administrator.',
            )
          : current.reason === 'denied'
            ? label(
                'activity.timeline.denied',
                '当前会话或权限不允许读取历史记录。',
                'The current session or permission does not allow this history read.',
              )
            : current.reason === 'invalid'
              ? label(
                  'activity.timeline.invalid',
                  '历史记录身份或内容未通过校验。',
                  'History identity or content could not be verified.',
                )
              : label(
                  'activity.timeline.unavailable',
                  '历史记录暂不可用，请重试。',
                  'History is unavailable. Please retry.',
                )}
        {current.reason !== 'configuration' && (
          <button
            type="button"
            data-testid="activity-timeline-retry"
            onClick={() => retry.current()}
          >
            {label('activity.timeline.retry', '重试', 'Retry')}
          </button>
        )}
      </div>
    );
  const data = current.data!;
  const limit = Math.min(
    previewLimit ?? data.pageSize ?? 100,
    data.pageSize ?? previewLimit ?? 100,
  );
  const visible = data.rows.slice(0, limit);
  const dict = (code: string | undefined, actual: Value | undefined, state = false): string => {
    if (actual === undefined)
      return label(
        'activity.timeline.unavailableValue',
        '未提供或不可见',
        'Not provided or not visible',
      );
    if (actual === null) return '—';
    if (!code) return String(actual);
    const item = (getDictItems?.(code) ?? []).find(
      (option) => String(option.value) === String(actual),
    );
    return item
      ? getLocalizedText(item.label, locale, t)
      : state
        ? label('activity.timeline.unknownState', '未识别状态', 'Unknown state')
        : label('activity.timeline.unknownValue', '未识别值', 'Unknown value');
  };
  const countTemplate =
    data.total === undefined
      ? label(
          'activity.timeline.limitUnknown',
          '显示{shown}条，总数未提供；最多{limit}条',
          'Showing {shown}; total unavailable; limit {limit}',
        )
      : label(
          'activity.timeline.limit',
          '显示{shown}条，共{total}条；最多{limit}条',
          'Showing {shown} of {total}; limit {limit}',
        );
  const count = countTemplate
    .replaceAll('{shown}', String(visible.length))
    .replaceAll('{total}', String(data.total))
    .replaceAll('{limit}', String(limit));
  return (
    <div data-testid="activity-timeline" data-record-pid={recordPid}>
      <p data-testid="activity-timeline-limit" className="text-text-3 mb-3 text-xs">
        {count}
        {data.page !== undefined && (
          <>
            {' '}
            · {label('activity.timeline.page', '页', 'Page')} {data.page}
          </>
        )}
      </p>
      {visible.length === 0 ? (
        <div data-testid="activity-timeline-empty" role="status">
          {label('activity.timeline.empty', '暂无历史记录', 'No history records')}
        </div>
      ) : (
        <ol className="border-border ml-2 space-y-4 border-l pl-5">
          {visible.map((row) => (
            <li
              key={row.pid}
              data-testid={`activity-timeline-item-business-${row.pid}`}
              className={`border-border bg-panel rounded-card relative border ${compact ? 'p-3' : 'p-4'}`}
            >
              {row.subject !== null && (
                <h4
                  data-testid={`activity-timeline-subject-${row.pid}`}
                  className="text-text text-sm font-semibold break-words whitespace-pre-wrap"
                >
                  {row.subject}
                </h4>
              )}
              <div className="text-text-3 mt-1 flex flex-wrap items-center justify-between gap-2 text-xs">
                <span data-testid={`activity-timeline-actor-${row.pid}`}>
                  {row.actor ??
                    label(
                      'activity.timeline.unknownActor',
                      '操作人未提供或不可见',
                      'Actor unavailable',
                    )}
                </span>
                <time dateTime={row.at} title={row.at}>
                  {formatInTimezone(row.at, 'YYYY-MM-DD HH:mm:ss', timezone)}
                </time>
              </div>
              {row.transition && (
                <div
                  data-testid={`activity-timeline-transition-${row.pid}`}
                  className="text-text-2 mt-2 flex flex-wrap items-center gap-2 text-xs"
                >
                  <span className="bg-subtle rounded px-2 py-1">
                    {dict(stateDictCode, row.transition.from, true)}
                  </span>
                  <span aria-hidden="true">→</span>
                  <span className="bg-accent/10 text-accent rounded px-2 py-1">
                    {dict(stateDictCode, row.transition.to, true)}
                  </span>
                </div>
              )}
              {row.details.length > 0 && (
                <dl data-testid={`activity-timeline-details-${row.pid}`} className="mt-3 space-y-2">
                  {row.details.map(({ field, value: actual }) => (
                    <div key={field.field}>
                      <dt className="text-text-3 text-xs">
                        {getLocalizedText(field.label, locale, t)}
                      </dt>
                      <dd className="text-text mt-1 text-sm [overflow-wrap:anywhere] break-words whitespace-pre-wrap">
                        {dict(field.dictCode, actual)}
                      </dd>
                    </div>
                  ))}
                </dl>
              )}
              {row.note !== null && (
                <p
                  data-testid={`activity-timeline-note-${row.pid}`}
                  className="text-text-2 mt-2 text-sm [overflow-wrap:anywhere] break-words whitespace-pre-wrap"
                >
                  {row.note}
                </p>
              )}
              {row.previous.length > 0 && (
                <details
                  data-testid={`activity-timeline-snapshot-${row.pid}`}
                  className="border-border rounded-control mt-3 border p-3"
                >
                  <summary className="text-text-2 cursor-pointer text-sm">
                    {label('activity.timeline.previous', '查看历史快照', 'View previous values')}
                  </summary>
                  <dl className="mt-3 space-y-3">
                    {row.previous.map(({ field, value: actual }) => (
                      <div key={field.field}>
                        <dt className="text-text-3 text-xs">
                          {getLocalizedText(field.label, locale, t)}
                        </dt>
                        <dd className="text-text mt-1 text-sm [overflow-wrap:anywhere] break-words whitespace-pre-wrap">
                          {dict(field.dictCode, actual)}
                        </dd>
                      </div>
                    ))}
                  </dl>
                </details>
              )}
            </li>
          ))}
        </ol>
      )}
    </div>
  );
}
