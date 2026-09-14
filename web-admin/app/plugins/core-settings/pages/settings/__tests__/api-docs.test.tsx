import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import OpenPlatformPage from '../api-docs';

const showToast = vi.fn();
const translate = vi.hoisted(() =>
  (_key: string, _params?: Record<string, unknown>, fallback?: string) => fallback);

vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({ t: translate }),
}));
vi.mock('~/contexts/ToastContext', () => ({ useToastContext: () => ({ showToast }) }));

const capability = {
  code: 'open.whoami', method: 'GET', pathPattern: '/api/open/v1/whoami',
  requiredScope: 'openapi.profile.read', dataPolicy: 'tenant', schemaVersion: 1,
};

function response(data: unknown) {
  return Promise.resolve({ ok: true, status: 200, json: async () => ({ code: '0', data }) });
}

describe('OpenPlatformPage', () => {
  beforeEach(() => {
    showToast.mockReset();
    vi.stubGlobal('fetch', vi.fn((url: string) => {
      if (url.endsWith('/capabilities')) return response([capability]);
      return response([]);
    }));
  });

  afterEach(() => vi.unstubAllGlobals());

  it('renders an actionable empty state and creates an application', async () => {
    const user = userEvent.setup();
    render(<OpenPlatformPage />);
    expect(await screen.findByTestId('open-platform-empty')).toBeVisible();

    await user.click(screen.getByTestId('open-platform-create-app'));
    await user.type(screen.getByTestId('open-platform-app-name'), 'ERP Bridge');
    await user.click(screen.getByRole('button', { name: 'Create' }));

    await waitFor(() => expect(fetch).toHaveBeenCalledWith('/api/open-platform/applications',
      expect.objectContaining({ method: 'POST', body: JSON.stringify({ name: 'ERP Bridge', description: null }) })));
    expect(showToast).toHaveBeenCalledWith('Application created', 'success');
  });

  it('shows one-time credentials and warns before scope or installation revocation', async () => {
    const installation = {
      pid: 'inst-1', environment: 'production', status: 'active',
      scopes: ['openapi.profile.read'], rateLimitPerMinute: 600, installedAt: '2026-09-14T00:00:00Z',
    };
    vi.stubGlobal('fetch', vi.fn((url: string, init?: RequestInit) => {
      if (url.endsWith('/capabilities')) return response([capability]);
      if (url.endsWith('/applications')) return response([{
        pid: 'app-1', name: 'ERP Bridge', status: 'active', createdAt: '2026-09-14T00:00:00Z',
        installations: [installation],
      }]);
      if (url.endsWith('/credentials') && init?.method === 'POST') return response({
        credentialPid: 'cred-1', clientId: 'ab_client', clientSecret: 'ab_secret',
        createdAt: '2026-09-14T00:00:00Z',
      });
      if (url.endsWith('/credentials')) return response([]);
      return response(undefined);
    }));
    const user = userEvent.setup();
    render(<OpenPlatformPage />);
    expect(await screen.findByText('ERP Bridge')).toBeVisible();
    expect(screen.getByText('Rate limit: 600/min')).toBeVisible();
    expect(screen.queryByText('app-1')).not.toBeInTheDocument();
    expect(screen.queryByText('inst-1')).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'New credential' }));
    expect(await screen.findByText('Save this credential now')).toBeVisible();
    expect(screen.getByText('ab_secret')).toBeVisible();
    await user.click(screen.getByRole('button', { name: 'I saved it' }));

    await user.click(screen.getByRole('button', { name: 'Edit scopes' }));
    expect(screen.getByText(/immediately revokes existing access tokens/)).toBeVisible();
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));

    await user.click(screen.getByRole('button', { name: 'Disable installation' }));
    expect(screen.getByText(/immediately revokes active tokens/)).toBeVisible();
  });

  it('configures the installation rate limit and explicit scopes', async () => {
    vi.stubGlobal('fetch', vi.fn((url: string, init?: RequestInit) => {
      if (url.endsWith('/capabilities')) return response([capability]);
      if (url.endsWith('/applications') && !init?.method) return response([{
        pid: 'app-2', name: 'Warehouse Connector', status: 'active',
        createdAt: '2026-09-14T00:00:00Z', installations: [],
      }]);
      return response({});
    }));
    const user = userEvent.setup();
    render(<OpenPlatformPage />);
    expect(await screen.findByText('Warehouse Connector')).toBeVisible();

    await user.click(screen.getByRole('button', { name: 'Add installation' }));
    const rateLimit = screen.getByTestId('open-platform-rate-limit');
    await user.clear(rateLimit);
    await user.type(rateLimit, '1200');
    await user.click(screen.getByRole('button', { name: 'Install' }));

    await waitFor(() => expect(fetch).toHaveBeenCalledWith(
      '/api/open-platform/applications/app-2/installations',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({
          environment: 'development',
          scopes: ['openapi.profile.read'],
          rateLimitPerMinute: 1200,
        }),
      }),
    ));
  });
});
