/**
 * InboxWidget — Workbench widget showing pending inbox items.
 *
 * Features:
 * - Category filter pills (all / approval / task / alert)
 * - Urgent item highlighting with left color bar
 * - Type icons in colored containers
 * - Quick approve action on urgent approval items
 * - Human-readable subtitles with source and relative time
 *
 * Data source: /api/inbox (shared by web and mobile).
 */

import React, { useEffect, useState, useCallback } from 'react';
import {
  listInboxItems,
  submitApprovalAction,
  type InboxItem,
} from '~/shared/services/inboxService';
import { useI18n } from '~/contexts/I18nContext';

interface InboxWidgetProps {
  title?: string;
  maxItems?: number;
  itemTypes?: string;
  className?: string;
}

const TYPE_ICONS: Record<string, string> = {
  approval: '\uD83D\uDCCB',
  task: '\u2705',
  mention: '\uD83D\uDCAC',
  alert: '\uD83D\uDD14',
  assignment: '\uD83D\uDC64',
  ai_suggestion: '\uD83E\uDD16',
};

const TYPE_ICON_BG: Record<string, string> = {
  approval: 'bg-red-50',
  task: 'bg-blue-50',
  alert: 'bg-amber-50',
  mention: 'bg-violet-50',
  assignment: 'bg-green-50',
  ai_suggestion: 'bg-indigo-50',
};

const FILTER_PILLS = [
  { key: null as string | null, labelKey: 'workbench.inbox.all' },
  { key: 'approval', labelKey: 'workbench.inbox.approval' },
  { key: 'task', labelKey: 'workbench.inbox.task' },
  { key: 'alert', labelKey: 'workbench.inbox.alert' },
];

function isUrgent(item: InboxItem): boolean {
  return item.priority === 'urgent' || item.priority === 'high';
}

export function InboxWidget({
  title,
  maxItems = 8,
  itemTypes,
  className = '',
}: InboxWidgetProps) {
  const { t } = useI18n();
  const [items, setItems] = useState<InboxItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [total, setTotal] = useState(0);
  const [activeFilter, setActiveFilter] = useState<string | null>(null);
  const [approvingId, setApprovingId] = useState<number | null>(null);

  const resolvedTitle = title
    ? t(title)
    : t('workbench.inbox.title');

  const loadItems = useCallback(async () => {
    setLoading(true);
    try {
      const effectiveType = activeFilter || itemTypes;
      const result = await listInboxItems({
        status: 'pending',
        itemType: effectiveType,
        pageNum: 1,
        pageSize: maxItems,
      });
      setItems(result.records);
      setTotal(result.total);
    } catch {
      setItems([]);
    } finally {
      setLoading(false);
    }
  }, [maxItems, itemTypes, activeFilter]);

  useEffect(() => {
    loadItems();
  }, [loadItems]);

  const handleItemClick = (item: InboxItem) => {
    if (item.deepLink) {
      window.location.href = item.deepLink;
    } else if (item.modelCode && item.recordId) {
      window.location.href = `/${item.modelCode}/${item.recordId}`;
    }
  };

  const handleApprove = async (e: React.MouseEvent, item: InboxItem) => {
    e.stopPropagation();
    setApprovingId(item.id);
    try {
      await submitApprovalAction(item.id, 'approve');
      await loadItems();
    } finally {
      setApprovingId(null);
    }
  };

  const handleView = (e: React.MouseEvent, item: InboxItem) => {
    e.stopPropagation();
    handleItemClick(item);
  };

  const formatTime = (dateStr: string) => {
    const date = new Date(dateStr);
    const now = new Date();
    const diff = now.getTime() - date.getTime();
    const hours = Math.floor(diff / 3600000);
    if (hours < 1) return t('workbench.inbox.justNow');
    if (hours < 24) return t('workbench.inbox.hoursAgo', { hours });
    const days = Math.floor(hours / 24);
    if (days < 7) return t('workbench.inbox.daysAgo', { days });
    return date.toLocaleDateString();
  };

  const urgentCount = items.filter(isUrgent).length;

  const handleFilterChange = (key: string | null) => {
    setActiveFilter(key);
  };

  // --- Header ---
  const renderHeader = () => (
    <div className="mb-3 flex items-center justify-between gap-2 px-3 pt-3">
      <div className="flex items-center gap-2">
        <span className="text-sm font-semibold text-gray-900">{resolvedTitle}</span>
        {urgentCount > 0 && (
          <span className="rounded-full bg-red-100 px-2 py-0.5 text-[10px] font-semibold text-red-600">
            {urgentCount} {t('workbench.inbox.urgent')}
          </span>
        )}
      </div>
      <div className="flex items-center gap-1">
        {total > maxItems && (
          <a
            href="/inbox"
            className="whitespace-nowrap text-[11px] text-blue-500 hover:text-blue-600"
          >
            {t('workbench.inbox.viewAll')} &rarr;
          </a>
        )}
      </div>
    </div>
  );

  // --- Filter pills ---
  const renderFilters = () => (
    <div className="mb-3 flex items-center gap-1 px-3">
      {FILTER_PILLS.map((pill) => {
        const isActive = activeFilter === pill.key;
        return (
          <button
            key={pill.key ?? '__all'}
            type="button"
            onClick={() => handleFilterChange(pill.key)}
            className={`cursor-pointer rounded-full px-3 py-1 text-[11px] transition-colors ${
              isActive
                ? 'bg-blue-50 font-medium text-blue-600'
                : 'text-gray-500 hover:bg-gray-50'
            }`}
          >
            {t(pill.labelKey)}
          </button>
        );
      })}
    </div>
  );

  // --- Loading state ---
  if (loading) {
    return (
      <div className={`flex h-full flex-col ${className}`}>
        {renderHeader()}
        {renderFilters()}
        <div className="flex flex-1 items-center justify-center">
          <div className="h-5 w-5 animate-spin rounded-full border-2 border-blue-500 border-t-transparent" />
        </div>
      </div>
    );
  }

  // --- Empty state ---
  if (items.length === 0) {
    return (
      <div className={`flex h-full flex-col ${className}`}>
        {renderHeader()}
        {renderFilters()}
        <div className="flex flex-1 flex-col items-center justify-center px-3 pb-3 text-gray-400">
          <span className="mb-1 text-2xl">{'\uD83C\uDF89'}</span>
          <span className="text-sm">
            {t('workbench.inbox.empty')}
          </span>
        </div>
      </div>
    );
  }

  // --- Item list ---
  return (
    <div className={`flex h-full flex-col ${className}`}>
      {renderHeader()}
      {renderFilters()}
      <div className="flex-1 space-y-2 overflow-y-auto px-3 pb-3">
        {items.map((item) => {
          const urgent = isUrgent(item);
          const iconBg = TYPE_ICON_BG[item.itemType] || 'bg-gray-100';
          const icon = TYPE_ICONS[item.itemType] || '\uD83D\uDCCC';
          const showActions = urgent && item.itemType === 'approval';
          const isApproving = approvingId === item.id;

          const subtitle = [
            item.sourceType,
            formatTime(item.createdAt),
          ]
            .filter(Boolean)
            .join(' \u00B7 ');

          return (
            <button
              key={item.id}
              type="button"
              onClick={() => handleItemClick(item)}
              className={`flex w-full items-center gap-3 rounded-lg border p-3 text-left transition-colors ${
                urgent
                  ? 'border-red-200 bg-red-50 hover:border-red-300 hover:bg-red-100/50'
                  : 'border-gray-100 bg-white hover:border-blue-200 hover:bg-blue-50/30'
              } cursor-pointer`}
            >
              {/* Icon container */}
              <div
                className={`flex h-9 w-9 flex-shrink-0 items-center justify-center rounded-[10px] text-base ${iconBg}`}
              >
                {icon}
              </div>

              {/* Content */}
              <div className="min-w-0 flex-1">
                <div className="flex items-center gap-2">
                  <span className="truncate text-[13px] font-medium text-gray-900">
                    {item.title}
                  </span>
                  {urgent && (
                    <span className="shrink-0 rounded bg-red-100 px-1.5 py-0.5 text-[9px] font-semibold text-red-600">
                      {item.priority}
                    </span>
                  )}
                </div>
                <p className="mt-0.5 truncate text-[11px] text-gray-400">{subtitle}</p>
              </div>

              {/* Quick actions for urgent approvals */}
              {showActions && (
                <div className="flex shrink-0 items-center gap-1.5">
                  <button
                    type="button"
                    onClick={(e) => handleApprove(e, item)}
                    disabled={isApproving}
                    className="rounded-md bg-green-500 px-3 py-1 text-[11px] text-white hover:bg-green-600 disabled:opacity-50"
                  >
                    {isApproving
                      ? '...'
                      : t('workbench.inbox.approve')}
                  </button>
                  <button
                    type="button"
                    onClick={(e) => handleView(e, item)}
                    className="rounded-md border border-gray-200 bg-white px-3 py-1 text-[11px] text-gray-600 hover:bg-gray-50"
                  >
                    {t('workbench.inbox.view')}
                  </button>
                </div>
              )}
            </button>
          );
        })}
      </div>
    </div>
  );
}
