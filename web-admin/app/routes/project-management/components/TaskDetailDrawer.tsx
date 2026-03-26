import { useState, useCallback, useEffect, useMemo } from 'react';
import { get, post } from '~/services/http-client';
import { ResultHelper } from '~/utils/type';
import { useI18n } from '~/contexts/I18nContext';
import { useToastContext } from '~/contexts/ToastContext';
import type { TaskRecord } from './TaskCard';
import TaskFormModal from './TaskFormModal';

// ============================================================================
// Types
// ============================================================================

interface TaskDetailDrawerProps {
  task: TaskRecord;
  projectId: string;
  onClose: () => void;
  onTaskUpdate: () => void;
}

interface CommentRecord {
  pid: string;
  pm_tc_content: string;
  pm_tc_task_id: string;
  created_by?: string;
  created_at?: string;
  [key: string]: unknown;
}

interface ActivityRecord {
  pid: string;
  pm_act_action: string;
  pm_act_field_changed?: string;
  pm_act_old_value?: string;
  pm_act_new_value?: string;
  created_by?: string;
  created_at?: string;
  [key: string]: unknown;
}

type DetailTab = 'comments' | 'activity';

// ============================================================================
// Constants
// ============================================================================

const TYPE_STYLES: Record<string, { bg: string; text: string; label: string }> = {
  EPIC: {
    bg: 'bg-purple-100 dark:bg-purple-900/40',
    text: 'text-purple-700 dark:text-purple-300',
    label: 'Epic',
  },
  STORY: {
    bg: 'bg-blue-100 dark:bg-blue-900/40',
    text: 'text-blue-700 dark:text-blue-300',
    label: 'Story',
  },
  TASK: {
    bg: 'bg-green-100 dark:bg-green-900/40',
    text: 'text-green-700 dark:text-green-300',
    label: 'Task',
  },
  BUG: {
    bg: 'bg-red-100 dark:bg-red-900/40',
    text: 'text-red-700 dark:text-red-300',
    label: 'Bug',
  },
  MILESTONE: {
    bg: 'bg-amber-100 dark:bg-amber-900/40',
    text: 'text-amber-700 dark:text-amber-300',
    label: 'Milestone',
  },
};

const STATUS_STYLES: Record<string, { bg: string; text: string }> = {
  TODO: { bg: 'bg-gray-100 dark:bg-gray-700', text: 'text-gray-700 dark:text-gray-300' },
  in_progress: { bg: 'bg-blue-100 dark:bg-blue-900/40', text: 'text-blue-700 dark:text-blue-300' },
  DONE: { bg: 'bg-green-100 dark:bg-green-900/40', text: 'text-green-700 dark:text-green-300' },
  cancelled: { bg: 'bg-red-100 dark:bg-red-900/40', text: 'text-red-700 dark:text-red-300' },
};

const STATUS_LABELS: Record<string, { zh: string; en: string }> = {
  TODO: { zh: '\u5F85\u5904\u7406', en: 'To Do' },
  in_progress: { zh: '\u8FDB\u884C\u4E2D', en: 'In Progress' },
  DONE: { zh: '\u5DF2\u5B8C\u6210', en: 'Done' },
  cancelled: { zh: '\u5DF2\u53D6\u6D88', en: 'Cancelled' },
};

const PRIORITY_STYLES: Record<
  string,
  { bg: string; text: string; label_zh: string; label_en: string }
> = {
  CRITICAL: {
    bg: 'bg-red-100 dark:bg-red-900/40',
    text: 'text-red-700 dark:text-red-300',
    label_zh: '\u7D27\u6025',
    label_en: 'Critical',
  },
  HIGH: {
    bg: 'bg-orange-100 dark:bg-orange-900/40',
    text: 'text-orange-700 dark:text-orange-300',
    label_zh: '\u9AD8',
    label_en: 'High',
  },
  MEDIUM: {
    bg: 'bg-yellow-100 dark:bg-yellow-900/40',
    text: 'text-yellow-700 dark:text-yellow-300',
    label_zh: '\u4E2D',
    label_en: 'Medium',
  },
  LOW: {
    bg: 'bg-blue-100 dark:bg-blue-900/40',
    text: 'text-blue-700 dark:text-blue-300',
    label_zh: '\u4F4E',
    label_en: 'Low',
  },
  NONE: {
    bg: 'bg-gray-100 dark:bg-gray-700',
    text: 'text-gray-600 dark:text-gray-400',
    label_zh: '\u65E0',
    label_en: 'None',
  },
};

interface StatusAction {
  label: { zh: string; en: string };
  commandCode: string;
  fromStatus: string[];
  style: string;
}

const STATUS_ACTIONS: StatusAction[] = [
  {
    label: { zh: '\u5F00\u59CB', en: 'Start' },
    commandCode: 'pm:start_task',
    fromStatus: ['todo'],
    style: 'bg-blue-600 hover:bg-blue-700 text-white',
  },
  {
    label: { zh: '\u5B8C\u6210', en: 'Complete' },
    commandCode: 'pm:complete_task',
    fromStatus: ['in_progress'],
    style: 'bg-green-600 hover:bg-green-700 text-white',
  },
  {
    label: { zh: '\u53D6\u6D88', en: 'Cancel' },
    commandCode: 'pm:cancel_task',
    fromStatus: ['todo', 'in_progress'],
    style: 'bg-gray-600 hover:bg-gray-700 text-white',
  },
  {
    label: { zh: '\u91CD\u65B0\u6253\u5F00', en: 'Reopen' },
    commandCode: 'pm:reopen_task',
    fromStatus: ['done', 'cancelled'],
    style: 'bg-amber-600 hover:bg-amber-700 text-white',
  },
];

// ============================================================================
// Component
// ============================================================================

export default function TaskDetailDrawer({
  task,
  projectId,
  onClose,
  onTaskUpdate,
}: TaskDetailDrawerProps) {
  const { locale } = useI18n();
  const { showSuccessToast, showErrorToast } = useToastContext();

  const l = useCallback((zh: string, en: string) => (locale === 'zh-CN' ? zh : en), [locale]);

  const [actionLoading, setActionLoading] = useState<string | null>(null);
  const [showEditModal, setShowEditModal] = useState(false);
  const [deleteConfirm, setDeleteConfirm] = useState(false);

  // --- Subtasks ---
  const [subtasks, setSubtasks] = useState<TaskRecord[]>([]);
  const [subtasksLoading, setSubtasksLoading] = useState(false);

  // --- Detail tab ---
  const [activeTab, setActiveTab] = useState<DetailTab>('comments');

  // --- Comments ---
  const [comments, setComments] = useState<CommentRecord[]>([]);
  const [commentsLoading, setCommentsLoading] = useState(false);
  const [newComment, setNewComment] = useState('');
  const [commentSubmitting, setCommentSubmitting] = useState(false);

  // --- Activity ---
  const [activities, setActivities] = useState<ActivityRecord[]>([]);
  const [activitiesLoading, setActivitiesLoading] = useState(false);

  // ------ Fetch subtasks ------
  const fetchSubtasks = useCallback(async () => {
    setSubtasksLoading(true);
    try {
      const result = await get<{ records: TaskRecord[]; total: number }>(
        '/api/dynamic/pm-task/list',
        {
          filters: JSON.stringify([
            { fieldName: 'pm_task_parent_id', operator: 'EQ', value: task.pid },
          ]),
          pageSize: '100',
        },
      );
      if (ResultHelper.isSuccess(result) && result.data) {
        setSubtasks(result.data.records || []);
      }
    } catch {
      // silent
    } finally {
      setSubtasksLoading(false);
    }
  }, [task.pid]);

  useEffect(() => {
    fetchSubtasks();
  }, [fetchSubtasks]);

  // ------ Fetch comments ------
  const fetchComments = useCallback(async () => {
    setCommentsLoading(true);
    try {
      const result = await get<{ records: CommentRecord[]; total: number }>(
        '/api/dynamic/pm-task-comment/list',
        {
          filters: JSON.stringify([
            { fieldName: 'pm_tc_task_id', operator: 'EQ', value: task.pid },
          ]),
          pageSize: '200',
        },
      );
      if (ResultHelper.isSuccess(result) && result.data) {
        setComments(result.data.records || []);
      }
    } catch {
      // silent
    } finally {
      setCommentsLoading(false);
    }
  }, [task.pid]);

  // ------ Fetch activities ------
  const fetchActivities = useCallback(async () => {
    setActivitiesLoading(true);
    try {
      const result = await get<{ records: ActivityRecord[]; total: number }>(
        '/api/dynamic/pm-task-activity/list',
        {
          filters: JSON.stringify([
            { fieldName: 'pm_act_task_id', operator: 'EQ', value: task.pid },
          ]),
          pageSize: '200',
        },
      );
      if (ResultHelper.isSuccess(result) && result.data) {
        setActivities(result.data.records || []);
      }
    } catch {
      // silent
    } finally {
      setActivitiesLoading(false);
    }
  }, [task.pid]);

  // Load data on mount / tab switch
  useEffect(() => {
    if (activeTab === 'comments') fetchComments();
    else fetchActivities();
  }, [activeTab, fetchComments, fetchActivities]);

  // ------ Status action handler ------
  const handleStatusAction = useCallback(
    async (action: StatusAction) => {
      setActionLoading(action.commandCode);
      try {
        const result = await post<unknown>(`/api/meta/commands/execute/${action.commandCode}`, {
          targetRecordId: task.pid,
          operationType: 'update',
        });
        if (ResultHelper.isSuccess(result)) {
          showSuccessToast(l('\u64CD\u4F5C\u6210\u529F', 'Action completed'));
          onTaskUpdate();
        } else {
          showErrorToast(result.message || l('\u64CD\u4F5C\u5931\u8D25', 'Action failed'));
        }
      } catch (e: unknown) {
        const msg = e instanceof Error ? e.message : l('\u64CD\u4F5C\u5931\u8D25', 'Action failed');
        showErrorToast(msg);
      } finally {
        setActionLoading(null);
      }
    },
    [task.pid, l, showSuccessToast, showErrorToast, onTaskUpdate],
  );

  // ------ Delete handler ------
  const handleDelete = useCallback(async () => {
    setActionLoading('delete');
    try {
      const result = await post<unknown>('/api/meta/commands/execute/pm:delete_task', {
        targetRecordId: task.pid,
        operationType: 'delete',
      });
      if (ResultHelper.isSuccess(result)) {
        showSuccessToast(l('\u4EFB\u52A1\u5DF2\u5220\u9664', 'Task deleted'));
        onClose();
        onTaskUpdate();
      } else {
        showErrorToast(result.message || l('\u5220\u9664\u5931\u8D25', 'Delete failed'));
      }
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : l('\u5220\u9664\u5931\u8D25', 'Delete failed');
      showErrorToast(msg);
    } finally {
      setActionLoading(null);
      setDeleteConfirm(false);
    }
  }, [task.pid, l, showSuccessToast, showErrorToast, onClose, onTaskUpdate]);

  // ------ Add comment ------
  const handleAddComment = useCallback(async () => {
    if (!newComment.trim()) return;
    setCommentSubmitting(true);
    try {
      const result = await post<unknown>('/api/meta/commands/execute/pm:create_task_comment', {
        payload: {
          pm_tc_task_id: task.pid,
          pm_tc_content: newComment.trim(),
        },
        operationType: 'create',
      });
      if (ResultHelper.isSuccess(result)) {
        setNewComment('');
        fetchComments();
      } else {
        showErrorToast(result.message || l('\u8BC4\u8BBA\u5931\u8D25', 'Comment failed'));
      }
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : l('\u8BC4\u8BBA\u5931\u8D25', 'Comment failed');
      showErrorToast(msg);
    } finally {
      setCommentSubmitting(false);
    }
  }, [newComment, task.pid, fetchComments, l, showErrorToast]);

  // ------ Derived values ------
  const typeStyle = TYPE_STYLES[task.pm_task_type] ?? TYPE_STYLES.TASK;
  const statusStyle = STATUS_STYLES[task.pm_task_status] ?? STATUS_STYLES.TODO;
  const statusLabel = STATUS_LABELS[task.pm_task_status] ?? STATUS_LABELS.TODO;
  const priorityStyle = PRIORITY_STYLES[task.pm_task_priority] ?? PRIORITY_STYLES.NONE;
  const progress = task.pm_task_progress ?? 0;
  const taskExt = task as Record<string, unknown>;

  const availableActions = useMemo(
    () => STATUS_ACTIONS.filter((a) => a.fromStatus.includes(task.pm_task_status)),
    [task.pm_task_status],
  );

  // ---- Render ----
  return (
    <>
      {/* Backdrop */}
      <div
        className="fixed inset-0 z-40 bg-black/40"
        onClick={onClose}
        data-testid="task-detail-backdrop"
      />

      {/* Drawer */}
      <div
        className="animate-slide-in-right fixed inset-y-0 right-0 z-50 flex w-[480px] max-w-full flex-col bg-white shadow-2xl dark:bg-gray-800"
        data-testid="task-detail-drawer"
      >
        {/* ===== Header ===== */}
        <div className="flex items-start justify-between border-b border-gray-200 px-6 py-4 dark:border-gray-700">
          <div className="min-w-0 flex-1 pr-4">
            <h2
              className="text-lg font-semibold break-words text-gray-900 dark:text-white"
              data-testid="task-detail-title"
            >
              {task.pm_task_title}
            </h2>
            <div className="mt-2 flex items-center gap-2">
              <span
                className={`inline-flex items-center rounded px-2 py-0.5 text-xs font-medium ${typeStyle.bg} ${typeStyle.text}`}
                data-testid="task-detail-type"
              >
                {typeStyle.label}
              </span>
              <span
                className={`inline-flex items-center rounded px-2 py-0.5 text-xs font-medium ${statusStyle.bg} ${statusStyle.text}`}
                data-testid="task-detail-status"
              >
                {l(statusLabel.zh, statusLabel.en)}
              </span>
            </div>
          </div>
          <button
            onClick={onClose}
            className="mt-1 flex-shrink-0 text-gray-400 transition-colors hover:text-gray-600 dark:hover:text-gray-300"
            data-testid="task-detail-close"
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

        {/* ===== Actions bar ===== */}
        <div
          className="flex flex-wrap items-center gap-2 border-b border-gray-200 px-6 py-3 dark:border-gray-700"
          data-testid="task-detail-actions"
        >
          {availableActions.map((action) => (
            <button
              key={action.commandCode}
              onClick={() => handleStatusAction(action)}
              disabled={actionLoading !== null}
              className={`rounded-lg px-3 py-1.5 text-xs font-medium transition-colors disabled:cursor-not-allowed disabled:opacity-50 ${action.style}`}
              data-testid={`task-action-${action.commandCode}`}
            >
              {actionLoading === action.commandCode ? (
                <span className="flex items-center gap-1">
                  <span className="h-3 w-3 animate-spin rounded-full border-b-2 border-current" />
                  {l('\u5904\u7406\u4E2D', 'Processing')}
                </span>
              ) : (
                l(action.label.zh, action.label.en)
              )}
            </button>
          ))}

          <button
            onClick={() => setShowEditModal(true)}
            className="rounded-lg bg-gray-100 px-3 py-1.5 text-xs font-medium text-gray-700 transition-colors hover:bg-gray-200 dark:bg-gray-700 dark:text-gray-300 dark:hover:bg-gray-600"
            data-testid="task-action-edit"
          >
            {l('\u7F16\u8F91', 'Edit')}
          </button>

          {!deleteConfirm ? (
            <button
              onClick={() => setDeleteConfirm(true)}
              className="rounded-lg px-3 py-1.5 text-xs font-medium text-red-600 transition-colors hover:bg-red-50 dark:text-red-400 dark:hover:bg-red-900/20"
              data-testid="task-action-delete"
            >
              {l('\u5220\u9664', 'Delete')}
            </button>
          ) : (
            <div className="flex items-center gap-1">
              <button
                onClick={handleDelete}
                disabled={actionLoading === 'delete'}
                className="rounded-lg bg-red-600 px-3 py-1.5 text-xs font-medium text-white transition-colors hover:bg-red-700 disabled:opacity-50"
                data-testid="task-action-delete-confirm"
              >
                {actionLoading === 'delete'
                  ? l('\u5220\u9664\u4E2D...', 'Deleting...')
                  : l('\u786E\u8BA4\u5220\u9664', 'Confirm')}
              </button>
              <button
                onClick={() => setDeleteConfirm(false)}
                className="rounded-lg px-3 py-1.5 text-xs font-medium text-gray-600 transition-colors hover:bg-gray-100 dark:text-gray-400 dark:hover:bg-gray-700"
                data-testid="task-action-delete-cancel"
              >
                {l('\u53D6\u6D88', 'Cancel')}
              </button>
            </div>
          )}
        </div>

        {/* ===== Scrollable content ===== */}
        <div className="flex-1 overflow-y-auto">
          {/* --- Details section --- */}
          <div
            className="border-b border-gray-200 px-6 py-4 dark:border-gray-700"
            data-testid="task-detail-info"
          >
            <h3 className="mb-3 text-xs font-semibold tracking-wider text-gray-500 uppercase dark:text-gray-400">
              {l('\u8BE6\u60C5', 'Details')}
            </h3>
            <dl className="grid grid-cols-2 gap-x-4 gap-y-3">
              {/* Priority */}
              <div>
                <dt className="text-xs text-gray-500 dark:text-gray-400">
                  {l('\u4F18\u5148\u7EA7', 'Priority')}
                </dt>
                <dd className="mt-0.5">
                  <span
                    className={`inline-flex items-center rounded px-2 py-0.5 text-xs font-medium ${priorityStyle.bg} ${priorityStyle.text}`}
                    data-testid="task-detail-priority"
                  >
                    {l(priorityStyle.label_zh, priorityStyle.label_en)}
                  </span>
                </dd>
              </div>

              {/* Assignee */}
              <div>
                <dt className="text-xs text-gray-500 dark:text-gray-400">
                  {l('\u8D1F\u8D23\u4EBA', 'Assignee')}
                </dt>
                <dd
                  className="mt-0.5 text-sm text-gray-900 dark:text-gray-100"
                  data-testid="task-detail-assignee"
                >
                  {(task.pm_task_assignee_id as string) || l('\u672A\u5206\u914D', 'Unassigned')}
                </dd>
              </div>

              {/* Start Date */}
              <div>
                <dt className="text-xs text-gray-500 dark:text-gray-400">
                  {l('\u5F00\u59CB\u65E5\u671F', 'Start Date')}
                </dt>
                <dd
                  className="mt-0.5 text-sm text-gray-900 dark:text-gray-100"
                  data-testid="task-detail-start-date"
                >
                  {(taskExt.pm_task_start_date as string) || '-'}
                </dd>
              </div>

              {/* Due Date */}
              <div>
                <dt className="text-xs text-gray-500 dark:text-gray-400">
                  {l('\u622A\u6B62\u65E5\u671F', 'Due Date')}
                </dt>
                <dd
                  className="mt-0.5 text-sm text-gray-900 dark:text-gray-100"
                  data-testid="task-detail-due-date"
                >
                  {task.pm_task_due_date || '-'}
                </dd>
              </div>

              {/* Estimated Hours */}
              <div>
                <dt className="text-xs text-gray-500 dark:text-gray-400">
                  {l('\u9884\u4F30\u5DE5\u65F6', 'Est. Hours')}
                </dt>
                <dd
                  className="mt-0.5 text-sm text-gray-900 dark:text-gray-100"
                  data-testid="task-detail-estimated-hours"
                >
                  {taskExt.pm_task_estimated_hours != null
                    ? String(taskExt.pm_task_estimated_hours)
                    : '-'}
                </dd>
              </div>

              {/* Actual Hours */}
              <div>
                <dt className="text-xs text-gray-500 dark:text-gray-400">
                  {l('\u5B9E\u9645\u5DE5\u65F6', 'Act. Hours')}
                </dt>
                <dd
                  className="mt-0.5 text-sm text-gray-900 dark:text-gray-100"
                  data-testid="task-detail-actual-hours"
                >
                  {taskExt.pm_task_actual_hours != null
                    ? String(taskExt.pm_task_actual_hours)
                    : '-'}
                </dd>
              </div>
            </dl>

            {/* Progress bar */}
            <div className="mt-4" data-testid="task-detail-progress">
              <div className="mb-1 flex items-center justify-between text-xs text-gray-500 dark:text-gray-400">
                <span>{l('\u8FDB\u5EA6', 'Progress')}</span>
                <span>{progress}%</span>
              </div>
              <div className="h-2 w-full overflow-hidden rounded-full bg-gray-200 dark:bg-gray-700">
                <div
                  className={`h-full rounded-full transition-all duration-300 ${
                    progress >= 100
                      ? 'bg-green-500'
                      : progress >= 50
                        ? 'bg-blue-500'
                        : 'bg-amber-500'
                  }`}
                  style={{ width: `${Math.min(progress, 100)}%` }}
                />
              </div>
            </div>
          </div>

          {/* --- Description section --- */}
          <div
            className="border-b border-gray-200 px-6 py-4 dark:border-gray-700"
            data-testid="task-detail-description"
          >
            <h3 className="mb-2 text-xs font-semibold tracking-wider text-gray-500 uppercase dark:text-gray-400">
              {l('\u63CF\u8FF0', 'Description')}
            </h3>
            <p className="text-sm whitespace-pre-wrap text-gray-700 dark:text-gray-300">
              {(taskExt.pm_task_description as string) ||
                l('\u6682\u65E0\u63CF\u8FF0', 'No description')}
            </p>
          </div>

          {/* --- Subtasks section --- */}
          {(subtasks.length > 0 || subtasksLoading) && (
            <div
              className="border-b border-gray-200 px-6 py-4 dark:border-gray-700"
              data-testid="task-subtasks-section"
            >
              <h3 className="mb-3 text-xs font-semibold tracking-wider text-gray-500 uppercase dark:text-gray-400">
                {l('\u5B50\u4EFB\u52A1', 'Subtasks')} ({subtasks.length})
              </h3>
              {subtasksLoading ? (
                <div className="py-2 text-center text-sm text-gray-400">
                  {l('\u52A0\u8F7D\u4E2D...', 'Loading...')}
                </div>
              ) : (
                <div className="space-y-1.5">
                  {subtasks.map((sub) => {
                    const subStatus = STATUS_STYLES[sub.pm_task_status] ?? STATUS_STYLES.TODO;
                    const subStatusLabel = STATUS_LABELS[sub.pm_task_status] ?? STATUS_LABELS.TODO;
                    const isDone =
                      sub.pm_task_status === 'done' || sub.pm_task_status === 'cancelled';
                    return (
                      <div
                        key={sub.pid}
                        className="flex cursor-pointer items-center gap-2 rounded px-2 py-1.5 transition-colors hover:bg-gray-50 dark:hover:bg-gray-700/50"
                        data-testid={`subtask-${sub.pid}`}
                        onClick={() => {
                          onClose();
                          setTimeout(() => onTaskUpdate(), 50);
                        }}
                      >
                        <span
                          className={`h-1.5 w-1.5 flex-shrink-0 rounded-full ${isDone ? 'bg-green-500' : 'bg-gray-400'}`}
                        />
                        <span
                          className={`min-w-0 flex-1 truncate text-sm ${isDone ? 'text-gray-400 line-through dark:text-gray-500' : 'text-gray-800 dark:text-gray-200'}`}
                        >
                          {sub.pm_task_title}
                        </span>
                        <span
                          className={`inline-flex items-center rounded px-1.5 py-0.5 text-[10px] font-medium ${subStatus.bg} ${subStatus.text}`}
                        >
                          {l(subStatusLabel.zh, subStatusLabel.en)}
                        </span>
                      </div>
                    );
                  })}
                </div>
              )}
            </div>
          )}

          {/* --- Comments / Activity tabs --- */}
          <div className="px-6 pt-3">
            <div className="mb-3 flex border-b border-gray-200 dark:border-gray-700">
              {(['comments', 'activity'] as DetailTab[]).map((tab) => {
                const tabLabel =
                  tab === 'comments'
                    ? l('\u8BC4\u8BBA', 'Comments')
                    : l('\u6D3B\u52A8', 'Activity');
                const isActive = activeTab === tab;
                return (
                  <button
                    key={tab}
                    onClick={() => setActiveTab(tab)}
                    className={`border-b-2 px-3 py-2 text-sm font-medium transition-colors ${
                      isActive
                        ? 'border-blue-600 text-blue-600 dark:border-blue-400 dark:text-blue-400'
                        : 'border-transparent text-gray-500 hover:text-gray-700 dark:text-gray-400 dark:hover:text-gray-300'
                    }`}
                    data-testid={`detail-tab-${tab}`}
                  >
                    {tabLabel}
                  </button>
                );
              })}
            </div>
          </div>

          {/* --- Comments content --- */}
          {activeTab === 'comments' && (
            <div className="px-6 pb-4" data-testid="task-comments-section">
              {/* Add comment */}
              <div className="mb-4 flex gap-2">
                <textarea
                  value={newComment}
                  onChange={(e) => setNewComment(e.target.value)}
                  rows={2}
                  className="flex-1 resize-none rounded-lg border border-gray-300 bg-white px-3 py-2 text-sm text-gray-900 outline-none focus:border-blue-500 focus:ring-2 focus:ring-blue-500 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-100"
                  placeholder={l('\u8F93\u5165\u8BC4\u8BBA...', 'Write a comment...')}
                  data-testid="comment-input"
                />
                <button
                  onClick={handleAddComment}
                  disabled={commentSubmitting || !newComment.trim()}
                  className="self-end rounded-lg bg-blue-600 px-3 py-2 text-xs font-medium text-white transition-colors hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-50"
                  data-testid="comment-submit"
                >
                  {commentSubmitting
                    ? l('\u53D1\u9001\u4E2D', 'Sending')
                    : l('\u53D1\u9001', 'Send')}
                </button>
              </div>

              {/* Comments list */}
              {commentsLoading ? (
                <div className="py-4 text-center text-sm text-gray-400">
                  {l('\u52A0\u8F7D\u4E2D...', 'Loading...')}
                </div>
              ) : comments.length === 0 ? (
                <div
                  className="py-4 text-center text-sm text-gray-400"
                  data-testid="comments-empty"
                >
                  {l('\u6682\u65E0\u8BC4\u8BBA', 'No comments yet')}
                </div>
              ) : (
                <div className="space-y-3">
                  {comments.map((c) => (
                    <div
                      key={c.pid}
                      className="rounded-lg bg-gray-50 p-3 dark:bg-gray-900/50"
                      data-testid={`comment-${c.pid}`}
                    >
                      <div className="mb-1 flex items-center justify-between">
                        <span className="text-xs font-medium text-gray-700 dark:text-gray-300">
                          {c.created_by || l('\u672A\u77E5', 'Unknown')}
                        </span>
                        <span className="text-xs text-gray-400">
                          {c.created_at ? new Date(c.created_at).toLocaleString(locale) : ''}
                        </span>
                      </div>
                      <p className="text-sm whitespace-pre-wrap text-gray-800 dark:text-gray-200">
                        {c.pm_tc_content}
                      </p>
                    </div>
                  ))}
                </div>
              )}
            </div>
          )}

          {/* --- Activity content --- */}
          {activeTab === 'activity' && (
            <div className="px-6 pb-4" data-testid="task-activity-section">
              {activitiesLoading ? (
                <div className="py-4 text-center text-sm text-gray-400">
                  {l('\u52A0\u8F7D\u4E2D...', 'Loading...')}
                </div>
              ) : activities.length === 0 ? (
                <div
                  className="py-4 text-center text-sm text-gray-400"
                  data-testid="activity-empty"
                >
                  {l('\u6682\u65E0\u6D3B\u52A8', 'No activity yet')}
                </div>
              ) : (
                <div className="relative">
                  {/* Timeline line */}
                  <div className="absolute top-2 bottom-2 left-2 w-px bg-gray-200 dark:bg-gray-700" />
                  <div className="space-y-4">
                    {activities.map((a) => (
                      <div
                        key={a.pid}
                        className="relative flex gap-3"
                        data-testid={`activity-${a.pid}`}
                      >
                        {/* Dot */}
                        <div className="z-10 mt-0.5 h-4 w-4 flex-shrink-0 rounded-full border-2 border-white bg-blue-500 dark:border-gray-800" />
                        <div className="min-w-0 flex-1">
                          <div className="text-sm text-gray-800 dark:text-gray-200">
                            <span className="font-medium">{a.pm_act_action}</span>
                            {a.pm_act_field_changed && (
                              <span className="text-gray-500 dark:text-gray-400">
                                {' '}
                                {a.pm_act_field_changed}
                              </span>
                            )}
                          </div>
                          {(a.pm_act_old_value || a.pm_act_new_value) && (
                            <div className="mt-0.5 text-xs text-gray-500 dark:text-gray-400">
                              {a.pm_act_old_value && (
                                <span className="mr-2 line-through">{a.pm_act_old_value}</span>
                              )}
                              {a.pm_act_new_value && (
                                <span className="text-gray-700 dark:text-gray-300">
                                  {a.pm_act_new_value}
                                </span>
                              )}
                            </div>
                          )}
                          <div className="mt-0.5 text-xs text-gray-400">
                            {a.created_by && <span>{a.created_by} &middot; </span>}
                            {a.created_at ? new Date(a.created_at).toLocaleString(locale) : ''}
                          </div>
                        </div>
                      </div>
                    ))}
                  </div>
                </div>
              )}
            </div>
          )}
        </div>
      </div>

      {/* Edit modal */}
      {showEditModal && (
        <TaskFormModal
          projectId={projectId}
          task={task}
          onClose={() => setShowEditModal(false)}
          onSuccess={() => {
            setShowEditModal(false);
            onTaskUpdate();
          }}
        />
      )}
    </>
  );
}
