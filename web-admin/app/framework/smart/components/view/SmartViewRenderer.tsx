/**
 * SmartViewRenderer Component
 *
 * Unified view renderer that dispatches to the appropriate view component
 * based on the SavedView's viewType field.
 * Includes optional data tools toolbar (export/import) and bulk action support.
 */

import React, { useState, useCallback } from 'react';
import { KanbanView } from './KanbanView';
import { CalendarView } from './CalendarView';
import { GalleryView } from './GalleryView';
import { GanttView } from './GanttView';
import { TreeView } from './TreeView';
import { TimelineView } from './TimelineView';
import { FormView } from './FormView';
import { BulkActionToolbar } from '~/framework/smart/components/bulk/BulkActionToolbar';
import { BulkEditModal } from '~/framework/smart/components/bulk/BulkEditModal';
import type { BulkEditField } from '~/framework/smart/components/bulk/BulkEditModal';
import { ExportButton } from '~/framework/smart/components/data-tools/ExportButton';
import { ImportModal } from '~/framework/smart/components/data-tools/ImportModal';
import type { SavedView, ViewType } from '~/framework/smart/types/savedView';
import type { KanbanCard, KanbanCardMoveEvent } from '~/framework/smart/types/kanban';
import type { FilterConfig } from '~/framework/smart/types/chart';
import { cn } from '~/utils/cn';

/**
 * Props for SmartViewRenderer component
 */
export interface SmartViewRendererProps {
  /** The saved view to render */
  view: SavedView;
  /** Render function for table view (delegated to parent since table rendering varies) */
  renderTableView?: () => React.ReactNode;
  /** Callback when a kanban card is clicked */
  onCardClick?: (card: KanbanCard) => void;
  /** Callback when a kanban card is moved */
  onCardMove?: (event: KanbanCardMoveEvent) => void;
  /** Callback when a calendar event (record) is clicked */
  onEventClick?: (recordId: string) => void;
  /** Callback when a calendar event is moved (date changed) */
  onEventMove?: (recordId: string, newStart: string, newEnd: string | null) => void;
  /** Callback when a gallery card (record) is clicked */
  onGalleryCardClick?: (recordId: string) => void;
  /** Callback when a gantt task (record) is clicked */
  onGanttTaskClick?: (recordId: string) => void;
  /** Callback when a tree node (record) is clicked */
  onTreeNodeClick?: (recordId: string) => void;
  /** Callback when a gantt task date is changed */
  onGanttTaskDateChange?: (recordId: string, start: string, end: string) => void;
  /** Callback when a gantt task progress is changed */
  onGanttTaskProgressChange?: (recordId: string, progress: number) => void;
  /** Callback to open current view settings */
  onOpenViewConfig?: () => void;
  /** Callback to switch to table view */
  onSwitchToTableView?: () => void;
  /** External filter conditions */
  linkageFilters?: FilterConfig[];
  /** Custom CSS class */
  className?: string;

  // --- Data tools & bulk operations (opt-in) ---
  /** Show data tools toolbar (export/import buttons) */
  showDataTools?: boolean;
  /** Selected record IDs for bulk operations */
  selectedKeys?: string[];
  /** Callback when selection is cleared */
  onClearSelection?: () => void;
  /** Callback for bulk delete */
  onBulkDelete?: (ids: string[]) => Promise<void>;
  /** Available fields for bulk edit */
  bulkEditFields?: BulkEditField[];
  /** Callback after data changes (refresh list) */
  onDataRefresh?: () => void;
}

/**
 * SmartViewRenderer - Dispatches rendering to the appropriate view component
 *
 * @example
 * <SmartViewRenderer
 *   view={currentView}
 *   renderTableView={() => <SmartTable ... />}
 *   onCardClick={(card) => navigate(`/detail/${card.id}`)}
 * />
 */
export const SmartViewRenderer: React.FC<SmartViewRendererProps> = ({
  view,
  renderTableView,
  onCardClick,
  onCardMove,
  onEventClick,
  onEventMove,
  onGalleryCardClick,
  onGanttTaskClick,
  onGanttTaskDateChange,
  onGanttTaskProgressChange,
  onOpenViewConfig,
  onSwitchToTableView,
  onTreeNodeClick,
  linkageFilters,
  className,
  // Data tools & bulk operations
  showDataTools,
  selectedKeys,
  onClearSelection,
  onBulkDelete,
  bulkEditFields,
  onDataRefresh,
}) => {
  const viewType: ViewType = (view.viewType as ViewType) || 'table';
  const [importOpen, setImportOpen] = useState(false);
  const [bulkEditOpen, setBulkEditOpen] = useState(false);

  const handleImportComplete = useCallback(() => {
    setImportOpen(false);
    onDataRefresh?.();
  }, [onDataRefresh]);

  const handleBulkEditComplete = useCallback(() => {
    setBulkEditOpen(false);
    onDataRefresh?.();
  }, [onDataRefresh]);

  const renderView = () => {
    switch (viewType) {
      case 'table':
        return <>{renderTableView?.()}</>;

      case 'kanban':
        return (
          <KanbanView
            viewConfig={view.viewConfig}
            modelCode={view.modelCode}
            onCardClick={onCardClick}
            onCardMove={onCardMove}
            linkageFilters={linkageFilters}
            className={className}
          />
        );

      case 'calendar':
        return (
          <CalendarView
            viewConfig={view.viewConfig}
            modelCode={view.modelCode}
            onEventClick={onEventClick}
            onEventMove={onEventMove}
            onOpenViewConfig={onOpenViewConfig}
            onSwitchToTableView={onSwitchToTableView}
            linkageFilters={linkageFilters}
            className={className}
          />
        );

      case 'gallery':
        return (
          <GalleryView
            viewConfig={view.viewConfig}
            modelCode={view.modelCode}
            onCardClick={onGalleryCardClick}
            onOpenViewConfig={onOpenViewConfig}
            onSwitchToTableView={onSwitchToTableView}
            linkageFilters={linkageFilters}
            className={className}
          />
        );

      case 'gantt':
        return (
          <GanttView
            viewConfig={view.viewConfig}
            modelCode={view.modelCode}
            onTaskClick={onGanttTaskClick}
            onTaskDateChange={onGanttTaskDateChange}
            onTaskProgressChange={onGanttTaskProgressChange}
            onOpenViewConfig={onOpenViewConfig}
            onSwitchToTableView={onSwitchToTableView}
            linkageFilters={linkageFilters}
            className={className}
          />
        );

      case 'tree':
        return (
          <TreeView
            viewConfig={view.viewConfig}
            modelCode={view.modelCode}
            onNodeClick={onTreeNodeClick}
            linkageFilters={linkageFilters}
            className={className}
          />
        );

      case 'timeline':
        return (
          <TimelineView
            viewConfig={view.viewConfig}
            modelCode={view.modelCode}
            onItemClick={onGanttTaskClick}
            onOpenViewConfig={onOpenViewConfig}
            onSwitchToTableView={onSwitchToTableView}
            linkageFilters={linkageFilters}
            className={className}
          />
        );

      case 'form':
        return (
          <FormView
            viewConfig={view.viewConfig}
            modelCode={view.modelCode}
            onOpenViewConfig={onOpenViewConfig}
            className={className}
          />
        );

      default:
        return (
          <div
            className={cn(
              'flex items-center justify-center rounded-lg border border-gray-200 bg-white p-8',
              className,
            )}
            style={{ minHeight: 300 }}
          >
            <div className="text-center text-gray-400">
              <div className="mb-1 text-lg">Unsupported view type</div>
              <div className="text-sm">{viewType} view is not yet available.</div>
            </div>
          </div>
        );
    }
  };

  return (
    <>
      {/* Data Tools Toolbar */}
      {showDataTools && (
        <div className="mb-3 flex items-center justify-end gap-2">
          <button
            type="button"
            onClick={() => setImportOpen(true)}
            className={cn(
              'inline-flex items-center gap-1.5 px-3 py-1.5 text-sm font-medium',
              'rounded-md border border-gray-300 bg-white text-gray-700 shadow-sm',
              'transition-colors duration-150 hover:bg-gray-50',
            )}
          >
            <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-8l-4-4m0 0L8 8m4-4v12"
              />
            </svg>
            Import
          </button>
          <ExportButton
            modelCode={view.modelCode}
            viewPid={view.pid}
            filters={linkageFilters?.map((f) => ({
              field: f.field,
              operator: f.operator,
              value: f.value,
            }))}
          />
        </div>
      )}

      {/* View Content */}
      {renderView()}

      {/* Bulk Action Toolbar (floating) */}
      {selectedKeys && selectedKeys.length > 0 && (
        <BulkActionToolbar
          selectedCount={selectedKeys.length}
          selectedIds={selectedKeys}
          modelCode={view.modelCode}
          onBulkEdit={bulkEditFields ? () => setBulkEditOpen(true) : undefined}
          onBulkDelete={onBulkDelete}
          onClearSelection={onClearSelection}
        />
      )}

      {/* Bulk Edit Modal */}
      {bulkEditFields && (
        <BulkEditModal
          open={bulkEditOpen}
          onClose={() => setBulkEditOpen(false)}
          modelCode={view.modelCode}
          selectedIds={selectedKeys ?? []}
          fields={bulkEditFields}
          onUpdateComplete={handleBulkEditComplete}
        />
      )}

      {/* Import Modal */}
      {showDataTools && (
        <ImportModal
          open={importOpen}
          onClose={() => setImportOpen(false)}
          modelCode={view.modelCode}
          onImportComplete={handleImportComplete}
        />
      )}
    </>
  );
};

export default SmartViewRenderer;
