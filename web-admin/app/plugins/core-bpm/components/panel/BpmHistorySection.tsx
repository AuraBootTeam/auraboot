/**
 * BpmHistorySection - Task 14 of the OSS BPM closure spec.
 *
 * Renders the history slot of a {@link BpmPanelBlock}. Given a resolved
 * {@link BpmInstanceForRecord}, it fetches the audit trail via
 * {@link listAuditEvents} and draws a vertical timeline: each event is a dot
 * on a rail, with icon / operation label / operator userId / timestamp, and
 * an optional expandable `<details>` block for structured metadata
 * (comment / receiverUserIds / raw JSON).
 *
 * Scope constraints taken from the per-task prompt:
 *   - field names follow `BpmAuditEvent` verbatim (backend
 *     `BpmAuditRecordEntity`) - no invented fields like `operatorName` or
 *     `timestamp`;
 *   - unknown `operation` values surface the raw string + neutral icon rather
 *     than silently fabricating a translation;
 *   - `userId` is a String with no name lookup - a follow-up task can wire a
 *     user-display resolver if one materialises. See the TODO near the
 *     operator row.
 *
 * Translator is prop-injected (same pattern as the other panel sections) so
 * tests can render without an I18nProvider.
 *
 * @since BPM closure spec 1 (Task 14)
 */
import { useEffect, useState } from 'react';

import {
  listAuditEvents,
  type BpmAuditEvent,
  type BpmInstanceForRecord,
} from '~/plugins/core-bpm/services/bpmWorkbenchService';

/**
 * Translator signature compatible with `useI18n`'s `t`. Kept as a prop for
 * trivial unit-testability (see BpmStatusSection.tsx for the same rationale).
 */
type Translator = (
  key: string,
  params?: Record<string, unknown>,
  fallback?: string,
) => string;

export interface BpmHistorySectionProps {
  instance: BpmInstanceForRecord | null;
  t: Translator;
}

/**
 * Descriptor for one known BPM audit operation. `icon` is a simple single
 * glyph (keeps the timeline dependency-free - no heroicons import); `color`
 * maps to a Tailwind background class used for the timeline rail dot.
 *
 * Known operations are the full set written by backend
 * {@code BpmAuditOperation} enum:
 *   - process-level: {@code process_start};
 *   - task-level: {@code task_approve}, {@code task_reject},
 *     {@code task_add_sign}, {@code task_transfer};
 *   - instance-level (no {@code task_} prefix on the backend):
 *     {@code withdraw}, {@code cc}.
 *
 * Any other value falls through to {@link UNKNOWN_OPERATION_STYLE}.
 */
interface OperationDescriptor {
  icon: string;
  dotClass: string;
  i18nKey: string;
  fallback: string;
}

const OPERATION_LABELS: Record<string, OperationDescriptor> = {
  process_start: {
    icon: '\u{1F680}',
    dotClass: 'bg-blue-500',
    i18nKey: 'bpm.history.op.process_start',
    fallback: '启动流程',
  },
  task_approve: {
    icon: '\u2705',
    dotClass: 'bg-green-500',
    i18nKey: 'bpm.history.op.task_approve',
    fallback: '审批通过',
  },
  task_reject: {
    icon: '\u274C',
    dotClass: 'bg-red-500',
    i18nKey: 'bpm.history.op.task_reject',
    fallback: '驳回',
  },
  task_add_sign: {
    icon: '\u2795',
    dotClass: 'bg-indigo-500',
    i18nKey: 'bpm.history.op.task_add_sign',
    fallback: '加签',
  },
  task_transfer: {
    icon: '\u{1F504}',
    dotClass: 'bg-gray-500',
    i18nKey: 'bpm.history.op.task_transfer',
    fallback: '转办',
  },
  withdraw: {
    icon: '\u21A9',
    dotClass: 'bg-gray-500',
    i18nKey: 'bpm.history.op.withdraw',
    fallback: '撤回',
  },
  cc: {
    icon: '\u{1F4E8}',
    dotClass: 'bg-blue-400',
    i18nKey: 'bpm.history.op.cc',
    fallback: '抄送',
  },
  // SmartEngine native audit event. `details.eventType` (activity_start /
  // activity_end) further discriminates the specific lifecycle phase; see
  // {@link resolveActivityEventPresentation}. The generic fallback label
  // applies when details are missing or eventType is unknown.
  activity_event: {
    icon: '\u2699\uFE0F',
    dotClass: 'bg-gray-400',
    i18nKey: 'bpm.history.op.activity_event',
    fallback: '活动事件',
  },
};

/**
 * Known `details.eventType` values for the SmartEngine-native
 * {@code activity_event} audit operation. Any other eventType degrades to
 * the generic {@code activity_event} label (no silent substitution).
 */
interface ActivityEventPresentation {
  icon: string;
  labelTemplate: (nodeDisplay: string) => string;
}

const ACTIVITY_EVENT_TYPES: Record<string, ActivityEventPresentation> = {
  activity_start: {
    icon: '\u25B6\uFE0F',
    labelTemplate: (nodeDisplay) => `进入节点 ${nodeDisplay}`,
  },
  activity_end: {
    icon: '\u23F9\uFE0F',
    labelTemplate: (nodeDisplay) => `完成节点 ${nodeDisplay}`,
  },
};

/** Neutral descriptor for any operation not in {@link OPERATION_LABELS}. */
const UNKNOWN_OPERATION_STYLE = {
  icon: '\u2022',
  dotClass: 'bg-gray-300',
};

/** Operations whose `details` payload carries a comment/reason worth showing inline. */
const DETAIL_COMMENT_OPERATIONS = new Set([
  'task_approve',
  'task_reject',
  'task_add_sign',
  'task_transfer',
  'withdraw',
  'cc',
]);

/**
 * Determine how many structured details an event carries. Used to decide
 * whether to render the `<details>` expander at all.
 */
function hasExpandableDetails(event: BpmAuditEvent): boolean {
  return event.details !== null && event.details !== undefined && Object.keys(event.details).length > 0;
}

/** Pull a string-valued field out of `details` safely. */
function readStringField(details: Record<string, unknown> | null, key: string): string | null {
  if (!details) return null;
  const value = details[key];
  return typeof value === 'string' && value.length > 0 ? value : null;
}

/** Pull a string-list-valued field out of `details` safely. */
function readStringListField(
  details: Record<string, unknown> | null,
  key: string,
): string[] | null {
  if (!details) return null;
  const value = details[key];
  if (!Array.isArray(value) || value.length === 0) return null;
  const strings = value.filter((v): v is string => typeof v === 'string');
  return strings.length > 0 ? strings : null;
}

/**
 * Sort audit events by `createdAt` descending (newest first). Events without
 * a createdAt sink to the bottom in insertion order.
 */
function sortEventsNewestFirst(events: BpmAuditEvent[]): BpmAuditEvent[] {
  const copy = [...events];
  copy.sort((a, b) => {
    if (!a.createdAt && !b.createdAt) return 0;
    if (!a.createdAt) return 1;
    if (!b.createdAt) return -1;
    // ISO-8601 timestamps sort lexicographically.
    return b.createdAt.localeCompare(a.createdAt);
  });
  return copy;
}

export function BpmHistorySection({ instance, t }: BpmHistorySectionProps) {
  const [events, setEvents] = useState<BpmAuditEvent[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (instance === null) {
      setEvents([]);
      setError(null);
      setLoading(false);
      return;
    }

    let cancelled = false;
    setLoading(true);
    setError(null);

    listAuditEvents(instance.instanceId)
      .then((result) => {
        if (cancelled) return;
        setEvents(result);
        setLoading(false);
      })
      .catch((err: unknown) => {
        if (cancelled) return;
        setError(err instanceof Error ? err.message : String(err));
        setLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, [instance]);

  if (instance === null) {
    return null;
  }

  if (loading) {
    return (
      <div
        data-testid="bpm-history-loading"
        className="rounded border border-gray-200 bg-gray-50 p-6 text-sm text-gray-500"
      >
        {t('bpm.history.loading', undefined, '加载审批历史...')}
      </div>
    );
  }

  if (error) {
    return (
      <div
        data-testid="bpm-history-error"
        className="rounded border border-red-200 bg-red-50 p-4 text-sm text-red-700"
      >
        {t('bpm.history.error', undefined, '审批历史加载失败')}: {error}
      </div>
    );
  }

  if (events.length === 0) {
    return (
      <div
        data-testid="bpm-history-empty"
        className="rounded border border-gray-200 bg-white p-4 text-sm text-gray-500"
      >
        {t('bpm.history.empty', undefined, '暂无审批记录')}
      </div>
    );
  }

  const sorted = sortEventsNewestFirst(events);

  return (
    <div
      data-testid="bpm-history-container"
      className="rounded border border-gray-200 bg-white p-4"
    >
      <div className="mb-3 text-xs font-medium text-gray-500">
        {t('bpm.history.title', undefined, '审批历史')}
      </div>
      <ol className="relative space-y-4 border-l border-gray-200 pl-6">
        {sorted.map((event) => {
          const descriptor = OPERATION_LABELS[event.operation];
          const isKnown = descriptor !== undefined;
          let label = isKnown
            ? t(descriptor.i18nKey, undefined, descriptor.fallback)
            : event.operation;
          let icon = isKnown ? descriptor.icon : UNKNOWN_OPERATION_STYLE.icon;
          const dotClass = isKnown ? descriptor.dotClass : UNKNOWN_OPERATION_STYLE.dotClass;

          // SmartEngine native activity_event: parse details to render a
          // human-readable "进入节点 X" / "完成节点 X" label. When details are
          // missing or eventType is not one of the known values, the generic
          // "活动事件" fallback from OPERATION_LABELS stays in place (no silent
          // substitution to a made-up label).
          let activityType: string | null = null;
          if (event.operation === 'activity_event') {
            const eventType = readStringField(event.details, 'eventType');
            const activityName = readStringField(event.details, 'activityName');
            const activityId = readStringField(event.details, 'activityId');
            activityType = readStringField(event.details, 'activityType');
            const presentation = eventType !== null ? ACTIVITY_EVENT_TYPES[eventType] : undefined;
            if (presentation && (activityName || activityId)) {
              const nodeDisplay = activityName ?? activityId ?? '';
              label = presentation.labelTemplate(nodeDisplay);
              icon = presentation.icon;
            }
          }

          const comment = readStringField(event.details, 'comment');
          const reason = readStringField(event.details, 'reason');
          const receivers = readStringListField(event.details, 'receiverUserIds');

          // Known operations with comment/reason/receivers render an
          // opinionated summary; any event that still has `details` beyond
          // that gets a raw-JSON expander.
          const showStructured =
            DETAIL_COMMENT_OPERATIONS.has(event.operation) && (comment || reason || receivers);
          const hasRawDetails = hasExpandableDetails(event);

          const isFailure =
            (event.result !== null && event.result !== 'success' && event.result !== 'SUCCESS') ||
            (event.errorMessage !== null && event.errorMessage !== '');

          return (
            <li
              key={event.id}
              data-testid={`bpm-history-event-${event.id}`}
              data-operation={event.operation}
              className="relative"
            >
              <span
                className={`absolute -left-[33px] top-1 flex h-5 w-5 items-center justify-center rounded-full text-[10px] text-white ${dotClass}`}
                aria-hidden="true"
              >
                {icon}
              </span>
              <div className="flex flex-wrap items-baseline gap-x-2">
                <span
                  data-testid={`bpm-history-event-${event.id}-label`}
                  className="text-sm font-medium text-gray-800"
                >
                  {label}
                </span>
                {/* TODO(user-display): resolve userId -> display name once a
                    batched user lookup hook exists. For now we render the raw
                    backend id so operators can still audit activity. */}
                {event.userId && (
                  <span className="text-xs text-gray-500">{event.userId}</span>
                )}
                {activityType && (
                  <span
                    data-testid={`bpm-history-event-${event.id}-activity-type`}
                    className="rounded bg-gray-100 px-1.5 py-0.5 font-mono text-[10px] text-gray-500"
                  >
                    {activityType}
                  </span>
                )}
                {event.createdAt && (
                  <span className="ml-auto text-xs text-gray-400">{event.createdAt}</span>
                )}
              </div>

              {isFailure && (
                <div
                  data-testid={`bpm-history-event-${event.id}-failure`}
                  className="mt-1 text-xs text-red-600"
                >
                  {event.errorMessage || event.result}
                </div>
              )}

              {showStructured && (
                <div className="mt-1 space-y-0.5 text-xs text-gray-600">
                  {comment && (
                    <div>
                      <span className="text-gray-400">
                        {t('bpm.history.field.comment', undefined, '意见')}:
                      </span>{' '}
                      {comment}
                    </div>
                  )}
                  {reason && (
                    <div>
                      <span className="text-gray-400">
                        {t('bpm.history.field.reason', undefined, '原因')}:
                      </span>{' '}
                      {reason}
                    </div>
                  )}
                  {receivers && (
                    <div>
                      <span className="text-gray-400">
                        {t('bpm.history.field.receivers', undefined, '抄送人')}:
                      </span>{' '}
                      {receivers.join(', ')}
                    </div>
                  )}
                </div>
              )}

              {hasRawDetails && !showStructured && (
                <details className="mt-1 text-xs text-gray-500">
                  <summary className="cursor-pointer text-gray-400">
                    {t('bpm.history.details.expand', undefined, '详情')}
                  </summary>
                  <pre className="mt-1 overflow-x-auto rounded bg-gray-50 p-2 text-[11px] text-gray-700">
                    {JSON.stringify(event.details, null, 2)}
                  </pre>
                </details>
              )}
            </li>
          );
        })}
      </ol>
    </div>
  );
}
