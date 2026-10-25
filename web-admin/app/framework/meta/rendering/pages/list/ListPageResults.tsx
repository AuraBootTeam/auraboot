import { BlockRenderer } from '@auraboot/runtime-kernel';
import React from 'react';
import type { ColumnConfig } from '~/framework/meta/schemas/types';
import { getLegacyCompatibleRecordPid } from '~/framework/meta/utils/publicRecordId';
import { SmartViewRenderer } from '~/framework/smart/components/view/SmartViewRenderer';
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';
import type { ListPageModel } from './listPageModel';
import { ListPagination } from './ListPagination';
import { resolveListReferenceFilterLabel } from './listReferenceDisplay';
import { ListTable } from './ListTable';
import { ListToolbar } from './ListToolbar';
import { SelectAllMatchingBanner } from './SelectAllMatchingBanner';
import { ViewAnalysisDrawer } from './ViewAnalysisDrawer';

/** list page results — preserves the original element and event-handler expressions. */
export function renderListPageResults(
  model: Pick<
    ListPageModel,
    | 'skipListData'
    | 'miscListBlocks'
    | 'runtime'
    | 'activeViewType'
    | 'tableBlock'
    | 'miscBlocksPosition'
    | 'schemaExtension'
    | 'workspace'
    | 'keyword'
    | 'tableSearchPlaceholder'
    | 'setKeyword'
    | 'syncKeywordToUrl'
    | 'setSearchParams'
    | 'keywordRef'
    | 'commitSearch'
    | 'loadData'
    | 'pagination'
    | 'filterFormVisible'
    | 'setFilterFormVisible'
    | 'filterBlock'
    | 'quickFilterChips'
    | 'activeQuickFilter'
    | 'currentView'
    | 'handleActivateChip'
    | 'handleSaveActivePreset'
    | 'activeSorts'
    | 'setLocalActiveSorts'
    | 'tableColumns'
    | 'resolveColumnLabel'
    | 'effectiveViewConfig'
    | 'handleRowHeightChange'
    | 'setColumnSettingsOpen'
    | 'namedQueryCode'
    | 'isApiDatasourcePage'
    | 'setAnalysisOpen'
    | 'chipFilters'
    | 'chipFiltersRef'
    | 'setLocalChipFilters'
    | 'filters'
    | 'filterFieldMetadata'
    | 'user'
    | 'translateCommon'
    | 'referenceDisplayConfigs'
    | 'referenceDisplayCache'
    | 'dictDataCache'
    | 'setFieldPickerAnchor'
    | 'setFieldPickerOpen'
    | 'setValuePopoverAnchor'
    | 'setEditingChipIdx'
    | 'hideQuickFilters'
    | 'listExtensions'
    | 'analysisOpen'
    | 'schema'
    | 'tableName'
    | 'locale'
    | 't'
    | 'activeRuntimeViewFilters'
    | 'handleAnalysisDrillDown'
    | 'selectionEnabled'
    | 'pageFullySelected'
    | 'allMatchingSelected'
    | 'selectedIds'
    | 'handleSelectAllMatching'
    | 'clearAllSelection'
    | 'displayData'
    | 'loading'
    | 'effectiveRowHeight'
    | 'modelCode'
    | 'columnOrder'
    | 'handleColumnReorder'
    | 'handleColumnResize'
    | 'toggleSort'
    | 'toggleRowSelection'
    | 'toggleSelectAll'
    | 'handleRowClick'
    | 'setContextMenu'
    | 'renderCellContent'
    | 'evaluateVisibleWhen'
    | 'canUseButton'
    | 'resolveButtonLabel'
    | 'handleAction'
    | 'columnWidths'
    | 'groupedData'
    | 'groupByField'
    | 'collapsedGroups'
    | 'toggleGroupCollapse'
    | 'getRowStyle'
    | 'previewRecordId'
    | 'tableEmptyState'
    | 'handleInlineSave'
    | 'summaryRowEnabled'
    | 'treeConfig'
    | 'handlePageChange'
    | 'handlePageSizeChange'
    | 'effectiveSelectedCount'
    | 'selectedIdList'
    | 'builtInBulkCapabilities'
    | 'setBulkEditOpen'
    | 'handleBulkDelete'
    | 'handleExportSelected'
    | 'visibleBulkActions'
    | 'handleBulkAction'
    | 'activeViewTemplate'
    | 'activeViewConfig'
    | 'navigateToRecordView'
    | 'setViewManageOpen'
    | 'setActiveViewType'
    | 'pageKey'
  >,
) {
  const {
    skipListData,
    miscListBlocks,
    runtime,
    activeViewType,
    tableBlock,
    miscBlocksPosition,
    schemaExtension,
    workspace,
    keyword,
    tableSearchPlaceholder,
    setKeyword,
    syncKeywordToUrl,
    setSearchParams,
    keywordRef,
    commitSearch,
    loadData,
    pagination,
    filterFormVisible,
    setFilterFormVisible,
    filterBlock,
    quickFilterChips,
    activeQuickFilter,
    currentView,
    handleActivateChip,
    handleSaveActivePreset,
    activeSorts,
    setLocalActiveSorts,
    tableColumns,
    resolveColumnLabel,
    effectiveViewConfig,
    handleRowHeightChange,
    setColumnSettingsOpen,
    namedQueryCode,
    isApiDatasourcePage,
    setAnalysisOpen,
    chipFilters,
    chipFiltersRef,
    setLocalChipFilters,
    filters,
    filterFieldMetadata,
    user,
    translateCommon,
    referenceDisplayConfigs,
    referenceDisplayCache,
    dictDataCache,
    setFieldPickerAnchor,
    setFieldPickerOpen,
    setValuePopoverAnchor,
    setEditingChipIdx,
    hideQuickFilters,
    listExtensions,
    analysisOpen,
    schema,
    tableName,
    locale,
    t,
    activeRuntimeViewFilters,
    handleAnalysisDrillDown,
    selectionEnabled,
    pageFullySelected,
    allMatchingSelected,
    selectedIds,
    handleSelectAllMatching,
    clearAllSelection,
    displayData,
    loading,
    effectiveRowHeight,
    modelCode,
    columnOrder,
    handleColumnReorder,
    handleColumnResize,
    toggleSort,
    toggleRowSelection,
    toggleSelectAll,
    handleRowClick,
    setContextMenu,
    renderCellContent,
    evaluateVisibleWhen,
    canUseButton,
    resolveButtonLabel,
    handleAction,
    columnWidths,
    groupedData,
    groupByField,
    collapsedGroups,
    toggleGroupCollapse,
    getRowStyle,
    previewRecordId,
    tableEmptyState,
    handleInlineSave,
    summaryRowEnabled,
    treeConfig,
    handlePageChange,
    handlePageSizeChange,
    effectiveSelectedCount,
    selectedIdList,
    builtInBulkCapabilities,
    setBulkEditOpen,
    handleBulkDelete,
    handleExportSelected,
    visibleBulkActions,
    handleBulkAction,
    activeViewTemplate,
    activeViewConfig,
    navigateToRecordView,
    setViewManageOpen,
    setActiveViewType,
    pageKey,
  } = model;
  return (
    <>
      {skipListData ? (
        miscListBlocks.length > 0 &&
        runtime && (
          <div className="flex flex-col gap-4 p-4" data-testid="list-misc-blocks">
            {miscListBlocks.map((block: any, idx: number) => (
              <BlockRenderer
                key={block.id || `misc-list-${idx}`}
                block={block}
                runtime={runtime}
                areaId="list-misc"
              />
            ))}
          </div>
        )
      ) : activeViewType === 'table' ? (
        <div
          className="relative"
          data-aura-block-id={tableBlock?.id}
          data-aura-element-id={tableBlock?.id}
        >
          {miscBlocksPosition === 'beforeTable' && miscListBlocks.length > 0 && runtime && (
            <div className="flex flex-col gap-4 p-4" data-testid="list-misc-blocks">
              {miscListBlocks.map((block: any, idx: number) => (
                <BlockRenderer
                  key={block.id || `misc-list-${idx}`}
                  block={block}
                  runtime={runtime}
                  areaId="list-misc"
                />
              ))}
            </div>
          )}

          {!schemaExtension.hideListToolbar && (
            <ListToolbar
              workspace={workspace}
              keyword={keyword}
              searchPlaceholder={tableSearchPlaceholder}
              onKeywordChange={setKeyword}
              onSearch={() => {
                // Enter is an explicit commit. Do not rely on flush(): the
                // debounce may already have fired (or been consumed by a
                // concurrent list-state update), in which case flush is a
                // no-op and a stale URL writer can drop the keyword. Commit
                // from the synchronous ref with a functional update so
                // pagination/filter params changed in the same tick survive.
                syncKeywordToUrl.cancel();
                setSearchParams(
                  (prev) => {
                    const p = new URLSearchParams(prev);
                    const value = keywordRef.current.trim();
                    if (value) p.set('keyword', value);
                    else p.delete('keyword');
                    return p;
                  },
                  { replace: true },
                );
                commitSearch();
              }}
              filterFormVisible={filterFormVisible}
              onFilterFormToggle={() => setFilterFormVisible((prev) => !prev)}
              hasFilterBlock={
                !!(filterBlock && filterBlock.fields && filterBlock.fields.length > 0)
              }
              chips={quickFilterChips}
              activeQuickFilter={activeQuickFilter}
              currentViewPid={currentView?.pid ?? null}
              onActivateChip={handleActivateChip}
              onSaveActivePreset={handleSaveActivePreset}
              activeSorts={activeSorts}
              onSortsChange={setLocalActiveSorts}
              sortableColumns={tableColumns
                .filter((c: ColumnConfig) => !c.isActionColumn && c.field && c.sortable !== false)
                .map((c: ColumnConfig) => ({
                  field: c.field,
                  label: resolveColumnLabel(c),
                  valueType: c.valueType || c.sorter,
                }))}
              rowHeight={effectiveViewConfig?.rowHeight}
              onRowHeightChange={handleRowHeightChange}
              onColumnSettingsOpen={() => setColumnSettingsOpen(true)}
              onAnalysisOpen={
                schemaExtension.hideViewAnalysis || namedQueryCode || isApiDatasourcePage
                  ? undefined
                  : () => setAnalysisOpen(true)
              }
              chipFilters={chipFilters}
              onChipFiltersChange={(nextChipFilters) => {
                chipFiltersRef.current = nextChipFilters;
                setLocalChipFilters(nextChipFilters);
                void loadData({
                  page: 0,
                  size: pagination.pageSize,
                  filters,
                  chipFilters: nextChipFilters,
                });
              }}
              fieldMetadata={filterFieldMetadata}
              resolveChipValueLabel={(filter) => {
                if (filter.isExpression && filter.expression) {
                  if (
                    filter.expression === '#currentUser' ||
                    filter.expression === '${system.currentUser}'
                  ) {
                    return (
                      user?.name ||
                      user?.nickname ||
                      user?.username ||
                      translateCommon('common.current_user', '当前用户')
                    );
                  }
                  if (
                    filter.expression === '#currentDepartmentOwners' ||
                    filter.expression === '${system.currentDepartmentOwners}'
                  ) {
                    return translateCommon('common.current_department', '当前部门');
                  }
                  if (
                    filter.expression === '#currentSharedRecords' ||
                    filter.expression === '${system.currentSharedRecords}'
                  ) {
                    return translateCommon('common.collaborative_records', '协作记录');
                  }
                  return filter.expression;
                }
                const referenceLabel = resolveListReferenceFilterLabel(
                  filter,
                  referenceDisplayConfigs,
                  referenceDisplayCache,
                  translateCommon('common.unknown', '未知'),
                );
                if (referenceLabel !== undefined) return referenceLabel;
                const dc = filterFieldMetadata.find(
                  (field) => field.fieldCode === filter.fieldCode,
                )?.dictCode;
                if (!dc) return undefined;
                const values = Array.isArray(filter.value) ? filter.value : [filter.value];
                const items = dictDataCache.current.get(dc);
                const labels = values.map(
                  (value) =>
                    items?.find((item) => String(item.value) === String(value))?.label ??
                    String(value),
                );
                return labels.join('、');
              }}
              onAddFilter={(e?: React.MouseEvent) => {
                const rect = (e?.currentTarget as HTMLElement)?.getBoundingClientRect?.();
                setFieldPickerAnchor(
                  rect ? { x: rect.left, y: rect.bottom + 4 } : { x: 300, y: 200 },
                );
                setFieldPickerOpen(true);
              }}
              onChipClick={(idx, e) => {
                const rect = (e.currentTarget as HTMLElement).getBoundingClientRect();
                setValuePopoverAnchor({ x: rect.left, y: rect.bottom + 4 });
                setEditingChipIdx(idx);
              }}
              onClearAll={() => {
                chipFiltersRef.current = [];
                setLocalChipFilters([]);
                setLocalActiveSorts([]);
                void loadData({
                  page: 0,
                  size: pagination.pageSize,
                  filters,
                  sorts: [],
                  chipFilters: [],
                });
              }}
              hideSearch={Boolean(schemaExtension.hideSearch)}
              hideQuickFilters={hideQuickFilters}
              hideSort={listExtensions?.hideSort ?? Boolean(schemaExtension.hideSort)}
              hideColumnSettings={
                listExtensions?.hideColumnSettings ?? Boolean(schemaExtension.hideColumnSettings)
              }
              hideRowHeight={
                listExtensions?.hideRowHeight ?? Boolean(schemaExtension.hideRowHeight)
              }
              hideFilterChips={
                listExtensions?.hideFilterChips ?? Boolean(schemaExtension.hideFilterChips)
              }
            />
          )}

          <ViewAnalysisDrawer
            open={analysisOpen}
            onClose={() => setAnalysisOpen(false)}
            modelCode={schema?.modelCode || tableName}
            viewName={getLocalizedText(currentView?.name, locale, t)}
            keyword={keyword}
            filters={activeRuntimeViewFilters}
            fields={filterFieldMetadata}
            onDrillDown={handleAnalysisDrillDown}
          />

          {/* T9 — cross-page select-all banner. Shown once the whole page
                  is selected and more matching records exist beyond it, or
                  while in "all N matching" mode. */}
          <SelectAllMatchingBanner
            enabled={selectionEnabled}
            pageFullySelected={pageFullySelected}
            allMatchingSelected={allMatchingSelected}
            pageSelectedCount={selectedIds.size}
            total={pagination.total}
            onSelectAllMatching={handleSelectAllMatching}
            onClearSelection={clearAllSelection}
            t={t}
            locale={locale}
          />

          {/* Table area — extracted to ListTable with DnD column reorder */}
          <ListTable
            columns={tableColumns}
            data={displayData}
            loading={loading}
            activeSorts={activeSorts}
            selectedIds={selectedIds}
            workspace={workspace}
            rowHeight={effectiveRowHeight}
            modelCode={modelCode}
            columnOrder={columnOrder}
            onColumnReorder={handleColumnReorder}
            onColumnResize={handleColumnResize}
            onToggleSort={toggleSort}
            onSelectRow={(id, _checked) => toggleRowSelection(id)}
            onSelectAll={() => toggleSelectAll()}
            onRowClick={handleRowClick}
            onContextMenu={(e, column) => {
              setContextMenu({ x: e.clientX, y: e.clientY, column });
            }}
            renderCellContent={(record, column, rowIndex) =>
              renderCellContent(column, record, rowIndex)
            }
            evaluateVisibleWhen={evaluateVisibleWhen}
            canUseButton={canUseButton}
            resolveButtonLabel={resolveButtonLabel}
            handleAction={handleAction}
            resolveColumnLabel={resolveColumnLabel}
            columnWidths={columnWidths}
            groupedData={groupedData}
            groupByField={groupByField ?? undefined}
            collapsedGroups={collapsedGroups}
            onToggleGroupCollapse={toggleGroupCollapse}
            getRowStyle={getRowStyle}
            previewRecordId={previewRecordId}
            t={t}
            emptyTitle={tableEmptyState.title}
            emptyDescription={tableEmptyState.description}
            onInlineSave={handleInlineSave}
            dictDataCache={dictDataCache.current}
            enableSelection={selectionEnabled}
            showSummaryRow={summaryRowEnabled}
            locale={locale}
            treeConfig={treeConfig}
          />

          {/* G7 — misc blocks (chart / description / rich-text / divider /
                  stat-card / etc.) dispatched via BlockRenderer fallback
                  registry. Unknown block types surface a visible placeholder. */}
          {miscBlocksPosition === 'afterTable' && miscListBlocks.length > 0 && runtime && (
            <div className="flex flex-col gap-4 p-4" data-testid="list-misc-blocks">
              {miscListBlocks.map((block: any, idx: number) => (
                <BlockRenderer
                  key={block.id || `misc-list-${idx}`}
                  block={block}
                  runtime={runtime}
                  areaId="list-misc"
                />
              ))}
            </div>
          )}

          {/* Pagination + Bulk Action Toolbar */}
          {!loading && (
          <ListPagination
            workspace={workspace}
            current={pagination.current}
            pageSize={pagination.pageSize}
            total={pagination.total}
            onPageChange={handlePageChange}
            onPageSizeChange={handlePageSizeChange}
            t={t}
            locale={locale}
            selectedCount={effectiveSelectedCount}
            selectedIds={selectedIdList}
            modelCode={modelCode}
            onBulkEdit={
              builtInBulkCapabilities.edit && !allMatchingSelected
                ? () => setBulkEditOpen(true)
                : undefined
            }
            onBulkDelete={
              builtInBulkCapabilities.delete && !allMatchingSelected ? handleBulkDelete : undefined
            }
            onBulkExport={
              builtInBulkCapabilities.export ? () => handleExportSelected('xlsx') : undefined
            }
            bulkActions={allMatchingSelected ? [] : visibleBulkActions}
            onBulkAction={handleBulkAction}
            resolveBulkActionLabel={resolveButtonLabel}
            onClearSelection={clearAllSelection}
          />
          )}
        </div>
      ) : (
        <div data-aura-block-id={tableBlock?.id} data-aura-element-id={tableBlock?.id}>
          <SmartViewRenderer
            view={
              {
                ...(activeViewTemplate || currentView || {}),
                modelCode,
                viewType: activeViewType,
                viewConfig: activeViewConfig,
              } as any
            }
            onGanttTaskClick={navigateToRecordView}
            onOpenViewConfig={() => setViewManageOpen(true)}
            onSwitchToTableView={() => setActiveViewType('table')}
            onCardClick={(card) => navigateToRecordView(getLegacyCompatibleRecordPid(card))}
            onEventClick={navigateToRecordView}
            onGalleryCardClick={navigateToRecordView}
            onTreeNodeClick={navigateToRecordView}
            onDataRefresh={() => loadData({ page: 0, size: pagination.pageSize })}
            linkageFilters={[]}
            pageKey={pageKey}
          />
        </div>
      )}
    </>
  );
}
