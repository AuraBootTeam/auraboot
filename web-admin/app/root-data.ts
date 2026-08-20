import type { RuntimeProfile } from '@auraboot/runtime-kernel';
import { useRouteLoaderData } from 'react-router';
import type { BrandingConfig, BuildIdentity } from '~/config/branding';
import type { IcpComplianceConfig } from '~/config/icpCompliance';
import type { BootstrapStatus } from '~/services/bootstrapStatus';
import type { AccessPolicy } from '~/services/accessPolicy';

export interface RootLoaderData {
  runtimeProfile: RuntimeProfile;
  user: any;
  permissions: any;
  preferences: any;
  menus: any[];
  i18n: Record<string, string>;
  locale: string;
  initialTimezone?: string;
  skipTenantPreferences?: boolean;
  edition: string;
  spaces: any[];
  bootstrapStatus: BootstrapStatus | null;
  icpCompliance: IcpComplianceConfig;
  branding: BrandingConfig;
  buildIdentity: BuildIdentity;
  accessPolicy: AccessPolicy;
}

export function useRootLoaderData(): RootLoaderData | undefined {
  return useRouteLoaderData<RootLoaderData>('root');
}
