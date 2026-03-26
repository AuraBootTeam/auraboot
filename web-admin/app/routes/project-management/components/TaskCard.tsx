import { useCallback, useMemo, useState } from 'react';
import { useI18n } from '~/contexts/I18nContext';
import type { TaskRecord } from './types';

export type { TaskRecord };

interface TaskCardProps {
  task: TaskRecord;
  onClick: (task: TaskRecord) => void;
  isDragging?: boolean;
}

// ============================================================================
// Constants
// ============================================================================

const TYPE_STYLES: Record<
  string,
  { bg: string; text: string; label_zh: string; label_en: string }
> = {
  EPIC: {
    bg: 'bg-purple-100 dark:bg-purple-900/40',
    text: 'text-purple-700 dark:text-purple-300',
    label_zh: 'Epic',
    label_en: 'Epic',
  },
  STORY: {
    bg: 'bg-blue-100 dark:bg-blue-900/40',
    text: 'text-blue-700 dark:text-blue-300',
    label_zh: 'Story',
    label_en: 'Story',
  },
  TASK: {
    bg: 'bg-green-100 dark:bg-green-900/40',
    text: 'text-green-700 dark:text-green-300',
    label_zh: 'Task',
    label_en: 'Task',
  },
  BUG: {
    bg: 'bg-red-100 dark:bg-red-900/40',
    text: 'text-red-700 dark:text-red-300',
    label_zh: 'Bug',
    label_en: 'Bug',
  },
  MILESTONE: {
    bg: 'bg-amber-100 dark:bg-amber-900/40',
    text: 'text-amber-700 dark:text-amber-300',
    label_zh: 'Milestone',
    label_en: 'Milestone',
  },
};

const PRIORITY_COLORS: Record<string, string> = {
  CRITICAL: 'bg-red-500',
  HIGH: 'bg-orange-500',
  MEDIUM: 'bg-yellow-500',
  LOW: 'bg-blue-500',
  NONE: 'bg-gray-400',
};

// ============================================================================
// Component
// ============================================================================

export default function TaskCard({ task, onClick, isDragging }: TaskCardProps) {
  const { locale } = useI18n();
  const l = useCallback((zh: string, en: string) => (locale === 'zh-CN' ? zh : en), [locale]);

  const typeStyle = TYPE_STYLES[task.pm_task_type] ?? TYPE_STYLES.TASK;
  const priorityColor = PRIORITY_COLORS[task.pm_task_priority] ?? PRIORITY_COLORS.NONE;

  const isOverdue = useMemo(() => {
    if (!task.pm_task_due_date) return false;
    return new Date(task.pm_task_due_date) < new Date();
  }, [task.pm_task_due_date]);

  const formattedDueDate = useMemo(() => {
    if (!task.pm_task_due_date) return null;
    const d = new Date(task.pm_task_due_date);
    return d.toLocaleDateString(locale, { month: 'short', day: 'numeric' });
  }, [task.pm_task_due_date, locale]);

  const progress = task.pm_task_progress ?? 0;

  return (
    <div
      data-testid={`task-card-${task.pid}`}
      className={`cursor-pointer rounded-lg border border-gray-200 bg-white p-3 shadow-sm transition-all duration-150 hover:border-gray-300 hover:shadow-md dark:border-gray-700 dark:bg-gray-800 dark:hover:border-gray-600 ${isDragging ? 'opacity-50 ring-2 ring-blue-400' : ''} `}
      onClick={() => onClick(task)}
    >
      {/* Title */}
      <p
        data-testid="task-card-title"
        className="mb-2 line-clamp-2 text-sm font-medium text-gray-900 dark:text-gray-100"
      >
        {task.pm_task_title}
      </p>

      {/* Type badge + Priority dot */}
      <div className="mb-2 flex items-center gap-2">
        <span
          data-testid="task-card-type"
          className={`inline-flex items-center rounded px-1.5 py-0.5 text-xs font-medium ${typeStyle.bg} ${typeStyle.text}`}
        >
          {l(typeStyle.label_zh, typeStyle.label_en)}
        </span>

        <span
          data-testid="task-card-priority"
          className={`h-2 w-2 rounded-full ${priorityColor} flex-shrink-0`}
          title={l(`${task.pm_task_priority}`, `${task.pm_task_priority}`)}
        />
      </div>

      {/* Due date */}
      {formattedDueDate && (
        <div
          data-testid="task-card-due-date"
          className={`mb-2 flex items-center gap-1 text-xs ${
            isOverdue
              ? 'font-medium text-red-600 dark:text-red-400'
              : 'text-gray-500 dark:text-gray-400'
          }`}
        >
          <svg
            className="h-3.5 w-3.5 flex-shrink-0"
            fill="none"
            viewBox="0 0 24 24"
            strokeWidth={1.5}
            stroke="currentColor"
          >
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              d="M6.75 3v2.25M17.25 3v2.25M3 18.75V7.5a2.25 2.25 0 012.25-2.25h13.5A2.25 2.25 0 0121 7.5v11.25m-18 0A2.25 2.25 0 005.25 21h13.5A2.25 2.25 0 0021 18.75m-18 0v-7.5A2.25 2.25 0 015.25 9h13.5A2.25 2.25 0 0121 11.25v7.5"
            />
          </svg>
          <span>{formattedDueDate}</span>
          {isOverdue && <span>({l('已逾期', 'Overdue')})</span>}
        </div>
      )}

      {/* Progress bar */}
      {progress > 0 && (
        <div data-testid="task-card-progress" className="mt-1">
          <div className="mb-1 flex items-center justify-between text-xs text-gray-500 dark:text-gray-400">
            <span>{l('进度', 'Progress')}</span>
            <span>{progress}%</span>
          </div>
          <div className="h-1.5 w-full overflow-hidden rounded-full bg-gray-200 dark:bg-gray-700">
            <div
              className={`h-full rounded-full transition-all duration-300 ${
                progress >= 100 ? 'bg-green-500' : progress >= 50 ? 'bg-blue-500' : 'bg-amber-500'
              }`}
              style={{ width: `${Math.min(progress, 100)}%` }}
            />
          </div>
        </div>
      )}
    </div>
  );
}
