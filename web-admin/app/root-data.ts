import type { RuntimeProfile } from '@auraboot/runtime-kernel';
import { useRouteLoaderData } from 'react-router';
import { createContext, useContext } from 'react';
import type { BrandingConfig, BuildIdentity } from '~/config/branding';
import type { IcpComplianceConfig } from '~/config/icpCompliance';
import type { BootstrapStatus } from '~/services/bootstrapStatus';
import type { AccessPolicy } from '~/services/accessPolicy';
import type { ImpersonationSessionInfo } from '~/shared/services/session';

export interface RootLoaderData {
  traceparent?: string;
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
  impersonation: ImpersonationSessionInfo | null;
}

const RootLoaderDataContext = createContext<RootLoaderData | undefined>(undefined);
export const RootLoaderDataProvider = RootLoaderDataContext.Provider;
export function useRootLoaderData(): RootLoaderData | undefined {
  const initial = useRouteLoaderData<RootLoaderData>('root');
  return useContext(RootLoaderDataContext) ?? initial;
}
