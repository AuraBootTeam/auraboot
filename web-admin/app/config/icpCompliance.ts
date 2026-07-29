import defaults from './icpCompliance.defaults.json';

const ENABLED_VALUES = new Set(['1', 'true', 'yes', 'on']);

export const ICP_RECORD_LOOKUP_URL = 'https://beian.miit.gov.cn/';

export interface IcpComplianceEnvironment {
  ICP_COMPLIANCE_ENABLED?: string;
  ICP_SITE_TITLE?: string;
  ICP_RECORD_NUMBER?: string;
}

export interface IcpComplianceConfig {
  enabled: boolean;
  siteTitle: string;
  recordNumber: string;
  siteDisplayName: string;
}

function envValue(value: string | undefined, fallback: string): string {
  const normalized = value?.trim();
  return normalized || fallback;
}

export function resolveIcpComplianceConfig(
  environment: IcpComplianceEnvironment = {},
): IcpComplianceConfig {
  const enabled = ENABLED_VALUES.has(
    (environment.ICP_COMPLIANCE_ENABLED || '').trim().toLowerCase(),
  );
  const siteTitle = envValue(environment.ICP_SITE_TITLE, defaults.siteTitle);
  const recordNumber = envValue(environment.ICP_RECORD_NUMBER, defaults.recordNumber);

  return {
    enabled,
    siteTitle,
    recordNumber,
    siteDisplayName: enabled ? `AuraBoot ${siteTitle}` : 'AuraBoot',
  };
}
