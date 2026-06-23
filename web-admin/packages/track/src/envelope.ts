import type { BehaviorEventInput, RawEventInput } from './types';

const ULID_CHARS = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';

function secureRandomBytes(length: number): Uint8Array {
  const bytes = new Uint8Array(length);
  const cryptoApi = globalThis.crypto;
  if (!cryptoApi || typeof cryptoApi.getRandomValues !== 'function') {
    throw new Error('Secure random generator is unavailable');
  }
  cryptoApi.getRandomValues(bytes);
  return bytes;
}

/**
 * Generates a 26-character Crockford base32 ULID.
 * 10 time chars (48-bit ms timestamp) + 16 random chars (80-bit entropy).
 */
export function generateEventId(): string {
  let now = Date.now();
  const time: string[] = [];
  for (let i = 9; i >= 0; i--) {
    time[i] = ULID_CHARS[now % 32];
    now = Math.floor(now / 32);
  }
  let rand = '';
  const bytes = secureRandomBytes(16);
  for (let i = 0; i < 16; i++) rand += ULID_CHARS[bytes[i] & 31];
  return time.join('') + rand;
}

/**
 * Strips the query string and replaces purely numeric path segments with :id.
 * e.g. /p/c/order_list/9182734?tab=x → /p/c/order_list/:id
 */
export function sanitizeRoute(path: string): string {
  const noQuery = path.split('?')[0];
  return noQuery.replace(/\/\d+(?=\/|$)/g, '/:id');
}

/**
 * Builds a flat camelCase BehaviorEventInput envelope from a raw input.
 */
export function buildEvent(input: RawEventInput): BehaviorEventInput {
  return {
    eventId: generateEventId(),
    schemaVersion: '1',
    eventName: input.eventName,
    eventCategory: input.eventCategory,
    source: 'web',
    occurredAt: new Date().toISOString(),
    clientSessionId: input.clientSessionId,
    anonId: input.anonId,
    uiElementId: input.ui?.uiElementId,
    appId: input.ui?.appId,
    pageId: input.ui?.pageId,
    blockId: input.ui?.blockId,
    elementCode: input.ui?.elementCode,
    identityQuality: input.ui?.identityQuality,
    props: input.props,
  };
}
