import defaults from './icpCompliance.defaults.json';

const ENABLED_VALUES = new Set(['1', 'true', 'yes', 'on']);

export const ICP_RECORD_LOOKUP_URL = 'https://beian.miit.gov.cn/';

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

export function getIcpComplianceConfig(): IcpComplianceConfig {
  const enabled = ENABLED_VALUES.has(
    (import.meta.env.VITE_ICP_COMPLIANCE_ENABLED || '').trim().toLowerCase(),
  );
  const siteTitle = envValue(import.meta.env.VITE_ICP_SITE_TITLE, defaults.siteTitle);
  const recordNumber = envValue(import.meta.env.VITE_ICP_RECORD_NUMBER, defaults.recordNumber);

  return {
    enabled,
    siteTitle,
    recordNumber,
    siteDisplayName: enabled ? `AuraBoot ${siteTitle}` : 'AuraBoot',
  };
}
