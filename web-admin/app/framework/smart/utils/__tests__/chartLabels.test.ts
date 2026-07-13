import { describe, expect, it } from 'vitest';
import { dimensionLabel, metricLabel } from '../chartLabels';
import type { QueryMeta } from '~/framework/smart/types/chart';

const meta: QueryMeta = {
  dimensions: ['crm_opp_stage'],
  metrics: ['deal_count'],
  dimensionLabels: {
    crm_opp_stage: { closed_won: '赢单', discovery: '发现' },
  },
};

describe('dimensionLabel', () => {
  it('resolves a dict-coded value to its label', () => {
    expect(dimensionLabel(meta, 'crm_opp_stage', 'closed_won')).toBe('赢单');
  });

  it('falls back to the raw value when the dict has no entry for it', () => {
    // A stage added to the data but not to the dict must still render, as the code.
    expect(dimensionLabel(meta, 'crm_opp_stage', 'renegotiation')).toBe('renegotiation');
  });

  it('falls back to the raw value when the field has no dict at all', () => {
    // crm_acc_industry is a free string field — no dict binding, nothing to resolve.
    expect(dimensionLabel(meta, 'crm_acc_industry', 'electronics')).toBe('electronics');
  });

  it('falls back to the raw value when labels have not resolved yet', () => {
    // The dict lookup is async; charts render before it lands.
    expect(dimensionLabel({ dimensions: [], metrics: [] }, 'crm_opp_stage', 'closed_won')).toBe(
      'closed_won',
    );
    expect(dimensionLabel(undefined, 'crm_opp_stage', 'closed_won')).toBe('closed_won');
  });

  it('stringifies non-string values and maps empty/nullish to an empty label', () => {
    expect(dimensionLabel(meta, 'crm_opp_stage', 2026)).toBe('2026');
    expect(dimensionLabel(meta, 'crm_opp_stage', null)).toBe('');
    expect(dimensionLabel(meta, undefined, 'closed_won')).toBe('closed_won');
  });
});

describe('formatBucketValue (via dimensionLabel)', () => {
  const meta = { dimensions: ['crm_opp_close__month'], metrics: [] };

  it('formats a month bucket to YYYY-MM', () => {
    expect(dimensionLabel(meta, 'crm_opp_close__month', '2025-04-01 00:00:00+08')).toBe('2025-04');
  });

  it('formats a quarter bucket to YYYY-Qn', () => {
    expect(dimensionLabel(meta, 'x__quarter', '2025-07-01 00:00:00+08')).toBe('2025-Q3');
  });

  it('formats a year bucket to YYYY', () => {
    expect(dimensionLabel(meta, 'x__year', '2025-01-01 00:00:00+08')).toBe('2025');
  });

  it('formats a day bucket to YYYY-MM-DD', () => {
    expect(dimensionLabel(meta, 'x__day', '2025-04-09 00:00:00+08')).toBe('2025-04-09');
  });

  it('leaves a plain (non-bucketed) dimension untouched', () => {
    expect(dimensionLabel(meta, 'crm_opp_stage', 'closed_won')).toBe('closed_won');
  });

  it('a dict label wins over bucket formatting', () => {
    const withDict = { dimensions: [], metrics: [], dimensionLabels: { s__month: { '2025-04-01 00:00:00+08': '四月' } } };
    expect(dimensionLabel(withDict, 's__month', '2025-04-01 00:00:00+08')).toBe('四月');
  });
});

describe('metricLabel', () => {
  it('resolves a metric alias to its configured display name', () => {
    expect(metricLabel({ won_amount: '赢单金额' }, 'won_amount')).toBe('赢单金额');
  });

  it('falls back to the alias when the widget supplied no label', () => {
    // This is the pre-existing behaviour: a legend reading `won_amount`.
    expect(metricLabel(undefined, 'won_amount')).toBe('won_amount');
    expect(metricLabel({}, 'won_amount')).toBe('won_amount');
    expect(metricLabel({ other: '别的' }, 'won_amount')).toBe('won_amount');
  });
});
