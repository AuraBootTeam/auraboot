import { describe, expect, it } from 'vitest';
import { createEmptyReport } from '../../types';
import { reportWithRouteParameters } from '../reportRouteParameters';
import { applyReportParameters } from '../applyReportParameters';

describe('declared report route inputs', () => {
  const report = {
    ...createEmptyReport('Project review'),
    dataSources: { facts: { type: 'namedQuery' as const, queryCode: 'project_facts' } },
    parameters: [{ name: 'projectPid', type: 'text' as const, label: 'Project', required: true,
      inputSource: 'route' as const, defaultValue: 'must-not-fallback',
      bindTo: { dataSource: 'facts', field: 'projectPid', operator: 'EQ' } }],
  };
  it('uses only declared route keys and retains the definition for save/reopen', () => {
    const scoped = reportWithRouteParameters(report, '?projectPid=project-a&tenantId=foreign')!;
    expect(applyReportParameters(scoped).dataSources.facts.params).toEqual({ projectPid: 'project-a' });
    expect(report.parameters[0].defaultValue).toBe('must-not-fallback');
    expect(reportWithRouteParameters(report, '?projectPid=project-b')!.parameters![0].defaultValue).toBe('project-b');
  });
  it('fails closed for missing, empty or duplicate scope instead of falling back to a saved default', () => {
    for (const search of ['', '?projectPid=', '?projectPid=a&projectPid=b'])
      expect(() => applyReportParameters(reportWithRouteParameters(report, search)!)).toThrow('Required');
    expect(reportWithRouteParameters(null, '')).toBeNull();
  });
});
