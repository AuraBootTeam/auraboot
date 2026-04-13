import type { LayoutItem, ResolvedLayoutItem } from './types';

export function autoFlow(
  items: LayoutItem[],
  cols: number,
): ResolvedLayoutItem[] {
  if (items.length === 0) return [];

  const locked = items.filter((i) => i.row != null);
  const auto = [...items.filter((i) => i.row == null)].sort(
    (a, b) => a.order - b.order,
  );

  const occupied = new Map<number, Set<number>>();

  function markOccupied(
    col: number,
    y: number,
    colSpan: number,
    rowSpan: number,
  ): void {
    for (let r = y; r < y + rowSpan; r++) {
      if (!occupied.has(r)) occupied.set(r, new Set());
      for (let c = col; c < col + colSpan; c++) {
        occupied.get(r)!.add(c);
      }
    }
  }

  function canPlace(
    col: number,
    y: number,
    colSpan: number,
    rowSpan: number,
  ): boolean {
    for (let r = y; r < y + rowSpan; r++) {
      const s = occupied.get(r);
      if (!s) continue;
      for (let c = col; c < col + colSpan; c++) {
        if (s.has(c)) return false;
      }
    }
    return true;
  }

  const results: ResolvedLayoutItem[] = [];

  // Place locked items first
  for (const item of locked) {
    markOccupied(item.col, item.row!, item.colSpan, item.rowSpan);
    results.push({ ...item, y: item.row! });
  }

  // Place auto items using vertical scan
  for (const item of auto) {
    let y = 0;
    while (!canPlace(item.col, y, item.colSpan, item.rowSpan) && y < 1000) {
      y++;
    }
    markOccupied(item.col, y, item.colSpan, item.rowSpan);
    results.push({ ...item, y });
  }

  return results;
}
