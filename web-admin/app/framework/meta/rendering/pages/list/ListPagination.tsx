/**
 * ListPagination — Extracted pagination + bulk action toolbar from ListPageContent.
 *
 * Renders the Pagination component and BulkActionToolbar.
 * Behavior-preserving extraction — no functional changes.
 */

import { Pagination } from '~/components/Pagination';
import { BulkActionToolbar } from '~/smart/components/bulk/BulkActionToolbar';

export interface ListPaginationProps {
  current: number;
  pageSize: number;
  total: number;
  onPageChange: (page: number) => void;
  onPageSizeChange: (pageSize: number) => void;
  t: (key: string) => string;
  selectedCount: number;
  selectedIds: string[];
  modelCode: string;
  onBulkEdit: () => void;
  onBulkDelete: (ids: string[]) => Promise<void>;
  onClearSelection: () => void;
}

export function ListPagination({
  current,
  pageSize,
  total,
  onPageChange,
  onPageSizeChange,
  t,
  selectedCount,
  selectedIds,
  modelCode,
  onBulkEdit,
  onBulkDelete,
  onClearSelection,
}: ListPaginationProps) {
  return (
    <>
      {/* Pagination (hidden in print) */}
      <div className="print-hide" data-print="hide">
        <Pagination
          current={current}
          pageSize={pageSize}
          total={total}
          onChange={onPageChange}
          onPageSizeChange={onPageSizeChange}
          t={t}
        />
      </div>

      {/* Bulk Action Toolbar (hidden in print) */}
      <BulkActionToolbar
        selectedCount={selectedCount}
        selectedIds={selectedIds}
        modelCode={modelCode}
        onBulkEdit={onBulkEdit}
        onBulkDelete={onBulkDelete}
        onClearSelection={onClearSelection}
      />
    </>
  );
}
