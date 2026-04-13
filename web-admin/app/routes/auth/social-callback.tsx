/**
 * OAuth Social Login Callback Page
 *
 * Handles the redirect from OAuth providers (WeChat/Google/Apple).
 * Parses the authorization code from URL params, exchanges it for a JWT,
 * and handles the account merge flow if needed.
 *
 * Route: /login/social/:provider/callback
 */

import { useState, useEffect, useCallback } from 'react';
import { useParams, useSearchParams, useNavigate } from 'react-router';
import { fetchResult } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

interface AuthResponse {
  jwt: string | null;
  userPid: string | null;
  username: string | null;
  tenantId: number | null;
  tenantStatus: string;
  mustChangePassword: boolean;
  mergeRequired: boolean;
  mergeToken: string | null;
  mergeProvider: string | null;
}

type CallbackState = 'loading' | 'merge' | 'success' | 'error';

const PROVIDER_LABELS: Record<string, string> = {
  wechat: 'WeChat',
  wechat_web: 'WeChat',
  google: 'Google',
  apple: 'Apple',
  oidc: 'Enterprise SSO',
};

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

export default function SocialCallback() {
  const { provider } = useParams<{ provider: string }>();
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();

  const [state, setState] = useState<CallbackState>('loading');
  const [error, setError] = useState<string>('');
  const [mergeToken, setMergeToken] = useState<string>('');
  const [mergeProvider, setMergeProvider] = useState<string>('');
  const [password, setPassword] = useState('');
  const [merging, setMerging] = useState(false);
  const [mergeError, setMergeError] = useState('');

  // Exchange the authorization code for a JWT
  const exchangeCode = useCallback(async () => {
    const code = searchParams.get('code');
    const stateParam = searchParams.get('state');

    if (!code) {
      setError('Missing authorization code from provider');
      setState('error');
      return;
    }

    try {
      const redirectUri = `${window.location.origin}/login/social/${provider}/callback`;
      const result = await fetchResult<AuthResponse>(
        `/api/auth/login/social/${provider}/callback`,
        {
          method: 'post',
          params: {
            code,
            state: stateParam,
            redirectUri,
          },
        },
      );

      if (!ResultHelper.isSuccess(result)) {
        setError(result.message || 'Authentication failed');
        setState('error');
        return;
      }

      const data = result.data;
      if (!data) {
        setError('No data received');
        setState('error');
        return;
      }

      if (data.mergeRequired && data.mergeToken) {
        // Account merge required
        setMergeToken(data.mergeToken);
        setMergeProvider(data.mergeProvider || provider || '');
        setState('merge');
        return;
      }

      if (data.jwt) {
        // Success — store token and redirect
        // Use form POST to create session via Remix action
        const form = document.createElement('form');
        form.method = 'post';
        form.action = '/login';

        const fields: Record<string, string> = {
          intent: 'social-callback',
          token: data.jwt,
          redirectTo: '/',
        };

        for (const [key, value] of Object.entries(fields)) {
          const input = document.createElement('input');
          input.type = 'hidden';
          input.name = key;
          input.value = value;
          form.appendChild(input);
        }

        document.body.appendChild(form);
        form.submit();
        setState('success');
        return;
      }

      setError('No token received');
      setState('error');
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Network error during authentication');
      setState('error');
    }
  }, [provider, searchParams]);

  useEffect(() => {
    exchangeCode();
  }, [exchangeCode]);

  // Handle merge confirmation
  const handleMergeConfirm = async () => {
    if (!password.trim()) {
      setMergeError('Please enter your password');
      return;
    }

    setMerging(true);
    setMergeError('');

    try {
      const result = await fetchResult<AuthResponse>('/api/auth/login/social/confirm-merge', {
        method: 'post',
        params: {
          mergeToken,
          password,
        },
      });

      if (!ResultHelper.isSuccess(result)) {
        setMergeError(result.message || 'Merge failed');
        setMerging(false);
        return;
      }

      if (result.data?.jwt) {
        const form = document.createElement('form');
        form.method = 'post';
        form.action = '/login';

        const fields: Record<string, string> = {
          intent: 'social-callback',
          token: result.data.jwt,
          redirectTo: '/',
        };

        for (const [key, value] of Object.entries(fields)) {
          const input = document.createElement('input');
          input.type = 'hidden';
          input.name = key;
          input.value = value;
          form.appendChild(input);
        }

        document.body.appendChild(form);
        form.submit();
        setState('success');
      } else {
        setMergeError('No token received after merge');
        setMerging(false);
      }
    } catch (err) {
      setMergeError(err instanceof Error ? err.message : 'Network error');
      setMerging(false);
    }
  };

  const providerLabel = PROVIDER_LABELS[provider || ''] || provider || 'OAuth';

  // -------------------------------------------------------------------------
  // Render
  // -------------------------------------------------------------------------

  // Loading state
  if (state === 'loading' || state === 'success') {
    return (
      <div className="flex min-h-screen items-center justify-center bg-gradient-to-br from-blue-50 to-indigo-100 dark:from-gray-900 dark:to-gray-800">
        <div className="mx-4 w-full max-w-md rounded-2xl bg-white p-8 text-center shadow-xl dark:bg-gray-800">
          <div className="mx-auto mb-4 h-12 w-12 animate-spin rounded-full border-b-2 border-blue-600" />
          <h2 className="mb-2 text-xl font-semibold text-gray-800 dark:text-white">
            {state === 'success' ? 'Redirecting...' : `Signing in with ${providerLabel}...`}
          </h2>
          <p className="text-gray-500 dark:text-gray-400">
            Please wait while we complete authentication.
          </p>
        </div>
      </div>
    );
  }

  // Error state
  if (state === 'error') {
    return (
      <div className="flex min-h-screen items-center justify-center bg-gradient-to-br from-blue-50 to-indigo-100 dark:from-gray-900 dark:to-gray-800">
        <div className="mx-4 w-full max-w-md rounded-2xl bg-white p-8 text-center shadow-xl dark:bg-gray-800">
          <div className="mx-auto mb-4 flex h-12 w-12 items-center justify-center rounded-full bg-red-100 dark:bg-red-900/30">
            <svg
              className="h-6 w-6 text-red-600 dark:text-red-400"
              fill="none"
              stroke="currentColor"
              viewBox="0 0 24 24"
            >
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M6 18L18 6M6 6l12 12"
              />
            </svg>
          </div>
          <h2 className="mb-2 text-xl font-semibold text-gray-800 dark:text-white">
            Authentication Failed
          </h2>
          <p className="mb-6 text-gray-500 dark:text-gray-400">{error}</p>
          <button
            onClick={() => navigate('/login')}
            className="rounded-lg bg-blue-600 px-6 py-2 text-white transition-colors hover:bg-blue-700"
            data-testid="social-callback-back-btn"
          >
            Back to Login
          </button>
        </div>
      </div>
    );
  }

  // Merge confirmation state
  return (
    <div className="flex min-h-screen items-center justify-center bg-gradient-to-br from-blue-50 to-indigo-100 dark:from-gray-900 dark:to-gray-800">
      <div
        className="mx-4 w-full max-w-md rounded-2xl bg-white p-8 shadow-xl dark:bg-gray-800"
        data-testid="social-merge-dialog"
      >
        <div className="mx-auto mb-4 flex h-12 w-12 items-center justify-center rounded-full bg-amber-100 dark:bg-amber-900/30">
          <svg
            className="h-6 w-6 text-amber-600 dark:text-amber-400"
            fill="none"
            stroke="currentColor"
            viewBox="0 0 24 24"
          >
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={2}
              d="M13.828 10.172a4 4 0 00-5.656 0l-4 4a4 4 0 105.656 5.656l1.102-1.101m-.758-4.899a4 4 0 005.656 0l4-4a4 4 0 00-5.656-5.656l-1.1 1.1"
            />
          </svg>
        </div>

        <h2 className="mb-2 text-center text-xl font-semibold text-gray-800 dark:text-white">
          Account Already Exists
        </h2>
        <p className="mb-6 text-center text-gray-500 dark:text-gray-400">
          An account with the same email already exists. Enter your password to link your{' '}
          {PROVIDER_LABELS[mergeProvider] || mergeProvider} account.
        </p>

        <div className="space-y-4">
          <div>
            <label
              htmlFor="merge-password"
              className="mb-1 block text-sm font-medium text-gray-700 dark:text-gray-300"
            >
              Password
            </label>
            <input
              id="merge-password"
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && handleMergeConfirm()}
              className="w-full rounded-lg border border-gray-300 bg-white px-4 py-2 text-gray-900 focus:border-blue-500 focus:ring-2 focus:ring-blue-500 dark:border-gray-600 dark:bg-gray-700 dark:text-white"
              placeholder="Enter your existing account password"
              data-testid="merge-password-input"
            />
          </div>

          {mergeError && <p className="text-sm text-red-600 dark:text-red-400">{mergeError}</p>}

          <div className="flex gap-3">
            <button
              onClick={() => navigate('/login')}
              className="flex-1 rounded-lg border border-gray-300 px-4 py-2 text-gray-700 transition-colors hover:bg-gray-50 dark:border-gray-600 dark:text-gray-300 dark:hover:bg-gray-700"
              data-testid="merge-cancel-btn"
            >
              Cancel
            </button>
            <button
              onClick={handleMergeConfirm}
              disabled={merging || !password.trim()}
              className="flex-1 rounded-lg bg-blue-600 px-4 py-2 text-white transition-colors hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-50"
              data-testid="merge-confirm-btn"
            >
              {merging ? 'Linking...' : 'Link Account'}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
