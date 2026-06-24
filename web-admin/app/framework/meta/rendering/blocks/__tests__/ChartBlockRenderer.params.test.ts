import { describe, expect, it } from 'vitest';
import { resolveRecordParams } from '~/framework/meta/rendering/blocks/ChartBlockRenderer';

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
