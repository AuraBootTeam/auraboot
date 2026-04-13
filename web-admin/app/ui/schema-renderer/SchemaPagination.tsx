/**
 * Schema Pagination Component
 *
 * Renders pagination controls for schema-driven data tables.
 */

import React from 'react';
import type { PaginationProps } from './types';

/**
 * Schema Pagination Component
 *
 * Displays pagination information and navigation buttons.
 */
export function SchemaPagination({ pagination, onPageChange }: PaginationProps) {
  if (!pagination || pagination.total === 0) {
    return null;
  }

  const totalPages = Math.ceil(pagination.total / pagination.pageSize);
  const { current: currentPage } = pagination;

  const isFirstPage = currentPage <= 1;
  const isLastPage = currentPage >= totalPages;

  return (
    <div className="flex items-center justify-between border-t border-gray-200 bg-white px-6 py-3">
      <div className="flex items-center text-sm text-gray-700">
        Total {pagination.total} records, Page {currentPage} / {totalPages}
      </div>
      <div className="flex space-x-1">
        <button
          onClick={() => onPageChange(currentPage - 1)}
          disabled={isFirstPage}
          className="rounded-md border border-gray-300 px-3 py-1 text-sm hover:bg-gray-50 disabled:cursor-not-allowed disabled:opacity-50"
        >
          Previous
        </button>
        <button
          onClick={() => onPageChange(currentPage + 1)}
          disabled={isLastPage}
          className="rounded-md border border-gray-300 px-3 py-1 text-sm hover:bg-gray-50 disabled:cursor-not-allowed disabled:opacity-50"
        >
          Next
        </button>
      </div>
    </div>
  );
}

export default SchemaPagination;
