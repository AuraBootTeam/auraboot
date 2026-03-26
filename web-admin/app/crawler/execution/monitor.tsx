import { useEffect, useState } from 'react';
import { useParams, useNavigate } from 'react-router';
import axios from 'axios';
import ExecutionStatusBadge from '~/crawler/execution/components/ExecutionStatusBadge';
import ProgressMetrics from '~/crawler/execution/components/ProgressMetrics';
import ArticlePreviewList from '~/crawler/execution/components/ArticlePreviewList';
import ErrorDetails from '~/crawler/execution/components/ErrorDetails';
import { confirmDialog } from '~/utils/confirmDialog';
import { useToastContext } from '~/contexts/ToastContext';

const API_BASE = '/api/crawler';
const POLL_INTERVAL = 3000; // 3 seconds

interface TaskInstance {
  id: string;
  templateId: string;
  tenantId: number;
  site: string;
  status: 'pending' | 'running' | 'success' | 'failed' | 'cancelled';
  workerId?: string;
  startTime?: string;
  endTime?: string;
  progress?: {
    urlsProcessed: number;
    articlesCollected: number;
    currentUrl?: string;
    lastUpdateTime?: string;
  };
  result?: any;
  error?: {
    message: string;
    stackTrace?: string;
    failedUrls?: string[];
  };
  createdAt: string;
  duration?: number;
}

interface Article {
  id: number;
  source: string;
  stock?: string;
  url: string;
  title: string;
  author?: string;
  contentText: string;
  publishTime?: string;
  createdAt: string;
}

export default function TaskExecutionMonitor() {
  const { instanceId } = useParams<{ instanceId: string }>();
  const navigate = useNavigate();
  const { showErrorToast } = useToastContext();

  const [instance, setInstance] = useState<TaskInstance | null>(null);
  const [articles, setArticles] = useState<Article[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // Fetch task instance details
  const fetchInstance = async () => {
    try {
      const response = await axios.get(`${API_BASE}/tasks/instances/${instanceId}`);
      setInstance(response.data);
      setError(null);
    } catch (err: any) {
      setError(err.response?.data?.message || '获取任务详情失败');
    }
  };

  // Fetch articles
  const fetchArticles = async () => {
    try {
      const response = await axios.get(`${API_BASE}/tasks/instances/${instanceId}/articles`, {
        params: { page: 1, size: 100 },
      });
      setArticles(response.data.records || []);
    } catch (err) {
      console.error('Failed to fetch articles:', err);
    }
  };

  // Initial load
  useEffect(() => {
    const loadData = async () => {
      setLoading(true);
      await fetchInstance();
      await fetchArticles();
      setLoading(false);
    };
    loadData();
  }, [instanceId]);

  // Polling for updates
  useEffect(() => {
    if (
      !instance ||
      instance.status === 'success' ||
      instance.status === 'failed' ||
      instance.status === 'cancelled'
    ) {
      return;
    }

    const interval = setInterval(() => {
      fetchInstance();
      fetchArticles();
    }, POLL_INTERVAL);

    return () => clearInterval(interval);
  }, [instance?.status]);

  // Cancel task
  const handleCancel = async () => {
    if (!(await confirmDialog({ content: '确定要取消此任务吗?' }))) {
      return;
    }

    try {
      await axios.post(`${API_BASE}/tasks/instances/${instanceId}/cancel`);
      await fetchInstance();
    } catch (err: any) {
      showErrorToast(err.response?.data?.message || '取消任务失败');
    }
  };

  if (loading) {
    return (
      <div className="flex min-h-screen items-center justify-center">
        <div className="loading loading-spinner loading-lg"></div>
      </div>
    );
  }

  if (error) {
    return (
      <div className="container mx-auto p-6">
        <div className="alert alert-error">
          <span>{error}</span>
        </div>
        <button className="btn btn-primary mt-4" onClick={() => navigate('/crawler/tasks')}>
          返回任务列表
        </button>
      </div>
    );
  }

  if (!instance) {
    return null;
  }

  return (
    <div className="min-h-screen bg-gradient-to-br from-blue-50 via-indigo-50 to-purple-50">
      <div className="container mx-auto max-w-7xl p-6">
        {/* Header */}
        <div className="mb-6 rounded-2xl border border-gray-100 bg-white p-6 shadow-lg">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-4">
              <div className="flex h-12 w-12 items-center justify-center rounded-xl bg-gradient-to-br from-blue-500 to-indigo-600">
                <svg
                  className="h-6 w-6 text-white"
                  fill="none"
                  stroke="currentColor"
                  viewBox="0 0 24 24"
                >
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={2}
                    d="M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z"
                  />
                </svg>
              </div>
              <div>
                <h1 className="text-2xl font-bold text-gray-900">任务执行监控</h1>
                <p className="mt-1 font-mono text-sm text-gray-500">
                  ID: {instance.id.substring(0, 16)}...
                </p>
              </div>
            </div>
            <div className="flex gap-3">
              {instance.status === 'running' && (
                <button
                  className="rounded-lg bg-gradient-to-r from-yellow-500 to-orange-500 px-5 py-2.5 font-medium text-white shadow-md transition-all duration-200 hover:from-yellow-600 hover:to-orange-600 hover:shadow-lg"
                  onClick={handleCancel}
                >
                  <span className="flex items-center gap-2">
                    <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path
                        strokeLinecap="round"
                        strokeLinejoin="round"
                        strokeWidth={2}
                        d="M6 18L18 6M6 6l12 12"
                      />
                    </svg>
                    取消任务
                  </span>
                </button>
              )}
              <button
                className="rounded-lg bg-gray-100 px-5 py-2.5 font-medium text-gray-700 transition-all duration-200 hover:bg-gray-200"
                onClick={() => navigate('/crawler/tasks')}
              >
                <span className="flex items-center gap-2">
                  <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path
                      strokeLinecap="round"
                      strokeLinejoin="round"
                      strokeWidth={2}
                      d="M10 19l-7-7m0 0l7-7m-7 7h18"
                    />
                  </svg>
                  返回列表
                </span>
              </button>
            </div>
          </div>
        </div>

        {/* Status Badge */}
        <div className="mb-6 rounded-2xl border border-gray-100 bg-white p-6 shadow-lg">
          <div className="flex items-center justify-between">
            <div>
              <h2 className="mb-3 text-lg font-semibold text-gray-900">执行状态</h2>
              <ExecutionStatusBadge status={instance.status} />
            </div>
            {instance.workerId && (
              <div className="text-right">
                <p className="text-sm text-gray-500">Worker</p>
                <p className="mt-1 font-mono text-sm text-gray-900">{instance.workerId}</p>
              </div>
            )}
          </div>
        </div>

        {/* Progress Metrics */}
        {(instance.status === 'running' || instance.status === 'success') && (
          <div className="mb-6 rounded-2xl border border-gray-100 bg-white p-6 shadow-lg">
            <h2 className="mb-4 text-lg font-semibold text-gray-900">进度指标</h2>
            <ProgressMetrics
              startTime={instance.startTime}
              endTime={instance.endTime}
              articlesCollected={instance.progress?.articlesCollected || 0}
              urlsProcessed={instance.progress?.urlsProcessed || 0}
              currentUrl={instance.progress?.currentUrl}
            />
          </div>
        )}

        {/* Error Details */}
        {instance.status === 'failed' && instance.error && (
          <div className="mb-6 rounded-2xl border border-red-200 bg-white p-6 shadow-lg">
            <h2 className="mb-4 flex items-center gap-2 text-lg font-semibold text-red-600">
              <svg className="h-5 w-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M12 8v4m0 4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z"
                />
              </svg>
              错误详情
            </h2>
            <ErrorDetails error={instance.error} />
          </div>
        )}

        {/* Articles Preview */}
        {articles.length > 0 && (
          <div className="rounded-2xl border border-gray-100 bg-white p-6 shadow-lg">
            <h2 className="mb-4 flex items-center gap-2 text-lg font-semibold text-gray-900">
              <svg
                className="h-5 w-5 text-green-600"
                fill="none"
                stroke="currentColor"
                viewBox="0 0 24 24"
              >
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z"
                />
              </svg>
              已收集文章
              <span className="ml-2 rounded-full bg-green-100 px-3 py-1 text-sm font-medium text-green-700">
                {articles.length}
              </span>
            </h2>
            <ArticlePreviewList articles={articles} />
          </div>
        )}

        {/* No articles yet */}
        {articles.length === 0 && instance.status === 'running' && (
          <div className="rounded-2xl border border-gray-100 bg-white p-12 text-center shadow-lg">
            <div className="mb-4 inline-flex h-16 w-16 items-center justify-center rounded-full bg-blue-100">
              <svg
                className="h-8 w-8 animate-spin text-blue-600"
                fill="none"
                stroke="currentColor"
                viewBox="0 0 24 24"
              >
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15"
                />
              </svg>
            </div>
            <p className="text-lg text-gray-600">正在收集文章...</p>
            <p className="mt-2 text-sm text-gray-400">请稍候，Worker 正在处理中</p>
          </div>
        )}
      </div>
    </div>
  );
}
