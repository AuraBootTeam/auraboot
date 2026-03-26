import { useState, useEffect, useCallback, useMemo } from 'react';
import { useNavigate } from 'react-router';
import { get } from '~/services/http-client';
import { ResultHelper } from '~/utils/type';
import { useI18n } from '~/contexts/I18nContext';
import { STATUS_COLORS, PRIORITY_COLORS, TYPE_COLORS } from './components/types';

// ============================================================================
// Types
// ============================================================================

interface MyTaskRecord {
  pid: string;
  pm_task_title: string;
  pm_task_status: string;
  pm_task_priority: string;
  pm_task_type: string;
  pm_task_due_date?: string;
  pm_task_progress?: number;
  pm_mt_project_name?: string;
  pm_mt_project_id?: string;
}

type GroupBy = 'project' | 'status' | 'due_date';
type StatusFilter = 'all' | 'todo' | 'in_progress' | 'done';

// ============================================================================
// Helpers
// ============================================================================

function groupByKey(tasks: MyTaskRecord[], groupBy: GroupBy): Record<string, MyTaskRecord[]> {
  const groups: Record<string, MyTaskRecord[]> = {};
  for (const task of tasks) {
    let key: string;
    switch (groupBy) {
      case 'project':
        key = task.pm_mt_project_name || 'Unknown';
        break;
      case 'status':
        key = task.pm_task_status || 'unknown';
        break;
      case 'due_date': {
        if (!task.pm_task_due_date) {
          key = 'No Due Date';
        } else {
          const d = new Date(task.pm_task_due_date);
          const today = new Date();
          today.setHours(0, 0, 0, 0);
          const diff = Math.ceil((d.getTime() - today.getTime()) / (1000 * 60 * 60 * 24));
          if (diff < 0) key = 'Overdue';
          else if (diff === 0) key = 'Today';
          else if (diff <= 7) key = 'This Week';
          else key = 'Later';
        }
        break;
      }
      default:
        key = 'Other';
    }
    if (!groups[key]) groups[key] = [];
    groups[key].push(task);
  }
  return groups;
}

// ============================================================================
// Component
// ============================================================================

export default function MyTasksPage() {
  const navigate = useNavigate();
  const { locale } = useI18n();

  const l = useCallback((zh: string, en: string) => (locale === 'zh-CN' ? zh : en), [locale]);

  const [tasks, setTasks] = useState<MyTaskRecord[]>([]);
  const [loading, setLoading] = useState(true);
  const [search, setSearch] = useState('');
  const [statusFilter, setStatusFilter] = useState<StatusFilter>('all');
  const [groupBy, setGroupBy] = useState<GroupBy>('project');

  // ------ Load tasks ------

  const loadTasks = useCallback(async () => {
    setLoading(true);
    try {
      const result = await get<{ records: MyTaskRecord[]; total: number }>('/api/datasource/list', {
        datasourceId: 'nq:pm_my_tasks',
        format: 'records',
        maxItems: 200,
      });
      if (ResultHelper.isSuccess(result) && result.data?.records) {
        setTasks(result.data.records);
      } else {
        setTasks([]);
      }
    } catch {
      setTasks([]);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadTasks();
  }, [loadTasks]);

  // ------ Filtered & grouped ------

  const filteredTasks = useMemo(() => {
    let list = tasks;

    // Status filter
    if (statusFilter !== 'all') {
      list = list.filter((t) => t.pm_task_status === statusFilter);
    }

    // Search filter (local, by title)
    if (search.trim()) {
      const q = search.trim().toLowerCase();
      list = list.filter((t) => t.pm_task_title?.toLowerCase().includes(q));
    }

    return list;
  }, [tasks, statusFilter, search]);

  const grouped = useMemo(() => groupByKey(filteredTasks, groupBy), [filteredTasks, groupBy]);

  // ------ Row click ------

  const handleRowClick = useCallback(
    (task: MyTaskRecord) => {
      if (task.pm_mt_project_id) {
        navigate(`/project-management/projects/${task.pm_mt_project_id}?task=${task.pid}`);
      }
    },
    [navigate],
  );

  // ------ Status filter buttons ------

  const statusOptions: { key: StatusFilter; label: string }[] = [
    { key: 'all', label: l('全部', 'All') },
    { key: 'todo', label: l('待办', 'To Do') },
    { key: 'in_progress', label: l('进行中', 'In Progress') },
    { key: 'done', label: l('已完成', 'Done') },
  ];

  const groupOptions: { key: GroupBy; label: string }[] = [
    { key: 'project', label: l('按项目', 'By Project') },
    { key: 'status', label: l('按状态', 'By Status') },
    { key: 'due_date', label: l('按截止日期', 'By Due Date') },
  ];

  // ------ Render ------

  return (
    <div className="flex h-full flex-col bg-gray-50 dark:bg-gray-900" data-testid="my-tasks-page">
      {/* Header */}
      <div className="border-b border-gray-200 bg-white px-6 py-5 dark:border-gray-700 dark:bg-gray-800">
        <h1
          className="text-xl font-bold text-gray-900 dark:text-white"
          data-testid="my-tasks-title"
        >
          {l('我的任务', 'My Tasks')}
        </h1>
      </div>

      {/* Toolbar */}
      <div className="flex flex-wrap items-center gap-3 border-b border-gray-200 bg-white px-6 py-3 dark:border-gray-700 dark:bg-gray-800">
        {/* Search */}
        <div className="relative max-w-sm min-w-[200px] flex-1">
          <svg
            className="absolute top-1/2 left-3 h-4 w-4 -translate-y-1/2 text-gray-400"
            fill="none"
            viewBox="0 0 24 24"
            stroke="currentColor"
          >
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={2}
              d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z"
            />
          </svg>
          <input
            type="text"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder={l('搜索任务...', 'Search tasks...')}
            className="w-full rounded-lg border border-gray-300 bg-white py-2 pr-3 pl-9 text-sm text-gray-900 focus:border-transparent focus:ring-2 focus:ring-blue-500 dark:border-gray-600 dark:bg-gray-700 dark:text-white"
            data-testid="my-tasks-search"
          />
        </div>

        {/* Status filter */}
        <div
          className="flex items-center gap-1 rounded-lg bg-gray-100 p-0.5 dark:bg-gray-700"
          data-testid="status-filter"
        >
          {statusOptions.map((opt) => (
            <button
              key={opt.key}
              onClick={() => setStatusFilter(opt.key)}
              className={`rounded-md px-3 py-1.5 text-xs font-medium transition-colors ${
                statusFilter === opt.key
                  ? 'bg-white text-gray-900 shadow-sm dark:bg-gray-600 dark:text-white'
                  : 'text-gray-600 hover:text-gray-800 dark:text-gray-400 dark:hover:text-gray-200'
              }`}
              data-testid={`filter-${opt.key.toLowerCase()}`}
            >
              {opt.label}
            </button>
          ))}
        </div>

        {/* Group by */}
        <div
          className="flex items-center gap-1 rounded-lg bg-gray-100 p-0.5 dark:bg-gray-700"
          data-testid="group-by-toggle"
        >
          {groupOptions.map((opt) => (
            <button
              key={opt.key}
              onClick={() => setGroupBy(opt.key)}
              className={`rounded-md px-3 py-1.5 text-xs font-medium transition-colors ${
                groupBy === opt.key
                  ? 'bg-white text-gray-900 shadow-sm dark:bg-gray-600 dark:text-white'
                  : 'text-gray-600 hover:text-gray-800 dark:text-gray-400 dark:hover:text-gray-200'
              }`}
              data-testid={`group-${opt.key}`}
            >
              {opt.label}
            </button>
          ))}
        </div>
      </div>

      {/* Content */}
      <div className="flex-1 overflow-auto px-6 py-4">
        {loading ? (
          <div
            className="flex min-h-[300px] items-center justify-center"
            data-testid="my-tasks-loading"
          >
            <div className="flex flex-col items-center gap-3">
              <div className="h-8 w-8 animate-spin rounded-full border-b-2 border-blue-600 dark:border-blue-400" />
              <span className="text-sm text-gray-500 dark:text-gray-400">
                {l('加载中...', 'Loading...')}
              </span>
            </div>
          </div>
        ) : filteredTasks.length === 0 ? (
          <div
            className="flex min-h-[300px] flex-col items-center justify-center text-gray-500 dark:text-gray-400"
            data-testid="my-tasks-empty"
          >
            <svg
              className="mb-4 h-16 w-16 text-gray-300 dark:text-gray-600"
              fill="none"
              viewBox="0 0 24 24"
              stroke="currentColor"
            >
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={1}
                d="M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2"
              />
            </svg>
            <p className="text-lg font-medium">{l('暂无任务', 'No tasks')}</p>
            <p className="mt-1 text-sm">
              {l('分配给你的任务会显示在这里', 'Tasks assigned to you will appear here')}
            </p>
          </div>
        ) : (
          <div className="space-y-6" data-testid="my-tasks-groups">
            {Object.entries(grouped).map(([groupName, groupTasks]) => (
              <div key={groupName} data-testid={`task-group-${groupName}`}>
                {/* Group header */}
                <div className="mb-2 flex items-center gap-2">
                  <h3 className="text-sm font-semibold text-gray-700 dark:text-gray-300">
                    {groupName}
                  </h3>
                  <span className="text-xs text-gray-500 dark:text-gray-400">
                    ({groupTasks.length})
                  </span>
                </div>

                {/* Group table */}
                <div className="overflow-hidden rounded-lg bg-white shadow-sm dark:bg-gray-800">
                  <table className="w-full text-sm">
                    <thead>
                      <tr className="border-b border-gray-200 bg-gray-50 dark:border-gray-700 dark:bg-gray-800/50">
                        {groupBy !== 'project' && (
                          <th className="px-4 py-2.5 text-left font-medium text-gray-600 dark:text-gray-400">
                            {l('项目', 'Project')}
                          </th>
                        )}
                        <th className="px-4 py-2.5 text-left font-medium text-gray-600 dark:text-gray-400">
                          {l('标题', 'Title')}
                        </th>
                        <th className="w-20 px-4 py-2.5 text-left font-medium text-gray-600 dark:text-gray-400">
                          {l('类型', 'Type')}
                        </th>
                        <th className="w-20 px-4 py-2.5 text-left font-medium text-gray-600 dark:text-gray-400">
                          {l('优先级', 'Priority')}
                        </th>
                        {groupBy !== 'status' && (
                          <th className="w-24 px-4 py-2.5 text-left font-medium text-gray-600 dark:text-gray-400">
                            {l('状态', 'Status')}
                          </th>
                        )}
                        <th className="w-28 px-4 py-2.5 text-left font-medium text-gray-600 dark:text-gray-400">
                          {l('截止日期', 'Due Date')}
                        </th>
                        <th className="w-20 px-4 py-2.5 text-left font-medium text-gray-600 dark:text-gray-400">
                          {l('进度', 'Progress')}
                        </th>
                      </tr>
                    </thead>
                    <tbody>
                      {groupTasks.map((task) => {
                        const isOverdue =
                          task.pm_task_due_date &&
                          new Date(task.pm_task_due_date) < new Date() &&
                          task.pm_task_status !== 'done' &&
                          task.pm_task_status !== 'cancelled';

                        return (
                          <tr
                            key={task.pid}
                            onClick={() => handleRowClick(task)}
                            className="cursor-pointer border-b border-gray-100 transition-colors hover:bg-blue-50/50 dark:border-gray-700/50 dark:hover:bg-blue-900/10"
                            data-testid={`task-row-${task.pid}`}
                          >
                            {groupBy !== 'project' && (
                              <td className="max-w-[160px] truncate px-4 py-2.5 text-gray-700 dark:text-gray-300">
                                {task.pm_mt_project_name || '-'}
                              </td>
                            )}
                            <td className="px-4 py-2.5 font-medium text-gray-900 dark:text-white">
                              {task.pm_task_title}
                            </td>
                            <td className="px-4 py-2.5">
                              <span
                                className={`inline-flex items-center rounded px-2 py-0.5 text-xs font-medium ${TYPE_COLORS[task.pm_task_type] || 'bg-gray-100 text-gray-800 dark:bg-gray-700 dark:text-gray-300'}`}
                              >
                                {task.pm_task_type}
                              </span>
                            </td>
                            <td className="px-4 py-2.5">
                              <span className="flex items-center gap-1.5">
                                <span
                                  className={`h-2 w-2 rounded-full ${PRIORITY_COLORS[task.pm_task_priority] || 'bg-gray-400'}`}
                                />
                                <span className="text-xs text-gray-700 dark:text-gray-300">
                                  {task.pm_task_priority}
                                </span>
                              </span>
                            </td>
                            {groupBy !== 'status' && (
                              <td className="px-4 py-2.5">
                                <span
                                  className={`inline-flex items-center rounded px-2 py-0.5 text-xs font-medium ${STATUS_COLORS[task.pm_task_status] || 'bg-gray-100 text-gray-800 dark:bg-gray-700 dark:text-gray-300'}`}
                                >
                                  {task.pm_task_status}
                                </span>
                              </td>
                            )}
                            <td
                              className={`px-4 py-2.5 text-xs ${isOverdue ? 'font-semibold text-red-600 dark:text-red-400' : 'text-gray-600 dark:text-gray-400'}`}
                            >
                              {task.pm_task_due_date || '-'}
                            </td>
                            <td className="px-4 py-2.5">
                              <div className="flex items-center gap-2">
                                <div className="h-1.5 w-12 overflow-hidden rounded-full bg-gray-200 dark:bg-gray-700">
                                  <div
                                    className="h-full rounded-full bg-blue-500"
                                    style={{ width: `${task.pm_task_progress ?? 0}%` }}
                                  />
                                </div>
                                <span className="text-xs text-gray-500 dark:text-gray-400">
                                  {task.pm_task_progress ?? 0}%
                                </span>
                              </div>
                            </td>
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
