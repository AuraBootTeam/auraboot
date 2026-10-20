import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import SecuritySettings from '../security';
const mocks = vi.hoisted(() => ({ fetch: vi.fn(), revoke: vi.fn(), all: vi.fn(), success: vi.fn(), error: vi.fn() }));
vi.mock('~/shared/services/activeSessionService', () => ({ fetchActiveSessions: mocks.fetch, revokeActiveSession: mocks.revoke, revokeAllActiveSessions: mocks.all }));
vi.mock('~/contexts/ToastContext', () => ({ useToast: () => ({ showSuccessToast: mocks.success, showErrorToast: mocks.error }) }));
vi.mock('~/contexts/I18nContext', () => ({ useI18n: () => ({ t: (key: string) => key }) }));
const row = { pid: 'session-pid', deviceInfo: 'Owned browser', ipAddress: '127.0.0.1', createdAt: '2026-10-18T00:00:00Z', lastActiveAt: '2026-10-18T01:00:00Z' };
describe('personal security generated session consumer', () => {
  beforeEach(() => { vi.resetAllMocks(); mocks.fetch.mockResolvedValue([row]); });
  it('shows load failure instead of an empty success and explicit retry recovers', async () => {
    mocks.fetch.mockRejectedValueOnce(new Error('offline'));
    render(<SecuritySettings />);
    expect(await screen.findByRole('alert')).toHaveTextContent('session_security.load_failed');
    expect(screen.queryByText('session_security.empty')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'session_security.retry' }));
    expect(await screen.findByText('Owned browser')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).toBeNull();
  });
  it('keeps a session row and refuses success feedback when revocation fails', async () => {
    mocks.revoke.mockRejectedValueOnce(new Error('Denied'));
    render(<SecuritySettings />);
    fireEvent.click(await screen.findByTestId('revoke-session-session-pid'));
    await waitFor(() => expect(mocks.error).toHaveBeenCalledWith('session_security.revoke_failed'));
    expect(screen.getByText('Owned browser')).toBeInTheDocument();
    expect(mocks.success).not.toHaveBeenCalled();
  });
  it('removes the exact public session PID after successful revocation', async () => {
    mocks.revoke.mockResolvedValueOnce(undefined);
    render(<SecuritySettings />);
    fireEvent.click(await screen.findByTestId('revoke-session-session-pid'));
    await waitFor(() => expect(screen.queryByText('Owned browser')).toBeNull());
    expect(mocks.revoke).toHaveBeenCalledExactlyOnceWith('session-pid');
    expect(mocks.success).toHaveBeenCalledWith('session_security.revoked');
    expect(screen.getByText('session_security.empty')).toBeInTheDocument();
  });
  it('does not announce logout everywhere when its request fails', async () => {
    mocks.fetch.mockResolvedValueOnce([row, { ...row, pid: 'second-session', deviceInfo: 'Second browser' }]);
    mocks.all.mockRejectedValueOnce(new Error('Denied'));
    render(<SecuritySettings />);
    fireEvent.click(await screen.findByTestId('revoke-all-sessions-btn'));
    await waitFor(() => expect(mocks.error).toHaveBeenCalledWith('session_security.revoke_all_failed'));
    expect(mocks.success).not.toHaveBeenCalled();
    expect(screen.getByText('Owned browser')).toBeInTheDocument();
    expect(screen.getByText('Second browser')).toBeInTheDocument();
  });
});
