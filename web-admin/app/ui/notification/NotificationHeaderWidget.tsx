import { useCallback, useEffect, useRef, useState } from 'react';
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
 * Header owns the single managed SSE connection and remounts this widget at every
 * tenant/actor boundary. A delayed initial count cannot overwrite a newer frame.
 */
export function NotificationHeaderWidget({ streamCount }: { streamCount?: number }) {
  const [unreadCount, setUnreadCount] = useState(0);
  const revision = useRef(0);
  const active = useRef(true);
  useEffect(() => {
    active.current = true;
    return () => { active.current = false; revision.current++; };
  }, []);

  useEffect(() => {
    if (Number.isSafeInteger(streamCount) && streamCount! >= 0) {
      revision.current++;
      setUnreadCount(streamCount!);
    }
  }, [streamCount]);

  const refreshUnreadCount = useCallback(async () => {
    const requestRevision = ++revision.current;
    try {
      const result = await fetchResult<{ count: number }>('/api/notifications/unread-count', {
        method: 'get',
      });
      const count = result.data?.count;
      if (active.current && requestRevision === revision.current && ResultHelper.isSuccess(result)
        && typeof count === 'number' && Number.isSafeInteger(count) && count >= 0) {
        setUnreadCount(count);
      }
    } catch {
      // A badge must never take the header down.
    }
  }, []);

  useEffect(() => {
    refreshUnreadCount();
  }, [refreshUnreadCount]);

  const handleMarkAllRead = useCallback(async () => {
    try {
      const result = await fetchResult('/api/notifications/read-all', { method: 'put' });
      if (!ResultHelper.isSuccess(result)) throw new Error('Notification read state was not confirmed');
      revision.current++;
      if (active.current) setUnreadCount(0);
    } finally {
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
