import { describe, expect, it } from 'vitest';
import { normalizeDashboard } from '../dashboardService';
import type { Dashboard } from '../../types';

function baseDashboard(widgets: unknown[]): Dashboard {
  return {
    code: 'test_dashboard',
    title: 'Test Dashboard',
    scope: 'global',
    layoutConfig: { columns: 12, rowHeight: 100, gap: 16 },
    widgets: widgets as Dashboard['widgets'],
    status: 'published',
  };
}

describe('dashboardService.normalizeDashboard', () => {
  it('keeps model-table shorthand without synthesizing an empty static dataSource', () => {
    const dashboard = normalizeDashboard(
      baseDashboard([
        {
          id: 'recent_opportunities',
          type: 'smart-table-chart',
          x: 0,
          y: 0,
          w: 8,
          h: 4,
          config: {
            title: { 'zh-CN': '最新商机', en: 'Recent Opportunities' },
            modelCode: 'crm_opportunity',
            table: {
              columns: [{ field: 'crm_opp_name', label: { 'zh-CN': '商机名称' } }],
            },
          },
        },
      ]),
    );

    expect(dashboard.widgets[0].config.modelCode).toBe('crm_opportunity');
    expect(dashboard.widgets[0].config.title).toEqual({
      'zh-CN': '最新商机',
      en: 'Recent Opportunities',
    });
    expect(dashboard.widgets[0].config.table).toBeTruthy();
    expect(dashboard.widgets[0].config.dataSource).toBeUndefined();
  });

  it('still normalizes inline static table data to a static dataSource', () => {
    const dashboard = normalizeDashboard(
      baseDashboard([
        {
          id: 'static_table',
          type: 'smart-table-chart',
          x: 0,
          y: 0,
          w: 8,
          h: 4,
          config: {
            title: 'Static Table',
            columns: ['name', 'value'],
            data: [['Alpha', '10']],
          },
        },
      ]),
    );

    expect(dashboard.widgets[0].config.dataSource).toMatchObject({
      type: 'static',
      staticData: [{ name: 'Alpha', value: '10' }],
    });
  });
});
