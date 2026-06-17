import { describe, expect, it } from 'vitest';
import {
  blocksWithinMarquee,
  rectFromPoints,
  rectsIntersect,
  type BlockRect,
  type PixelRect,
} from '../utils/marqueeHitTest';

const rect = (left: number, top: number, right: number, bottom: number): PixelRect => ({
  left,
  top,
  right,
  bottom,
});

describe('marqueeHitTest — rectFromPoints', () => {
  it('normalizes a down-right drag', () => {
    expect(rectFromPoints({ x: 10, y: 20 }, { x: 110, y: 220 })).toEqual({
      left: 10,
      top: 20,
      right: 110,
      bottom: 220,
    });
  });

  it('normalizes an up-left drag (current before start)', () => {
    expect(rectFromPoints({ x: 110, y: 220 }, { x: 10, y: 20 })).toEqual({
      left: 10,
      top: 20,
      right: 110,
      bottom: 220,
    });
  });

  it('normalizes a mixed-direction drag (down-left)', () => {
    expect(rectFromPoints({ x: 200, y: 30 }, { x: 40, y: 300 })).toEqual({
      left: 40,
      top: 30,
      right: 200,
      bottom: 300,
    });
  });

  it('produces a zero-area rect for a stationary point (a click)', () => {
    expect(rectFromPoints({ x: 50, y: 50 }, { x: 50, y: 50 })).toEqual({
      left: 50,
      top: 50,
      right: 50,
      bottom: 50,
    });
  });
});

describe('marqueeHitTest — rectsIntersect', () => {
  const a = rect(0, 0, 100, 100);

  it('detects partial overlap', () => {
    expect(rectsIntersect(a, rect(80, 80, 200, 200))).toBe(true);
  });

  it('detects full containment of b inside a', () => {
    expect(rectsIntersect(a, rect(10, 10, 40, 40))).toBe(true);
  });

  it('detects a containing the marquee (b encloses a)', () => {
    expect(rectsIntersect(a, rect(-50, -50, 500, 500))).toBe(true);
  });

  it('returns false for a fully disjoint rect', () => {
    expect(rectsIntersect(a, rect(200, 200, 300, 300))).toBe(false);
  });

  it('returns false for edge-touching rects (shared border, no area)', () => {
    // b starts exactly where a ends on x — they share the x=100 edge only.
    expect(rectsIntersect(a, rect(100, 0, 200, 100))).toBe(false);
  });

  it('returns false for a zero-area marquee even when it sits inside a block', () => {
    expect(rectsIntersect(rect(50, 50, 50, 50), a)).toBe(false);
  });
});

describe('marqueeHitTest — blocksWithinMarquee', () => {
  const blocks: BlockRect[] = [
    { blockId: 'A', rect: rect(0, 0, 100, 100) },
    { blockId: 'B', rect: rect(120, 0, 220, 100) },
    { blockId: 'C', rect: rect(0, 120, 100, 220) },
  ];

  it('selects a single block fully covered by the marquee', () => {
    expect(blocksWithinMarquee(rect(-10, -10, 110, 110), blocks)).toEqual(['A']);
  });

  it('selects multiple blocks a wide marquee partially overlaps', () => {
    // A wide horizontal band crossing A and B (touching the top of both).
    expect(blocksWithinMarquee(rect(50, 10, 200, 60), blocks)).toEqual(['A', 'B']);
  });

  it('selects all blocks when the marquee encloses every block', () => {
    expect(blocksWithinMarquee(rect(-50, -50, 500, 500), blocks)).toEqual(['A', 'B', 'C']);
  });

  it('selects nothing when the marquee misses every block', () => {
    expect(blocksWithinMarquee(rect(300, 300, 400, 400), blocks)).toEqual([]);
  });

  it('selects nothing for a degenerate (zero-area) marquee — a plain click', () => {
    expect(blocksWithinMarquee(rect(50, 50, 50, 50), blocks)).toEqual([]);
  });

  it('preserves input order in the result', () => {
    // Marquee covers C then B then A by geometry, but order follows the input list.
    expect(blocksWithinMarquee(rect(-50, -50, 500, 500), blocks)).toEqual(['A', 'B', 'C']);
  });

  it('counts partial vertical overlap (marquee clips only the bottom of A)', () => {
    expect(blocksWithinMarquee(rect(10, 90, 90, 130), blocks)).toEqual(['A', 'C']);
  });
});
