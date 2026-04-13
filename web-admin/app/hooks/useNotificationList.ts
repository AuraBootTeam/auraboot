import { useState, useCallback } from 'react';
import { fetchResult } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';
import { useToastContext } from '~/contexts/ToastContext';

/**
 * Notification DTO matching backend NotificationDTO
 */
export interface Notification {
  id: number;
  title: string;
  content: string;
  category: 'system' | 'approval' | 'alert' | 'business';
  priority: 'high' | 'normal' | 'low';
  sourceType: 'workflow' | 'system' | 'user';
  sourceId: string | null;
  isRead: boolean;
  readAt: string | null;
  createdAt: string;
}

/**
 * Query parameters for notification list
 */
export interface NotificationQueryParams {
  category?: string;
  isRead?: boolean | null;
  pageNum: number;
  pageSize: number;
}

/**
 * Hook for managing notification list
 */
export function useNotificationList() {
  const [notifications, setNotifications] = useState<Notification[]>([]);
  const [loading, setLoading] = useState(false);
  const [total, setTotal] = useState(0);
  const [unreadCount, setUnreadCount] = useState(0);
  const { showSuccessToast, showErrorToast } = useToastContext();

  /**
   * Fetch notification list
   */
  const fetchNotifications = useCallback(
    async (params: NotificationQueryParams) => {
      setLoading(true);
      try {
        const queryParams: Record<string, string> = {
          pageNum: params.pageNum.toString(),
          pageSize: params.pageSize.toString(),
        };
        if (params.category) {
          queryParams.category = params.category;
        }
        if (params.isRead !== null && params.isRead !== undefined) {
          queryParams.isRead = params.isRead.toString();
        }

        const result = await fetchResult('/api/notifications', {
          method: 'get',
          params: queryParams,
        });

        if (ResultHelper.isSuccess(result)) {
          const data = result.data as { records: Notification[]; total: number } | Notification[];
          if (Array.isArray(data)) {
            setNotifications(data);
            setTotal(data.length);
          } else {
            setNotifications(data?.records || []);
            setTotal(data?.total || 0);
          }
        } else {
          showErrorToast(result.desc || 'Failed to load notifications');
        }
      } catch (error) {
        console.error('Failed to fetch notifications:', error);
        showErrorToast('Failed to load notifications');
      } finally {
        setLoading(false);
      }
    },
    [showErrorToast],
  );

  /**
   * Fetch unread notification count
   */
  const fetchUnreadCount = useCallback(async () => {
    try {
      const result = await fetchResult('/api/notifications/unread-count', {
        method: 'get',
      });

      if (ResultHelper.isSuccess(result)) {
        const data = result.data as { count: number } | number;
        if (typeof data === 'number') {
          setUnreadCount(data);
        } else {
          setUnreadCount(data?.count || 0);
        }
      }
    } catch (error) {
      console.error('Failed to fetch unread count:', error);
    }
  }, []);

  /**
   * Mark a single notification as read
   */
  const markAsRead = useCallback(
    async (id: number) => {
      try {
        const result = await fetchResult(`/api/notifications/${id}/read`, {
          method: 'put',
        });

        if (ResultHelper.isSuccess(result)) {
          setNotifications((prev) =>
            prev.map((n) =>
              n.id === id ? { ...n, isRead: true, readAt: new Date().toISOString() } : n,
            ),
          );
          setUnreadCount((prev) => Math.max(0, prev - 1));
          showSuccessToast('Notification marked as read');
        } else {
          showErrorToast(result.desc || 'Failed to mark notification as read');
        }
      } catch (error) {
        console.error('Failed to mark notification as read:', error);
        showErrorToast('Failed to mark notification as read');
      }
    },
    [showSuccessToast, showErrorToast],
  );

  /**
   * Mark all notifications as read
   */
  const markAllAsRead = useCallback(async () => {
    try {
      const result = await fetchResult('/api/notifications/read-all', {
        method: 'put',
      });

      if (ResultHelper.isSuccess(result)) {
        setNotifications((prev) =>
          prev.map((n) => ({ ...n, isRead: true, readAt: n.readAt || new Date().toISOString() })),
        );
        setUnreadCount(0);
        showSuccessToast('已全部标为已读');
      } else {
        showErrorToast(result.desc || '标记已读失败');
      }
    } catch (error) {
      console.error('Failed to mark all notifications as read:', error);
      showErrorToast('标记已读失败');
    }
  }, [showSuccessToast, showErrorToast]);

  /**
   * Delete multiple notifications by ID.
   * Backend API: DELETE /api/notifications/batch with body { ids: [...] }
   */
  const deleteNotifications = useCallback(
    async (ids: number[]) => {
      if (ids.length === 0) return;

      try {
        const result = await fetchResult('/api/notifications/batch', {
          method: 'delete',
          params: { ids },
        });

        if (ResultHelper.isSuccess(result)) {
          // Remove deleted notifications from local state
          const deletedSet = new Set(ids);
          setNotifications((prev) => prev.filter((n) => !deletedSet.has(n.id)));
          setTotal((prev) => Math.max(0, prev - ids.length));
          // Recalculate unread count for deleted items
          const deletedUnreadCount = notifications.filter(
            (n) => deletedSet.has(n.id) && !n.isRead,
          ).length;
          if (deletedUnreadCount > 0) {
            setUnreadCount((prev) => Math.max(0, prev - deletedUnreadCount));
          }
          showSuccessToast(`已删除 ${ids.length} 条通知`);
        } else {
          showErrorToast(result.desc || '删除通知失败');
        }
      } catch (error) {
        console.error('Failed to delete notifications:', error);
        showErrorToast('删除通知失败');
      }
    },
    [notifications, showSuccessToast, showErrorToast],
  );

  return {
    notifications,
    loading,
    total,
    unreadCount,
    fetchNotifications,
    fetchUnreadCount,
    markAsRead,
    markAllAsRead,
    deleteNotifications,
  };
}
