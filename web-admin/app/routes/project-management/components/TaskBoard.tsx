import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  DndContext,
  DragOverlay,
  PointerSensor,
  useSensor,
  useSensors,
  closestCorners,
  type DragEndEvent,
  type DragStartEvent,
} from '@dnd-kit/core';
import { SortableContext, verticalListSortingStrategy, useSortable } from '@dnd-kit/sortable';
import { useDroppable } from '@dnd-kit/core';
import { CSS } from '@dnd-kit/utilities';

import { useI18n } from '~/contexts/I18nContext';
import { useToastContext } from '~/contexts/ToastContext';
import { get, post } from '~/shared/services/http-client';
import TaskCard, { type TaskRecord } from './TaskCard';

// ============================================================================
// Types
// ============================================================================

interface TaskBoardProps {
  projectId: string;
  onTaskClick: (task: TaskRecord) => void;
  onCreateTask: () => void;
  refreshKey?: number;
}

type ColumnStatus = 'todo' | 'in_progress' | 'done' | 'cancelled';

interface ColumnDef {
  status: ColumnStatus;
  label_zh: string;
  label_en: string;
  headerColor: string;
}

// ============================================================================
// Constants
// ============================================================================

const COLUMNS: ColumnDef[] = [
  { status: 'todo', label_zh: '待办', label_en: 'To Do', headerColor: 'bg-gray-400' },
  {
    status: 'in_progress',
    label_zh: '进行中',
    label_en: 'In Progress',
    headerColor: 'bg-blue-500',
  },
  { status: 'done', label_zh: '已完成', label_en: 'Done', headerColor: 'bg-green-500' },
  { status: 'cancelled', label_zh: '已取消', label_en: 'Cancelled', headerColor: 'bg-red-400' },
];

const TERMINAL_STATUSES = new Set<ColumnStatus>(['done', 'cancelled']);

const STATUS_COMMAND_MAP: Record<ColumnStatus, string> = {
  todo: 'pm:reopen_task',
  in_progress: 'pm:start_task',
  done: 'pm:complete_task',
  cancelled: 'pm:cancel_task',
};

// ============================================================================
// SortableTaskCard
// ============================================================================

function SortableTaskCard({
  task,
  onClick,
  disabled,
}: {
  task: TaskRecord;
  onClick: (task: TaskRecord) => void;
  disabled?: boolean;
}) {
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({
    id: task.pid,
    disabled,
  });

  const style = {
    transform: CSS.Transform.toString(transform),
    transition,
  };

  return (
    <div
      ref={setNodeRef}
      style={style}
      {...attributes}
      {...(disabled ? {} : listeners)}
      className={disabled ? 'opacity-60' : ''}
    >
      <TaskCard task={task} onClick={onClick} isDragging={isDragging} />
    </div>
  );
}

// ============================================================================
// DroppableColumn
// ============================================================================

function DroppableColumn({
  column,
  tasks,
  locale,
  onTaskClick,
  onCreateTask,
  isTerminal,
}: {
  column: ColumnDef;
  tasks: TaskRecord[];
  locale: string;
  onTaskClick: (task: TaskRecord) => void;
  onCreateTask?: () => void;
  isTerminal?: boolean;
}) {
  const l = useCallback((zh: string, en: string) => (locale === 'zh-CN' ? zh : en), [locale]);

  const { setNodeRef, isOver } = useDroppable({ id: column.status });

  const taskIds = useMemo(() => tasks.map((t) => t.pid), [tasks]);

  return (
    <div
      ref={setNodeRef}
      data-testid={`board-column-${column.status}`}
      className={`flex min-h-[200px] max-w-[360px] min-w-[260px] flex-1 flex-col rounded-lg bg-gray-50 transition-colors duration-150 dark:bg-gray-800/50 ${isOver ? 'bg-blue-50 ring-2 ring-blue-400 dark:bg-blue-900/20' : ''} `}
    >
      {/* Column header */}
      <div className="flex items-center justify-between border-b border-gray-200 px-3 py-2.5 dark:border-gray-700">
        <div className="flex items-center gap-2">
          <span className={`h-2.5 w-2.5 rounded-full ${column.headerColor}`} />
          <span className="text-sm font-semibold text-gray-700 dark:text-gray-200">
            {l(column.label_zh, column.label_en)}
          </span>
          <span
            data-testid={`column-count-${column.status}`}
            className="text-xs font-medium text-gray-400 dark:text-gray-500"
          >
            ({tasks.length})
          </span>
        </div>

        {column.status === 'todo' && onCreateTask && (
          <button
            data-testid="board-add-task-btn"
            onClick={onCreateTask}
            className="flex h-6 w-6 items-center justify-center rounded text-gray-500 transition-colors hover:bg-gray-200 dark:text-gray-400 dark:hover:bg-gray-700"
            title={l('新建任务', 'New Task')}
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
          </button>
        )}
      </div>

      {/* Cards */}
      <div className="flex-1 space-y-2 overflow-y-auto p-2">
        <SortableContext items={taskIds} strategy={verticalListSortingStrategy}>
          {tasks.map((task) => (
            <SortableTaskCard
              key={task.pid}
              task={task}
              onClick={onTaskClick}
              disabled={isTerminal}
            />
          ))}
        </SortableContext>

        {tasks.length === 0 && (
          <div
            data-testid={`column-empty-${column.status}`}
            className="flex h-20 items-center justify-center text-xs text-gray-400 dark:text-gray-500"
          >
            {l('暂无任务', 'No tasks')}
          </div>
        )}
      </div>
    </div>
  );
}

// ============================================================================
// TaskBoard (main)
// ============================================================================

export default function TaskBoard({
  projectId,
  onTaskClick,
  onCreateTask,
  refreshKey,
}: TaskBoardProps) {
  const { locale } = useI18n();
  const { showErrorToast } = useToastContext();
  const l = useCallback((zh: string, en: string) => (locale === 'zh-CN' ? zh : en), [locale]);

  const [tasks, setTasks] = useState<TaskRecord[]>([]);
  const [loading, setLoading] = useState(false);
  const [activeTask, setActiveTask] = useState<TaskRecord | null>(null);

  // ---- Filters ----
  const [filterPriority, setFilterPriority] = useState<string>('');
  const [filterSearch, setFilterSearch] = useState('');

  // Snapshot for optimistic rollback
  const snapshotRef = useRef<TaskRecord[]>([]);

  // ---- Sensors ----
  const sensors = useSensors(useSensor(PointerSensor, { activationConstraint: { distance: 5 } }));

  // ---- Fetch tasks ----
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
      showErrorToast(l('加载任务失败', 'Failed to load tasks'));
    } finally {
      setLoading(false);
    }
  }, [projectId, showErrorToast, l]);

  useEffect(() => {
    if (projectId) {
      fetchTasks();
    }
  }, [projectId, refreshKey, fetchTasks]);

  // ---- Filter tasks ----
  const filteredTasks = useMemo(() => {
    let result = tasks;
    if (filterPriority) {
      result = result.filter((t) => t.pm_task_priority === filterPriority);
    }
    if (filterSearch.trim()) {
      const q = filterSearch.trim().toLowerCase();
      result = result.filter((t) => t.pm_task_title?.toLowerCase().includes(q));
    }
    return result;
  }, [tasks, filterPriority, filterSearch]);

  // ---- Group tasks by status ----
  const grouped = useMemo(() => {
    const map: Record<ColumnStatus, TaskRecord[]> = {
      todo: [],
      in_progress: [],
      done: [],
      cancelled: [],
    };
    for (const t of filteredTasks) {
      const status = t.pm_task_status as ColumnStatus;
      if (map[status]) {
        map[status].push(t);
      } else {
        // Fallback: put unknown statuses into TODO
        map.todo.push(t);
      }
    }
    // Sort each column by sort_key
    for (const key of Object.keys(map) as ColumnStatus[]) {
      map[key].sort((a, b) => (a.pm_task_sort_key ?? 0) - (b.pm_task_sort_key ?? 0));
    }
    return map;
  }, [filteredTasks]);

  // ---- Drag handlers ----
  const handleDragStart = useCallback(
    (event: DragStartEvent) => {
      const draggedTask = tasks.find((t) => t.pid === event.active.id);
      if (draggedTask && TERMINAL_STATUSES.has(draggedTask.pm_task_status as ColumnStatus)) {
        return; // Block drag from terminal states
      }
      setActiveTask(draggedTask ?? null);
      snapshotRef.current = [...tasks];
    },
    [tasks],
  );

  const handleDragEnd = useCallback(
    async (event: DragEndEvent) => {
      setActiveTask(null);

      const { active, over } = event;
      if (!over) return;

      const taskPid = active.id as string;
      const task = tasks.find((t) => t.pid === taskPid);
      if (!task) return;

      // Determine target column: over.id could be a column status or another task pid
      let targetStatus: ColumnStatus | null = null;

      // Check if dropped directly on a column
      if (COLUMNS.some((c) => c.status === over.id)) {
        targetStatus = over.id as ColumnStatus;
      } else {
        // Dropped on a task — find which column that task belongs to
        const overTask = tasks.find((t) => t.pid === over.id);
        if (overTask) {
          targetStatus = overTask.pm_task_status as ColumnStatus;
        }
      }

      if (!targetStatus || targetStatus === task.pm_task_status) return;

      // Optimistic update
      setTasks((prev) =>
        prev.map((t) => (t.pid === taskPid ? { ...t, pm_task_status: targetStatus } : t)),
      );

      // Execute status transition command
      const commandCode = STATUS_COMMAND_MAP[targetStatus];
      try {
        const result = await post(`/api/meta/commands/execute/${commandCode}`, {
          targetRecordId: taskPid,
          operationType: 'update',
        });
        if (result.code !== '0') {
          // Revert on failure
          setTasks(snapshotRef.current);
          showErrorToast(result.message || l('状态变更失败', 'Failed to update status'));
        }
      } catch {
        // Revert on error
        setTasks(snapshotRef.current);
        showErrorToast(l('状态变更失败', 'Failed to update status'));
      }
    },
    [tasks, showErrorToast, l],
  );

  // ---- Loading state ----
  if (loading && tasks.length === 0) {
    return (
      <div
        data-testid="task-board-loading"
        className="flex h-64 items-center justify-center text-gray-400 dark:text-gray-500"
      >
        <svg className="mr-2 h-6 w-6 animate-spin" fill="none" viewBox="0 0 24 24">
          <circle
            className="opacity-25"
            cx="12"
            cy="12"
            r="10"
            stroke="currentColor"
            strokeWidth="4"
          />
          <path
            className="opacity-75"
            fill="currentColor"
            d="M4 12a8 8 0 018-8v4a4 4 0 00-4 4H4z"
          />
        </svg>
        {l('加载中...', 'Loading...')}
      </div>
    );
  }

  const hasFilters = filterPriority !== '' || filterSearch.trim() !== '';

  return (
    <div data-testid="task-board" className="flex h-full flex-col gap-3">
      {/* Filter bar */}
      <div className="flex flex-wrap items-center gap-3" data-testid="board-filter-bar">
        <div className="relative flex-shrink-0">
          <svg
            className="absolute top-1/2 left-2.5 h-3.5 w-3.5 -translate-y-1/2 text-gray-400"
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
            data-testid="board-filter-search"
            value={filterSearch}
            onChange={(e) => setFilterSearch(e.target.value)}
            placeholder={l('搜索任务...', 'Search...')}
            className="w-48 rounded-lg border border-gray-300 bg-white py-1.5 pr-3 pl-8 text-xs text-gray-900 placeholder-gray-400 focus:ring-1 focus:ring-blue-500 focus:outline-none dark:border-gray-600 dark:bg-gray-800 dark:text-gray-100"
          />
        </div>
        <select
          data-testid="board-filter-priority"
          value={filterPriority}
          onChange={(e) => setFilterPriority(e.target.value)}
          className="rounded-lg border border-gray-300 bg-white px-2 py-1.5 text-xs text-gray-700 focus:ring-1 focus:ring-blue-500 focus:outline-none dark:border-gray-600 dark:bg-gray-800 dark:text-gray-300"
        >
          <option value="">{l('全部优先级', 'All Priorities')}</option>
          <option value="critical">{l('紧急', 'Critical')}</option>
          <option value="high">{l('高', 'High')}</option>
          <option value="medium">{l('中', 'Medium')}</option>
          <option value="low">{l('低', 'Low')}</option>
        </select>
        {hasFilters && (
          <button
            onClick={() => {
              setFilterPriority('');
              setFilterSearch('');
            }}
            className="text-xs text-blue-600 hover:underline dark:text-blue-400"
            data-testid="board-filter-clear"
          >
            {l('清除筛选', 'Clear filters')}
          </button>
        )}
      </div>

      <DndContext
        sensors={sensors}
        collisionDetection={closestCorners}
        onDragStart={handleDragStart}
        onDragEnd={handleDragEnd}
      >
        <div className="flex flex-1 gap-4 overflow-x-auto pb-2">
          {COLUMNS.map((col) => (
            <DroppableColumn
              key={col.status}
              column={col}
              tasks={grouped[col.status]}
              locale={locale}
              onTaskClick={onTaskClick}
              onCreateTask={col.status === 'todo' ? onCreateTask : undefined}
              isTerminal={TERMINAL_STATUSES.has(col.status)}
            />
          ))}
        </div>

        <DragOverlay>
          {activeTask ? (
            <div className="scale-105 rotate-2 rounded-lg shadow-xl">
              <TaskCard task={activeTask} onClick={() => {}} isDragging />
            </div>
          ) : null}
        </DragOverlay>
      </DndContext>
    </div>
  );
}
