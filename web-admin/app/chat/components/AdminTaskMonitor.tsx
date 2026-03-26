/**
 * Admin Task Monitor Component
 * Real-time monitoring of document processing tasks
 */

import { useState, useEffect, useRef } from 'react';

interface AdminTaskStatus {
  task_id: string;
  document_id?: string;
  status: string;
  progress: number;
  current_stage: string;
  stage_details: Record<string, any>;
  chunks_created: number;
  quality_score?: number;
  processing_warnings: string[];
  error_message?: string;
  admin_user_id: string;
  priority: number;
  approval_required: boolean;
  created_at: string;
  started_at?: string;
  completed_at?: string;
  estimated_completion?: string;
}

interface AdminTaskMonitorProps {
  taskId?: string;
  onTaskComplete?: (task: AdminTaskStatus) => void;
  onTaskError?: (task: AdminTaskStatus) => void;
}

const STAGE_LABELS = {
  pending: '等待处理',
  parsing: '文档解析',
  chunking: '文本分块',
  embedding: '生成嵌入',
  storing: '存储数据',
  validating: '验证完整性',
  completed: '处理完成',
  failed: '处理失败',
};

const STATUS_COLORS = {
  pending: 'bg-yellow-100 text-yellow-800',
  running: 'bg-blue-100 text-blue-800',
  completed: 'bg-green-100 text-green-800',
  failed: 'bg-red-100 text-red-800',
};

export function AdminTaskMonitor({ taskId, onTaskComplete, onTaskError }: AdminTaskMonitorProps) {
  const [task, setTask] = useState<AdminTaskStatus | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const intervalRef = useRef<NodeJS.Timeout | null>(null);

  const fetchTaskStatus = async (id: string) => {
    try {
      const response = await fetch(
        `/api/admin/documents/tasks/${id}?admin_user_id=admin_001&tenant_id=default`,
      );

      if (!response.ok) {
        throw new Error(`Failed to fetch task status: ${response.status}`);
      }

      const taskData = await response.json();
      setTask(taskData);
      setError(null);

      // Handle task completion
      if (taskData.status === 'completed') {
        onTaskComplete?.(taskData);
        if (intervalRef.current) {
          clearInterval(intervalRef.current);
          intervalRef.current = null;
        }
      } else if (taskData.status === 'failed') {
        onTaskError?.(taskData);
        if (intervalRef.current) {
          clearInterval(intervalRef.current);
          intervalRef.current = null;
        }
      }
    } catch (err: any) {
      console.error('Error fetching task status:', err);
      setError(err.message || 'Failed to fetch task status');
    }
  };

  useEffect(() => {
    if (!taskId) {
      setTask(null);
      return;
    }

    setLoading(true);
    fetchTaskStatus(taskId).finally(() => setLoading(false));

    // Set up polling for active tasks
    intervalRef.current = setInterval(() => {
      fetchTaskStatus(taskId);
    }, 2000); // Poll every 2 seconds

    return () => {
      if (intervalRef.current) {
        clearInterval(intervalRef.current);
        intervalRef.current = null;
      }
    };
  }, [taskId]);

  const formatDuration = (startTime?: string, endTime?: string) => {
    if (!startTime) return 'N/A';

    const start = new Date(startTime);
    const end = endTime ? new Date(endTime) : new Date();
    const duration = Math.round((end.getTime() - start.getTime()) / 1000);

    if (duration < 60) return `${duration}秒`;
    if (duration < 3600) return `${Math.round(duration / 60)}分钟`;
    return `${Math.round(duration / 3600)}小时`;
  };

  const getProgressColor = (progress: number) => {
    if (progress < 30) return 'bg-red-500';
    if (progress < 70) return 'bg-yellow-500';
    return 'bg-green-500';
  };

  if (!taskId) {
    return (
      <div className="rounded-lg border border-gray-200 bg-white p-6 shadow-sm">
        <div className="text-center text-gray-500">
          <p>选择一个任务以查看处理状态</p>
        </div>
      </div>
    );
  }

  if (loading && !task) {
    return (
      <div className="rounded-lg border border-gray-200 bg-white p-6 shadow-sm">
        <div className="flex items-center justify-center">
          <div className="h-6 w-6 animate-spin rounded-full border-b-2 border-blue-600"></div>
          <span className="ml-2 text-gray-600">加载任务状态...</span>
        </div>
      </div>
    );
  }

  if (error && !task) {
    return (
      <div className="rounded-lg border border-gray-200 bg-white p-6 shadow-sm">
        <div className="text-center text-red-600">
          <p>加载失败: {error}</p>
          <button
            onClick={() => taskId && fetchTaskStatus(taskId)}
            className="mt-2 rounded-md bg-blue-600 px-4 py-2 text-white hover:bg-blue-700"
          >
            重试
          </button>
        </div>
      </div>
    );
  }

  if (!task) {
    return (
      <div className="rounded-lg border border-gray-200 bg-white p-6 shadow-sm">
        <div className="text-center text-gray-500">
          <p>未找到任务信息</p>
        </div>
      </div>
    );
  }

  return (
    <div className="rounded-lg border border-gray-200 bg-white p-6 shadow-sm">
      <div className="mb-4 flex items-center justify-between">
        <h3 className="text-lg font-medium text-gray-900">任务监控</h3>
        <span
          className={`inline-flex items-center rounded-full px-2 py-1 text-xs font-medium ${STATUS_COLORS[task.status as keyof typeof STATUS_COLORS] || 'bg-gray-100 text-gray-800'}`}
        >
          {task.status.toUpperCase()}
        </span>
      </div>

      {/* Task Basic Info */}
      <div className="mb-6 grid grid-cols-1 gap-4 md:grid-cols-2">
        <div>
          <label className="block text-sm font-medium text-gray-700">任务 ID</label>
          <p className="font-mono text-sm text-gray-900">{task.task_id}</p>
        </div>
        <div>
          <label className="block text-sm font-medium text-gray-700">文档 ID</label>
          <p className="font-mono text-sm text-gray-900">{task.document_id || 'N/A'}</p>
        </div>
        <div>
          <label className="block text-sm font-medium text-gray-700">优先级</label>
          <p className="text-sm text-gray-900">
            {task.priority} / 5
            {task.priority >= 4 && <span className="ml-1 text-red-600">高优先级</span>}
          </p>
        </div>
        <div>
          <label className="block text-sm font-medium text-gray-700">需要审批</label>
          <p className="text-sm text-gray-900">{task.approval_required ? '是' : '否'}</p>
        </div>
      </div>

      {/* Progress Bar */}
      <div className="mb-6">
        <div className="mb-2 flex items-center justify-between">
          <label className="block text-sm font-medium text-gray-700">处理进度</label>
          <span className="text-sm text-gray-600">{task.progress}%</span>
        </div>
        <div className="h-3 w-full rounded-full bg-gray-200">
          <div
            className={`h-3 rounded-full transition-all duration-500 ${getProgressColor(task.progress)}`}
            style={{ width: `${task.progress}%` }}
          />
        </div>
      </div>

      {/* Current Stage */}
      <div className="mb-6">
        <label className="mb-2 block text-sm font-medium text-gray-700">当前阶段</label>
        <div className="rounded-lg bg-gray-50 p-4">
          <div className="mb-2 flex items-center justify-between">
            <span className="text-sm font-medium text-gray-900">
              {STAGE_LABELS[task.current_stage as keyof typeof STAGE_LABELS] || task.current_stage}
            </span>
            {task.status === 'running' && (
              <div className="flex items-center text-blue-600">
                <div className="mr-1 h-4 w-4 animate-spin rounded-full border-b-2 border-blue-600"></div>
                <span className="text-xs">处理中</span>
              </div>
            )}
          </div>

          {/* Stage Details */}
          {Object.keys(task.stage_details).length > 0 && (
            <div className="space-y-1 text-xs text-gray-600">
              {Object.entries(task.stage_details).map(([stage, details]) => (
                <div key={stage}>
                  <strong>{stage}:</strong> {JSON.stringify(details, null, 2)}
                </div>
              ))}
            </div>
          )}
        </div>
      </div>

      {/* Processing Results */}
      {(task.chunks_created > 0 || task.quality_score) && (
        <div className="mb-6">
          <label className="mb-2 block text-sm font-medium text-gray-700">处理结果</label>
          <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
            <div className="rounded-lg bg-blue-50 p-3">
              <div className="text-sm font-medium text-blue-900">文档分块</div>
              <div className="text-lg font-bold text-blue-700">{task.chunks_created}</div>
            </div>
            {task.quality_score && (
              <div className="rounded-lg bg-green-50 p-3">
                <div className="text-sm font-medium text-green-900">质量评分</div>
                <div className="text-lg font-bold text-green-700">
                  {(task.quality_score * 100).toFixed(1)}%
                </div>
              </div>
            )}
          </div>
        </div>
      )}

      {/* Processing Warnings */}
      {task.processing_warnings.length > 0 && (
        <div className="mb-6">
          <label className="mb-2 block text-sm font-medium text-gray-700">处理警告</label>
          <div className="rounded-lg border border-yellow-200 bg-yellow-50 p-3">
            <ul className="space-y-1 text-sm text-yellow-800">
              {task.processing_warnings.map((warning, index) => (
                <li key={index} className="flex items-start">
                  <span className="mr-2 text-yellow-600">⚠</span>
                  {warning}
                </li>
              ))}
            </ul>
          </div>
        </div>
      )}

      {/* Error Message */}
      {task.error_message && (
        <div className="mb-6">
          <label className="mb-2 block text-sm font-medium text-gray-700">错误信息</label>
          <div className="rounded-lg border border-red-200 bg-red-50 p-3">
            <p className="text-sm text-red-800">{task.error_message}</p>
          </div>
        </div>
      )}

      {/* Timing Information */}
      <div className="border-t border-gray-200 pt-4">
        <label className="mb-2 block text-sm font-medium text-gray-700">时间信息</label>
        <div className="grid grid-cols-1 gap-4 text-sm md:grid-cols-3">
          <div>
            <span className="text-gray-600">创建时间:</span>
            <br />
            <span className="text-gray-900">
              {new Date(task.created_at).toLocaleString('zh-CN')}
            </span>
          </div>
          {task.started_at && (
            <div>
              <span className="text-gray-600">开始时间:</span>
              <br />
              <span className="text-gray-900">
                {new Date(task.started_at).toLocaleString('zh-CN')}
              </span>
            </div>
          )}
          {task.completed_at ? (
            <div>
              <span className="text-gray-600">完成时间:</span>
              <br />
              <span className="text-gray-900">
                {new Date(task.completed_at).toLocaleString('zh-CN')}
              </span>
            </div>
          ) : task.estimated_completion ? (
            <div>
              <span className="text-gray-600">预计完成:</span>
              <br />
              <span className="text-gray-900">
                {new Date(task.estimated_completion).toLocaleString('zh-CN')}
              </span>
            </div>
          ) : null}
        </div>

        {task.started_at && (
          <div className="mt-2 text-sm text-gray-600">
            处理时长: {formatDuration(task.started_at, task.completed_at)}
          </div>
        )}
      </div>
    </div>
  );
}
