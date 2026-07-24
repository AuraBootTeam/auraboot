import { describe, expect, it } from 'vitest';
import { resolvePageTargetPath } from '../resolvePageTarget';

describe('resolvePageTargetPath', () => {
  it('returns empty string for an empty target', () => {
    expect(resolvePageTargetPath(undefined)).toBe('');
    expect(resolvePageTargetPath('')).toBe('');
  });

  it('passes an absolute path through unchanged', () => {
    expect(resolvePageTargetPath('/aurabot/providers')).toBe('/aurabot/providers');
  });

  it('interpolates {field} inside an absolute path from the record', () => {
    expect(resolvePageTargetPath('/dashboard-designer/{pid}', { pid: 'D-1' })).toBe(
      '/dashboard-designer/D-1',
    );
  });

  it('backs {pid}/{id} with recordPid when the record lacks them', () => {
    expect(resolvePageTargetPath('/p/x/view/{pid}', {}, 'REC-9')).toBe('/p/x/view/REC-9');
  });

  it('resolves cross-designer prefixes', () => {
    expect(resolvePageTargetPath('dashboard:sales')).toBe('/dashboards/view/sales');
    expect(resolvePageTargetPath('automation:A-1')).toBe('/automation/A-1');
  });

  it('resolves legacy modelCode_pageType pageKeys', () => {
    expect(resolvePageTargetPath('crm_account_list')).toBe('/p/crm_account');
    expect(resolvePageTargetPath('crm_account_form', {}, 'R-1')).toBe('/p/crm_account/edit/R-1');
  });

  // The additive capability: a leading {placeholder} carries the whole route in a
  // record field, so one shared cardAction navigates each card-grid card to its own path.
  it('resolves a leading {field} placeholder to the record\'s full absolute path', () => {
    expect(resolvePageTargetPath('{target}', { target: '/aurabot/providers' })).toBe(
      '/aurabot/providers',
    );
    expect(resolvePageTargetPath('{target}', { target: '/p/mcp-server' })).toBe('/p/mcp-server');
  });

  it('does not treat a leading {field} as absolute when the record value is not a path', () => {
    // record.target is not an absolute path → falls through to legacy pageKey handling,
    // not silently mis-resolved as an absolute route.
    const out = resolvePageTargetPath('{target}', { target: 'not-a-path' });
    expect(out.startsWith('/')).toBe(true); // legacy fallback still yields a route, not the raw value
    expect(out).not.toBe('not-a-path');
  });

  it('ignores the leading-placeholder branch when no record is provided', () => {
    // Without a record the {target} cannot resolve; must not throw, falls through.
    expect(() => resolvePageTargetPath('{target}')).not.toThrow();
  });
});
