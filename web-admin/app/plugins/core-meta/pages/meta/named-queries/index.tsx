/**
 * Named Query List Page
 *
 * Displays a paginated list of named queries with search and filter capabilities
 */

import React, { useState, useCallback } from 'react';
import { useNavigate, useLoaderData, type LoaderFunctionArgs } from 'react-router';
import { confirmDialog } from '~/utils/confirmDialog';
import { useToastContext } from '~/contexts/ToastContext';
import {
  namedQueryService,
  type NamedQueryDTO,
  type NamedQueryQueryRequest,
  type PageResult,
  type NamedQueryStatusType,
} from '~/shared/services/namedQueryService';

/**
 * Loader function
 */
export const loader = async ({ request }: LoaderFunctionArgs) => {
  const url = new URL(request.url);
  const pageNum = parseInt(url.searchParams.get('page') || '1');
  const pageSize = parseInt(url.searchParams.get('size') || '20');
  const keyword = url.searchParams.get('keyword') || undefined;
  const status = url.searchParams.get('status') || undefined;

  const queryRequest: NamedQueryQueryRequest = {
    pageNum,
    pageSize,
    keyword,
    status,
  };

  try {
    const result = await namedQueryService.query(queryRequest, request);
    return { result, query: queryRequest };
  } catch (error) {
    console.error('Failed to load named queries:', error);
    throw new Response('Failed to load named queries', { status: 500 });
  }
};

/**
 * Named Query List Page Component
 */
export default function NamedQueryListPage() {
  const navigate = useNavigate();
  const { result: initialResult, query: initialQuery } = useLoaderData<typeof loader>();
  const { showSuccessToast, showErrorToast } = useToastContext();

  const [result, setResult] = useState<PageResult<NamedQueryDTO>>(initialResult);
  const [loading, setLoading] = useState(false);
  const [selectedItems, setSelectedItems] = useState<Set<string>>(new Set());

  // Search filters
  const [keywordFilter, setKeywordFilter] = useState(initialQuery.keyword || '');
  const [statusFilter, setStatusFilter] = useState(initialQuery.status || '');

  /**
   * Handle search
   */
  const handleSearch = useCallback(async () => {
    setLoading(true);
    try {
      const queryRequest: NamedQueryQueryRequest = {
        pageNum: 1,
        pageSize: result.size,
        keyword: keywordFilter || undefined,
        status: statusFilter || undefined,
      };

      const newResult = await namedQueryService.query(queryRequest);
      setResult(newResult);
      setSelectedItems(new Set());

      // Update URL
      const params = new URLSearchParams();
      if (keywordFilter) params.set('keyword', keywordFilter);
      if (statusFilter) params.set('status', statusFilter);
      navigate(`/meta/named-queries?${params.toString()}`, { replace: true });
    } catch (error) {
      console.error('Failed to search named queries:', error);
      showErrorToast('搜索失败');
    } finally {
      setLoading(false);
    }
  }, [keywordFilter, statusFilter, result.size, navigate, showErrorToast]);

  /**
   * Handle page change
   */
  const handlePageChange = useCallback(
    async (page: number) => {
      setLoading(true);
      try {
        const queryRequest: NamedQueryQueryRequest = {
          pageNum: page,
          pageSize: result.size,
          keyword: keywordFilter || undefined,
          status: statusFilter || undefined,
        };

        const newResult = await namedQueryService.query(queryRequest);
        setResult(newResult);
      } catch (error) {
        console.error('Failed to load page:', error);
        showErrorToast('加载失败');
      } finally {
        setLoading(false);
      }
    },
    [keywordFilter, statusFilter, result.size, showErrorToast],
  );

  /**
   * Handle create
   */
  const handleCreate = useCallback(() => {
    navigate('/meta/named-queries/new');
  }, [navigate]);

  /**
   * Handle edit
   */
  const handleEdit = useCallback(
    (pid: string) => {
      navigate(`/meta/named-queries/${pid}`);
    },
    [navigate],
  );

  /**
   * Handle status transition
   */
  const handleStatusTransition = useCallback(
    async (query: NamedQueryDTO, targetStatus: NamedQueryStatusType) => {
      const statusLabels: Record<string, string> = {
        draft: '草稿',
        testing: '测试中',
        published: '已发布',
        deprecated: '已废弃',
        archived: '已归档',
      };

      setLoading(true);
      try {
        await namedQueryService.updateStatus(query.pid, targetStatus);
        showSuccessToast(`已转为${statusLabels[targetStatus]}`);
        await handlePageChange(result.current);
      } catch (error) {
        console.error('Failed to update status:', error);
        showErrorToast(`状态更新失败: ${error instanceof Error ? error.message : '未知错误'}`);
      } finally {
        setLoading(false);
      }
    },
    [result.current, handlePageChange, showSuccessToast, showErrorToast],
  );

  /**
   * Handle delete
   */
  const handleDelete = useCallback(
    async (query: NamedQueryDTO) => {
      const confirmed = await confirmDialog({
        content: `确定要删除查询 "${query.title}" 吗？此操作不可恢复。`,
        variant: 'danger',
      });

      if (!confirmed) return;

      setLoading(true);
      try {
        await namedQueryService.delete(query.pid);
        showSuccessToast('删除成功');
        await handlePageChange(result.current);
      } catch (error) {
        console.error('Failed to delete named query:', error);
        showErrorToast('删除失败');
      } finally {
        setLoading(false);
      }
    },
    [result.current, handlePageChange, showSuccessToast, showErrorToast],
  );

  /**
   * Handle test query
   */
  const handleTest = useCallback(
    (pid: string) => {
      navigate(`/meta/named-queries/${pid}#test`);
    },
    [navigate],
  );

  /**
   * Handle batch status update
   */
  const handleBatchStatus = useCallback(
    async (status: NamedQueryStatusType) => {
      if (selectedItems.size === 0) {
        showErrorToast('请选择要操作的查询');
        return;
      }

      const statusLabels: Record<string, string> = {
        draft: '草稿',
        testing: '测试中',
        published: '已发布',
        deprecated: '已废弃',
        archived: '已归档',
      };
      const confirmed = await confirmDialog({
        content: `确定要批量将选中的 ${selectedItems.size} 个查询转为${statusLabels[status]}吗？`,
      });

      if (!confirmed) return;

      setLoading(true);
      try {
        await namedQueryService.batchUpdateStatus(Array.from(selectedItems), status);
        showSuccessToast(`批量操作成功`);
        setSelectedItems(new Set());
        await handlePageChange(result.current);
      } catch (error) {
        console.error('Failed to batch update status:', error);
        showErrorToast(`批量操作失败`);
      } finally {
        setLoading(false);
      }
    },
    [selectedItems, result.current, handlePageChange, showSuccessToast, showErrorToast],
  );

  /**
   * Handle select all
   */
  const handleSelectAll = useCallback(() => {
    if (selectedItems.size === result.records.length) {
      setSelectedItems(new Set());
    } else {
      setSelectedItems(new Set(result.records.map((q: NamedQueryDTO) => q.pid)));
    }
  }, [selectedItems, result.records]);

  /**
   * Handle select one
   */
  const handleSelectOne = useCallback(
    (pid: string) => {
      const newSelected = new Set(selectedItems);
      if (newSelected.has(pid)) {
        newSelected.delete(pid);
      } else {
        newSelected.add(pid);
      }
      setSelectedItems(newSelected);
    },
    [selectedItems],
  );

  /**
   * Get status badge
   */
  const getStatusBadge = (status: string) => {
    const config: Record<string, { color: string; label: string }> = {
      draft: { color: 'bg-gray-100 text-gray-800 border-gray-200', label: '草稿' },
      testing: { color: 'bg-yellow-100 text-yellow-800 border-yellow-200', label: '测试中' },
      published: { color: 'bg-green-100 text-green-800 border-green-200', label: '已发布' },
      deprecated: { color: 'bg-orange-100 text-orange-800 border-orange-200', label: '已废弃' },
      archived: { color: 'bg-red-100 text-red-800 border-red-200', label: '已归档' },
    };
    const c = config[status] || {
      color: 'bg-gray-100 text-gray-800 border-gray-200',
      label: status,
    };
    return (
      <span
        className={`inline-flex items-center rounded-md border px-2.5 py-1 text-sm font-medium ${c.color}`}
      >
        {c.label}
      </span>
    );
  };

  /**
   * Get available status transitions for a query
   */
  const getStatusActions = (
    query: NamedQueryDTO,
  ): { label: string; target: NamedQueryStatusType; color: string }[] => {
    const transitions: Record<
      string,
      { label: string; target: NamedQueryStatusType; color: string }[]
    > = {
      draft: [
        { label: '开始测试', target: 'testing', color: 'text-yellow-600 hover:text-yellow-900' },
        { label: '归档', target: 'archived', color: 'text-red-600 hover:text-red-900' },
      ],
      testing: [
        { label: '发布', target: 'published', color: 'text-green-600 hover:text-green-900' },
        { label: '退回草稿', target: 'draft', color: 'text-gray-600 hover:text-gray-900' },
      ],
      published: [
        { label: '废弃', target: 'deprecated', color: 'text-orange-600 hover:text-orange-900' },
      ],
      deprecated: [
        { label: '重新发布', target: 'published', color: 'text-green-600 hover:text-green-900' },
        { label: '归档', target: 'archived', color: 'text-red-600 hover:text-red-900' },
      ],
      archived: [
        { label: '重新打开', target: 'draft', color: 'text-blue-600 hover:text-blue-900' },
      ],
    };
    return transitions[query.status] || [];
  };

  return (
    <div className="p-6">
      {/* Header */}
      <div className="mb-6 flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-gray-900" data-testid="page-title">
            Named Query 管理
          </h1>
          <p className="mt-1 text-sm text-gray-500">
            管理系统命名查询，配置 SQL 查询定义和字段映射
          </p>
        </div>
        <button
          onClick={handleCreate}
          className="inline-flex items-center rounded-md border border-transparent bg-blue-600 px-4 py-2 text-sm font-medium text-white shadow-sm hover:bg-blue-700 focus:ring-2 focus:ring-blue-500 focus:ring-offset-2 focus:outline-none"
          disabled={loading}
          data-testid="btn-create-query"
        >
          <svg className="mr-2 -ml-1 h-5 w-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 4v16m8-8H4" />
          </svg>
          新建查询
        </button>
      </div>

      {/* Filters */}
      <div className="mb-6 rounded-lg bg-white p-4 shadow">
        <div className="grid grid-cols-1 gap-4 md:grid-cols-3">
          <div className="md:col-span-2">
            <label className="mb-1 block text-sm font-medium text-gray-700">关键词搜索</label>
            <input
              type="text"
              value={keywordFilter}
              onChange={(e) => setKeywordFilter(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && handleSearch()}
              placeholder="输入编码或标题搜索"
              className="w-full rounded-md border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
            />
          </div>
          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700">状态</label>
            <select
              value={statusFilter}
              onChange={(e) => setStatusFilter(e.target.value)}
              className="w-full rounded-md border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
            >
              <option value="">全部状态</option>
              <option value="draft">草稿</option>
              <option value="testing">测试中</option>
              <option value="published">已发布</option>
              <option value="deprecated">已废弃</option>
              <option value="archived">已归档</option>
            </select>
          </div>
        </div>
        <div className="mt-4 flex justify-end gap-2">
          <button
            onClick={() => {
              setKeywordFilter('');
              setStatusFilter('');
            }}
            className="rounded-md border border-gray-300 px-4 py-2 text-gray-700 hover:bg-gray-50"
          >
            重置
          </button>
          <button
            onClick={handleSearch}
            className="rounded-md bg-blue-600 px-4 py-2 text-white hover:bg-blue-700"
            disabled={loading}
          >
            搜索
          </button>
        </div>
      </div>

      {/* Batch Actions */}
      {selectedItems.size > 0 && (
        <div className="mb-4 rounded-md border border-blue-200 bg-blue-50 p-3">
          <div className="flex items-center justify-between">
            <span className="text-sm text-blue-800">已选择 {selectedItems.size} 个查询</span>
            <div className="flex gap-2">
              <button
                onClick={() => handleBatchStatus('published')}
                className="text-sm text-green-600 hover:text-green-900"
                disabled={loading}
              >
                批量发布
              </button>
              <button
                onClick={() => handleBatchStatus('archived')}
                className="text-sm text-red-600 hover:text-red-900"
                disabled={loading}
              >
                批量归档
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Named Query Table */}
      <div className="overflow-x-auto rounded-lg bg-white shadow">
        {loading && (
          <div className="bg-opacity-75 absolute inset-0 z-10 flex items-center justify-center bg-white">
            <div className="h-8 w-8 animate-spin rounded-full border-b-2 border-blue-600"></div>
          </div>
        )}

        <table className="w-full divide-y divide-gray-200" data-testid="query-table">
          <thead className="bg-gray-50">
            <tr>
              <th className="w-10 px-4 py-3 text-left">
                <input
                  type="checkbox"
                  checked={
                    selectedItems.size === result.records.length && result.records.length > 0
                  }
                  onChange={handleSelectAll}
                  className="rounded border-gray-300 text-blue-600 focus:ring-blue-500"
                />
              </th>
              <th className="px-4 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase">
                编码
              </th>
              <th className="px-4 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase">
                标题
              </th>
              <th className="max-w-xs px-4 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase">
                FROM SQL
              </th>
              <th className="w-16 px-4 py-3 text-center text-xs font-medium tracking-wider text-gray-500 uppercase">
                字段数
              </th>
              <th className="w-20 px-4 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase">
                状态
              </th>
              <th className="px-4 py-3 text-left text-xs font-medium tracking-wider whitespace-nowrap text-gray-500 uppercase">
                创建时间
              </th>
              <th className="px-4 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase">
                操作
              </th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-200 bg-white">
            {result.records.length === 0 ? (
              <tr>
                <td colSpan={8} className="px-6 py-12 text-center text-gray-500">
                  暂无数据
                </td>
              </tr>
            ) : (
              result.records.map((query: NamedQueryDTO) => (
                <tr key={query.pid} className="hover:bg-gray-50">
                  <td className="px-4 py-3">
                    <input
                      type="checkbox"
                      checked={selectedItems.has(query.pid)}
                      onChange={() => handleSelectOne(query.pid)}
                      className="rounded border-gray-300 text-blue-600 focus:ring-blue-500"
                    />
                  </td>
                  <td className="px-4 py-3 whitespace-nowrap">
                    <button
                      onClick={() => handleEdit(query.pid)}
                      className="font-mono text-sm text-blue-600 hover:text-blue-900"
                    >
                      {query.code}
                    </button>
                  </td>
                  <td className="px-4 py-3 text-sm whitespace-nowrap text-gray-900">
                    {query.title}
                  </td>
                  <td
                    className="max-w-xs truncate px-4 py-3 font-mono text-sm text-gray-500"
                    title={query.fromSql}
                  >
                    {query.fromSql || '-'}
                  </td>
                  <td className="px-4 py-3 text-center text-sm whitespace-nowrap text-gray-500">
                    {query.fieldCount ?? 0}
                  </td>
                  <td className="px-4 py-3 whitespace-nowrap">{getStatusBadge(query.status)}</td>
                  <td className="px-4 py-3 text-sm whitespace-nowrap text-gray-500">
                    {new Date(query.createdAt).toLocaleString()}
                  </td>
                  <td className="px-4 py-3 text-sm whitespace-nowrap">
                    <div className="flex items-center gap-3">
                      <button
                        onClick={() => handleEdit(query.pid)}
                        className="text-blue-600 hover:text-blue-900"
                      >
                        编辑
                      </button>
                      {query.executable !== false && (
                        <button
                          onClick={() => handleTest(query.pid)}
                          className="text-purple-600 hover:text-purple-900"
                        >
                          测试
                        </button>
                      )}
                      {getStatusActions(query).map((action) => (
                        <button
                          key={action.target}
                          onClick={() => handleStatusTransition(query, action.target)}
                          className={action.color}
                          disabled={loading}
                        >
                          {action.label}
                        </button>
                      ))}
                      {(query.status === 'draft' || query.status === 'archived') && (
                        <button
                          onClick={() => handleDelete(query)}
                          className="text-red-600 hover:text-red-900"
                        >
                          删除
                        </button>
                      )}
                    </div>
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>

        {/* Pagination */}
        {result.pages > 1 && (
          <div className="flex items-center justify-between border-t border-gray-200 bg-white px-4 py-3">
            <div className="flex flex-1 justify-between sm:hidden">
              <button
                onClick={() => handlePageChange(result.current - 1)}
                disabled={result.current === 1 || loading}
                className="relative inline-flex items-center rounded-md border border-gray-300 bg-white px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50 disabled:opacity-50"
              >
                上一页
              </button>
              <button
                onClick={() => handlePageChange(result.current + 1)}
                disabled={result.current === result.pages || loading}
                className="relative ml-3 inline-flex items-center rounded-md border border-gray-300 bg-white px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50 disabled:opacity-50"
              >
                下一页
              </button>
            </div>
            <div className="hidden sm:flex sm:flex-1 sm:items-center sm:justify-between">
              <div>
                <p className="text-sm text-gray-700">
                  显示第{' '}
                  <span className="font-medium">{(result.current - 1) * result.size + 1}</span> 到{' '}
                  <span className="font-medium">
                    {Math.min(result.current * result.size, result.total)}
                  </span>{' '}
                  条，共 <span className="font-medium">{result.total}</span> 条
                </p>
              </div>
              <div>
                <nav className="relative z-0 inline-flex -space-x-px rounded-md shadow-sm">
                  <button
                    onClick={() => handlePageChange(result.current - 1)}
                    disabled={result.current === 1 || loading}
                    className="relative inline-flex items-center rounded-l-md border border-gray-300 bg-white px-2 py-2 text-sm font-medium text-gray-500 hover:bg-gray-50 disabled:opacity-50"
                  >
                    上一页
                  </button>
                  {Array.from({ length: Math.min(result.pages, 5) }, (_, i) => {
                    const page = i + 1;
                    return (
                      <button
                        key={page}
                        onClick={() => handlePageChange(page)}
                        className={`relative inline-flex items-center border px-4 py-2 text-sm font-medium ${
                          result.current === page
                            ? 'z-10 border-blue-500 bg-blue-50 text-blue-600'
                            : 'border-gray-300 bg-white text-gray-500 hover:bg-gray-50'
                        }`}
                      >
                        {page}
                      </button>
                    );
                  })}
                  <button
                    onClick={() => handlePageChange(result.current + 1)}
                    disabled={result.current === result.pages || loading}
                    className="relative inline-flex items-center rounded-r-md border border-gray-300 bg-white px-2 py-2 text-sm font-medium text-gray-500 hover:bg-gray-50 disabled:opacity-50"
                  >
                    下一页
                  </button>
                </nav>
              </div>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
