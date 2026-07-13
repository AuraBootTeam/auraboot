/**
 * Label resolution in the ChartSpec → ECharts adapter.
 *
 * The byte-equivalence gates next door prove the adapter still matches the legacy
 * builders; these prove the *new* affordances (`dimension.valueLabels`,
 * `measure.label`) actually reach the option, and — crucially — that leaving them
 * out changes nothing, which is what keeps those gates green.
 */

import { describe, expect, it } from 'vitest';
import { chartSpecToEChartsOption } from '../chart-spec-echarts';
import type { ChartSpec } from '../chart-spec';

const rows = [
  { crm_opp_stage: 'discovery', deal_count: 7, won_amount: 100 },
  { crm_opp_stage: 'closed_won', deal_count: 23, won_amount: 900 },
];

function spec(overrides: Partial<ChartSpec> = {}): ChartSpec {
  return {
    type: 'bar',
    title: '商机阶段',
    dataSource: { type: 'aggregate', modelCode: 'crm_opportunity', dimensions: [], metrics: [] },
    dimensions: [{ field: 'crm_opp_stage', role: 'category' }],
    measures: [{ field: 'deal_count' }],
    ...overrides,
  } as ChartSpec;
}

function categories(option: Record<string, unknown>): unknown {
  return (option.xAxis as { data?: unknown }).data;
}

function seriesNames(option: Record<string, unknown>): unknown[] {
  return (option.series as Array<{ name: unknown }>).map((s) => s.name);
}

describe('chartSpecToEChartsOption — labels', () => {
  it('renders dict labels for category values', () => {
    const option = chartSpecToEChartsOption(
      spec({
        dimensions: [
          {
            field: 'crm_opp_stage',
            role: 'category',
            valueLabels: { discovery: '发现', closed_won: '赢单' },
          },
        ],
      }),
      rows,
    );

    expect(categories(option)).toEqual(['发现', '赢单']);
  });

  it('renders the raw code for a value the dict does not carry', () => {
    const option = chartSpecToEChartsOption(
      spec({
        dimensions: [
          { field: 'crm_opp_stage', role: 'category', valueLabels: { discovery: '发现' } },
        ],
      }),
      rows,
    );

    expect(categories(option)).toEqual(['发现', 'closed_won']);
  });

  it('names a series after the measure label when the widget supplies one', () => {
    const option = chartSpecToEChartsOption(
      spec({
        measures: [
          { field: 'deal_count', label: '商机数' },
          { field: 'won_amount', label: '赢单金额' },
        ],
      }),
      rows,
    );

    expect(seriesNames(option)).toEqual(['商机数', '赢单金额']);
  });

  it('falls back to raw codes with no labels — the pre-label behaviour the equivalence gates pin', () => {
    const option = chartSpecToEChartsOption(spec({ measures: [{ field: 'deal_count' }] }), rows);

    expect(categories(option)).toEqual(['discovery', 'closed_won']);
    expect(seriesNames(option)).toEqual(['deal_count']);
  });

  it('labels a partially-labelled measure set without disturbing the rest', () => {
    const option = chartSpecToEChartsOption(
      spec({
        measures: [{ field: 'deal_count', label: '商机数' }, { field: 'won_amount' }],
      }),
      rows,
    );

    expect(seriesNames(option)).toEqual(['商机数', 'won_amount']);
  });
});
