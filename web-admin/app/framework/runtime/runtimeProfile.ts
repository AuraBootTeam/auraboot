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
