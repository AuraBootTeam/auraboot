/**
 * ActivityTimeline - Timeline view of activity records for a specific record.
 * Calls GET /api/activities to display system and user activities.
 *
 * Activity types: STATE_CHANGE, CREATE, UPDATE, DELETE, NOTE, CALL, EMAIL, MEETING, SYSTEM
 * Actor types: USER, SYSTEM, AGENT
 */

import React, { useState, useEffect, useCallback, useMemo } from 'react';
import dayjs from 'dayjs';
import relativeTime from 'dayjs/plugin/relativeTime';
import { fetchResult } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';

dayjs.extend(relativeTime);

const INTERNAL_ID_PATTERN =
  /\b(?:01[0-9A-HJKMNP-TV-Z]{24}|[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12})\b/gi;

interface ActivityRecord {
  id: number | string;
  pid: string;
  objectModel: string;
  objectRecord: string;
  activityType: string;
  subject: string | null;
  content: string | null;
  actorType: string; // USER, SYSTEM, AGENT
  actorId: number | null;
  actorName: string | null;
  commandCode: string | null;
  operationType: string | null;
  metadata: string | null;
  occurredAt: string;
  createdAt: string;
  source?: 'audit' | 'business';
}

export interface BusinessActivityDataSource {
  endpoint?: string;
  url?: string;
  method?: 'get' | 'post';
  queryCode?: string;
  params?: Record<string, unknown>;
}

export interface ActivityTimelineProps {
  modelCode: string;
  recordPid: string;
  token?: string;
  locale?: string;
  t?: (key: string) => string;
  /** Optional CRM business-activity query merged with the platform audit log. */
  businessDataSource?: BusinessActivityDataSource;
}

// Activity type → [icon, zh label, en label, dot color class]
// Dot colors fold onto the 5 semantic status tones (§1.3): purple/teal/indigo
// have no status token so they collapse onto blue; yellow/orange → amber.
const ACTIVITY_TYPE_CONFIG: Record<string, [React.ReactNode, string, string, string]> = {
  CREATE: [<PlusIcon key="c" />, 'Created', 'Created', 'bg-status-green'],
  UPDATE: [<PencilIcon key="u" />, 'Updated', 'Updated', 'bg-status-blue'],
  STATE_CHANGE: [<ArrowPathIcon key="s" />, 'State Change', 'State Change', 'bg-status-blue'],
  DELETE: [<TrashIcon key="d" />, 'Deleted', 'Deleted', 'bg-status-red'],
  NOTE: [<NoteIcon key="n" />, 'Note', 'Note', 'bg-status-amber'],
  CALL: [<PhoneIcon key="p" />, 'Call', 'Call', 'bg-status-blue'],
  EMAIL: [<EmailIcon key="e" />, 'Email', 'Email', 'bg-status-blue'],
  MEETING: [<MeetingIcon key="m" />, 'Meeting', 'Meeting', 'bg-status-amber'],
  VISIT: [<MeetingIcon key="v" />, 'Visit', 'Visit', 'bg-status-green'],
  TASK: [<NoteIcon key="t" />, 'Task', 'Task', 'bg-status-amber'],
  SMS: [<EmailIcon key="sms" />, 'Message', 'Message', 'bg-status-blue'],
  CHAT: [<EmailIcon key="chat" />, 'Chat', 'Chat', 'bg-status-blue'],
  SYSTEM: [<SystemIcon key="sys" />, 'System', 'System', 'bg-status-gray'],
};

export const ActivityTimeline: React.FC<ActivityTimelineProps> = ({
  modelCode,
  recordPid,
  token,
  locale = 'zh-CN',
  businessDataSource,
}) => {
  const [activities, setActivities] = useState<ActivityRecord[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [activeFilter, setActiveFilter] = useState<'all' | 'interaction' | 'task' | 'system'>(
    'all',
  );

  const loadActivities = useCallback(async () => {
    if (!modelCode || !recordPid) {
      setLoading(false);
      return;
    }

    setLoading(true);
    setError(null);
    const auditRequest = fetchResult<ActivityRecord[]>('/api/activities', {
      method: 'get',
      params: { objectModel: modelCode, objectRecord: recordPid, limit: 100 },
      token,
    });
    const businessRequest = businessDataSource
      ? fetchResult<unknown>(
          businessDataSource.endpoint ?? businessDataSource.url ?? '/api/datasource/list',
          {
            method: businessDataSource.method ?? 'get',
            params: resolveBusinessParams(businessDataSource, modelCode, recordPid),
            token,
          },
        )
      : null;

    try {
      const [auditOutcome, businessOutcome] = await Promise.all([
        settle(auditRequest),
        businessRequest ? settle(businessRequest) : Promise.resolve(null),
      ]);
      const auditRows =
        auditOutcome.ok &&
        ResultHelper.isSuccess(auditOutcome.value) &&
        Array.isArray(auditOutcome.value.data)
          ? auditOutcome.value.data.map((row) => ({ ...row, source: 'audit' as const }))
          : [];
      const businessRows =
        businessOutcome?.ok && ResultHelper.isSuccess(businessOutcome.value)
          ? extractBusinessRecords(businessOutcome.value.data).map(normalizeBusinessActivity)
          : [];
      const merged = [...auditRows, ...businessRows].sort(
        (left, right) => dayjs(right.occurredAt).valueOf() - dayjs(left.occurredAt).valueOf(),
      );
      setActivities(merged);

      if (
        merged.length === 0 &&
        (!auditOutcome.ok || (businessOutcome != null && !businessOutcome.ok))
      ) {
        const denied =
          (!auditOutcome.ok && auditOutcome.error?.status === 403) ||
          (businessOutcome != null && !businessOutcome.ok && businessOutcome.error?.status === 403);
        setError(
          denied
            ? locale === 'zh-CN'
              ? '无活动记录查看权限'
              : 'No permission to view activities'
            : locale === 'zh-CN'
              ? '活动时间线加载失败，请重试'
              : 'Failed to load the activity timeline. Try again.',
        );
      }
    } catch (e: any) {
      setError(
        e?.status === 403
          ? locale === 'zh-CN'
            ? '无活动记录查看权限'
            : 'No permission to view activities'
          : e?.message || (locale === 'zh-CN' ? '活动时间线加载失败' : 'Failed to load activities'),
      );
    } finally {
      setLoading(false);
    }
  }, [businessDataSource, modelCode, recordPid, token, locale]);

  useEffect(() => {
    loadActivities();
  }, [loadActivities]);

  const counts = useMemo(() => summarizeActivities(activities), [activities]);
  const filteredActivities = useMemo(
    () => activities.filter((activity) => matchesTimelineFilter(activity, activeFilter)),
    [activeFilter, activities],
  );
  const groups = groupByDate(filteredActivities);

  if (loading) {
    return (
      <div
        className="text-text-3 flex items-center justify-center py-12"
        data-testid="activity-timeline-loading"
      >
        <div className="rounded-pill border-border-strong border-t-accent mr-2 h-5 w-5 animate-spin border-2" />
        {locale === 'zh-CN' ? '加载活动记录...' : 'Loading activities...'}
      </div>
    );
  }

  if (error) {
    return (
      <div
        className="text-status-red py-8 text-center text-sm"
        data-testid="activity-timeline-error"
      >
        {error}
      </div>
    );
  }

  if (activities.length === 0) {
    return (
      <div className="text-text-3 py-12 text-center text-sm" data-testid="activity-timeline-empty">
        {locale === 'zh-CN' ? '暂无活动记录' : 'No activities yet'}
      </div>
    );
  }

  const filterOptions = [
    { key: 'all' as const, zh: '全部', en: 'All', count: counts.all },
    { key: 'interaction' as const, zh: '客户互动', en: 'Interactions', count: counts.interaction },
    { key: 'task' as const, zh: '任务', en: 'Tasks', count: counts.task },
    { key: 'system' as const, zh: '系统变更', en: 'System changes', count: counts.system },
  ];

  return (
    <div data-testid="activity-timeline">
      <div className="border-border mb-5 flex flex-col gap-3 border-b pb-4 sm:flex-row sm:items-end sm:justify-between">
        <div>
          <h3 className="text-text text-base font-semibold">
            {locale === 'zh-CN' ? '客户互动时间线' : 'Customer activity timeline'}
          </h3>
          <p className="text-text-3 mt-1 text-xs">
            {locale === 'zh-CN'
              ? '业务跟进、待办任务与系统变更按时间合并展示'
              : 'Business interactions, tasks, and system changes in one chronological view'}
          </p>
        </div>
        <div className="flex flex-wrap gap-1.5" role="tablist" aria-label="Timeline filters">
          {filterOptions.map((option) => (
            <button
              key={option.key}
              type="button"
              role="tab"
              aria-selected={activeFilter === option.key}
              data-testid={`activity-timeline-filter-${option.key}`}
              onClick={() => setActiveFilter(option.key)}
              className={`rounded-pill px-3 py-1.5 text-xs font-medium transition-colors ${
                activeFilter === option.key
                  ? 'bg-accent text-white'
                  : 'bg-subtle text-text-2 hover:bg-hover'
              }`}
            >
              {locale === 'zh-CN' ? option.zh : option.en}
              <span className="ml-1 opacity-75">{option.count}</span>
            </button>
          ))}
        </div>
      </div>

      {filteredActivities.length === 0 ? (
        <div className="text-text-3 rounded-card bg-subtle py-10 text-center text-sm">
          {locale === 'zh-CN' ? '当前筛选下暂无活动' : 'No activity in this filter'}
        </div>
      ) : (
        <div className="relative">
          <div className="bg-border-strong absolute top-0 bottom-0 left-4 w-px" />

          <div className="space-y-6 pl-10">
            {groups.map((group) => (
              <div key={group.date}>
                {/* Date header */}
                <div className="relative mb-3">
                  <div className="rounded-pill border-border-strong bg-panel absolute top-0.5 -left-10 h-3 w-3 border-2" />
                  <span className="text-text-3 text-xs font-medium tracking-wide uppercase">
                    {formatDateHeader(group.date, locale)}
                  </span>
                </div>

                {/* Activity entries for this date */}
                <div className="space-y-3">
                  {group.entries.map((activity) => {
                    const activityType = normalizeActivityType(activity.activityType);
                    const actorType = normalizeActorType(activity.actorType);
                    const actorLabel = resolveActorLabel(activity, locale);
                    const subject = sanitizeVisibleText(activity.subject);
                    const content = sanitizeVisibleText(activity.content);
                    const config =
                      ACTIVITY_TYPE_CONFIG[activityType] || ACTIVITY_TYPE_CONFIG.SYSTEM;
                    const [icon, _zhLabel, _enLabel, dotColor] = config;

                    return (
                      <div
                        key={activity.id}
                        className="relative"
                        data-testid={`activity-timeline-item-${activity.id}`}
                        data-activity-type={activityType}
                      >
                        {/* Timeline dot */}
                        <div
                          className={`absolute top-1.5 -left-10 h-3 w-3 rounded-full ${dotColor}`}
                        />

                        <div className="rounded-card bg-panel border-border border px-4 py-3 shadow-sm">
                          {/* Header: icon + type badge + actor + time */}
                          <div className="flex items-center gap-2 text-sm">
                            <span className="text-text-3 flex-shrink-0">{icon}</span>
                            <ActivityTypeBadge type={activityType} locale={locale} />
                            <span className="text-text-2 font-medium">{actorLabel}</span>
                            {actorType === 'agent' && (
                              <span className="bg-status-blue-bg text-status-blue rounded px-1.5 py-0.5 text-[10px] font-medium">
                                AI
                              </span>
                            )}
                            <span className="text-text-3">&middot;</span>
                            <time className="text-text-3 text-xs" title={activity.occurredAt}>
                              {formatTime(activity.occurredAt)}
                            </time>
                          </div>

                          {/* Subject */}
                          {subject && <p className="text-text mt-1.5 text-sm">{subject}</p>}

                          {/* Content */}
                          {content && (
                            <p className="text-text-2 mt-1 text-xs whitespace-pre-wrap">
                              {content}
                            </p>
                          )}

                          {/* Metadata (state transitions and business activity context) */}
                          {activity.metadata && (
                            <MetadataDisplay metadata={activity.metadata} locale={locale} />
                          )}
                        </div>
                      </div>
                    );
                  })}
                </div>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
};

// ============================================================================
// Sub-components
// ============================================================================

function ActivityTypeBadge({ type, locale }: { type: string; locale: string }) {
  const labels: Record<string, [string, string, string]> = {
    CREATE: ['创建', 'Created', 'bg-status-green-bg text-status-green'],
    UPDATE: ['更新', 'Updated', 'bg-status-blue-bg text-status-blue'],
    STATE_CHANGE: ['状态变更', 'State Change', 'bg-status-blue-bg text-status-blue'],
    DELETE: ['删除', 'Deleted', 'bg-status-red-bg text-status-red'],
    NOTE: ['备注', 'Note', 'bg-status-amber-bg text-status-amber'],
    CALL: ['通话', 'Call', 'bg-status-blue-bg text-status-blue'],
    EMAIL: ['邮件', 'Email', 'bg-status-blue-bg text-status-blue'],
    MEETING: ['会议', 'Meeting', 'bg-status-amber-bg text-status-amber'],
    VISIT: ['拜访', 'Visit', 'bg-status-green-bg text-status-green'],
    TASK: ['任务', 'Task', 'bg-status-amber-bg text-status-amber'],
    SMS: ['短信', 'Message', 'bg-status-blue-bg text-status-blue'],
    CHAT: ['在线沟通', 'Chat', 'bg-status-blue-bg text-status-blue'],
    SYSTEM: ['系统', 'System', 'bg-status-gray-bg text-status-gray'],
  };
  const [zh, en, cls] = labels[type] || ['—', '—', 'bg-status-gray-bg text-status-gray'];
  return (
    <span
      className={`inline-block flex-shrink-0 rounded px-1.5 py-0.5 text-[10px] font-medium ${cls}`}
    >
      {locale === 'zh-CN' ? zh : en}
    </span>
  );
}

function normalizeActivityType(type: string | null | undefined): string {
  const normalized = String(type ?? '')
    .trim()
    .replace(/-/g, '_')
    .toUpperCase();
  if (normalized === 'STATECHANGE') return 'STATE_CHANGE';
  return normalized || 'SYSTEM';
}

function normalizeActorType(type: string | null | undefined): string {
  return String(type ?? '')
    .trim()
    .toLowerCase();
}

function resolveActorLabel(activity: ActivityRecord, locale: string): string {
  const actorName = sanitizeVisibleText(activity.actorName);
  if (actorName) {
    return actorName;
  }

  const actorType = normalizeActorType(activity.actorType);
  if (actorType === 'system') {
    return locale === 'zh-CN' ? '系统' : 'System';
  }
  if (actorType === 'agent') {
    return locale === 'zh-CN' ? 'AI 助手' : 'AI Assistant';
  }
  return '—';
}

function sanitizeVisibleText(value: string | null | undefined): string | null {
  if (value == null) {
    return null;
  }
  const text = String(value).trim();
  if (!text) {
    return null;
  }
  const sanitized = text
    .replace(INTERNAL_ID_PATTERN, '')
    .replace(/\s+([,.;:，。；：])/g, '$1')
    .replace(/([（(])\s+|\s+([）)])/g, '$1$2')
    .replace(/\s{2,}/g, ' ')
    .trim();
  return sanitized || null;
}

function MetadataDisplay({ metadata, locale }: { metadata: string; locale: string }) {
  try {
    const parsed = JSON.parse(metadata);
    // Display state transitions
    if (parsed.fromState && parsed.toState) {
      const fromState = sanitizeVisibleText(String(parsed.fromState));
      const toState = sanitizeVisibleText(String(parsed.toState));
      if (!fromState || !toState) {
        return null;
      }
      return (
        <div className="mt-1.5 flex items-center gap-1.5 text-xs">
          <span className="bg-status-gray-bg text-status-gray rounded px-1.5 py-0.5">
            {fromState}
          </span>
          <svg
            className="text-text-3 h-3 w-3 flex-shrink-0"
            fill="none"
            stroke="currentColor"
            strokeWidth={2}
            viewBox="0 0 24 24"
          >
            <path d="M5 12h14m-4-4 4 4-4 4" />
          </svg>
          <span className="bg-status-blue-bg text-status-blue rounded px-1.5 py-0.5">
            {toState}
          </span>
        </div>
      );
    }
    // Display changed fields count
    if (
      parsed.changedFields &&
      Array.isArray(parsed.changedFields) &&
      parsed.changedFields.length > 0
    ) {
      return (
        <div className="text-text-3 mt-1 text-xs">
          {locale === 'zh-CN'
            ? `${parsed.changedFields.length} 个字段变更`
            : `${parsed.changedFields.length} field(s) changed`}
        </div>
      );
    }
    const contextItems = [
      parsed.status && {
        label: locale === 'zh-CN' ? '状态' : 'Status',
        value: localizeBusinessValue(String(parsed.status), locale),
      },
      parsed.priority && {
        label: locale === 'zh-CN' ? '优先级' : 'Priority',
        value: localizeBusinessValue(String(parsed.priority), locale),
      },
      parsed.role && {
        label: locale === 'zh-CN' ? '参与角色' : 'Role',
        value: sanitizeVisibleText(String(parsed.role)),
      },
    ].filter((item): item is { label: string; value: string } => Boolean(item?.value));
    if (contextItems.length > 0) {
      return (
        <div className="mt-2 flex flex-wrap gap-1.5 text-[11px]">
          {contextItems.map((item) => (
            <span key={item.label} className="rounded-pill bg-subtle text-text-2 px-2 py-1">
              <span className="text-text-3">{item.label}</span> {item.value}
            </span>
          ))}
        </div>
      );
    }
  } catch {
    // Invalid JSON — skip
  }
  return null;
}

// ============================================================================
// Icons (inline SVG)
// ============================================================================

function PlusIcon() {
  return (
    <svg
      className="h-3.5 w-3.5"
      fill="none"
      stroke="currentColor"
      strokeWidth={2}
      viewBox="0 0 24 24"
    >
      <path d="M12 4v16m8-8H4" />
    </svg>
  );
}

function PencilIcon() {
  return (
    <svg
      className="h-3.5 w-3.5"
      fill="none"
      stroke="currentColor"
      strokeWidth={2}
      viewBox="0 0 24 24"
    >
      <path d="M15.232 5.232l3.536 3.536m-2.036-5.036a2.5 2.5 0 113.536 3.536L6.5 21.036H3v-3.572L16.732 3.732z" />
    </svg>
  );
}

function ArrowPathIcon() {
  return (
    <svg
      className="h-3.5 w-3.5"
      fill="none"
      stroke="currentColor"
      strokeWidth={2}
      viewBox="0 0 24 24"
    >
      <path d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" />
    </svg>
  );
}

function TrashIcon() {
  return (
    <svg
      className="h-3.5 w-3.5"
      fill="none"
      stroke="currentColor"
      strokeWidth={2}
      viewBox="0 0 24 24"
    >
      <path d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" />
    </svg>
  );
}

function NoteIcon() {
  return (
    <svg
      className="h-3.5 w-3.5"
      fill="none"
      stroke="currentColor"
      strokeWidth={2}
      viewBox="0 0 24 24"
    >
      <path d="M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5m-1.414-9.414a2 2 0 112.828 2.828L11.828 15H9v-2.828l8.586-8.586z" />
    </svg>
  );
}

function PhoneIcon() {
  return (
    <svg
      className="h-3.5 w-3.5"
      fill="none"
      stroke="currentColor"
      strokeWidth={2}
      viewBox="0 0 24 24"
    >
      <path d="M3 5a2 2 0 012-2h3.28a1 1 0 01.948.684l1.498 4.493a1 1 0 01-.502 1.21l-2.257 1.13a11.042 11.042 0 005.516 5.516l1.13-2.257a1 1 0 011.21-.502l4.493 1.498a1 1 0 01.684.949V19a2 2 0 01-2 2h-1C9.716 21 3 14.284 3 6V5z" />
    </svg>
  );
}

function EmailIcon() {
  return (
    <svg
      className="h-3.5 w-3.5"
      fill="none"
      stroke="currentColor"
      strokeWidth={2}
      viewBox="0 0 24 24"
    >
      <path d="M3 8l7.89 5.26a2 2 0 002.22 0L21 8M5 19h14a2 2 0 002-2V7a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z" />
    </svg>
  );
}

function MeetingIcon() {
  return (
    <svg
      className="h-3.5 w-3.5"
      fill="none"
      stroke="currentColor"
      strokeWidth={2}
      viewBox="0 0 24 24"
    >
      <path d="M17 20h5v-2a3 3 0 00-5.356-1.857M17 20H7m10 0v-2c0-.656-.126-1.283-.356-1.857M7 20H2v-2a3 3 0 015.356-1.857M7 20v-2c0-.656.126-1.283.356-1.857m0 0a5.002 5.002 0 019.288 0M15 7a3 3 0 11-6 0 3 3 0 016 0zm6 3a2 2 0 11-4 0 2 2 0 014 0zM7 10a2 2 0 11-4 0 2 2 0 014 0z" />
    </svg>
  );
}

function SystemIcon() {
  return (
    <svg
      className="h-3.5 w-3.5"
      fill="none"
      stroke="currentColor"
      strokeWidth={2}
      viewBox="0 0 24 24"
    >
      <path d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.066 2.573c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.573 1.066c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.066-2.573c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z" />
      <path d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" />
    </svg>
  );
}

// ============================================================================
// Helpers
// ============================================================================

interface DateGroup {
  date: string; // YYYY-MM-DD
  entries: ActivityRecord[];
}

type Settled<T> = { ok: true; value: T } | { ok: false; error: any };

async function settle<T>(promise: Promise<T>): Promise<Settled<T>> {
  try {
    return { ok: true, value: await promise };
  } catch (error) {
    return { ok: false, error };
  }
}

function resolveBusinessParams(
  dataSource: BusinessActivityDataSource,
  modelCode: string,
  recordPid: string,
): Record<string, unknown> {
  const interpolate = (value: unknown): unknown => {
    if (typeof value === 'string') {
      return value.replaceAll('${modelCode}', modelCode).replaceAll('${recordPid}', recordPid);
    }
    if (Array.isArray(value)) {
      return value.map(interpolate);
    }
    if (value && typeof value === 'object') {
      return Object.fromEntries(
        Object.entries(value as Record<string, unknown>).map(([key, entry]) => [
          key,
          interpolate(entry),
        ]),
      );
    }
    return value;
  };

  const params = interpolate(dataSource.params ?? {}) as Record<string, unknown>;
  return {
    ...(dataSource.queryCode
      ? { datasourceId: `nq:${dataSource.queryCode}`, format: 'records', maxItems: 100 }
      : {}),
    ...params,
  };
}

function extractBusinessRecords(data: unknown): Record<string, unknown>[] {
  if (Array.isArray(data)) {
    return data as Record<string, unknown>[];
  }
  if (!data || typeof data !== 'object') {
    return [];
  }
  const payload = data as Record<string, unknown>;
  if (Array.isArray(payload.records)) {
    return payload.records as Record<string, unknown>[];
  }
  if (payload.data && typeof payload.data === 'object') {
    const nested = payload.data as Record<string, unknown>;
    if (Array.isArray(nested.records)) {
      return nested.records as Record<string, unknown>[];
    }
  }
  return [];
}

function normalizeBusinessActivity(row: Record<string, unknown>, index: number): ActivityRecord {
  const pid = String(row.pid ?? row.crm_act_pid ?? `row-${index}`);
  const occurredAt = String(
    row.crm_act_date ??
      row.occurred_at ??
      row.occurredAt ??
      row.created_at ??
      new Date(0).toISOString(),
  );
  const metadata = Object.fromEntries(
    [
      ['status', row.crm_act_status ?? row.status],
      ['priority', row.crm_act_priority ?? row.priority],
      ['role', row.crm_act_role ?? row.role],
    ].filter(([, value]) => value != null && String(value).trim() !== ''),
  );

  return {
    id: `business-${pid}`,
    pid,
    objectModel: String(row.crm_act_object_type ?? 'crm_activity_common'),
    objectRecord: String(row.crm_act_object_id ?? ''),
    activityType: String(row.crm_act_type ?? row.activity_type ?? 'NOTE'),
    subject: String(row.crm_act_subject ?? row.subject ?? '').trim() || null,
    content: String(row.crm_act_content ?? row.content ?? row.description ?? '').trim() || null,
    actorType: row.owner_name ? 'USER' : 'SYSTEM',
    actorId: null,
    actorName: String(row.owner_name ?? '').trim() || null,
    commandCode: null,
    operationType: null,
    metadata: Object.keys(metadata).length > 0 ? JSON.stringify(metadata) : null,
    occurredAt,
    createdAt: String(row.created_at ?? occurredAt),
    source: 'business',
  };
}

function matchesTimelineFilter(
  activity: ActivityRecord,
  filter: 'all' | 'interaction' | 'task' | 'system',
): boolean {
  if (filter === 'all') return true;
  if (filter === 'system') return activity.source !== 'business';
  const type = normalizeActivityType(activity.activityType);
  if (filter === 'task') return activity.source === 'business' && type === 'TASK';
  return activity.source === 'business' && type !== 'TASK';
}

function summarizeActivities(activities: ActivityRecord[]) {
  return {
    all: activities.length,
    interaction: activities.filter((activity) => matchesTimelineFilter(activity, 'interaction'))
      .length,
    task: activities.filter((activity) => matchesTimelineFilter(activity, 'task')).length,
    system: activities.filter((activity) => matchesTimelineFilter(activity, 'system')).length,
  };
}

function localizeBusinessValue(value: string, locale: string): string {
  const normalized = value.trim().toUpperCase();
  const labels: Record<string, [string, string]> = {
    PLANNED: ['计划中', 'Planned'],
    IN_PROGRESS: ['进行中', 'In progress'],
    COMPLETED: ['已完成', 'Completed'],
    CANCELLED: ['已取消', 'Cancelled'],
    LOW: ['低', 'Low'],
    MEDIUM: ['中', 'Medium'],
    HIGH: ['高', 'High'],
    URGENT: ['紧急', 'Urgent'],
  };
  const label = labels[normalized];
  return label ? (locale === 'zh-CN' ? label[0] : label[1]) : sanitizeVisibleText(value) || '—';
}

function groupByDate(activities: ActivityRecord[]): DateGroup[] {
  const map = new Map<string, DateGroup>();
  // Already sorted by occurred_at DESC from backend
  for (const a of activities) {
    const date = dayjs(a.occurredAt).format('YYYY-MM-DD');
    if (!map.has(date)) {
      map.set(date, { date, entries: [] });
    }
    map.get(date)!.entries.push(a);
  }
  return Array.from(map.values());
}

function formatDateHeader(dateStr: string, locale: string): string {
  const d = dayjs(dateStr);
  if (!d.isValid()) return dateStr;
  const today = dayjs().format('YYYY-MM-DD');
  const yesterday = dayjs().subtract(1, 'day').format('YYYY-MM-DD');
  if (dateStr === today) return locale === 'zh-CN' ? '今天' : 'Today';
  if (dateStr === yesterday) return locale === 'zh-CN' ? '昨天' : 'Yesterday';
  return d.format(locale === 'zh-CN' ? 'MM月DD日' : 'MMM D');
}

function formatTime(iso: string): string {
  const d = dayjs(iso);
  if (!d.isValid()) return iso;
  return d.format('HH:mm');
}
