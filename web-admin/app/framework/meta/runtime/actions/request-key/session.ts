/** Session-only request identity; never derived from a command payload or expression global. */
export interface RequestKeyIdentity {
  tenantId: unknown;
  userId: unknown;
  pageKey: unknown;
  recordPid: unknown;
}
export interface RequestKeySession {
  ensure(field: string, current: unknown): string;
  peek(field: string, current: unknown): string | null;
  clear(field: string, current: unknown): void;
}
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
function uuid(value: unknown): string {
  if (typeof value !== 'string' || !UUID.test(value))
    throw new Error('Invalid persisted request key');
  return value;
}
function identity(value: RequestKeyIdentity | undefined): string[] {
  const tenant = value?.tenantId;
  const tenantId =
    typeof tenant === 'number' && Number.isSafeInteger(tenant) && tenant > 0
      ? String(tenant)
      : tenant;
  if (typeof tenantId !== 'string' || !/^[1-9][0-9]{0,39}$/.test(tenantId))
    throw new Error('Request key requires an authenticated tenant');
  const fields = [value?.userId, value?.pageKey, value?.recordPid];
  if (
    fields.some(
      (item) =>
        typeof item !== 'string' ||
        !item.length ||
        item.length > 256 ||
        item.trim() !== item ||
        /[\x00-\x20\x7f]/.test(item) ||
        item === 'current-user',
    )
  )
    throw new Error('Request key requires complete authenticated page identity');
  return [tenantId, ...fields] as string[];
}
function browserStorage(): Storage {
  try {
    if (typeof window === 'undefined') throw new Error();
    return window.sessionStorage;
  } catch {
    throw new Error('Request key session storage is unavailable');
  }
}
export function createRequestKeySession(
  getIdentity: () => RequestKeyIdentity | undefined,
  getStorage: () => Pick<Storage, 'getItem' | 'setItem' | 'removeItem'> = browserStorage,
): RequestKeySession {
  // Capture immutable values even if a caller later mutates the object itself.
  const original = getIdentity();
  const captured = original ? { ...original } : undefined;
  function key(field: string): string {
    if (
      !/^[A-Za-z_][A-Za-z0-9_]*$/.test(field) ||
      ['__proto__', 'prototype', 'constructor'].includes(field)
    )
      throw new Error('Invalid request key field');
    const initial = identity(captured);
    const current = identity(getIdentity());
    if (JSON.stringify(initial) !== JSON.stringify(current))
      throw new Error('Request key identity changed; reload the authenticated page');
    return 'aura.request-key.v1:' + encodeURIComponent(JSON.stringify([...initial, field]));
  }
  function read(
    storage: Pick<Storage, 'getItem'>,
    name: string,
    current: unknown,
  ): string | undefined {
    const stored = storage.getItem(name);
    const existing = current === null || current === undefined ? undefined : uuid(current);
    const persisted = stored === null ? undefined : uuid(stored);
    if (existing && persisted && existing !== persisted)
      throw new Error('Request key state does not match its persisted attempt');
    return existing || persisted;
  }
  return {
    peek(field, current) {
      const name = key(field);
      const storage = getStorage();
      const stored = storage.getItem(name);
      const existing = current === null || current === undefined ? undefined : uuid(current);
      const persisted = stored === null ? undefined : uuid(stored);
      if (existing && existing !== persisted)
        throw new Error('Request key state does not match its persisted attempt');
      return persisted || null;
    },
    ensure(field, current) {
      const name = key(field);
      const storage = getStorage();
      const existing = read(storage, name, current);
      if (!existing && typeof globalThis.crypto?.randomUUID !== 'function')
        throw new Error('Secure request key generation is unavailable');
      const value = existing || uuid(globalThis.crypto.randomUUID());
      // Persist before exposing the key to the outbound flow. Storage failure aborts it.
      storage.setItem(name, value);
      return value;
    },
    clear(field, current) {
      const name = key(field);
      const storage = getStorage();
      read(storage, name, current);
      storage.removeItem(name);
    },
  };
}
