/**
 * ActivitiesWidget — Timeline of recent CRM activities.
 *
 * Data source: GET /crm_activity/list (dynamic controller)
 * Sorted by created_at desc, top N items.
 */

import React, { useEffect, useState } from 'react';
import { get } from '~/shared/services/http-client';
import { useI18n } from '~/contexts/I18nContext';

interface ActivityRecord {
  id: string;
  crm_activity_type?: string;
  crm_activity_subject?: string;
  crm_activity_related_model?: string;
  crm_activity_related_id?: string;
  crm_activity_related_name?: string;
  created_at?: string;
}

interface ActivityListResponse {
  records: ActivityRecord[];
  total: number;
}

interface ActivitiesWidgetProps {
  title?: string;
  maxItems?: number;
  className?: string;
}

const TYPE_ICONS: Record<string, string> = {
  call: '\uD83D\uDCDE',
  meeting: '\uD83E\uDD1D',
  email: '\uD83D\uDCE7',
  note: '\uD83D\uDCDD',
};

const TYPE_COLORS: Record<string, string> = {
  call: 'bg-blue-100 text-blue-600',
  meeting: 'bg-violet-100 text-violet-600',
  email: 'bg-amber-100 text-amber-600',
  note: 'bg-gray-100 text-gray-600',
};

function formatRelativeTime(dateStr: string, t: (key: string, params?: Record<string, unknown>, fallback?: string) => string): string {
  const date = new Date(dateStr);
  const now = new Date();
  const diff = now.getTime() - date.getTime();
  const minutes = Math.floor(diff / 60000);
  if (minutes < 1) return t('workbench.activities.justNow', {}, 'Just now');
  if (minutes < 60) return t('workbench.activities.minutesAgo', { minutes }, `${minutes}m ago`);
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return t('workbench.activities.hoursAgo', { hours }, `${hours}h ago`);
  const days = Math.floor(hours / 24);
  if (days < 7) return t('workbench.activities.daysAgo', { days }, `${days}d ago`);
  return date.toLocaleDateString();
}

export function ActivitiesWidget({ title, maxItems = 6, className = '' }: ActivitiesWidgetProps) {
  const { t } = useI18n();
  const [activities, setActivities] = useState<ActivityRecord[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);

  const resolvedTitle = title || t('workbench.activities.title', {}, 'Recent Activities');

  useEffect(() => {
    let cancelled = false;
    (async () => {
      setLoading(true);
      try {
        const result = await get<ActivityListResponse>(
          `/crm_activity/list?pageNum=1&pageSize=${maxItems}&sortField=created_at&sortOrder=desc`,
        );
        if (!cancelled && result.code === '0' && result.data) {
          setActivities(result.data.records || []);
        } else if (!cancelled) {
          setError(true);
        }
      } catch {
        if (!cancelled) setError(true);
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => { cancelled = true; };
  }, [maxItems]);

  const handleActivityClick = (activity: ActivityRecord) => {
    if (activity.crm_activity_related_model && activity.crm_activity_related_id) {
      window.location.href = `/${activity.crm_activity_related_model}/${activity.crm_activity_related_id}`;
    }
  };

  // --- Loading ---
  if (loading) {
    return (
      <div className={`flex h-full flex-col ${className}`} data-testid="activities-skeleton">
        <div className="mb-3 flex items-center justify-between px-1">
          <div className="h-4 w-32 animate-pulse rounded bg-gray-100" />
        </div>
        <div className="flex-1 space-y-3 pl-4">
          {Array.from({ length: maxItems }, (_, i) => (
            <div key={i} className="flex items-start gap-3">
              <div className="h-7 w-7 animate-pulse rounded-full bg-gray-100" />
              <div className="flex-1 space-y-1.5">
                <div className="h-3.5 w-40 animate-pulse rounded bg-gray-100" />
                <div className="h-2.5 w-24 animate-pulse rounded bg-gray-100" />
              </div>
            </div>
          ))}
        </div>
      </div>
    );
  }

  // --- Empty / Error ---
  if (error || activities.length === 0) {
    return (
      <div className={`flex h-full flex-col ${className}`} data-testid="activities-empty">
        <div className="mb-3 flex items-center justify-between px-1">
          <span className="text-sm font-semibold text-gray-900">{resolvedTitle}</span>
        </div>
        <div className="flex flex-1 flex-col items-center justify-center text-gray-400">
          <span className="mb-1 text-2xl">{'\uD83D\uDCCB'}</span>
          <span className="text-sm">
            {t('workbench.activities.empty', {}, 'No recent activities')}
          </span>
        </div>
      </div>
    );
  }

  // --- Timeline ---
  return (
    <div className={`flex h-full flex-col ${className}`} data-testid="activities-widget">
      {/* Header */}
      <div className="mb-3 flex items-center justify-between px-1">
        <span className="text-sm font-semibold text-gray-900">{resolvedTitle}</span>
      </div>

      {/* Timeline list */}
      <div className="relative flex-1 overflow-y-auto">
        {/* Vertical line */}
        <div className="absolute bottom-0 left-[17px] top-0 w-px bg-gray-200" />

        <div className="space-y-3">
          {activities.map((activity) => {
            const actType = activity.crm_activity_type || 'note';
            const icon = TYPE_ICONS[actType] || '\uD83D\uDCCC';
            const colorClass = TYPE_COLORS[actType] || TYPE_COLORS.note;
            const timeStr = activity.created_at
              ? formatRelativeTime(activity.created_at, t)
              : '';
            const isClickable = !!(activity.crm_activity_related_model && activity.crm_activity_related_id);

            return (
              <button
                key={activity.id}
                type="button"
                onClick={() => handleActivityClick(activity)}
                disabled={!isClickable}
                className={`relative flex w-full items-start gap-3 pl-1 text-left ${
                  isClickable
                    ? 'cursor-pointer rounded-md transition-colors hover:bg-gray-50'
                    : 'cursor-default'
                }`}
              >
                {/* Circle icon */}
                <div
                  className={`relative z-10 flex h-[34px] w-[34px] flex-shrink-0 items-center justify-center rounded-full text-sm ${colorClass}`}
                >
                  {icon}
                </div>

                {/* Content */}
                <div className="min-w-0 flex-1 pt-1">
                  <div className="text-[13px] text-gray-900">
                    <span className="font-medium">
                      {activity.crm_activity_subject || t(`workbench.activities.type.${actType}`, {}, actType)}
                    </span>
                    {activity.crm_activity_related_name && (
                      <span className="text-gray-500">
                        {' '}&middot; {activity.crm_activity_related_name}
                      </span>
                    )}
                  </div>
                  {timeStr && (
                    <div className="mt-0.5 text-[11px] text-gray-400">{timeStr}</div>
                  )}
                </div>
              </button>
            );
          })}
        </div>
      </div>
    </div>
  );
}
