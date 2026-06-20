export const RUNTIME_PROFILES = [
  'admin',
  'merchant',
  'storefront',
  'checkout',
  'theme-preview',
] as const;

export type RuntimeProfile = (typeof RUNTIME_PROFILES)[number];

export const DEFAULT_RUNTIME_PROFILE: RuntimeProfile = 'admin';

const PROFILE_PREFIXES: Array<[RuntimeProfile, string]> = [
  ['admin', '/admin'],
  ['merchant', '/merchant'],
  ['storefront', '/s'],
  ['checkout', '/checkout'],
  ['theme-preview', '/theme-preview'],
];

function matchesPathPrefix(pathname: string, prefix: string): boolean {
  return pathname === prefix || pathname.startsWith(`${prefix}/`);
}

export function normalizeRuntimePathname(pathname: string): string {
  if (!pathname) return '/';
  const [pathWithoutQuery] = pathname.split('?');
  const absolutePath = pathWithoutQuery.startsWith('/') ? pathWithoutQuery : `/${pathWithoutQuery}`;
  return absolutePath.replace(/\/+$/, '') || '/';
}

export function getRuntimeProfileFromPathname(pathname: string): RuntimeProfile {
  const normalized = normalizeRuntimePathname(pathname);
  const match = PROFILE_PREFIXES.find(([, prefix]) => matchesPathPrefix(normalized, prefix));
  return match?.[0] ?? DEFAULT_RUNTIME_PROFILE;
}

export function isAnonymousRuntimeProfile(profile: RuntimeProfile): boolean {
  return profile === 'storefront' || profile === 'checkout';
}

export function isPublicRuntimePathname(pathname: string): boolean {
  return isAnonymousRuntimeProfile(getRuntimeProfileFromPathname(pathname));
}

export function shouldBootCorePlugins(profile: RuntimeProfile): boolean {
  return profile === 'admin' || profile === 'merchant' || profile === 'theme-preview';
}

export function getDefaultPluginRuntimeProfiles(): RuntimeProfile[] {
  return [DEFAULT_RUNTIME_PROFILE];
}

/**
 * Plugin federation gating — is a plugin (whose manifest declares
 * `runtimeProfiles`) enabled for the current runtime profile? A plugin that
 * declares no profiles defaults to the plugin default set (admin-only), so
 * admin plugins never leak into anonymous public runtimes.
 *
 * Generic over the declared profile list so the kernel stays free of the
 * host's PluginManifest type; the host passes `manifest.clientConfig?.runtimeProfiles`.
 */
export function isRuntimeProfileEnabled(
  declaredProfiles: readonly RuntimeProfile[] | null | undefined,
  current: RuntimeProfile,
): boolean {
  const profiles = declaredProfiles ?? getDefaultPluginRuntimeProfiles();
  return profiles.includes(current);
}

/**
 * Slot / contribution gating — a contribution that declares no profiles is
 * allowed in every runtime; otherwise it is restricted to the declared set.
 * (Differs from {@link isRuntimeProfileEnabled}: the empty default is "all".)
 */
export function isRuntimeProfileAllowed(
  declaredProfiles: readonly RuntimeProfile[] | null | undefined,
  current: RuntimeProfile,
): boolean {
  if (!declaredProfiles || declaredProfiles.length === 0) return true;
  return declaredProfiles.includes(current);
}
