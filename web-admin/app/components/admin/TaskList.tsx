/**
 * 上传任务列表组件
 * 显示文档上传任务的状态和进度
 */

import { useState, useEffect } from 'react';
import {
  type IngestionTask,
  getTaskList,
  getTaskStatus,
  formatFileSize,
  getFileTypeIcon,
  getStatusText,
  getStatusColorClass,
} from '~/services/documentService';
import dayjs from 'dayjs';

interface TaskListProps {
  refreshTrigger?: number;
  onTaskClick?: (task: IngestionTask) => void;
}

export function TaskList({ refreshTrigger, onTaskClick }: TaskListProps) {
  const [tasks, setTasks] = useState<IngestionTask[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [pollingTaskIds, setPollingTaskIds] = useState<Set<string>>(new Set());

  // 加载任务列表
  const loadTasks = async () => {
    try {
      setLoading(true);
      setError(null);
      const result = await getTaskList({ limit: 50, offset: 0 });
      setTasks(result.tasks);

      // 找出需要轮询的任务（pending 或 running）
      const needPolling = result.tasks
        .filter((t) => t.status === 'pending' || t.status === 'running')
        .map((t) => t.task_id);
      setPollingTaskIds(new Set(needPolling));
    } catch (err: any) {
      console.error('Failed to load tasks:', err);
      setError(err.message || '加载任务列表失败');
    } finally {
      setLoading(false);
    }
  };

  // 初始加载和刷新触发
  useEffect(() => {
    loadTasks();
  }, [refreshTrigger]);

  // 轮询处理中的任务
  useEffect(() => {
    if (pollingTaskIds.size === 0) return;

    const interval = setInterval(async () => {
      const updatedTasks = [...tasks];
      let hasChanges = false;
      const stillPolling = new Set<string>();

      for (const taskId of pollingTaskIds) {
        try {
          const updatedTask = await getTaskStatus(taskId);
          const index = updatedTasks.findIndex((t) => t.task_id === taskId);
          if (index !== -1) {
            updatedTasks[index] = updatedTask;
            hasChanges = true;
          }

          // 如果任务还在处理中，继续轮询
          if (updatedTask.status === 'pending' || updatedTask.status === 'running') {
            stillPolling.add(taskId);
          }
        } catch (err) {
          console.error(`Failed to poll task ${taskId}:`, err);
        }
      }

      if (hasChanges) {
        setTasks(updatedTasks);
      }
      setPollingTaskIds(stillPolling);
    }, 2000); // 每 2 秒轮询一次

    return () => clearInterval(interval);
  }, [pollingTaskIds, tasks]);

  if (loading) {
    return (
      <div className="flex items-center justify-center py-12">
        <div className="h-8 w-8 animate-spin rounded-full border-b-2 border-blue-600"></div>
      </div>
    );
  }

  if (error) {
    return (
      <div className="rounded-lg border border-red-200 bg-red-50 p-4">
        <div className="flex items-center space-x-2">
          <span className="text-red-600">⚠️</span>
          <span className="text-red-700">{error}</span>
        </div>
      </div>
    );
  }

  if (tasks.length === 0) {
    return (
      <div className="py-12 text-center">
        <div className="mb-4 text-4xl">📭</div>
        <div className="text-gray-500">暂无上传任务</div>
      </div>
    );
  }

  return (
    <div className="space-y-3">
      {tasks.map((task) => (
        <TaskCard key={task.task_id} task={task} onClick={() => onTaskClick?.(task)} />
      ))}
    </div>
  );
}

interface TaskCardProps {
  task: IngestionTask;
  onClick?: () => void;
}

function TaskCard({ task, onClick }: TaskCardProps) {
  const isProcessing = task.status === 'pending' || task.status === 'running';

  return (
    <div
      onClick={onClick}
      className={`rounded-lg border bg-white p-4 transition-all ${onClick ? 'cursor-pointer hover:shadow-md' : ''} `}
    >
      {/* 文件信息 */}
      <div className="flex items-start space-x-3">
        <div className="text-2xl">{getFileTypeIcon(task.file_type)}</div>
        <div className="min-w-0 flex-1">
          <div className="mb-1 flex items-center justify-between">
            <h4 className="truncate font-medium text-gray-900">{task.file_name}</h4>
            <span
              className={`rounded-full px-2 py-1 text-xs font-medium ${getStatusColorClass(task.status)} `}
            >
              {getStatusText(task.status)}
            </span>
          </div>

          {/* 元数据 */}
          <div className="mb-2 flex flex-wrap gap-2 text-xs text-gray-500">
            <span>{formatFileSize(task.file_size)}</span>
            {task.metadata?.document_type && (
              <span>• {getDocumentTypeText(task.metadata.document_type)}</span>
            )}
            {task.metadata?.symbol && <span>• {task.metadata.symbol}</span>}
            {task.metadata?.broker && <span>• {task.metadata.broker}</span>}
          </div>

          {/* 进度条 */}
          {isProcessing && (
            <div className="mb-2">
              <div className="mb-1 flex items-center justify-between text-xs text-gray-600">
                <span>处理进度</span>
                <span>{task.progress}%</span>
              </div>
              <div className="h-2 w-full rounded-full bg-gray-200">
                <div
                  className="h-2 rounded-full bg-blue-600 transition-all duration-300"
                  style={{ width: `${task.progress}%` }}
                ></div>
              </div>
            </div>
          )}

          {/* 完成信息 */}
          {task.status === 'completed' && task.chunks_created && (
            <div className="text-xs text-green-600">✓ 已创建 {task.chunks_created} 个文档块</div>
          )}

          {/* 错误信息 */}
          {task.status === 'failed' && task.error_message && (
            <div className="mt-2 rounded bg-red-50 p-2 text-xs text-red-600">
              {task.error_message}
            </div>
          )}

          {/* 时间信息 */}
          <div className="mt-2 text-xs text-gray-400">
            创建于 {dayjs(task.created_at).format('YYYY-MM-DD HH:mm:ss')}
            {task.completed_at && (
              <span> • 完成于 {dayjs(task.completed_at).format('HH:mm:ss')}</span>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}

function getDocumentTypeText(type: string): string {
  const typeMap: Record<string, string> = {
    research_report: '研究报告',
    disclosure: '信息披露',
    news: '新闻资讯',
    user_note: '用户笔记',
  };
  return typeMap[type] || type;
}
