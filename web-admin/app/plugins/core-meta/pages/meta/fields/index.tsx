/**
 * Field Library Page
 *
 * Advanced field library management with search, filtering, and recommendations
 *
 * Features:
 * - Advanced search with multiple filters
 * - Field usage statistics
 * - Field recommendations
 * - Unused fields detection
 * - System fields management
 */

import { useState, useCallback, useEffect, useRef, useMemo } from 'react';
import { useLoaderData, useNavigate } from 'react-router';
import type { LoaderFunctionArgs } from 'react-router';
import { fieldLibraryService } from '~/services/fieldLibraryService';
import { useToastContext } from '~/contexts/ToastContext';
import type { FieldSearchRequest, MetaFieldDTO } from '~/types/fieldLibrary';
import { LoadingSpinner } from '~/components/LoadingSpinner';
import { ErrorAlert } from '~/components/ErrorAlert';
import { Pagination } from '~/components/Pagination';
import { ManagedBadge } from '~/components/common/ManagedBadge';
import { useBatchResourceOwners } from '~/hooks/useResourceOwner';
import { useDslRegistry } from '~/contexts/DslRegistryContext';

/**
 * Loader function - Load initial data
 */
export const loader = async ({ request }: LoaderFunctionArgs) => {
  try {
    const url = new URL(request.url);
    const keyword = url.searchParams.get('keyword') || '';
    const baseType = url.searchParams.get('baseType') || '';
    const semanticType = url.searchParams.get('semanticType') || '';
    const systemFieldsOnly = url.searchParams.get('systemFieldsOnly') === 'true';
    const unusedOnly = url.searchParams.get('unusedOnly') === 'true';
    const page = parseInt(url.searchParams.get('page') || '1');
    const size = parseInt(url.searchParams.get('size') || '20');

    const searchRequest: FieldSearchRequest = {
      keyword,
      baseType: baseType || undefined,
      semanticType: semanticType || undefined,
      systemFieldsOnly,
      unusedOnly,
      page,
      size,
    };

    const result = await fieldLibraryService.searchFields(searchRequest, request);

    return {
      initialData: result,
      initialParams: searchRequest,
    };
  } catch (error) {
    console.error('Failed to load field library:', error);
    return {
      initialData: {
        records: [],
        total: 0,
        current: 1,
        size: 20,
        pages: 0,
        hasPrevious: false,
        hasNext: false,
      },
      initialParams: {
        keyword: '',
        baseType: '',
        semanticType: '',
        systemFieldsOnly: false,
        unusedOnly: false,
        page: 1,
        size: 20,
      },
      error: error instanceof Error ? error.message : 'Failed to load field library',
    };
  }
};

/**
 * Field Library Page Component
 */
export default function FieldLibraryPage() {
  const loaderData = useLoaderData<typeof loader>();
  const { initialData, initialParams } = loaderData;
  const loaderError = 'error' in loaderData ? loaderData.error : null;

  const navigate = useNavigate();
  const { showErrorToast } = useToastContext();
  const { ensureLoaded, getEnumOptions } = useDslRegistry();
  useEffect(() => { ensureLoaded(); }, [ensureLoaded]);
  const dataTypeOptions = getEnumOptions('DataType');

  // State management
  const [data, setData] = useState<MetaFieldDTO[]>(initialData?.records || []);
  const [loading, setLoading] = useState(false);
  const [selectedPids, setSelectedPids] = useState<string[]>([]);
  const initialPaginationLoadRef = useRef(true);
  const initialResetLoadRef = useRef(true);

  // Filter state
  const [filters, setFilters] = useState<FieldSearchRequest>({
    keyword: initialParams?.keyword || '',
    baseType: initialParams?.baseType || '',
    semanticType: initialParams?.semanticType || '',
    systemFieldsOnly: initialParams?.systemFieldsOnly || false,
    unusedOnly: initialParams?.unusedOnly || false,
  });

  // Pagination state
  const [pagination, setPagination] = useState({
    current: initialParams?.page || 1,
    pageSize: initialParams?.size || 20,
    total: Number(initialData?.total || 0),
  });

  /**
   * Load data with explicit parameters (no closure over state to avoid infinite loops)
   */
  const loadData = useCallback(
    async (searchRequest: FieldSearchRequest) => {
      setLoading(true);
      try {
        const result = await fieldLibraryService.searchFields(searchRequest);
        setData(result.records || []);
        setPagination((prev) => ({
          ...prev,
          total: Number(result.total || 0),
        }));
      } catch (error) {
        console.error('Failed to load fields:', error);
        showErrorToast('加载字段库失败');
      } finally {
        setLoading(false);
      }
    },
    [showErrorToast],
  );

  /**
   * Search handler
   */
  const handleSearch = useCallback(() => {
    const searchRequest: FieldSearchRequest = {
      ...filters,
      page: 1,
      size: pagination.pageSize,
    };
    setPagination((prev) => ({ ...prev, current: 1 }));
    loadData(searchRequest);
  }, [filters, pagination.pageSize, loadData]);

  /**
   * Reset filters
   */
  const handleReset = useCallback(() => {
    setFilters({
      keyword: '',
      baseType: '',
      semanticType: '',
      systemFieldsOnly: false,
      unusedOnly: false,
    });
    setPagination((prev) => ({ ...prev, current: 1 }));
  }, []);

  // Reload data after reset (when all filters become empty)
  useEffect(() => {
    if (initialResetLoadRef.current) {
      initialResetLoadRef.current = false;
      return;
    }
    if (
      !filters.keyword &&
      !filters.baseType &&
      !filters.semanticType &&
      !filters.systemFieldsOnly &&
      !filters.unusedOnly
    ) {
      loadData({
        ...filters,
        page: 1,
        size: pagination.pageSize,
      });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [filters]);

  /**
   * View field details
   */
  const handleView = useCallback(
    (pid: string) => {
      navigate(`/meta/fields/${pid}`);
    },
    [navigate],
  );

  /**
   * View field usage
   */
  const handleViewUsage = useCallback(
    (pid: string) => {
      navigate(`/meta/fields/${pid}/usage`);
    },
    [navigate],
  );

  /**
   * View field impact
   */
  const handleViewImpact = useCallback(
    (pid: string) => {
      navigate(`/meta/fields/${pid}/impact`);
    },
    [navigate],
  );

  /**
   * Create new field
   */
  const handleCreate = useCallback(() => {
    navigate('/meta/fields/new');
  }, [navigate]);

  /**
   * Page change handler
   */
  const handlePageChange = useCallback((page: number) => {
    setPagination((prev) => ({
      ...prev,
      current: page,
    }));
  }, []);

  // Reload data when pagination changes (but not on initial mount)
  useEffect(() => {
    if (initialPaginationLoadRef.current) {
      initialPaginationLoadRef.current = false;
      return;
    }
    loadData({
      ...filters,
      page: pagination.current,
      size: pagination.pageSize,
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pagination.current, pagination.pageSize]);

  /**
   * Row selection handler
   */
  const handleSelectRow = useCallback((pid: string, checked: boolean) => {
    setSelectedPids((prev) => (checked ? [...prev, pid] : prev.filter((id) => id !== pid)));
  }, []);

  /**
   * Select all handler
   */
  const handleSelectAll = useCallback(
    (checked: boolean) => {
      setSelectedPids(checked ? data.map((item) => item.pid) : []);
    },
    [data],
  );

  // Batch query resource ownership for managed badges
  const resourceRefs = useMemo(() => data.map((f) => ({ type: 'field', code: f.code })), [data]);
  const { owners } = useBatchResourceOwners(resourceRefs);

  // Render loading state
  if (loading && data.length === 0) {
    return <LoadingSpinner />;
  }

  return (
    <div className="p-6">
      {/* Page title */}
      <div className="mb-6">
        <h1 className="text-2xl font-bold text-gray-900">字段库</h1>
        <p className="mt-1 text-sm text-gray-500">
          管理和搜索可复用的字段定义，查看字段使用情况和影响分析
        </p>
      </div>

      {/* Display loader error */}
      {loaderError && (
        <div className="mb-4">
          <ErrorAlert error={loaderError} />
        </div>
      )}

      {/* Filter area */}
      <div className="mb-4 rounded-lg bg-white p-4 shadow">
        <div className="grid grid-cols-12 gap-4">
          {/* Keyword search */}
          <div className="col-span-4">
            <label className="mb-1 block text-sm font-medium text-gray-700">关键词</label>
            <input
              type="text"
              value={filters.keyword || ''}
              onChange={(e) => setFilters((prev) => ({ ...prev, keyword: e.target.value }))}
              placeholder="搜索字段编码或名称"
              className="w-full rounded-md border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
            />
          </div>

          {/* Base type filter */}
          <div className="col-span-3">
            <label className="mb-1 block text-sm font-medium text-gray-700">基础类型</label>
            <select
              value={filters.baseType || ''}
              onChange={(e) => setFilters((prev) => ({ ...prev, baseType: e.target.value }))}
              className="w-full rounded-md border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
            >
              <option value="">All Types</option>
              {dataTypeOptions.map((dt) => (
                <option key={dt.value} value={dt.value}>
                  {dt.label}
                </option>
              ))}
            </select>
          </div>

          {/* Semantic type filter */}
          <div className="col-span-3">
            <label className="mb-1 block text-sm font-medium text-gray-700">语义类型</label>
            <input
              type="text"
              value={filters.semanticType || ''}
              onChange={(e) => setFilters((prev) => ({ ...prev, semanticType: e.target.value }))}
              placeholder="如: email, phone"
              className="w-full rounded-md border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
            />
          </div>

          {/* Checkboxes */}
          <div className="col-span-2 flex flex-col justify-end space-y-2">
            <label className="flex items-center">
              <input
                type="checkbox"
                checked={filters.systemFieldsOnly || false}
                onChange={(e) =>
                  setFilters((prev) => ({ ...prev, systemFieldsOnly: e.target.checked }))
                }
                className="mr-2 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
              />
              <span className="text-sm text-gray-700">仅系统字段</span>
            </label>
            <label className="flex items-center">
              <input
                type="checkbox"
                checked={filters.unusedOnly || false}
                onChange={(e) => setFilters((prev) => ({ ...prev, unusedOnly: e.target.checked }))}
                className="mr-2 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
              />
              <span className="text-sm text-gray-700">仅未使用</span>
            </label>
          </div>
        </div>

        {/* Button group */}
        <div className="mt-4 flex gap-2">
          <button
            onClick={handleSearch}
            className="rounded-md bg-blue-600 px-4 py-2 text-white hover:bg-blue-700 focus:ring-2 focus:ring-blue-500 focus:outline-none"
          >
            搜索
          </button>
          <button
            onClick={handleReset}
            className="rounded-md bg-gray-200 px-4 py-2 text-gray-700 hover:bg-gray-300 focus:ring-2 focus:ring-gray-500 focus:outline-none"
          >
            重置
          </button>
        </div>
      </div>

      {/* Toolbar */}
      <div className="mb-4 flex items-center justify-between">
        <div className="text-sm text-gray-600">
          {selectedPids.length > 0 && <span>已选择 {selectedPids.length} 项</span>}
        </div>
        <div className="flex gap-2">
          <button
            onClick={handleCreate}
            className="rounded-md bg-blue-600 px-4 py-2 text-white hover:bg-blue-700 focus:ring-2 focus:ring-blue-500 focus:outline-none"
          >
            新建字段
          </button>
        </div>
      </div>

      {/* Data table */}
      <div className="overflow-hidden rounded-lg bg-white shadow">
        <table className="min-w-full divide-y divide-gray-200">
          <thead className="bg-gray-50">
            <tr>
              <th className="px-6 py-3 text-left">
                <input
                  type="checkbox"
                  checked={selectedPids.length === data.length && data.length > 0}
                  onChange={(e) => handleSelectAll(e.target.checked)}
                  className="rounded border-gray-300 text-blue-600 focus:ring-blue-500"
                />
              </th>
              <th className="px-6 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase">
                字段编码
              </th>
              <th className="px-6 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase">
                数据类型
              </th>
              <th className="px-6 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase">
                状态
              </th>
              <th className="px-6 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase">
                版本
              </th>
              <th className="px-6 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase">
                必填
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
                <td colSpan={8} className="px-6 py-12 text-center text-gray-500">
                  暂无数据
                </td>
              </tr>
            ) : (
              data.map((field) => {
                const owner = owners[`field:${field.code}`];
                return (
                  <tr key={field.pid} className="hover:bg-gray-50">
                    <td className="px-6 py-4">
                      <input
                        type="checkbox"
                        checked={selectedPids.includes(field.pid)}
                        onChange={(e) => handleSelectRow(field.pid, e.target.checked)}
                        className="rounded border-gray-300 text-blue-600 focus:ring-blue-500"
                      />
                    </td>
                    <td className="px-6 py-4 text-sm font-medium whitespace-nowrap text-gray-900">
                      <span className="inline-flex items-center gap-2">
                        {field.code}
                        {owner?.managed && (
                          <ManagedBadge
                            pluginName={owner.pluginName || ''}
                            userModified={owner.userModified}
                          />
                        )}
                      </span>
                    </td>
                    <td className="px-6 py-4 text-sm whitespace-nowrap text-gray-500">
                      {field.dataType}
                    </td>
                    <td className="px-6 py-4 whitespace-nowrap">
                      <span
                        className={`rounded-full px-2 py-1 text-xs ${
                          field.status === 'published'
                            ? 'bg-green-100 text-green-800'
                            : field.status === 'draft'
                              ? 'bg-yellow-100 text-yellow-800'
                              : 'bg-gray-100 text-gray-800'
                        }`}
                      >
                        {field.status === 'published'
                          ? '已发布'
                          : field.status === 'draft'
                            ? '草稿'
                            : field.status}
                      </span>
                    </td>
                    <td className="px-6 py-4 text-sm whitespace-nowrap text-gray-500">
                      v{field.version}
                    </td>
                    <td className="px-6 py-4 text-sm whitespace-nowrap text-gray-500">
                      {field.required ? '是' : '否'}
                    </td>
                    <td className="px-6 py-4 text-sm whitespace-nowrap text-gray-500">
                      {new Date(field.createdAt).toLocaleDateString('zh-CN')}
                    </td>
                    <td className="px-6 py-4 text-right text-sm font-medium whitespace-nowrap">
                      <button
                        onClick={() => handleView(field.pid)}
                        className="mr-3 text-blue-600 hover:text-blue-900"
                      >
                        查看
                      </button>
                      <button
                        onClick={() => handleViewUsage(field.pid)}
                        className="mr-3 text-indigo-600 hover:text-indigo-900"
                      >
                        使用情况
                      </button>
                      <button
                        onClick={() => handleViewImpact(field.pid)}
                        className="text-purple-600 hover:text-purple-900"
                      >
                        影响分析
                      </button>
                    </td>
                  </tr>
                );
              })
            )}
          </tbody>
        </table>
      </div>

      {/* Pagination */}
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
