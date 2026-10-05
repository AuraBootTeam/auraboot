import { describe, expect, it } from 'vitest';
import { resolveRecordParams, resolveStateParams } from '~/framework/meta/rendering/blocks/ChartBlockRenderer';

describe('resolveRecordParams', () => {
  it('resolves ${record.<field>}, ${recordPid} and ${<field>} against the record', () => {
    const out = resolveRecordParams(
      {
        chartId: '${record.pid}',
        byId: '${recordPid}',
        shortField: '${qc_spc_name}',
      },
      { pid: '01ABC', qc_spc_name: 'Paste Thickness' },
      '01ABC',
    );
    expect(out).toEqual({
      chartId: '01ABC',
      byId: '01ABC',
      shortField: 'Paste Thickness',
    });
  });

  it('passes non-string values through and resolves missing fields to empty string', () => {
    const out = resolveRecordParams(
      { sampleSize: 5, missing: '${record.nope}' },
      {},
      undefined,
    );
    expect(out).toEqual({ sampleSize: 5, missing: '' });
  });

  it('returns undefined params unchanged (dashboards / no params)', () => {
    expect(resolveRecordParams(undefined, { pid: 'x' }, 'x')).toBeUndefined();
  });

  it('does not mutate the input params object', () => {
    const input = { chartId: '${record.pid}' };
    resolveRecordParams(input, { pid: '01ABC' }, '01ABC');
    expect(input).toEqual({ chartId: '${record.pid}' });
  });
});

describe('resolveStateParams', () => {
  it('resolves ${state.<key>} against the page filter state', () => {
    const out = resolveStateParams(
      { dateFrom: '${state.dateFrom}', dateTo: '${state.dateTo}', statusFilter: '${state.statusFilter}' },
      { dateFrom: '2026-10-05', dateTo: '2026-10-05', statusFilter: 'submitted' },
    );
    expect(out).toEqual({ dateFrom: '2026-10-05', dateTo: '2026-10-05', statusFilter: 'submitted' });
  });

  it('drops state-bound params whose state value is unset so optional NQ params stay absent', () => {
    const out = resolveStateParams(
      { dateFrom: '${state.dateFrom}', shopId: '${state.shopFilter}' },
      { dateFrom: '2026-10-05' },
    );
    expect(out).toEqual({ dateFrom: '2026-10-05' });
    expect(Object.prototype.hasOwnProperty.call(out, 'shopId')).toBe(false);
  });

  it('passes non-string values through and returns undefined params unchanged', () => {
    expect(resolveStateParams({ limit: 20 }, undefined)).toEqual({ limit: 20 });
    expect(resolveStateParams(undefined, { a: 1 })).toBeUndefined();
  });

  it('does not mutate the input params object', () => {
    const input = { dateFrom: '${state.dateFrom}' };
    resolveStateParams(input, { dateFrom: '2026-10-05' });
    expect(input).toEqual({ dateFrom: '${state.dateFrom}' });
  });
});
