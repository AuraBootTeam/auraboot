/**
 * Pagination Component - 通用分页组件
 *
 * 提取自动态列表页面，支持完整的分页功能
 *
 * 功能:
 * - 首页/末页/上一页/下一页导航
 * - 页码跳转
 * - 每页条数切换
 * - i18n 支持
 *
 * 变更记录:
 * - 2025-12-03: 创建 (修复 P2-7)
 *
 * @example
 * ```tsx
 * <Pagination
 *   current={1}
 *   pageSize={10}
 *   total={100}
 *   onChange={(page) => handlePageChange(page)}
 *   onPageSizeChange={(size) => handlePageSizeChange(size)}
 * />
 * ```
 */

import React from 'react';

export interface PaginationProps {
  /** 当前页码 (从 1 开始) */
  current: number;

  /** 每页条数 */
  pageSize: number;

  /** 总记录数 */
  total: number;

  /** 页码变化回调 */
  onChange: (page: number) => void;

  /** 每页条数变化回调 (可选) */
  onPageSizeChange?: (pageSize: number) => void;

  /** 可选的每页条数选项 */
  pageSizeOptions?: number[];

  /** 翻译函数 (可选) */
  t?: (key: string) => string;

  /** 是否显示快速跳转 */
  showQuickJumper?: boolean;

  /** 是否显示每页条数选择器 */
  showPageSizeSelector?: boolean;
}

export const Pagination: React.FC<PaginationProps> = ({
  current,
  pageSize,
  total,
  onChange,
  onPageSizeChange,
  pageSizeOptions = [10, 20, 50, 100],
  t = (key) => key,
  showQuickJumper: _showQuickJumper = true,
  showPageSizeSelector = true,
}) => {
  const totalPages = Math.ceil(total / pageSize);

  // 处理页码变化
  const handlePageChange = (newPage: number) => {
    if (newPage < 1 || newPage > totalPages || newPage === current) {
      return;
    }
    onChange(newPage);
  };

  // 处理每页条数变化
  const handlePageSizeChange = (newSize: number) => {
    if (onPageSizeChange) {
      onPageSizeChange(newSize);
    }
  };

  // 生成页码按钮
  const renderPageButtons = () => {
    const buttons: React.ReactNode[] = [];
    const maxButtons = 7; // 最多显示 7 个页码按钮

    if (totalPages <= maxButtons) {
      // 页数少，显示所有页码
      for (let i = 1; i <= totalPages; i++) {
        buttons.push(
          <button
            key={i}
            onClick={() => handlePageChange(i)}
            className={`rounded border px-3 py-1 ${
              i === current
                ? 'border-blue-600 bg-blue-600 text-white'
                : 'border-gray-300 bg-white text-gray-700 hover:bg-gray-50'
            }`}
          >
            {i}
          </button>,
        );
      }
    } else {
      // 页数多，智能显示
      const start = Math.max(1, current - 2);
      const end = Math.min(totalPages, current + 2);

      // 首页
      if (start > 1) {
        buttons.push(
          <button
            key={1}
            onClick={() => handlePageChange(1)}
            className="rounded border border-gray-300 bg-white px-3 py-1 text-gray-700 hover:bg-gray-50"
          >
            1
          </button>,
        );
        if (start > 2) {
          buttons.push(
            <span key="ellipsis-start" className="px-2 text-gray-500">
              ...
            </span>,
          );
        }
      }

      // 中间页码
      for (let i = start; i <= end; i++) {
        buttons.push(
          <button
            key={i}
            onClick={() => handlePageChange(i)}
            className={`rounded border px-3 py-1 ${
              i === current
                ? 'border-blue-600 bg-blue-600 text-white'
                : 'border-gray-300 bg-white text-gray-700 hover:bg-gray-50'
            }`}
          >
            {i}
          </button>,
        );
      }

      // 末页
      if (end < totalPages) {
        if (end < totalPages - 1) {
          buttons.push(
            <span key="ellipsis-end" className="px-2 text-gray-500">
              ...
            </span>,
          );
        }
        buttons.push(
          <button
            key={totalPages}
            onClick={() => handlePageChange(totalPages)}
            className="rounded border border-gray-300 bg-white px-3 py-1 text-gray-700 hover:bg-gray-50"
          >
            {totalPages}
          </button>,
        );
      }
    }

    return buttons;
  };

  if (totalPages <= 1) {
    return null; // 只有一页或没有数据时不显示分页
  }

  return (
    <div className="flex items-center justify-between border-t border-gray-200 px-6 py-4">
      {/* 左侧：统计信息 */}
      <div className="text-sm text-gray-700">
        {(() => {
          const pageText = t('common.page_of');
          return pageText && pageText !== 'common.page_of'
            ? pageText.replace('{current}', String(current)).replace('{total}', String(totalPages))
            : `第 ${current} 页，共 ${totalPages} 页`;
        })()}
        {' · '}
        {(() => {
          const itemsText = t('common.items_total');
          return itemsText && itemsText !== 'common.items_total'
            ? itemsText.replace('{total}', String(total))
            : `共 ${total} 条`;
        })()}
      </div>

      {/* 中间：页码按钮 */}
      <div className="flex items-center space-x-2">
        {/* 首页 */}
        <button
          data-testid="pagination-first"
          onClick={() => handlePageChange(1)}
          disabled={current === 1}
          className="rounded border border-gray-300 bg-white px-3 py-1 text-gray-700 hover:bg-gray-50 disabled:cursor-not-allowed disabled:opacity-50"
        >
          {(() => {
            const v = t('common.first_page');
            return v !== 'common.first_page' ? v : '首页';
          })()}
        </button>

        {/* 上一页 */}
        <button
          data-testid="pagination-prev"
          onClick={() => handlePageChange(current - 1)}
          disabled={current === 1}
          className="rounded border border-gray-300 bg-white px-3 py-1 text-gray-700 hover:bg-gray-50 disabled:cursor-not-allowed disabled:opacity-50"
        >
          {(() => {
            const v = t('common.previous_page');
            return v !== 'common.previous_page' ? v : '上一页';
          })()}
        </button>

        {/* 页码 */}
        {renderPageButtons()}

        {/* 下一页 */}
        <button
          data-testid="pagination-next"
          onClick={() => handlePageChange(current + 1)}
          disabled={current === totalPages}
          className="rounded border border-gray-300 bg-white px-3 py-1 text-gray-700 hover:bg-gray-50 disabled:cursor-not-allowed disabled:opacity-50"
        >
          {(() => {
            const v = t('common.next_page');
            return v !== 'common.next_page' ? v : '下一页';
          })()}
        </button>

        {/* 末页 */}
        <button
          data-testid="pagination-last"
          onClick={() => handlePageChange(totalPages)}
          disabled={current === totalPages}
          className="rounded border border-gray-300 bg-white px-3 py-1 text-gray-700 hover:bg-gray-50 disabled:cursor-not-allowed disabled:opacity-50"
        >
          {(() => {
            const v = t('common.last_page');
            return v !== 'common.last_page' ? v : '末页';
          })()}
        </button>
      </div>

      {/* 右侧：每页条数选择 */}
      {showPageSizeSelector && onPageSizeChange && (
        <div className="flex items-center space-x-2">
          <span className="text-sm text-gray-700">
            {(() => {
              const v = t('common.items_per_page');
              return v !== 'common.items_per_page' ? v : '每页';
            })()}
            :
          </span>
          <select
            data-testid="pagination-page-size"
            value={pageSize}
            onChange={(e) => handlePageSizeChange(Number(e.target.value))}
            className="rounded border border-gray-300 px-2 py-1 text-sm"
          >
            {pageSizeOptions.map((option) => (
              <option key={option} value={option}>
                {option}
              </option>
            ))}
          </select>
        </div>
      )}
    </div>
  );
};
