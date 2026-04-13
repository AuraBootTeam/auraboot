import type { LayoutItem } from './types';
import {
  DEFAULT_GRID_COLS,
  DEFAULT_COL_SPAN,
  DEFAULT_ROW_SPAN,
  MIN_COL_SPAN,
  MIN_ROW_SPAN,
} from './layout-constants';

export interface RawLayoutItem {
  id: string;
  col?: number;
  colSpan?: number;
  rowSpan?: number;
  order?: number;
  row?: number;
}

export function normalizeLayoutItem(
  raw: RawLayoutItem,
  arrayIndex: number,
  cols: number = DEFAULT_GRID_COLS,
): LayoutItem {
  let colSpan = raw.colSpan ?? DEFAULT_COL_SPAN;
  colSpan = Math.max(MIN_COL_SPAN, Math.min(colSpan, cols));

  let col = Math.max(0, raw.col ?? 0);
  if (col + colSpan > cols) {
    col = cols - colSpan;
  }

  const rowSpan = Math.max(MIN_ROW_SPAN, raw.rowSpan ?? DEFAULT_ROW_SPAN);
  const order = raw.order ?? arrayIndex;
  const row = raw.row != null ? Math.max(0, raw.row) : undefined;

  const result: LayoutItem = { id: raw.id, col, colSpan, rowSpan, order };
  if (row !== undefined) {
    result.row = row;
  }
  return result;
}

export function normalizeLayoutItems(
  items: RawLayoutItem[],
  cols: number = DEFAULT_GRID_COLS,
): LayoutItem[] {
  if (items.length === 0) return [];

  const normalized = items.map((raw, i) => normalizeLayoutItem(raw, i, cols));

  // Re-number order to 0..n-1 based on relative ordering
  const indexed = normalized.map((item, origIndex) => ({ item, origIndex }));
  indexed.sort((a, b) => a.item.order - b.item.order);
  indexed.forEach(({ origIndex }, newOrder) => {
    normalized[origIndex] = { ...normalized[origIndex], order: newOrder };
  });

  return normalized;
}
