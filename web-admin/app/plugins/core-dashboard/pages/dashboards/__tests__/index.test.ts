import { describe, expect, it } from 'vitest';
import { resolveDashboardErrorMessage } from '../index';

describe('Dashboard list error localization', () => {
  const translate = (key: string) => ({
    'dashboard.error.access_forbidden': '无权访问仪表盘',
  })[key] ?? key;

  it('replaces backend access-denied text with the localized product message', () => {
    expect(resolveDashboardErrorMessage('Access forbidden', translate))
      .toBe('无权访问仪表盘');
    expect(resolveDashboardErrorMessage('HTTP 403: permission denied', translate))
      .toBe('无权访问仪表盘');
  });

  it('keeps non-permission failures available for diagnosis', () => {
    expect(resolveDashboardErrorMessage('Dashboard service unavailable', translate))
      .toBe('Dashboard service unavailable');
  });
});
