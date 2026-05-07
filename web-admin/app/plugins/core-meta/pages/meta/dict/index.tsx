/**
 * Dictionary List Page
 *
 * Displays a paginated list of dictionaries with search and filter capabilities
 */

import React, { useState, useCallback, useEffect, useRef } from 'react';
import { Link, useNavigate, useLoaderData } from 'react-router';
import type { LoaderFunctionArgs } from 'react-router';
import { dictService } from '~/shared/services/dictService';
import { confirmDialog } from '~/utils/confirmDialog';
import { useToastContext } from '~/contexts/ToastContext';
import type { DictDTO, DictQueryRequest, DictPageResult } from '~/types/dict';
import { StatusBadge } from '~/ui/common/StatusBadge';

/**
 * Loader function
 */
export const loader = async ({ request }: LoaderFunctionArgs) => {
  const url = new URL(request.url);
  const pageNum = parseInt(url.searchParams.get('page') || '1');
  const pageSize = parseInt(url.searchParams.get('size') || '20');
  const code = url.searchParams.get('code') || undefined;
  const name = url.searchParams.get('name') || undefined;
  const dictType = url.searchParams.get('dictType') || undefined;
  const status = url.searchParams.get('status') || undefined;

  const queryRequest: DictQueryRequest = {
    pageNum,
    pageSize,
    code,
    name,
    dictType,
    status,
  };

  try {
    const result = await dictService.query(queryRequest, request);
    return { result, query: queryRequest };
  } catch (error) {
    console.error('Failed to load dictionaries:', error);
    throw new Response('Failed to load dictionaries', { status: 500 });
  }
};

/**
 * Dictionary List Page Component
 */
export default function DictListPage() {
  const navigate = useNavigate();
  const { result: initialResult, query: initialQuery } = useLoaderData<typeof loader>();
  const { showSuccessToast, showErrorToast } = useToastContext();

  const [result, setResult] = useState<DictPageResult<DictDTO>>(initialResult);
  const [loading, setLoading] = useState(false);
  const [hydrated, setHydrated] = useState(false);
  const [selectedDicts, setSelectedDicts] = useState<Set<string>>(new Set());

  const codeFilterRef = useRef(initialQuery.code || '');
  const nameFilterRef = useRef(initialQuery.name || '');
  const typeFilterRef = useRef(initialQuery.dictType || '');
  const statusFilterRef = useRef(initialQuery.status || '');
  const codeInputRef = useRef<HTMLInputElement | null>(null);
  const nameInputRef = useRef<HTMLInputElement | null>(null);
  const typeSelectRef = useRef<HTMLSelectElement | null>(null);
  const statusSelectRef = useRef<HTMLSelectElement | null>(null);

  useEffect(() => {
    setHydrated(true);
  }, []);

  /**
   * Handle page change
   */
  const handlePageChange = useCallback(
    async (page: number) => {
      setLoading(true);
      try {
        const queryRequest: DictQueryRequest = {
          pageNum: page,
          pageSize: result.size,
          code: codeFilterRef.current || undefined,
          name: nameFilterRef.current || undefined,
          dictType: typeFilterRef.current || undefined,
          status: statusFilterRef.current || undefined,
        };

        const newResult = await dictService.query(queryRequest);
        setResult(newResult);
      } catch (error) {
        console.error('Failed to load page:', error);
        showErrorToast('加载失败');
      } finally {
        setLoading(false);
      }
    },
    [result.size, showErrorToast],
  );

  /**
   * Handle create
   */
  const handleCreate = useCallback(() => {
    navigate('/meta/dict/new');
  }, [navigate]);

  /**
   * Handle delete
   */
  const handleDelete = useCallback(
    async (dict: DictDTO) => {
      const confirmed = await confirmDialog({
        content: `确定要删除字典 "${dict.name}" 吗？此操作不可恢复。`,
        variant: 'danger',
      });

      if (!confirmed) return;

      setLoading(true);
      try {
        await dictService.delete(dict.pid);
        showSuccessToast('删除成功');

        // Reload current page
        await handlePageChange(result.current);
      } catch (error) {
        console.error('Failed to delete dictionary:', error);
        showErrorToast('删除失败');
      } finally {
        setLoading(false);
      }
    },
    [result.current, handlePageChange, showSuccessToast, showErrorToast],
  );

  /**
   * Handle batch delete
   */
  const handleBatchDelete = useCallback(async () => {
    if (selectedDicts.size === 0) {
      showErrorToast('请选择要删除的字典');
      return;
    }

    const confirmed = await confirmDialog({
      content: `确定要删除选中的 ${selectedDicts.size} 个字典吗？此操作不可恢复。`,
      variant: 'danger',
    });

    if (!confirmed) return;

    setLoading(true);
    try {
      await dictService.batchDelete(Array.from(selectedDicts));
      showSuccessToast(`成功删除 ${selectedDicts.size} 个字典`);
      setSelectedDicts(new Set());

      // Reload current page
      await handlePageChange(result.current);
    } catch (error) {
      console.error('Failed to batch delete:', error);
      showErrorToast('批量删除失败');
    } finally {
      setLoading(false);
    }
  }, [selectedDicts, result.current, handlePageChange, showSuccessToast, showErrorToast]);

  /**
   * Handle select all
   */
  const handleSelectAll = useCallback(() => {
    if (selectedDicts.size === result.records.length) {
      setSelectedDicts(new Set());
    } else {
      setSelectedDicts(new Set(result.records.map((d: DictDTO) => d.pid)));
    }
  }, [selectedDicts, result.records]);

  /**
   * Handle select one
   */
  const handleSelectOne = useCallback(
    (pid: string) => {
      const newSelected = new Set(selectedDicts);
      if (newSelected.has(pid)) {
        newSelected.delete(pid);
      } else {
        newSelected.add(pid);
      }
      setSelectedDicts(newSelected);
    },
    [selectedDicts],
  );

  /**
   * Get type badge color
   */
  const getTypeBadgeColor = (type: string) => {
    switch (type) {
      case 'simple':
        return 'bg-blue-100 text-blue-800';
      case 'tree':
        return 'bg-green-100 text-green-800';
      case 'cascade':
        return 'bg-purple-100 text-purple-800';
      default:
        return 'bg-gray-100 text-gray-800';
    }
  };

  return (
    <div className="mx-auto w-full px-2 py-3" data-testid="dictionary-list">
      {/* Header */}
      <div className="mb-6 flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">字典管理</h1>
          <p className="mt-1 text-sm text-gray-500">
            管理系统字典数据，包括简单字典、树形字典和级联字典
          </p>
        </div>
        <button
          onClick={handleCreate}
          data-testid="toolbar-btn-create"
          className="inline-flex items-center rounded-md border border-transparent bg-blue-600 px-4 py-2 text-sm font-medium text-white shadow-sm hover:bg-blue-700 focus:ring-2 focus:ring-blue-500 focus:ring-offset-2 focus:outline-none"
          disabled={loading}
        >
          <svg className="mr-2 -ml-1 h-5 w-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 4v16m8-8H4" />
          </svg>
          创建字典
        </button>
      </div>

      {/* Filters */}
      <form action="/meta/dict" method="get" className="mb-6 rounded-lg bg-white p-4 shadow">
        <div className="grid grid-cols-1 gap-4 md:grid-cols-4">
          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700">字典编码</label>
            <input
              ref={codeInputRef}
              name="code"
              type="text"
              defaultValue={initialQuery.code || ''}
              onChange={(e) => {
                codeFilterRef.current = e.target.value;
              }}
              placeholder="输入字典编码"
              data-testid="filter-code"
              disabled={!hydrated}
              className="w-full rounded-md border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
            />
          </div>
          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700">字典名称</label>
            <input
              ref={nameInputRef}
              name="name"
              type="text"
              defaultValue={initialQuery.name || ''}
              onChange={(e) => {
                nameFilterRef.current = e.target.value;
              }}
              placeholder="输入字典名称"
              data-testid="filter-name"
              disabled={!hydrated}
              className="w-full rounded-md border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
            />
          </div>
          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700">字典类型</label>
            <select
              ref={typeSelectRef}
              name="dictType"
              defaultValue={initialQuery.dictType || ''}
              onChange={(e) => {
                typeFilterRef.current = e.target.value;
              }}
              data-testid="filter-type"
              disabled={!hydrated}
              className="w-full rounded-md border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
            >
              <option value="">全部类型</option>
              <option value="simple">简单字典</option>
              <option value="tree">树形字典</option>
              <option value="cascade">级联字典</option>
            </select>
          </div>
          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700">状态</label>
            <select
              ref={statusSelectRef}
              name="status"
              defaultValue={initialQuery.status || ''}
              onChange={(e) => {
                statusFilterRef.current = e.target.value;
              }}
              data-testid="filter-status"
              disabled={!hydrated}
              className="w-full rounded-md border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
            >
              <option value="">全部状态</option>
              <option value="draft">草稿</option>
              <option value="published">已发布</option>
              <option value="deprecated">已废弃</option>
              <option value="archived">已归档</option>
              <option value="disabled">已禁用</option>
            </select>
          </div>
        </div>
        <div className="mt-4 flex justify-end gap-2">
          <button
            type="button"
            onClick={() => {
              codeFilterRef.current = '';
              nameFilterRef.current = '';
              typeFilterRef.current = '';
              statusFilterRef.current = '';
              if (codeInputRef.current) codeInputRef.current.value = '';
              if (nameInputRef.current) nameInputRef.current.value = '';
              if (typeSelectRef.current) typeSelectRef.current.value = '';
              if (statusSelectRef.current) statusSelectRef.current.value = '';
              navigate('/meta/dict');
            }}
            data-testid="filter-reset"
            className="rounded-md border border-gray-300 px-4 py-2 text-gray-700 hover:bg-gray-50"
            disabled={!hydrated}
          >
            重置
          </button>
          <button
            type="submit"
            data-testid="filter-search"
            className="rounded-md bg-blue-600 px-4 py-2 text-white hover:bg-blue-700"
            disabled={!hydrated || loading}
          >
            搜索
          </button>
        </div>
      </form>

      {/* Batch Actions */}
      {selectedDicts.size > 0 && (
        <div className="mb-4 rounded-md border border-blue-200 bg-blue-50 p-3">
          <div className="flex items-center justify-between">
            <span className="text-sm text-blue-800">已选择 {selectedDicts.size} 个字典</span>
            <button
              onClick={handleBatchDelete}
              data-testid="toolbar-btn-batch-delete"
              className="text-sm text-red-600 hover:text-red-900"
              disabled={loading}
            >
              批量删除
            </button>
          </div>
        </div>
      )}

      {/* Dictionary Table */}
      <div className="overflow-hidden rounded-lg bg-white shadow">
        {loading && (
          <div className="bg-opacity-75 absolute inset-0 z-10 flex items-center justify-center bg-white">
            <div className="h-8 w-8 animate-spin rounded-full border-b-2 border-blue-600"></div>
          </div>
        )}

        <table className="min-w-full divide-y divide-gray-200">
          <thead className="bg-gray-50">
            <tr>
              <th className="px-6 py-3 text-left">
                <input
                  type="checkbox"
                  checked={
                    selectedDicts.size === result.records.length && result.records.length > 0
                  }
                  onChange={handleSelectAll}
                  className="rounded border-gray-300 text-blue-600 focus:ring-blue-500"
                />
              </th>
              <th className="px-6 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase">
                字典编码
              </th>
              <th className="px-6 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase">
                字典名称
              </th>
              <th className="px-6 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase">
                类型
              </th>
              <th className="px-6 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase">
                状态
              </th>
              <th className="px-6 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase">
                版本
              </th>
              <th className="px-6 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase">
                更新时间
              </th>
              <th className="px-6 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase">
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
              result.records.map((dict: DictDTO) => (
                <tr key={dict.pid} className="hover:bg-gray-50">
                  <td className="px-6 py-4">
                    <input
                      type="checkbox"
                      checked={selectedDicts.has(dict.pid)}
                      onChange={() => handleSelectOne(dict.pid)}
                      className="rounded border-gray-300 text-blue-600 focus:ring-blue-500"
                    />
                  </td>
                  <td className="px-6 py-4 whitespace-nowrap">
                    <Link
                      to={`/meta/dict/${dict.pid}`}
                      data-testid={`dict-row-code-${dict.pid}`}
                      className="font-mono text-sm text-blue-600 hover:text-blue-900"
                    >
                      {dict.code}
                    </Link>
                  </td>
                  <td className="px-6 py-4 text-sm whitespace-nowrap text-gray-900">{dict.name}</td>
                  <td className="px-6 py-4 whitespace-nowrap">
                    <span
                      className={`inline-flex rounded-full px-2 py-1 text-xs font-medium ${getTypeBadgeColor(dict.dictType)}`}
                    >
                      {dict.dictType === 'simple' && '简单'}
                      {dict.dictType === 'tree' && '树形'}
                      {dict.dictType === 'cascade' && '级联'}
                    </span>
                  </td>
                  <td className="px-6 py-4 whitespace-nowrap">
                    <StatusBadge status={dict.status} />
                  </td>
                  <td className="px-6 py-4 text-sm whitespace-nowrap text-gray-500">
                    v{dict.version}
                  </td>
                  <td className="px-6 py-4 text-sm whitespace-nowrap text-gray-500">
                    {new Date(dict.updatedAt).toLocaleString()}
                  </td>
                  <td className="px-6 py-4 text-sm whitespace-nowrap">
                    <Link
                      to={`/meta/dict/${dict.pid}`}
                      data-testid={`dict-row-view-${dict.pid}`}
                      className="mr-3 text-blue-600 hover:text-blue-900"
                    >
                      查看
                    </Link>
                    <Link
                      to={`/meta/dict/${dict.pid}/edit`}
                      data-testid={`dict-row-edit-${dict.pid}`}
                      className="mr-3 text-green-600 hover:text-green-900"
                    >
                      编辑
                    </Link>
                    <button
                      type="button"
                      onClick={() => handleDelete(dict)}
                      data-testid={`dict-row-delete-${dict.pid}`}
                      className="text-red-600 hover:text-red-900"
                    >
                      删除
                    </button>
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
