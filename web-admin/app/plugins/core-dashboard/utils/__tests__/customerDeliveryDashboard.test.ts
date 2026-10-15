import fs from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';
import { parseDashboardDocument } from '../dashboardDocument';

const root = path.resolve(process.cwd(), fs.existsSync(path.join(process.cwd(), 'plugins/project-management')) ? 'plugins/project-management' : '../plugins/project-management');
const document = JSON.parse(fs.readFileSync(path.join(root, 'examples/dashboard-customer-delivery.json'), 'utf8'));
const fields = JSON.parse(fs.readFileSync(path.join(root, 'config/fields/tpm_project.json'), 'utf8'));
const dictionaries = JSON.parse(fs.readFileSync(path.join(root, 'config/dicts.json'), 'utf8'));

describe('portable customer delivery dashboard', () => {
  it('uses six registered widgets and carries no runtime identity or hardcoded metric values', () => {
    const parsed = parseDashboardDocument(JSON.stringify(document));
    expect(parsed.dashboard.widgets).toHaveLength(6);
    expect(parsed).toEqual(document);
    expect(JSON.stringify(parsed)).not.toMatch(/REPLACE_|staticData|tenantId|recordPid/);
    expect(parsed.dashboard.widgets.filter(w => w.type === 'smart-number-card')).toHaveLength(4);
    expect(parsed.dashboard.widgets.find(w => w.type === 'smart-pie-chart')).toBeDefined();
    expect(parsed.dashboard.widgets.find(w => w.type === 'smart-table-chart')).toBeDefined();
  });
  it('binds real project fields and real dictionary states without a second business model', () => {
    const fieldCodes = new Set(fields.map((f: { code: string }) => f.code));
    const statuses = dictionaries.find((d: { code: string }) => d.code === 'tpm_project_status');
    for (const widget of document.dashboard.widgets) {
      const source = widget.config.dataSource;
      expect(source?.modelCode || widget.config.modelCode).toBe('tpm_project');
      for (const metric of source?.metrics || []) {
        expect(metric.field === 'id' || fieldCodes.has(metric.field)).toBe(true);
        expect(['count', 'sum']).toContain(metric.aggregation);
      }
      for (const filter of source?.filters || []) {
        expect(filter.field).toBe('tpm_pj_status');
        expect(statuses.items.some((item: { value: string }) => item.value === filter.value)).toBe(true);
      }
      expect(widget.config.title['zh-CN']).toBeTruthy();
      expect(widget.config.title['en-US']).toBeTruthy();
      for (const column of widget.config.table?.columns || []) {
        expect(fieldCodes.has(column.field)).toBe(true);
        expect(column.label['zh-CN']).toBeTruthy();
        expect(column.label['en-US']).toBeTruthy();
      }
    }
    const budget = document.dashboard.widgets.find((w: {id: string}) => w.id === 'project-budget');
    expect(budget.config.dataSource.metrics).toEqual([{ field: 'tpm_pj_budget', aggregation: 'sum', alias: 'value' }]);
    const stages = document.dashboard.widgets.find((w: {id: string}) => w.id === 'delivery-stages');
    expect(stages.config.dataSource.dimensions).toEqual(['tpm_pj_status']);
    expect(fields.find((f: {code: string}) => f.code === 'tpm_pj_status').dictCode).toBe(statuses.code);
  });
});
