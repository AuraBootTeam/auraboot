import { renderHook, act } from '@testing-library/react';
import { describe, expect, it, vi, beforeEach } from 'vitest';
import type { ReactNode } from 'react';
import { I18nProvider } from '~/contexts/I18nContext';

const showSuccessToast = vi.fn();
const showErrorToast = vi.fn();

vi.mock('~/contexts/ToastContext', () => ({
  useToastContext: () => ({ showSuccessToast, showErrorToast }),
}));

vi.mock('~/shared/services/http-client', () => ({
  fetchResult: vi.fn(),
}));

vi.mock('~/utils/type', () => ({
  ResultHelper: {
    isSuccess: (r: { code: string }) => r.code === '0',
  },
}));

import { useNotificationList } from '../useNotificationList';
import { fetchResult } from '~/shared/services/http-client';

const mockFetch = fetchResult as ReturnType<typeof vi.fn>;

const ok = (data: unknown) => ({ code: '0', data, desc: '' });
const err = (desc = 'error') => ({ code: '1', data: null, desc });

describe('useNotificationList', () => {
  beforeEach(() => vi.clearAllMocks());

  it.each([
    ['zh-CN', '已标记为已读'],
    ['en-US', 'Notification marked as read'],
    ['zh-TW', '已標記為已讀'],
    ['ja', '通知を既読にしました'],
  ])('uses the current %s locale for the read receipt', async (locale, expected) => {
    mockFetch.mockResolvedValue(ok(null));
    const wrapper = ({ children }: { children: ReactNode }) => (
      <I18nProvider initialLocale={locale} initialData={{ ready: 'ready' }}>{children}</I18nProvider>
    );
    const { result } = renderHook(() => useNotificationList(), { wrapper });
    await act(async () => { await result.current.markAsRead(7); });
    expect(mockFetch).toHaveBeenCalledWith('/api/notifications/7/read', { method: 'put' });
    expect(showSuccessToast).toHaveBeenCalledWith(expected);
  });

  it.each([
    ['zh-CN', '标记已读失败'],
    ['en-US', 'Failed to mark notification as read'],
  ])('localizes fallback read failures in %s and preserves server explanations', async (locale, expected) => {
    const wrapper = ({ children }: { children: ReactNode }) => (
      <I18nProvider initialLocale={locale} initialData={{ ready: 'ready' }}>{children}</I18nProvider>
    );
    const { result } = renderHook(() => useNotificationList(), { wrapper });
    mockFetch.mockResolvedValue(err(''));
    await act(async () => { await result.current.markAsRead(7); });
    expect(showErrorToast).toHaveBeenLastCalledWith(expected);
    mockFetch.mockResolvedValue(err('Access is restricted'));
    await act(async () => { await result.current.markAsRead(7); });
    expect(showErrorToast).toHaveBeenLastCalledWith('Access is restricted');
    expect(showSuccessToast).not.toHaveBeenCalled();
  });

  it('localizes bulk read and deletion counts without changing their requests', async () => {
    const wrapper = ({ children }: { children: ReactNode }) => (
      <I18nProvider initialLocale="en-US" initialData={{ ready: 'ready' }}>{children}</I18nProvider>
    );
    const { result } = renderHook(() => useNotificationList(), { wrapper });
    mockFetch.mockResolvedValue(ok(null));
    await act(async () => { await result.current.markAllAsRead(); });
    expect(showSuccessToast).toHaveBeenLastCalledWith('All notifications marked as read');
    expect(mockFetch).toHaveBeenCalledWith('/api/notifications/read-all', { method: 'put' });
    await act(async () => { await result.current.deleteNotifications([7, 8]); });
    expect(showSuccessToast).toHaveBeenLastCalledWith('Deleted 2 notifications');
    expect(mockFetch).toHaveBeenCalledWith('/api/notifications/batch', {
      method: 'delete', params: { ids: [7, 8] },
    });
  });

  it('initial state', () => {
    const { result } = renderHook(() => useNotificationList());
    expect(result.current.notifications).toEqual([]);
    expect(result.current.loading).toBe(false);
    expect(result.current.total).toBe(0);
    expect(result.current.unreadCount).toBe(0);
  });

  it('fetchNotifications with paginated response shape', async () => {
    const records = [
      { id: 1, title: 'hello', isRead: false, category: 'system', priority: 'normal' },
    ];
    mockFetch.mockResolvedValue(ok({ records, total: 1 }));

    const { result } = renderHook(() => useNotificationList());
    await act(async () => {
      await result.current.fetchNotifications({ pageNum: 1, pageSize: 10 });
    });

    expect(result.current.notifications).toEqual(records);
    expect(result.current.total).toBe(1);
    expect(result.current.loading).toBe(false);
  });

  it('fetchNotifications with flat array response shape', async () => {
    const notifs = [
      { id: 2, title: 'flat', isRead: true, category: 'alert', priority: 'high' },
    ];
    mockFetch.mockResolvedValue(ok(notifs));

    const { result } = renderHook(() => useNotificationList());
    await act(async () => {
      await result.current.fetchNotifications({ pageNum: 1, pageSize: 10 });
    });

    expect(result.current.notifications).toEqual(notifs);
    expect(result.current.total).toBe(1);
  });

  it('fetchNotifications builds query params correctly', async () => {
    mockFetch.mockResolvedValue(ok({ records: [], total: 0 }));

    const { result } = renderHook(() => useNotificationList());
    await act(async () => {
      await result.current.fetchNotifications({
        pageNum: 2,
        pageSize: 5,
        category: 'approval',
        isRead: false,
      });
    });

    expect(mockFetch).toHaveBeenCalledWith(
      '/api/notifications',
      expect.objectContaining({
        params: expect.objectContaining({
          pageNum: '2',
          pageSize: '5',
          category: 'approval',
          isRead: 'false',
        }),
      }),
    );
  });

  it('fetchNotifications shows error toast on failure', async () => {
    mockFetch.mockResolvedValue(err('load failed'));

    const { result } = renderHook(() => useNotificationList());
    await act(async () => {
      await result.current.fetchNotifications({ pageNum: 1, pageSize: 10 });
    });

    expect(showErrorToast).toHaveBeenCalledWith('load failed');
  });

  it('fetchUnreadCount with numeric data shape', async () => {
    mockFetch.mockResolvedValue(ok(7));

    const { result } = renderHook(() => useNotificationList());
    await act(async () => {
      await result.current.fetchUnreadCount();
    });

    expect(result.current.unreadCount).toBe(7);
  });

  it('fetchUnreadCount with object data shape', async () => {
    mockFetch.mockResolvedValue(ok({ count: 3 }));

    const { result } = renderHook(() => useNotificationList());
    await act(async () => {
      await result.current.fetchUnreadCount();
    });

    expect(result.current.unreadCount).toBe(3);
  });

  it('markAsRead updates local state and decrements unreadCount', async () => {
    // seed state: 2 unread notifications
    const notifs = [
      { id: 1, title: 'a', isRead: false, category: 'system', priority: 'normal' },
      { id: 2, title: 'b', isRead: false, category: 'system', priority: 'normal' },
    ];
    mockFetch.mockResolvedValueOnce(ok(notifs));
    mockFetch.mockResolvedValueOnce(ok(2)); // unread count
    const { result } = renderHook(() => useNotificationList());
    await act(async () => {
      await result.current.fetchNotifications({ pageNum: 1, pageSize: 10 });
    });
    await act(async () => {
      await result.current.fetchUnreadCount();
    });

    expect(result.current.unreadCount).toBe(2);

    // Now mark id=1 as read
    mockFetch.mockResolvedValue(ok(null));
    await act(async () => {
      await result.current.markAsRead(1);
    });

    expect(result.current.notifications.find((n: { id: number; isRead: boolean }) => n.id === 1)?.isRead).toBe(true);
    expect(result.current.unreadCount).toBe(1);
    expect(showSuccessToast).toHaveBeenCalledWith('已标记为已读');
  });

  it('markAllAsRead sets all notifications to read and zeroes unread count', async () => {
    const notifs = [
      { id: 1, isRead: false },
      { id: 2, isRead: false },
    ];
    mockFetch.mockResolvedValueOnce(ok(notifs));
    const { result } = renderHook(() => useNotificationList());
    await act(async () => {
      await result.current.fetchNotifications({ pageNum: 1, pageSize: 10 });
    });

    mockFetch.mockResolvedValue(ok(null));
    await act(async () => {
      await result.current.markAllAsRead();
    });

    expect(result.current.notifications.every((n: { isRead: boolean }) => n.isRead)).toBe(true);
    expect(result.current.unreadCount).toBe(0);
  });

  it('deleteNotifications removes items from local state', async () => {
    const notifs = [
      { id: 1, isRead: true },
      { id: 2, isRead: false },
      { id: 3, isRead: true },
    ];
    mockFetch.mockResolvedValueOnce(ok(notifs));
    const { result } = renderHook(() => useNotificationList());
    await act(async () => {
      await result.current.fetchNotifications({ pageNum: 1, pageSize: 10 });
    });

    mockFetch.mockResolvedValue(ok(null));
    await act(async () => {
      await result.current.deleteNotifications([1, 3]);
    });

    expect(result.current.notifications).toEqual([{ id: 2, isRead: false }]);
    expect(result.current.total).toBe(1);
  });

  it('deleteNotifications is a no-op for empty array', async () => {
    const { result } = renderHook(() => useNotificationList());
    await act(async () => {
      await result.current.deleteNotifications([]);
    });
    expect(mockFetch).not.toHaveBeenCalled();
  });
});
