/**
 * UnifiedInboxPage — inbox workbench with clearer queue semantics and wider layout.
 */

import { useCallback, useEffect, useMemo, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router';
import {
  ArrowPathIcon,
  ArrowRightIcon,
  BellIcon,
  CheckCircleIcon,
  ChevronLeftIcon,
  ChevronRightIcon,
  EnvelopeOpenIcon,
  ExclamationTriangleIcon,
  InboxIcon,
  XMarkIcon,
} from '@heroicons/react/24/outline';
import { useToastContext } from '~/contexts/ToastContext';
import {
  dismissItem,
  getUnreadSummary,
  listInboxItems,
  markAllRead as markAllReadApi,
  markRead,
  type InboxItem,
  type InboxPage,
  type UnreadSummary,
} from '~/shared/services/inboxService';
import { BpmTaskDrawer } from '~/plugins/core-bpm/components/BpmTaskDrawer';
import { cn } from '~/utils/cn';

const TABS = [
  { key: '', label: 'All', icon: InboxIcon },
  { key: 'approval', label: 'Approval', icon: CheckCircleIcon },
  { key: 'alert', label: 'Alert', icon: ExclamationTriangleIcon },
  { key: 'assignment', label: 'Assignment', icon: ArrowRightIcon },
] as const;

const STATUS_FILTERS = [
  { key: 'pending', label: 'Pending', description: 'Only items that still need action.' },
  { key: '', label: 'All', description: 'Everything in the selected queue.' },
  { key: 'acted', label: 'Acted', description: 'Items you already handled.' },
  { key: 'closed', label: 'Closed', description: 'Items that no longer require attention.' },
] as const;

const TYPE_COPY: Record<string, { title: string; description: string }> = {
  '': {
    title: 'All inbox items',
    description: 'Review every approval, alert, and assignment from one place.',
  },
  approval: {
    title: 'Approval queue',
    description: 'Inline workflow approvals that still need a decision.',
  },
  alert: {
    title: 'Alert queue',
    description: 'Warnings or issues that need follow-up.',
  },
  assignment: {
    title: 'Assignment queue',
    description: 'Delegated work items linked to business records.',
  },
};

type InboxCardPayload = {
  cardType?: string;
  modelCode?: string;
  recordId?: string;
  commandCode?: string;
  fromState?: string | null;
  toState?: string | null;
};

function parseCardPayload(item: InboxItem): InboxCardPayload | null {
  if (!item.cardPayload) return null;
  try {
    return JSON.parse(item.cardPayload) as InboxCardPayload;
  } catch {
    return null;
  }
}

function humanizeCode(value?: string | null): string {
  if (!value) return '';
  const namespaceParts = value.split(':');
  const raw = namespaceParts.length > 1 ? namespaceParts[namespaceParts.length - 1] : value;
  return raw
    .replace(/[-:]/g, '_')
    .split('_')
    .filter(Boolean)
    .map((segment) => segment.charAt(0).toUpperCase() + segment.slice(1).toLowerCase())
    .join(' ');
}

function modelLabel(modelCode?: string | null): string {
  const normalized = modelCode?.replace(/^[a-z0-9]{1,3}_/i, '') ?? modelCode;
  const humanized = humanizeCode(normalized);
  return humanized || 'Record';
}

function resolveWebDeepLink(item: InboxItem, payload: InboxCardPayload | null): string | null {
  if (item.itemType === 'approval' && item.sourceId) return null;
  if (item.deepLink?.startsWith('/')) return item.deepLink;
  if (item.deepLink?.startsWith('auraboot://object/')) {
    const parts = item.deepLink.replace('auraboot://object/', '').split('/');
    if (parts.length >= 2) {
      const [modelCode, recordId] = parts;
      return `/p/${modelCode}/view/${recordId}`;
    }
  }
  const modelCode = item.modelCode || payload?.modelCode;
  const recordId = item.recordId != null ? String(item.recordId) : payload?.recordId;
  return modelCode && recordId ? `/p/${modelCode}/view/${recordId}` : null;
}

function getDisplayItem(item: InboxItem) {
  const payload = parseCardPayload(item);
  const webLink = resolveWebDeepLink(item, payload);

  if (item.sourceType === 'command') {
    const commandLabel =
      humanizeCode(payload?.commandCode) || humanizeCode(item.title) || item.title;
    const fromState = payload?.fromState ? humanizeCode(payload.fromState) : '';
    const toState = payload?.toState ? humanizeCode(payload.toState) : '';
    const title =
      fromState && toState ? `${commandLabel}: ${fromState} → ${toState}` : commandLabel;
    const recordId = item.recordId != null ? String(item.recordId) : payload?.recordId;
    return {
      title,
      subtitle: recordId
        ? `${modelLabel(item.modelCode || payload?.modelCode)} #${recordId}`
        : item.subtitle,
      metaModelCode: item.modelCode || payload?.modelCode,
      metaRecordId: recordId,
      actionLabel: webLink ? 'Open' : 'View',
      actionHint: webLink ? 'Opens related record' : 'Marks item as read',
      webLink,
    };
  }

  return {
    title: item.title,
    subtitle: item.subtitle,
    metaModelCode: item.modelCode,
    metaRecordId: item.recordId != null ? String(item.recordId) : undefined,
    actionLabel: item.itemType === 'approval' ? 'Review' : webLink ? 'Open' : 'View',
    actionHint:
      item.itemType === 'approval'
        ? 'Opens approval drawer'
        : webLink
          ? 'Opens related record'
          : 'Marks item as read',
    webLink,
  };
}

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
      className={cn(
        'rounded-full px-2 py-0.5 text-xs font-medium capitalize',
        styles[priority] || styles.normal,
      )}
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
      className={cn(
        'rounded-full px-2 py-0.5 text-xs font-medium capitalize',
        styles[status] || styles.pending,
      )}
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

function InboxLoadingSkeleton() {
  return (
    <div className="space-y-3 p-4" data-testid="inbox-loading-skeleton">
      {Array.from({ length: 5 }).map((_, index) => (
        <div
          key={index}
          className="animate-pulse rounded-xl border border-gray-200 bg-white p-4 dark:border-gray-700 dark:bg-gray-800"
        >
          <div className="flex items-start gap-4">
            <div className="h-5 w-5 rounded bg-gray-200 dark:bg-gray-700" />
            <div className="min-w-0 flex-1 space-y-3">
              <div className="flex items-center justify-between gap-4">
                <div className="h-4 w-1/3 rounded bg-gray-200 dark:bg-gray-700" />
                <div className="h-3 w-16 rounded bg-gray-100 dark:bg-gray-700" />
              </div>
              <div className="h-3 w-2/3 rounded bg-gray-100 dark:bg-gray-700" />
              <div className="flex gap-2">
                <div className="h-5 w-16 rounded-full bg-gray-100 dark:bg-gray-700" />
                <div className="h-5 w-16 rounded-full bg-gray-100 dark:bg-gray-700" />
                <div className="h-5 w-20 rounded-full bg-gray-100 dark:bg-gray-700" />
              </div>
            </div>
            <div className="h-9 w-20 rounded-lg bg-gray-100 dark:bg-gray-700" />
          </div>
        </div>
      ))}
    </div>
  );
}

export default function UnifiedInboxPage() {
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const { showErrorToast, showSuccessToast } = useToastContext();

  const [activeTab, setActiveTab] = useState(searchParams.get('type') || '');
  const [statusFilter, setStatusFilter] = useState(searchParams.get('status') || 'pending');
  const [page, setPage] = useState<InboxPage | null>(null);
  const [summary, setSummary] = useState<UnreadSummary>({});
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [quickFilter, setQuickFilter] = useState<'all' | 'unread' | 'urgent'>('all');
  const [currentPage, setCurrentPage] = useState(() => {
    const value = Number(searchParams.get('page') || '1');
    return Number.isFinite(value) && value > 0 ? value : 1;
  });
  const [drawerTaskId, setDrawerTaskId] = useState<string | null>(searchParams.get('task') || null);

  useEffect(() => {
    setActiveTab(searchParams.get('type') || '');
    setStatusFilter(searchParams.get('status') || 'pending');
    const nextPage = Number(searchParams.get('page') || '1');
    setCurrentPage(Number.isFinite(nextPage) && nextPage > 0 ? nextPage : 1);
    setDrawerTaskId(searchParams.get('task') || null);
  }, [searchParams]);

  const updateSearchParams = useCallback(
    (next: { type?: string; status?: string; page?: number; task?: string | null }) => {
      const params = new URLSearchParams(searchParams);
      if (next.type) params.set('type', next.type);
      else params.delete('type');
      if (next.status) params.set('status', next.status);
      else params.delete('status');
      if (next.page && next.page > 1) params.set('page', String(next.page));
      else params.delete('page');
      if (next.task) params.set('task', next.task);
      else params.delete('task');
      setSearchParams(params);
    },
    [searchParams, setSearchParams],
  );

  const fetchSummary = useCallback(async () => {
    try {
      const result = await getUnreadSummary();
      setSummary(result);
    } catch {
      // Secondary data.
    }
  }, []);

  const fetchData = useCallback(
    async (mode: 'initial' | 'refresh' = 'initial') => {
      if (mode === 'refresh') {
        setRefreshing(true);
      } else {
        setLoading(true);
      }
      setError(null);
      try {
        const [result] = await Promise.all([
          listInboxItems({
            itemType: activeTab || undefined,
            status: statusFilter || undefined,
            pageNum: currentPage,
            pageSize: 20,
          }),
          fetchSummary(),
        ]);
        setPage(result);
      } catch (err) {
        console.error('Failed to load inbox:', err);
        setError('Failed to load inbox items.');
      } finally {
        setLoading(false);
        setRefreshing(false);
      }
    },
    [activeTab, currentPage, fetchSummary, statusFilter],
  );

  useEffect(() => {
    fetchData();
  }, [fetchData]);

  const handleTabChange = (tab: string) => {
    updateSearchParams({ type: tab, status: statusFilter, page: 1, task: drawerTaskId });
  };

  const handleStatusChange = (status: string) => {
    updateSearchParams({ type: activeTab, status, page: 1, task: drawerTaskId });
  };

  const handlePageChange = (nextPage: number) => {
    updateSearchParams({
      type: activeTab,
      status: statusFilter,
      page: nextPage,
      task: drawerTaskId,
    });
  };

  const handleItemClick = async (item: InboxItem) => {
    if (!item.isRead) {
      try {
        await markRead(item.id);
        setPage((prev) => {
          if (!prev) return prev;
          return {
            ...prev,
            records: prev.records.map((record) =>
              record.id === item.id ? { ...record, isRead: true } : record,
            ),
          };
        });
        fetchSummary();
      } catch {
        showErrorToast('Failed to mark item as read');
      }
    }

    if (item.itemType === 'approval' && item.sourceId) {
      setDrawerTaskId(item.sourceId);
      updateSearchParams({
        type: activeTab,
        status: statusFilter,
        page: currentPage,
        task: item.sourceId,
      });
      return;
    }

    const webLink = getDisplayItem(item).webLink;
    if (webLink) {
      navigate(webLink);
      return;
    }

    showSuccessToast('Item marked as read');
  };

  const handleDismiss = async (item: InboxItem) => {
    try {
      await dismissItem(item.id);
      setPage((prev) => {
        if (!prev) return prev;
        return {
          ...prev,
          records: prev.records.filter((record) => record.id !== item.id),
          total: Math.max(0, prev.total - 1),
        };
      });
      fetchSummary();
      showSuccessToast('Item dismissed');
    } catch {
      showErrorToast('Failed to dismiss item');
    }
  };

  const handleMarkAllRead = async () => {
    try {
      await markAllReadApi();
      setPage((prev) => {
        if (!prev) return prev;
        return {
          ...prev,
          records: prev.records.map((record) => ({ ...record, isRead: true })),
        };
      });
      setSummary({});
      showSuccessToast('All visible items marked as read');
    } catch {
      showErrorToast('Failed to mark all items as read');
    }
  };

  const handleDrawerComplete = () => {
    setDrawerTaskId(null);
    updateSearchParams({ type: activeTab, status: statusFilter, page: currentPage, task: null });
    fetchData('refresh');
    showSuccessToast('Approval action completed');
  };

  const baseItems = useMemo(() => page?.records || [], [page]);
  const items = useMemo(() => {
    if (quickFilter === 'unread') return baseItems.filter((item) => !item.isRead);
    if (quickFilter === 'urgent') return baseItems.filter((item) => item.priority === 'urgent');
    return baseItems;
  }, [baseItems, quickFilter]);
  const total = page?.total || 0;
  const totalPages = page?.pages || 0;
  const totalUnread = Object.values(summary).reduce((acc, value) => acc + value, 0);
  const activeTypeCopy = TYPE_COPY[activeTab] || TYPE_COPY[''];
  const quickFilterCount =
    quickFilter === 'unread'
      ? baseItems.filter((item) => !item.isRead).length
      : quickFilter === 'urgent'
        ? baseItems.filter((item) => item.priority === 'urgent').length
        : items.length;
  const pageMetrics = useMemo(() => {
    const urgent = items.filter((item) => item.priority === 'urgent').length;
    const pending = items.filter((item) => item.status === 'pending').length;
    const approvals = items.filter((item) => item.itemType === 'approval').length;
    return { urgent, pending, approvals };
  }, [items]);

  return (
    <div className="w-full px-4 py-6 sm:px-6 xl:px-8" data-testid="unified-inbox-page">
      <div className="mb-4 flex flex-col gap-4 lg:flex-row lg:items-start lg:justify-between">
        <div className="flex items-start gap-3">
          <InboxIcon className="mt-0.5 h-7 w-7 text-gray-700 dark:text-gray-300" />
          <div>
            <h1 className="text-xl font-semibold text-gray-900 dark:text-white">Inbox</h1>
            <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">
              {pageMetrics.pending} items need attention
            </p>
          </div>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <button
            type="button"
            onClick={() => fetchData('refresh')}
            className="flex items-center gap-1.5 rounded-lg border border-gray-200 px-3 py-2 text-sm text-gray-600 transition-colors hover:bg-gray-50 dark:border-gray-600 dark:text-gray-300 dark:hover:bg-gray-700"
            data-testid="inbox-refresh"
          >
            <ArrowPathIcon className={cn('h-4 w-4', refreshing && 'animate-spin')} />
            Refresh
          </button>
          <button
            type="button"
            onClick={handleMarkAllRead}
            className="flex items-center gap-1.5 rounded-lg border border-gray-200 px-3 py-2 text-sm text-gray-600 transition-colors hover:bg-gray-50 dark:border-gray-600 dark:text-gray-300 dark:hover:bg-gray-700"
            data-testid="inbox-mark-all-read"
          >
            <EnvelopeOpenIcon className="h-4 w-4" />
            Mark all read
          </button>
        </div>
      </div>

      <section className="min-w-0">
        <div className="overflow-hidden rounded-xl border border-gray-200 bg-white dark:border-gray-700 dark:bg-gray-800">
          <div className="border-b border-gray-200 px-4 dark:border-gray-700">
            <div
              className="flex flex-wrap items-center gap-1 py-3"
              data-testid="inbox-primary-toolbar"
            >
              {TABS.map((tab) => {
                const Icon = tab.icon;
                const isActive = activeTab === tab.key;
                const countKey = tab.key || 'all';
                const count = tab.key ? summary[tab.key] || 0 : totalUnread;
                return (
                  <button
                    key={tab.key || 'all'}
                    type="button"
                    onClick={() => handleTabChange(tab.key)}
                    data-testid={`inbox-tab-${tab.key || 'all'}`}
                    className={cn(
                      'flex items-center gap-1.5 rounded-md px-3 py-2 text-sm font-medium transition-all',
                      isActive
                        ? 'bg-blue-50 text-blue-600 dark:bg-blue-900/30 dark:text-blue-300'
                        : 'text-gray-500 hover:text-gray-700 dark:text-gray-400 dark:hover:text-gray-200',
                    )}
                  >
                    <Icon className="h-4 w-4" />
                    <span>{tab.label}</span>
                    <span
                      data-testid={`inbox-tab-count-${countKey}`}
                      className={cn(
                        'rounded-full px-1.5 py-0.5 text-xs',
                        isActive
                          ? 'bg-blue-50 text-blue-600 dark:bg-blue-900/30 dark:text-blue-300'
                          : 'bg-gray-200 text-gray-500 dark:bg-gray-700 dark:text-gray-300',
                      )}
                    >
                      {count}
                    </span>
                  </button>
                );
              })}
            </div>
          </div>

          <div className="flex flex-col gap-3 border-b border-gray-200 px-4 py-3 lg:flex-row lg:items-center lg:justify-between dark:border-gray-700">
            <div className="flex flex-wrap items-center gap-2">
              <div className="flex items-center gap-1 rounded-lg bg-gray-100 p-0.5 dark:bg-gray-700">
                {STATUS_FILTERS.filter(
                  (filter) => filter.key !== 'closed' && filter.key !== 'acted',
                ).map((filter) => {
                  const normalizedKey = filter.key === '' ? 'done' : filter.key;
                  const isActive =
                    (filter.key === 'pending' && statusFilter === 'pending') ||
                    (filter.key === '' && statusFilter === '');
                  return (
                    <button
                      key={normalizedKey}
                      type="button"
                      onClick={() => handleStatusChange(filter.key)}
                      data-testid={`inbox-status-${filter.key || 'all'}`}
                      className={cn(
                        'rounded-md px-3 py-1.5 text-sm font-medium transition-colors',
                        isActive
                          ? 'bg-white text-gray-900 shadow-sm dark:bg-gray-600 dark:text-white'
                          : 'text-gray-600 hover:text-gray-800 dark:text-gray-400 dark:hover:text-gray-200',
                      )}
                    >
                      {filter.key === '' ? 'Done' : filter.label}
                    </button>
                  );
                })}
              </div>
              <p className="text-sm text-gray-500 dark:text-gray-400">
                {activeTypeCopy.description}
              </p>
            </div>

            <div className="flex flex-wrap items-center gap-2">
              <button
                type="button"
                onClick={() => setQuickFilter('all')}
                className={cn(
                  'rounded-full px-3 py-1.5 text-sm transition-colors',
                  quickFilter === 'all'
                    ? 'bg-gray-900 text-white dark:bg-gray-100 dark:text-gray-900'
                    : 'bg-gray-100 text-gray-600 hover:bg-gray-200 dark:bg-gray-700 dark:text-gray-300',
                )}
              >
                All {baseItems.length}
              </button>
              <button
                type="button"
                onClick={() => setQuickFilter('unread')}
                className={cn(
                  'rounded-full px-3 py-1.5 text-sm transition-colors',
                  quickFilter === 'unread'
                    ? 'bg-gray-900 text-white dark:bg-gray-100 dark:text-gray-900'
                    : 'bg-gray-100 text-gray-600 hover:bg-gray-200 dark:bg-gray-700 dark:text-gray-300',
                )}
              >
                Unread {baseItems.filter((item) => !item.isRead).length}
              </button>
              <button
                type="button"
                onClick={() => setQuickFilter('urgent')}
                className={cn(
                  'rounded-full px-3 py-1.5 text-sm transition-colors',
                  quickFilter === 'urgent'
                    ? 'bg-red-50 text-red-700 dark:bg-red-900/20 dark:text-red-300'
                    : 'bg-gray-100 text-gray-600 hover:bg-gray-200 dark:bg-gray-700 dark:text-gray-300',
                )}
              >
                Urgent {baseItems.filter((item) => item.priority === 'urgent').length}
              </button>
            </div>
          </div>

          <div
            className="flex flex-col gap-3 border-b border-gray-200 px-4 py-3 lg:flex-row lg:items-center lg:justify-between dark:border-gray-700"
            data-testid="inbox-summary-cards"
          >
            <div>
              <p className="text-sm font-medium text-gray-900 dark:text-white">
                {activeTypeCopy.title}
              </p>
              <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">
                {quickFilterCount} results
              </p>
            </div>
            <div className="flex flex-wrap gap-2">
              <StatPill label="Unread" value={totalUnread} />
              <StatPill label="Pending" value={pageMetrics.pending} />
              <StatPill label="Approvals" value={pageMetrics.approvals} />
              <StatPill label="Urgent" value={pageMetrics.urgent} tone="danger" />
            </div>
          </div>

          <div className="grid grid-cols-[minmax(0,1.6fr)_110px_110px_120px_200px_120px] gap-4 border-b border-gray-200 bg-gray-50 px-4 py-3 text-xs font-medium tracking-wide text-gray-500 uppercase dark:border-gray-700 dark:bg-gray-900/30 dark:text-gray-400">
            <div>Title</div>
            <div>Type</div>
            <div>Status</div>
            <div>Time</div>
            <div>Source / Record</div>
            <div className="text-right">Action</div>
          </div>

          <div className="overflow-hidden bg-white dark:bg-gray-800">
            {error ? (
              <div
                className="border-b border-rose-200 bg-rose-50 p-5"
                data-testid="inbox-error-state"
              >
                <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
                  <div>
                    <p className="text-sm font-semibold text-rose-700">Inbox failed to load</p>
                    <p className="mt-1 text-sm text-rose-600">{error}</p>
                  </div>
                  <button
                    type="button"
                    onClick={() => fetchData('refresh')}
                    className="inline-flex items-center justify-center rounded-lg border border-rose-200 bg-white px-4 py-2 text-sm font-medium text-rose-700 transition hover:bg-rose-50"
                  >
                    Retry
                  </button>
                </div>
              </div>
            ) : loading && items.length === 0 ? (
              <InboxLoadingSkeleton />
            ) : items.length === 0 ? (
              <div
                className="flex flex-col items-center justify-center py-16"
                data-testid="inbox-empty-state"
              >
                <InboxIcon className="mb-3 h-10 w-10 text-gray-300 dark:text-gray-600" />
                <p className="text-sm font-medium text-gray-700 dark:text-gray-200">
                  No items to show
                </p>
                <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">
                  Try a broader status filter or switch back to the full inbox.
                </p>
                <div className="mt-4 flex gap-2">
                  <button
                    type="button"
                    onClick={() => handleStatusChange('')}
                    className="rounded-lg border border-gray-200 px-4 py-2 text-sm text-gray-600 transition-colors hover:bg-gray-50 dark:border-gray-600 dark:text-gray-300 dark:hover:bg-gray-700"
                  >
                    Show all statuses
                  </button>
                  {activeTab !== '' && (
                    <button
                      type="button"
                      onClick={() => handleTabChange('')}
                      className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-blue-700"
                    >
                      Back to all inbox
                    </button>
                  )}
                </div>
              </div>
            ) : (
              items.map((item, index) => {
                const display = getDisplayItem(item);
                return (
                  <article
                    key={item.id}
                    className={cn(
                      'dark:hover:bg-gray-750 grid grid-cols-[minmax(0,1.6fr)_110px_110px_120px_200px_120px] gap-4 px-4 py-3 transition-colors hover:bg-gray-50',
                      index > 0 && 'border-t border-gray-100 dark:border-gray-700',
                      !item.isRead ? 'bg-blue-50/40 dark:bg-blue-900/10' : '',
                    )}
                  >
                    <div className="flex min-w-0 items-start gap-3">
                      <div className="mt-0.5 flex-shrink-0">{itemTypeIcon(item.itemType)}</div>
                      <div className="min-w-0 flex-1">
                        <button
                          type="button"
                          onClick={() => handleItemClick(item)}
                          data-testid={`inbox-item-${item.id}`}
                          className="min-w-0 text-left"
                        >
                          <h3
                            className={cn(
                              'truncate text-sm text-gray-900 dark:text-white',
                              !item.isRead ? 'font-semibold' : 'font-medium',
                            )}
                          >
                            {display.title}
                          </h3>
                          {display.subtitle && (
                            <p className="mt-0.5 truncate text-xs text-gray-500 dark:text-gray-400">
                              {display.subtitle}
                            </p>
                          )}
                        </button>
                        <div className="mt-2 flex flex-wrap items-center gap-2">
                          {priorityBadge(item.priority)}
                          {item.actionTaken && (
                            <span className="text-xs text-gray-400 dark:text-gray-500">
                              {item.actionTaken}
                            </span>
                          )}
                        </div>
                      </div>
                    </div>

                    <div className="flex items-center text-sm text-gray-500 dark:text-gray-400">
                      <span className="capitalize">{item.itemType}</span>
                    </div>

                    <div className="flex items-center">{statusBadge(item.status)}</div>

                    <div className="flex items-center text-sm text-gray-500 dark:text-gray-400">
                      {timeAgo(item.createdAt)}
                    </div>

                    <div className="flex min-w-0 items-center text-sm text-gray-500 dark:text-gray-400">
                      <div className="min-w-0">
                        {item.sourceType && <p className="truncate">{item.sourceType}</p>}
                        {(display.metaModelCode || display.metaRecordId != null) && (
                          <p className="truncate text-xs text-gray-400 dark:text-gray-500">
                            {display.metaModelCode}
                            {display.metaRecordId != null ? ` · #${display.metaRecordId}` : ''}
                          </p>
                        )}
                      </div>
                    </div>

                    <div className="flex items-center justify-end gap-2">
                      <button
                        type="button"
                        onClick={() => handleItemClick(item)}
                        className="rounded-lg border border-gray-200 px-3 py-2 text-sm text-gray-600 transition-colors hover:bg-gray-50 dark:border-gray-600 dark:text-gray-300 dark:hover:bg-gray-700"
                      >
                        {display.actionLabel}
                      </button>
                      {item.status === 'pending' && (
                        <button
                          type="button"
                          onClick={() => handleDismiss(item)}
                          className="rounded-lg p-2 text-gray-400 transition-colors hover:bg-gray-100 hover:text-gray-600 dark:hover:bg-gray-700 dark:hover:text-gray-200"
                          title="Dismiss"
                          data-testid={`inbox-dismiss-${item.id}`}
                        >
                          <XMarkIcon className="h-4 w-4" />
                        </button>
                      )}
                    </div>
                  </article>
                );
              })
            )}
          </div>

          {totalPages > 1 && (
            <div className="mt-4 flex items-center justify-between rounded-xl border border-gray-200 bg-white p-4 dark:border-gray-700 dark:bg-gray-800">
              <p className="text-sm text-gray-500 dark:text-gray-400">
                Page {currentPage} of {totalPages} ({total} items)
              </p>
              <div className="flex gap-2">
                <button
                  type="button"
                  onClick={() => handlePageChange(Math.max(1, currentPage - 1))}
                  disabled={currentPage <= 1}
                  className="inline-flex items-center gap-2 rounded-lg border border-gray-200 px-3 py-1.5 text-sm transition-colors hover:bg-gray-50 disabled:opacity-50 dark:border-gray-600 dark:text-gray-300 dark:hover:bg-gray-700"
                >
                  <ChevronLeftIcon className="h-4 w-4" />
                  Previous
                </button>
                <button
                  type="button"
                  onClick={() => handlePageChange(Math.min(totalPages, currentPage + 1))}
                  disabled={currentPage >= totalPages}
                  className="inline-flex items-center gap-2 rounded-lg bg-blue-600 px-3 py-1.5 text-sm text-white transition-colors hover:bg-blue-700 disabled:opacity-50"
                >
                  Next
                  <ChevronRightIcon className="h-4 w-4" />
                </button>
              </div>
            </div>
          )}
        </div>
      </section>

      <BpmTaskDrawer
        taskId={drawerTaskId || ''}
        open={!!drawerTaskId}
        onClose={() => {
          setDrawerTaskId(null);
          updateSearchParams({
            type: activeTab,
            status: statusFilter,
            page: currentPage,
            task: null,
          });
        }}
        onComplete={handleDrawerComplete}
      />
    </div>
  );
}

function StatPill({
  label,
  value,
  tone = 'default',
}: {
  label: string;
  value: number;
  tone?: 'default' | 'danger';
}) {
  return (
    <div
      className={cn(
        'flex items-center gap-2 rounded-lg px-3 py-2',
        tone === 'danger'
          ? 'bg-red-50 text-red-700 dark:bg-red-900/20 dark:text-red-300'
          : 'bg-gray-50 text-gray-700 dark:bg-gray-700 dark:text-gray-200',
      )}
    >
      <span className="text-xs font-medium text-gray-500 dark:text-gray-400">{label}</span>
      <span className="text-sm font-semibold">{value}</span>
    </div>
  );
}
