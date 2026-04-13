/**
 * UnifiedInboxPage — full inbox page with tabs, card list, and BpmTaskDrawer integration.
 *
 * Tabs: All | Approval | Alert | Assignment
 * Data source: /api/inbox via inboxService
 * Approval items open BpmTaskDrawer for approve/reject actions.
 */

import { useState, useEffect, useCallback } from 'react';
import { useSearchParams } from 'react-router';
import {
  InboxIcon,
  CheckCircleIcon,
  ExclamationTriangleIcon,
  ArrowRightIcon,
  BellIcon,
  FunnelIcon,
  EnvelopeOpenIcon,
} from '@heroicons/react/24/outline';
import {
  listInboxItems,
  markRead,
  markAllRead as markAllReadApi,
  dismissItem,
  type InboxItem,
  type InboxPage,
} from '~/services/inboxService';
import { BpmTaskDrawer } from '~/bpm/components/BpmTaskDrawer';

const TABS = [
  { key: '', label: 'All', icon: InboxIcon },
  { key: 'approval', label: 'Approval', icon: CheckCircleIcon },
  { key: 'alert', label: 'Alert', icon: ExclamationTriangleIcon },
  { key: 'assignment', label: 'Assignment', icon: ArrowRightIcon },
] as const;

const STATUS_FILTERS = [
  { key: 'pending', label: 'Pending' },
  { key: '', label: 'All' },
  { key: 'acted', label: 'Acted' },
  { key: 'closed', label: 'Closed' },
] as const;

function timeAgo(dateStr: string): string {
  const now = Date.now();
  const diff = now - new Date(dateStr).getTime();
  const minutes = Math.floor(diff / 60000);
  if (minutes < 1) return 'just now';
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h ago`;
  const days = Math.floor(hours / 24);
  if (days < 30) return `${days}d ago`;
  return new Date(dateStr).toLocaleDateString();
}

function priorityBadge(priority: string) {
  const styles: Record<string, string> = {
    urgent: 'bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-400',
    high: 'bg-orange-100 text-orange-700 dark:bg-orange-900/30 dark:text-orange-400',
    normal: 'bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-400',
    low: 'bg-gray-100 text-gray-600 dark:bg-gray-700 dark:text-gray-400',
  };
  return (
    <span
      className={`rounded-full px-2 py-0.5 text-xs font-medium ${styles[priority] || styles.normal}`}
    >
      {priority}
    </span>
  );
}

function statusBadge(status: string) {
  const styles: Record<string, string> = {
    pending: 'bg-yellow-100 text-yellow-700 dark:bg-yellow-900/30 dark:text-yellow-400',
    acted: 'bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-400',
    dismissed: 'bg-gray-100 text-gray-600 dark:bg-gray-700 dark:text-gray-400',
    closed: 'bg-gray-100 text-gray-600 dark:bg-gray-700 dark:text-gray-400',
    expired: 'bg-red-100 text-red-600 dark:bg-red-900/30 dark:text-red-400',
  };
  return (
    <span
      className={`rounded-full px-2 py-0.5 text-xs font-medium ${styles[status] || styles.pending}`}
    >
      {status}
    </span>
  );
}

function itemTypeIcon(type: string) {
  switch (type) {
    case 'approval':
      return <CheckCircleIcon className="h-5 w-5 text-blue-500" />;
    case 'alert':
      return <ExclamationTriangleIcon className="h-5 w-5 text-amber-500" />;
    case 'assignment':
      return <ArrowRightIcon className="h-5 w-5 text-green-500" />;
    default:
      return <BellIcon className="h-5 w-5 text-gray-400" />;
  }
}

export default function UnifiedInboxPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const [activeTab, setActiveTab] = useState(searchParams.get('type') || '');
  const [statusFilter, setStatusFilter] = useState('pending');
  const [page, setPage] = useState<InboxPage | null>(null);
  const [loading, setLoading] = useState(true);
  const [currentPage, setCurrentPage] = useState(1);

  // BpmTaskDrawer state
  const [drawerTaskId, setDrawerTaskId] = useState<string | null>(searchParams.get('task') || null);

  const fetchData = useCallback(async () => {
    setLoading(true);
    try {
      const result = await listInboxItems({
        itemType: activeTab || undefined,
        status: statusFilter || undefined,
        pageNum: currentPage,
        pageSize: 20,
      });
      setPage(result);
    } catch (err) {
      console.error('Failed to load inbox:', err);
    } finally {
      setLoading(false);
    }
  }, [activeTab, statusFilter, currentPage]);

  useEffect(() => {
    fetchData();
  }, [fetchData]);

  const handleTabChange = (tab: string) => {
    setActiveTab(tab);
    setCurrentPage(1);
    if (tab) {
      setSearchParams({ type: tab });
    } else {
      setSearchParams({});
    }
  };

  const handleItemClick = async (item: InboxItem) => {
    // Mark as read
    if (!item.isRead) {
      try {
        await markRead(item.id);
        setPage((prev) => {
          if (!prev) return prev;
          return {
            ...prev,
            records: prev.records.map((r) => (r.id === item.id ? { ...r, isRead: true } : r)),
          };
        });
      } catch {
        // Non-critical
      }
    }

    // Open BpmTaskDrawer for approval items
    if (item.itemType === 'approval' && item.sourceId) {
      setDrawerTaskId(item.sourceId);
    }
  };

  const handleDismiss = async (e: React.MouseEvent, item: InboxItem) => {
    e.stopPropagation();
    try {
      await dismissItem(item.id);
      setPage((prev) => {
        if (!prev) return prev;
        return {
          ...prev,
          records: prev.records.filter((r) => r.id !== item.id),
          total: prev.total - 1,
        };
      });
    } catch {
      // Non-critical
    }
  };

  const handleMarkAllRead = async () => {
    try {
      await markAllReadApi();
      setPage((prev) => {
        if (!prev) return prev;
        return {
          ...prev,
          records: prev.records.map((r) => ({ ...r, isRead: true })),
        };
      });
    } catch {
      // Non-critical
    }
  };

  const handleDrawerComplete = () => {
    setDrawerTaskId(null);
    fetchData(); // Refresh after approval action
  };

  const items = page?.records || [];
  const total = page?.total || 0;
  const totalPages = page?.pages || 0;

  return (
    <div className="mx-auto max-w-4xl px-4 py-6" data-testid="unified-inbox-page">
      {/* Page Header */}
      <div className="mb-6 flex items-center justify-between">
        <div className="flex items-center gap-3">
          <InboxIcon className="h-7 w-7 text-gray-700 dark:text-gray-300" />
          <div>
            <h1 className="text-xl font-semibold text-gray-900 dark:text-white">Inbox</h1>
            <p className="text-sm text-gray-500 dark:text-gray-400">{total} items</p>
          </div>
        </div>
        <button
          onClick={handleMarkAllRead}
          className="flex items-center gap-1.5 rounded-lg border border-gray-200 px-3 py-2 text-sm text-gray-600 transition-colors hover:bg-gray-50 dark:border-gray-600 dark:text-gray-300 dark:hover:bg-gray-700"
          data-testid="inbox-mark-all-read"
        >
          <EnvelopeOpenIcon className="h-4 w-4" />
          Mark all read
        </button>
      </div>

      {/* Tabs */}
      <div className="mb-4 flex items-center gap-1 rounded-lg border border-gray-200 bg-gray-50 p-1 dark:border-gray-700 dark:bg-gray-800">
        {TABS.map((tab) => {
          const Icon = tab.icon;
          const isActive = activeTab === tab.key;
          return (
            <button
              key={tab.key}
              onClick={() => handleTabChange(tab.key)}
              data-testid={`inbox-tab-${tab.key || 'all'}`}
              className={`flex items-center gap-1.5 rounded-md px-3 py-2 text-sm font-medium transition-all ${
                isActive
                  ? 'bg-white text-blue-600 shadow-sm dark:bg-gray-700 dark:text-blue-400'
                  : 'text-gray-500 hover:text-gray-700 dark:text-gray-400 dark:hover:text-gray-200'
              }`}
            >
              <Icon className="h-4 w-4" />
              {tab.label}
            </button>
          );
        })}

        {/* Status filter */}
        <div className="ml-auto flex items-center gap-1">
          <FunnelIcon className="h-4 w-4 text-gray-400" />
          {STATUS_FILTERS.map((sf) => (
            <button
              key={sf.key}
              onClick={() => {
                setStatusFilter(sf.key);
                setCurrentPage(1);
              }}
              data-testid={`inbox-status-${sf.key || 'all'}`}
              className={`rounded px-2 py-1 text-xs transition-colors ${
                statusFilter === sf.key
                  ? 'bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-400'
                  : 'text-gray-500 hover:bg-gray-200 dark:text-gray-400 dark:hover:bg-gray-600'
              }`}
            >
              {sf.label}
            </button>
          ))}
        </div>
      </div>

      {/* Item List */}
      <div className="space-y-2">
        {loading && items.length === 0 ? (
          <div className="flex items-center justify-center py-16">
            <div className="h-6 w-6 animate-spin rounded-full border-2 border-blue-500 border-t-transparent" />
          </div>
        ) : items.length === 0 ? (
          <div
            className="flex flex-col items-center justify-center rounded-xl border border-dashed border-gray-300 py-16 dark:border-gray-600"
            data-testid="inbox-empty-state"
          >
            <InboxIcon className="mb-3 h-10 w-10 text-gray-300 dark:text-gray-600" />
            <p className="text-sm text-gray-500 dark:text-gray-400">No items to show</p>
          </div>
        ) : (
          items.map((item) => (
            <button
              key={item.id}
              onClick={() => handleItemClick(item)}
              data-testid={`inbox-item-${item.id}`}
              className={`flex w-full items-start gap-4 rounded-xl border p-4 text-start transition-all hover:shadow-md ${
                !item.isRead
                  ? 'border-blue-200 bg-blue-50/50 dark:border-blue-800/50 dark:bg-blue-900/10'
                  : 'border-gray-200 bg-white dark:border-gray-700 dark:bg-gray-800'
              }`}
            >
              <div className="mt-0.5 flex-shrink-0">{itemTypeIcon(item.itemType)}</div>
              <div className="min-w-0 flex-1">
                <div className="flex items-start justify-between gap-2">
                  <div className="min-w-0">
                    <h3
                      className={`text-sm ${!item.isRead ? 'font-semibold' : 'font-medium'} text-gray-900 dark:text-white`}
                    >
                      {item.title}
                    </h3>
                    {item.subtitle && (
                      <p className="mt-0.5 text-xs text-gray-500 dark:text-gray-400">
                        {item.subtitle}
                      </p>
                    )}
                  </div>
                  <span className="flex-shrink-0 text-xs text-gray-400">
                    {timeAgo(item.createdAt)}
                  </span>
                </div>
                <div className="mt-2 flex items-center gap-2">
                  {priorityBadge(item.priority)}
                  {statusBadge(item.status)}
                  {item.actionTaken && (
                    <span className="text-xs text-gray-400">{item.actionTaken}</span>
                  )}
                </div>
              </div>
              {item.status === 'pending' && (
                <button
                  onClick={(e) => handleDismiss(e, item)}
                  className="flex-shrink-0 rounded-lg p-1.5 text-gray-400 transition-colors hover:bg-gray-100 hover:text-gray-600 dark:hover:bg-gray-700"
                  title="Dismiss"
                  data-testid={`inbox-dismiss-${item.id}`}
                >
                  <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                    <path
                      strokeLinecap="round"
                      strokeLinejoin="round"
                      strokeWidth={2}
                      d="M6 18L18 6M6 6l12 12"
                    />
                  </svg>
                </button>
              )}
            </button>
          ))
        )}
      </div>

      {/* Pagination */}
      {totalPages > 1 && (
        <div className="mt-4 flex items-center justify-between">
          <p className="text-sm text-gray-500 dark:text-gray-400">
            Page {currentPage} of {totalPages} ({total} items)
          </p>
          <div className="flex gap-2">
            <button
              onClick={() => setCurrentPage((p) => Math.max(1, p - 1))}
              disabled={currentPage <= 1}
              className="rounded-lg border border-gray-200 px-3 py-1.5 text-sm transition-colors hover:bg-gray-50 disabled:opacity-50 dark:border-gray-600 dark:hover:bg-gray-700"
            >
              Previous
            </button>
            <button
              onClick={() => setCurrentPage((p) => Math.min(totalPages, p + 1))}
              disabled={currentPage >= totalPages}
              className="rounded-lg border border-gray-200 px-3 py-1.5 text-sm transition-colors hover:bg-gray-50 disabled:opacity-50 dark:border-gray-600 dark:hover:bg-gray-700"
            >
              Next
            </button>
          </div>
        </div>
      )}

      {/* BPM Task Drawer */}
      <BpmTaskDrawer
        taskId={drawerTaskId || ''}
        open={!!drawerTaskId}
        onClose={() => setDrawerTaskId(null)}
        onComplete={handleDrawerComplete}
      />
    </div>
  );
}
