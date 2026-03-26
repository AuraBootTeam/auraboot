import { useState, useEffect, useCallback, useMemo } from 'react';
import { get, post } from '~/services/http-client';
import { ResultHelper } from '~/utils/type';
import { useI18n } from '~/contexts/I18nContext';
import { useToastContext } from '~/contexts/ToastContext';
import type { TaskRecord } from './TaskCard';

// ============================================================================
// Types
// ============================================================================

interface TaskListViewProps {
  projectId: string;
  onTaskClick: (task: TaskRecord) => void;
  onCreateTask: () => void;
  refreshKey?: number;
}

type SortField =
  | 'pm_task_title'
  | 'pm_task_type'
  | 'pm_task_status'
  | 'pm_task_priority'
  | 'pm_task_due_date'
  | 'pm_task_progress';

type SortDir = 'asc' | 'desc';

// ============================================================================
// Constants
// ============================================================================

const STATUS_STYLES: Record<
  string,
  { bg: string; text: string; label_zh: string; label_en: string }
> = {
  TODO: {
    bg: 'bg-gray-100 dark:bg-gray-700',
    text: 'text-gray-700 dark:text-gray-300',
    label_zh: '待办',
    label_en: 'To Do',
  },
  in_progress: {
    bg: 'bg-blue-100 dark:bg-blue-900/40',
    text: 'text-blue-700 dark:text-blue-300',
    label_zh: '进行中',
    label_en: 'In Progress',
  },
  DONE: {
    bg: 'bg-green-100 dark:bg-green-900/40',
    text: 'text-green-700 dark:text-green-300',
    label_zh: '已完成',
    label_en: 'Done',
  },
  cancelled: {
    bg: 'bg-red-100 dark:bg-red-900/40',
    text: 'text-red-700 dark:text-red-300',
    label_zh: '已取消',
    label_en: 'Cancelled',
  },
};

const TYPE_ICONS: Record<string, { icon: string; color: string }> = {
  EPIC: { icon: '\u26A1', color: 'text-purple-600 dark:text-purple-400' },
  STORY: { icon: '\u{1F4D6}', color: 'text-blue-600 dark:text-blue-400' },
  TASK: { icon: '\u2705', color: 'text-green-600 dark:text-green-400' },
  BUG: { icon: '\u{1F41B}', color: 'text-red-600 dark:text-red-400' },
  MILESTONE: { icon: '\u{1F3AF}', color: 'text-amber-600 dark:text-amber-400' },
};

const PRIORITY_COLORS: Record<string, string> = {
  CRITICAL: 'bg-red-500',
  HIGH: 'bg-orange-500',
  MEDIUM: 'bg-yellow-500',
  LOW: 'bg-blue-500',
  NONE: 'bg-gray-400',
};

const PRIORITY_ORDER: Record<string, number> = {
  CRITICAL: 0,
  HIGH: 1,
  MEDIUM: 2,
  LOW: 3,
  NONE: 4,
};

// ============================================================================
// Component
// ============================================================================

export default function TaskListView({
  projectId,
  onTaskClick,
  onCreateTask,
  refreshKey,
}: TaskListViewProps) {
  const { locale } = useI18n();
  const { showErrorToast } = useToastContext();
  const l = useCallback((zh: string, en: string) => (locale === 'zh-CN' ? zh : en), [locale]);

  const [tasks, setTasks] = useState<TaskRecord[]>([]);
  const [loading, setLoading] = useState(true);
  const [search, setSearch] = useState('');
  const [sortField, setSortField] = useState<SortField>('pm_task_title');
  const [sortDir, setSortDir] = useState<SortDir>('asc');
  const [filterPriority, setFilterPriority] = useState<string>('');
  const [filterStatus, setFilterStatus] = useState<string>('');

  // ------ Data fetching ------

  const fetchTasks = useCallback(async () => {
    setLoading(true);
    try {
      const result = await get<{ records: TaskRecord[]; total: number }>(
        '/api/dynamic/pm-task/list',
        {
          filters: JSON.stringify([
            { fieldName: 'pm_task_project_id', operator: 'EQ', value: projectId },
          ]),
          pageSize: '500',
        },
      );
      if (result.code === '0' && result.data) {
        setTasks(result.data.records ?? []);
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

  // ------ Inline edit handler ------
  const handleInlineUpdate = useCallback(
    async (taskPid: string, field: string, value: string) => {
      // Optimistic update
      setTasks((prev) => prev.map((t) => (t.pid === taskPid ? { ...t, [field]: value } : t)));
      try {
        const result = await post<unknown>('/api/meta/commands/execute/pm:update_task', {
          targetRecordId: taskPid,
          operationType: 'update',
          payload: { [field]: value },
        });
        if (!ResultHelper.isSuccess(result)) {
          showErrorToast(l('更新失败', 'Update failed'));
          fetchTasks();
        }
      } catch {
        showErrorToast(l('更新失败', 'Update failed'));
        fetchTasks();
      }
    },
    [fetchTasks, showErrorToast, l],
  );

  // ------ Sorting ------

  const handleSort = useCallback(
    (field: SortField) => {
      if (sortField === field) {
        setSortDir((d) => (d === 'asc' ? 'desc' : 'asc'));
      } else {
        setSortField(field);
        setSortDir('asc');
      }
    },
    [sortField],
  );

  // ------ Filtered + sorted rows ------

  const rows = useMemo(() => {
    let filtered = tasks;
    if (search.trim()) {
      const q = search.trim().toLowerCase();
      filtered = filtered.filter((t) => t.pm_task_title?.toLowerCase().includes(q));
    }
    if (filterPriority) {
      filtered = filtered.filter((t) => t.pm_task_priority === filterPriority);
    }
    if (filterStatus) {
      filtered = filtered.filter((t) => t.pm_task_status === filterStatus);
    }

    const sorted = [...filtered].sort((a, b) => {
      let cmp = 0;
      switch (sortField) {
        case 'pm_task_title':
          cmp = (a.pm_task_title ?? '').localeCompare(b.pm_task_title ?? '');
          break;
        case 'pm_task_type':
          cmp = (a.pm_task_type ?? '').localeCompare(b.pm_task_type ?? '');
          break;
        case 'pm_task_status':
          cmp = (a.pm_task_status ?? '').localeCompare(b.pm_task_status ?? '');
          break;
        case 'pm_task_priority':
          cmp =
            (PRIORITY_ORDER[a.pm_task_priority] ?? 99) - (PRIORITY_ORDER[b.pm_task_priority] ?? 99);
          break;
        case 'pm_task_due_date':
          cmp = (a.pm_task_due_date ?? '').localeCompare(b.pm_task_due_date ?? '');
          break;
        case 'pm_task_progress':
          cmp = (a.pm_task_progress ?? 0) - (b.pm_task_progress ?? 0);
          break;
      }
      return sortDir === 'asc' ? cmp : -cmp;
    });

    return sorted;
  }, [tasks, search, sortField, sortDir, filterPriority, filterStatus]);

  // ------ Column header helper ------

  const SortHeader = useCallback(
    ({
      field,
      children,
      className = '',
    }: {
      field: SortField;
      children: React.ReactNode;
      className?: string;
    }) => (
      <th
        className={`cursor-pointer px-4 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase transition-colors select-none hover:text-gray-700 dark:text-gray-400 dark:hover:text-gray-200 ${className}`}
        onClick={() => handleSort(field)}
        data-testid={`sort-${field}`}
      >
        <span className="inline-flex items-center gap-1">
          {children}
          {sortField === field && (
            <svg className="h-3.5 w-3.5" viewBox="0 0 20 20" fill="currentColor">
              {sortDir === 'asc' ? (
                <path
                  fillRule="evenodd"
                  d="M5.23 7.21a.75.75 0 011.06.02L10 11.168l3.71-3.938a.75.75 0 111.08 1.04l-4.25 4.5a.75.75 0 01-1.08 0l-4.25-4.5a.75.75 0 01.02-1.06z"
                  clipRule="evenodd"
                />
              ) : (
                <path
                  fillRule="evenodd"
                  d="M14.77 12.79a.75.75 0 01-1.06-.02L10 8.832 6.29 12.77a.75.75 0 11-1.08-1.04l4.25-4.5a.75.75 0 011.08 0l4.25 4.5a.75.75 0 01-.02 1.06z"
                  clipRule="evenodd"
                />
              )}
            </svg>
          )}
        </span>
      </th>
    ),
    [handleSort, sortField, sortDir],
  );

  // ------ Render ------

  return (
    <div data-testid="task-list-view" className="flex flex-col gap-4">
      {/* Toolbar */}
      <div className="flex flex-wrap items-center gap-3">
        {/* Search */}
        <div className="relative max-w-sm min-w-[200px] flex-1">
          <svg
            className="absolute top-1/2 left-3 h-4 w-4 -translate-y-1/2 text-gray-400"
            fill="none"
            viewBox="0 0 24 24"
            strokeWidth={1.5}
            stroke="currentColor"
          >
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              d="M21 21l-5.197-5.197m0 0A7.5 7.5 0 105.196 5.196a7.5 7.5 0 0010.607 10.607z"
            />
          </svg>
          <input
            type="text"
            data-testid="task-list-search"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder={l('搜索任务...', 'Search tasks...')}
            className="w-full rounded-lg border border-gray-300 bg-white py-2 pr-3 pl-9 text-sm text-gray-900 placeholder-gray-400 focus:border-transparent focus:ring-2 focus:ring-blue-500 focus:outline-none dark:border-gray-600 dark:bg-gray-800 dark:text-gray-100"
          />
        </div>

        {/* Filters */}
        <select
          data-testid="task-list-filter-priority"
          value={filterPriority}
          onChange={(e) => setFilterPriority(e.target.value)}
          className="rounded-lg border border-gray-300 bg-white px-2 py-2 text-sm text-gray-700 focus:ring-2 focus:ring-blue-500 focus:outline-none dark:border-gray-600 dark:bg-gray-800 dark:text-gray-300"
        >
          <option value="">{l('全部优先级', 'All Priorities')}</option>
          <option value="critical">{l('紧急', 'Critical')}</option>
          <option value="high">{l('高', 'High')}</option>
          <option value="medium">{l('中', 'Medium')}</option>
          <option value="low">{l('低', 'Low')}</option>
        </select>
        <select
          data-testid="task-list-filter-status"
          value={filterStatus}
          onChange={(e) => setFilterStatus(e.target.value)}
          className="rounded-lg border border-gray-300 bg-white px-2 py-2 text-sm text-gray-700 focus:ring-2 focus:ring-blue-500 focus:outline-none dark:border-gray-600 dark:bg-gray-800 dark:text-gray-300"
        >
          <option value="">{l('全部状态', 'All Statuses')}</option>
          <option value="todo">{l('待办', 'To Do')}</option>
          <option value="in_progress">{l('进行中', 'In Progress')}</option>
          <option value="done">{l('已完成', 'Done')}</option>
          <option value="cancelled">{l('已取消', 'Cancelled')}</option>
        </select>
        {(filterPriority || filterStatus) && (
          <button
            onClick={() => {
              setFilterPriority('');
              setFilterStatus('');
            }}
            className="flex-shrink-0 text-sm text-blue-600 hover:underline dark:text-blue-400"
            data-testid="task-list-filter-clear"
          >
            {l('清除', 'Clear')}
          </button>
        )}

        <div className="flex-1" />

        {/* New Task button */}
        <button
          data-testid="task-list-new-btn"
          onClick={onCreateTask}
          className="inline-flex flex-shrink-0 items-center gap-1.5 rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-blue-700"
        >
          <svg
            className="h-4 w-4"
            fill="none"
            viewBox="0 0 24 24"
            strokeWidth={2}
            stroke="currentColor"
          >
            <path strokeLinecap="round" strokeLinejoin="round" d="M12 4.5v15m7.5-7.5h-15" />
          </svg>
          {l('新建任务', 'New Task')}
        </button>
      </div>

      {/* Table */}
      {loading ? (
        <div data-testid="task-list-loading" className="flex items-center justify-center py-16">
          <div className="h-8 w-8 animate-spin rounded-full border-b-2 border-blue-600" />
        </div>
      ) : rows.length === 0 ? (
        <div
          data-testid="task-list-empty"
          className="flex flex-col items-center justify-center py-16 text-gray-500 dark:text-gray-400"
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
              d="M9 12h3.75M9 15h3.75M9 18h3.75m3 .75H18a2.25 2.25 0 002.25-2.25V6.108c0-1.135-.845-2.098-1.976-2.192a48.424 48.424 0 00-1.123-.08m-5.801 0c-.065.21-.1.433-.1.664 0 .414.336.75.75.75h4.5a.75.75 0 00.75-.75 2.25 2.25 0 00-.1-.664m-5.8 0A2.251 2.251 0 0113.5 2.25H15c1.012 0 1.867.668 2.15 1.586m-5.8 0c-.376.023-.75.05-1.124.08C9.095 4.01 8.25 4.973 8.25 6.108V8.25m0 0H4.875c-.621 0-1.125.504-1.125 1.125v11.25c0 .621.504 1.125 1.125 1.125h9.75c.621 0 1.125-.504 1.125-1.125V9.375c0-.621-.504-1.125-1.125-1.125H8.25zM6.75 12h.008v.008H6.75V12zm0 3h.008v.008H6.75V15zm0 3h.008v.008H6.75V18z"
            />
          </svg>
          <p className="text-sm">
            {search.trim()
              ? l('没有匹配的任务', 'No matching tasks')
              : l('暂无任务', 'No tasks yet')}
          </p>
        </div>
      ) : (
        <div className="overflow-x-auto rounded-lg border border-gray-200 bg-white shadow-sm dark:border-gray-700 dark:bg-gray-800">
          <table className="min-w-full divide-y divide-gray-200 dark:divide-gray-700">
            <thead className="bg-gray-50 dark:bg-gray-800/50">
              <tr>
                <SortHeader field="pm_task_type" className="w-24">
                  {l('类型', 'Type')}
                </SortHeader>
                <SortHeader field="pm_task_title">{l('标题', 'Title')}</SortHeader>
                <SortHeader field="pm_task_status" className="w-32">
                  {l('状态', 'Status')}
                </SortHeader>
                <SortHeader field="pm_task_priority" className="w-28">
                  {l('优先级', 'Priority')}
                </SortHeader>
                <th className="w-32 px-4 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase dark:text-gray-400">
                  {l('负责人', 'Assignee')}
                </th>
                <SortHeader field="pm_task_due_date" className="w-32">
                  {l('截止日期', 'Due Date')}
                </SortHeader>
                <SortHeader field="pm_task_progress" className="w-32">
                  {l('进度', 'Progress')}
                </SortHeader>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-200 dark:divide-gray-700">
              {rows.map((task) => {
                const statusStyle = STATUS_STYLES[task.pm_task_status] ?? STATUS_STYLES.TODO;
                const typeInfo = TYPE_ICONS[task.pm_task_type] ?? TYPE_ICONS.TASK;
                const priorityColor =
                  PRIORITY_COLORS[task.pm_task_priority] ?? PRIORITY_COLORS.NONE;
                const progress = task.pm_task_progress ?? 0;

                const isOverdue =
                  task.pm_task_due_date &&
                  task.pm_task_status !== 'done' &&
                  task.pm_task_status !== 'cancelled' &&
                  new Date(task.pm_task_due_date) < new Date();

                return (
                  <tr
                    key={task.pid}
                    data-testid={`task-row-${task.pid}`}
                    onClick={() => onTaskClick(task)}
                    className="cursor-pointer transition-colors hover:bg-gray-50 dark:hover:bg-gray-700/50"
                  >
                    {/* Type */}
                    <td className="px-4 py-3">
                      <span
                        className={`inline-flex items-center gap-1 text-xs font-medium ${typeInfo.color}`}
                      >
                        <span>{typeInfo.icon}</span>
                        <span>{task.pm_task_type}</span>
                      </span>
                    </td>

                    {/* Title */}
                    <td className="px-4 py-3">
                      <span className="line-clamp-1 text-sm font-medium text-gray-900 dark:text-gray-100">
                        {task.pm_task_title}
                      </span>
                    </td>

                    {/* Status */}
                    <td className="px-4 py-3">
                      <span
                        className={`inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium ${statusStyle.bg} ${statusStyle.text}`}
                      >
                        {l(statusStyle.label_zh, statusStyle.label_en)}
                      </span>
                    </td>

                    {/* Priority (inline edit) */}
                    <td className="px-4 py-3" onClick={(e) => e.stopPropagation()}>
                      <div className="inline-flex items-center gap-1.5">
                        <span className={`h-2 w-2 rounded-full ${priorityColor} flex-shrink-0`} />
                        <select
                          value={task.pm_task_priority}
                          onChange={(e) =>
                            handleInlineUpdate(task.pid, 'pm_task_priority', e.target.value)
                          }
                          className="cursor-pointer rounded border-none bg-transparent px-0 py-0 text-xs text-gray-700 focus:ring-1 focus:ring-blue-500 focus:outline-none dark:text-gray-300"
                          data-testid={`inline-priority-${task.pid}`}
                        >
                          <option value="critical">{l('紧急', 'Critical')}</option>
                          <option value="high">{l('高', 'High')}</option>
                          <option value="medium">{l('中', 'Medium')}</option>
                          <option value="low">{l('低', 'Low')}</option>
                          <option value="none">{l('无', 'None')}</option>
                        </select>
                      </div>
                    </td>

                    {/* Assignee */}
                    <td className="px-4 py-3 text-sm text-gray-600 dark:text-gray-400">
                      {task.pm_task_assignee_id || '-'}
                    </td>

                    {/* Due Date */}
                    <td className="px-4 py-3">
                      {task.pm_task_due_date ? (
                        <span
                          className={`text-xs ${
                            isOverdue
                              ? 'font-medium text-red-600 dark:text-red-400'
                              : 'text-gray-600 dark:text-gray-400'
                          }`}
                        >
                          {new Date(task.pm_task_due_date).toLocaleDateString(locale, {
                            month: 'short',
                            day: 'numeric',
                            year: 'numeric',
                          })}
                          {isOverdue && <span className="ml-1">({l('已逾期', 'Overdue')})</span>}
                        </span>
                      ) : (
                        <span className="text-xs text-gray-400">-</span>
                      )}
                    </td>

                    {/* Progress */}
                    <td className="px-4 py-3">
                      <div className="flex items-center gap-2">
                        <div className="h-1.5 flex-1 overflow-hidden rounded-full bg-gray-200 dark:bg-gray-700">
                          <div
                            className={`h-full rounded-full transition-all ${
                              progress >= 100
                                ? 'bg-green-500'
                                : progress >= 50
                                  ? 'bg-blue-500'
                                  : 'bg-amber-500'
                            }`}
                            style={{
                              width: `${Math.min(progress, 100)}%`,
                            }}
                          />
                        </div>
                        <span className="w-8 text-right text-xs text-gray-500 tabular-nums dark:text-gray-400">
                          {progress}%
                        </span>
                      </div>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}

      {/* Result count */}
      {!loading && rows.length > 0 && (
        <div className="text-xs text-gray-500 dark:text-gray-400">
          {search.trim()
            ? l(
                `${rows.length} / ${tasks.length} 条任务`,
                `${rows.length} of ${tasks.length} tasks`,
              )
            : l(`${tasks.length} 条任务`, `${tasks.length} tasks`)}
        </div>
      )}
    </div>
  );
}
