import { describe, expect, it } from 'vitest';
import { fitDefaultColumnWidths } from '../responsiveColumnWidths';
import type { ColumnConfig } from '~/framework/meta/schemas/types';

const columns: ColumnConfig[] = [
  { field: 'name', presentation: { kind: 'object' } },
  { field: 'code' }, { field: 'customer' }, { field: 'owner' },
  { field: 'status', valueType: 'tag' },
  { field: 'budget', valueType: 'currency' }, { field: 'progress' },
];
const base = { name: 280, code: 160, customer: 160, owner: 160, status: 160, budget: 160, progress: 160 };
const total = (widths: Record<string, number>) => Object.values(widths).reduce((sum, width) => sum + width, 0);

describe('responsive default column widths', () => {
  it('fits the seven business columns into an 808px data area without dropping a field', () => {
    const widths = fitDefaultColumnWidths(columns, base, {}, 808);
    expect(Object.keys(widths)).toEqual(Object.keys(base));
    expect(total(widths)).toBeLessThanOrEqual(808);
    expect(widths.name).toBeGreaterThanOrEqual(176);
    expect(widths.budget).toBeGreaterThanOrEqual(128);
    expect(widths.progress).toBeGreaterThanOrEqual(104);
    expect(base.budget).toBe(160);
  });

  it('preserves exact DSL and personalized widths even when scrolling remains necessary', () => {
    const configured = columns.map(column => column.field === 'name' ? { ...column, width: 300 } : column);
    const widths = fitDefaultColumnWidths(configured, { ...base, name: 300, budget: 220 }, { budget: 220 }, 700);
    expect(widths.name).toBe(300);
    expect(widths.budget).toBe(220);
    expect(total(widths)).toBeGreaterThan(700);
    expect(widths.status).toBeGreaterThanOrEqual(80);
  });

  it('does not shrink a wide or not-yet-measured table', () => {
    expect(fitDefaultColumnWidths(columns, base, {}, 1500)).toEqual(base);
    expect(fitDefaultColumnWidths(columns, base, {}, 0)).toEqual(base);
  });

  it('stops at readable floors for an impossibly narrow viewport', () => {
    const widths = fitDefaultColumnWidths(columns, base, {}, 200);
    expect(widths.budget).toBe(128);
    expect(total(widths)).toBeGreaterThan(200);
  });
});
