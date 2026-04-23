/**
 * ActivityTimeline - Timeline view of activity records for a specific record.
 * Calls GET /api/activities to display system and user activities.
 *
 * Activity types: STATE_CHANGE, CREATE, UPDATE, DELETE, NOTE, CALL, EMAIL, MEETING, SYSTEM
 * Actor types: USER, SYSTEM, AGENT
 */

import React, { useState, useEffect, useCallback } from 'react';
import dayjs from 'dayjs';
import relativeTime from 'dayjs/plugin/relativeTime';
import { fetchResult } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';

dayjs.extend(relativeTime);

interface ActivityRecord {
  id: number;
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
}

export interface ActivityTimelineProps {
  modelCode: string;
  recordPid: string;
  token?: string;
  locale?: string;
  t?: (key: string) => string;
}

// Activity type → [icon, zh label, en label, dot color class]
const ACTIVITY_TYPE_CONFIG: Record<string, [React.ReactNode, string, string, string]> = {
  CREATE: [<PlusIcon key="c" />, 'Created', 'Created', 'bg-green-500'],
  UPDATE: [<PencilIcon key="u" />, 'Updated', 'Updated', 'bg-blue-500'],
  STATE_CHANGE: [<ArrowPathIcon key="s" />, 'State Change', 'State Change', 'bg-purple-500'],
  DELETE: [<TrashIcon key="d" />, 'Deleted', 'Deleted', 'bg-red-500'],
  NOTE: [<NoteIcon key="n" />, 'Note', 'Note', 'bg-yellow-500'],
  CALL: [<PhoneIcon key="p" />, 'Call', 'Call', 'bg-teal-500'],
  EMAIL: [<EmailIcon key="e" />, 'Email', 'Email', 'bg-indigo-500'],
  MEETING: [<MeetingIcon key="m" />, 'Meeting', 'Meeting', 'bg-orange-500'],
  SYSTEM: [<SystemIcon key="sys" />, 'System', 'System', 'bg-gray-500'],
};

export const ActivityTimeline: React.FC<ActivityTimelineProps> = ({
  modelCode,
  recordPid,
  token,
  locale = 'zh-CN',
}) => {
  const [activities, setActivities] = useState<ActivityRecord[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const loadActivities = useCallback(async () => {
    if (!modelCode || !recordPid) {
      setLoading(false);
      return;
    }

    setLoading(true);
    setError(null);
    try {
      const result = await fetchResult<ActivityRecord[]>('/api/activities', {
        method: 'get',
        params: { objectModel: modelCode, objectRecord: recordPid, limit: 50 },
        token,
      });
      if (ResultHelper.isSuccess(result) && Array.isArray(result.data)) {
        setActivities(result.data);
      } else {
        setActivities([]);
      }
    } catch (e: any) {
      if (e?.status === 403) {
        setError(locale === 'zh-CN' ? '无活动记录查看权限' : 'No permission to view activities');
      } else {
        setError(e?.message || 'Failed to load activities');
      }
    } finally {
      setLoading(false);
    }
  }, [modelCode, recordPid, token, locale]);

  useEffect(() => {
    loadActivities();
  }, [loadActivities]);

  if (loading) {
    return (
      <div
        className="flex items-center justify-center py-12 text-gray-400"
        data-testid="activity-timeline-loading"
      >
        <div className="mr-2 h-5 w-5 animate-spin rounded-full border-2 border-gray-300 border-t-blue-500" />
        {locale === 'zh-CN' ? '加载活动记录...' : 'Loading activities...'}
      </div>
    );
  }

  if (error) {
    return (
      <div className="py-8 text-center text-sm text-red-500" data-testid="activity-timeline-error">
        {error}
      </div>
    );
  }

  if (activities.length === 0) {
    return (
      <div className="py-12 text-center text-sm text-gray-400" data-testid="activity-timeline-empty">
        {locale === 'zh-CN' ? '暂无活动记录' : 'No activities yet'}
      </div>
    );
  }

  // Group activities by date
  const groups = groupByDate(activities);

  return (
    <div className="relative" data-testid="activity-timeline">
      {/* Timeline line */}
      <div className="absolute top-0 bottom-0 left-4 w-px bg-gray-200" />

      <div className="space-y-6 pl-10">
        {groups.map((group) => (
          <div key={group.date}>
            {/* Date header */}
            <div className="relative mb-3">
              <div className="absolute top-0.5 -left-10 h-3 w-3 rounded-full border-2 border-gray-300 bg-white" />
              <span className="text-xs font-medium tracking-wide text-gray-400 uppercase">
                {formatDateHeader(group.date, locale)}
              </span>
            </div>

            {/* Activity entries for this date */}
            <div className="space-y-3">
              {group.entries.map((activity) => {
                const config =
                  ACTIVITY_TYPE_CONFIG[activity.activityType] || ACTIVITY_TYPE_CONFIG.SYSTEM;
                const [icon, _zhLabel, _enLabel, dotColor] = config;

                return (
                  <div
                    key={activity.id}
                    className="relative"
                    data-testid={`activity-timeline-item-${activity.id}`}
                    data-activity-type={activity.activityType}
                  >
                    {/* Timeline dot */}
                    <div className={`absolute top-1.5 -left-10 h-3 w-3 rounded-full ${dotColor}`} />

                    <div className="rounded-lg border border-gray-100 bg-gray-50 px-4 py-3">
                      {/* Header: icon + type badge + actor + time */}
                      <div className="flex items-center gap-2 text-sm">
                        <span className="flex-shrink-0 text-gray-400">{icon}</span>
                        <ActivityTypeBadge type={activity.activityType} locale={locale} />
                        <span className="font-medium text-gray-700">
                          {activity.actorName || (activity.actorType === 'system' ? 'System' : '—')}
                        </span>
                        {activity.actorType === 'agent' && (
                          <span className="rounded bg-violet-100 px-1.5 py-0.5 text-[10px] font-medium text-violet-700">
                            AI
                          </span>
                        )}
                        <span className="text-gray-300">&middot;</span>
                        <time className="text-xs text-gray-400" title={activity.occurredAt}>
                          {formatTime(activity.occurredAt)}
                        </time>
                        {activity.commandCode && (
                          <>
                            <span className="text-gray-300">&middot;</span>
                            <span className="font-mono text-xs text-gray-400">
                              {activity.commandCode}
                            </span>
                          </>
                        )}
                      </div>

                      {/* Subject */}
                      {activity.subject && (
                        <p className="mt-1.5 text-sm text-gray-800">{activity.subject}</p>
                      )}

                      {/* Content */}
                      {activity.content && (
                        <p className="mt-1 text-xs whitespace-pre-wrap text-gray-500">
                          {activity.content}
                        </p>
                      )}

                      {/* Metadata (state transitions) */}
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
  );
};

// ============================================================================
// Sub-components
// ============================================================================

function ActivityTypeBadge({ type, locale }: { type: string; locale: string }) {
  const labels: Record<string, [string, string, string]> = {
    CREATE: ['创建', 'Created', 'bg-green-100 text-green-700'],
    UPDATE: ['更新', 'Updated', 'bg-blue-100 text-blue-700'],
    STATE_CHANGE: ['状态变更', 'State Change', 'bg-purple-100 text-purple-700'],
    DELETE: ['删除', 'Deleted', 'bg-red-100 text-red-700'],
    NOTE: ['备注', 'Note', 'bg-yellow-100 text-yellow-700'],
    CALL: ['通话', 'Call', 'bg-teal-100 text-teal-700'],
    EMAIL: ['邮件', 'Email', 'bg-indigo-100 text-indigo-700'],
    MEETING: ['会议', 'Meeting', 'bg-orange-100 text-orange-700'],
    SYSTEM: ['系统', 'System', 'bg-gray-100 text-gray-600'],
  };
  const [zh, en, cls] = labels[type] || ['—', '—', 'bg-gray-100 text-gray-600'];
  return (
    <span
      className={`inline-block flex-shrink-0 rounded px-1.5 py-0.5 text-[10px] font-medium ${cls}`}
    >
      {locale === 'zh-CN' ? zh : en}
    </span>
  );
}

function MetadataDisplay({ metadata, locale }: { metadata: string; locale: string }) {
  try {
    const parsed = JSON.parse(metadata);
    // Display state transitions
    if (parsed.fromState && parsed.toState) {
      return (
        <div className="mt-1.5 flex items-center gap-1.5 text-xs">
          <span className="rounded bg-gray-100 px-1.5 py-0.5 text-gray-600">
            {parsed.fromState}
          </span>
          <svg
            className="h-3 w-3 flex-shrink-0 text-gray-400"
            fill="none"
            stroke="currentColor"
            strokeWidth={2}
            viewBox="0 0 24 24"
          >
            <path d="M5 12h14m-4-4 4 4-4 4" />
          </svg>
          <span className="rounded bg-blue-100 px-1.5 py-0.5 text-blue-700">{parsed.toState}</span>
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
        <div className="mt-1 text-xs text-gray-400">
          {locale === 'zh-CN'
            ? `${parsed.changedFields.length} 个字段变更`
            : `${parsed.changedFields.length} field(s) changed`}
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
