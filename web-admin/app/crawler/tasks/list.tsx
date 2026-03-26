import React, { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router';
import { useCrawlerStore } from '~/crawler/store';

interface FeedbackState {
  type: 'success' | 'error';
  message: string;
}

const badgeClass = (base: string) =>
  `inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium ${base}`;

export default function TaskListPage() {
  const navigate = useNavigate();
  const { tasks, loading, fetchTasks, executeTask } = useCrawlerStore();
  const [feedback, setFeedback] = useState<FeedbackState | null>(null);

  useEffect(() => {
    fetchTasks();
  }, [fetchTasks]);

  const showFeedback = (type: FeedbackState['type'], message: string) => {
    setFeedback({ type, message });
    window.setTimeout(() => setFeedback(null), 2800);
  };

  const handleExecute = async (templateId: string) => {
    try {
      const instanceId = await executeTask(templateId);
      showFeedback('success', '任务已提交执行');
      navigate(`/crawler/tasks/${instanceId}/monitor`);
    } catch (error) {
      console.error(error);
      showFeedback('error', '执行失败');
    }
  };

  const taskRows = useMemo(() => tasks || [], [tasks]);

  return (
    <div className="space-y-4 p-6">
      <div className="flex items-center justify-between">
        <h2 className="text-xl font-semibold text-gray-900">爬虫任务</h2>
        <button
          type="button"
          onClick={() => navigate('/crawler/tasks/new')}
          className="inline-flex items-center rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white shadow-sm transition hover:bg-blue-700 focus:ring-2 focus:ring-blue-500 focus:ring-offset-2 focus:outline-none"
        >
          <span className="mr-2 text-lg" aria-hidden="true">
            ＋
          </span>
          创建任务
        </button>
      </div>

      {feedback && (
        <div
          className={`rounded-md border px-4 py-3 text-sm ${
            feedback.type === 'success'
              ? 'border-green-200 bg-green-50 text-green-700'
              : 'border-red-200 bg-red-50 text-red-700'
          }`}
        >
          {feedback.message}
        </div>
      )}

      <div className="overflow-x-auto rounded-lg border border-gray-200 bg-white shadow-sm">
        <table className="min-w-full divide-y divide-gray-200 text-sm">
          <thead className="bg-gray-50 text-xs text-gray-500 uppercase">
            <tr>
              <th className="px-4 py-3 text-left">任务名称</th>
              <th className="px-4 py-3 text-left">站点</th>
              <th className="px-4 py-3 text-left">状态</th>
              <th className="px-4 py-3 text-left">创建时间</th>
              <th className="px-4 py-3 text-left">操作</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100">
            {loading && (
              <tr>
                <td colSpan={5} className="px-4 py-6 text-center text-gray-500">
                  加载中...
                </td>
              </tr>
            )}
            {!loading && taskRows.length === 0 && (
              <tr>
                <td colSpan={5} className="px-4 py-6 text-center text-gray-500">
                  暂无任务
                </td>
              </tr>
            )}
            {!loading &&
              taskRows.map((task) => (
                <tr key={task.id} className="hover:bg-gray-50">
                  <td className="px-4 py-3 text-gray-900">{task.name}</td>
                  <td className="px-4 py-3">
                    <span
                      className={badgeClass(
                        task.site === 'wechat_mp'
                          ? 'bg-green-100 text-green-700'
                          : 'bg-blue-100 text-blue-700',
                      )}
                    >
                      {task.site}
                    </span>
                  </td>
                  <td className="px-4 py-3">
                    <span
                      className={badgeClass(
                        task.enabled
                          ? 'bg-emerald-100 text-emerald-700'
                          : 'bg-gray-100 text-gray-600',
                      )}
                    >
                      {task.enabled ? '启用' : '禁用'}
                    </span>
                  </td>
                  <td className="px-4 py-3 text-gray-700">
                    {task.createdAt ? new Date(task.createdAt).toLocaleString('zh-CN') : '-'}
                  </td>
                  <td className="px-4 py-3">
                    <div className="flex flex-wrap gap-2">
                      <button
                        type="button"
                        onClick={() => handleExecute(task.id)}
                        className="rounded-md bg-blue-50 px-3 py-1.5 text-sm font-medium text-blue-600 hover:bg-blue-100"
                      >
                        执行
                      </button>
                      <button
                        type="button"
                        onClick={() => navigate(`/crawler/tasks/${task.id}/history`)}
                        className="rounded-md bg-gray-100 px-3 py-1.5 text-sm font-medium text-gray-700 hover:bg-gray-200"
                      >
                        执行历史
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
