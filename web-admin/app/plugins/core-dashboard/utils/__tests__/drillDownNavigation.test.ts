import { describe, expect, it } from 'vitest';

import { decodeFilters } from '~/framework/meta/rendering/pages/list/useListUrlState';
import {
  buildDrillDownTarget,
  resolveDashboardRuntimeValue,
} from '../drillDownNavigation';
import { enrichDrillDownFilters } from '~/framework/smart/utils/drillDownFilters';

function decodedFilters(target: string) {
  const url = new URL(target, 'http://localhost');
  return decodeFilters(url.searchParams.get('filters'));
}

describe('CRM dashboard drill-down navigation', () => {
  it('preserves a clicked stage and a static open-pipeline cohort', () => {
    const target = buildDrillDownTarget(
      {
        enabled: true,
        action: 'navigate',
        targetPage: '/p/crm_opportunity_common',
        filters: [
          { sourceField: 'stage', targetField: 'crm_opp_stage' },
          {
            targetField: 'crm_opp_forecast_category',
            operator: 'in',
            value: ['commit', 'best_case'],
          },
        ],
      },
      [{ field: 'stage', operator: 'eq', value: 'proposal' }],
    );

    expect(decodedFilters(target!)).toEqual([
      { fieldCode: 'crm_opp_stage', operator: 'eq', value: 'proposal' },
      {
        fieldCode: 'crm_opp_forecast_category',
        operator: 'in',
        value: ['commit', 'best_case'],
      },
    ]);
  });

  it('turns a monthly bucket into an exact half-open date range', () => {
    const target = buildDrillDownTarget(
      {
        enabled: true,
        action: 'navigate',
        targetPage: '/p/crm_opportunity_common',
        filters: [
          {
            sourceField: 'month',
            targetField: 'crm_opp_expected_close_date',
            transform: 'month-range',
          },
        ],
      },
      [{ field: 'month', operator: 'eq', value: '2026-12' }],
    );

    expect(decodedFilters(target!)).toEqual([
      { fieldCode: 'crm_opp_expected_close_date', operator: 'gte', value: '2026-12-01' },
      { fieldCode: 'crm_opp_expected_close_date', operator: 'lt', value: '2027-01-01' },
    ]);
  });

  it('resolves record-scoped dashboard placeholders before fetching and navigating', () => {
    expect(
      resolveDashboardRuntimeValue(
        {
          parameters: { accountId: '${recordPid}' },
          filters: [{ value: '${recordPid}' }],
        },
        { recordPid: 'ACC-001' },
      ),
    ).toEqual({
      parameters: { accountId: 'ACC-001' },
      filters: [{ value: 'ACC-001' }],
    });
  });

  it('carries a non-display owner PID from the clicked chart row', () => {
    expect(
      enrichDrillDownFilters(
        { field: 'owner_name', operator: 'eq', value: '周顾问' },
        { owner_name: '周顾问', owner_pid: 'USER-007' },
        {
          enabled: true,
          action: 'navigate',
          targetPage: '/p/crm_opportunity_common',
          filters: [{ sourceField: 'owner_pid', targetField: 'crm_opp_owner' }],
        },
      ),
    ).toEqual([
      { field: 'owner_name', operator: 'eq', value: '周顾问' },
      { field: 'owner_pid', operator: 'eq', value: 'USER-007' },
    ]);
  });
});
