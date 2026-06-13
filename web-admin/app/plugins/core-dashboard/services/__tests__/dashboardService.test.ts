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

  it('preserves smart-number-card presentation keys (cards/label) through the dataSource branch', () => {
    // Regression: the dataSource branch only forwarded a whitelist (dataSource,
    // visualization, linkage, ...), silently dropping `cards`. The card renderer then
    // fell back to auto-cards labelled with the raw named-query field codes
    // (e.g. "new_leads") instead of the authored `cards[].label` eyebrow.
    const dashboard = normalizeDashboard(
      baseDashboard([
        {
          id: 'block_kpi_cards',
          type: 'smart-number-card',
          x: 0,
          y: 0,
          w: 12,
          h: 1,
          config: {
            title: { 'zh-CN': '关键指标', en: 'Key Metrics' },
            dataSource: {
              type: 'api',
              url: '/api/datasource/list',
              params: { datasourceId: 'nq:crm_dashboard_kpi', format: 'records', maxItems: '1' },
            },
            cards: [
              { field: 'new_leads', label: { 'zh-CN': '新线索', en: 'New Leads' }, color: '#3b82f6' },
              {
                field: 'open_opportunities',
                label: { 'zh-CN': '进行中商机', en: 'Open Opportunities' },
                color: '#f59e0b',
              },
            ],
          },
        },
      ]),
    );

    const config = dashboard.widgets[0].config;
    expect(config.dataSource).toMatchObject({ type: 'api', url: '/api/datasource/list' });
    expect(config.cards).toHaveLength(2);
    expect(config.cards?.[0]).toMatchObject({
      field: 'new_leads',
      label: { 'zh-CN': '新线索', en: 'New Leads' },
    });
    expect(config.cards?.[1]).toMatchObject({ field: 'open_opportunities' });
  });

  it('forwards single number-card presentation keys (metricField/format/suffix/label)', () => {
    const dashboard = normalizeDashboard(
      baseDashboard([
        {
          id: 'revenue_card',
          type: 'smart-number-card',
          x: 0,
          y: 0,
          w: 3,
          h: 2,
          config: {
            title: { 'zh-CN': '本月回款' },
            label: { 'zh-CN': '回款金额', en: 'Collected' },
            dataSource: {
              type: 'namedQuery',
              queryCode: 'crm_collection_summary',
            },
            metricField: 'collected_amount',
            format: 'currency',
            currency: 'cny',
            precision: 2,
            suffix: '元',
          },
        },
      ]),
    );

    const config = dashboard.widgets[0].config;
    expect(config.metricField).toBe('collected_amount');
    expect(config.format).toBe('currency');
    expect(config.precision).toBe(2);
    expect(config.suffix).toBe('元');
    expect(config.label).toEqual({ 'zh-CN': '回款金额', en: 'Collected' });
  });

  it('preserves authored shortcut rows through the dataSource branch', () => {
    const dashboard = normalizeDashboard(
      baseDashboard([
        {
          id: 'shortcut_widget',
          type: 'smart-shortcuts',
          x: 0,
          y: 0,
          w: 6,
          h: 2,
          config: {
            title: 'Runtime Shortcuts',
            dataSource: { type: 'static' },
            shortcuts: [
              {
                label: 'Runtime Dashboards',
                icon: '>',
                path: '/dashboards',
                color: 'bg-blue-50',
              },
            ],
          },
        },
      ]),
    );

    const config = dashboard.widgets[0].config as typeof dashboard.widgets[0]['config'] & {
      shortcuts?: Array<{ label: string; path: string }>;
    };
    expect(config.dataSource).toMatchObject({ type: 'static' });
    expect(config.shortcuts).toEqual([
      {
        label: 'Runtime Dashboards',
        icon: '>',
        path: '/dashboards',
        color: 'bg-blue-50',
      },
    ]);
  });
});
