import { isIP } from 'node:net';

/** Explicit proxy identities only; an invalid policy must fail startup, never trust everybody. */
export function trustedProxyPolicy(raw: string | undefined): string[] {
  if (raw === undefined || raw.trim() === '') return ['loopback'];
  const entries = raw.split(',').map((entry) => entry.trim());
  for (const entry of entries) {
    if (entry === 'loopback') continue;
    const parts = entry.split('/');
    const family = isIP(parts[0]);
    const bits = family === 4 ? 32 : 128;
    if (
      !family ||
      parts.length > 2 ||
      (parts.length === 2 &&
        (!/^\d+$/.test(parts[1]) || Number(parts[1]) <= 0 || Number(parts[1]) > bits))
    ) {
      throw new Error('AURA_TRUSTED_PROXIES must list explicit IP addresses or bounded CIDRs');
    }
  }
  return entries;
}
