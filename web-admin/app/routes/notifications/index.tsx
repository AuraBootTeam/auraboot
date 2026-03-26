import { useState, useEffect, useCallback, useRef, useMemo } from 'react';
import { Link, useNavigate } from 'react-router';
import {
  useNotificationList,
  type Notification,
  type NotificationQueryParams,
} from '~/hooks/useNotificationList';
import {
  BellIcon,
  CheckIcon,
  CheckCircleIcon,
  ExclamationTriangleIcon,
  InformationCircleIcon,
  CogIcon,
  ClipboardDocumentCheckIcon,
  Cog6ToothIcon,
  TrashIcon,
  ArrowTopRightOnSquareIcon,
  InboxIcon,
} from '@heroicons/react/24/outline';
import { BellAlertIcon } from '@heroicons/react/24/solid';

/**
 * Notification Center Page
 *
 * Full-featured notification center with category tabs, batch operations,
 * enhanced list items, and notification preferences link.
 */

// -- Types ---------------------------------------------------------------

type CategoryKey = '' | 'system' | 'approval' | 'business' | 'alert';
type ReadFilter = 'all' | 'unread' | 'read';

interface CategoryTab {
  key: CategoryKey;
  label: string;
  icon: React.ReactNode;
  color: string;
  activeColor: string;
}

// -- Constants -----------------------------------------------------------

const CATEGORY_TABS: CategoryTab[] = [
  {
    key: '',
    label: '全部',
    icon: <BellIcon className="h-4 w-4" />,
    color: 'text-gray-600 dark:text-gray-400',
    activeColor: 'text-indigo-600 dark:text-indigo-400 border-indigo-600 dark:border-indigo-400',
  },
  {
    key: 'system',
    label: '系统',
    icon: <CogIcon className="h-4 w-4" />,
    color: 'text-gray-600 dark:text-gray-400',
    activeColor: 'text-gray-700 dark:text-gray-300 border-gray-600 dark:border-gray-400',
  },
  {
    key: 'approval',
    label: '审批',
    icon: <ClipboardDocumentCheckIcon className="h-4 w-4" />,
    color: 'text-gray-600 dark:text-gray-400',
    activeColor: 'text-blue-600 dark:text-blue-400 border-blue-600 dark:border-blue-400',
  },
  {
    key: 'business',
    label: '业务',
    icon: <InformationCircleIcon className="h-4 w-4" />,
    color: 'text-gray-600 dark:text-gray-400',
    activeColor: 'text-green-600 dark:text-green-400 border-green-600 dark:border-green-400',
  },
  {
    key: 'alert',
    label: '告警',
    icon: <ExclamationTriangleIcon className="h-4 w-4" />,
    color: 'text-gray-600 dark:text-gray-400',
    activeColor: 'text-yellow-600 dark:text-yellow-400 border-yellow-600 dark:border-yellow-400',
  },
];

const PRIORITY_LABELS: Record<string, string> = {
  HIGH: '高',
  NORMAL: '普通',
  LOW: '低',
};

// -- Helpers -------------------------------------------------------------

/**
 * Format a date string into Chinese relative time.
 */
function formatRelativeTime(dateString: string): string {
  const date = new Date(dateString);
  const now = new Date();
  const diffMs = now.getTime() - date.getTime();
  const diffSecs = Math.floor(diffMs / 1000);
  const diffMins = Math.floor(diffSecs / 60);
  const diffHours = Math.floor(diffMins / 60);
  const diffDays = Math.floor(diffHours / 24);

  if (diffSecs < 60) return '刚刚';
  if (diffMins < 60) return `${diffMins}分钟前`;
  if (diffHours < 24) return `${diffHours}小时前`;
  if (diffDays === 1) return '昨天';
  if (diffDays < 7) return `${diffDays}天前`;
  return date.toLocaleDateString('zh-CN');
}

/**
 * Get category icon with color.
 */
function getCategoryIcon(category: string, size: string = 'h-5 w-5') {
  switch (category) {
    case 'system':
      return <CogIcon className={`${size} text-gray-500`} />;
    case 'approval':
      return <ClipboardDocumentCheckIcon className={`${size} text-blue-500`} />;
    case 'alert':
      return <ExclamationTriangleIcon className={`${size} text-yellow-500`} />;
    case 'business':
      return <InformationCircleIcon className={`${size} text-green-500`} />;
    default:
      return <BellIcon className={`${size} text-gray-400`} />;
  }
}

/**
 * Get category icon background color for the circular badge.
 */
function getCategoryIconBg(category: string): string {
  switch (category) {
    case 'system':
      return 'bg-gray-100 dark:bg-gray-700';
    case 'approval':
      return 'bg-blue-50 dark:bg-blue-900/30';
    case 'alert':
      return 'bg-yellow-50 dark:bg-yellow-900/30';
    case 'business':
      return 'bg-green-50 dark:bg-green-900/30';
    default:
      return 'bg-gray-100 dark:bg-gray-700';
  }
}

/**
 * Get priority badge style.
 */
function getPriorityBadge(priority: string): string {
  switch (priority) {
    case 'high':
      return 'bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-400';
    case 'normal':
      return 'bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-400';
    case 'low':
      return 'bg-gray-100 text-gray-600 dark:bg-gray-700 dark:text-gray-400';
    default:
      return 'bg-gray-100 text-gray-600 dark:bg-gray-700 dark:text-gray-400';
  }
}

// -- Component -----------------------------------------------------------

export default function NotificationCenter() {
  const {
    notifications,
    loading,
    total,
    unreadCount,
    fetchNotifications,
    fetchUnreadCount,
    markAsRead,
    markAllAsRead,
    deleteNotifications,
  } = useNotificationList();

  const navigate = useNavigate();

  // Query params
  const [activeCategory, setActiveCategory] = useState<CategoryKey>('');
  const [readFilter, setReadFilter] = useState<ReadFilter>('all');
  const [pageNum, setPageNum] = useState(1);
  const [pageSize] = useState(10);

  // Batch selection
  const [selectedIds, setSelectedIds] = useState<Set<number>>(new Set());

  // Refs to avoid infinite loops
  const fetchNotificationsRef = useRef(fetchNotifications);
  const fetchUnreadCountRef = useRef(fetchUnreadCount);

  useEffect(() => {
    fetchNotificationsRef.current = fetchNotifications;
    fetchUnreadCountRef.current = fetchUnreadCount;
  });

  // Initial unread count load
  useEffect(() => {
    fetchUnreadCountRef.current();
  }, []);

  // Build query params from local state
  const queryParams: NotificationQueryParams = useMemo(
    () => ({
      category: activeCategory,
      isRead: readFilter === 'all' ? null : readFilter === 'read',
      pageNum,
      pageSize,
    }),
    [activeCategory, readFilter, pageNum, pageSize],
  );

  // Fetch on query param changes
  useEffect(() => {
    fetchNotificationsRef.current(queryParams);
    // Clear selection when filters/pages change
    setSelectedIds(new Set());
  }, [queryParams]);

  // -- Handlers ----------------------------------------------------------

  const handleCategoryChange = useCallback((key: CategoryKey) => {
    setActiveCategory(key);
    setPageNum(1);
  }, []);

  const handleReadFilterChange = useCallback((filter: ReadFilter) => {
    setReadFilter(filter);
    setPageNum(1);
  }, []);

  const handlePageChange = useCallback((page: number) => {
    setPageNum(page);
  }, []);

  const handleNotificationClick = useCallback(
    (notification: Notification) => {
      // Mark as read on click
      if (!notification.isRead) {
        markAsRead(notification.id);
      }
      if (notification.sourceType && notification.sourceId) {
        const sourceTypeLower = notification.sourceType.toLowerCase();
        navigate(`/dynamic/${sourceTypeLower}/view/${notification.sourceId}`);
      }
    },
    [navigate, markAsRead],
  );

  // Batch selection
  const handleToggleSelect = useCallback((id: number) => {
    setSelectedIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) {
        next.delete(id);
      } else {
        next.add(id);
      }
      return next;
    });
  }, []);

  const handleSelectAll = useCallback(() => {
    if (selectedIds.size === notifications.length) {
      setSelectedIds(new Set());
    } else {
      setSelectedIds(new Set(notifications.map((n) => n.id)));
    }
  }, [notifications, selectedIds.size]);

  const handleDeleteSelected = useCallback(async () => {
    if (selectedIds.size === 0) return;
    await deleteNotifications(Array.from(selectedIds));
    setSelectedIds(new Set());
  }, [selectedIds, deleteNotifications]);

  const handleMarkAllAsRead = useCallback(async () => {
    await markAllAsRead();
  }, [markAllAsRead]);

  // Computed
  const totalPages = Math.ceil(total / pageSize);
  const allSelected = notifications.length > 0 && selectedIds.size === notifications.length;
  const someSelected = selectedIds.size > 0;

  return (
    <div className="min-h-screen bg-gray-50 dark:bg-gray-900">
      {/* Page Header */}
      <div className="border-b border-gray-200 bg-white shadow-sm dark:border-gray-700 dark:bg-gray-800">
        <div className="mx-auto max-w-5xl px-4 sm:px-6 lg:px-8">
          <div className="flex items-center justify-between py-5">
            <div className="flex items-center gap-3">
              <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-indigo-100 dark:bg-indigo-900/30">
                <BellAlertIcon className="h-6 w-6 text-indigo-600 dark:text-indigo-400" />
              </div>
              <div>
                <h1 className="text-xl font-bold text-gray-900 dark:text-white">通知中心</h1>
                <p className="text-sm text-gray-500 dark:text-gray-400">
                  {unreadCount > 0 ? `${unreadCount} 条未读通知` : '所有通知已读'}
                </p>
              </div>
            </div>
            <div className="flex items-center gap-2">
              <Link
                to="/settings/notification-preferences"
                className="inline-flex items-center gap-1.5 rounded-lg bg-gray-100 px-3 py-2 text-sm font-medium text-gray-600 transition-colors hover:bg-gray-200 dark:bg-gray-700 dark:text-gray-300 dark:hover:bg-gray-600"
                title="通知偏好设置"
              >
                <Cog6ToothIcon className="h-4 w-4" />
                <span className="hidden sm:inline">偏好设置</span>
              </Link>
            </div>
          </div>

          {/* Category Tabs */}
          <div className="scrollbar-hide -mb-px flex items-center gap-1 overflow-x-auto">
            {CATEGORY_TABS.map((tab) => {
              const isActive = activeCategory === tab.key;
              return (
                <button
                  key={tab.key}
                  onClick={() => handleCategoryChange(tab.key)}
                  className={`inline-flex items-center gap-1.5 border-b-2 px-4 py-2.5 text-sm font-medium whitespace-nowrap transition-colors ${
                    isActive
                      ? tab.activeColor
                      : `${tab.color} border-transparent hover:border-gray-300 hover:text-gray-900 dark:hover:border-gray-500 dark:hover:text-gray-200`
                  } `}
                >
                  {tab.icon}
                  {tab.label}
                </button>
              );
            })}
          </div>
        </div>
      </div>

      {/* Content Area */}
      <div className="mx-auto max-w-5xl px-4 py-6 sm:px-6 lg:px-8">
        {/* Toolbar */}
        <div className="mb-4 flex items-center justify-between rounded-lg border border-gray-200 bg-white px-4 py-3 dark:border-gray-700 dark:bg-gray-800">
          <div className="flex items-center gap-3">
            {/* Select all checkbox */}
            <label className="flex cursor-pointer items-center gap-2 select-none">
              <input
                type="checkbox"
                checked={allSelected}
                onChange={handleSelectAll}
                className="h-4 w-4 rounded border-gray-300 text-indigo-600 focus:ring-indigo-500 dark:border-gray-600 dark:bg-gray-700"
                disabled={notifications.length === 0}
              />
              <span className="text-sm text-gray-600 dark:text-gray-400">
                {someSelected ? `已选 ${selectedIds.size} 项` : '全选'}
              </span>
            </label>

            {/* Batch action buttons (visible when items selected) */}
            {someSelected && (
              <div className="ml-2 flex items-center gap-2 border-l border-gray-200 pl-2 dark:border-gray-600">
                <button
                  onClick={handleDeleteSelected}
                  className="inline-flex items-center gap-1.5 rounded-md bg-red-50 px-3 py-1.5 text-sm font-medium text-red-600 transition-colors hover:bg-red-100 dark:bg-red-900/20 dark:text-red-400 dark:hover:bg-red-900/30"
                >
                  <TrashIcon className="h-4 w-4" />
                  删除选中
                </button>
              </div>
            )}
          </div>

          <div className="flex items-center gap-3">
            {/* Read status filter */}
            <div className="flex items-center rounded-lg bg-gray-100 p-0.5 dark:bg-gray-700">
              {(
                [
                  { key: 'all', label: '全部' },
                  { key: 'unread', label: '未读' },
                  { key: 'read', label: '已读' },
                ] as { key: ReadFilter; label: string }[]
              ).map((filter) => (
                <button
                  key={filter.key}
                  onClick={() => handleReadFilterChange(filter.key)}
                  className={`rounded-md px-3 py-1 text-sm font-medium transition-colors ${
                    readFilter === filter.key
                      ? 'bg-white text-gray-900 shadow-sm dark:bg-gray-600 dark:text-white'
                      : 'text-gray-600 hover:text-gray-900 dark:text-gray-400 dark:hover:text-gray-200'
                  } `}
                >
                  {filter.label}
                </button>
              ))}
            </div>

            {/* Mark all read button */}
            {unreadCount > 0 && (
              <button
                onClick={handleMarkAllAsRead}
                className="inline-flex items-center gap-1.5 rounded-md bg-indigo-50 px-3 py-1.5 text-sm font-medium text-indigo-600 transition-colors hover:bg-indigo-100 dark:bg-indigo-900/20 dark:text-indigo-400 dark:hover:bg-indigo-900/30"
              >
                <CheckCircleIcon className="h-4 w-4" />
                全部已读
              </button>
            )}
          </div>
        </div>

        {/* Notification List */}
        {loading ? (
          <LoadingSkeleton />
        ) : notifications.length === 0 ? (
          <EmptyState activeCategory={activeCategory} readFilter={readFilter} />
        ) : (
          <div className="space-y-2">
            {notifications.map((notification: Notification) => (
              <NotificationItem
                key={notification.id}
                notification={notification}
                selected={selectedIds.has(notification.id)}
                onToggleSelect={handleToggleSelect}
                onClick={handleNotificationClick}
                onMarkAsRead={markAsRead}
              />
            ))}
          </div>
        )}

        {/* Pagination */}
        {totalPages > 1 && (
          <Pagination
            pageNum={pageNum}
            pageSize={pageSize}
            total={total}
            totalPages={totalPages}
            onPageChange={handlePageChange}
          />
        )}
      </div>
    </div>
  );
}

// -- Sub-components ------------------------------------------------------

/**
 * Individual notification item with checkbox, icon, content, and actions.
 */
function NotificationItem({
  notification,
  selected,
  onToggleSelect,
  onClick,
  onMarkAsRead,
}: {
  notification: Notification;
  selected: boolean;
  onToggleSelect: (id: number) => void;
  onClick: (n: Notification) => void;
  onMarkAsRead: (id: number) => void;
}) {
  const hasSource = notification.sourceType && notification.sourceId;

  return (
    <div
      className={`group relative rounded-lg border bg-white transition-all duration-150 dark:bg-gray-800 ${
        !notification.isRead
          ? 'border-t border-r border-b border-l-4 border-t-gray-200 border-r-gray-200 border-b-gray-200 border-l-indigo-500 shadow-sm dark:border-t-gray-700 dark:border-r-gray-700 dark:border-b-gray-700'
          : 'border-gray-200 hover:border-gray-300 dark:border-gray-700 dark:hover:border-gray-600'
      } hover:shadow-md`}
    >
      <div className="flex items-start gap-3 p-4">
        {/* Checkbox */}
        <div className="flex-shrink-0 pt-0.5">
          <input
            type="checkbox"
            checked={selected}
            onChange={(e) => {
              e.stopPropagation();
              onToggleSelect(notification.id);
            }}
            className="h-4 w-4 cursor-pointer rounded border-gray-300 text-indigo-600 focus:ring-indigo-500 dark:border-gray-600 dark:bg-gray-700"
          />
        </div>

        {/* Category icon with background */}
        <div
          className={`flex h-9 w-9 flex-shrink-0 items-center justify-center rounded-lg ${getCategoryIconBg(notification.category)}`}
        >
          {getCategoryIcon(notification.category, 'h-5 w-5')}
        </div>

        {/* Main content */}
        <div
          className={`min-w-0 flex-1 ${hasSource ? 'cursor-pointer' : ''}`}
          onClick={() => onClick(notification)}
        >
          <div className="mb-0.5 flex flex-wrap items-center gap-2">
            <h3
              className={`text-sm leading-tight ${
                !notification.isRead
                  ? 'font-semibold text-gray-900 dark:text-white'
                  : 'font-medium text-gray-700 dark:text-gray-300'
              }`}
            >
              {notification.title}
            </h3>
            {notification.priority === 'high' && (
              <span
                className={`inline-flex items-center rounded px-1.5 py-0.5 text-xs font-medium ${getPriorityBadge('high')}`}
              >
                {PRIORITY_LABELS.HIGH}
              </span>
            )}
          </div>

          <p className="line-clamp-2 text-sm leading-relaxed text-gray-600 dark:text-gray-400">
            {notification.content}
          </p>

          <div className="mt-2 flex items-center gap-3">
            <span className="text-xs text-gray-400 dark:text-gray-500">
              {formatRelativeTime(notification.createdAt)}
            </span>
            {hasSource && (
              <span className="inline-flex items-center gap-1 text-xs text-indigo-500 dark:text-indigo-400">
                <ArrowTopRightOnSquareIcon className="h-3 w-3" />
                查看详情
              </span>
            )}
            {notification.isRead && notification.readAt && (
              <span className="text-xs text-gray-400 dark:text-gray-500">
                已读于 {formatRelativeTime(notification.readAt)}
              </span>
            )}
          </div>
        </div>

        {/* Actions */}
        <div className="flex flex-shrink-0 items-center gap-1 opacity-0 transition-opacity group-hover:opacity-100">
          {!notification.isRead && (
            <button
              onClick={(e) => {
                e.stopPropagation();
                onMarkAsRead(notification.id);
              }}
              className="inline-flex items-center gap-1 rounded-md bg-indigo-50 px-2.5 py-1 text-xs font-medium text-indigo-600 transition-colors hover:bg-indigo-100 dark:bg-indigo-900/20 dark:text-indigo-400 dark:hover:bg-indigo-900/30"
              title="标为已读"
            >
              <CheckIcon className="h-3.5 w-3.5" />
              已读
            </button>
          )}
        </div>

        {/* Unread dot indicator */}
        {!notification.isRead && (
          <span className="absolute top-4 right-4 h-2 w-2 rounded-full bg-indigo-500 group-hover:hidden" />
        )}
      </div>
    </div>
  );
}

/**
 * Empty state illustration.
 */
function EmptyState({
  activeCategory,
  readFilter,
}: {
  activeCategory: CategoryKey;
  readFilter: ReadFilter;
}) {
  const categoryLabel = CATEGORY_TABS.find((t) => t.key === activeCategory)?.label || '全部';
  const filterLabel = readFilter === 'unread' ? '未读' : readFilter === 'read' ? '已读' : '';

  return (
    <div className="rounded-lg border border-gray-200 bg-white py-16 text-center dark:border-gray-700 dark:bg-gray-800">
      <div className="mx-auto mb-4 flex h-16 w-16 items-center justify-center rounded-full bg-gray-100 dark:bg-gray-700">
        <InboxIcon className="h-8 w-8 text-gray-400 dark:text-gray-500" />
      </div>
      <h3 className="mb-1 text-lg font-medium text-gray-900 dark:text-white">
        暂无{filterLabel}
        {activeCategory ? categoryLabel : ''}通知
      </h3>
      <p className="mx-auto max-w-sm text-sm text-gray-500 dark:text-gray-400">
        {readFilter === 'unread'
          ? '所有通知已处理完毕，做得很棒！'
          : '当有新的通知时，它们将显示在这里。'}
      </p>
    </div>
  );
}

/**
 * Pagination component.
 */
function Pagination({
  pageNum,
  pageSize,
  total,
  totalPages,
  onPageChange,
}: {
  pageNum: number;
  pageSize: number;
  total: number;
  totalPages: number;
  onPageChange: (page: number) => void;
}) {
  // Generate page numbers with ellipsis
  const pages: (number | 'ellipsis')[] = [];
  if (totalPages <= 7) {
    for (let i = 1; i <= totalPages; i++) pages.push(i);
  } else {
    pages.push(1);
    if (pageNum > 3) pages.push('ellipsis');
    const start = Math.max(2, pageNum - 1);
    const end = Math.min(totalPages - 1, pageNum + 1);
    for (let i = start; i <= end; i++) pages.push(i);
    if (pageNum < totalPages - 2) pages.push('ellipsis');
    pages.push(totalPages);
  }

  return (
    <div className="mt-6 flex items-center justify-between rounded-lg border border-gray-200 bg-white px-4 py-3 dark:border-gray-700 dark:bg-gray-800">
      <div className="text-sm text-gray-600 dark:text-gray-400">
        第{' '}
        <span className="font-medium text-gray-900 dark:text-white">
          {(pageNum - 1) * pageSize + 1}
        </span>
        {' - '}
        <span className="font-medium text-gray-900 dark:text-white">
          {Math.min(pageNum * pageSize, total)}
        </span>{' '}
        条，共 <span className="font-medium text-gray-900 dark:text-white">{total}</span> 条
      </div>
      <div className="flex items-center gap-1">
        <button
          onClick={() => onPageChange(pageNum - 1)}
          disabled={pageNum === 1}
          className="rounded-md border border-gray-300 bg-white px-3 py-1.5 text-sm font-medium text-gray-700 transition-colors hover:bg-gray-50 disabled:cursor-not-allowed disabled:opacity-40 dark:border-gray-600 dark:bg-gray-800 dark:text-gray-300 dark:hover:bg-gray-700"
        >
          上一页
        </button>
        {pages.map((p, idx) =>
          p === 'ellipsis' ? (
            <span key={`ellipsis-${idx}`} className="px-2 text-gray-400">
              ...
            </span>
          ) : (
            <button
              key={p}
              onClick={() => onPageChange(p)}
              className={`min-w-[36px] rounded-md px-2 py-1.5 text-sm font-medium transition-colors ${
                pageNum === p
                  ? 'bg-indigo-600 text-white shadow-sm'
                  : 'text-gray-700 hover:bg-gray-100 dark:text-gray-300 dark:hover:bg-gray-700'
              } `}
            >
              {p}
            </button>
          ),
        )}
        <button
          onClick={() => onPageChange(pageNum + 1)}
          disabled={pageNum === totalPages}
          className="rounded-md border border-gray-300 bg-white px-3 py-1.5 text-sm font-medium text-gray-700 transition-colors hover:bg-gray-50 disabled:cursor-not-allowed disabled:opacity-40 dark:border-gray-600 dark:bg-gray-800 dark:text-gray-300 dark:hover:bg-gray-700"
        >
          下一页
        </button>
      </div>
    </div>
  );
}

/**
 * Loading skeleton for notification list.
 */
function LoadingSkeleton() {
  return (
    <div className="space-y-2">
      {Array.from({ length: 5 }).map((_, i) => (
        <div
          key={i}
          className="rounded-lg border border-gray-200 bg-white p-4 dark:border-gray-700 dark:bg-gray-800"
        >
          <div className="flex items-start gap-3">
            <div className="mt-0.5 h-4 w-4 animate-pulse rounded bg-gray-200 dark:bg-gray-700" />
            <div className="h-9 w-9 animate-pulse rounded-lg bg-gray-200 dark:bg-gray-700" />
            <div className="flex-1">
              <div className="mb-2 h-4 w-48 animate-pulse rounded bg-gray-200 dark:bg-gray-700" />
              <div className="mb-1 h-3 w-full animate-pulse rounded bg-gray-200 dark:bg-gray-700" />
              <div className="mb-2 h-3 w-2/3 animate-pulse rounded bg-gray-200 dark:bg-gray-700" />
              <div className="h-3 w-24 animate-pulse rounded bg-gray-200 dark:bg-gray-700" />
            </div>
          </div>
        </div>
      ))}
    </div>
  );
}
