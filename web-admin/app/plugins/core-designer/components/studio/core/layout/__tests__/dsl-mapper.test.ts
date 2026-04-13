import { describe, it, expect } from 'vitest';
import { rglToDslLayout } from '../dsl-mapper';

describe('rglToDslLayout', () => {
  it('converts a single full-width block', () => {
    const result = rglToDslLayout([{ i: 'a', x: 0, y: 0, w: 12, h: 1 }]);
    expect(result).toEqual([{ id: 'a', col: 0, colSpan: 12, rowSpan: 1, order: 0 }]);
  });

  it('converts two side-by-side blocks', () => {
    const result = rglToDslLayout([
      { i: 'a', x: 0, y: 0, w: 6, h: 1 },
      { i: 'b', x: 6, y: 0, w: 6, h: 1 },
    ]);
    expect(result[0]).toMatchObject({ id: 'a', col: 0, colSpan: 6 });
    expect(result[1]).toMatchObject({ id: 'b', col: 6, colSpan: 6 });
    expect(result[0].order).toBe(0);
    expect(result[1].order).toBe(1);
  });

  it('converts stacked blocks with correct order', () => {
    const result = rglToDslLayout([
      { i: 'top', x: 0, y: 0, w: 12, h: 1 },
      { i: 'bottom', x: 0, y: 1, w: 12, h: 1 },
    ]);
    expect(result.find(r => r.id === 'top')!.order).toBe(0);
    expect(result.find(r => r.id === 'bottom')!.order).toBe(1);
  });

  it('preserves rowSpan from h', () => {
    const result = rglToDslLayout([{ i: 'a', x: 0, y: 0, w: 12, h: 3 }]);
    expect(result[0].rowSpan).toBe(3);
  });

  it('does not include row in output', () => {
    const result = rglToDslLayout([{ i: 'a', x: 0, y: 5, w: 12, h: 1 }]);
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    expect((result[0] as any).row).toBeUndefined();
  });

  it('normalizes order to 0..n-1 sorted by y then x', () => {
    const result = rglToDslLayout([
      { i: 'c', x: 0, y: 2, w: 12, h: 1 },
      { i: 'a', x: 0, y: 0, w: 6, h: 1 },
      { i: 'b', x: 6, y: 0, w: 6, h: 1 },
    ]);
    expect(result.find(r => r.id === 'a')!.order).toBe(0);
    expect(result.find(r => r.id === 'b')!.order).toBe(1);
    expect(result.find(r => r.id === 'c')!.order).toBe(2);
  });

  it('returns empty array for empty input', () => {
    expect(rglToDslLayout([])).toEqual([]);
  });
});
