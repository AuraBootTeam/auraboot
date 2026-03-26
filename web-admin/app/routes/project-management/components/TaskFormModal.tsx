import { useState, useCallback, useEffect } from 'react';
import { post } from '~/services/http-client';
import { ResultHelper } from '~/utils/type';
import { useI18n } from '~/contexts/I18nContext';
import { useToastContext } from '~/contexts/ToastContext';
import type { TaskRecord } from './TaskCard';

// ============================================================================
// Types
// ============================================================================

interface TaskFormModalProps {
  projectId: string;
  task?: TaskRecord | null;
  onClose: () => void;
  onSuccess: () => void;
}

const TASK_TYPES = ['epic', 'story', 'task', 'bug', 'milestone'] as const;
const PRIORITIES = ['critical', 'high', 'medium', 'low', 'none'] as const;
const STATUSES = ['todo', 'in_progress', 'done', 'cancelled'] as const;

const TYPE_LABELS: Record<string, { zh: string; en: string }> = {
  epic: { zh: 'Epic', en: 'Epic' },
  story: { zh: 'Story', en: 'Story' },
  task: { zh: 'Task', en: 'Task' },
  bug: { zh: 'Bug', en: 'Bug' },
  milestone: { zh: 'Milestone', en: 'Milestone' },
};

const PRIORITY_LABELS: Record<string, { zh: string; en: string }> = {
  critical: { zh: '\u7D27\u6025', en: 'Critical' },
  high: { zh: '\u9AD8', en: 'High' },
  medium: { zh: '\u4E2D', en: 'Medium' },
  low: { zh: '\u4F4E', en: 'Low' },
  none: { zh: '\u65E0', en: 'None' },
};

const STATUS_LABELS: Record<string, { zh: string; en: string }> = {
  todo: { zh: '\u5F85\u5904\u7406', en: 'To Do' },
  in_progress: { zh: '\u8FDB\u884C\u4E2D', en: 'In Progress' },
  done: { zh: '\u5DF2\u5B8C\u6210', en: 'Done' },
  cancelled: { zh: '\u5DF2\u53D6\u6D88', en: 'Cancelled' },
};

// ============================================================================
// Component
// ============================================================================

export default function TaskFormModal({ projectId, task, onClose, onSuccess }: TaskFormModalProps) {
  const { locale } = useI18n();
  const { showSuccessToast, showErrorToast } = useToastContext();
  const isEdit = Boolean(task);

  const l = useCallback((zh: string, en: string) => (locale === 'zh-CN' ? zh : en), [locale]);

  // ---- Form state ----
  const [title, setTitle] = useState('');
  const [type, setType] = useState<string>('task');
  const [priority, setPriority] = useState<string>('medium');
  const [status, setStatus] = useState<string>('todo');
  const [startDate, setStartDate] = useState('');
  const [dueDate, setDueDate] = useState('');
  const [estimatedHours, setEstimatedHours] = useState('');
  const [description, setDescription] = useState('');
  const [parentId, setParentId] = useState('');
  const [submitting, setSubmitting] = useState(false);

  // Populate form when editing
  useEffect(() => {
    if (task) {
      setTitle(task.pm_task_title || '');
      setType(task.pm_task_type || 'task');
      setPriority(task.pm_task_priority || 'medium');
      setStatus(task.pm_task_status || 'todo');
      setStartDate(((task as Record<string, unknown>).pm_task_start_date as string) || '');
      setDueDate(task.pm_task_due_date || '');
      setEstimatedHours(
        (task as Record<string, unknown>).pm_task_estimated_hours
          ? String((task as Record<string, unknown>).pm_task_estimated_hours)
          : '',
      );
      setDescription(((task as Record<string, unknown>).pm_task_description as string) || '');
      setParentId(((task as Record<string, unknown>).pm_task_parent_id as string) || '');
    }
  }, [task]);

  // ---- Submit handler ----
  const handleSubmit = useCallback(async () => {
    if (!title.trim()) {
      showErrorToast(l('\u8BF7\u8F93\u5165\u4EFB\u52A1\u6807\u9898', 'Please enter a task title'));
      return;
    }
    setSubmitting(true);
    try {
      const params: Record<string, unknown> = {
        pm_task_title: title.trim(),
        pm_task_type: type,
        pm_task_priority: priority,
      };

      if (startDate) params.pm_task_start_date = startDate;
      if (dueDate) params.pm_task_due_date = dueDate;
      if (estimatedHours) params.pm_task_estimated_hours = Number(estimatedHours);
      if (description.trim()) params.pm_task_description = description.trim();
      if (parentId.trim()) params.pm_task_parent_id = parentId.trim();

      if (isEdit && task) {
        params.pm_task_status = status;
        const result = await post<unknown>('/api/meta/commands/execute/pm:update_task', {
          payload: params,
          targetRecordId: task.pid,
          operationType: 'update',
        });
        if (ResultHelper.isSuccess(result)) {
          showSuccessToast(l('\u4EFB\u52A1\u5DF2\u66F4\u65B0', 'Task updated'));
          onSuccess();
        } else {
          showErrorToast(result.message || l('\u66F4\u65B0\u5931\u8D25', 'Update failed'));
        }
      } else {
        params.pm_task_project_id = projectId;
        const result = await post<unknown>('/api/meta/commands/execute/pm:create_task', {
          payload: params,
          operationType: 'create',
        });
        if (ResultHelper.isSuccess(result)) {
          showSuccessToast(l('\u4EFB\u52A1\u5DF2\u521B\u5EFA', 'Task created'));
          onSuccess();
        } else {
          showErrorToast(result.message || l('\u521B\u5EFA\u5931\u8D25', 'Create failed'));
        }
      }
    } catch (e: unknown) {
      const msg =
        e instanceof Error ? e.message : l('\u64CD\u4F5C\u5931\u8D25', 'Operation failed');
      showErrorToast(msg);
    } finally {
      setSubmitting(false);
    }
  }, [
    title,
    type,
    priority,
    status,
    startDate,
    dueDate,
    estimatedHours,
    description,
    parentId,
    isEdit,
    task,
    projectId,
    l,
    showSuccessToast,
    showErrorToast,
    onSuccess,
  ]);

  // ---- Shared styles ----
  const labelCls = 'block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1';
  const inputCls =
    'w-full rounded-lg border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-700 ' +
    'text-sm text-gray-900 dark:text-gray-100 px-3 py-2 ' +
    'focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none transition-colors';
  const selectCls = inputCls + ' appearance-none';

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center"
      data-testid="task-form-modal"
    >
      {/* Backdrop */}
      <div
        className="absolute inset-0 bg-black/50"
        onClick={onClose}
        data-testid="task-form-modal-backdrop"
      />

      {/* Content */}
      <div className="relative mx-4 flex max-h-[90vh] w-full max-w-lg flex-col rounded-xl bg-white shadow-2xl dark:bg-gray-800">
        {/* Header */}
        <div className="flex items-center justify-between border-b border-gray-200 px-6 py-4 dark:border-gray-700">
          <h2
            className="text-lg font-semibold text-gray-900 dark:text-white"
            data-testid="task-form-modal-title"
          >
            {isEdit
              ? l('\u7F16\u8F91\u4EFB\u52A1', 'Edit Task')
              : l('\u521B\u5EFA\u4EFB\u52A1', 'Create Task')}
          </h2>
          <button
            onClick={onClose}
            className="text-gray-400 transition-colors hover:text-gray-600 dark:hover:text-gray-300"
            data-testid="task-form-modal-close"
          >
            <svg
              className="h-5 w-5"
              fill="none"
              viewBox="0 0 24 24"
              strokeWidth={1.5}
              stroke="currentColor"
            >
              <path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" />
            </svg>
          </button>
        </div>

        {/* Form body (scrollable) */}
        <div className="flex-1 space-y-4 overflow-y-auto px-6 py-4">
          {/* Title */}
          <div>
            <label className={labelCls}>{l('\u6807\u9898', 'Title')} *</label>
            <input
              type="text"
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              className={inputCls}
              placeholder={l('\u8F93\u5165\u4EFB\u52A1\u6807\u9898', 'Enter task title')}
              data-testid="task-form-title"
              autoFocus
            />
          </div>

          {/* Type + Priority row */}
          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className={labelCls}>{l('\u7C7B\u578B', 'Type')}</label>
              <select
                value={type}
                onChange={(e) => setType(e.target.value)}
                className={selectCls}
                data-testid="task-form-type"
              >
                {TASK_TYPES.map((t) => (
                  <option key={t} value={t}>
                    {l(TYPE_LABELS[t].zh, TYPE_LABELS[t].en)}
                  </option>
                ))}
              </select>
            </div>
            <div>
              <label className={labelCls}>{l('\u4F18\u5148\u7EA7', 'Priority')}</label>
              <select
                value={priority}
                onChange={(e) => setPriority(e.target.value)}
                className={selectCls}
                data-testid="task-form-priority"
              >
                {PRIORITIES.map((p) => (
                  <option key={p} value={p}>
                    {l(PRIORITY_LABELS[p].zh, PRIORITY_LABELS[p].en)}
                  </option>
                ))}
              </select>
            </div>
          </div>

          {/* Status (edit mode only) */}
          {isEdit && (
            <div>
              <label className={labelCls}>{l('\u72B6\u6001', 'Status')}</label>
              <select
                value={status}
                onChange={(e) => setStatus(e.target.value)}
                className={selectCls}
                data-testid="task-form-status"
              >
                {STATUSES.map((s) => (
                  <option key={s} value={s}>
                    {l(STATUS_LABELS[s].zh, STATUS_LABELS[s].en)}
                  </option>
                ))}
              </select>
            </div>
          )}

          {/* Start Date + Due Date row */}
          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className={labelCls}>{l('\u5F00\u59CB\u65E5\u671F', 'Start Date')}</label>
              <input
                type="date"
                value={startDate}
                onChange={(e) => setStartDate(e.target.value)}
                className={inputCls}
                data-testid="task-form-start-date"
              />
            </div>
            <div>
              <label className={labelCls}>{l('\u622A\u6B62\u65E5\u671F', 'Due Date')}</label>
              <input
                type="date"
                value={dueDate}
                onChange={(e) => setDueDate(e.target.value)}
                className={inputCls}
                data-testid="task-form-due-date"
              />
            </div>
          </div>

          {/* Estimated Hours */}
          <div>
            <label className={labelCls}>{l('\u9884\u4F30\u5DE5\u65F6', 'Estimated Hours')}</label>
            <input
              type="number"
              min="0"
              step="0.5"
              value={estimatedHours}
              onChange={(e) => setEstimatedHours(e.target.value)}
              className={inputCls}
              placeholder="0"
              data-testid="task-form-estimated-hours"
            />
          </div>

          {/* Description */}
          <div>
            <label className={labelCls}>{l('\u63CF\u8FF0', 'Description')}</label>
            <textarea
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              rows={4}
              className={inputCls + ' resize-none'}
              placeholder={l('\u4EFB\u52A1\u63CF\u8FF0...', 'Task description...')}
              data-testid="task-form-description"
            />
          </div>

          {/* Parent Task */}
          <div>
            <label className={labelCls}>{l('\u7236\u4EFB\u52A1 ID', 'Parent Task ID')}</label>
            <input
              type="text"
              value={parentId}
              onChange={(e) => setParentId(e.target.value)}
              className={inputCls}
              placeholder={l(
                '\u8F93\u5165\u7236\u4EFB\u52A1 ID\uFF08\u53EF\u9009\uFF09',
                'Enter parent task ID (optional)',
              )}
              data-testid="task-form-parent-id"
            />
          </div>
        </div>

        {/* Footer */}
        <div className="flex items-center justify-end gap-3 border-t border-gray-200 px-6 py-4 dark:border-gray-700">
          <button
            onClick={onClose}
            disabled={submitting}
            className="rounded-lg bg-gray-100 px-4 py-2 text-sm font-medium text-gray-700 transition-colors hover:bg-gray-200 disabled:opacity-50 dark:bg-gray-700 dark:text-gray-300 dark:hover:bg-gray-600"
            data-testid="task-form-cancel"
          >
            {l('\u53D6\u6D88', 'Cancel')}
          </button>
          <button
            onClick={handleSubmit}
            disabled={submitting}
            className="flex items-center gap-2 rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-50"
            data-testid="task-form-submit"
          >
            {submitting && (
              <span className="h-3.5 w-3.5 animate-spin rounded-full border-b-2 border-white" />
            )}
            {submitting ? l('\u4FDD\u5B58\u4E2D...', 'Saving...') : l('\u4FDD\u5B58', 'Save')}
          </button>
        </div>
      </div>
    </div>
  );
}
