/**
 * AnnouncementWidget — Card list of active announcements.
 *
 * Features:
 * - Fetches from GET /api/announcements
 * - Each card: title + truncated content (2 lines) + published time + author
 * - Pinned items: left accent border + "Pinned" badge
 * - Empty state with guidance text
 *
 * @since 6.5.0
 */

import React, { useCallback, useEffect, useState } from 'react';
import { get } from '~/shared/services/http-client';
import { useI18n } from '~/contexts/I18nContext';

interface AnnouncementItem {
  id: number;
  title: string;
  content: string | null;
  priority: string;
  pinned: boolean;
  publishedByName: string | null;
  publishedAt: string | null;
}

interface AnnouncementWidgetProps {
  maxItems?: number;
  className?: string;
}

function formatTime(dateStr: string | null): string {
  if (!dateStr) return '';
  const date = new Date(dateStr);
  const now = new Date();
  const diff = now.getTime() - date.getTime();
  const hours = Math.floor(diff / 3600000);
  if (hours < 1) return 'Just now';
  if (hours < 24) return `${hours}h ago`;
  const days = Math.floor(hours / 24);
  if (days < 7) return `${days}d ago`;
  return date.toLocaleDateString();
}

export function AnnouncementWidget({ maxItems = 10, className = '' }: AnnouncementWidgetProps) {
  const { t } = useI18n();
  const [items, setItems] = useState<AnnouncementItem[]>([]);
  const [loading, setLoading] = useState(true);

  const fetchAnnouncements = useCallback(async () => {
    setLoading(true);
    const result = await get<AnnouncementItem[]>('/api/announcements', {
      limit: String(maxItems),
    });
    if (result.code === '0' && result.data) {
      setItems(result.data);
    }
    setLoading(false);
  }, [maxItems]);

  useEffect(() => {
    fetchAnnouncements();
  }, [fetchAnnouncements]);

  const renderHeader = () => (
    <div className="mb-3 flex items-center justify-between">
      <span className="text-sm font-semibold text-gray-900">
        {t('workbench.announcement.title', {}, 'Announcements')}
      </span>
      {items.length > 0 && (
        <span className="rounded-full bg-blue-50 px-2 py-0.5 text-[10px] font-medium text-blue-600">
          {items.length}
        </span>
      )}
    </div>
  );

  if (loading) {
    return (
      <div className={`flex h-full flex-col ${className}`} data-testid="announcement-widget">
        {renderHeader()}
        <div className="flex flex-1 items-center justify-center">
          <div className="h-5 w-5 animate-spin rounded-full border-2 border-blue-500 border-t-transparent" />
        </div>
      </div>
    );
  }

  if (items.length === 0) {
    return (
      <div className={`flex h-full flex-col ${className}`} data-testid="announcement-widget">
        {renderHeader()}
        <div className="flex flex-1 flex-col items-center justify-center text-gray-400">
          <span className="mb-1 text-2xl">{'\uD83D\uDCE2'}</span>
          <span className="text-sm">
            {t('workbench.announcement.empty', {}, 'No announcements')}
          </span>
        </div>
      </div>
    );
  }

  return (
    <div className={`flex h-full flex-col ${className}`} data-testid="announcement-widget">
      {renderHeader()}
      <div className="flex-1 space-y-2 overflow-y-auto">
        {items.map((item) => (
          <div
            key={item.id}
            className={`rounded-lg border p-3 ${
              item.pinned
                ? 'border-l-4 border-blue-500 border-t-gray-100 border-r-gray-100 border-b-gray-100 bg-blue-50/30'
                : 'border-gray-100 bg-white'
            }`}
          >
            <div className="flex items-start justify-between gap-2">
              <div className="min-w-0 flex-1">
                <div className="flex items-center gap-2">
                  <span className="truncate text-[13px] font-medium text-gray-900">
                    {item.title}
                  </span>
                  {item.pinned && (
                    <span className="shrink-0 rounded bg-blue-100 px-1.5 py-0.5 text-[9px] font-semibold text-blue-600">
                      {t('workbench.announcement.pinned', {}, 'Pinned')}
                    </span>
                  )}
                  {item.priority === 'urgent' && (
                    <span className="shrink-0 rounded bg-red-100 px-1.5 py-0.5 text-[9px] font-semibold text-red-600">
                      {t('workbench.announcement.urgent', {}, 'Urgent')}
                    </span>
                  )}
                </div>
                {item.content && (
                  <p className="mt-1 line-clamp-2 text-[12px] leading-relaxed text-gray-500">
                    {item.content}
                  </p>
                )}
                <div className="mt-1.5 flex items-center gap-2 text-[10px] text-gray-400">
                  {item.publishedByName && <span>{item.publishedByName}</span>}
                  {item.publishedAt && (
                    <>
                      {item.publishedByName && <span>{'\u00B7'}</span>}
                      <span>{formatTime(item.publishedAt)}</span>
                    </>
                  )}
                </div>
              </div>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}
