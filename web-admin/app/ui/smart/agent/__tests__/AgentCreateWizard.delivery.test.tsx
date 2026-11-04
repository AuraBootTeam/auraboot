import { fireEvent, render, screen, waitFor, cleanup } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AgentCreateWizard } from '../AgentCreateWizard';

const { get, post, showErrorToast } = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  showErrorToast: vi.fn(),
}));
vi.mock('~/shared/services/http-client', () => ({ get, post }));
vi.mock('react-router', () => ({ useNavigate: () => vi.fn() }));
vi.mock('~/contexts/ToastContext', () => ({
  useToastContext: () => ({ showErrorToast, showSuccessToast: vi.fn() }),
}));
vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({ t: (_key: string, _args: unknown, fallback: string) => fallback || _key }),
}));

const tools = [
  'list:tpm_project',
  'get:tpm_project',
  'list:tpm_task',
  'get:tpm_task',
  'list:tpm_milestone',
  'cmd:tpm:create_task',
  'cmd:tpm:update_task',
];
async function submitDelivery() {
  await screen.findByTestId('wizard-template-project_delivery');
  fireEvent.click(screen.getByTestId('wizard-template-project_delivery'));
  fireEvent.click(screen.getByTestId('wizard-btn-next'));
  fireEvent.click(screen.getByTestId('wizard-btn-next'));
  await waitFor(() =>
    expect(screen.getByTestId('review-provider-select')).toHaveValue('configured-test-provider'),
  );
  fireEvent.click(screen.getByTestId('wizard-btn-create'));
}
beforeEach(() => {
  vi.clearAllMocks();
  vi.stubGlobal(
    'fetch',
    vi
      .fn()
      .mockResolvedValue({
        ok: true,
        json: async () => [
          { providerCode: 'configured-test-provider', displayName: 'Configured service' },
        ],
      }),
  );
  get.mockResolvedValue({ code: '0', data: { tools: tools.map((code) => ({ code })) } });
  post.mockResolvedValue({ code: '0', data: { pid: 'test-agent' } });
});
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe('Project delivery colleague setup', () => {
  it('keeps the selected service and sends model-scoped tools without granting permissions', async () => {
    render(<AgentCreateWizard />);
    await submitDelivery();
    await waitFor(() => expect(post).toHaveBeenCalledTimes(1));
    const payload = post.mock.calls[0][1];
    expect(JSON.parse(payload.guardrails)).toEqual({ provider: 'configured-test-provider' });
    expect(payload.model).toBeNull();
    expect(JSON.parse(payload.allowed_models)).toEqual([
      'tpm_project',
      'tpm_task',
      'tpm_milestone',
    ]);
    expect(JSON.parse(payload.tools)).toEqual(tools);
    expect(payload.permissions).toBeUndefined();
    expect(payload.roles).toBeUndefined();
  });
  it('refuses creation when governed project tools are absent instead of saving an unusable colleague', async () => {
    get.mockResolvedValue({ code: '0', data: { tools: [] } });
    render(<AgentCreateWizard />);
    await submitDelivery();
    await waitFor(() => expect(showErrorToast).toHaveBeenCalled());
    expect(post).not.toHaveBeenCalled();
  });
});
