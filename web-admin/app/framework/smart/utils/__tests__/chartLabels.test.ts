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
