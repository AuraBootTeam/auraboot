import { describe, expect, it } from 'vitest';
import {
  analysisGroupFields,
  analysisMetricFields,
  viewFiltersToChartFilters,
} from '../viewAnalysis';

const fields = [
  { fieldCode: 'stage', label: '阶段', fieldType: 'dict', dictCode: 'crm_stage' },
  { fieldCode: 'amount', label: '金额', fieldType: 'decimal' },
  { fieldCode: 'notes', label: '备注', fieldType: 'richtext' },
];

describe('view analysis field eligibility', () => {
  it('keeps business dimensions, excludes rich payloads and limits measures to numeric fields', () => {
    expect(analysisGroupFields(fields).map((field) => field.fieldCode)).toEqual([
      'stage',
      'amount',
    ]);
    expect(analysisMetricFields(fields).map((field) => field.fieldCode)).toEqual(['amount']);
  });
});

describe('viewFiltersToChartFilters', () => {
  it('preserves exact/in filters and expands between into a half-bounded pair', () => {
    expect(
      viewFiltersToChartFilters([
        { fieldCode: 'stage', operator: 'in', value: ['discovery', 'proposal'] },
        { fieldCode: 'amount', operator: 'between', value: [100, 500] },
        { fieldCode: 'owner_id', operator: 'isNotNull', value: null },
      ]),
    ).toEqual([
      { field: 'stage', operator: 'in', value: ['discovery', 'proposal'] },
      { field: 'amount', operator: 'gte', value: 100 },
      { field: 'amount', operator: 'lte', value: 500 },
      { field: 'owner_id', operator: 'is_not_null', value: null },
    ]);
  });
});
