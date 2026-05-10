import { useState, useEffect, useCallback } from 'react';
import {
  useScheduledTask,
  type ScheduledTask,
  type ScheduledTaskLog,
  type ScheduledTaskRequest,
} from '~/hooks/useScheduledTask';
import {
  ClockIcon,
  PlayIcon,
  PauseIcon,
  TrashIcon,
  PencilIcon,
  ArrowPathIcon,
  PlusIcon,
  DocumentTextIcon,
  XMarkIcon,
  CheckCircleIcon,
} from '@heroicons/react/24/outline';
import { ClockIcon as ClockSolidIcon } from '@heroicons/react/24/solid';
import { useI18n } from '~/contexts/I18nContext';

/**
 * Scheduled Task Management Page
 */
export default function SchedulerManagement() {
  const { locale } = useI18n();
  const l = useCallback(
    (zhCN: string, enUS: string) => (locale === 'zh-CN' ? zhCN : enUS),
    [locale],
  );
  const {
    tasks,
    logs,
    loading,
    logsLoading,
    fetchTasks,
    createTask,
    updateTask,
    deleteTask,
    enableTask,
    disableTask,
    triggerTask,
    fetchLogs,
    reloadScheduler,
  } = useScheduledTask();

  const [showModal, setShowModal] = useState(false);
  const [showLogsModal, setShowLogsModal] = useState(false);
  const [editingTask, setEditingTask] = useState<ScheduledTask | null>(null);
  const [selectedTaskPid, setSelectedTaskPid] = useState<string | null>(null);
  const [formData, setFormData] = useState<ScheduledTaskRequest>({
    name: '',
    description: '',
    taskType: 'cron',
    cronExpression: '',
    intervalMs: 60000,
    handlerBean: '',
    handlerMethod: 'execute',
    params: '',
    maxRetries: 0,
    timeoutMs: 300000,
    enabled: true,
  });

  // Initial load
  useEffect(() => {
    fetchTasks();
  }, [fetchTasks]);

  // Open create modal
  const handleCreate = useCallback(() => {
    setEditingTask(null);
    setFormData({
      name: '',
      description: '',
      taskType: 'cron',
      cronExpression: '0 0 * * *',
      intervalMs: 60000,
      handlerBean: '',
      handlerMethod: 'execute',
      params: '',
      maxRetries: 0,
      timeoutMs: 300000,
      enabled: true,
    });
    setShowModal(true);
  }, []);

  // Open edit modal
  const handleEdit = useCallback((task: ScheduledTask) => {
    setEditingTask(task);
    setFormData({
      name: task.name,
      description: task.description || '',
      taskType: task.taskType,
      cronExpression: task.cronExpression || '',
      intervalMs: task.intervalMs || 60000,
      handlerBean: task.handlerBean,
      handlerMethod: task.handlerMethod,
      params: task.params || '',
      maxRetries: task.maxRetries,
      timeoutMs: task.timeoutMs,
      enabled: task.enabled,
    });
    setShowModal(true);
  }, []);

  // Submit form
  const handleSubmit = useCallback(
    async (e: React.FormEvent) => {
      e.preventDefault();
      const success = editingTask
        ? await updateTask(editingTask.pid, formData)
        : await createTask(formData);
      if (success) {
        setShowModal(false);
      }
    },
    [editingTask, formData, createTask, updateTask],
  );

  // View logs
  const handleViewLogs = useCallback(
    (pid: string) => {
      setSelectedTaskPid(pid);
      fetchLogs(pid);
      setShowLogsModal(true);
    },
    [fetchLogs],
  );

  // Format date
  const formatDate = (dateString: string | null) => {
    if (!dateString) return '-';
    return new Date(dateString).toLocaleString();
  };

  // Format duration
  const formatDuration = (ms: number | null) => {
    if (!ms) return '-';
    if (ms < 1000) return `${ms}ms`;
    if (ms < 60000) return `${(ms / 1000).toFixed(1)}s`;
    return `${(ms / 60000).toFixed(1)}min`;
  };

  // Get task type badge
  const getTaskTypeBadge = (type: string) => {
    switch (type) {
      case 'cron':
        return 'bg-purple-100 text-purple-800';
      case 'interval':
        return 'bg-blue-100 text-blue-800';
      case 'one_time':
        return 'bg-yellow-100 text-yellow-800';
      default:
        return 'bg-gray-100 text-gray-800';
    }
  };

  // Get log status badge
  const getLogStatusBadge = (status: string) => {
    switch (status) {
      case 'success':
        return 'bg-green-100 text-green-800';
      case 'failed':
        return 'bg-red-100 text-red-800';
      case 'timeout':
        return 'bg-orange-100 text-orange-800';
      case 'running':
        return 'bg-blue-100 text-blue-800';
      default:
        return 'bg-gray-100 text-gray-800';
    }
  };

  return (
    <div className="mx-auto w-full max-w-7xl p-6">
      {/* Header */}
      <div className="mb-6 flex items-center justify-between">
        <div className="flex items-center gap-3">
          <ClockSolidIcon className="h-8 w-8 text-indigo-600" />
          <div>
            <h1 className="text-2xl font-bold text-gray-900">{l('定时任务', 'Scheduled Tasks')}</h1>
            <p className="text-sm text-gray-500">
              {l(`已配置 ${tasks.length} 个任务`, `${tasks.length} tasks configured`)}
            </p>
          </div>
        </div>
        <div className="flex gap-2">
          <button
            onClick={reloadScheduler}
            className="inline-flex items-center gap-2 rounded-lg border border-gray-300 bg-white px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50"
          >
            <ArrowPathIcon className="h-4 w-4" />
            {l('重载', 'Reload')}
          </button>
          <button
            onClick={handleCreate}
            className="inline-flex items-center gap-2 rounded-lg bg-indigo-600 px-4 py-2 text-sm font-medium text-white hover:bg-indigo-700"
          >
            <PlusIcon className="h-4 w-4" />
            {l('新建任务', 'New Task')}
          </button>
        </div>
      </div>

      {/* Task List */}
      {loading ? (
        <div className="flex items-center justify-center py-12">
          <div className="h-8 w-8 animate-spin rounded-full border-b-2 border-indigo-600"></div>
          <span className="ml-3 text-gray-500">{l('正在加载任务...', 'Loading tasks...')}</span>
        </div>
      ) : tasks.length === 0 ? (
        <div className="rounded-lg bg-gray-50 py-12 text-center">
          <ClockIcon className="mx-auto mb-4 h-12 w-12 text-gray-400" />
          <h3 className="text-lg font-medium text-gray-900">
            {l('暂无定时任务', 'No scheduled tasks')}
          </h3>
          <p className="mt-1 text-gray-500">
            {l('创建第一个定时任务开始使用', 'Create your first scheduled task to get started')}
          </p>
          <button
            onClick={handleCreate}
            className="mt-4 inline-flex items-center gap-2 rounded-lg bg-indigo-600 px-4 py-2 text-sm font-medium text-white hover:bg-indigo-700"
          >
            <PlusIcon className="h-4 w-4" />
            {l('创建任务', 'Create Task')}
          </button>
        </div>
      ) : (
        <div className="overflow-hidden rounded-lg bg-white shadow">
          <table className="min-w-full divide-y divide-gray-200">
            <thead className="bg-gray-50">
              <tr>
                <th className="px-6 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase">
                  {l('任务', 'Task')}
                </th>
                <th className="px-6 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase">
                  {l('类型', 'Type')}
                </th>
                <th className="px-6 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase">
                  {l('调度', 'Schedule')}
                </th>
                <th className="px-6 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase">
                  {l('执行器', 'Handler')}
                </th>
                <th className="px-6 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase">
                  {l('状态', 'Status')}
                </th>
                <th className="px-6 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase">
                  {l('上次/下次运行', 'Last/Next Run')}
                </th>
                <th className="px-6 py-3 text-right text-xs font-medium tracking-wider text-gray-500 uppercase">
                  {l('操作', 'Actions')}
                </th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-200 bg-white">
              {tasks.map((task: ScheduledTask) => (
                <tr key={task.pid} className="hover:bg-gray-50">
                  <td className="px-6 py-4">
                    <div className="text-sm font-medium text-gray-900">{task.name}</div>
                    <div className="text-sm text-gray-500">{task.description || '-'}</div>
                  </td>
                  <td className="px-6 py-4">
                    <span
                      className={`inline-flex rounded px-2 py-1 text-xs font-medium ${getTaskTypeBadge(task.taskType)}`}
                    >
                      {task.taskType}
                    </span>
                  </td>
                  <td className="px-6 py-4 text-sm text-gray-500">
                    {task.taskType === 'cron' && (
                      <code className="rounded bg-gray-100 px-2 py-1">{task.cronExpression}</code>
                    )}
                    {task.taskType === 'interval' && (
                      <span>
                        {l('每隔', 'Every')} {formatDuration(task.intervalMs)}
                      </span>
                    )}
                    {task.taskType === 'one_time' && <span>{l('一次性', 'One-time')}</span>}
                  </td>
                  <td className="px-6 py-4 text-sm text-gray-500">
                    <code className="rounded bg-gray-100 px-2 py-1 text-xs">
                      {task.handlerBean}.{task.handlerMethod}()
                    </code>
                  </td>
                  <td className="px-6 py-4">
                    {task.enabled ? (
                      <span className="inline-flex items-center gap-1 rounded bg-green-100 px-2 py-1 text-xs font-medium text-green-800">
                        <CheckCircleIcon className="h-3.5 w-3.5" />
                        {l('启用', 'Enabled')}
                      </span>
                    ) : (
                      <span className="inline-flex items-center gap-1 rounded bg-gray-100 px-2 py-1 text-xs font-medium text-gray-600">
                        <PauseIcon className="h-3.5 w-3.5" />
                        {l('停用', 'Disabled')}
                      </span>
                    )}
                  </td>
                  <td className="px-6 py-4 text-xs text-gray-500">
                    <div>
                      {l('上次', 'Last')}: {formatDate(task.lastRunAt)}
                    </div>
                    <div>
                      {l('下次', 'Next')}: {formatDate(task.nextRunAt)}
                    </div>
                  </td>
                  <td className="px-6 py-4 text-right">
                    <div className="flex justify-end gap-2">
                      <button
                        onClick={() => triggerTask(task.pid)}
                        className="rounded p-1.5 text-gray-600 hover:bg-green-50 hover:text-green-600"
                        title={l('立即触发', 'Trigger Now')}
                      >
                        <PlayIcon className="h-4 w-4" />
                      </button>
                      <button
                        onClick={() => handleViewLogs(task.pid)}
                        className="rounded p-1.5 text-gray-600 hover:bg-blue-50 hover:text-blue-600"
                        title={l('查看日志', 'View Logs')}
                      >
                        <DocumentTextIcon className="h-4 w-4" />
                      </button>
                      <button
                        onClick={() =>
                          task.enabled ? disableTask(task.pid) : enableTask(task.pid)
                        }
                        className="rounded p-1.5 text-gray-600 hover:bg-yellow-50 hover:text-yellow-600"
                        title={task.enabled ? l('停用', 'Disable') : l('启用', 'Enable')}
                      >
                        {task.enabled ? (
                          <PauseIcon className="h-4 w-4" />
                        ) : (
                          <PlayIcon className="h-4 w-4" />
                        )}
                      </button>
                      <button
                        onClick={() => handleEdit(task)}
                        className="rounded p-1.5 text-gray-600 hover:bg-indigo-50 hover:text-indigo-600"
                        title={l('编辑', 'Edit')}
                      >
                        <PencilIcon className="h-4 w-4" />
                      </button>
                      <button
                        onClick={() => {
                          if (
                            confirm(
                              l('确认删除该任务吗？', 'Are you sure you want to delete this task?'),
                            )
                          ) {
                            deleteTask(task.pid);
                          }
                        }}
                        className="rounded p-1.5 text-gray-600 hover:bg-red-50 hover:text-red-600"
                        title={l('删除', 'Delete')}
                      >
                        <TrashIcon className="h-4 w-4" />
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {/* Create/Edit Modal */}
      {showModal && (
        <div className="bg-opacity-50 fixed inset-0 z-50 flex items-center justify-center bg-black">
          <div className="max-h-[90vh] w-full max-w-2xl overflow-y-auto rounded-lg bg-white shadow-xl">
            <div className="flex items-center justify-between border-b px-6 py-4">
              <h2 className="text-lg font-semibold text-gray-900">
                {editingTask ? l('编辑任务', 'Edit Task') : l('创建任务', 'Create Task')}
              </h2>
              <button
                onClick={() => setShowModal(false)}
                className="text-gray-400 hover:text-gray-600"
              >
                <XMarkIcon className="h-6 w-6" />
              </button>
            </div>
            <form onSubmit={handleSubmit} className="space-y-4 p-6">
              <div className="grid grid-cols-2 gap-4">
                <div>
                  <label className="mb-1 block text-sm font-medium text-gray-700">
                    {l('名称', 'Name')} *
                  </label>
                  <input
                    type="text"
                    required
                    value={formData.name}
                    onChange={(e) => setFormData((prev) => ({ ...prev, name: e.target.value }))}
                    className="w-full rounded-md border-gray-300 shadow-sm focus:border-indigo-500 focus:ring-indigo-500"
                  />
                </div>
                <div>
                  <label className="mb-1 block text-sm font-medium text-gray-700">
                    {l('任务类型', 'Task Type')} *
                  </label>
                  <select
                    required
                    value={formData.taskType}
                    onChange={(e) =>
                      setFormData((prev) => ({
                        ...prev,
                        taskType: e.target.value as 'cron' | 'interval' | 'one_time',
                      }))
                    }
                    className="w-full rounded-md border-gray-300 shadow-sm focus:border-indigo-500 focus:ring-indigo-500"
                  >
                    <option value="cron">CRON</option>
                    <option value="interval">INTERVAL</option>
                    <option value="one_time">ONE_TIME</option>
                  </select>
                </div>
              </div>

              <div>
                <label className="mb-1 block text-sm font-medium text-gray-700">
                  {l('描述', 'Description')}
                </label>
                <textarea
                  value={formData.description}
                  onChange={(e) =>
                    setFormData((prev) => ({ ...prev, description: e.target.value }))
                  }
                  rows={2}
                  className="w-full rounded-md border-gray-300 shadow-sm focus:border-indigo-500 focus:ring-indigo-500"
                />
              </div>

              {formData.taskType === 'cron' && (
                <div>
                  <label className="mb-1 block text-sm font-medium text-gray-700">
                    {l('Cron 表达式', 'Cron Expression')} *
                  </label>
                  <input
                    type="text"
                    required
                    value={formData.cronExpression}
                    onChange={(e) =>
                      setFormData((prev) => ({ ...prev, cronExpression: e.target.value }))
                    }
                    placeholder="0 0 * * *"
                    className="w-full rounded-md border-gray-300 shadow-sm focus:border-indigo-500 focus:ring-indigo-500"
                  />
                  <p className="mt-1 text-xs text-gray-500">
                    {l(
                      '标准 cron 格式：分 时 日 月 周',
                      'Standard cron format: minute hour day month weekday',
                    )}
                  </p>
                </div>
              )}

              {formData.taskType === 'interval' && (
                <div>
                  <label className="mb-1 block text-sm font-medium text-gray-700">
                    {l('间隔(毫秒)', 'Interval (ms)')} *
                  </label>
                  <input
                    type="number"
                    required
                    min={1000}
                    value={formData.intervalMs}
                    onChange={(e) =>
                      setFormData((prev) => ({ ...prev, intervalMs: parseInt(e.target.value) }))
                    }
                    className="w-full rounded-md border-gray-300 shadow-sm focus:border-indigo-500 focus:ring-indigo-500"
                  />
                </div>
              )}

              <div className="grid grid-cols-2 gap-4">
                <div>
                  <label className="mb-1 block text-sm font-medium text-gray-700">
                    {l('处理器 Bean', 'Handler Bean')} *
                  </label>
                  <input
                    type="text"
                    required
                    value={formData.handlerBean}
                    onChange={(e) =>
                      setFormData((prev) => ({ ...prev, handlerBean: e.target.value }))
                    }
                    placeholder="myTaskHandler"
                    className="w-full rounded-md border-gray-300 shadow-sm focus:border-indigo-500 focus:ring-indigo-500"
                  />
                </div>
                <div>
                  <label className="mb-1 block text-sm font-medium text-gray-700">
                    {l('处理器方法', 'Handler Method')}
                  </label>
                  <input
                    type="text"
                    value={formData.handlerMethod}
                    onChange={(e) =>
                      setFormData((prev) => ({ ...prev, handlerMethod: e.target.value }))
                    }
                    placeholder="execute"
                    className="w-full rounded-md border-gray-300 shadow-sm focus:border-indigo-500 focus:ring-indigo-500"
                  />
                </div>
              </div>

              <div>
                <label className="mb-1 block text-sm font-medium text-gray-700">
                  {l('参数(JSON)', 'Parameters (JSON)')}
                </label>
                <textarea
                  value={formData.params}
                  onChange={(e) => setFormData((prev) => ({ ...prev, params: e.target.value }))}
                  rows={2}
                  placeholder='{"key": "value"}'
                  className="w-full rounded-md border-gray-300 font-mono text-sm shadow-sm focus:border-indigo-500 focus:ring-indigo-500"
                />
              </div>

              <div className="grid grid-cols-3 gap-4">
                <div>
                  <label className="mb-1 block text-sm font-medium text-gray-700">
                    {l('最大重试次数', 'Max Retries')}
                  </label>
                  <input
                    type="number"
                    min={0}
                    value={formData.maxRetries}
                    onChange={(e) =>
                      setFormData((prev) => ({ ...prev, maxRetries: parseInt(e.target.value) }))
                    }
                    className="w-full rounded-md border-gray-300 shadow-sm focus:border-indigo-500 focus:ring-indigo-500"
                  />
                </div>
                <div>
                  <label className="mb-1 block text-sm font-medium text-gray-700">
                    {l('超时(毫秒)', 'Timeout (ms)')}
                  </label>
                  <input
                    type="number"
                    min={1000}
                    value={formData.timeoutMs}
                    onChange={(e) =>
                      setFormData((prev) => ({ ...prev, timeoutMs: parseInt(e.target.value) }))
                    }
                    className="w-full rounded-md border-gray-300 shadow-sm focus:border-indigo-500 focus:ring-indigo-500"
                  />
                </div>
                <div className="flex items-center pt-6">
                  <label className="flex cursor-pointer items-center gap-2">
                    <input
                      type="checkbox"
                      checked={formData.enabled}
                      onChange={(e) =>
                        setFormData((prev) => ({ ...prev, enabled: e.target.checked }))
                      }
                      className="rounded border-gray-300 text-indigo-600 focus:ring-indigo-500"
                    />
                    <span className="text-sm font-medium text-gray-700">
                      {l('启用', 'Enabled')}
                    </span>
                  </label>
                </div>
              </div>

              <div className="flex justify-end gap-3 border-t pt-4">
                <button
                  type="button"
                  onClick={() => setShowModal(false)}
                  className="rounded-lg border border-gray-300 bg-white px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50"
                >
                  {l('取消', 'Cancel')}
                </button>
                <button
                  type="submit"
                  className="rounded-lg bg-indigo-600 px-4 py-2 text-sm font-medium text-white hover:bg-indigo-700"
                >
                  {editingTask ? l('更新', 'Update') : l('创建', 'Create')}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* Logs Modal */}
      {showLogsModal && selectedTaskPid && (
        <div className="bg-opacity-50 fixed inset-0 z-50 flex items-center justify-center bg-black">
          <div className="max-h-[90vh] w-full max-w-4xl overflow-y-auto rounded-lg bg-white shadow-xl">
            <div className="flex items-center justify-between border-b px-6 py-4">
              <h2 className="text-lg font-semibold text-gray-900">
                {l('执行日志', 'Execution Logs')} - {selectedTaskPid}
              </h2>
              <button
                onClick={() => setShowLogsModal(false)}
                className="text-gray-400 hover:text-gray-600"
              >
                <XMarkIcon className="h-6 w-6" />
              </button>
            </div>
            <div className="p-6">
              {logsLoading ? (
                <div className="flex items-center justify-center py-8">
                  <div className="h-6 w-6 animate-spin rounded-full border-b-2 border-indigo-600"></div>
                  <span className="ml-3 text-gray-500">
                    {l('正在加载日志...', 'Loading logs...')}
                  </span>
                </div>
              ) : logs.length === 0 ? (
                <div className="py-8 text-center text-gray-500">
                  {l('暂无执行日志', 'No execution logs found')}
                </div>
              ) : (
                <div className="space-y-3">
                  {logs.map((log: ScheduledTaskLog) => (
                    <div
                      key={log.id}
                      className={`rounded-lg border p-4 ${
                        log.status === 'success'
                          ? 'border-green-200 bg-green-50'
                          : log.status === 'failed'
                            ? 'border-red-200 bg-red-50'
                            : log.status === 'running'
                              ? 'border-blue-200 bg-blue-50'
                              : 'border-orange-200 bg-orange-50'
                      }`}
                    >
                      <div className="mb-2 flex items-center justify-between">
                        <div className="flex items-center gap-3">
                          <span
                            className={`inline-flex rounded px-2 py-1 text-xs font-medium ${getLogStatusBadge(log.status)}`}
                          >
                            {log.status}
                          </span>
                          <span className="rounded bg-gray-100 px-2 py-1 text-xs text-gray-500">
                            {log.triggerType}
                          </span>
                          {log.retryCount > 0 && (
                            <span className="text-xs text-orange-600">
                              {l('重试', 'Retry')} #{log.retryCount}
                            </span>
                          )}
                        </div>
                        <div className="text-sm text-gray-500">
                          {l('耗时', 'Duration')}:{' '}
                          <span className="font-medium">{formatDuration(log.durationMs)}</span>
                        </div>
                      </div>
                      <div className="mb-2 text-xs text-gray-500">
                        {l('开始', 'Started')}: {formatDate(log.startedAt)}
                        {log.finishedAt &&
                          ` | ${l('结束', 'Finished')}: ${formatDate(log.finishedAt)}`}
                      </div>
                      {log.result && (
                        <div className="mt-2 rounded border bg-white p-2 text-sm text-gray-700">
                          <strong>{l('结果', 'Result')}:</strong> {log.result}
                        </div>
                      )}
                      {log.errorMessage && (
                        <div className="mt-2 rounded border border-red-200 bg-red-100 p-2 text-sm text-red-700">
                          <strong>{l('错误', 'Error')}:</strong> {log.errorMessage}
                        </div>
                      )}
                    </div>
                  ))}
                </div>
              )}
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
