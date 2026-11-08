import type { Capability, CapabilityGroup } from './types';

/** Optional nested domain convention, matching DomainCapabilityLevels on the server. */
export function domainLevels(groups: CapabilityGroup[], code: string): Capability[] {
  const prefix = code.replace(/(read|write|manage)$/, '');
  if (!prefix.endsWith('.cap.')) return [];
  const all = groups.flatMap((g) => g.capabilities);
  const levels = ['read', 'write', 'manage'].map((level) =>
    all.find((cap) => cap.code === prefix + level && !cap.conventionDerived),
  );
  if (levels.some((cap) => !cap)) return [];
  const [read, write, manage] = levels as Capability[];
  if (read.tier !== 'viewer' || write.tier !== 'editor' || manage.tier !== 'admin') return [];
  if (
    write.includes.length <= read.includes.length ||
    manage.includes.length <= write.includes.length ||
    !read.includes.every((permission) => write.includes.includes(permission)) ||
    !write.includes.every((permission) => manage.includes.includes(permission))
  )
    return [];
  return [read, write, manage];
}

export function selectDomainLevel(
  selected: string[],
  revoked: string[],
  levels: Capability[],
  code: string | null,
): { selected: string[]; revoked: string[] } {
  const index = code === null ? -1 : levels.findIndex((cap) => cap.code === code);
  if (code !== null && index < 0) throw new Error('Unknown domain level');
  const codes = new Set(levels.map((cap) => cap.code));
  return {
    selected: [
      ...selected.filter((value) => !codes.has(value)),
      ...levels.slice(0, index + 1).map((cap) => cap.code),
    ],
    // Explicitly replace legacy partial grants in this domain. Shared selected dependencies survive
    // in the server's final desired set; scopes, conditions and field policies are not edited.
    revoked: [
      ...revoked.filter((value) => !codes.has(value)),
      ...levels.slice(index + 1).map((cap) => cap.code),
    ],
  };
}

/** Shared page metadata does not imply a historical business grant in another domain. */
export function hasLegacyDomainGrants(
  levels: Capability[],
  selected: string[],
  revoked: string[],
): boolean {
  const namespace = levels[0].code.replace(/cap\.read$/, '');
  return levels.some((cap, index) => {
    if (
      selected.includes(cap.code) ||
      revoked.includes(cap.code) ||
      cap.authorizationState !== 'partial'
    )
      return false;
    const lower = new Set(index === 0 ? [] : levels[index - 1].includes);
    return cap.includes.some(
      (code) => code.startsWith(namespace) && !lower.has(code) && !cap.missingCodes?.includes(code),
    );
  });
}
