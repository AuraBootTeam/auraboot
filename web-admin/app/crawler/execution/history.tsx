import { useEffect, useState } from 'react';
import { useParams, useNavigate } from 'react-router';
import { useCrawlerStore } from '~/crawler/store';

export default function ExecutionHistoryPage() {
  const { templateId } = useParams<{ templateId: string }>();
  const navigate = useNavigate();
  const { executionHistory, loading, fetchExecutionHistory } = useCrawlerStore();
  const [pagination, setPagination] = useState({ current: 1, pageSize: 20, total: 0 });

  useEffect(() => {
    if (templateId) {
      loadHistory(1);
    }
  }, [templateId]);

  const loadHistory = async (page: number) => {
    if (!templateId) return;
    const result = await fetchExecutionHistory(templateId, page, pagination.pageSize);
    setPagination({
      current: result.page,
      pageSize: result.size,
      total: result.total,
    });
  };

  const handleViewDetails = (instanceId: string) => {
    navigate(`/crawler/tasks/${instanceId}/monitor`);
  };

  const formatDuration = (seconds?: number) => {
    if (!seconds) return '-';
    const minutes = Math.floor(seconds / 60);
    const secs = seconds % 60;
    return `${minutes}分${secs}秒`;
  };

  const getStatusColor = (status: string) => {
    const colorMap: Record<string, string> = {
      pending: 'bg-gray-100 text-gray-800',
      running: 'bg-blue-100 text-blue-800',
      success: 'bg-green-100 text-green-800',
      failed: 'bg-red-100 text-red-800',
      cancelled: 'bg-yellow-100 text-yellow-800',
    };
    return colorMap[status] || 'bg-gray-100 text-gray-800';
  };

  const getStatusText = (status: string) => {
    const textMap: Record<string, string> = {
      pending: '等待中',
      running: '运行中',
      success: '成功',
      failed: '失败',
      cancelled: '已取消',
    };
    return textMap[status] || status;
  };

  return (
    <div className="p-6">
      <div className="rounded-lg bg-white shadow-md">
        {/* Header */}
        <div className="border-b border-gray-200 px-6 py-4">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-4">
              <button
                onClick={() => navigate('/crawler/tasks')}
                className="flex items-center gap-2 rounded-md bg-gray-100 px-4 py-2 text-gray-700 transition-colors hover:bg-gray-200"
              >
                <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={2}
                    d="M10 19l-7-7m0 0l7-7m-7 7h18"
                  />
                </svg>
                返回任务列表
              </button>
              <h1 className="text-2xl font-semibold text-gray-900">执行历史</h1>
            </div>
          </div>
        </div>

        {/* Table */}
        <div className="p-6">
          {loading ? (
            <div className="py-12 text-center text-gray-500">加载中...</div>
          ) : executionHistory.length === 0 ? (
            <div className="py-12 text-center text-gray-500">暂无执行历史</div>
          ) : (
            <>
              <div className="overflow-x-auto">
                <table className="min-w-full divide-y divide-gray-200">
                  <thead className="bg-gray-50">
                    <tr>
                      <th className="px-6 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase">
                        执行 ID
                      </th>
                      <th className="px-6 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase">
                        状态
                      </th>
                      <th className="px-6 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase">
                        开始时间
                      </th>
                      <th className="px-6 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase">
                        结束时间
                      </th>
                      <th className="px-6 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase">
                        执行时长
                      </th>
                      <th className="px-6 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase">
                        收集文章数
                      </th>
                      <th className="px-6 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase">
                        操作
                      </th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-gray-200 bg-white">
                    {executionHistory.map((record) => (
                      <tr key={record.id} className="hover:bg-gray-50">
                        <td className="px-6 py-4 whitespace-nowrap">
                          <span className="font-mono text-sm text-gray-900">
                            {record.id.substring(0, 12)}...
                          </span>
                        </td>
                        <td className="px-6 py-4 whitespace-nowrap">
                          <span
                            className={`rounded-full px-2 py-1 text-xs font-medium ${getStatusColor(record.status)}`}
                          >
                            {getStatusText(record.status)}
                          </span>
                        </td>
                        <td className="px-6 py-4 text-sm whitespace-nowrap text-gray-500">
                          {new Date(record.startTime).toLocaleString('zh-CN')}
                        </td>
                        <td className="px-6 py-4 text-sm whitespace-nowrap text-gray-500">
                          {record.endTime ? new Date(record.endTime).toLocaleString('zh-CN') : '-'}
                        </td>
                        <td className="px-6 py-4 text-sm whitespace-nowrap text-gray-500">
                          {formatDuration(record.duration)}
                        </td>
                        <td className="px-6 py-4 whitespace-nowrap">
                          <span className="font-semibold text-blue-600">
                            {record.articlesCollected}
                          </span>
                        </td>
                        <td className="px-6 py-4 text-sm whitespace-nowrap">
                          <button
                            onClick={() => handleViewDetails(record.id)}
                            className="flex items-center gap-1 text-blue-600 hover:text-blue-900"
                          >
                            <svg
                              className="h-4 w-4"
                              fill="none"
                              stroke="currentColor"
                              viewBox="0 0 24 24"
                            >
                              <path
                                strokeLinecap="round"
                                strokeLinejoin="round"
                                strokeWidth={2}
                                d="M15 12a3 3 0 11-6 0 3 3 0 016 0z"
                              />
                              <path
                                strokeLinecap="round"
                                strokeLinejoin="round"
                                strokeWidth={2}
                                d="M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z"
                              />
                            </svg>
                            查看详情
                          </button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>

              {/* Pagination */}
              {pagination.total > pagination.pageSize && (
                <div className="mt-4 flex items-center justify-between border-t border-gray-200 pt-4">
                  <div className="text-sm text-gray-700">共 {pagination.total} 条记录</div>
                  <div className="flex gap-2">
                    <button
                      onClick={() => loadHistory(pagination.current - 1)}
                      disabled={pagination.current === 1}
                      className="rounded-md border border-gray-300 px-3 py-1 hover:bg-gray-50 disabled:cursor-not-allowed disabled:opacity-50"
                    >
                      上一页
                    </button>
                    <span className="px-3 py-1">
                      {pagination.current} / {Math.ceil(pagination.total / pagination.pageSize)}
                    </span>
                    <button
                      onClick={() => loadHistory(pagination.current + 1)}
                      disabled={
                        pagination.current >= Math.ceil(pagination.total / pagination.pageSize)
                      }
                      className="rounded-md border border-gray-300 px-3 py-1 hover:bg-gray-50 disabled:cursor-not-allowed disabled:opacity-50"
                    >
                      下一页
                    </button>
                  </div>
                </div>
              )}
            </>
          )}
        </div>
      </div>
    </div>
  );
}
