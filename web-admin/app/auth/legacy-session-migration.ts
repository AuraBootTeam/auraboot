import {
  JWT_TOKEN_KEY,
  REFRESH_TOKEN_KEY,
  REMEMBER_KEY,
  TOKEN_EXPIRY_KEY,
} from '~/constants/AuthConstant';

const LEGACY_AUTH_KEYS = [
  JWT_TOKEN_KEY,
  'jwt',
  REFRESH_TOKEN_KEY,
  TOKEN_EXPIRY_KEY,
  REMEMBER_KEY,
] as const;

interface LegacyToken {
  token: string;
  remember: boolean;
}

function readToken(storage: Storage | undefined, remember: boolean): LegacyToken | null {
  if (!storage) return null;
  try {
    const token = storage.getItem(JWT_TOKEN_KEY) ?? storage.getItem('jwt');
    return token ? { token, remember } : null;
  } catch {
    return null;
  }
}

function clearLegacyAuthStorage(storage: Storage | undefined): void {
  if (!storage) return;
  try {
    for (const key of LEGACY_AUTH_KEYS) storage.removeItem(key);
  } catch {
    // Storage may be unavailable in hardened/private browser modes.
  }
}

export function clearLegacyBrowserAuthStorage(target: Window = window): void {
  clearLegacyAuthStorage(target.sessionStorage);
  clearLegacyAuthStorage(target.localStorage);
}

/**
 * Exchanges a pre-cookie browser JWT for the server-owned httpOnly session once.
 * The token is never placed in a URL or response body and is deleted after a
 * successful exchange (or after the server proves it is no longer valid).
 */
export async function migrateLegacyBrowserSession(
  target: Window = window,
  fetchImpl: typeof fetch = fetch,
): Promise<boolean> {
  const legacy =
    readToken(target.sessionStorage, false) ?? readToken(target.localStorage, true);
  if (!legacy) return false;

  let response: Response;
  try {
    response = await fetchImpl('/api/auth/session-migrate', {
      method: 'POST',
      credentials: 'same-origin',
      headers: {
        Authorization: `Bearer ${legacy.token}`,
        'X-Aura-Remember': legacy.remember ? 'true' : 'false',
      },
    });
  } catch {
    // Preserve the legacy token on a transient network failure so the user can retry.
    return false;
  }

  if (response.ok) {
    clearLegacyBrowserAuthStorage(target);
    return true;
  }
  if (response.status === 401 || response.status === 403) {
    clearLegacyBrowserAuthStorage(target);
  }
  return false;
}
