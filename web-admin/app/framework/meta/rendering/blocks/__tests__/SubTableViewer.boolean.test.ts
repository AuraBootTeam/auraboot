import { describe, it, expect } from 'vitest';
import { formatCellValue } from '../SubTableViewer';
import type { ColumnConfig } from '~/framework/meta/schemas/types';

const col = (overrides: Partial<ColumnConfig>): ColumnConfig =>
  ({ field: 'x', ...overrides }) as ColumnConfig;

// A sub-table cell of a boolean read `Yes` and `No` on a Chinese stack: the CRM account detail
// lists its contacts with a 主要联系人 column (CRM journey walkthrough of 5501, 2026-11-26).
describe('SubTableViewer formatCellValue — boolean', () => {
  it('reads a boolean in Chinese for a Chinese locale', () => {
    expect(formatCellValue(true, col({ cellRenderer: 'boolean' }), undefined, undefined, undefined, undefined, 'zh-CN')).toBe('是');
    expect(formatCellValue(false, col({ cellRenderer: 'boolean' }), undefined, undefined, undefined, undefined, 'zh-CN')).toBe('否');
  });

  it('reads it in English for every other locale and for no locale', () => {
    expect(formatCellValue(true, col({}), undefined, undefined, undefined, undefined, 'en-US')).toBe('Yes');
    expect(formatCellValue(false, col({}), undefined, undefined, undefined, undefined, 'en-US')).toBe('No');
    expect(formatCellValue(true, col({}))).toBe('Yes');
    expect(formatCellValue(false, col({}))).toBe('No');
  });

  it('still shows an em-dash for a missing value', () => {
    expect(formatCellValue(null, col({}), undefined, undefined, undefined, undefined, 'zh-CN')).toBe('-');
    expect(formatCellValue(undefined, col({}), undefined, undefined, undefined, undefined, 'zh-CN')).toBe('-');
  });
});
