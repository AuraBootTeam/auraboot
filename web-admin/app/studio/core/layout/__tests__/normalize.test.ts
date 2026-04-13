import { describe, it, expect } from 'vitest';
import { normalizeLayoutItem, normalizeLayoutItems } from '../normalize';
import { DEFAULT_GRID_COLS, DEFAULT_COL_SPAN, DEFAULT_ROW_SPAN } from '../layout-constants';

describe('normalizeLayoutItem', () => {
  it('fills defaults for minimal input', () => {
    const result = normalizeLayoutItem({ id: 'a' }, 0);
    expect(result).toEqual({
      id: 'a',
      col: 0,
      colSpan: DEFAULT_COL_SPAN,
      rowSpan: DEFAULT_ROW_SPAN,
      order: 0,
    });
  });

  it('preserves valid values', () => {
    const result = normalizeLayoutItem(
      { id: 'b', col: 3, colSpan: 6, rowSpan: 2, order: 5 },
      1,
    );
    expect(result).toEqual({
      id: 'b',
      col: 3,
      colSpan: 6,
      rowSpan: 2,
      order: 5,
    });
  });

  it('clamps col < 0 to 0', () => {
    const result = normalizeLayoutItem({ id: 'c', col: -3 }, 0);
    expect(result.col).toBe(0);
  });

  it('clamps colSpan < 1 to MIN_COL_SPAN', () => {
    const result = normalizeLayoutItem({ id: 'd', colSpan: 0 }, 0);
    expect(result.colSpan).toBe(1);
  });

  it('clamps colSpan > cols to cols', () => {
    const result = normalizeLayoutItem({ id: 'e', colSpan: 20 }, 0, 12);
    expect(result.colSpan).toBe(12);
  });

  it('adjusts col when col + colSpan > cols (preserves colSpan)', () => {
    const result = normalizeLayoutItem({ id: 'f', col: 10, colSpan: 6 }, 0, 12);
    expect(result.colSpan).toBe(6);
    expect(result.col).toBe(6); // 12 - 6 = 6
  });

  it('clamps rowSpan < 1 to MIN_ROW_SPAN', () => {
    const result = normalizeLayoutItem({ id: 'g', rowSpan: 0 }, 0);
    expect(result.rowSpan).toBe(1);
  });

  it('uses array index as order when order not provided', () => {
    const result = normalizeLayoutItem({ id: 'h' }, 7);
    expect(result.order).toBe(7);
  });

  it('preserves row when provided', () => {
    const result = normalizeLayoutItem({ id: 'i', row: 3 }, 0);
    expect(result.row).toBe(3);
  });

  it('clamps row < 0 to 0', () => {
    const result = normalizeLayoutItem({ id: 'j', row: -2 }, 0);
    expect(result.row).toBe(0);
  });
});

describe('normalizeLayoutItems', () => {
  it('normalizes order to 0..n-1', () => {
    const items = [
      { id: 'a', order: 10 },
      { id: 'b', order: 5 },
      { id: 'c', order: 20 },
    ];
    const result = normalizeLayoutItems(items);
    const orders = result.map((r) => r.order);
    expect(orders).toEqual([1, 0, 2]);
  });

  it('handles items without order (uses array index)', () => {
    const items = [{ id: 'a' }, { id: 'b' }, { id: 'c' }];
    const result = normalizeLayoutItems(items);
    const orders = result.map((r) => r.order);
    expect(orders).toEqual([0, 1, 2]);
  });

  it('returns empty array for empty input', () => {
    expect(normalizeLayoutItems([])).toEqual([]);
  });
});
