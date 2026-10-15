import { describe, expect, it } from 'vitest';
import {
  parseDashboardDocument,
  exportDashboardDocument,
  DASHBOARD_DOCUMENT_MAX_BYTES,
} from '../dashboardDocument';
import type { Dashboard } from '../../types';
function fixture(): Dashboard {
  return {
    pid: 'source',
    tenantId: 42,
    ownerId: 'other',
    scope: 'global',
    status: 'published',
    title: 'Customer delivery',
    extension: { analyticsOrigin: { analysisId: 'private' } },
    layoutConfig: {
      columns: 12,
      rowHeight: 80,
      gap: 16,
      compactType: 'vertical',
    },
    widgets: [
      {
        id: 'count',
        type: 'smart-number-card',
        componentType: 'smart-number-card',
        props: {},
        x: 0,
        y: 0,
        w: 4,
        h: 2,
        config: {
          title: { 'zh-CN': '已归档任务', en: 'Archived tasks' },
          dataSource: {
            type: 'aggregate',
            modelCode: 'tpm_task',
            metrics: [{ field: 'pid', aggregation: 'count', alias: 'n' }],
            filters: [
              { field: 'tpm_ts_status', operator: 'eq', value: 'done' },
            ],
          },
        },
      },
    ],
  };
}
function portable() {
  const d = fixture();
  return exportDashboardDocument(d, d.widgets, d.layoutConfig);
}
describe('portable dashboard design', () => {
  it('round-trips query binding without tenant, identity or publishing state', () => {
    const doc = portable();
    for (const key of ['pid', 'tenantId', 'scope', 'extension'])
      expect(doc.dashboard).not.toHaveProperty(key);
    expect(parseDashboardDocument(JSON.stringify(doc))).toEqual(doc);
    expect(doc.dashboard.widgets[0].config.dataSource?.modelCode).toBe(
      'tpm_task',
    );
    expect(doc.dashboard.widgets[0].config.dataSource?.filters?.[0].value).toBe(
      'done',
    );
    doc.dashboard.widgets[0].config.title = 'Changed';
    expect(fixture().widgets[0].config.title).not.toBe('Changed');
  });
  it('strips injected source dashboard identity', () => {
    const doc = portable();
    Object.assign(doc.dashboard, {
      pid: 'attack',
      status: 'published',
      scope: 'global',
      tenantId: 99,
    });
    expect(
      parseDashboardDocument(JSON.stringify(doc)).dashboard,
    ).not.toHaveProperty('tenantId');
  });
  it('rejects malformed, unsupported and oversized documents', () => {
    expect(() => parseDashboardDocument('{')).toThrow();
    expect(() =>
      parseDashboardDocument(JSON.stringify({ ...portable(), version: 2 })),
    ).toThrow('version');
    expect(() =>
      parseDashboardDocument(' '.repeat(DASHBOARD_DOCUMENT_MAX_BYTES + 1)),
    ).toThrow('size');
  });
  it('rejects duplicate ids and unknown types', () => {
    const doc = portable();
    doc.dashboard.widgets.push(doc.dashboard.widgets[0]);
    expect(() => parseDashboardDocument(JSON.stringify(doc))).toThrow();
    const unknown = portable();
    unknown.dashboard.widgets[0].type = 'not-a-widget' as never;
    expect(() => parseDashboardDocument(JSON.stringify(unknown))).toThrow();
  });
  it('rejects invalid grid dimensions and unsafe object keys', () => {
    const doc = portable();
    doc.dashboard.widgets[0].w = 13;
    expect(() => parseDashboardDocument(JSON.stringify(doc))).toThrow();
    expect(() =>
      parseDashboardDocument(
        JSON.stringify(portable()).replace(
          '"title":',
          '"__proto__":{},"title":',
        ),
      ),
    ).toThrow();
  });
});
