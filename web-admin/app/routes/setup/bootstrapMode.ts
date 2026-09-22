import type { BrandingConfig, TenantJoinChannel } from '~/config/branding';

type BootstrapBranding = Pick<BrandingConfig, 'mode'> & {
  tenantOnboarding?: { joinChannel: TenantJoinChannel };
};

export function requiresSchoolSelfServiceMode(
  branding: BootstrapBranding,
): boolean {
  return branding.mode === 'commercial' && branding.tenantOnboarding?.joinChannel === 'wechat_mini';
}

export function resolveBootstrapSystemMode(
  branding: BootstrapBranding,
  selectedMode: string,
): string {
  return requiresSchoolSelfServiceMode(branding) ? 'multi' : selectedMode;
}
