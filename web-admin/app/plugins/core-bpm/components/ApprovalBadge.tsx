/**
 * ApprovalBadge — a header icon that shows the number of pending approval
 * tasks. Clicking it navigates to the BPM Task Center.
 *
 * Uses the same polling pattern as the notification bell in Header.tsx.
 */

import { useState, useEffect, useCallback } from 'react';
import { Link } from 'react-router';
import { ClipboardCheck } from 'lucide-react';
import { getTodoTasks } from '../services/bpmWorkbenchService';

interface ApprovalBadgeProps {
  /** Polling interval in ms (default: 60000 = 1 minute) */
  pollInterval?: number;
  className?: string;
}

export function ApprovalBadge({ pollInterval = 60_000, className }: ApprovalBadgeProps) {
  const [count, setCount] = useState(0);

  const fetchCount = useCallback(async () => {
    try {
      const tasks = await getTodoTasks();
      setCount(tasks.length);
    } catch {
      // Silently ignore — badge is non-critical
    }
  }, []);

  useEffect(() => {
    fetchCount();
    const timer = setInterval(fetchCount, pollInterval);
    return () => clearInterval(timer);
  }, [fetchCount, pollInterval]);

  return (
    <Link
      to="/bpm/task-center"
      className={`relative rounded-xl p-2.5 text-gray-500 transition-all duration-200 hover:scale-105 hover:bg-gray-100 hover:text-gray-700 hover:shadow-md dark:text-gray-400 dark:hover:bg-gray-700 dark:hover:text-gray-200 ${className || ''}`}
      title={count > 0 ? `${count} 条待审批` : '我的审批'}
    >
      <ClipboardCheck className="h-6 w-6" />
      {count > 0 && (
        <span className="absolute -top-1 -right-1 flex h-[18px] min-w-[18px] items-center justify-center rounded-full bg-orange-500 px-1 text-xs font-medium text-white shadow-sm">
          {count > 99 ? '99+' : count}
        </span>
      )}
    </Link>
  );
}
