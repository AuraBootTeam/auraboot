import { describe, it, expect } from 'vitest';
import { autoFlow } from '../auto-flow';
import type { LayoutItem } from '../types';

function item(overrides: Partial<LayoutItem> & { id: string }): LayoutItem {
  return {
    col: 0,
    colSpan: 12,
    rowSpan: 1,
    order: 0,
    ...overrides,
  };
}

describe('autoFlow', () => {
  it('places a single full-width item at y=0', () => {
    const result = autoFlow([item({ id: 'a', order: 0 })], 12);
    expect(result).toHaveLength(1);
    expect(result[0].y).toBe(0);
  });

  it('stacks full-width items vertically', () => {
    const items = [
      item({ id: 'a', order: 0 }),
      item({ id: 'b', order: 1 }),
    ];
    const result = autoFlow(items, 12);
    expect(result.find((r) => r.id === 'a')!.y).toBe(0);
    expect(result.find((r) => r.id === 'b')!.y).toBe(1);
  });

  it('places two half-width items on the same row', () => {
    const items = [
      item({ id: 'a', col: 0, colSpan: 6, order: 0 }),
      item({ id: 'b', col: 6, colSpan: 6, order: 1 }),
    ];
    const result = autoFlow(items, 12);
    expect(result.find((r) => r.id === 'a')!.y).toBe(0);
    expect(result.find((r) => r.id === 'b')!.y).toBe(0);
  });

  it('wraps to next row on overflow', () => {
    const items = [
      item({ id: 'a', col: 0, colSpan: 8, order: 0 }),
      item({ id: 'b', col: 0, colSpan: 8, order: 1 }),
    ];
    const result = autoFlow(items, 12);
    expect(result.find((r) => r.id === 'a')!.y).toBe(0);
    expect(result.find((r) => r.id === 'b')!.y).toBe(1);
  });

  it('handles rowSpan > 1', () => {
    const items = [
      item({ id: 'a', col: 0, colSpan: 6, rowSpan: 2, order: 0 }),
      item({ id: 'b', col: 0, colSpan: 6, order: 1 }),
    ];
    const result = autoFlow(items, 12);
    expect(result.find((r) => r.id === 'a')!.y).toBe(0);
    // b starts at col=0 but a occupies rows 0-1 at cols 0-5, so b goes to y=2
    expect(result.find((r) => r.id === 'b')!.y).toBe(2);
  });

  it('respects locked row', () => {
    const items = [
      item({ id: 'a', col: 0, colSpan: 12, order: 0, row: 3 }),
      item({ id: 'b', col: 0, colSpan: 12, order: 1 }),
    ];
    const result = autoFlow(items, 12);
    expect(result.find((r) => r.id === 'a')!.y).toBe(3);
    expect(result.find((r) => r.id === 'b')!.y).toBe(0);
  });

  it('sorts by order', () => {
    const items = [
      item({ id: 'b', col: 0, colSpan: 12, order: 1 }),
      item({ id: 'a', col: 0, colSpan: 12, order: 0 }),
    ];
    const result = autoFlow(items, 12);
    // a has order=0, placed first at y=0; b has order=1, placed at y=1
    expect(result.find((r) => r.id === 'a')!.y).toBe(0);
    expect(result.find((r) => r.id === 'b')!.y).toBe(1);
  });

  it('returns empty array for empty input', () => {
    expect(autoFlow([], 12)).toEqual([]);
  });

  it('places three columns side by side', () => {
    const items = [
      item({ id: 'a', col: 0, colSpan: 4, order: 0 }),
      item({ id: 'b', col: 4, colSpan: 4, order: 1 }),
      item({ id: 'c', col: 8, colSpan: 4, order: 2 }),
    ];
    const result = autoFlow(items, 12);
    expect(result.every((r) => r.y === 0)).toBe(true);
  });
});
