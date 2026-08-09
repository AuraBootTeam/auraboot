import { readFile } from 'node:fs/promises';
import {
  isCommercialEdition,
  resolveCommercialBranding,
  resolveCommunityBranding,
  type BrandingConfig,
} from './branding';

export interface BrandingEnvironment {
  EDITION?: string;
  AURABOOT_BRANDING_CONFIG_PATH?: string;
  AURABOOT_WHITE_LABEL_ORDER_REFERENCE?: string;
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

  const cacheKey = `${configPath}\0${orderReference}`;
  const cached = cache.get(cacheKey);
  if (cached) return { ...(await cached) };

  const resolved = readFile(configPath, 'utf8')
    .then((contents) => {
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
