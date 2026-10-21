import { useCallback, useMemo } from 'react';
import type { BlockConfig } from '~/framework/meta/schemas/types';
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';
import { fetchResult } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';
import { useTreeData } from '~/framework/meta/hooks/useTreeData';
import { isDescendant } from '~/framework/meta/components/subtable/dndUtils';
import type { ResolvedSubTableViewerProps } from './subTableTypes';
import type { useSubTableColumns } from './useSubTableColumns';
import type { useSubTableState } from './useSubTableState';
import type { useSubTableData } from './useSubTableData';

type UseSubTableTreeSortingArgs = Pick<
  ResolvedSubTableViewerProps,
  'config' | 'parentRecordData' | 'parentRecordPid' | 'isEditable' | 'locale' | 't' | 'token'
> &
  Pick<ReturnType<typeof useSubTableColumns>, 'canInlineEdit' | 'effectiveColumns'> &
  Pick<ReturnType<typeof useSubTableState>, 'rows' | 'setRows'> &
  Pick<ReturnType<typeof useSubTableData>, 'reloadRows'>;

export function useSubTableTreeSorting({
  config,
  parentRecordData,
  parentRecordPid,
  isEditable,
  canInlineEdit,
  effectiveColumns,
  locale,
  t,
  rows,
  setRows,
  token,
  reloadRows,
}: UseSubTableTreeSortingArgs) {
  const configuredRowActions = config.actions ?? config.rowActions ?? [];
  const configuredToolbarActions = useMemo(
    () => config.toolbarActions ?? [],
    [config.toolbarActions],
  );
  const toolbarBlock = useMemo<BlockConfig | null>(() => {
    if (configuredToolbarActions.length === 0) return null;
    return {
      id: `subtable-toolbar-${config.childModel}`,
      blockType: 'toolbar',
      buttons: configuredToolbarActions,
      record: {
        ...(parentRecordData || {}),
        pid: parentRecordPid,
      },
    } as BlockConfig;
  }, [configuredToolbarActions, config.childModel, parentRecordData, parentRecordPid]);
  const hasConfiguredRowActions = configuredRowActions.length > 0;
  const hasActions =
    hasConfiguredRowActions ||
    (isEditable && (config.commands?.create || config.commands?.delete || canInlineEdit));
  const isSortable = config.sortable && isEditable;
  const sortField = config.sortField || 'sort_order';
  const totalCols = effectiveColumns.length + (hasActions ? 1 : 0) + (isSortable ? 1 : 0);
  const extraColCount = (hasActions ? 1 : 0) + (isSortable ? 1 : 0);
  const emptyStateTitle = config.emptyState?.title
    ? getLocalizedText(config.emptyState.title, locale, t)
    : t('common.noData') || 'No data';
  const emptyStateDescription = config.emptyState?.description
    ? getLocalizedText(config.emptyState.description, locale, t)
    : '';
  const emptyStateActionLabel = config.emptyState?.actionLabel
    ? getLocalizedText(config.emptyState.actionLabel, locale, t)
    : t('common.addLine') !== 'common.addLine'
      ? t('common.addLine')
      : '添加明细';

  // Tree data management
  const { visibleRows: treeRows, toggleExpand } = useTreeData(rows, config.treeConfig);
  const displayRows = config.treeConfig ? treeRows : rows;

  // DnD item IDs
  const dndItems = useMemo(
    () => displayRows.map((r) => r.pid || String(r.id || '')),
    [displayRows],
  );

  // DnD drop handler → batch API update
  const handleDragEnd = useCallback(
    async (activeId: string, overId: string, _deltaX: number) => {
      if (!isSortable) return;

      // Simple flat sort: swap positions
      const oldIndex = displayRows.findIndex((r) => (r.pid || r.id) === activeId);
      const newIndex = displayRows.findIndex((r) => (r.pid || r.id) === overId);
      if (oldIndex === -1 || newIndex === -1 || oldIndex === newIndex) return;

      // Tree mode: check for cycle
      if (config.treeConfig) {
        if (isDescendant(activeId, overId, treeRows)) return;
      }

      // Reorder locally
      const reordered = [...displayRows];
      const [moved] = reordered.splice(oldIndex, 1);
      reordered.splice(newIndex, 0, moved);

      // Compute batch update payload
      const updates = reordered.map((r, i) => ({
        pid: r.pid || r.id,
        [sortField]: (i + 1) * 1000,
      }));

      // Optimistic update
      setRows(reordered.map((r, i) => ({ ...r, [sortField]: (i + 1) * 1000 })));

      // Persist via batch API
      try {
        const result = await fetchResult<{ failed?: number; errors?: string[] }>(
          `/api/dynamic/${config.childModel}/batch`,
          {
            method: 'put',
            token,
            params: updates,
          },
        );
        if (!ResultHelper.isSuccess(result) || (result.data?.failed ?? 0) > 0) {
          throw new Error(
            result.data?.errors?.join('; ') ||
              result.message ||
              result.desc ||
              'Failed to update sort order',
          );
        }
      } catch (err) {
        console.error('[SubTableViewer] Batch sort update failed:', err);
        reloadRows(); // Rollback by reloading
      }
    },
    [isSortable, displayRows, treeRows, config, sortField, token, reloadRows, setRows],
  );
  return {
    configuredRowActions,
    toolbarBlock,
    hasActions,
    isSortable,
    totalCols,
    extraColCount,
    emptyStateTitle,
    emptyStateDescription,
    emptyStateActionLabel,
    toggleExpand,
    displayRows,
    dndItems,
    handleDragEnd,
  };
}
