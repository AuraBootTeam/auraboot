import type { ColumnConfig } from '~/framework/meta/schemas/types';

function hasFixedWidth(column: ColumnConfig, saved: Record<string, number>): boolean {
  const value = Number(saved[column.field] ?? column.width);
  return Number.isFinite(value) && value > 0;
}

/** Readable floors assume compact cell padding. Explicit author/user widths remain authoritative. */
export function defaultColumnFloor(column: ColumnConfig): number {
  if (column.presentation?.kind === 'object') return 176;
  if (column.valueType === 'currency' || column.format === 'currency') return 128;
  if (column.valueType === 'datetime') return 152;
  if (column.valueType === 'date') return 120;
  if (column.valueType === 'boolean') return 72;
  if (column.valueType === 'tag' || column.dictCode) return 80;
  if (column.valueType === 'progress' || column.valueType === 'rating') return 96;
  return 104;
}

/**
 * Distribute a real container deficit over default columns only. Never hide columns,
 * shrink saved widths, or squeeze values indefinitely merely to avoid scrolling.
 */
export function fitDefaultColumnWidths(
  columns: ColumnConfig[],
  baseWidths: Record<string, number>,
  savedWidths: Record<string, number>,
  availableDataWidth: number,
): Record<string, number> {
  const widths = { ...baseWidths };
  if (availableDataWidth <= 0) return widths;
  const total = columns.reduce((sum, column) => sum + widths[column.field], 0);
  const deficit = total - availableDataWidth;
  if (deficit <= 0) return widths;
  const flexible = columns.filter(column => !hasFixedWidth(column, savedWidths));
  const capacity = (column: ColumnConfig) =>
    Math.max(0, widths[column.field] - defaultColumnFloor(column));
  const totalCapacity = flexible.reduce((sum, column) => sum + capacity(column), 0);
  if (totalCapacity <= 0) return widths;
  const reduction = Math.min(deficit, totalCapacity);
  for (const column of flexible) {
    widths[column.field] = Math.max(Math.min(widths[column.field], defaultColumnFloor(column)),
      Math.floor(widths[column.field] - reduction * capacity(column) / totalCapacity));
  }
  return widths;
}
