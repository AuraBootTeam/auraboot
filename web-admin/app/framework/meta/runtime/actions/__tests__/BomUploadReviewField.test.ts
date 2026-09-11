import { describe, expect, it } from 'vitest';
import { buildBomReviewColumns } from '../BomUploadReviewField';

describe('BOM review spreadsheet content bounds', () => {
  it('does not render thousands of styled but empty trailing columns', () => {
    const header = Array(16364).fill('');
    header[0] = 'Part Number';
    header[4] = 'Quantity';
    const data = Array(16364).fill('');
    data[0] = 'R-0603';
    data[4] = '2';
    const columns = buildBomReviewColumns([header, data], 0);
    expect(columns).toHaveLength(5);
    expect(columns[4]).toMatchObject({ index: 4, role: 'quantity', selected: true });
    expect(columns[1]).toMatchObject({ index: 1, role: 'ignore', selected: false });
  });
  it('retains data beyond the preview and preserves original column indexes', () => {
    const rows = [['Part Number', 'Quantity'], ...Array.from({ length: 20 }, () => ['R1', '1'])];
    rows[20] = ['R2', '2', '', '', '', 'Late material note'];
    expect(buildBomReviewColumns(rows, 0).map((column) => column.index)).toEqual([
      0, 1, 2, 3, 4, 5,
    ]);
  });
});
