import { describe, it, expect } from 'vitest';
import { formatCellValue } from '../SubTableViewer';
import type { ColumnConfig } from '~/framework/meta/schemas/types';

const col = (overrides: Partial<ColumnConfig>): ColumnConfig =>
  ({ field: 'x', ...overrides }) as ColumnConfig;

describe('SubTableViewer formatCellValue — datetime', () => {
  it('converts a UTC datetime column (field ending _at) into the target timezone', () => {
    // 03:08 UTC == 11:08 Beijing — was previously rendered raw as the ISO string
    expect(
      formatCellValue(
        '2026-06-03T03:08:04.030+00:00',
        col({ field: 'bom_task_completed_at' }),
        undefined,
        'Asia/Shanghai',
      ),
    ).toBe('2026-06-03 11:08:04');
  });

  it('converts an explicit valueType=datetime column', () => {
    expect(
      formatCellValue(
        '2026-06-03T03:08:00Z',
        col({ field: 'whenever', valueType: 'datetime' }),
        undefined,
        'Asia/Shanghai',
      ),
    ).toBe('2026-06-03 11:08:00');
  });

  it('renders a date-typed column (field ending _date) with date format', () => {
    expect(
      formatCellValue(
        '2026-06-03T20:00:00Z',
        col({ field: 'ship_date' }),
        undefined,
        'Asia/Shanghai',
      ),
    ).toBe('2026-06-04'); // 20:00 UTC == 04:00 next day Beijing
  });

  it('leaves non-temporal strings untouched', () => {
    expect(formatCellValue('AAAR-6000', col({ field: 'code' }), undefined, 'Asia/Shanghai')).toBe(
      'AAAR-6000',
    );
  });

  it('still returns dash for null', () => {
    expect(
      formatCellValue(null, col({ field: 'bom_task_completed_at' }), undefined, 'Asia/Shanghai'),
    ).toBe('-');
  });
});
