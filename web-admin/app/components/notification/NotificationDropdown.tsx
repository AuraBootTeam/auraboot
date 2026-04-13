/**
 * NotificationDropdown — Bell icon dropdown in header that shows
 * the latest 5 unread notifications with quick actions.
 *
 * Features:
 * - Latest 5 unread notifications with title, relative time, category icon
 * - Click notification → navigate to source page (if sourceType/sourceId available)
 * - Mark all as read button
 * - View all link → navigates to /notifications
 * - Empty state when no notifications
 * - Click outside or Escape to close
 * - All text is i18n-aware via useI18n()
 */

import React, { useState, useEffect, useRef, useCallback } from 'react';
import { Link, useNavigate } from 'react-router';
import {
  BellIcon,
  CheckCircleIcon,
  CogIcon,
  ClipboardDocumentCheckIcon,
  ExclamationTriangleIcon,
  InformationCircleIcon,
} from '@heroicons/react/24/outline';
import { useI18n } from '~/contexts/I18nContext';
import { useHydrated } from '~/hooks/useHydrated';
import { fetchResult } from '~/services/http-client';
import { ResultHelper } from '~/utils/type';
import type { Notification } from '~/hooks/useNotificationList';

interface NotificationDropdownProps {
  unreadCount: number;
  onMarkAllRead: () => void;
  onCountChange?: (count: number) => void;
}

/**
 * Format a date string into locale-aware relative time using i18n.
 */
function formatRelativeTime(
  dateString: string,
  t: (key: string, params?: Record<string, string>) => string,
): string {
  const date = new Date(dateString);
  const now = new Date();
  const diffMs = now.getTime() - date.getTime();
  const diffSecs = Math.floor(diffMs / 1000);
  const diffMins = Math.floor(diffSecs / 60);
  const diffHours = Math.floor(diffMins / 60);
  const diffDays = Math.floor(diffHours / 24);

  if (diffSecs < 60) return t('notification.time.just_now');
  if (diffMins < 60) return t('notification.time.minutes_ago', { count: String(diffMins) });
  if (diffHours < 24) return t('notification.time.hours_ago', { count: String(diffHours) });
  if (diffDays === 1) return t('notification.time.yesterday');
  if (diffDays < 7) return t('notification.time.days_ago', { count: String(diffDays) });
  return date.toLocaleDateString();
}

/**
 * Get category icon for a notification.
 */
function getCategoryIcon(category: string) {
  switch (category) {
    case 'system':
      return <CogIcon className="h-4 w-4 text-gray-500" />;
    case 'approval':
      return <ClipboardDocumentCheckIcon className="h-4 w-4 text-blue-500" />;
    case 'alert':
      return <ExclamationTriangleIcon className="h-4 w-4 text-yellow-500" />;
    case 'business':
      return <InformationCircleIcon className="h-4 w-4 text-green-500" />;
    default:
      return <BellIcon className="h-4 w-4 text-gray-400" />;
  }
}

/**
 * Get priority dot color.
 */
function getPriorityDotClass(priority: string): string {
  switch (priority) {
    case 'high':
      return 'bg-red-500';
    case 'normal':
      return 'bg-blue-500';
    case 'low':
      return 'bg-gray-400';
    default:
      return 'bg-gray-400';
  }
}

export function NotificationDropdown({ unreadCount, onMarkAllRead }: NotificationDropdownProps) {
  const hydrated = useHydrated();
  const [open, setOpen] = useState(false);
  const [notifications, setNotifications] = useState<Notification[]>([]);
  const [loading, setLoading] = useState(false);
  const dropdownRef = useRef<HTMLDivElement>(null);
  const navigate = useNavigate();
  const { t } = useI18n();

  // Fetch latest 5 unread notifications when dropdown opens
  const fetchLatest = useCallback(async () => {
    setLoading(true);
    try {
      const result = await fetchResult('/api/notifications', {
        method: 'get',
        params: {
          pageNum: '1',
          pageSize: '5',
          isRead: 'false',
        },
      });
      if (ResultHelper.isSuccess(result)) {
        const data = result.data as { records: Notification[]; total: number } | Notification[];
        if (Array.isArray(data)) {
          setNotifications(data.slice(0, 5));
        } else {
          setNotifications(data?.records?.slice(0, 5) || []);
        }
      }
    } catch (error) {
      console.error('Failed to fetch latest notifications:', error);
    } finally {
      setLoading(false);
    }
  }, []);

  // Toggle dropdown and fetch when opening
  const toggleDropdown = useCallback(() => {
    setOpen((prev) => {
      const nextOpen = !prev;
      if (nextOpen) {
        fetchLatest();
      }
      return nextOpen;
    });
  }, [fetchLatest]);

  // Close on click outside
  useEffect(() => {
    const handleClickOutside = (event: MouseEvent) => {
      if (dropdownRef.current && !dropdownRef.current.contains(event.target as Node)) {
        setOpen(false);
      }
    };
    const handleEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setOpen(false);
      }
    };

    if (open) {
      document.addEventListener('mousedown', handleClickOutside);
      document.addEventListener('keydown', handleEscape);
    }
    return () => {
      document.removeEventListener('mousedown', handleClickOutside);
      document.removeEventListener('keydown', handleEscape);
    };
  }, [open]);

  // Handle notification click — navigate to source or notification center
  const handleNotificationClick = useCallback(
    (notification: Notification) => {
      setOpen(false);
      if (notification.sourceType && notification.sourceId) {
        // Navigate to the source entity
        const sourceTypeLower = notification.sourceType.toLowerCase();
        navigate(`/p/${sourceTypeLower}/view/${notification.sourceId}`);
      } else {
        navigate('/notifications');
      }
    },
    [navigate],
  );

  // Handle mark all read from dropdown
  const handleMarkAllRead = useCallback(() => {
    onMarkAllRead();
    setNotifications((prev) =>
      prev.map((n) => ({ ...n, isRead: true, readAt: new Date().toISOString() })),
    );
  }, [onMarkAllRead]);

  return (
    <div className="relative" ref={dropdownRef} data-testid="notification-dropdown">
      {/* Bell icon button */}
      <button
        type="button"
        onClick={toggleDropdown}
        disabled={!hydrated}
        aria-busy={!hydrated}
        aria-expanded={open}
        className="relative rounded-xl p-2.5 text-gray-500 transition-all duration-200 hover:scale-105 hover:bg-gray-100 hover:text-gray-700 hover:shadow-md dark:text-gray-400 dark:hover:bg-gray-700 dark:hover:text-gray-200"
        title={t('notification.center.title')}
        data-testid="notification-bell"
      >
        <BellIcon className="h-6 w-6" />
        {unreadCount > 0 && (
          <span className="absolute -top-1 -right-1 flex h-[18px] min-w-[18px] items-center justify-center rounded-full bg-red-500 px-1 text-xs font-medium text-white shadow-sm">
            {unreadCount > 99 ? '99+' : unreadCount}
          </span>
        )}
      </button>

      {/* Dropdown panel */}
      {open && (
        <div
          className="absolute right-0 z-50 mt-2 w-96 overflow-hidden rounded-lg border border-gray-200 bg-white shadow-xl dark:border-gray-700 dark:bg-gray-800"
          data-testid="notification-dropdown-panel"
        >
          {/* Header */}
          <div className="flex items-center justify-between border-b border-gray-200 bg-gray-50 px-4 py-3 dark:border-gray-700 dark:bg-gray-900/50">
            <h3 className="text-sm font-semibold text-gray-900 dark:text-white">
              {t('notification.center.title')}
              {unreadCount > 0 && (
                <span className="ml-2 inline-flex items-center rounded-full bg-red-100 px-2 py-0.5 text-xs font-medium text-red-800 dark:bg-red-900/30 dark:text-red-400">
                  {t('notification.center.unread_count', { count: String(unreadCount) })}
                </span>
              )}
            </h3>
            {unreadCount > 0 && (
              <button
                onClick={handleMarkAllRead}
                className="text-xs font-medium text-indigo-600 transition-colors hover:text-indigo-800 dark:text-indigo-400 dark:hover:text-indigo-300"
                data-testid="mark-all-read-btn"
              >
                {t('notification.center.mark_all_read')}
              </button>
            )}
          </div>

          {/* Notification list */}
          <div className="max-h-80 overflow-y-auto">
            {loading ? (
              <div className="flex items-center justify-center py-8">
                <div className="h-5 w-5 animate-spin rounded-full border-b-2 border-indigo-600"></div>
                <span className="ml-2 text-sm text-gray-500">{t('common.loading')}</span>
              </div>
            ) : notifications.length === 0 ? (
              <div className="flex flex-col items-center justify-center py-8 text-gray-400">
                <BellIcon className="mb-2 h-10 w-10" />
                <p className="text-sm">{t('notification.center.empty')}</p>
              </div>
            ) : (
              notifications.map((notification) => (
                <button
                  key={notification.id}
                  onClick={() => handleNotificationClick(notification)}
                  className={`w-full border-b border-gray-100 px-4 py-3 text-left transition-colors last:border-b-0 hover:bg-gray-50 dark:border-gray-700/50 dark:hover:bg-gray-700/50 ${
                    !notification.isRead ? 'bg-indigo-50/40 dark:bg-indigo-900/10' : ''
                  }`}
                  data-testid="notification-item"
                >
                  <div className="flex items-start gap-3">
                    {/* Category icon */}
                    <div className="mt-0.5 flex-shrink-0">
                      {getCategoryIcon(notification.category)}
                    </div>

                    {/* Content */}
                    <div className="min-w-0 flex-1">
                      <div className="flex items-center gap-2">
                        <p
                          className={`truncate text-sm ${
                            !notification.isRead
                              ? 'font-semibold text-gray-900 dark:text-white'
                              : 'font-medium text-gray-700 dark:text-gray-300'
                          }`}
                        >
                          {notification.title}
                        </p>
                        {/* Priority dot */}
                        {notification.priority === 'high' && (
                          <span
                            className={`inline-block h-2 w-2 flex-shrink-0 rounded-full ${getPriorityDotClass(notification.priority)}`}
                            title={t('notification.priority.high')}
                          />
                        )}
                      </div>
                      <p className="mt-0.5 truncate text-xs text-gray-500 dark:text-gray-400">
                        {notification.content}
                      </p>
                      <p className="mt-1 text-xs text-gray-400 dark:text-gray-500">
                        {formatRelativeTime(notification.createdAt, t)}
                      </p>
                    </div>

                    {/* Unread indicator */}
                    {!notification.isRead && (
                      <span className="mt-2 h-2 w-2 flex-shrink-0 rounded-full bg-indigo-500" />
                    )}
                  </div>
                </button>
              ))
            )}
          </div>

          {/* Footer */}
          <div className="border-t border-gray-200 bg-gray-50 dark:border-gray-700 dark:bg-gray-900/50">
            <Link
              to="/notifications"
              onClick={() => setOpen(false)}
              className="block w-full px-4 py-2.5 text-center text-sm font-medium text-indigo-600 transition-colors hover:bg-gray-100 dark:text-indigo-400 dark:hover:bg-gray-700/50"
              data-testid="view-all-notifications"
            >
              {t('notification.center.view_all')}
            </Link>
          </div>
        </div>
      )}
    </div>
  );
}
