import { useState, useEffect } from 'react';
import { useLoaderData, useNavigate, type LoaderFunctionArgs } from 'react-router';
import { useToast } from '~/contexts/ToastContext';
import { getTasks } from '~/shared/services/crawler';

// Loader - 获取任务列表
export async function loader({ request }: LoaderFunctionArgs) {
  try {
    const tasks = await getTasks(request);
    return { tasks, error: null };
  } catch (error) {
    console.error('Failed to load tasks:', error);
    return { tasks: [], error: error instanceof Error ? error.message : 'Failed to load tasks' };
  }
}

export default function CrawlerTaskList() {
  const { showSuccessToast, showErrorToast } = useToast();
  const navigate = useNavigate();
  const { tasks, error } = useLoaderData<typeof loader>();
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (error) {
      showErrorToast(error);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [error]);

  const handleExecute = async (templateId: string) => {
    setLoading(true);
    try {
      const response = await fetch(`/api/crawler/tasks/templates/${templateId}/execute`, {
        method: 'post',
      });

      if (!response.ok) {
        throw new Error('Failed to execute task');
      }

      const data = await response.json();
      // 响应格式: { instanceId, templateId, status }
      const instanceId = data.instanceId;

      showSuccessToast('任务已提交执行');

      // 导航到监控页面
      navigate(`/crawler/tasks/${instanceId}/monitor`);
    } catch (error) {
      showErrorToast('执行失败');
    } finally {
      setLoading(false);
    }
  };

  const getSiteColor = (site: string) => {
    const colorMap: Record<string, string> = {
      xueqiu: 'bg-blue-100 text-blue-800',
      wechat_mp: 'bg-green-100 text-green-800',
    };
    return colorMap[site] || 'bg-gray-100 text-gray-800';
  };

  return (
    <div className="p-6">
      <div className="rounded-lg bg-white shadow-md">
        {/* Header */}
        <div className="border-b border-gray-200 px-6 py-4">
          <div className="flex items-center justify-between">
            <h1 className="text-2xl font-semibold text-gray-900">爬虫任务</h1>
            <button
              onClick={() => navigate('/crawler/tasks/new')}
              className="rounded-md bg-blue-600 px-4 py-2 text-white transition-colors hover:bg-blue-700"
            >
              + 创建任务
            </button>
          </div>
        </div>

        {/* Task List */}
        <div className="p-6">
          {tasks.length === 0 ? (
            <div className="py-12 text-center text-gray-500">暂无任务，点击"创建任务"开始</div>
          ) : (
            <div className="overflow-x-auto">
              <table className="min-w-full divide-y divide-gray-200">
                <thead className="bg-gray-50">
                  <tr>
                    <th className="px-6 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase">
                      任务名称
                    </th>
                    <th className="px-6 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase">
                      站点
                    </th>
                    <th className="px-6 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase">
                      状态
                    </th>
                    <th className="px-6 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase">
                      创建时间
                    </th>
                    <th className="px-6 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase">
                      操作
                    </th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-gray-200 bg-white">
                  {tasks.map((task: any) => (
                    <tr key={task.id} className="hover:bg-gray-50">
                      <td className="px-6 py-4 whitespace-nowrap">
                        <div className="text-sm font-medium text-gray-900">{task.name}</div>
                        {task.description && (
                          <div className="text-sm text-gray-500">{task.description}</div>
                        )}
                      </td>
                      <td className="px-6 py-4 whitespace-nowrap">
                        <span
                          className={`rounded-full px-2 py-1 text-xs font-medium ${getSiteColor(task.site)}`}
                        >
                          {task.site}
                        </span>
                      </td>
                      <td className="px-6 py-4 whitespace-nowrap">
                        <span
                          className={`rounded-full px-2 py-1 text-xs font-medium ${
                            task.enabled
                              ? 'bg-green-100 text-green-800'
                              : 'bg-gray-100 text-gray-800'
                          }`}
                        >
                          {task.enabled ? '启用' : '禁用'}
                        </span>
                      </td>
                      <td className="px-6 py-4 text-sm whitespace-nowrap text-gray-500">
                        {new Date(task.createdAt).toLocaleString('zh-CN')}
                      </td>
                      <td className="px-6 py-4 text-sm whitespace-nowrap">
                        <button
                          onClick={() => handleExecute(task.id)}
                          disabled={loading}
                          className="text-blue-600 hover:text-blue-900 disabled:opacity-50"
                        >
                          ▶ 执行
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
