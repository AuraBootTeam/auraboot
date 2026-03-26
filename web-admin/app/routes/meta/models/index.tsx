/**
 * Model列表页面
 *
 * 基于DSL驱动的Model管理列表页面
 * DSL文件位置: web-admin/app/studio/dsl/model/list.model.json
 *
 * 功能特性:
 * - 分页查询Model列表
 * - 搜索和过滤（关键词、模型类型、状态）
 * - 批量操作（批量删除、导出）
 * - CRUD操作（新建、编辑、查看、删除）
 * - Git-First工作流集成
 * - 权限控制集成
 */

import React, { useState, useCallback, useEffect, useMemo } from 'react';
import { useLoaderData, useNavigate } from 'react-router';
import type { LoaderFunctionArgs } from 'react-router';
import { getTokenFromRequest } from '~/services/session';
import { modelService } from '~/services/modelService';
import { confirmDialog } from '~/utils/confirmDialog';
import { useToastContext } from '~/contexts/ToastContext';
import type { MetaModelDTO, ModelQueryParams } from '~/types/model';
import { LoadingSpinner } from '~/components/LoadingSpinner';
import { ErrorAlert } from '~/components/ErrorAlert';
import { Pagination } from '~/components/Pagination';
import { ManagedBadge } from '~/components/common/ManagedBadge';
import { useBatchResourceOwners } from '~/hooks/useResourceOwner';

/**
 * Loader函数 - 加载初始数据
 */
export const loader = async ({ request }: LoaderFunctionArgs) => {
  try {
    const token = await getTokenFromRequest(request);

    // 从URL参数获取查询条件
    const url = new URL(request.url);
    const keyword = url.searchParams.get('keyword') || '';
    const modelType = url.searchParams.get('modelType') || '';
    const status = url.searchParams.get('status') || '';
    const page = parseInt(url.searchParams.get('page') || '1');
    const size = parseInt(url.searchParams.get('size') || '20');

    const params: ModelQueryParams = {
      keyword,
      modelType: (modelType || '') as any,
      status: (status || '') as any,
      page,
      size,
    };

    // 加载Model列表数据
    const result = await modelService.findByPage(params, request);

    // 确保返回的数据结构正确
    const safeResult = {
      data: result?.data || [],
      total: result?.total || 0,
      page: result?.page || page,
      size: result?.size || size,
      totalPages: result?.totalPages || 0,
    };

    return {
      token,
      initialData: safeResult,
      initialParams: params,
    };
  } catch (error) {
    console.error('Failed to load model list:', error);
    // 返回空数据而不是抛出错误，让页面可以正常渲染
    return {
      token: null,
      initialData: {
        data: [],
        total: 0,
        page: 1,
        size: 20,
        totalPages: 0,
      },
      initialParams: {
        keyword: '',
        modelType: '' as any,
        status: '' as any,
        page: 1,
        size: 20,
      },
      error: error instanceof Error ? error.message : 'Failed to load model list',
    };
  }
};

/**
 * Model列表页面组件
 */
export default function ModelListPage() {
  const loaderData = useLoaderData<typeof loader>();
  const { token, initialData, initialParams } = loaderData;
  const loaderError = 'error' in loaderData ? loaderData.error : null;

  const navigate = useNavigate();
  const { showSuccessToast, showErrorToast } = useToastContext();

  // 状态管理
  const [data, setData] = useState<MetaModelDTO[]>(initialData?.data || []);
  const [loading, setLoading] = useState(false);
  const [selectedIds, setSelectedIds] = useState<string[]>([]);

  // 过滤条件状态
  const [filters, setFilters] = useState({
    keyword: initialParams.keyword || '',
    modelType: initialParams.modelType || '',
    status: initialParams.status || '',
  });

  // 分页状态
  const [pagination, setPagination] = useState({
    current: initialParams.page || 1,
    pageSize: initialParams.size || 20,
    total: initialData?.total || 0,
  });

  /**
   * 加载数据
   */
  const loadData = useCallback(async (params: ModelQueryParams) => {
    setLoading(true);
    try {
      const result = await modelService.findByPage(params);
      setData(result?.data || []);
      setPagination((prev) => ({
        ...prev,
        total: result?.total || 0,
      }));
    } catch (error) {
      console.error('Failed to load models:', error);
      showErrorToast('加载Model列表失败');
    } finally {
      setLoading(false);
    }
  }, [showErrorToast]);

  /**
   * 搜索处理
   */
  const handleSearch = useCallback(() => {
    setPagination((prev) => ({ ...prev, current: 1 }));
    loadData({
      keyword: filters.keyword,
      modelType: (filters.modelType || '') as any,
      status: (filters.status || '') as any,
      page: 1,
      size: pagination.pageSize,
    });
  }, [filters, pagination.pageSize, loadData]);

  const handleKeywordKeyDown = useCallback(
    (event: React.KeyboardEvent<HTMLInputElement>) => {
      if (event.key === 'Enter') {
        event.preventDefault();
        handleSearch();
      }
    },
    [handleSearch],
  );

  /**
   * 重置过滤条件
   */
  const handleReset = useCallback(() => {
    setFilters({
      keyword: '',
      modelType: '',
      status: '',
    });
    setPagination((prev) => ({ ...prev, current: 1 }));
  }, []);

  // 重置后重新加载数据
  useEffect(() => {
    if (!filters.keyword && !filters.modelType && !filters.status) {
      loadData({
        keyword: '',
        modelType: '' as any,
        status: '' as any,
        page: 1,
        size: pagination.pageSize,
      });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [filters]);

  /**
   * 打开创建表单
   */
  const handleCreate = useCallback(() => {
    navigate('/meta/models/new');
  }, [navigate]);

  /**
   * 打开编辑表单
   */
  const handleEdit = useCallback(
    (pid: string) => {
      navigate(`/meta/models/${pid}/edit`);
    },
    [navigate],
  );

  /**
   * 打开详情页
   */
  const handleView = useCallback(
    (pid: string) => {
      navigate(`/meta/models/${pid}`);
    },
    [navigate],
  );

  /**
   * 删除单个Model
   */
  const handleDelete = useCallback(
    async (pid: string, displayName: string) => {
      const confirmed = await confirmDialog({
        title: '确认删除',
        content: `确定要删除Model "${displayName}" 吗？此操作不可恢复。`,
        variant: 'danger',
      });
      if (!confirmed) return;

      try {
        await modelService.delete(pid);
        showSuccessToast('删除成功');
        loadData({
          keyword: filters.keyword,
          modelType: (filters.modelType || '') as any,
          status: (filters.status || '') as any,
          page: pagination.current,
          size: pagination.pageSize,
        });
      } catch (error) {
        console.error('Failed to delete model:', error);
        showErrorToast('删除失败');
      }
    },
    [filters, pagination.current, pagination.pageSize, loadData, showSuccessToast, showErrorToast],
  );

  /**
   * 批量删除
   */
  const handleBatchDelete = useCallback(async () => {
    if (selectedIds.length === 0) {
      showErrorToast('请先选择要删除的Model');
      return;
    }

    const confirmed = await confirmDialog({
      title: '确认批量删除',
      content: `确定要删除选中的 ${selectedIds.length} 个Model吗？此操作不可恢复。`,
      variant: 'danger',
    });
    if (!confirmed) return;

    try {
      await modelService.batchDelete(selectedIds);
      showSuccessToast(`成功删除 ${selectedIds.length} 个Model`);
      setSelectedIds([]);
      loadData({
        keyword: filters.keyword,
        modelType: (filters.modelType || '') as any,
        status: (filters.status || '') as any,
        page: pagination.current,
        size: pagination.pageSize,
      });
    } catch (error) {
      console.error('Failed to batch delete models:', error);
      showErrorToast('批量删除失败');
    }
  }, [selectedIds, filters, pagination.current, pagination.pageSize, loadData, showSuccessToast, showErrorToast]);

  /**
   * 导出Model
   */
  const handleExport = useCallback(async () => {
    try {
      const exportParams: ModelQueryParams = {
        keyword: filters.keyword,
        modelType: (filters.modelType || '') as any,
        status: (filters.status || '') as any,
      };
      const blob = await modelService.exportModels(exportParams, selectedIds);
      const url = window.URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `models_${new Date().toISOString().split('T')[0]}.xlsx`;
      document.body.appendChild(a);
      a.click();
      window.URL.revokeObjectURL(url);
      document.body.removeChild(a);
      showSuccessToast('导出成功');
    } catch (error) {
      console.error('Failed to export models:', error);
      showErrorToast('导出失败');
    }
  }, [filters, selectedIds, showSuccessToast, showErrorToast]);

  /**
   * 分页变化处理
   */
  const handlePageChange = useCallback((page: number) => {
    setPagination((prev) => ({
      ...prev,
      current: page,
    }));
  }, []);

  // 分页变化时重新加载数据
  useEffect(() => {
    loadData({
      keyword: filters.keyword,
      modelType: (filters.modelType || '') as any,
      status: (filters.status || '') as any,
      page: pagination.current,
      size: pagination.pageSize,
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pagination.current, pagination.pageSize]);

  /**
   * 行选择处理
   */
  const handleSelectRow = useCallback((pid: string, checked: boolean) => {
    setSelectedIds((prev) => (checked ? [...prev, pid] : prev.filter((id) => id !== pid)));
  }, []);

  /**
   * 全选处理
   */
  const handleSelectAll = useCallback(
    (checked: boolean) => {
      setSelectedIds(checked ? data.map((item) => item.pid) : []);
    },
    [data],
  );

  // Batch query resource ownership for managed badges
  const resourceRefs = useMemo(() => data.map((m) => ({ type: 'model', code: m.code })), [data]);
  const { owners } = useBatchResourceOwners(resourceRefs);

  // 渲染加载状态
  if (loading && data.length === 0) {
    return <LoadingSpinner />;
  }

  return (
    <div className="p-6">
      {/* 页面标题 */}
      <div className="mb-6">
        <h1 className="text-2xl font-bold text-gray-900">模型管理</h1>
        <p className="mt-1 text-sm text-gray-500">
          管理系统中的业务实体模型，定义数据结构和字段关系
        </p>
      </div>

      {/* 显示加载错误 */}
      {loaderError && (
        <div className="mb-4">
          <ErrorAlert error={loaderError} />
        </div>
      )}

      {/* 过滤器区域 */}
      <div className="mb-4 rounded-lg bg-white p-4 shadow">
        <div className="grid grid-cols-12 gap-4">
          {/* 关键词搜索 */}
          <div className="col-span-4">
            <label className="mb-1 block text-sm font-medium text-gray-700">关键词</label>
            <input
              type="text"
              value={filters.keyword}
              onChange={(e) => setFilters((prev) => ({ ...prev, keyword: e.target.value }))}
              onKeyDown={handleKeywordKeyDown}
              placeholder="搜索模型编码或名称"
              data-testid="filter-keyword"
              className="w-full rounded-md border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
            />
          </div>

          {/* 模型类型筛选 */}
          <div className="col-span-4">
            <label className="mb-1 block text-sm font-medium text-gray-700">模型类型</label>
            <select
              value={filters.modelType}
              onChange={(e) =>
                setFilters((prev) => ({ ...prev, modelType: e.target.value as any }))
              }
              className="w-full rounded-md border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
            >
              <option value="">全部类型</option>
              <option value="entity">实体</option>
              <option value="view">视图</option>
              <option value="aggregate">聚合</option>
            </select>
          </div>

          {/* 状态筛选 */}
          <div className="col-span-4">
            <label className="mb-1 block text-sm font-medium text-gray-700">状态</label>
            <select
              value={filters.status}
              onChange={(e) => setFilters((prev) => ({ ...prev, status: e.target.value as any }))}
              className="w-full rounded-md border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
            >
              <option value="">全部状态</option>
              <option value="draft">草稿</option>
              <option value="published">已发布</option>
              <option value="archived">已归档</option>
            </select>
          </div>
        </div>

        {/* 按钮组 */}
        <div className="mt-4 flex gap-2">
          <button
            onClick={handleSearch}
            data-testid="filter-search"
            className="rounded-md bg-blue-600 px-4 py-2 text-white hover:bg-blue-700 focus:ring-2 focus:ring-blue-500 focus:outline-none"
          >
            搜索
          </button>
          <button
            onClick={handleReset}
            data-testid="filter-reset"
            className="rounded-md bg-gray-200 px-4 py-2 text-gray-700 hover:bg-gray-300 focus:ring-2 focus:ring-gray-500 focus:outline-none"
          >
            重置
          </button>
        </div>
      </div>

      {/* 工具栏 */}
      <div className="mb-4 flex items-center justify-between">
        <div className="text-sm text-gray-600">
          {selectedIds.length > 0 && <span>已选择 {selectedIds.length} 项</span>}
        </div>
        <div className="flex gap-2">
          <button
            onClick={handleCreate}
            data-testid="toolbar-btn-create"
            className="rounded-md bg-blue-600 px-4 py-2 text-white hover:bg-blue-700 focus:ring-2 focus:ring-blue-500 focus:outline-none"
          >
            新建模型
          </button>
          <button
            onClick={handleExport}
            data-testid="toolbar-btn-export"
            className="rounded-md bg-green-600 px-4 py-2 text-white hover:bg-green-700 focus:ring-2 focus:ring-green-500 focus:outline-none"
          >
            导出
          </button>
          {selectedIds.length > 0 && (
            <button
              onClick={handleBatchDelete}
              data-testid="toolbar-btn-batch-delete"
              className="rounded-md bg-red-600 px-4 py-2 text-white hover:bg-red-700 focus:ring-2 focus:ring-red-500 focus:outline-none"
            >
              批量删除
            </button>
          )}
        </div>
      </div>

      {/* 数据表格 */}
      <div className="overflow-hidden rounded-lg bg-white shadow">
        <table className="min-w-full divide-y divide-gray-200">
          <thead className="bg-gray-50">
            <tr>
              <th className="px-6 py-3 text-left">
                <input
                  type="checkbox"
                  checked={selectedIds.length === data.length && data.length > 0}
                  onChange={(e) => handleSelectAll(e.target.checked)}
                  className="rounded border-gray-300 text-blue-600 focus:ring-blue-500"
                />
              </th>
              <th className="px-6 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase">
                模型编码
              </th>
              <th className="px-6 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase">
                显示名称
              </th>
              <th className="px-6 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase">
                模型类型
              </th>
              <th className="px-6 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase">
                状态
              </th>
              <th className="px-6 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase">
                版本号
              </th>
              <th className="px-6 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase">
                字段数
              </th>
              <th className="px-6 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase">
                创建时间
              </th>
              <th className="px-6 py-3 text-right text-xs font-medium tracking-wider text-gray-500 uppercase">
                操作
              </th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-200 bg-white">
            {data.length === 0 ? (
              <tr>
                <td colSpan={9} className="px-6 py-12 text-center text-gray-500">
                  暂无数据
                </td>
              </tr>
            ) : (
              data.map((model) => {
                const owner = owners[`model:${model.code}`];
                return (
                <tr key={model.pid} className="hover:bg-gray-50">
                  <td className="px-6 py-4">
                    <input
                      type="checkbox"
                      checked={selectedIds.includes(model.pid)}
                      onChange={(e) => handleSelectRow(model.pid, e.target.checked)}
                      className="rounded border-gray-300 text-blue-600 focus:ring-blue-500"
                    />
                  </td>
                  <td className="px-6 py-4 text-sm font-medium whitespace-nowrap text-gray-900">
                    <span className="inline-flex items-center gap-2">
                      {model.code}
                      {owner?.managed && (
                        <ManagedBadge
                          pluginName={owner.pluginName || ''}
                          userModified={owner.userModified}
                        />
                      )}
                    </span>
                  </td>
                  <td className="px-6 py-4 text-sm whitespace-nowrap text-gray-900">
                    {model.displayName}
                  </td>
                  <td className="px-6 py-4 text-sm whitespace-nowrap text-gray-500">
                    {model.modelType === 'entity'
                      ? '实体'
                      : model.modelType === 'view'
                        ? '视图'
                        : '聚合'}
                  </td>
                  <td className="px-6 py-4 whitespace-nowrap">
                    <span
                      className={`rounded-full px-2 py-1 text-xs ${
                        model.status === 'published'
                          ? 'bg-green-100 text-green-800'
                          : model.status === 'draft'
                            ? 'bg-yellow-100 text-yellow-800'
                            : 'bg-gray-100 text-gray-800'
                      }`}
                    >
                      {model.status === 'published'
                        ? '已发布'
                        : model.status === 'draft'
                          ? '草稿'
                          : '已归档'}
                    </span>
                  </td>
                  <td className="px-6 py-4 text-sm whitespace-nowrap text-gray-500">
                    v{model.version}
                  </td>
                  <td className="px-6 py-4 text-sm whitespace-nowrap text-gray-500">
                    {model.fieldCount || 0}
                  </td>
                  <td className="px-6 py-4 text-sm whitespace-nowrap text-gray-500">
                    {new Date(model.createdAt).toLocaleDateString('zh-CN')}
                  </td>
                  <td className="px-6 py-4 text-right text-sm font-medium whitespace-nowrap">
                    <button
                      onClick={() => handleView(model.pid)}
                      className="mr-3 text-blue-600 hover:text-blue-900"
                    >
                      查看
                    </button>
                    <button
                      onClick={() => handleEdit(model.pid)}
                      className="mr-3 text-indigo-600 hover:text-indigo-900"
                    >
                      编辑
                    </button>
                    <button
                      onClick={() => handleDelete(model.pid, model.displayName)}
                      className="text-red-600 hover:text-red-900"
                    >
                      删除
                    </button>
                  </td>
                </tr>
              )})
            )}
          </tbody>
        </table>
      </div>

      {/* 分页 */}
      {data.length > 0 && (
        <div className="mt-4">
          <Pagination
            current={pagination.current}
            pageSize={pagination.pageSize}
            total={pagination.total}
            onChange={handlePageChange}
          />
        </div>
      )}
    </div>
  );
}
