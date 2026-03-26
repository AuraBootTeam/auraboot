/**
 * PageList Component
 *
 * Page list management center with filtering, sorting, and pagination.
 *
 * @since 3.2.0
 */

import React, { useState, useEffect, useCallback } from 'react';
import { PageCard } from './PageCard';
import type {
  PageMeta,
  PageListFilter,
  PageListSort,
  PageMode,
  PageStatus,
} from '../../../services/page-manager';
import {
  pageManagerService,
  PAGE_MODE_INFO,
  PAGE_STATUS_INFO,
} from '../../../services/page-manager';
import { confirmDialog } from '~/utils/confirmDialog';

/**
 * PageList props
 */
export interface PageListProps {
  /** Callback when user wants to create new page */
  onCreateNew?: () => void;
  /** Callback when user opens a page */
  onOpenPage?: (page: PageMeta) => void;
  /** Callback when user wants to import */
  onImport?: () => void;
}

/**
 * PageList component
 */
export const PageList: React.FC<PageListProps> = ({ onCreateNew, onOpenPage, onImport }) => {
  // State
  const [pages, setPages] = useState<PageMeta[]>([]);
  const [loading, setLoading] = useState(true);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [currentPage, setCurrentPage] = useState(1);
  const [totalPages, setTotalPages] = useState(1);
  const [total, setTotal] = useState(0);
  const pageSize = 12;

  // Filters
  const [query, setQuery] = useState('');
  const [statusFilter, setStatusFilter] = useState<PageStatus | 'all'>('all');
  const [modeFilter, setModeFilter] = useState<PageMode | 'all'>('all');
  const [sort, setSort] = useState<PageListSort>({
    field: 'updatedAt',
    direction: 'desc',
  });

  // View mode (grid/list)
  const [viewMode, setViewMode] = useState<'grid' | 'list'>('grid');

  // Batch mode
  const [batchMode, setBatchMode] = useState(false);
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());
  const [batchLoading, setBatchLoading] = useState(false);

  // Load pages
  const loadPages = useCallback(async () => {
    setLoading(true);
    try {
      // Ensure localStorage data is loaded on client side
      pageManagerService.ensureLoaded();

      const filter: PageListFilter = {
        query: query || undefined,
        status: statusFilter,
        mode: modeFilter,
      };
      const result = await pageManagerService.getPageList(filter, sort, currentPage, pageSize);
      setPages(result.items);
      setTotalPages(result.pagination.totalPages);
      setTotal(result.pagination.total);
    } catch (error) {
      console.error('Failed to load pages:', error);
    } finally {
      setLoading(false);
    }
  }, [query, statusFilter, modeFilter, sort, currentPage]);

  useEffect(() => {
    loadPages();
  }, [loadPages]);

  // Handlers
  const handlePageClick = (page: PageMeta) => {
    setSelectedId(page.id);
  };

  const handlePageDoubleClick = (page: PageMeta) => {
    onOpenPage?.(page);
  };

  const handleEdit = (page: PageMeta) => {
    onOpenPage?.(page);
  };

  const handleDuplicate = async (page: PageMeta) => {
    await pageManagerService.duplicatePage(page.id);
    loadPages();
  };

  const handleDelete = async (page: PageMeta) => {
    if (
      await confirmDialog({ content: `确定要删除页面 "${page.title}" 吗？`, variant: 'danger' })
    ) {
      await pageManagerService.deletePage(page.id);
      if (selectedId === page.id) {
        setSelectedId(null);
      }
      loadPages();
    }
  };

  const handleArchive = async (page: PageMeta) => {
    await pageManagerService.archivePage(page.id);
    loadPages();
  };

  const handlePublish = async (page: PageMeta) => {
    await pageManagerService.publishPage(page.id);
    loadPages();
  };

  // Batch mode handlers
  const toggleBatchMode = () => {
    setBatchMode(!batchMode);
    if (batchMode) {
      setSelectedIds(new Set());
    }
  };

  const handleCheckChange = (page: PageMeta, checked: boolean) => {
    setSelectedIds((prev) => {
      const next = new Set(prev);
      if (checked) {
        next.add(page.id);
      } else {
        next.delete(page.id);
      }
      return next;
    });
  };

  const handleSelectAll = () => {
    if (selectedIds.size === pages.length) {
      setSelectedIds(new Set());
    } else {
      setSelectedIds(new Set(pages.map((p) => p.id)));
    }
  };

  const handleBatchDelete = async () => {
    if (selectedIds.size === 0) return;
    const count = selectedIds.size;
    if (
      !(await confirmDialog({
        content: `确定要删除选中的 ${count} 个页面吗？此操作不可恢复。`,
        variant: 'danger',
      }))
    )
      return;

    setBatchLoading(true);
    try {
      for (const id of selectedIds) {
        await pageManagerService.deletePage(id);
      }
      setSelectedIds(new Set());
      setBatchMode(false);
      loadPages();
    } catch (error) {
      console.error('Batch delete failed:', error);
    } finally {
      setBatchLoading(false);
    }
  };

  const handleBatchPublish = async () => {
    if (selectedIds.size === 0) return;
    const count = selectedIds.size;
    if (!(await confirmDialog({ content: `确定要发布选中的 ${count} 个页面吗？` }))) return;

    setBatchLoading(true);
    try {
      for (const id of selectedIds) {
        await pageManagerService.publishPage(id);
      }
      setSelectedIds(new Set());
      setBatchMode(false);
      loadPages();
    } catch (error) {
      console.error('Batch publish failed:', error);
    } finally {
      setBatchLoading(false);
    }
  };

  const handleBatchArchive = async () => {
    if (selectedIds.size === 0) return;
    const count = selectedIds.size;
    if (!(await confirmDialog({ content: `确定要归档选中的 ${count} 个页面吗？` }))) return;

    setBatchLoading(true);
    try {
      for (const id of selectedIds) {
        await pageManagerService.archivePage(id);
      }
      setSelectedIds(new Set());
      setBatchMode(false);
      loadPages();
    } catch (error) {
      console.error('Batch archive failed:', error);
    } finally {
      setBatchLoading(false);
    }
  };

  const handleSearchKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter') {
      setCurrentPage(1);
      loadPages();
    }
  };

  const handleSortChange = (field: PageListSort['field']) => {
    setSort((prev) => ({
      field,
      direction: prev.field === field && prev.direction === 'desc' ? 'asc' : 'desc',
    }));
    setCurrentPage(1);
  };

  // Empty state
  const isEmpty =
    pages.length === 0 && !loading && !query && statusFilter === 'all' && modeFilter === 'all';

  return (
    <div className="min-h-screen bg-gray-50">
      {/* Header */}
      <div className="sticky top-0 z-10 border-b border-gray-200 bg-white">
        <div className="mx-auto max-w-7xl px-4 sm:px-6 lg:px-8">
          <div className="flex h-16 items-center justify-between">
            <div className="flex items-center gap-4">
              <h1 className="text-xl font-semibold text-gray-900">页面管理</h1>
              <span className="text-sm text-gray-500">{total} 个页面</span>
            </div>
            <div className="flex items-center gap-3">
              <button
                onClick={onImport}
                className="flex items-center gap-2 rounded-lg px-3 py-2 text-sm text-gray-700 hover:bg-gray-100"
              >
                <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={2}
                    d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-8l-4-4m0 0L8 8m4-4v12"
                  />
                </svg>
                导入
              </button>
              <button
                onClick={onCreateNew}
                className="flex items-center gap-2 rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700"
              >
                <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={2}
                    d="M12 4v16m8-8H4"
                  />
                </svg>
                新建页面
              </button>
            </div>
          </div>
        </div>
      </div>

      {/* Toolbar */}
      <div className="border-b border-gray-200 bg-white">
        <div className="mx-auto max-w-7xl px-4 py-3 sm:px-6 lg:px-8">
          <div className="flex items-center justify-between gap-4">
            {/* Search */}
            <div className="max-w-md flex-1">
              <div className="relative">
                <svg
                  className="absolute top-1/2 left-3 h-4 w-4 -translate-y-1/2 text-gray-400"
                  fill="none"
                  viewBox="0 0 24 24"
                  stroke="currentColor"
                >
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={2}
                    d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z"
                  />
                </svg>
                <input
                  type="text"
                  placeholder="搜索页面..."
                  value={query}
                  onChange={(e) => setQuery(e.target.value)}
                  onKeyDown={handleSearchKeyDown}
                  className="w-full rounded-lg border border-gray-300 py-2 pr-4 pl-10 text-sm focus:border-transparent focus:ring-2 focus:ring-blue-500 focus:outline-none"
                />
              </div>
            </div>

            {/* Filters */}
            <div className="flex items-center gap-3">
              {/* Status filter */}
              <select
                value={statusFilter}
                onChange={(e) => {
                  setStatusFilter(e.target.value as PageStatus | 'all');
                  setCurrentPage(1);
                }}
                className="rounded-lg border border-gray-300 px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
              >
                <option value="all">全部状态</option>
                {Object.entries(PAGE_STATUS_INFO).map(([key, info]) => (
                  <option key={key} value={key}>
                    {info.label}
                  </option>
                ))}
              </select>

              {/* Mode filter */}
              <select
                value={modeFilter}
                onChange={(e) => {
                  setModeFilter(e.target.value as PageMode | 'all');
                  setCurrentPage(1);
                }}
                className="rounded-lg border border-gray-300 px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none"
              >
                <option value="all">全部类型</option>
                {Object.entries(PAGE_MODE_INFO).map(([key, info]) => (
                  <option key={key} value={key}>
                    {info.label}
                  </option>
                ))}
              </select>

              {/* Sort */}
              <div className="flex items-center gap-1 overflow-hidden rounded-lg border border-gray-300">
                {(['updatedAt', 'createdAt', 'title'] as const).map((field) => (
                  <button
                    key={field}
                    onClick={() => handleSortChange(field)}
                    className={`px-3 py-2 text-sm ${
                      sort.field === field
                        ? 'bg-blue-50 text-blue-600'
                        : 'text-gray-600 hover:bg-gray-50'
                    }`}
                  >
                    {field === 'updatedAt' && '最近更新'}
                    {field === 'createdAt' && '创建时间'}
                    {field === 'title' && '名称'}
                    {sort.field === field && (
                      <span className="ml-1">{sort.direction === 'desc' ? '↓' : '↑'}</span>
                    )}
                  </button>
                ))}
              </div>

              {/* View mode toggle */}
              <div className="flex items-center overflow-hidden rounded-lg border border-gray-300">
                <button
                  onClick={() => setViewMode('grid')}
                  className={`p-2 ${viewMode === 'grid' ? 'bg-blue-50 text-blue-600' : 'text-gray-600 hover:bg-gray-50'}`}
                >
                  <svg className="h-4 w-4" fill="currentColor" viewBox="0 0 20 20">
                    <path d="M5 3a2 2 0 00-2 2v2a2 2 0 002 2h2a2 2 0 002-2V5a2 2 0 00-2-2H5zM5 11a2 2 0 00-2 2v2a2 2 0 002 2h2a2 2 0 002-2v-2a2 2 0 00-2-2H5zM11 5a2 2 0 012-2h2a2 2 0 012 2v2a2 2 0 01-2 2h-2a2 2 0 01-2-2V5zM11 13a2 2 0 012-2h2a2 2 0 012 2v2a2 2 0 01-2 2h-2a2 2 0 01-2-2v-2z" />
                  </svg>
                </button>
                <button
                  onClick={() => setViewMode('list')}
                  className={`p-2 ${viewMode === 'list' ? 'bg-blue-50 text-blue-600' : 'text-gray-600 hover:bg-gray-50'}`}
                >
                  <svg className="h-4 w-4" fill="currentColor" viewBox="0 0 20 20">
                    <path
                      fillRule="evenodd"
                      d="M3 4a1 1 0 011-1h12a1 1 0 110 2H4a1 1 0 01-1-1zm0 4a1 1 0 011-1h12a1 1 0 110 2H4a1 1 0 01-1-1zm0 4a1 1 0 011-1h12a1 1 0 110 2H4a1 1 0 01-1-1zm0 4a1 1 0 011-1h12a1 1 0 110 2H4a1 1 0 01-1-1z"
                      clipRule="evenodd"
                    />
                  </svg>
                </button>
              </div>

              {/* Batch mode toggle */}
              <button
                onClick={toggleBatchMode}
                className={`flex items-center gap-2 rounded-lg px-3 py-2 text-sm ${
                  batchMode
                    ? 'bg-blue-600 text-white'
                    : 'border border-gray-300 text-gray-600 hover:bg-gray-100'
                }`}
              >
                <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={2}
                    d="M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2"
                  />
                </svg>
                {batchMode ? '退出批量' : '批量操作'}
              </button>
            </div>
          </div>
        </div>
      </div>

      {/* Batch Action Bar */}
      {batchMode && (
        <div className="border-b border-blue-200 bg-blue-50">
          <div className="mx-auto max-w-7xl px-4 py-2 sm:px-6 lg:px-8">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-4">
                <label className="flex cursor-pointer items-center gap-2">
                  <input
                    type="checkbox"
                    checked={pages.length > 0 && selectedIds.size === pages.length}
                    onChange={handleSelectAll}
                    className="h-4 w-4 rounded border-gray-300 text-blue-600"
                  />
                  <span className="text-sm text-gray-700">全选</span>
                </label>
                <span className="text-sm font-medium text-blue-600">
                  已选择 {selectedIds.size} 项
                </span>
              </div>
              <div className="flex items-center gap-2">
                <button
                  onClick={handleBatchPublish}
                  disabled={selectedIds.size === 0 || batchLoading}
                  className="flex items-center gap-1.5 rounded-lg bg-green-100 px-3 py-1.5 text-sm text-green-700 hover:bg-green-200 disabled:cursor-not-allowed disabled:opacity-50"
                >
                  <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                    <path
                      strokeLinecap="round"
                      strokeLinejoin="round"
                      strokeWidth={2}
                      d="M7 16a4 4 0 01-.88-7.903A5 5 0 1115.9 6L16 6a5 5 0 011 9.9M15 13l-3-3m0 0l-3 3m3-3v12"
                    />
                  </svg>
                  批量发布
                </button>
                <button
                  onClick={handleBatchArchive}
                  disabled={selectedIds.size === 0 || batchLoading}
                  className="flex items-center gap-1.5 rounded-lg bg-yellow-100 px-3 py-1.5 text-sm text-yellow-700 hover:bg-yellow-200 disabled:cursor-not-allowed disabled:opacity-50"
                >
                  <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                    <path
                      strokeLinecap="round"
                      strokeLinejoin="round"
                      strokeWidth={2}
                      d="M5 8h14M5 8a2 2 0 110-4h14a2 2 0 110 4M5 8v10a2 2 0 002 2h10a2 2 0 002-2V8m-9 4h4"
                    />
                  </svg>
                  批量归档
                </button>
                <button
                  onClick={handleBatchDelete}
                  disabled={selectedIds.size === 0 || batchLoading}
                  className="flex items-center gap-1.5 rounded-lg bg-red-100 px-3 py-1.5 text-sm text-red-700 hover:bg-red-200 disabled:cursor-not-allowed disabled:opacity-50"
                >
                  {batchLoading ? (
                    <div className="h-4 w-4 animate-spin rounded-full border-2 border-red-400 border-t-transparent" />
                  ) : (
                    <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                      <path
                        strokeLinecap="round"
                        strokeLinejoin="round"
                        strokeWidth={2}
                        d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16"
                      />
                    </svg>
                  )}
                  批量删除
                </button>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* Content */}
      <div className="mx-auto max-w-7xl px-4 py-6 sm:px-6 lg:px-8">
        {loading ? (
          // Loading state
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
            {Array.from({ length: 8 }).map((_, i) => (
              <div
                key={i}
                className="animate-pulse overflow-hidden rounded-lg border border-gray-200 bg-white"
              >
                <div className="h-36 bg-gray-200" />
                <div className="space-y-2 p-3">
                  <div className="h-4 w-3/4 rounded bg-gray-200" />
                  <div className="h-3 w-1/2 rounded bg-gray-200" />
                </div>
              </div>
            ))}
          </div>
        ) : isEmpty ? (
          // Empty state
          <div className="flex flex-col items-center justify-center py-20">
            <div className="mb-6 flex h-24 w-24 items-center justify-center rounded-full bg-gray-100">
              <svg
                className="h-12 w-12 text-gray-400"
                fill="none"
                viewBox="0 0 24 24"
                stroke="currentColor"
              >
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={1.5}
                  d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z"
                />
              </svg>
            </div>
            <h2 className="mb-2 text-xl font-medium text-gray-900">还没有页面</h2>
            <p className="mb-6 max-w-md text-center text-gray-500">
              创建你的第一个页面，开始设计精美的用户界面
            </p>
            <div className="flex items-center gap-3">
              <button
                onClick={onCreateNew}
                className="flex items-center gap-2 rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700"
              >
                <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={2}
                    d="M12 4v16m8-8H4"
                  />
                </svg>
                新建页面
              </button>
              <button
                onClick={onImport}
                className="flex items-center gap-2 rounded-lg border border-gray-300 bg-white px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50"
              >
                <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={2}
                    d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-8l-4-4m0 0L8 8m4-4v12"
                  />
                </svg>
                导入页面
              </button>
            </div>
          </div>
        ) : pages.length === 0 ? (
          // No results state
          <div className="flex flex-col items-center justify-center py-20">
            <div className="mb-4 flex h-16 w-16 items-center justify-center rounded-full bg-gray-100">
              <svg
                className="h-8 w-8 text-gray-400"
                fill="none"
                viewBox="0 0 24 24"
                stroke="currentColor"
              >
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z"
                />
              </svg>
            </div>
            <h3 className="mb-1 text-lg font-medium text-gray-900">没有找到匹配的页面</h3>
            <p className="text-gray-500">尝试调整搜索条件或筛选器</p>
          </div>
        ) : viewMode === 'grid' ? (
          // Grid view
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
            {pages.map((page) => (
              <PageCard
                key={page.id}
                page={page}
                isSelected={selectedId === page.id}
                onClick={handlePageClick}
                onDoubleClick={handlePageDoubleClick}
                onEdit={handleEdit}
                onDuplicate={handleDuplicate}
                onDelete={handleDelete}
                onArchive={handleArchive}
                onPublish={handlePublish}
                batchMode={batchMode}
                isChecked={selectedIds.has(page.id)}
                onCheckChange={handleCheckChange}
              />
            ))}
          </div>
        ) : (
          // List view
          <div className="overflow-hidden rounded-lg border border-gray-200 bg-white">
            <table className="min-w-full divide-y divide-gray-200">
              <thead className="bg-gray-50">
                <tr>
                  {batchMode && (
                    <th className="w-10 px-4 py-3">
                      <input
                        type="checkbox"
                        checked={pages.length > 0 && selectedIds.size === pages.length}
                        onChange={handleSelectAll}
                        className="h-4 w-4 rounded border-gray-300 text-blue-600"
                      />
                    </th>
                  )}
                  <th className="px-4 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase">
                    页面
                  </th>
                  <th className="px-4 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase">
                    类型
                  </th>
                  <th className="px-4 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase">
                    状态
                  </th>
                  <th className="px-4 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase">
                    更新时间
                  </th>
                  <th className="px-4 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase">
                    版本
                  </th>
                  <th className="px-4 py-3 text-right text-xs font-medium tracking-wider text-gray-500 uppercase">
                    操作
                  </th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-200 bg-white">
                {pages.map((page) => {
                  const modeInfo = PAGE_MODE_INFO[page.mode];
                  const statusInfo = PAGE_STATUS_INFO[page.status];
                  return (
                    <tr
                      key={page.id}
                      className={`cursor-pointer hover:bg-gray-50 ${selectedId === page.id ? 'bg-blue-50' : ''} ${batchMode && selectedIds.has(page.id) ? 'bg-blue-50' : ''}`}
                      onClick={() =>
                        batchMode
                          ? handleCheckChange(page, !selectedIds.has(page.id))
                          : handlePageClick(page)
                      }
                      onDoubleClick={batchMode ? undefined : () => handlePageDoubleClick(page)}
                    >
                      {batchMode && (
                        <td className="w-10 px-4 py-3">
                          <input
                            type="checkbox"
                            checked={selectedIds.has(page.id)}
                            onChange={(e) => {
                              e.stopPropagation();
                              handleCheckChange(page, e.target.checked);
                            }}
                            onClick={(e) => e.stopPropagation()}
                            className="h-4 w-4 rounded border-gray-300 text-blue-600"
                          />
                        </td>
                      )}
                      <td className="px-4 py-3">
                        <div className="flex items-center gap-3">
                          <div className="flex h-10 w-10 flex-shrink-0 items-center justify-center rounded bg-gray-100">
                            <svg
                              className="h-5 w-5 text-gray-400"
                              fill="none"
                              viewBox="0 0 24 24"
                              stroke="currentColor"
                            >
                              <path
                                strokeLinecap="round"
                                strokeLinejoin="round"
                                strokeWidth={1.5}
                                d={modeInfo.icon}
                              />
                            </svg>
                          </div>
                          <div className="min-w-0">
                            <div className="truncate font-medium text-gray-900">{page.title}</div>
                            {page.description && (
                              <div className="truncate text-sm text-gray-500">
                                {page.description}
                              </div>
                            )}
                          </div>
                        </div>
                      </td>
                      <td className="px-4 py-3 text-sm text-gray-600">{modeInfo.label}</td>
                      <td className="px-4 py-3">
                        <span
                          className={`inline-flex rounded px-2 py-0.5 text-xs font-medium ${statusInfo.color} ${statusInfo.bgColor}`}
                        >
                          {statusInfo.label}
                        </span>
                      </td>
                      <td className="px-4 py-3 text-sm text-gray-500">
                        {new Date(page.updatedAt).toLocaleDateString('zh-CN')}
                      </td>
                      <td className="px-4 py-3 text-sm text-gray-500">v{page.version}</td>
                      <td className="px-4 py-3 text-right">
                        <div className="flex items-center justify-end gap-1">
                          <button
                            onClick={(e) => {
                              e.stopPropagation();
                              handleEdit(page);
                            }}
                            className="rounded p-1.5 text-gray-400 hover:bg-blue-50 hover:text-blue-600"
                            title="编辑"
                          >
                            <svg
                              className="h-4 w-4"
                              fill="none"
                              viewBox="0 0 24 24"
                              stroke="currentColor"
                            >
                              <path
                                strokeLinecap="round"
                                strokeLinejoin="round"
                                strokeWidth={2}
                                d="M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5m-1.414-9.414a2 2 0 112.828 2.828L11.828 15H9v-2.828l8.586-8.586z"
                              />
                            </svg>
                          </button>
                          <button
                            onClick={(e) => {
                              e.stopPropagation();
                              handleDuplicate(page);
                            }}
                            className="rounded p-1.5 text-gray-400 hover:bg-blue-50 hover:text-blue-600"
                            title="复制"
                          >
                            <svg
                              className="h-4 w-4"
                              fill="none"
                              viewBox="0 0 24 24"
                              stroke="currentColor"
                            >
                              <path
                                strokeLinecap="round"
                                strokeLinejoin="round"
                                strokeWidth={2}
                                d="M8 16H6a2 2 0 01-2-2V6a2 2 0 012-2h8a2 2 0 012 2v2m-6 12h8a2 2 0 002-2v-8a2 2 0 00-2-2h-8a2 2 0 00-2 2v8a2 2 0 002 2z"
                              />
                            </svg>
                          </button>
                          <button
                            onClick={(e) => {
                              e.stopPropagation();
                              handleDelete(page);
                            }}
                            className="rounded p-1.5 text-gray-400 hover:bg-red-50 hover:text-red-600"
                            title="删除"
                          >
                            <svg
                              className="h-4 w-4"
                              fill="none"
                              viewBox="0 0 24 24"
                              stroke="currentColor"
                            >
                              <path
                                strokeLinecap="round"
                                strokeLinejoin="round"
                                strokeWidth={2}
                                d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16"
                              />
                            </svg>
                          </button>
                        </div>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}

        {/* Pagination */}
        {totalPages > 1 && (
          <div className="mt-6 flex items-center justify-between">
            <div className="text-sm text-gray-500">
              显示 {(currentPage - 1) * pageSize + 1} - {Math.min(currentPage * pageSize, total)} 共{' '}
              {total} 条
            </div>
            <div className="flex items-center gap-2">
              <button
                onClick={() => setCurrentPage((p) => Math.max(1, p - 1))}
                disabled={currentPage === 1}
                className="rounded-lg border border-gray-300 px-3 py-1.5 text-sm hover:bg-gray-50 disabled:cursor-not-allowed disabled:opacity-50"
              >
                上一页
              </button>
              <div className="flex items-center gap-1">
                {Array.from({ length: totalPages }, (_, i) => i + 1)
                  .filter((p) => p === 1 || p === totalPages || Math.abs(p - currentPage) <= 1)
                  .map((p, i, arr) => (
                    <React.Fragment key={p}>
                      {i > 0 && arr[i - 1] !== p - 1 && (
                        <span className="px-1 text-gray-400">...</span>
                      )}
                      <button
                        onClick={() => setCurrentPage(p)}
                        className={`h-8 w-8 rounded-lg text-sm ${
                          p === currentPage
                            ? 'bg-blue-600 text-white'
                            : 'text-gray-700 hover:bg-gray-100'
                        }`}
                      >
                        {p}
                      </button>
                    </React.Fragment>
                  ))}
              </div>
              <button
                onClick={() => setCurrentPage((p) => Math.min(totalPages, p + 1))}
                disabled={currentPage === totalPages}
                className="rounded-lg border border-gray-300 px-3 py-1.5 text-sm hover:bg-gray-50 disabled:cursor-not-allowed disabled:opacity-50"
              >
                下一页
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
};

export default PageList;
