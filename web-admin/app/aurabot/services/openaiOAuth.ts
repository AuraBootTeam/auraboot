/**
 * OpenAI Codex OAuth Service
 *
 * Handles OAuth authentication with OpenAI to use ChatGPT Plus/Pro subscription
 * for API access instead of API credits.
 *
 * Token storage is now fully server-side — no tokens in localStorage.
 *
 * @see https://developers.openai.com/codex/auth/
 * @since 1.0.0
 */

import {
  generateCodeVerifier,
  generateCodeChallenge,
  generateState,
  storeCodeVerifier,
  storeState,
} from './pkce';

// ============================================================================
// Configuration
// ============================================================================

export const OPENAI_OAUTH_CONFIG = {
  authorizationEndpoint: 'https://auth.openai.com/oauth/authorize',
  tokenEndpoint: 'https://auth.openai.com/oauth/token',
  clientId: 'app_EMoamEEZ73f0CkXaXp7hrann',
  apiBaseUrl: 'https://api.openai.com/v1',
  scope: 'openid profile email offline_access',
  responseType: 'code',
  codeChallengeMethod: 's256',
} as const;

// ============================================================================
// Types
// ============================================================================

export interface OpenAIAuthState {
  isAuthenticated: boolean;
  isLoading: boolean;
  error: string | null;
  providers: ProviderInfo[];
}

export interface ProviderInfo {
  provider: string;
  authType: string;
  status: string;
  hasExpiry: boolean;
}

// ============================================================================
// OAuth Flow
// ============================================================================

/**
 * Start the OAuth authorization flow.
 * Calls backend to register the flow, then opens OAuth URL.
 * Polls for completion (backend handles token exchange via callback).
 */
export async function startOAuthFlow(provider = 'openai'): Promise<void> {
  const codeVerifier = generateCodeVerifier();
  const codeChallenge = await generateCodeChallenge(codeVerifier);
  const state = generateState();

  storeCodeVerifier(codeVerifier);
  storeState(state);

  // Register flow with backend
  const response = await fetch('/api/ai/aurabot/oauth/start', {
    method: 'post',
    headers: { 'Content-Type': 'application/json' },
    credentials: 'include',
    body: JSON.stringify({ codeVerifier, state, provider }),
  });

  if (!response.ok) {
    throw new Error('Failed to start OAuth flow');
  }

  const result = await response.json();
  const callbackUrl =
    result.data?.callbackUrl || `${window.location.origin}/api/ai/aurabot/oauth/callback`;

  // Build OAuth URL with callback pointing to our backend endpoint
  const authUrl = new URL(OPENAI_OAUTH_CONFIG.authorizationEndpoint);
  authUrl.searchParams.set('response_type', OPENAI_OAUTH_CONFIG.responseType);
  authUrl.searchParams.set('client_id', OPENAI_OAUTH_CONFIG.clientId);
  authUrl.searchParams.set('redirect_uri', callbackUrl);
  authUrl.searchParams.set('scope', OPENAI_OAUTH_CONFIG.scope);
  authUrl.searchParams.set('code_challenge', codeChallenge);
  authUrl.searchParams.set('code_challenge_method', OPENAI_OAUTH_CONFIG.codeChallengeMethod);
  authUrl.searchParams.set('state', state);
  authUrl.searchParams.set('id_token_add_organizations', 'true');
  authUrl.searchParams.set('codex_cli_simplified_flow', 'true');
  authUrl.searchParams.set('originator', 'aurabot');

  // Open OAuth URL in new window
  window.open(authUrl.toString(), '_blank');

  // Poll for completion (backend handles callback + token exchange)
  return new Promise((resolve, reject) => {
    let attempts = 0;
    const maxAttempts = 120; // 2 minutes

    const checkStatus = async () => {
      attempts++;
      if (attempts > maxAttempts) {
        reject(new Error('OAuth timeout'));
        return;
      }

      try {
        const statusResponse = await fetch('/api/ai/aurabot/oauth/status', {
          credentials: 'include',
        });
        const status = await statusResponse.json();

        if (status.data?.authenticated) {
          resolve();
          return;
        }
      } catch {
        // Continue polling
      }

      setTimeout(checkStatus, 1000);
    };

    checkStatus();
  });
}

// ============================================================================
// Authentication State (server-side)
// ============================================================================

/**
 * Check if user is authenticated. Async — queries backend.
 */
export async function isAuthenticated(): Promise<boolean> {
  try {
    const response = await fetch('/api/ai/aurabot/oauth/status', {
      credentials: 'include',
    });
    const result = await response.json();
    return result.data?.authenticated === true;
  } catch {
    return false;
  }
}

/**
 * Logout — clear server-side tokens.
 */
export async function logout(): Promise<void> {
  try {
    await fetch('/api/ai/aurabot/oauth/logout', {
      method: 'post',
      credentials: 'include',
    });
  } catch (e) {
    console.error('Failed to logout:', e);
  }
}

/**
 * Get authentication state from backend.
 */
export async function getAuthState(): Promise<OpenAIAuthState> {
  try {
    const response = await fetch('/api/ai/aurabot/oauth/status', {
      credentials: 'include',
    });
    const result = await response.json();

    return {
      isAuthenticated: result.data?.authenticated === true,
      isLoading: false,
      error: null,
      providers: result.data?.providers || [],
    };
  } catch {
    return {
      isAuthenticated: false,
      isLoading: false,
      error: null,
      providers: [],
    };
  }
}
