import { describe, expect, it } from 'vitest';
import { resolveIcpComplianceConfig, ICP_RECORD_LOOKUP_URL } from '../icpCompliance';

describe('ICP compliance configuration', () => {
  it('is disabled by default', () => {
    expect(resolveIcpComplianceConfig()).toEqual({
      enabled: false,
      siteTitle: '个人技术',
      recordNumber: '浙ICP备2023054087号',
      siteDisplayName: 'AuraBoot',
    });
  });

  it.each(['1', 'true', 'TRUE', 'yes', 'on'])('enables the filing profile for %s', (value) => {
    expect(resolveIcpComplianceConfig({ ICP_COMPLIANCE_ENABLED: value })).toMatchObject({
      enabled: true,
      siteDisplayName: 'AuraBoot 个人技术',
    });
  });

  it('allows deployment-specific title and record overrides', () => {
    expect(
      resolveIcpComplianceConfig({
        ICP_COMPLIANCE_ENABLED: 'true',
        ICP_SITE_TITLE: 'My filed site',
        ICP_RECORD_NUMBER: 'Test ICP record',
      }),
    ).toEqual({
      enabled: true,
      siteTitle: 'My filed site',
      recordNumber: 'Test ICP record',
      siteDisplayName: 'AuraBoot My filed site',
    });
    expect(ICP_RECORD_LOOKUP_URL).toBe('https://beian.miit.gov.cn/');
  });
});
