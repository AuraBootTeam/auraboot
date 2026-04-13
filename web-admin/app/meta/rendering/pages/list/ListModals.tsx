/**
 * ListModals — Extracted all modals/drawers/floating panels from ListPageContent.
 *
 * Contains: BulkEditModal, ImportModal, FormDialog, ViewManagePanel,
 * ColumnSettingsPanel, FilterFieldPicker, FilterValuePopover,
 * ColumnContextMenu, RecordPreviewDrawer.
 * Behavior-preserving extraction — no functional changes.
 */

import type { ColumnConfig } from '~/meta/schemas/types';
import { ImportModal } from '~/smart/components/data-tools/ImportModal';
import FormDialog from '~/meta/runtime/actions/FormDialog';
import { ViewManagePanel } from '~/smart/components/view/ViewManagePanel';
import { ColumnSettingsPanel } from '~/smart/components/view/ColumnSettingsPanel';
import { FilterFieldPicker } from '~/smart/components/view/FilterFieldPicker';
import { FilterValuePopover } from '~/smart/components/view/FilterValuePopover';
import { BulkEditModal } from '~/smart/components/bulk/BulkEditModal';
import { RecordPreviewDrawer } from '~/smart/components/preview/RecordPreviewDrawer';
import { ColumnContextMenu } from './ColumnContextMenu';
import type {
  SavedViewCreateRequest,
  ColumnConfig as ViewColumnConfig,
  ViewFilterConfig,
  ViewType,
  ViewScope,
  SortConfig,
} from '~/smart/types/savedView';

export interface ListModalsProps {
  // BulkEditModal
  bulkEditOpen: boolean;
  onBulkEditClose: () => void;
  selectedIds: string[];
  modelCode: string;
  bulkEditFields: Array<{ code: string; name: string; dataType: string }>;
  onBulkEditComplete: () => void;

  // ImportModal
  importOpen: boolean;
  onImportClose: () => void;
  onImportComplete: () => void;

  // ViewManagePanel
  viewManageOpen: boolean;
  onViewManageClose: () => void;
  savedViews: any[];
  currentView: any;
  onCreateView: (req: SavedViewCreateRequest) => Promise<any>;
  onCreateViewSuccess: (view: any) => void;
  onDeleteView: (pid: string) => Promise<void>;
  onDuplicateView: (pid: string, name: string) => Promise<void>;
  onEditView?: (pid: string, name: string, description: string, scope: ViewScope) => Promise<void>;
  onSetDefaultView: (pid: string) => Promise<void>;
  onSelectView: (pid: string) => void;
  pageKey: string;
  activeViewType: ViewType;
  startInCreateMode: boolean;
  modelPid?: string;
  onFieldsCreated: () => void;
  onViewConfigSaved?: () => void;
  viewManageFields?: Array<{ code: string; name: string; dataType: string }>;

  // ColumnSettingsPanel
  columnSettingsOpen: boolean;
  onColumnSettingsClose: () => void;
  allColumnDefs: Array<{ field: string; label: string }>;
  viewColumns?: ViewColumnConfig[];
  onColumnSettingsSave: (columns: ViewColumnConfig[]) => Promise<void>;
  t: (key: string) => string;

  // FilterFieldPicker
  fieldPickerOpen: boolean;
  fieldPickerAnchor?: { x: number; y: number };
  fieldPickerFields: Array<{
    fieldCode: string;
    label: string;
    fieldType: string;
    dictCode?: string;
  }>;
  chipFilterFieldCodes: string[];
  onFieldPickerSelect: (fieldCode: string) => void;
  onFieldPickerClose: () => void;

  // FilterValuePopover
  editingChipIdx: number | null;
  chipFilters: ViewFilterConfig[];
  valuePopoverAnchor?: { x: number; y: number };
  tableColumns: ColumnConfig[];
  schema: any;
  tableName: string;
  onFilterApply: (operator: string, value: string) => void;
  onFilterCancel: () => void;

  // ColumnContextMenu
  contextMenu: { x: number; y: number; column: ColumnConfig } | null;
  activeSorts: SortConfig[];
  onSort: (dir: 'asc' | 'desc' | 'clear') => void;
  onFreeze: (pos: 'left' | 'right' | 'none') => void;
  onHide: () => void;
  onFilterByColumn: () => void;
  onGroupBy: () => void;
  onContextMenuClose: () => void;

  // RecordPreviewDrawer
  previewRecordId: string | null;
  previewApiEndpoint?: string;
  /** Custom detail page key from extension.relatedPages.detail; overrides the default {modelCode}_detail convention */
  previewDetailPageKey?: string;
  onPreviewClose: () => void;
}

export function ListModals({
  // BulkEditModal
  bulkEditOpen,
  onBulkEditClose,
  selectedIds,
  modelCode,
  bulkEditFields,
  onBulkEditComplete,

  // ImportModal
  importOpen,
  onImportClose,
  onImportComplete,

  // ViewManagePanel
  viewManageOpen,
  onViewManageClose,
  savedViews,
  currentView,
  onCreateView,
  onCreateViewSuccess,
  onDeleteView,
  onDuplicateView,
  onEditView,
  onSetDefaultView,
  onSelectView,
  pageKey,
  activeViewType,
  startInCreateMode,
  modelPid,
  onFieldsCreated,
  onViewConfigSaved,
  viewManageFields,

  // ColumnSettingsPanel
  columnSettingsOpen,
  onColumnSettingsClose,
  allColumnDefs,
  viewColumns,
  onColumnSettingsSave,
  t,

  // FilterFieldPicker
  fieldPickerOpen,
  fieldPickerAnchor,
  fieldPickerFields,
  chipFilterFieldCodes,
  onFieldPickerSelect,
  onFieldPickerClose,

  // FilterValuePopover
  editingChipIdx,
  chipFilters,
  valuePopoverAnchor,
  tableColumns,
  schema,
  tableName,
  onFilterApply,
  onFilterCancel,

  // ColumnContextMenu
  contextMenu,
  activeSorts,
  onSort,
  onFreeze,
  onHide,
  onFilterByColumn,
  onGroupBy,
  onContextMenuClose,

  // RecordPreviewDrawer
  previewRecordId,
  previewApiEndpoint,
  previewDetailPageKey,
  onPreviewClose,
}: ListModalsProps) {
  return (
    <>
      {/* Bulk Edit Modal */}
      {bulkEditOpen && (
        <BulkEditModal
          open={bulkEditOpen}
          onClose={onBulkEditClose}
          selectedIds={selectedIds}
          modelCode={modelCode}
          fields={bulkEditFields}
          onUpdateComplete={onBulkEditComplete}
        />
      )}

      <ImportModal
        open={importOpen}
        onClose={onImportClose}
        modelCode={modelCode}
        onImportComplete={onImportComplete}
      />

      <FormDialog />

      <ViewManagePanel
        open={viewManageOpen}
        onClose={onViewManageClose}
        views={savedViews}
        currentView={currentView}
        onCreateView={async (req: SavedViewCreateRequest) => onCreateView(req)}
        onCreateViewSuccess={onCreateViewSuccess}
        onDeleteView={async (pid: string) => {
          await onDeleteView(pid);
        }}
        onDuplicateView={async (pid: string, name: string) => {
          await onDuplicateView(pid, name);
        }}
        onEditView={onEditView}
        onSetDefaultView={async (pid: string) => {
          await onSetDefaultView(pid);
        }}
        onSelectView={onSelectView}
        modelCode={modelCode}
        pageKey={pageKey}
        activeViewType={activeViewType}
        startInCreateMode={startInCreateMode}
        modelPid={modelPid}
        onFieldsCreated={onFieldsCreated}
        onViewConfigSaved={onViewConfigSaved}
        fields={viewManageFields}
      />

      <ColumnSettingsPanel
        open={columnSettingsOpen}
        onClose={onColumnSettingsClose}
        allColumns={allColumnDefs}
        viewColumns={viewColumns}
        onSave={onColumnSettingsSave}
        t={t}
      />

      {/* Filter Field Picker */}
      <FilterFieldPicker
        open={fieldPickerOpen}
        anchorEl={fieldPickerAnchor}
        fields={fieldPickerFields}
        activeFieldCodes={chipFilterFieldCodes}
        onSelect={onFieldPickerSelect}
        onClose={onFieldPickerClose}
      />

      {/* Filter Value Popover — edit operator + value of a chip */}
      {editingChipIdx !== null &&
        chipFilters[editingChipIdx] &&
        (() => {
          const cf = chipFilters[editingChipIdx];
          const fieldMeta = tableColumns.find(
            (c: ColumnConfig) => c.field === cf.fieldCode,
          ) as any;
          return (
            <FilterValuePopover
              open
              anchorEl={valuePopoverAnchor}
              fieldCode={cf.fieldCode}
              fieldLabel={
                fieldMeta?.label
                  ? typeof fieldMeta.label === 'string'
                    ? fieldMeta.label
                    : fieldMeta.label?.['zh-CN'] || cf.fieldCode
                  : (() => {
                      const mc = (schema?.modelCode || tableName);
                      const key = `model.${mc}.${cf.fieldCode}.label`;
                      const resolved = t(key);
                      return resolved !== key ? resolved : cf.fieldCode;
                    })()
              }
              fieldType={fieldMeta?.valueType || fieldMeta?.sorter || 'text'}
              dictCode={fieldMeta?.dictCode}
              operator={cf.operator}
              value={cf.value}
              onApply={(operator, value) => onFilterApply(String(operator), String(value))}
              onCancel={onFilterCancel}
            />
          );
        })()}

      {/* Column Context Menu */}
      {contextMenu && (
        <ColumnContextMenu
          x={contextMenu.x}
          y={contextMenu.y}
          column={contextMenu.column}
          currentSortDir={
            activeSorts.find((s) => s.fieldCode === contextMenu.column.field)?.direction
          }
          onSort={onSort}
          onFreeze={onFreeze}
          onHide={onHide}
          onFilterByColumn={onFilterByColumn}
          onGroupBy={onGroupBy}
          onClose={onContextMenuClose}
        />
      )}

      <RecordPreviewDrawer
        open={!!previewRecordId}
        modelCode={modelCode}
        recordId={previewRecordId || ''}
        apiEndpoint={previewApiEndpoint}
        detailPageKey={previewDetailPageKey}
        onClose={onPreviewClose}
      />
    </>
  );
}
