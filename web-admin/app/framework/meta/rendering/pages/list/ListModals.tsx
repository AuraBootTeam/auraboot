/**
 * ListModals — Extracted all modals/drawers/floating panels from ListPageContent.
 *
 * Contains: BulkEditModal, ImportModal, FormDialog, ViewManagePanel,
 * ColumnSettingsPanel, FilterFieldPicker, FilterValuePopover,
 * ColumnContextMenu, RecordPreviewDrawer.
 * Behavior-preserving extraction — no functional changes.
 */

import type { ColumnConfig, FieldConfig } from '~/framework/meta/schemas/types';
import type { ExpressionContext } from '~/framework/meta/runtime/expression/context';
import {
  ImportModal,
  type ImportConfiguration,
} from '~/framework/smart/components/data-tools/ImportModal';
import FormDialog from '~/framework/meta/runtime/actions/FormDialog';
import { ViewManagePanel } from '~/framework/smart/components/view/ViewManagePanel';
import {
  ColumnSettingsPanel,
  type ColumnSettingsDefinition,
  type ColumnSettingsSavePayload,
} from '~/framework/smart/components/view/ColumnSettingsPanel';
import { FilterFieldPicker } from '~/framework/smart/components/view/FilterFieldPicker';
import { FilterValuePopover } from '~/framework/smart/components/view/FilterValuePopover';
import { BulkEditModal } from '~/framework/smart/components/bulk/BulkEditModal';
import { BulkFieldCommandModal } from '~/framework/smart/components/bulk/BulkFieldCommandModal';
import { RecordPreviewDrawer } from '~/framework/smart/components/preview/RecordPreviewDrawer';
import { ColumnContextMenu } from './ColumnContextMenu';
import type { ListFilterFieldMetadata } from '../ListPageContent';
import type {
  SavedViewCreateRequest,
  ColumnConfig as ViewColumnConfig,
  ViewFilterConfig,
  ViewType,
  ViewScope,
  SortConfig,
} from '~/framework/smart/types/savedView';

export interface ListModalsProps {
  // BulkEditModal
  bulkEditOpen: boolean;
  onBulkEditClose: () => void;
  selectedIds: string[];
  modelCode: string;
  bulkEditFields: Array<{ code: string; name: string; dataType: string }>;
  onBulkEditComplete: () => void;
  locale: string;

  // BulkFieldCommandModal
  bulkFieldCommand: {
    actionLabel: string;
    selectedCount: number;
    field: FieldConfig;
  } | null;
  bulkFieldCommandContext: ExpressionContext;
  onBulkFieldCommandClose: () => void;
  onBulkFieldCommandSubmit: (value: unknown) => Promise<void>;

  // ImportModal
  importOpen: boolean;
  importConfig?: ImportConfiguration;
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
  pinnedViewPids?: string[];
  onPinView?: (pid: string) => Promise<void>;
  onUnpinView?: (pid: string) => Promise<void>;
  canManageTeamPins?: boolean;
  teamViews?: any[];
  teamPinnedViewPids?: string[];
  onTeamPinView?: (pid: string, teamId: string) => Promise<void>;
  onTeamUnpinView?: (pid: string, teamId: string) => Promise<void>;
  viewManageFields?: Array<{ code: string; name: string; dataType: string }>;

  // ColumnSettingsPanel
  columnSettingsOpen: boolean;
  onColumnSettingsClose: () => void;
  allColumnDefs: ColumnSettingsDefinition[];
  viewColumns?: ViewColumnConfig[];
  columnSettingsRowHeight?: import('~/framework/smart/types/savedView').RowHeight;
  onColumnSettingsSave: (payload: ColumnSettingsSavePayload) => Promise<void>;
  t: (key: string) => string;

  // FilterFieldPicker
  fieldPickerOpen: boolean;
  fieldPickerAnchor?: { x: number; y: number };
  filterFieldMetadata: ListFilterFieldMetadata[];
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
  onFilterApply: (operator: string, value: unknown) => void;
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
  locale,

  // BulkFieldCommandModal
  bulkFieldCommand,
  bulkFieldCommandContext,
  onBulkFieldCommandClose,
  onBulkFieldCommandSubmit,

  // ImportModal
  importOpen,
  importConfig,
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
  pinnedViewPids,
  onPinView,
  onUnpinView,
  canManageTeamPins,
  teamViews,
  teamPinnedViewPids,
  onTeamPinView,
  onTeamUnpinView,
  viewManageFields,

  // ColumnSettingsPanel
  columnSettingsOpen,
  onColumnSettingsClose,
  allColumnDefs,
  viewColumns,
  columnSettingsRowHeight,
  onColumnSettingsSave,
  t,

  // FilterFieldPicker
  fieldPickerOpen,
  fieldPickerAnchor,
  filterFieldMetadata,
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
          locale={locale}
          t={t}
          onUpdateComplete={onBulkEditComplete}
        />
      )}

      {bulkFieldCommand && (
        <BulkFieldCommandModal
          open
          actionLabel={bulkFieldCommand.actionLabel}
          selectedCount={bulkFieldCommand.selectedCount}
          field={bulkFieldCommand.field}
          context={bulkFieldCommandContext}
          locale={locale}
          t={t}
          onClose={onBulkFieldCommandClose}
          onSubmit={onBulkFieldCommandSubmit}
        />
      )}

      <ImportModal
        open={importOpen}
        onClose={onImportClose}
        modelCode={modelCode}
        config={importConfig}
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
        pinnedViewPids={pinnedViewPids}
        onPinView={onPinView}
        onUnpinView={onUnpinView}
        canManageTeamPins={canManageTeamPins}
        teamViews={teamViews}
        teamPinnedViewPids={teamPinnedViewPids}
        onTeamPinView={onTeamPinView}
        onTeamUnpinView={onTeamUnpinView}
        fields={viewManageFields}
      />

      <ColumnSettingsPanel
        open={columnSettingsOpen}
        onClose={onColumnSettingsClose}
        allColumns={allColumnDefs}
        viewColumns={viewColumns}
        rowHeight={columnSettingsRowHeight}
        onSave={onColumnSettingsSave}
        t={t}
      />

      {/* Filter Field Picker */}
      <FilterFieldPicker
        open={fieldPickerOpen}
        anchorEl={fieldPickerAnchor}
        fields={filterFieldMetadata}
        activeFieldCodes={chipFilterFieldCodes}
        onSelect={onFieldPickerSelect}
        onClose={onFieldPickerClose}
      />

      {/* Filter Value Popover — edit operator + value of a chip */}
      {editingChipIdx !== null &&
        chipFilters[editingChipIdx] &&
        (() => {
          const cf = chipFilters[editingChipIdx];
          const column = tableColumns.find((c: ColumnConfig) => c.field === cf.fieldCode) as any;
          const fieldMeta = filterFieldMetadata.find((field) => field.fieldCode === cf.fieldCode);
          return (
            <FilterValuePopover
              open
              anchorEl={valuePopoverAnchor}
              fieldCode={cf.fieldCode}
              fieldLabel={
                fieldMeta?.label
                  ? fieldMeta.label
                  : column?.label
                    ? typeof column.label === 'string'
                      ? column.label
                      : column.label?.['zh-CN'] || cf.fieldCode
                    : (() => {
                        const mc = schema?.modelCode || tableName;
                        const key = `model.${mc}.${cf.fieldCode}.label`;
                        const resolved = t(key);
                        return resolved !== key ? resolved : cf.fieldCode;
                      })()
              }
              fieldType={fieldMeta?.fieldType || 'text'}
              dictCode={fieldMeta?.dictCode}
              referenceModelCode={fieldMeta?.referenceModelCode}
              referenceValueField={fieldMeta?.referenceValueField}
              referenceDisplayField={fieldMeta?.referenceDisplayField}
              operator={cf.operator}
              value={cf.value}
              onApply={(operator, value) => onFilterApply(String(operator), value)}
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
        recordPid={previewRecordId || ''}
        apiEndpoint={previewApiEndpoint}
        detailPageKey={previewDetailPageKey}
        onClose={onPreviewClose}
      />
    </>
  );
}
