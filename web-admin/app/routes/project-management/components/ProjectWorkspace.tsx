import { useState, useCallback, useEffect } from 'react';
import { useSearchParams, useNavigate } from 'react-router';
import { post } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';
import { useI18n } from '~/contexts/I18nContext';
import { useToastContext } from '~/contexts/ToastContext';
import type { TaskRecord } from './types';
import ProjectOverview from './ProjectOverview';
import ProjectContracts from './ProjectContracts';
import ProjectCosts from './ProjectCosts';
import MemberManager from './MemberManager';
import ProjectSettings from './ProjectSettings';
import TaskBoard from './TaskBoard';
import TaskListView from './TaskListView';
import TaskGanttView from './TaskGanttView';
import TaskFormModal from './TaskFormModal';
import TaskDetailDrawer from './TaskDetailDrawer';

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

type ProjectStatus = 'planning' | 'in_progress' | 'completed' | 'archived' | 'cancelled';
type TabKey = 'overview' | 'tasks' | 'contracts' | 'costs' | 'members' | 'settings';
type TaskView = 'kanban' | 'list' | 'gantt';

interface ProjectWorkspaceProps {
  projectId: string;
  projectData: Record<string, unknown>;
  onProjectUpdate: () => void;
}

// ---------------------------------------------------------------------------
// Status config
// ---------------------------------------------------------------------------

const STATUS_STYLES: Record<ProjectStatus, { bg: string; text: string; dot: string }> = {
  planning: {
    bg: 'bg-blue-100 dark:bg-blue-900/30',
    text: 'text-blue-700 dark:text-blue-300',
    dot: 'bg-blue-500',
  },
  in_progress: {
    bg: 'bg-amber-100 dark:bg-amber-900/30',
    text: 'text-amber-700 dark:text-amber-300',
    dot: 'bg-amber-500',
  },
  completed: {
    bg: 'bg-green-100 dark:bg-green-900/30',
    text: 'text-green-700 dark:text-green-300',
    dot: 'bg-green-500',
  },
  archived: {
    bg: 'bg-gray-100 dark:bg-gray-700/50',
    text: 'text-gray-600 dark:text-gray-400',
    dot: 'bg-gray-400',
  },
  cancelled: {
    bg: 'bg-red-100 dark:bg-red-900/30',
    text: 'text-red-700 dark:text-red-300',
    dot: 'bg-red-500',
  },
};

const STATUS_LABELS: Record<ProjectStatus, { zh: string; en: string }> = {
  planning: { zh: '规划中', en: 'Planning' },
  in_progress: { zh: '进行中', en: 'In Progress' },
  completed: { zh: '已完成', en: 'Completed' },
  archived: { zh: '已归档', en: 'Archived' },
  cancelled: { zh: '已取消', en: 'Cancelled' },
};

interface StatusAction {
  label: { zh: string; en: string };
  commandCode: string;
  fromStatus: ProjectStatus[];
  style: string;
}

const STATUS_ACTIONS: StatusAction[] = [
  {
    label: { zh: '启动项目', en: 'Activate' },
    commandCode: 'pm:activate_project',
    fromStatus: ['planning'],
    style: 'bg-blue-600 hover:bg-blue-700 text-white',
  },
  {
    label: { zh: '完成项目', en: 'Complete' },
    commandCode: 'pm:complete_project',
    fromStatus: ['in_progress'],
    style: 'bg-green-600 hover:bg-green-700 text-white',
  },
  {
    label: { zh: '归档项目', en: 'Archive' },
    commandCode: 'pm:archive_project',
    fromStatus: ['completed'],
    style: 'bg-gray-600 hover:bg-gray-700 text-white',
  },
];

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

export default function ProjectWorkspace({
  projectId,
  projectData,
  onProjectUpdate,
}: ProjectWorkspaceProps) {
  const { locale } = useI18n();
  const { showSuccessToast, showErrorToast } = useToastContext();
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const [activeTab, setActiveTab] = useState<TabKey>('tasks');
  const [taskView, setTaskView] = useState<TaskView>('kanban');
  const [actionLoading, setActionLoading] = useState<string | null>(null);
  const [refreshKey, setRefreshKey] = useState(0);

  // Task modal / drawer state
  const [showTaskForm, setShowTaskForm] = useState(false);
  const [editingTask, setEditingTask] = useState<TaskRecord | null>(null);
  const [selectedTask, setSelectedTask] = useState<TaskRecord | null>(null);

  const l = useCallback((zh: string, en: string) => (locale === 'zh-CN' ? zh : en), [locale]);

  const status = ((projectData.pm_project_status as string) || 'planning') as ProjectStatus;
  const statusStyle = STATUS_STYLES[status] || STATUS_STYLES.planning;
  const statusLabel = STATUS_LABELS[status] || STATUS_LABELS.planning;

  // Open task drawer from URL param (e.g., ?task=xxx from My Tasks)
  // This is a one-time effect handled by the parent route

  const handleStatusAction = useCallback(
    async (action: StatusAction) => {
      setActionLoading(action.commandCode);
      try {
        const result = await post<unknown>(`/api/meta/commands/execute/${action.commandCode}`, {
          targetRecordId: projectId,
          operationType: 'update',
        });
        if (ResultHelper.isSuccess(result)) {
          showSuccessToast(l('操作成功', 'Action completed'));
          onProjectUpdate();
        } else {
          showErrorToast(result.message || l('操作失败', 'Action failed'));
        }
      } catch (e: unknown) {
        showErrorToast(e instanceof Error ? e.message : l('操作失败', 'Action failed'));
      } finally {
        setActionLoading(null);
      }
    },
    [projectId, l, showSuccessToast, showErrorToast, onProjectUpdate],
  );

  const availableActions = STATUS_ACTIONS.filter((a) => a.fromStatus.includes(status));
  const refreshTasks = useCallback(() => setRefreshKey((k) => k + 1), []);

  const handleTaskClick = useCallback((task: TaskRecord) => setSelectedTask(task), []);
  const handleCreateTask = useCallback(() => {
    setEditingTask(null);
    setShowTaskForm(true);
  }, []);

  // ---- Keyboard shortcuts ----
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      // Skip if user is typing in an input/textarea/select
      const tag = (e.target as HTMLElement)?.tagName;
      if (tag === 'input' || tag === 'textarea' || tag === 'select') return;

      if (e.key === 'n' || e.key === 'N') {
        e.preventDefault();
        handleCreateTask();
      }
    };
    document.addEventListener('keydown', handler);
    return () => document.removeEventListener('keydown', handler);
  }, [handleCreateTask]);

  const tabs: { key: TabKey; label: { zh: string; en: string } }[] = [
    { key: 'overview', label: { zh: '概览', en: 'Overview' } },
    { key: 'tasks', label: { zh: '任务', en: 'Tasks' } },
    { key: 'contracts', label: { zh: '合同', en: 'Contracts' } },
    { key: 'costs', label: { zh: '成本', en: 'Costs' } },
    { key: 'members', label: { zh: '成员', en: 'Members' } },
    { key: 'settings', label: { zh: '设置', en: 'Settings' } },
  ];

  const viewButtons: { key: TaskView; label: string }[] = [
    { key: 'kanban', label: l('看板', 'Kanban') },
    { key: 'list', label: l('列表', 'List') },
    { key: 'gantt', label: l('甘特图', 'Gantt') },
  ];

  return (
    <div className="flex h-full flex-col" data-testid="project-workspace">
      {/* Breadcrumb */}
      <div
        className="flex items-center gap-1.5 bg-white px-6 pt-3 pb-1 text-xs dark:bg-gray-800"
        data-testid="project-breadcrumb"
      >
        <button
          onClick={() => navigate('/project-management/projects')}
          className="text-gray-500 transition-colors hover:text-blue-600 dark:text-gray-400 dark:hover:text-blue-400"
        >
          {l('项目管理', 'Projects')}
        </button>
        <svg
          className="h-3 w-3 text-gray-400"
          fill="none"
          viewBox="0 0 24 24"
          strokeWidth={2}
          stroke="currentColor"
        >
          <path strokeLinecap="round" strokeLinejoin="round" d="M8.25 4.5l7.5 7.5-7.5 7.5" />
        </svg>
        <span className="max-w-[200px] truncate font-medium text-gray-700 dark:text-gray-300">
          {projectData.pm_project_name as string}
        </span>
      </div>

      {/* Header */}
      <div
        className="flex items-center justify-between border-b border-gray-200 bg-white px-6 py-4 dark:border-gray-700 dark:bg-gray-800"
        data-testid="project-header"
      >
        <div className="flex min-w-0 items-center gap-3">
          <h1
            className="truncate text-xl font-bold text-gray-900 dark:text-white"
            data-testid="project-name"
          >
            {projectData.pm_project_name as string}
          </h1>
          <span
            className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-medium whitespace-nowrap ${statusStyle.bg} ${statusStyle.text}`}
            data-testid="project-status-badge"
          >
            <span className={`h-1.5 w-1.5 rounded-full ${statusStyle.dot}`} />
            {l(statusLabel.zh, statusLabel.en)}
          </span>
        </div>
        {availableActions.length > 0 && (
          <div className="ml-4 flex flex-shrink-0 items-center gap-2">
            {availableActions.map((action) => (
              <button
                key={action.commandCode}
                onClick={() => handleStatusAction(action)}
                disabled={actionLoading !== null}
                className={`rounded-lg px-4 py-2 text-sm font-medium transition-colors disabled:opacity-50 ${action.style}`}
                data-testid={`action-${action.commandCode}`}
              >
                {actionLoading === action.commandCode
                  ? l('处理中...', 'Processing...')
                  : l(action.label.zh, action.label.en)}
              </button>
            ))}
          </div>
        )}
      </div>

      {/* Tab Navigation */}
      <div
        className="flex items-center justify-between border-b border-gray-200 bg-white px-6 dark:border-gray-700 dark:bg-gray-800"
        data-testid="project-tabs"
      >
        <div className="flex">
          {tabs.map((tab) => (
            <button
              key={tab.key}
              onClick={() => setActiveTab(tab.key)}
              className={`border-b-2 px-4 py-3 text-sm font-medium transition-colors ${
                activeTab === tab.key
                  ? 'border-blue-600 text-blue-600 dark:border-blue-400 dark:text-blue-400'
                  : 'border-transparent text-gray-500 hover:text-gray-700 dark:text-gray-400 dark:hover:text-gray-300'
              }`}
              data-testid={`tab-${tab.key}`}
            >
              {l(tab.label.zh, tab.label.en)}
            </button>
          ))}
        </div>
        {/* View toggle for tasks tab */}
        {activeTab === 'tasks' && (
          <div
            className="flex items-center gap-1 rounded-lg bg-gray-100 p-1 dark:bg-gray-700"
            data-testid="task-view-toggle"
          >
            {viewButtons.map((v) => (
              <button
                key={v.key}
                onClick={() => setTaskView(v.key)}
                className={`rounded-md px-3 py-1 text-xs font-medium transition-colors ${
                  taskView === v.key
                    ? 'bg-white text-gray-900 shadow-sm dark:bg-gray-600 dark:text-white'
                    : 'text-gray-500 hover:text-gray-700 dark:text-gray-400'
                }`}
                data-testid={`view-${v.key}`}
              >
                {v.label}
              </button>
            ))}
          </div>
        )}
      </div>

      {/* Tab Content */}
      <div
        className="flex-1 overflow-auto bg-gray-50 dark:bg-gray-900"
        data-testid="project-tab-content"
      >
        {activeTab === 'overview' && (
          <div className="p-6">
            <ProjectOverview projectId={projectId} />
          </div>
        )}
        {activeTab === 'tasks' && (
          <div className="p-6">
            {taskView === 'kanban' && (
              <TaskBoard
                projectId={projectId}
                onTaskClick={handleTaskClick}
                onCreateTask={handleCreateTask}
                refreshKey={refreshKey}
              />
            )}
            {taskView === 'list' && (
              <TaskListView
                projectId={projectId}
                onTaskClick={handleTaskClick}
                onCreateTask={handleCreateTask}
                refreshKey={refreshKey}
              />
            )}
            {taskView === 'gantt' && (
              <TaskGanttView
                projectId={projectId}
                onTaskClick={handleTaskClick}
                refreshKey={refreshKey}
              />
            )}
          </div>
        )}
        {activeTab === 'contracts' && (
          <div className="p-6">
            <ProjectContracts projectId={projectId} />
          </div>
        )}
        {activeTab === 'costs' && (
          <div className="p-6">
            <ProjectCosts projectId={projectId} />
          </div>
        )}
        {activeTab === 'members' && (
          <div className="p-6">
            <MemberManager projectId={projectId} />
          </div>
        )}
        {activeTab === 'settings' && (
          <div className="p-6">
            <ProjectSettings
              projectId={projectId}
              projectData={projectData}
              onProjectUpdate={onProjectUpdate}
            />
          </div>
        )}
      </div>

      {/* Task Form Modal */}
      {showTaskForm && (
        <TaskFormModal
          projectId={projectId}
          task={editingTask}
          onClose={() => {
            setShowTaskForm(false);
            setEditingTask(null);
          }}
          onSuccess={() => {
            setShowTaskForm(false);
            setEditingTask(null);
            refreshTasks();
          }}
        />
      )}

      {/* Task Detail Drawer */}
      {selectedTask && (
        <TaskDetailDrawer
          task={selectedTask}
          projectId={projectId}
          onClose={() => setSelectedTask(null)}
          onTaskUpdate={() => {
            refreshTasks();
          }}
        />
      )}
    </div>
  );
}
