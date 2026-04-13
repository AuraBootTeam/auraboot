/**
 * PKCE (Proof Key for Code Exchange) Utilities
 *
 * Implements RFC 7636 for secure OAuth 2.0 authorization code flow.
 * Used for OpenAI Codex OAuth authentication.
 *
 * Token storage is now server-side only. This module only handles
 * PKCE generation and temporary session storage for the OAuth flow.
 *
 * @see https://datatracker.ietf.org/doc/html/rfc7636
 * @since 1.0.0
 */

// ============================================================================
// Constants
// ============================================================================

const VERIFIER_LENGTH = 64;
const ALLOWED_CHARS = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~';

// ============================================================================
// Code Verifier
// ============================================================================

/**
 * Generate a cryptographically random code verifier.
 * Must be between 43-128 characters using unreserved URI characters.
 */
export function generateCodeVerifier(): string {
  const array = new Uint8Array(VERIFIER_LENGTH);
  crypto.getRandomValues(array);

  let verifier = '';
  for (let i = 0; i < array.length; i++) {
    verifier += ALLOWED_CHARS[array[i] % ALLOWED_CHARS.length];
  }

  return verifier;
}

// ============================================================================
// Code Challenge
// ============================================================================

/**
 * Generate code challenge from verifier using S256 method.
 * challenge = BASE64URL(SHA256(verifier))
 */
export async function generateCodeChallenge(verifier: string): Promise<string> {
  const encoder = new TextEncoder();
  const data = encoder.encode(verifier);

  // SHA-256 hash
  const hashBuffer = await crypto.subtle.digest('SHA-256', data);

  // Convert to base64url (no padding)
  const hashArray = new Uint8Array(hashBuffer);
  const base64 = btoa(String.fromCharCode(...hashArray));
  const base64url = base64.replace(/\+/g, '-').replace(/\//g, '_').replace(/=/g, '');

  return base64url;
}

// ============================================================================
// State Parameter
// ============================================================================

/**
 * Generate a random state parameter for CSRF protection.
 */
export function generateState(): string {
  const array = new Uint8Array(32);
  crypto.getRandomValues(array);
  return Array.from(array, (byte) => byte.toString(16).padStart(2, '0')).join('');
}

// ============================================================================
// Session Storage (for OAuth flow only — temporary)
// ============================================================================

const STORAGE_KEYS = {
  CODE_VERIFIER: 'aurabot_oauth_code_verifier',
  STATE: 'aurabot_oauth_state',
} as const;

/**
 * Store code verifier in session storage for the OAuth flow.
 */
export function storeCodeVerifier(verifier: string): void {
  sessionStorage.setItem(STORAGE_KEYS.CODE_VERIFIER, verifier);
}

/**
 * Retrieve and clear code verifier from session storage.
 */
export function retrieveCodeVerifier(): string | null {
  const verifier = sessionStorage.getItem(STORAGE_KEYS.CODE_VERIFIER);
  sessionStorage.removeItem(STORAGE_KEYS.CODE_VERIFIER);
  return verifier;
}

/**
 * Store state in session storage for CSRF validation.
 */
export function storeState(state: string): void {
  sessionStorage.setItem(STORAGE_KEYS.STATE, state);
}

/**
 * Retrieve and clear state from session storage.
 */
export function retrieveState(): string | null {
  const state = sessionStorage.getItem(STORAGE_KEYS.STATE);
  sessionStorage.removeItem(STORAGE_KEYS.STATE);
  return state;
}
