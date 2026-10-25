import type { DslBlockV3 } from '../../types';
import type { RuntimeSelectionContextValue } from './runtimeContexts';
import { isRuntimeFieldValueEmpty } from './runtimeFormLogic';
import { getStringProp, isRecord } from './runtimePresentation';





export function getRuntimeTableColumnBlocks(block: DslBlockV3): DslBlockV3[] {
  return block.blocks?.filter((child) => child.blockType === 'column') ?? [];
}

export function getRuntimeTableRowActionBlocks(block: DslBlockV3): DslBlockV3[] {
  return (
    block.blocks?.filter(
      (child) => child.blockType === 'action' || child.region === 'row-actions',
    ) ?? []
  );
}

export function getRuntimeTableRows(block: DslBlockV3): Array<Record<string, unknown>> {
  const rows = Array.isArray(block.props?.rows)
    ? block.props.rows
    : Array.isArray(block.dataSource?.rows)
      ? block.dataSource.rows
      : [];
  return rows.filter(isRecord);
}

export function applyRuntimeListFilters(
  rows: Array<Record<string, unknown>>,
  listContext: RuntimeSelectionContextValue | null,
): Array<Record<string, unknown>> {
  if (!listContext?.filterBlocks.length) return rows;
  const activeFilters = listContext.filterBlocks.flatMap((filterBlock) => {
    const field = filterBlock.field || filterBlock.id;
    const value = listContext.filterValues[field];
    return isRuntimeFieldValueEmpty(value) ? [] : [{ filterBlock, field, value }];
  });
  if (!activeFilters.length) return rows;

  return rows.filter((row) =>
    activeFilters.every(({ filterBlock, field, value }) =>
      matchesRuntimeListFilter(row[field], value, getStringProp(filterBlock.props?.operator)),
    ),
  );
}

function matchesRuntimeListFilter(
  rawCellValue: unknown,
  rawFilterValue: unknown,
  operator: string,
): boolean {
  const cellValue = rawCellValue == null ? '' : String(rawCellValue);
  const filterValue = rawFilterValue == null ? '' : String(rawFilterValue);
  const normalizedOperator = operator || 'contains';

  if (normalizedOperator === 'equals') return cellValue === filterValue;
  if (normalizedOperator === 'gt') return Number(cellValue) > Number(filterValue);
  if (normalizedOperator === 'lt') return Number(cellValue) < Number(filterValue);
  if (normalizedOperator === 'between') {
    const [min, max] = filterValue.split(',').map((item) => Number(item.trim()));
    const numericValue = Number(cellValue);
    return Number.isFinite(min) && Number.isFinite(max) && numericValue >= min && numericValue <= max;
  }
  return cellValue.toLowerCase().includes(filterValue.toLowerCase());
}

export function getRuntimeTableColumns(
  block: DslBlockV3,
  rows: Array<Record<string, unknown>>,
  columnBlocks = getRuntimeTableColumnBlocks(block),
  hasConfiguredColumns = Boolean(block.blocks?.some((child) => child.blockType === 'column')),
): string[] {
  const configuredColumns = columnBlocks.map((child) => child.field || child.id).filter(Boolean);
  if (hasConfiguredColumns) return configuredColumns;
  return Object.keys(rows[0] ?? {});
}

export function getRuntimeRowId(row: Record<string, unknown>, index: number): string {
                            const id = row.pid ?? row.key ?? row._id ?? index;
  return String(id);
}
