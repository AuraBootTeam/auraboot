import { afterEach, describe, expect, it, vi } from 'vitest';
import { getIcpComplianceConfig, ICP_RECORD_LOOKUP_URL } from '../icpCompliance';

describe('ICP compliance configuration', () => {
  afterEach(() => {
    vi.unstubAllEnvs();
  });

  it('is disabled by default', () => {
    vi.stubEnv('VITE_ICP_COMPLIANCE_ENABLED', '');

    expect(getIcpComplianceConfig()).toEqual({
      enabled: false,
      siteTitle: '个人技术',
      recordNumber: '浙ICP备2023054087号',
      siteDisplayName: 'AuraBoot',
    });
  });

  it.each(['1', 'true', 'TRUE', 'yes', 'on'])('enables the filing profile for %s', (value) => {
    vi.stubEnv('VITE_ICP_COMPLIANCE_ENABLED', value);

    expect(getIcpComplianceConfig()).toMatchObject({
      enabled: true,
      siteDisplayName: 'AuraBoot 个人技术',
    });
  });

  it('allows deployment-specific title and record overrides', () => {
    vi.stubEnv('VITE_ICP_COMPLIANCE_ENABLED', 'true');
    vi.stubEnv('VITE_ICP_SITE_TITLE', 'My filed site');
    vi.stubEnv('VITE_ICP_RECORD_NUMBER', 'Test ICP record');

    expect(getIcpComplianceConfig()).toEqual({
      enabled: true,
      siteTitle: 'My filed site',
      recordNumber: 'Test ICP record',
      siteDisplayName: 'AuraBoot My filed site',
    });
    expect(ICP_RECORD_LOOKUP_URL).toBe('https://beian.miit.gov.cn/');
  });
});
