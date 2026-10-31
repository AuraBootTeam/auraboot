import { WorkspaceCell } from '~/framework/meta/runtime/renderers/WorkspaceCells';
import type { ButtonConfig } from '~/framework/meta/schemas/types';
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';
import { DndSubTableWrapper } from '~/framework/meta/components/subtable/DndSubTableWrapper';
import { SortableSubTableRow } from '~/framework/meta/components/subtable/SortableSubTableRow';
import { InlineEditableCell } from '~/framework/meta/components/subtable/InlineEditableCell';
import { SubTableSummaryRow } from '~/framework/meta/components/subtable/SubTableSummaryRow';
import { getLegacyCompatibleRecordPid } from '~/framework/meta/utils/publicRecordId';
import { ToolbarBlockRenderer } from '../ToolbarBlockRenderer';
import { Select } from '~/ui/smart/form/Select';
import type { EnrichedColumnConfig, ResolvedSubTableViewerProps } from './subTableTypes';
import { formatCellValue } from './subTableValues';
import type { useSubTableState } from './useSubTableState';

import type { useSubTableTreeSorting } from './useSubTableTreeSorting';
import type { useSubTableColumns } from './useSubTableColumns';
import type { useSubTableRowActions } from './useSubTableRowActions';
import type { useSubTableReferenceDisplay } from './useSubTableReferenceDisplay';
import type { useSubTableInlineEditing } from './useSubTableInlineEditing';
import type { useSubTableMutations } from './useSubTableMutations';

type RenderSubTableViewArgs = Pick<
  ReturnType<typeof useSubTableState>,
  | 'loading'
  | 'columnMetaLoading'
  | 'error'
  | 'timezone'
  | 'formats'
  | 'rowActionLoadingKey'
  | 'crossRowErrors'
  | 'isAdding'
  | 'setIsAdding'
  | 'editingRowId'
  | 'editingValues'
  | 'editErrors'
  | 'setEditingValues'
  | 'setEditErrors'
  | 'editSaving'
  | 'deletingId'
  | 'newRowData'
  | 'setNewRowData'
  | 'setAddErrors'
  | 'addErrors'
  | 'saving'
  | 'rows'
> &
  Pick<
    ResolvedSubTableViewerProps,
    't' | 'config' | 'locale' | 'parentRecordData' | 'runtime' | 'isEditable'
  > &
  Pick<
    ReturnType<typeof useSubTableTreeSorting>,
    | 'displayRows'
    | 'emptyStateTitle'
    | 'configuredRowActions'
    | 'isSortable'
    | 'hasActions'
    | 'toolbarBlock'
    | 'extraColCount'
    | 'emptyStateDescription'
    | 'emptyStateActionLabel'
    | 'dndItems'
    | 'handleDragEnd'
    | 'toggleExpand'
    | 'totalCols'
  > &
  Pick<
    ReturnType<typeof useSubTableColumns>,
    | 'effectiveColumns'
    | 'getDictLabel'
    | 'isColumnEditable'
    | 'getDictItems'
    | 'referenceInputSources'
    | 'editableFields'
    | 'canInlineEdit'
  > &
  Pick<
    ReturnType<typeof useSubTableRowActions>,
    'isRowActionVisible' | 'isRowActionDisabled' | 'handleRowAction' | 'resolveButtonLabel'
  > &
  Pick<
    ReturnType<typeof useSubTableReferenceDisplay>,
    'resolveColumnLabel' | 'getReferenceDisplayValue'
  > &
  Pick<
    ReturnType<typeof useSubTableInlineEditing>,
    'handleEditKeyDown' | 'handleStartEdit' | 'handleSaveEdit' | 'handleCancelEdit'
  > &
  Pick<ReturnType<typeof useSubTableMutations>, 'handleDeleteRow' | 'handleAddRow'>;

export function renderSubTableView({
  loading,
  columnMetaLoading,
  t,
  error,
  config,
  displayRows,
  locale,
  timezone,
  parentRecordData,
  emptyStateTitle,
  effectiveColumns,
  getDictLabel,
  formats,
  configuredRowActions,
  isRowActionVisible,
  rowActionLoadingKey,
  isRowActionDisabled,
  handleRowAction,
  resolveButtonLabel,
  isSortable,
  resolveColumnLabel,
  hasActions,
  toolbarBlock,
  runtime,
  crossRowErrors,
  isAdding,
  extraColCount,
  emptyStateDescription,
  isEditable,
  setIsAdding,
  emptyStateActionLabel,
  dndItems,
  handleDragEnd,
  editingRowId,
  toggleExpand,
  isColumnEditable,
  getReferenceDisplayValue,
  getDictItems,
  referenceInputSources,
  editingValues,
  editErrors,
  setEditingValues,
  setEditErrors,
  handleEditKeyDown,
  editableFields,
  canInlineEdit,
  handleStartEdit,
  handleSaveEdit,
  editSaving,
  handleCancelEdit,
  handleDeleteRow,
  deletingId,
  newRowData,
  setNewRowData,
  setAddErrors,
  addErrors,
  handleAddRow,
  saving,
  totalCols,
  rows,
}: RenderSubTableViewArgs) {
  if (loading || columnMetaLoading) {
    return (
      <div className="text-text-3 py-8 text-center text-sm">
        {t('common.loading') || 'Loading...'}
      </div>
    );
  }

  if (error) {
    return (
      <div role="alert" className="text-status-red py-4 text-center text-sm">
        {error}
      </div>
    );
  }

  if (config.presentation) {
    const view = config.presentation;
    const completed = displayRows.filter((row) =>
      view.completedValues.includes(String(row[view.statusField])),
    );
    const next = displayRows.find(
      (row) => !view.completedValues.includes(String(row[view.statusField])),
    );
    if (view.kind === 'focus') {
      return (
        <div data-testid="collection-focus" className="space-y-3">
          {next ? (
            <>
              <p className="text-text-2 text-xs leading-6">
                {String(next[view.descriptionField || view.titleField] || next[view.titleField])}
              </p>
              <div className="bg-subtle rounded-control px-3 py-3">
                <p className="text-text-3 mb-2 text-[11px]">
                  {locale.startsWith('zh') ? '下一次交付节点' : 'Next delivery checkpoint'}
                </p>
                <strong className="text-text text-xs font-medium">
                  {String(next[view.titleField])}
                </strong>
                {view.dateField && (
                  <div className="mt-2">
                    <WorkspaceCell
                      value={next[view.dateField]}
                      record={next}
                      column={{
                        field: view.dateField,
                        timezone,
                        presentation: { kind: 'deadline' },
                      }}
                      locale={locale}
                    />
                  </div>
                )}
                {view.ownerField && parentRecordData?.[`${view.ownerField}_display`] && (
                  <p className="text-text-2 mt-2 text-[11px]">
                    {String(parentRecordData[`${view.ownerField}_display`])}
                  </p>
                )}
              </div>
            </>
          ) : (
            <p className="text-text-3 text-xs">
              {locale.startsWith('zh') ? '暂无待完成里程碑' : 'No pending checkpoints'}
            </p>
          )}
        </div>
      );
    }
    return (
      <div data-testid="subtable-viewer" className="min-w-0">
        <div className="mb-4 flex flex-wrap items-center gap-3">
          {view.title && (
            <h3 className="text-text text-sm font-semibold">
              {getLocalizedText(view.title, locale, t)}
            </h3>
          )}
          <p className="text-text-3 text-[11px]" data-testid="collection-completion">
            {locale.startsWith('zh')
              ? `${completed.length} / ${displayRows.length} 已完成`
              : `${completed.length} / ${displayRows.length} completed`}
          </p>
        </div>
        {displayRows.length === 0 && (
          <p data-testid="subtable-empty-state" className="text-text-3 py-6 text-center text-xs">
            {emptyStateTitle}
          </p>
        )}
        <div role="table" aria-label={locale.startsWith('zh') ? '关联事项' : 'Related items'}>
          {displayRows.map((row, index) => {
            const done = view.completedValues.includes(String(row[view.statusField]));
            return (
              <div
                role="row"
                key={row.pid}
                className="border-border flex flex-wrap items-center gap-3 border-t py-2"
                data-testid={`collection-row-${row.pid}`}
              >
                <span
                  aria-hidden="true"
                  className={`flex h-5 w-5 shrink-0 items-center justify-center rounded-full border text-xs ${done ? 'border-status-green bg-status-green-bg text-status-green' : 'border-border bg-panel text-text-3'}`}
                >
                  {done ? '✓' : ''}
                </span>
                <div role="cell" className="min-w-0 flex-1">
                  <strong className="text-text block text-xs leading-4 font-medium">
                    {String(row[view.titleField] || '—')}
                  </strong>
                  {view.descriptionField && (
                    <p className="text-text-3 mt-1 text-[11px] leading-4">
                      {String(row[view.descriptionField] || '')}
                    </p>
                  )}
                </div>
                {view.dateField && (
                  <span role="cell" className="text-text-3 text-[11px] tabular-nums">
                    {formatCellValue(
                      row[view.dateField],
                      effectiveColumns.find((col) => col.field === view.dateField) || {
                        field: view.dateField,
                        valueType: 'date',
                      },
                      getDictLabel,
                      timezone,
                      formats,
                    )}
                  </span>
                )}
                <span role="cell" className="text-xs">
                  {formatCellValue(
                    row[view.statusField],
                    effectiveColumns.find((col) => col.field === view.statusField) || {
                      field: view.statusField,
                    },
                    getDictLabel,
                    timezone,
                    formats,
                  )}
                </span>
                {configuredRowActions
                  .filter((button) => button && isRowActionVisible(button, row))
                  .map((button) => (
                    <button
                      key={button.code}
                      type="button"
                      data-testid={`subtable-row-action-${button.code}-${index}`}
                      disabled={Boolean(rowActionLoadingKey) || isRowActionDisabled(button, row)}
                      onClick={() => handleRowAction(button, row, index)}
                      className="text-accent text-[11px] disabled:opacity-40"
                    >
                      {resolveButtonLabel(button)}
                    </button>
                  ))}
              </div>
            );
          })}
        </div>
      </div>
    );
  }

  const tableHeader = (
    <thead className="bg-subtle">
      <tr>
        {isSortable && <th className="text-text-3 w-10 px-1 py-2.5 text-xs font-medium"></th>}
        {effectiveColumns.map((col: EnrichedColumnConfig) => (
          <th
            key={col.field}
            className={`text-text-2 px-4 py-2.5 text-xs font-medium tracking-wider whitespace-nowrap uppercase ${
              col.align === 'right'
                ? 'text-right'
                : col.align === 'center'
                  ? 'text-center'
                  : 'text-left'
            }`}
            style={
              col.width
                ? {
                    width: typeof col.width === 'number' ? `${col.width}px` : col.width,
                    minWidth: typeof col.width === 'number' ? `${col.width}px` : col.width,
                  }
                : undefined
            }
          >
            {col.label ? getLocalizedText(col.label, locale, t) : resolveColumnLabel(col.field)}
            {isEditable && col.required && <span className="text-status-red ml-0.5">*</span>}
          </th>
        ))}
        {hasActions && (
          <th className="text-text-2 w-28 px-4 py-2.5 text-center text-xs font-medium">
            {t('common.actions') !== 'common.actions' ? t('common.actions') : 'Actions'}
          </th>
        )}
      </tr>
    </thead>
  );

  return (
    <div
      className="rounded-card border-border overflow-x-auto border"
      data-testid="subtable-viewer"
    >
      {toolbarBlock && runtime ? (
        <div
          className="border-border bg-subtle flex justify-end border-b px-4 py-3"
          data-testid="subtable-toolbar-actions"
        >
          <ToolbarBlockRenderer block={toolbarBlock} runtime={runtime} />
        </div>
      ) : null}

      {/* Cross-row validation errors banner */}
      {crossRowErrors.length > 0 && (
        <div
          className="bg-status-red-bg text-status-red border-status-red border-b px-4 py-2 text-sm"
          data-testid="subtable-cross-row-errors"
          role="alert"
        >
          {crossRowErrors.map((msg, i) => (
            <p key={i}>{msg}</p>
          ))}
        </div>
      )}

      {displayRows.length === 0 && !isAdding ? (
        <table className="divide-border min-w-full divide-y" data-testid="subtable-table">
          {tableHeader}
          <tbody>
            <tr>
              <td colSpan={effectiveColumns.length + extraColCount}>
                <div
                  className="flex flex-col items-center justify-center px-6 py-10 text-center"
                  data-testid="subtable-empty-state"
                >
                  <div className="text-text-2 text-sm font-medium">{emptyStateTitle}</div>
                  {emptyStateDescription ? (
                    <div className="text-text-2 mt-2 max-w-md text-sm leading-6">
                      {emptyStateDescription}
                    </div>
                  ) : null}
                  {isEditable && config.commands?.create ? (
                    <button
                      onClick={() => setIsAdding(true)}
                      data-testid="subtable-empty-action"
                      className="rounded-control bg-accent-weak text-accent border-border hover:bg-hover mt-5 border px-4 py-2 text-sm font-medium transition-colors"
                    >
                      {emptyStateActionLabel}
                    </button>
                  ) : null}
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      ) : (
        <table className="divide-border min-w-full divide-y" data-testid="subtable-table">
          {tableHeader}
          <tbody className="bg-panel divide-border divide-y">
            <DndSubTableWrapper
              items={dndItems}
              onDragEnd={handleDragEnd}
              disabled={!isSortable || !!editingRowId}
            >
              {displayRows.map((row, rowIndex) => {
                const rowId = row.pid || String(rowIndex);
                const isEditingThis = editingRowId === rowId;
                return (
                  <SortableSubTableRow
                    key={rowId}
                    id={rowId}
                    depth={row._depth || 0}
                    hasChildren={row._hasChildren || false}
                    expanded={row._expanded || false}
                    onToggleExpand={() => toggleExpand(rowId)}
                    sortable={!!isSortable && !editingRowId}
                  >
                    {effectiveColumns.map((col: EnrichedColumnConfig) => {
                      const cellAlign =
                        col.align === 'right'
                          ? 'text-right'
                          : col.align === 'center'
                            ? 'text-center'
                            : 'text-left';

                      // --- Editing mode ---
                      if (isEditingThis) {
                        const editable = isColumnEditable(col);
                        const referenceDisplayValue = getReferenceDisplayValue(row, col);
                        const dictOptions = col.dictCode
                          ? (getDictItems(col.dictCode) || []).map((d) => ({
                              value: d.value,
                              label: d.label,
                            }))
                          : undefined;
                        return (
                          <td
                            key={col.field}
                            className={`px-4 py-1.5 ${cellAlign} bg-accent-weak transition-colors`}
                          >
                            <InlineEditableCell
                              col={col}
                              referenceDataSource={referenceInputSources.get(col.field)}
                              maxLength={col.maxLength}
                              referenceContext={runtime?.getContext()}
                              value={editable ? editingValues[col.field] : row[col.field]}
                              displayValue={formatCellValue(
                                row[col.field],
                                col,
                                getDictLabel,
                                timezone,
                                formats,
                                referenceDisplayValue,
                              )}
                              isEditing={editable}
                              error={editErrors[col.field]}
                              onChange={(val) => {
                                setEditingValues((prev) => ({ ...prev, [col.field]: val }));
                                if (editErrors[col.field]) {
                                  setEditErrors((prev) => {
                                    const n = { ...prev };
                                    delete n[col.field];
                                    return n;
                                  });
                                }
                              }}
                              onKeyDown={(e) => handleEditKeyDown(e, col.field)}
                              dictOptions={dictOptions}
                              autoFocus={editableFields[0] === col.field}
                              t={t}
                            />
                          </td>
                        );
                      }

                      // --- Read mode ---
                      const referenceDisplayValue = getReferenceDisplayValue(row, col);
                      return (
                        <td
                          key={col.field}
                          className={`text-text-2 px-4 py-2 text-sm ${cellAlign}${
                            canInlineEdit ? 'hover:bg-hover cursor-pointer' : ''
                          }`}
                          onDoubleClick={canInlineEdit ? () => handleStartEdit(row) : undefined}
                        >
                          {formatCellValue(
                            row[col.field],
                            col,
                            getDictLabel,
                            timezone,
                            formats,
                            referenceDisplayValue,
                          )}
                        </td>
                      );
                    })}
                    {hasActions && (
                      <td className="px-4 py-2 text-center whitespace-nowrap">
                        {isEditingThis ? (
                          <>
                            <button
                              onClick={handleSaveEdit}
                              disabled={editSaving}
                              data-testid={`subtable-edit-save-${rowIndex}`}
                              className="text-accent hover:text-accent mr-2 text-xs disabled:opacity-50"
                            >
                              {editSaving
                                ? '...'
                                : t('common.save') !== 'common.save'
                                  ? t('common.save')
                                  : 'Save'}
                            </button>
                            <button
                              onClick={handleCancelEdit}
                              data-testid={`subtable-edit-cancel-${rowIndex}`}
                              className="text-text-2 hover:text-text-2 text-xs"
                            >
                              {t('common.cancel') !== 'common.cancel'
                                ? t('common.cancel')
                                : 'Cancel'}
                            </button>
                          </>
                        ) : (
                          <div className="flex items-center justify-center gap-2">
                            {configuredRowActions
                              .filter((button): button is ButtonConfig => {
                                if (!button) return false;
                                return isRowActionVisible(button, row);
                              })
                              .map((button) => {
                                const rowPid = getLegacyCompatibleRecordPid(row);
                                const loadingKey = `${rowPid || rowIndex}:${button.code}:${rowIndex}`;
                                const isLoading = rowActionLoadingKey === loadingKey;
                                const disabled =
                                  !!editingRowId || isLoading || isRowActionDisabled(button, row);
                                return (
                                  <button
                                    key={button.code}
                                    onClick={() => handleRowAction(button, row, rowIndex)}
                                    disabled={disabled}
                                    data-testid={`subtable-row-action-${button.code}-${rowIndex}`}
                                    className={`text-xs disabled:cursor-not-allowed disabled:opacity-40 ${
                                      button.danger || button.variant === 'danger'
                                        ? 'text-status-red hover:text-status-red'
                                        : 'text-accent hover:text-accent'
                                    }`}
                                    title={resolveButtonLabel(button)}
                                  >
                                    {isLoading ? '...' : resolveButtonLabel(button)}
                                  </button>
                                );
                              })}
                            {canInlineEdit && (
                              <button
                                onClick={() => handleStartEdit(row)}
                                disabled={!!editingRowId}
                                data-testid={`subtable-edit-${rowIndex}`}
                                className="text-accent hover:text-accent text-xs disabled:cursor-not-allowed disabled:opacity-30"
                                title={
                                  t('common.edit') !== 'common.edit' ? t('common.edit') : 'Edit'
                                }
                              >
                                <svg
                                  className="inline h-3.5 w-3.5"
                                  fill="none"
                                  viewBox="0 0 24 24"
                                  strokeWidth={1.5}
                                  stroke="currentColor"
                                >
                                  <path
                                    strokeLinecap="round"
                                    strokeLinejoin="round"
                                    d="M16.862 4.487l1.687-1.688a1.875 1.875 0 112.652 2.652L10.582 16.07a4.5 4.5 0 01-1.897 1.13L6 18l.8-2.685a4.5 4.5 0 011.13-1.897l8.932-8.931zm0 0L19.5 7.125M18 14v4.75A2.25 2.25 0 0115.75 21H5.25A2.25 2.25 0 013 18.75V8.25A2.25 2.25 0 015.25 6H10"
                                  />
                                </svg>
                              </button>
                            )}
                            {config.commands?.delete && (
                              <button
                                onClick={() =>
                                  handleDeleteRow(getLegacyCompatibleRecordPid(row) || '')
                                }
                                disabled={
                                  deletingId === getLegacyCompatibleRecordPid(row) || !!editingRowId
                                }
                                data-testid={`subtable-delete-${rowIndex}`}
                                className="text-status-red hover:text-status-red text-xs disabled:opacity-50"
                              >
                                {deletingId === getLegacyCompatibleRecordPid(row)
                                  ? '...'
                                  : t('common.delete') !== 'common.delete'
                                    ? t('common.delete')
                                    : 'Delete'}
                              </button>
                            )}
                          </div>
                        )}
                      </td>
                    )}
                  </SortableSubTableRow>
                );
              })}
            </DndSubTableWrapper>

            {/* Inline add form row */}
            {isEditable && isAdding && (
              <tr className="border-border bg-accent-weak border-t" data-testid="subtable-add-form">
                {effectiveColumns.map((col: EnrichedColumnConfig) => (
                  <td
                    key={col.field}
                    className={`px-4 py-2 ${col.align === 'right' ? 'text-right' : 'text-left'}`}
                  >
                    {col.editable === false ? (
                      <span className="text-text-3 text-xs">-</span>
                    ) : (
                      <div>
                        {referenceInputSources.has(col.field) ? (
                          <Select
                            name={col.field}
                            value={newRowData[col.field] ?? ''}
                            placeholder={resolveColumnLabel(col.field)}
                            size="small"
                            dataSource={referenceInputSources.get(col.field)}
                            context={runtime?.getContext()}
                            onChange={(value) => {
                              setNewRowData((previous) => ({ ...previous, [col.field]: value }));
                              setAddErrors((previous) => {
                                const next = { ...previous };
                                delete next[col.field];
                                return next;
                              });
                            }}
                          />
                        ) : (
                          <InlineEditableCell
                            col={col}
                            maxLength={col.maxLength}
                            value={newRowData[col.field] ?? ''}
                            displayValue=""
                            isEditing
                            testId={`subtable-add-${col.field}`}
                            placeholder={`${resolveColumnLabel(col.field)}${col.required ? ' *' : ''}`}
                            error={addErrors[col.field]}
                            showErrorTooltip={false}
                            dictOptions={col.dictCode ? (getDictItems(col.dictCode) || []).map(item => ({ value: item.value, label: item.label })) : undefined}
                            onChange={(value) => {
                              setNewRowData(previous => ({ ...previous, [col.field]: value }));
                              if (addErrors[col.field]) setAddErrors(previous => {
                                const next = { ...previous }; delete next[col.field]; return next;
                              });
                            }}
                            onKeyDown={(event) => {
                              if (event.key === 'Enter') handleAddRow();
                              if (event.key === 'Escape') {
                                setIsAdding(false); setNewRowData({}); setAddErrors({});
                              }
                            }}
                            t={t}
                          />
                        )}
                        {addErrors[col.field] && (
                          <p
                            className="text-status-red mt-0.5 text-xs"
                            data-testid={`subtable-error-${col.field}`}
                          >
                            {addErrors[col.field]}
                          </p>
                        )}
                      </div>
                    )}
                  </td>
                ))}
                <td className="px-4 py-2 text-center whitespace-nowrap">
                  <button
                    onClick={handleAddRow}
                    disabled={saving}
                    data-testid="subtable-save-btn"
                    className="text-accent hover:text-accent mr-2 text-xs disabled:opacity-50"
                  >
                    {saving
                      ? '...'
                      : t('common.save') !== 'common.save'
                        ? t('common.save')
                        : 'Save'}
                  </button>
                  <button
                    onClick={() => {
                      setIsAdding(false);
                      setNewRowData({});
                      setAddErrors({});
                    }}
                    data-testid="subtable-cancel-btn"
                    className="text-text-2 hover:text-text-2 text-xs"
                  >
                    {t('common.cancel') !== 'common.cancel' ? t('common.cancel') : 'Cancel'}
                  </button>
                </td>
              </tr>
            )}
            {addErrors._form && (
              <tr>
                <td
                  colSpan={totalCols}
                  className="text-status-red px-4 py-2 text-sm"
                  data-testid="subtable-form-error"
                >
                  {addErrors._form}
                </td>
              </tr>
            )}
            {editErrors._form && (
              <tr>
                <td
                  colSpan={totalCols}
                  className="text-status-red px-4 py-2 text-sm"
                  data-testid="subtable-edit-form-error"
                >
                  {editErrors._form}
                </td>
              </tr>
            )}
          </tbody>

          {/* Summary row — reactive during inline editing */}
          {config.summary && (
            <SubTableSummaryRow
              columns={effectiveColumns}
              rows={
                editingRowId
                  ? rows.map((r) =>
                      (r.pid || String(r.id)) === editingRowId ? { ...r, ...editingValues } : r,
                    )
                  : rows
              }
              summary={config.summary}
              locale={locale}
              t={t}
              extraColCount={extraColCount}
            />
          )}
        </table>
      )}

      {/* Add Line button */}
      {isEditable && config.commands?.create && !isAdding && displayRows.length > 0 && (
        <div className="border-border border-t">
          <button
            onClick={() => setIsAdding(true)}
            data-testid="subtable-add-row"
            className="text-text-3 hover:bg-hover hover:text-accent w-full py-2 text-xs transition-colors"
          >
            + {emptyStateActionLabel}
          </button>
        </div>
      )}
    </div>
  );
}
