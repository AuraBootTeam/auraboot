import { useState, useEffect, useCallback, useMemo } from 'react';
import { Gantt, ViewMode, type Task as GanttTask } from 'gantt-task-react';
import 'gantt-task-react/dist/index.css';
import { get, post } from '~/services/http-client';
import { ResultHelper } from '~/utils/type';
import { useI18n } from '~/contexts/I18nContext';
import { useToastContext } from '~/contexts/ToastContext';
import type { TaskRecord } from './TaskCard';

// ============================================================================
// Types
// ============================================================================

interface TaskGanttViewProps {
  projectId: string;
  onTaskClick: (task: TaskRecord) => void;
  refreshKey?: number;
}

type GanttViewMode = 'Day' | 'Week' | 'Month';

// Extend TaskRecord to include created_at which may come from the API
interface TaskRecordWithDates extends TaskRecord {
  pm_task_start_date?: string;
  created_at?: string;
}

// ============================================================================
// Constants
// ============================================================================

const VIEW_MODE_MAP: Record<GanttViewMode, ViewMode> = {
  Day: ViewMode.Day,
  Week: ViewMode.Week,
  Month: ViewMode.Month,
};

const STATUS_COLORS: Record<
  string,
  { progress: string; progressSelected: string; bg: string; bgSelected: string }
> = {
  TODO: {
    progress: '#9CA3AF',
    progressSelected: '#6B7280',
    bg: '#D1D5DB',
    bgSelected: '#9CA3AF',
  },
  in_progress: {
    progress: '#3B82F6',
    progressSelected: '#2563EB',
    bg: '#93C5FD',
    bgSelected: '#60A5FA',
  },
  DONE: {
    progress: '#22C55E',
    progressSelected: '#16A34A',
    bg: '#86EFAC',
    bgSelected: '#4ADE80',
  },
  cancelled: {
    progress: '#EF4444',
    progressSelected: '#DC2626',
    bg: '#FCA5A5',
    bgSelected: '#F87171',
  },
};

const DEFAULT_COLORS = STATUS_COLORS.TODO;

const LEGEND_COLORS = {
  TODO: STATUS_COLORS.TODO,
  IN_PROGRESS: STATUS_COLORS.in_progress,
  DONE: STATUS_COLORS.DONE,
  CANCELLED: STATUS_COLORS.cancelled,
} as const;

// ============================================================================
// Helpers
// ============================================================================

function addDays(date: Date, days: number): Date {
  const result = new Date(date);
  result.setDate(result.getDate() + days);
  return result;
}

function toGanttTaskType(taskType: string): 'task' | 'milestone' | 'project' {
  if (taskType === 'milestone') return 'milestone';
  if (taskType === 'epic') return 'project';
  return 'task';
}

// ============================================================================
// Component
// ============================================================================

export default function TaskGanttView({ projectId, onTaskClick, refreshKey }: TaskGanttViewProps) {
  const { locale } = useI18n();
  const { showErrorToast } = useToastContext();
  const l = useCallback((zh: string, en: string) => (locale === 'zh-CN' ? zh : en), [locale]);

  const [allTasks, setAllTasks] = useState<TaskRecordWithDates[]>([]);
  const [loading, setLoading] = useState(true);
  const [viewMode, setViewMode] = useState<GanttViewMode>('Week');

  // ------ Data fetching ------

  const fetchTasks = useCallback(async () => {
    setLoading(true);
    try {
      const result = await get<{ records: TaskRecordWithDates[]; total: number }>(
        '/api/dynamic/pm-task/list',
        {
          filters: JSON.stringify([
            { fieldName: 'pm_task_project_id', operator: 'EQ', value: projectId },
          ]),
          pageSize: '500',
        },
      );
      if (result.code === '0' && result.data) {
        setAllTasks(result.data.records ?? []);
      }
    } catch {
      // silent
    } finally {
      setLoading(false);
    }
  }, [projectId]);

  useEffect(() => {
    fetchTasks();
  }, [fetchTasks, refreshKey]);

  // ------ Build Gantt tasks ------

  const { ganttTasks, hiddenCount, taskMap } = useMemo(() => {
    const mapped: GanttTask[] = [];
    const tMap = new Map<string, TaskRecordWithDates>();
    let hidden = 0;

    for (const task of allTasks) {
      const hasStart = task.pm_task_start_date != null && task.pm_task_start_date !== '';
      const hasDue = task.pm_task_due_date != null && task.pm_task_due_date !== '';
      const hasCreated = task.created_at != null && task.created_at !== '';

      // Must have at least one date to render
      if (!hasStart && !hasDue && !hasCreated) {
        hidden++;
        continue;
      }

      const startDate = hasStart
        ? new Date(task.pm_task_start_date!)
        : hasCreated
          ? new Date(task.created_at!)
          : new Date();

      if (isNaN(startDate.getTime())) {
        hidden++;
        continue;
      }

      let endDate = hasDue ? new Date(task.pm_task_due_date!) : addDays(startDate, 7);
      if (isNaN(endDate.getTime())) {
        endDate = addDays(startDate, 7);
      }

      // Ensure end >= start
      if (endDate < startDate) {
        endDate = startDate;
      }

      const colors = STATUS_COLORS[task.pm_task_status] ?? DEFAULT_COLORS;

      tMap.set(task.pid, task);

      mapped.push({
        id: task.pid,
        name: task.pm_task_title || 'Untitled',
        start: startDate,
        end: endDate,
        progress: task.pm_task_progress ?? 0,
        type: toGanttTaskType(task.pm_task_type),
        dependencies: [],
        isDisabled: false,
        styles: {
          progressColor: colors.progress,
          progressSelectedColor: colors.progressSelected,
          backgroundColor: colors.bg,
          backgroundSelectedColor: colors.bgSelected,
        },
      });
    }

    return { ganttTasks: mapped, hiddenCount: hidden, taskMap: tMap };
  }, [allTasks]);

  // ------ Handlers ------

  const handleTaskClick = useCallback(
    (ganttTask: GanttTask) => {
      const record = taskMap.get(ganttTask.id);
      if (record) {
        onTaskClick(record);
      }
    },
    [taskMap, onTaskClick],
  );

  // ------ Date change handler (drag to adjust dates) ------

  const handleDateChange = useCallback(
    async (ganttTask: GanttTask) => {
      const record = taskMap.get(ganttTask.id);
      if (!record) return;

      const fmtDate = (d: Date) => d.toISOString().slice(0, 10);
      const newStart = fmtDate(ganttTask.start);
      const newEnd = fmtDate(ganttTask.end);

      // Optimistic update
      setAllTasks((prev) =>
        prev.map((t) =>
          t.pid === ganttTask.id
            ? { ...t, pm_task_start_date: newStart, pm_task_due_date: newEnd }
            : t,
        ),
      );

      try {
        const result = await post<unknown>('/api/meta/commands/execute/pm:update_task', {
          targetRecordId: ganttTask.id,
          operationType: 'update',
          payload: {
            pm_task_start_date: newStart,
            pm_task_due_date: newEnd,
          },
        });
        if (!ResultHelper.isSuccess(result)) {
          showErrorToast(l('日期更新失败', 'Failed to update dates'));
          fetchTasks();
        }
      } catch {
        showErrorToast(l('日期更新失败', 'Failed to update dates'));
        fetchTasks();
      }
    },
    [taskMap, fetchTasks, showErrorToast, l],
  );

  // ------ Column width based on view mode ------

  const columnWidth = useMemo(() => {
    switch (viewMode) {
      case 'Month':
        return 300;
      case 'Week':
        return 250;
      default:
        return 65;
    }
  }, [viewMode]);

  // ------ Render ------

  return (
    <div data-testid="task-gantt-view" className="flex flex-col gap-4">
      {/* Toolbar */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <span className="text-sm text-gray-600 dark:text-gray-400">
            {ganttTasks.length} {l('条任务', 'tasks')}
          </span>
          {hiddenCount > 0 && (
            <span
              data-testid="gantt-hidden-count"
              className="rounded bg-amber-50 px-2 py-0.5 text-xs text-amber-600 dark:bg-amber-900/30 dark:text-amber-400"
            >
              {l(
                `${hiddenCount} 条无日期任务已隐藏`,
                `${hiddenCount} tasks without dates are hidden`,
              )}
            </span>
          )}
          {loading && (
            <span className="h-4 w-4 animate-spin rounded-full border-2 border-blue-200 border-t-blue-600" />
          )}
        </div>

        {/* View mode toggle */}
        <div className="flex items-center gap-1 rounded-lg bg-gray-100 p-0.5 dark:bg-gray-700">
          {(['Day', 'Week', 'Month'] as GanttViewMode[]).map((mode) => (
            <button
              key={mode}
              type="button"
              data-testid={`gantt-mode-${mode.toLowerCase()}`}
              onClick={() => setViewMode(mode)}
              className={`rounded-md px-3 py-1.5 text-xs font-medium transition-colors ${
                viewMode === mode
                  ? 'bg-white text-gray-900 shadow-sm dark:bg-gray-600 dark:text-white'
                  : 'text-gray-500 hover:text-gray-700 dark:text-gray-400 dark:hover:text-gray-200'
              }`}
            >
              {mode === 'Day'
                ? l('日', 'Day')
                : mode === 'Week'
                  ? l('周', 'Week')
                  : l('月', 'Month')}
            </button>
          ))}
        </div>
      </div>

      {/* Chart area */}
      {loading ? (
        <div data-testid="gantt-loading" className="flex items-center justify-center py-16">
          <div className="h-8 w-8 animate-spin rounded-full border-b-2 border-blue-600" />
        </div>
      ) : ganttTasks.length === 0 ? (
        <div
          data-testid="gantt-empty"
          className="flex flex-col items-center justify-center rounded-lg border border-gray-200 bg-white py-16 dark:border-gray-700 dark:bg-gray-800"
        >
          <svg
            className="mb-3 h-12 w-12 text-gray-300 dark:text-gray-600"
            fill="none"
            viewBox="0 0 24 24"
            strokeWidth={1}
            stroke="currentColor"
          >
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              d="M3 13.125C3 12.504 3.504 12 4.125 12h2.25c.621 0 1.125.504 1.125 1.125v6.75C7.5 20.496 6.996 21 6.375 21h-2.25A1.125 1.125 0 013 19.875v-6.75zM9.75 8.625c0-.621.504-1.125 1.125-1.125h2.25c.621 0 1.125.504 1.125 1.125v11.25c0 .621-.504 1.125-1.125 1.125h-2.25a1.125 1.125 0 01-1.125-1.125V8.625zM16.5 4.125c0-.621.504-1.125 1.125-1.125h2.25C20.496 3 21 3.504 21 4.125v15.75c0 .621-.504 1.125-1.125 1.125h-2.25a1.125 1.125 0 01-1.125-1.125V4.125z"
            />
          </svg>
          <p className="text-sm text-gray-500 dark:text-gray-400">
            {allTasks.length === 0
              ? l('暂无任务', 'No tasks yet')
              : l(
                  '所有任务均缺少日期信息，无法显示甘特图',
                  'All tasks are missing date information for Gantt display',
                )}
          </p>
        </div>
      ) : (
        <div className="overflow-x-auto rounded-lg border border-gray-200 bg-white dark:border-gray-700 dark:bg-gray-800">
          <Gantt
            tasks={ganttTasks}
            viewMode={VIEW_MODE_MAP[viewMode]}
            onClick={handleTaskClick}
            onDoubleClick={handleTaskClick}
            onDateChange={handleDateChange}
            columnWidth={columnWidth}
            listCellWidth="155px"
            rowHeight={42}
            barCornerRadius={4}
            barFill={65}
            fontSize="12px"
            todayColor="rgba(59, 130, 246, 0.06)"
            locale={locale === 'zh-CN' ? 'zh' : 'en'}
          />
        </div>
      )}

      {/* Legend */}
      {!loading && ganttTasks.length > 0 && (
        <div className="flex items-center gap-4 text-xs text-gray-500 dark:text-gray-400">
          <span className="flex items-center gap-1.5">
            <span
              className="h-2 w-3 rounded-sm"
              style={{ backgroundColor: LEGEND_COLORS.TODO.progress }}
            />
            {l('待办', 'To Do')}
          </span>
          <span className="flex items-center gap-1.5">
            <span
              className="h-2 w-3 rounded-sm"
              style={{ backgroundColor: LEGEND_COLORS.IN_PROGRESS.progress }}
            />
            {l('进行中', 'In Progress')}
          </span>
          <span className="flex items-center gap-1.5">
            <span
              className="h-2 w-3 rounded-sm"
              style={{ backgroundColor: LEGEND_COLORS.DONE.progress }}
            />
            {l('已完成', 'Done')}
          </span>
          <span className="flex items-center gap-1.5">
            <span
              className="h-2 w-3 rounded-sm"
              style={{ backgroundColor: LEGEND_COLORS.CANCELLED.progress }}
            />
            {l('已取消', 'Cancelled')}
          </span>
        </div>
      )}
    </div>
  );
}
