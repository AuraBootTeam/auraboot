interface RglLayoutItem {
  i: string;
  x: number;
  y: number;
  w: number;
  h: number;
}

interface DslLayoutItem {
  id: string;
  col: number;
  colSpan: number;
  rowSpan: number;
  order: number;
}

/**
 * Converts react-grid-layout positions to DSL layout items.
 *
 * DSL never stores y. Order is derived from y-then-x sorting, normalized to 0..n-1.
 */
export function rglToDslLayout(rglLayout: RglLayoutItem[]): DslLayoutItem[] {
  if (rglLayout.length === 0) return [];

  const sorted = [...rglLayout].sort((a, b) => a.y - b.y || a.x - b.x);

  return sorted.map((item, index) => ({
    id: item.i,
    col: item.x,
    colSpan: item.w,
    rowSpan: item.h,
    order: index,
  }));
}
