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
  it('binds declared named query parameters without allowing authorization or transport overrides', () => {
    const source = report();
    source.dataSources.orders = { type: 'namedQuery', queryCode: 'project_facts', params: { period: 'week' } };
    source.parameters![0].bindTo!.field = 'projectPid';
    expect(applyReportParameters(source, { input: 'project-a' }).dataSources.orders.params).toEqual({ period: 'week', projectPid: 'project-a' });
    expect(source.dataSources.orders.params).toEqual({ period: 'week' });
    for (const field of ['tenantId', 'userId', 'datasourceId', 'maxItems', '../project']) {
      source.parameters![0].bindTo!.field = field;
      expect(() => applyReportParameters(source)).toThrow('Invalid named query');
    }
    source.parameters![0].bindTo = { dataSource: 'orders', field: 'projectPid', operator: 'LIKE' };
    expect(() => applyReportParameters(source)).toThrow('Invalid named query');
  });
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
