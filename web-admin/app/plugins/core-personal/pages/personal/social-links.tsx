/**
 * User Social Account Binding Page
 *
 * Displays linked social accounts (WeChat/Google/Apple) and allows
 * users to bind or unbind social login providers.
 *
 * Route: /personal/social-links
 * API: GET /api/user/social-links
 *      POST /api/user/social-links/{provider}/link
 *      DELETE /api/user/social-links/{provider}
 */

import { useState, useEffect, useCallback } from 'react';
import { Link } from 'react-router';
import { fetchResult } from '~/services/http-client';
import { ResultHelper } from '~/utils/type';
import { useToast } from '~/contexts/ToastContext';

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

interface SocialLink {
  pid: string;
  provider: string;
  displayName: string | null;
  avatarUrl: string | null;
  email: string | null;
  linkedAt: string;
}

interface DeactivationStatus {
  pid: string;
  status: string;
  reason: string | null;
  requestedAt: string;
  coolingOffUntil: string;
  cancelledAt: string | null;
  completedAt: string | null;
}

// All supported social providers
const PROVIDERS = [
  {
    code: 'wechat_web',
    label: 'WeChat',
    color: 'bg-green-500',
    icon: (
      <svg className="h-6 w-6" viewBox="0 0 24 24" fill="currentColor">
        <path d="M8.691 2.188C3.891 2.188 0 5.476 0 9.53c0 2.212 1.17 4.203 3.002 5.55a.59.59 0 0 1 .213.665l-.39 1.48c-.019.07-.048.141-.048.213 0 .163.13.295.29.295a.326.326 0 0 0 .167-.054l1.903-1.114a.864.864 0 0 1 .717-.098 10.16 10.16 0 0 0 2.837.403c.276 0 .543-.027.811-.05-.857-2.578.157-4.972 1.932-6.446 1.703-1.415 3.882-1.98 5.853-1.838-.576-3.583-4.196-6.348-8.596-6.348z" />
      </svg>
    ),
  },
  {
    code: 'google',
    label: 'Google',
    color: 'bg-red-500',
    icon: (
      <svg className="h-6 w-6" viewBox="0 0 24 24" fill="currentColor">
        <path
          d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92a5.06 5.06 0 0 1-2.2 3.32v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.1z"
          fill="#4285F4"
        />
        <path
          d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84C3.99 20.53 7.7 23 12 23z"
          fill="#34A853"
        />
        <path
          d="M5.84 14.09c-.22-.66-.35-1.36-.35-2.09s.13-1.43.35-2.09V7.07H2.18C1.43 8.55 1 10.22 1 12s.43 3.45 1.18 4.93l2.85-2.22.81-.62z"
          fill="#FBBC05"
        />
        <path
          d="M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1 7.7 1 3.99 3.47 2.18 7.07l3.66 2.84c.87-2.6 3.3-4.53 6.16-4.53z"
          fill="#EA4335"
        />
      </svg>
    ),
  },
  {
    code: 'apple',
    label: 'Apple',
    color: 'bg-black',
    icon: (
      <svg className="h-6 w-6" viewBox="0 0 24 24" fill="currentColor">
        <path d="M17.05 20.28c-.98.95-2.05.88-3.08.4-1.09-.5-2.08-.48-3.24 0-1.44.62-2.2.44-3.06-.4C2.79 15.25 3.51 7.59 9.05 7.31c1.35.07 2.29.74 3.08.8 1.18-.24 2.31-.93 3.57-.84 1.51.12 2.65.72 3.4 1.8-3.12 1.87-2.38 5.98.48 7.13-.57 1.5-1.31 2.99-2.54 4.09zM12.03 7.25c-.15-2.23 1.66-4.07 3.74-4.25.29 2.58-2.34 4.5-3.74 4.25z" />
      </svg>
    ),
  },
];

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

export default function SocialLinksPage() {
  const { showSuccessToast, showErrorToast } = useToast();

  const [links, setLinks] = useState<SocialLink[]>([]);
  const [loading, setLoading] = useState(true);
  const [unlinking, setUnlinking] = useState<string | null>(null);

  // Fetch linked accounts
  const fetchLinks = useCallback(async () => {
    try {
      const result = await fetchResult<SocialLink[]>('/api/user/social-links', {
        method: 'get',
      });
      if (ResultHelper.isSuccess(result) && Array.isArray(result.data)) {
        setLinks(result.data);
      }
    } catch {
      // ignore — empty state is fine
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchLinks();
  }, [fetchLinks]);

  // Bind a new social account
  const handleBind = async (providerCode: string) => {
    try {
      const redirectUri = `${window.location.origin}/personal/social-links`;
      const result = await fetchResult<{ authorizeUrl: string }>(
        `/api/user/social-links/${providerCode}/link`,
        {
          method: 'post',
          params: { redirectUri },
        },
      );

      if (ResultHelper.isSuccess(result) && result.data?.authorizeUrl) {
        window.location.href = result.data.authorizeUrl;
      } else {
        showErrorToast(result.message || 'Failed to initiate linking');
      }
    } catch {
      showErrorToast('Network error');
    }
  };

  // Unbind a social account
  const handleUnbind = async (providerCode: string) => {
    setUnlinking(providerCode);
    try {
      const result = await fetchResult<void>(`/api/user/social-links/${providerCode}`, {
        method: 'delete',
      });

      if (ResultHelper.isSuccess(result)) {
        setLinks((prev) => prev.filter((l) => l.provider !== providerCode));
        showSuccessToast('Account unlinked successfully');
      } else {
        showErrorToast(result.message || 'Failed to unlink');
      }
    } catch {
      showErrorToast('Network error');
    } finally {
      setUnlinking(null);
    }
  };

  // Check if a provider is linked
  const getLink = (providerCode: string): SocialLink | undefined =>
    links.find((l) => l.provider === providerCode);

  // -------------------------------------------------------------------------
  // Render
  // -------------------------------------------------------------------------

  return (
    <div className="mx-auto max-w-3xl space-y-6 p-6">
      {/* Header */}
      <div className="flex items-center gap-3">
        <Link
          to="/personal/profile"
          className="rounded-lg p-2 text-gray-500 transition-colors hover:bg-gray-100 hover:text-gray-700"
          data-testid="social-links-back-btn"
        >
          <svg className="h-5 w-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={2}
              d="M15 19l-7-7 7-7"
            />
          </svg>
        </Link>
        <h1 className="text-2xl font-semibold text-gray-900">Social Account Binding</h1>
      </div>

      {/* Social accounts list */}
      <div className="divide-y divide-gray-200 rounded-lg bg-white shadow-md">
        {loading ? (
          <div className="p-8 text-center">
            <div className="mx-auto mb-2 h-8 w-8 animate-spin rounded-full border-b-2 border-blue-600" />
            <p className="text-gray-500">Loading...</p>
          </div>
        ) : (
          PROVIDERS.map((prov) => {
            const link = getLink(prov.code);
            const isUnlinking = unlinking === prov.code;

            return (
              <div
                key={prov.code}
                className="flex items-center justify-between p-4 transition-colors hover:bg-gray-50"
                data-testid={`social-link-${prov.code}`}
              >
                {/* Left: icon + info */}
                <div className="flex items-center gap-4">
                  <div
                    className={`h-10 w-10 ${prov.color} flex items-center justify-center rounded-full text-white`}
                  >
                    {prov.icon}
                  </div>
                  <div>
                    <p className="font-medium text-gray-900">{prov.label}</p>
                    {link ? (
                      <p className="text-sm text-gray-500">
                        {link.displayName || link.email || 'Linked'}{' '}
                        <span className="text-xs text-gray-400">
                          &middot; {new Date(link.linkedAt).toLocaleDateString()}
                        </span>
                      </p>
                    ) : (
                      <p className="text-sm text-gray-400">Not linked</p>
                    )}
                  </div>
                </div>

                {/* Right: action button */}
                <div>
                  {link ? (
                    <button
                      onClick={() => handleUnbind(prov.code)}
                      disabled={isUnlinking}
                      className="rounded-lg border border-red-300 px-4 py-1.5 text-sm text-red-600 transition-colors hover:bg-red-50 disabled:cursor-not-allowed disabled:opacity-50"
                      data-testid={`social-unlink-${prov.code}`}
                    >
                      {isUnlinking ? 'Unlinking...' : 'Unlink'}
                    </button>
                  ) : (
                    <button
                      onClick={() => handleBind(prov.code)}
                      className="rounded-lg border border-blue-300 px-4 py-1.5 text-sm text-blue-600 transition-colors hover:bg-blue-50"
                      data-testid={`social-bind-${prov.code}`}
                    >
                      Bind
                    </button>
                  )}
                </div>
              </div>
            );
          })
        )}
      </div>

      {/* Info box */}
      <div className="rounded-lg border border-blue-200 bg-blue-50 p-4">
        <div className="flex gap-3">
          <svg
            className="mt-0.5 h-5 w-5 flex-shrink-0 text-blue-600"
            fill="none"
            stroke="currentColor"
            viewBox="0 0 24 24"
          >
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={2}
              d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z"
            />
          </svg>
          <div className="text-sm text-blue-700">
            <p className="mb-1 font-medium">About Social Login</p>
            <ul className="list-inside list-disc space-y-1 text-blue-600">
              <li>Linked accounts allow one-click login without password</li>
              <li>Your data is never shared between providers</li>
              <li>You can unlink accounts at any time</li>
            </ul>
          </div>
        </div>
      </div>
    </div>
  );
}
