import { useCallback, useEffect, useState } from 'react';
import { NotificationDropdown } from './NotificationDropdown';
import { fetchResult } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';

/**
 * Header entry point for the notification centre.
 *
 * `NotificationDropdown` is a controlled component — it takes `unreadCount` and
 * `onMarkAllRead` — so it needs an owner holding that state. No owner existed, which
 * is why a fully-built dropdown had zero imports and the shell shipped without a bell.
 *
 * State is kept here rather than via `useNotificationList` on purpose: that hook pulls
 * the whole list and depends on ToastProvider, neither of which a badge needs. This
 * mirrors `InboxHeaderWidget`, which talks to its service directly.
 *
 * The widget also subscribes to `GET /api/notifications/stream`. The backend has always
 * pushed `unread-count` frames over SSE — and `InAppChannel` skips the push when no
 * connection is registered — but nothing ever opened the stream, so "real-time
 * notifications" amounted to whatever the next manual refresh happened to fetch.
 */
export function NotificationHeaderWidget() {
  const [unreadCount, setUnreadCount] = useState(0);

  const refreshUnreadCount = useCallback(async () => {
    try {
      const result = await fetchResult<{ count: number }>('/api/notifications/unread-count', {
        method: 'get',
      });
      if (ResultHelper.isSuccess(result) && typeof result.data?.count === 'number') {
        setUnreadCount(result.data.count);
      }
    } catch {
      // A badge must never take the header down.
    }
  }, []);

  useEffect(() => {
    refreshUnreadCount();
  }, [refreshUnreadCount]);

  useEffect(() => {
    // EventSource is browser-only; keep SSR inert.
    if (typeof window === 'undefined' || typeof EventSource === 'undefined') return;

    const es = new EventSource('/api/notifications/stream', { withCredentials: true });

    const onUnread = (event: MessageEvent) => {
      try {
        const payload = JSON.parse(event.data) as { count?: number };
        if (typeof payload.count === 'number') setUnreadCount(payload.count);
      } catch {
        // A malformed frame must not break the stream handler.
      }
    };

    es.addEventListener('unread-count', onUnread as EventListener);
    es.onerror = () => {
      // EventSource retries on its own; only a terminal close is worth giving up on.
      if (es.readyState === EventSource.CLOSED) es.close();
    };

    return () => {
      es.removeEventListener('unread-count', onUnread as EventListener);
      es.close();
    };
  }, []);

  const handleMarkAllRead = useCallback(async () => {
    try {
      await fetchResult('/api/notifications/read-all', { method: 'put' });
    } finally {
      setUnreadCount(0);
      await refreshUnreadCount();
    }
  }, [refreshUnreadCount]);

  return (
    <NotificationDropdown
      unreadCount={unreadCount}
      onMarkAllRead={handleMarkAllRead}
      onCountChange={setUnreadCount}
    />
  );
}
