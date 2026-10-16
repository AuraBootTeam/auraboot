import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { IntegrationTokenPanel, type IntegrationTokenView } from '../IntegrationTokenPanel';

vi.mock('~/contexts/I18nContext', () => ({ useI18n: () => ({ locale: 'en-US', t: (_key: string, _params: unknown, fallback: string) => fallback }) }));
const base = '/api/open-platform/installations/inst-1/integration-tokens';
const row: IntegrationTokenView = { pid: 'token-1', name: 'Office devices', status: 'active', expirationMode: 'never', issuedAt: '2026-10-15T00:00:00Z', expiresAt: null, lastUsedAt: null, acceptUntil: null, rotatedAt: null, replacedByPid: null };
const ok = (data: unknown) => Promise.resolve({ ok: true, status: 200, json: async () => ({ code: '0', data }) });

describe('IntegrationTokenPanel', () => {
  beforeEach(() => vi.stubGlobal('fetch', vi.fn(() => ok([]))));
  afterEach(() => vi.unstubAllGlobals());

  it('explicitly creates a permanent token, clears one-time material, and never reads it back from the list', async () => {
    const user = userEvent.setup();
    let created = false;
    vi.stubGlobal('fetch', vi.fn((_path: string, init?: RequestInit) => {
      if (init?.method === 'POST') { created = true; return ok({ appId: 'app-1', installationPid: 'inst-1', token: 'ab_at_once', metadata: row }); }
      return ok(created ? [row] : []);
    }));
    render(<IntegrationTokenPanel appId="app-1" installationPid="inst-1" active onClose={vi.fn()} />);
    await screen.findByText('No integration tokens yet.');
    await user.click(screen.getByRole('button', { name: 'Generate token' }));
    await user.type(screen.getByLabelText('Token name'), 'Office devices');
    await user.selectOptions(screen.getByLabelText('Expiration mode'), 'never');
    expect(screen.queryByLabelText('Valid for days')).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Confirm' }));
    expect(await screen.findByLabelText('New token')).toHaveValue('ab_at_once');
    expect(fetch).toHaveBeenCalledWith(base, expect.objectContaining({ method: 'POST', headers: expect.objectContaining({ 'Idempotency-Key': expect.any(String) }), body: JSON.stringify({ expirationMode: 'never', name: 'Office devices' }) }));
    await user.click(screen.getByRole('button', { name: 'Saved; close secret' }));
    expect(screen.queryByDisplayValue('ab_at_once')).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Refresh token list' }));
    expect(await screen.findByTestId('integration-token-row')).toHaveTextContent('Office devices');
    expect(screen.queryByLabelText('New token')).not.toBeInTheDocument();
  });

  it('rotates with explicit zero grace and displays the server cutoff rather than calculating it', async () => {
    const user = userEvent.setup();
    vi.stubGlobal('fetch', vi.fn((_path: string, init?: RequestInit) => init?.method === 'POST'
      ? ok({ credential: { appId: 'app-1', installationPid: 'inst-1', token: 'ab_at_replacement', metadata: { ...row, pid: 'token-2' } }, replacedTokenAcceptUntil: '2026-10-15T12:00:00Z' }) : ok([row])));
    render(<IntegrationTokenPanel appId="app-1" installationPid="inst-1" active onClose={vi.fn()} />);
    await user.click(await screen.findByRole('button', { name: 'Rotate token' }));
    await user.selectOptions(screen.getByLabelText('Expiration mode'), 'never');
    await user.clear(screen.getByLabelText('Old token grace minutes'));
    await user.type(screen.getByLabelText('Old token grace minutes'), '0');
    await user.click(screen.getByRole('button', { name: 'Confirm' }));
    await screen.findByDisplayValue('ab_at_replacement');
    expect(fetch).toHaveBeenCalledWith(base + '/token-1/rotate', expect.objectContaining({ body: JSON.stringify({ expirationMode: 'never', graceMinutes: 0 }) }));
    expect(screen.getByTestId('integration-token-secret')).toHaveTextContent(new Date('2026-10-15T12:00:00Z').toLocaleString('en-US'));
  });

  it('requires confirmation before revoking an inactive installation token and keeps rotation disabled', async () => {
    const user = userEvent.setup(); let revoked = false;
    vi.stubGlobal('fetch', vi.fn((_path: string, init?: RequestInit) => {
      if (init?.method === 'DELETE') { revoked = true; return ok(null); }
      return ok([{ ...row, status: revoked ? 'revoked' : 'active' }]);
    }));
    render(<IntegrationTokenPanel appId="app-1" installationPid="inst-1" active={false} onClose={vi.fn()} />);
    expect(await screen.findByRole('button', { name: 'Rotate token' })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: 'Revoke token' }));
    expect(fetch).not.toHaveBeenCalledWith(base + '/token-1', expect.objectContaining({ method: 'DELETE' }));
    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    await user.click(screen.getByRole('button', { name: 'Revoke token' }));
    await user.click(screen.getByRole('button', { name: 'Confirm' }));
    await waitFor(() => expect(within(screen.getByTestId('integration-token-row')).getByRole('button', { name: 'Revoke token' })).toBeDisabled());
    expect(screen.getByRole('button', { name: 'Generate token' })).toBeDisabled();
  });

  it('fails closed on permission loss and prevents generation after an unauthorized list read', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve({ ok: false, status: 403, json: async () => ({ message: 'denied' }) })));
    render(<IntegrationTokenPanel appId="app-1" installationPid="inst-1" active onClose={vi.fn()} />);
    expect(await screen.findByRole('alert')).toHaveTextContent('Application runtime-management permission is required.');
    expect(screen.getByRole('button', { name: 'Generate token' })).toBeDisabled();
    expect(screen.queryByTestId('integration-token-row')).not.toBeInTheDocument();
  });
});
