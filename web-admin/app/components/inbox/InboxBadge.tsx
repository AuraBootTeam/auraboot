/**
 * InboxBadge — header icon showing unified inbox unread count.
 * Replaces ApprovalBadge with unified inbox data source.
 * Clicking opens InboxDropdown (managed by parent).
 */

import { useState, useEffect, useCallback } from 'react';
import { InboxIcon } from '@heroicons/react/24/outline';
import { getUnreadCount } from '~/services/inboxService';

interface InboxBadgeProps {
  onClick?: () => void;
  isOpen?: boolean;
  pollInterval?: number;
  className?: string;
}

export function InboxBadge({ onClick, isOpen, pollInterval = 30_000, className }: InboxBadgeProps) {
  const [count, setCount] = useState(0);

  const fetchCount = useCallback(async () => {
    try {
      const c = await getUnreadCount();
      setCount(typeof c === 'number' ? c : 0);
    } catch {
      // Non-critical
    }
  }, []);

  useEffect(() => {
    fetchCount();
    const timer = setInterval(fetchCount, pollInterval);
    return () => clearInterval(timer);
  }, [fetchCount, pollInterval]);

  // Listen for SSE inbox updates
  useEffect(() => {
    const handler = (e: Event) => {
      const detail = (e as CustomEvent).detail;
      if (detail?.type === 'inbox' && typeof detail?.count === 'number') {
        setCount(detail.count);
      }
    };
    window.addEventListener('aura:inbox-update', handler);
    return () => window.removeEventListener('aura:inbox-update', handler);
  }, []);

  return (
    <button
      onClick={onClick}
      data-testid="inbox-badge"
      className={`relative rounded-xl p-2.5 transition-all duration-200 hover:scale-105 hover:shadow-md ${
        isOpen
          ? 'bg-blue-50 text-blue-600 dark:bg-blue-900/20 dark:text-blue-400'
          : 'text-gray-500 hover:bg-gray-100 hover:text-gray-700 dark:text-gray-400 dark:hover:bg-gray-700 dark:hover:text-gray-200'
      } ${className || ''}`}
      title={count > 0 ? `${count} unread items` : 'Inbox'}
    >
      <InboxIcon className="h-5 w-5" />
      {count > 0 && (
        <span className="absolute -top-1 -right-1 flex h-[18px] min-w-[18px] items-center justify-center rounded-full bg-red-500 px-1 text-xs font-medium text-white shadow-sm">
          {count > 99 ? '99+' : count}
        </span>
      )}
    </button>
  );
}
