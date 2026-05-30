/**
 * InboxWidget — Workbench widget showing pending inbox items.
 *
 * Compact table layout with three columns:
 *  - Task: item title
 *  - Type: colored badge keyed by itemType
 *  - Due:  relative time derived from createdAt
 *
 * Filter pills are rendered as underline tabs.
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

const TYPE_BADGE: Record<string, string> = {
  approval: 'bg-amber-100 text-amber-800',
  task: 'bg-blue-100 text-blue-800',
  alert: 'bg-red-100 text-red-800',
  mention: 'bg-violet-100 text-violet-800',
  assignment: 'bg-green-100 text-green-800',
  ai_suggestion: 'bg-indigo-100 text-indigo-800',
};

const FILTER_PILLS = [
  { key: null as string | null, labelKey: 'workbench.inbox.all' },
  { key: 'approval', labelKey: 'workbench.inbox.approval' },
  { key: 'task', labelKey: 'workbench.inbox.task' },
  { key: 'alert', labelKey: 'workbench.inbox.alert' },
];

function formatDue(item: InboxItem): string {
  const created = item.createdAt ? new Date(item.createdAt).getTime() : 0;
  if (!created) return '—';
  const diffMs = Date.now() - created;
  const days = Math.floor(diffMs / 86_400_000);
  if (days <= 0) return 'Today';
  if (days === 1) return '1d ago';
  if (days < 7) return `${days}d ago`;
  return `${Math.floor(days / 7)}w ago`;
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
  const [, setTotal] = useState(0);
  const [activeFilter, setActiveFilter] = useState<string | null>(null);
  const [approvingId, setApprovingId] = useState<number | null>(null);

  const resolvedTitle = title ? t(title) : t('workbench.inbox.title');

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
    } else if (item.sourceModel ?? item.modelCode) {
      const modelCode = item.sourceModel ?? item.modelCode;
      const recordPid =
        item.sourceRecordPid ??
        item.sourceRecordId ??
        (item.recordId != null ? String(item.recordId) : undefined);
      if (recordPid) {
        window.location.href = `/p/${modelCode}/view/${recordPid}`;
      }
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

  return (
    <div
      className={`rounded-[10px] bg-white border border-[#e3e8ee] dark:bg-gray-900 dark:border-gray-700 overflow-hidden ${className}`}
    >
      <div className="flex items-center justify-between px-5 py-4 border-b border-[#f0f3f7] dark:border-gray-700">
        <h2 className="text-[14px] font-semibold text-gray-900 dark:text-gray-100">
          {resolvedTitle}
        </h2>
        <a href="/inbox" className="text-[13px] text-[#635bff]">
          {t('workbench.inbox.viewAll')} &rarr;
        </a>
      </div>
      <div className="flex gap-6 px-5 border-b border-[#f0f3f7] dark:border-gray-700">
        {FILTER_PILLS.map((pill) => {
          const isActive = activeFilter === pill.key;
          return (
            <button
              key={pill.key ?? '__all'}
              type="button"
              onClick={() => setActiveFilter(pill.key)}
              className={`py-3 text-[13px] font-medium border-b-2 transition-colors ${
                isActive
                  ? 'border-[#635bff] text-[#635bff]'
                  : 'border-transparent text-gray-500 hover:text-gray-900'
              }`}
            >
              {t(pill.labelKey)}
            </button>
          );
        })}
      </div>
      <table className="w-full text-[13px]">
        <thead className="bg-[#fafbfc] dark:bg-gray-800">
          <tr>
            <th className="text-left font-semibold text-[11px] uppercase tracking-wide text-gray-500 px-5 py-3">
              {t('workbench.inbox.col.task', undefined, '待办')}
            </th>
            <th className="text-left font-semibold text-[11px] uppercase tracking-wide text-gray-500 px-5 py-3">
              {t('workbench.inbox.col.type', undefined, '类型')}
            </th>
            <th className="text-left font-semibold text-[11px] uppercase tracking-wide text-gray-500 px-5 py-3">
              {t('workbench.inbox.col.due', undefined, '截止')}
            </th>
          </tr>
        </thead>
        <tbody>
          {loading && (
            <tr>
              <td
                colSpan={3}
                className="px-5 py-6 text-center text-gray-400"
              >
                <span className="inline-block h-5 w-5 animate-spin rounded-full border-2 border-blue-500 border-t-transparent align-middle" />
              </td>
            </tr>
          )}
          {!loading && items.length === 0 && (
            <tr>
              <td
                colSpan={3}
                className="px-5 py-6 text-center text-sm text-gray-400"
              >
                {t('workbench.inbox.empty')}
              </td>
            </tr>
          )}
          {!loading &&
            items.map((item) => {
              const isApproval = item.itemType === 'approval';
              const isApproving = approvingId === item.id;
              return (
                <tr
                  key={item.id}
                  onClick={() => handleItemClick(item)}
                  className="group border-t border-[#f0f3f7] dark:border-gray-700 hover:bg-[#fafbfc] dark:hover:bg-gray-800 cursor-pointer"
                >
                  <td className="px-5 py-3 font-medium text-gray-900 dark:text-gray-100">
                    {item.title}
                  </td>
                  <td className="px-5 py-3">
                    <span
                      data-testid="inbox-type-badge"
                      className={`inline-block px-2 py-0.5 rounded text-[11px] font-medium ${
                        TYPE_BADGE[item.itemType] ?? 'bg-gray-100 text-gray-700'
                      }`}
                    >
                      {t(`workbench.inbox.${item.itemType}`)}
                    </span>
                  </td>
                  <td className="px-5 py-3 text-gray-500">
                    <div className="flex items-center justify-between gap-3">
                      <span>{formatDue(item)}</span>
                      {isApproval && (
                        <button
                          type="button"
                          data-testid={`inbox-approve-${item.id}`}
                          onClick={(e) => handleApprove(e, item)}
                          disabled={isApproving}
                          className="opacity-0 group-hover:opacity-100 focus:opacity-100 transition-opacity rounded border border-[#e3e8ee] bg-white px-2 py-1 text-[12px] font-medium text-[#635bff] hover:bg-[#f4f3ff] hover:border-[#635bff] disabled:opacity-50 disabled:cursor-not-allowed dark:bg-gray-900 dark:border-gray-700"
                        >
                          {isApproving
                            ? t('workbench.inbox.approving', undefined, '处理中…')
                            : `✓ ${t('workbench.inbox.approve', undefined, '通过')}`}
                        </button>
                      )}
                    </div>
                  </td>
                </tr>
              );
            })}
        </tbody>
      </table>
    </div>
  );
}
