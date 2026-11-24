import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { CallAuditMessageDetails } from '../CallAuditMessageDetails';
import { openPlatformApiFetch, OpenPlatformApiError } from '../open-platform-api';

vi.mock('~/contexts/I18nContext', () => ({ useI18n: () => ({ locale: 'en', t: (_key: string, _params: unknown, fallback: string) => fallback }) }));
vi.mock('../open-platform-api', async importOriginal => ({ ...await importOriginal<typeof import('../open-platform-api')>(), openPlatformApiFetch: vi.fn() }));
const api = vi.mocked(openPlatformApiFetch);
const captured = (value: string) => ({ httpDetails: { request: { state: 'captured', body: { value } }, response: { state: 'captured', body: { received: true } } } });
const toggle = () => fireEvent.click(screen.getByTestId('call-audit-messages-toggle'));
beforeEach(() => { api.mockReset(); });

describe('existing audit message disclosure', () => {
  it('loads the exact authorized record only after opening, and closes inline', async () => {
    api.mockResolvedValue(captured('actual-request'));
    render(<CallAuditMessageDetails installationPid="installation" auditPid="audit" />);
    expect(api).not.toHaveBeenCalled();
    toggle();
    expect(await screen.findByText(/actual-request/)).toBeVisible();
    expect(screen.getByText(/"received": true/)).toBeVisible();
    expect(api).toHaveBeenCalledWith('/api/open-platform/installations/installation/audits/audit', expect.objectContaining({ signal: expect.any(AbortSignal) }));
    toggle();
    expect(screen.queryByTestId('http-message-details')).not.toBeInTheDocument();
  });

  it('keeps forbidden detail distinct and never retries automatically', async () => {
    api.mockRejectedValue(new OpenPlatformApiError(403, 'do-not-render-server-context'));
    render(<CallAuditMessageDetails installationPid="installation" auditPid="audit" />);
    toggle();
    expect(await screen.findByRole('alert')).toHaveTextContent('You do not have permission');
    expect(screen.queryByRole('button', { name: 'Retry' })).not.toBeInTheDocument();
    expect(screen.queryByText('do-not-render-server-context')).not.toBeInTheDocument();
    expect(api).toHaveBeenCalledTimes(1);
  });

  it('allows an explicit retry after failure', async () => {
    api.mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce(captured('recovered'));
    render(<CallAuditMessageDetails installationPid="installation" auditPid="audit" />);
    toggle();
    fireEvent.click(await screen.findByRole('button', { name: 'Retry' }));
    expect(await screen.findByText(/recovered/)).toBeVisible();
    expect(api).toHaveBeenCalledTimes(2);
  });

  it('aborts an old context and cannot display its late result', async () => {
    let resolveOld!: (value: unknown) => void;
    api.mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve; })).mockResolvedValueOnce(captured('new-installation'));
    const { rerender } = render(<CallAuditMessageDetails installationPid="old" auditPid="audit" />);
    toggle();
    expect(screen.getByRole('status')).toHaveTextContent('Loading');
    const signal = api.mock.calls[0][1]?.signal;
    rerender(<CallAuditMessageDetails installationPid="new" auditPid="audit" />);
    expect(signal?.aborted).toBe(true);
    expect(await screen.findByText(/new-installation/)).toBeVisible();
    resolveOld(captured('old-secret-context'));
    await waitFor(() => expect(screen.queryByText(/old-secret-context/)).not.toBeInTheDocument());
  });

  it('does not invent bodies for historical records', async () => {
    api.mockResolvedValue({ httpDetails: null });
    render(<CallAuditMessageDetails installationPid="installation" auditPid="audit" />);
    toggle();
    expect(await screen.findAllByText('Body was not captured; historical content is unavailable')).toHaveLength(2);
  });
});
