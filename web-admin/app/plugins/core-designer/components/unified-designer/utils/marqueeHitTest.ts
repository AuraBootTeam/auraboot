/**
 * Geometric box-select (marquee) hit testing — pure, DOM-free.
 *
 * The canvas marquee draws a selection rectangle while the user drags across the
 * empty canvas. On pointer-up we need to know which blocks the rectangle covers.
 * This module isolates that geometry from the React component so it can be unit
 * tested with reliable jsdom-free math (the rendering side — pointer wiring, the
 * absolutely-positioned overlay div — is covered by the real-stack E2E golden,
 * which jsdom cannot exercise because it has zero layout geometry).
 *
 * Coordinates are viewport-relative pixels (the same space as
 * `getBoundingClientRect()` and `PointerEvent.clientX/clientY`), so the caller
 * can feed raw client coordinates and `rect` snapshots straight in.
 */

/** A rectangle in viewport-pixel space (matches DOMRect's read fields). */
export interface PixelRect {
  left: number;
  top: number;
  right: number;
  bottom: number;
}

/** A block's id paired with its on-screen bounding rectangle. */
export interface BlockRect {
  blockId: string;
  rect: PixelRect;
}

/**
 * Normalize a marquee defined by its start and current pointer positions into a
 * well-formed rectangle (left ≤ right, top ≤ bottom) regardless of drag
 * direction (the user may drag up-left, down-right, etc.).
 */
export function rectFromPoints(
  start: { x: number; y: number },
  current: { x: number; y: number },
): PixelRect {
  return {
    left: Math.min(start.x, current.x),
    top: Math.min(start.y, current.y),
    right: Math.max(start.x, current.x),
    bottom: Math.max(start.y, current.y),
  };
}

/**
 * True when two rectangles overlap (share a positive-area region). Edge-touching
 * alone does NOT count as an overlap, and a zero-area rectangle (a click that
 * never moved — `left === right` or `top === bottom`) never overlaps anything.
 * That makes a degenerate marquee select nothing, which is the intended
 * empty-marquee behaviour even when the click lands inside a block.
 */
export function rectsIntersect(a: PixelRect, b: PixelRect): boolean {
  if (a.right <= a.left || a.bottom <= a.top || b.right <= b.left || b.bottom <= b.top) {
    return false;
  }
  return a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top;
}

/**
 * Return the ids of every block whose bounding rectangle intersects the marquee
 * rectangle. Partial overlap counts (industry-standard marquee semantics: a
 * block is selected if the box touches any part of it, not only when fully
 * enclosed). The result preserves input order so selection is deterministic.
 *
 * A degenerate (zero-area) marquee — e.g. a plain click on the empty canvas —
 * intersects nothing, so the caller can treat an empty result as "clear /
 * leave selection unchanged" without special-casing the click case.
 */
export function blocksWithinMarquee(marquee: PixelRect, blocks: BlockRect[]): string[] {
  const selected: string[] = [];
  for (const { blockId, rect } of blocks) {
    if (rectsIntersect(marquee, rect)) {
      selected.push(blockId);
    }
  }
  return selected;
}
