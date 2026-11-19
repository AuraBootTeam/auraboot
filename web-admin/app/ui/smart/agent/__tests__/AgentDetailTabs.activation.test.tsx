import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest';
import { MemoryRouter } from 'react-router';
import { ActivateAgentDialog } from '../AgentDetailTabs';
const { post } = vi.hoisted(() => ({ post: vi.fn() }));
vi.mock('~/shared/services/http-client', () => ({
  get: vi.fn(),
  post,
  put: vi.fn(),
  del: vi.fn(),
}));
vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({ t: (key: string, _params: unknown, fallback: string) => fallback || key }),
}));
const fetchProviders = vi.fn();
function open(onSuccess = vi.fn(), onClose = vi.fn()) {
  render(
    <MemoryRouter>
      <ActivateAgentDialog agentPid="TEMPLATE" onClose={onClose} onSuccess={onSuccess} />
    </MemoryRouter>,
  );
  return { onSuccess, onClose };
}
describe('First template activation', () => {
  beforeEach(() => {
    post.mockReset();
    fetchProviders.mockReset();
    vi.stubGlobal('fetch', fetchProviders);
    fetchProviders.mockResolvedValue({
      ok: true,
      json: async () => [{ providerCode: 'service-a', displayName: 'Configured service' }],
    });
    post.mockResolvedValue({ code: '0', data: { releaseNo: 1 } });
  });
  afterEach(() => vi.unstubAllGlobals());
  it('requires explicit selection and sends only the selected provider', async () => {
    const { onSuccess } = open();
    const select = await screen.findByTestId('activation-provider-select');
    expect(select).toHaveValue('');
    fireEvent.click(screen.getByTestId('confirm-activate-agent'));
    expect(await screen.findByRole('alert')).toHaveTextContent('Choose an AI service first.');
    expect(post).not.toHaveBeenCalled();
    fireEvent.change(select, { target: { value: 'service-a' } });
    fireEvent.click(screen.getByTestId('confirm-activate-agent'));
    await waitFor(() => expect(onSuccess).toHaveBeenCalledOnce());
    expect(post).toHaveBeenCalledExactlyOnceWith('/api/agent/definitions/TEMPLATE/activate', {
      providerCode: 'service-a',
    });
  });
  it('blocks duplicate submissions and cancellation while activation is pending', async () => {
    let finish!: (value: unknown) => void;
    post.mockImplementation(
      () =>
        new Promise((resolve) => {
          finish = resolve;
        }),
    );
    open();
    fireEvent.change(await screen.findByTestId('activation-provider-select'), {
      target: { value: 'service-a' },
    });
    const button = screen.getByTestId('confirm-activate-agent');
    fireEvent.click(button);
    fireEvent.click(button);
    expect(post).toHaveBeenCalledOnce();
    expect(button).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Cancel' })).toBeDisabled();
    finish({ code: '0' });
    await waitFor(() => expect(button).not.toBeDisabled());
  });
  it('preserves selection and allows retry after a rejected activation', async () => {
    post.mockResolvedValueOnce({ code: 'error', message: 'AI service unavailable' });
    const { onSuccess } = open();
    const select = await screen.findByTestId('activation-provider-select');
    fireEvent.change(select, { target: { value: 'service-a' } });
    fireEvent.click(screen.getByTestId('confirm-activate-agent'));
    expect(await screen.findByRole('alert')).toHaveTextContent('AI service unavailable');
    expect(select).toHaveValue('service-a');
    expect(onSuccess).not.toHaveBeenCalled();
    fireEvent.click(screen.getByTestId('confirm-activate-agent'));
    await waitFor(() => expect(onSuccess).toHaveBeenCalledOnce());
  });
  it('fails closed on discovery failure, and offers retry into the empty configuration state', async () => {
    fetchProviders
      .mockResolvedValueOnce({ ok: false })
      .mockResolvedValueOnce({ ok: true, json: async () => [] });
    open();
    expect(await screen.findByRole('alert')).toHaveTextContent('AI services could not be loaded');
    expect(screen.getByTestId('confirm-activate-agent')).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
    expect(await screen.findByRole('link', { name: 'AI Settings' })).toHaveAttribute(
      'href',
      '/p/c/ai_settings_hub',
    );
    expect(screen.getByTestId('confirm-activate-agent')).toBeDisabled();
    expect(post).not.toHaveBeenCalled();
  });
});
