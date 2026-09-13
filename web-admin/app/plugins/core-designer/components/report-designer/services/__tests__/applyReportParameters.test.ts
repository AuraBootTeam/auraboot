import { describe, expect, it } from 'vitest';
import { applyReportParameters } from '../applyReportParameters';
import { createEmptyReport, type ReportDsl } from '../../types';

function report(): ReportDsl {
  return {
    ...createEmptyReport('Parameters'),
    dataSources: { orders: { type: 'model', modelCode: 'orders' } },
    parameters: [
      {
        name: 'input',
        label: 'Input',
        type: 'text',
        required: true,
        defaultValue: 'A',
        bindTo: { dataSource: 'orders', field: 'title', operator: 'EQ' },
      },
    ],
  };
}

describe('report parameters', () => {
  it('applies defaults and overrides while leaving the definition unchanged', () => {
    const source = report();
    expect(applyReportParameters(source).dataSources.orders.filters).toEqual([
      { field: 'title', operator: 'EQ', value: 'A' },
    ]);
    expect(applyReportParameters(source, { input: 'B' }).dataSources.orders.filters).toEqual([
      { field: 'title', operator: 'EQ', value: 'B' },
    ]);
    expect(source.dataSources.orders.filters).toBeUndefined();
    expect(() => applyReportParameters(source, { input: '' })).toThrow('Required');
    expect(() => applyReportParameters(source, { other: 'A' })).toThrow('Unknown');
  });
  it('binds complete ranges and refuses partial or invalid dates', () => {
    const source = report();
    source.parameters![0].type = 'date-range';
    expect(
      applyReportParameters(source, { input_start: '2026-09-01', input_end: '2026-09-12' })
        .dataSources.orders.filters,
    ).toEqual([{ field: 'title', operator: 'BETWEEN', values: ['2026-09-01', '2026-09-12'] }]);
    expect(() => applyReportParameters(source, { input_start: '2026-09-01' })).toThrow('range');
    expect(() =>
      applyReportParameters(source, { input_start: '2026-02-30', input_end: '2026-03-01' }),
    ).toThrow('range');
  });
});
