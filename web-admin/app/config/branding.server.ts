import { readFile } from 'node:fs/promises';
import {
  OFFLINE_SIGNATURE_ENFORCEMENT,
  verifyDeploymentCommercialLicense,
  type CommercialLicenseEnvironment,
} from './commercial-license.server';
import {
  isCommercialEdition,
  resolveCommercialBranding,
  resolveCommunityBranding,
  type BrandingConfig,
} from './branding';

export interface BrandingEnvironment extends CommercialLicenseEnvironment {
  EDITION?: string;
  AURABOOT_BRANDING_CONFIG_PATH?: string;
  AURABOOT_WHITE_LABEL_ORDER_REFERENCE?: string;
  AURABOOT_COMMERCIAL_LICENSE_ENFORCEMENT?: string;
}

const cache = new Map<string, Promise<BrandingConfig>>();

export async function resolveDeploymentBranding(
  environment: BrandingEnvironment,
): Promise<BrandingConfig> {
  const configPath = environment.AURABOOT_BRANDING_CONFIG_PATH?.trim();
  if (!configPath || !isCommercialEdition(environment.EDITION)) {
    return resolveCommunityBranding();
  }

  const orderReference = environment.AURABOOT_WHITE_LABEL_ORDER_REFERENCE?.trim();
  if (!orderReference) {
    throw new Error(
      'AURABOOT_WHITE_LABEL_ORDER_REFERENCE is required when deployment branding is enabled.',
    );
  }

  const enforcement = environment.AURABOOT_COMMERCIAL_LICENSE_ENFORCEMENT?.trim() || 'none';
  if (enforcement !== 'none' && enforcement !== OFFLINE_SIGNATURE_ENFORCEMENT) {
    throw new Error(`Unsupported commercial License enforcement mode: ${enforcement}.`);
  }

  const cacheKey = [
    configPath,
    orderReference,
    enforcement,
    environment.AURABOOT_COMMERCIAL_LICENSE_PATH,
    environment.AURABOOT_COMMERCIAL_LICENSE_SIGNATURE_PATH,
    environment.AURABOOT_COMMERCIAL_LICENSE_PUBLIC_KEY_PATH,
    environment.AURABOOT_COMMERCIAL_LICENSE_KEY_ID,
    environment.AURABOOT_COMMERCIAL_LICENSE_CUSTOMER,
    environment.AURABOOT_VERSION,
    environment.APP_VERSION,
  ].join('\0');
  const cached = cache.get(cacheKey);
  if (cached) return { ...(await cached) };

  const licenseVerification =
    enforcement === OFFLINE_SIGNATURE_ENFORCEMENT
      ? verifyDeploymentCommercialLicense(environment, {
          edition: environment.EDITION ?? '',
          orderReference,
          requiredFeatures: ['white_label'],
        })
      : Promise.resolve();
  const resolved = Promise.all([readFile(configPath, 'utf8'), licenseVerification])
    .then(([contents]) => {
      let document: unknown;
      try {
        document = JSON.parse(contents);
      } catch (error) {
        throw new Error(`Deployment branding file is not valid JSON: ${configPath}`, {
          cause: error,
        });
      }
      return resolveCommercialBranding(document, orderReference);
    })
    .catch((error) => {
      cache.delete(cacheKey);
      throw error;
    });

  cache.set(cacheKey, resolved);
  return { ...(await resolved) };
}

export function clearDeploymentBrandingCacheForTests(): void {
  cache.clear();
}
