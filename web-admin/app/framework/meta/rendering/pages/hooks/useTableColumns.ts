/**
 * useTableColumns — column computation + SavedView column overlay
 *
 * Reads columns from schema's table block and merges SavedView
 * column visibility/order/width preferences.
 */

import { useMemo } from 'react';
import type { ColumnConfig, BlockConfig, UnifiedSchema } from '~/framework/meta/schemas/types';
import type { ViewConfig, ColumnConfig as SavedViewColumnConfig } from '~/smart/types/savedView';

interface UseTableColumnsOptions {
  schema: UnifiedSchema | null;
  viewConfig?: ViewConfig | null;
}

interface UseTableColumnsResult {
  /** All columns from DSL schema */
  allColumns: ColumnConfig[];
  /** Visible columns (after SavedView filter) */
  visibleColumns: ColumnConfig[];
  /** The table block from schema */
  tableBlock: BlockConfig | null;
}

export function useTableColumns({
  schema,
  viewConfig,
}: UseTableColumnsOptions): UseTableColumnsResult {
  return useMemo(() => {
    if (!schema?.blocks) {
      return { allColumns: [], visibleColumns: [], tableBlock: null };
    }

    const tableBlock =
      schema.blocks.find((block) => block.blockType === 'table') ?? null;

    if (!tableBlock) {
      return { allColumns: [], visibleColumns: [], tableBlock: null };
    }

    const rawColumns = tableBlock.table?.columns || tableBlock.columns;
    const allColumns: ColumnConfig[] = Array.isArray(rawColumns) ? rawColumns : [];

    // Apply SavedView column settings
    if (viewConfig?.columns && viewConfig.columns.length > 0) {
      const viewColMap = new Map<string, SavedViewColumnConfig>();
      viewConfig.columns.forEach((vc) => viewColMap.set(vc.fieldCode, vc));

      const visibleColumns = allColumns
        .filter((col) => {
          const vc = viewColMap.get(col.field);
          return vc ? vc.visible !== false : true;
        })
        .sort((a, b) => {
          const aOrder = viewColMap.get(a.field)?.order ?? 999;
          const bOrder = viewColMap.get(b.field)?.order ?? 999;
          return aOrder - bOrder;
        })
        .map((col) => {
          const vc = viewColMap.get(col.field);
          if (vc?.width) {
            return { ...col, width: vc.width };
          }
          return col;
        });

      return { allColumns, visibleColumns, tableBlock };
    }

    return { allColumns, visibleColumns: allColumns, tableBlock };
  }, [schema, viewConfig]);
}
