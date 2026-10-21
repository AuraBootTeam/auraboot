import { clearTransientViewSearchParams } from './listPageStateUtils';
import {
  type SavedViewCreateRequest,
  type ViewFilterConfig,
  type ViewType,
} from '~/framework/smart/types/savedView';
import { savedViewService } from '~/shared/services/savedViewService';
import { ListModals } from './ListModals';
import type { ListPageModel } from './listPageModel';

/** list page modals — preserves the original element and event-handler expressions. */
export function renderListPageModals(
  model: Pick<
    ListPageModel,
    | 'bulkEditOpen'
    | 'setBulkEditOpen'
    | 'selectedIdList'
    | 'modelCode'
    | 'bulkEditColumns'
    | 'resolveColumnLabel'
    | 'handleBulkEditComplete'
    | 'locale'
    | 'bulkFieldCommand'
    | 'pageContext'
    | 'setBulkFieldCommand'
    | 'handleBulkFieldCommandSubmit'
    | 'importOpen'
    | 'importConfig'
    | 'setImportOpen'
    | 'handleImportComplete'
    | 'viewManageOpen'
    | 'setViewManageOpen'
    | 'setStartCreateViewMode'
    | 'savedViews'
    | 'currentView'
    | 'createView'
    | 'clearSortOverrides'
    | 'clearFilterOverrides'
    | 'activeQuickFilterRef'
    | 'setActiveQuickFilter'
    | 'setActiveViewType'
    | 'setSearchParams'
    | 'listSchemaFieldNameSet'
    | 'deleteSavedView'
    | 'duplicateView'
    | 'handleEditView'
    | 'setDefaultView'
    | 'selectView'
    | 'pageKey'
    | 'activeViewType'
    | 'startCreateViewMode'
    | 'modelPid'
    | 'loadData'
    | 'pagination'
    | 'filters'
    | 'reloadViews'
    | 'chipPins'
    | 'loadChipPins'
    | 'canManageTeamPins'
    | 'teamViews'
    | 'viewManageFields'
    | 'columnSettingsOpen'
    | 'setColumnSettingsOpen'
    | 'allColumnDefs'
    | 'effectiveViewConfig'
    | 'effectiveRowHeight'
    | 'handleColumnSettingsSave'
    | 't'
    | 'fieldPickerOpen'
    | 'fieldPickerAnchor'
    | 'filterFieldMetadata'
    | 'chipFilters'
    | 'setLocalChipFilters'
    | 'setEditingChipIdx'
    | 'setValuePopoverAnchor'
    | 'setFieldPickerOpen'
    | 'editingChipIdx'
    | 'valuePopoverAnchor'
    | 'tableColumns'
    | 'schema'
    | 'tableName'
    | 'chipFiltersRef'
    | 'contextMenu'
    | 'activeSorts'
    | 'setLocalActiveSorts'
    | 'buildCurrentColumnSettings'
    | 'ensureViewAndUpdateConfig'
    | 'setGroupByField'
    | 'setContextMenu'
    | 'previewRecordId'
    | 'setPreviewRecordId'
  >,
) {
  const {
    bulkEditOpen,
    setBulkEditOpen,
    selectedIdList,
    modelCode,
    bulkEditColumns,
    resolveColumnLabel,
    handleBulkEditComplete,
    locale,
    bulkFieldCommand,
    pageContext,
    setBulkFieldCommand,
    handleBulkFieldCommandSubmit,
    importOpen,
    importConfig,
    setImportOpen,
    handleImportComplete,
    viewManageOpen,
    setViewManageOpen,
    setStartCreateViewMode,
    savedViews,
    currentView,
    createView,
    clearSortOverrides,
    clearFilterOverrides,
    activeQuickFilterRef,
    setActiveQuickFilter,
    setActiveViewType,
    setSearchParams,
    listSchemaFieldNameSet,
    deleteSavedView,
    duplicateView,
    handleEditView,
    setDefaultView,
    selectView,
    pageKey,
    activeViewType,
    startCreateViewMode,
    modelPid,
    loadData,
    pagination,
    filters,
    reloadViews,
    chipPins,
    loadChipPins,
    canManageTeamPins,
    teamViews,
    viewManageFields,
    columnSettingsOpen,
    setColumnSettingsOpen,
    allColumnDefs,
    effectiveViewConfig,
    effectiveRowHeight,
    handleColumnSettingsSave,
    t,
    fieldPickerOpen,
    fieldPickerAnchor,
    filterFieldMetadata,
    chipFilters,
    setLocalChipFilters,
    setEditingChipIdx,
    setValuePopoverAnchor,
    setFieldPickerOpen,
    editingChipIdx,
    valuePopoverAnchor,
    tableColumns,
    schema,
    tableName,
    chipFiltersRef,
    contextMenu,
    activeSorts,
    setLocalActiveSorts,
    buildCurrentColumnSettings,
    ensureViewAndUpdateConfig,
    setGroupByField,
    setContextMenu,
    previewRecordId,
    setPreviewRecordId,
  } = model;
  return (
    <>
      <ListModals
        // BulkEditModal
        bulkEditOpen={bulkEditOpen}
        onBulkEditClose={() => setBulkEditOpen(false)}
        selectedIds={selectedIdList}
        modelCode={modelCode}
        bulkEditFields={bulkEditColumns.map((c) => ({
          code: c.field,
          name: resolveColumnLabel(c),
          dataType: c.valueType || 'string',
        }))}
        onBulkEditComplete={handleBulkEditComplete}
        locale={locale}
        bulkFieldCommand={bulkFieldCommand}
        bulkFieldCommandContext={pageContext}
        onBulkFieldCommandClose={() => setBulkFieldCommand(null)}
        onBulkFieldCommandSubmit={handleBulkFieldCommandSubmit}
        // ImportModal
        importOpen={importOpen}
        importConfig={importConfig}
        onImportClose={() => setImportOpen(false)}
        onImportComplete={handleImportComplete}
        // ViewManagePanel
        viewManageOpen={viewManageOpen}
        onViewManageClose={() => {
          setViewManageOpen(false);
          setStartCreateViewMode(false);
        }}
        savedViews={savedViews}
        currentView={currentView}
        onCreateView={async (req: SavedViewCreateRequest) => createView(req)}
        onCreateViewSuccess={(view) => {
          const newType = (view.viewType as ViewType) || 'table';
          clearSortOverrides();
          clearFilterOverrides();
          activeQuickFilterRef.current = null;
          setActiveQuickFilter(null);
          setActiveViewType(newType);
          setStartCreateViewMode(false);
          if (view.pid) {
            setSearchParams(
              (prev) => {
                const p = new URLSearchParams(prev);
                p.set('view', view.pid);
                clearTransientViewSearchParams(p, listSchemaFieldNameSet);
                return p;
              },
              { replace: true },
            );
          }
        }}
        onDeleteView={async (pid: string) => {
          await deleteSavedView(pid);
        }}
        onDuplicateView={async (pid: string, name: string) => {
          await duplicateView(pid, name);
        }}
        onEditView={handleEditView}
        onSetDefaultView={async (pid: string) => {
          await setDefaultView(pid);
        }}
        onSelectView={(pid) => {
          clearSortOverrides();
          clearFilterOverrides();
          activeQuickFilterRef.current = null;
          setActiveQuickFilter(null);
          selectView(pid);
          setSearchParams(
            (prev) => {
              const p = new URLSearchParams(prev);
              p.set('view', pid);
              clearTransientViewSearchParams(p, listSchemaFieldNameSet);
              return p;
            },
            { replace: true },
          );
          const view = savedViews.find((v) => v.pid === pid);
          setActiveViewType((view?.viewType as ViewType) || 'table');
        }}
        pageKey={pageKey}
        activeViewType={activeViewType}
        startInCreateMode={startCreateViewMode}
        modelPid={modelPid}
        onFieldsCreated={() => {
          loadData({ page: 0, size: pagination.pageSize, filters });
        }}
        onViewConfigSaved={reloadViews}
        pinnedViewPids={chipPins.map((p) => p.viewPid)}
        onPinView={async (pid: string) => {
          await savedViewService.pinView(pid);
          await loadChipPins();
        }}
        onUnpinView={async (pid: string) => {
          await savedViewService.unpinView(pid);
          await loadChipPins();
        }}
        canManageTeamPins={canManageTeamPins}
        teamViews={teamViews}
        teamPinnedViewPids={chipPins.map((p) => p.viewPid)}
        onTeamPinView={async (pid: string, teamId: string) => {
          await savedViewService.pinView(pid, { scope: 'team', teamId });
          await loadChipPins();
        }}
        onTeamUnpinView={async (pid: string, teamId: string) => {
          await savedViewService.unpinView(pid, { scope: 'team', teamId });
          await loadChipPins();
        }}
        viewManageFields={viewManageFields}
        // ColumnSettingsPanel
        columnSettingsOpen={columnSettingsOpen}
        onColumnSettingsClose={() => setColumnSettingsOpen(false)}
        allColumnDefs={allColumnDefs}
        viewColumns={effectiveViewConfig?.columns}
        columnSettingsRowHeight={effectiveRowHeight}
        onColumnSettingsSave={handleColumnSettingsSave}
        t={t}
        // FilterFieldPicker
        fieldPickerOpen={fieldPickerOpen}
        fieldPickerAnchor={fieldPickerAnchor}
        filterFieldMetadata={filterFieldMetadata}
        chipFilterFieldCodes={chipFilters.map((f) => f.fieldCode)}
        onFieldPickerSelect={(fieldCode) => {
          // Add a new empty filter chip and immediately open the value popover
          const newFilter: ViewFilterConfig = {
            fieldCode,
            operator: 'eq',
            value: '',
          };
          setLocalChipFilters((prev) => {
            const next = [...prev, newFilter];
            // Open value popover for the newly added chip
            setTimeout(() => {
              setEditingChipIdx(next.length - 1);
              // Position near the "Add Filter" button
              setValuePopoverAnchor(fieldPickerAnchor || { x: 300, y: 200 });
            }, 50);
            return next;
          });
          setFieldPickerOpen(false);
        }}
        onFieldPickerClose={() => setFieldPickerOpen(false)}
        // FilterValuePopover
        editingChipIdx={editingChipIdx}
        chipFilters={chipFilters}
        valuePopoverAnchor={valuePopoverAnchor}
        tableColumns={tableColumns}
        schema={schema}
        tableName={tableName}
        onFilterApply={(operator, value) => {
          const nextChipFilters = chipFilters.map((filter, index) =>
            index === editingChipIdx
              ? { ...filter, operator: operator as ViewFilterConfig['operator'], value }
              : filter,
          );
          chipFiltersRef.current = nextChipFilters;
          setLocalChipFilters(nextChipFilters);
          setEditingChipIdx(null);
          void loadData({
            page: 0,
            size: pagination.pageSize,
            filters,
            chipFilters: nextChipFilters,
          });
        }}
        onFilterCancel={() => setEditingChipIdx(null)}
        // ColumnContextMenu
        contextMenu={contextMenu}
        activeSorts={activeSorts}
        onSort={(dir) => {
          if (!contextMenu) return;
          if (dir === 'clear') {
            setLocalActiveSorts((prev) =>
              prev.filter((s) => s.fieldCode !== contextMenu.column.field),
            );
          } else {
            setLocalActiveSorts([
              { fieldCode: contextMenu.column.field, direction: dir, priority: 0 },
            ]);
          }
        }}
        onFreeze={(position) => {
          if (!contextMenu) return;
          const fieldCode = contextMenu.column.field;
          const columns = buildCurrentColumnSettings().map((column) => {
            if (column.fieldCode === fieldCode) {
              return position === 'none'
                ? { ...column, frozen: false, frozenPosition: undefined }
                : {
                    ...column,
                    visible: true,
                    frozen: true,
                    frozenPosition: position,
                  };
            }
            return column;
          });
          void ensureViewAndUpdateConfig({ columns });
        }}
        onHide={() => {
          if (!contextMenu) return;
          if (tableColumns.filter((column) => !column.isActionColumn).length <= 1) return;
          const columns = buildCurrentColumnSettings().map((column) =>
            column.fieldCode === contextMenu.column.field
              ? { ...column, visible: false, frozen: false, frozenPosition: undefined }
              : column,
          );
          void ensureViewAndUpdateConfig({ columns });
        }}
        onFilterByColumn={() => {
          // Placeholder — will be connected to FilterChipBar in integration step
        }}
        onGroupBy={() => {
          if (!contextMenu) return;
          setGroupByField((prev) =>
            prev === contextMenu.column.field ? null : contextMenu.column.field,
          );
        }}
        onContextMenuClose={() => setContextMenu(null)}
        // RecordPreviewDrawer
        previewRecordId={previewRecordId}
        previewApiEndpoint={
          schema?.dataSource?.type === 'api'
            ? schema.dataSource.endpoint || (schema.dataSource as any).url
            : undefined
        }
        previewDetailPageKey={(schema as any)?.extension?.relatedPages?.detail}
        onPreviewClose={() => setPreviewRecordId(null)}
      />
    </>
  );
}
