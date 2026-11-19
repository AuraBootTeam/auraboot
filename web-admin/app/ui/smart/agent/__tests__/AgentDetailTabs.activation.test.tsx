import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest';
import { MemoryRouter } from 'react-router';
import { AgentDetailTabs, ActivateAgentDialog, EnrollEmployeeDialog } from '../AgentDetailTabs';
vi.mock('react-router', async () => vi.importActual('react-router'));
const { post, get } = vi.hoisted(() => ({ post: vi.fn(), get: vi.fn() }));
vi.mock('~/shared/services/http-client', () => ({
  get,
  post,
  put: vi.fn(),
  del: vi.fn(),
}));
vi.mock('~/contexts/I18nContext', () => {
  const t = (key: string, _params: unknown, fallback: string) => fallback || key;
  return { useI18n: () => ({ t }) };
});
vi.mock('~/contexts/AuthContext', () => ({ usePermission: () => false }));
vi.mock('~/shared/services/organizationService', () => ({ organizationService: { getDepartmentTree: vi.fn(async () => ({ code: '0', data: [{ pid: 'DEPARTMENT', name: 'Acceptance Department', children: [] }] })) } }));
vi.mock('~/contexts/ToastContext', () => { const toast = { showSuccessToast: vi.fn(), showErrorToast: vi.fn() }; return { useToastContext: () => toast }; });
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

describe('Enrollment required fields', () => {
  it('requires both department and position without presenting the position as optional', async () => {
    post.mockReset(); post.mockResolvedValue({ code: '0' });
    get.mockResolvedValue({ code: '0', data: { records: [{ pid: 'POSITION', org_pos_name: 'Acceptance Position', org_pos_status: 'active' }] } });
    const onSuccess = vi.fn();
    render(<EnrollEmployeeDialog agentPid="TEMPLATE" agentName="Acceptance Colleague" onClose={vi.fn()} onSuccess={onSuccess} />);
    const dept = await screen.findByTestId('enroll-dept-select');
    expect(screen.queryByText('(optional)')).not.toBeInTheDocument();
    expect(screen.getByTestId('enroll-confirm-btn')).toBeDisabled();
    fireEvent.change(dept, { target: { value: 'DEPARTMENT' } });
    await screen.findByRole('option', { name: 'Acceptance Position' });
    expect(screen.getByTestId('enroll-confirm-btn')).toBeDisabled();
    fireEvent.change(screen.getByTestId('enroll-position-select'), { target: { value: 'POSITION' } });
    fireEvent.click(screen.getByTestId('enroll-confirm-btn'));
    await waitFor(() => expect(onSuccess).toHaveBeenCalledOnce());
    expect(post).toHaveBeenCalledExactlyOnceWith('/api/agent/definitions/TEMPLATE/enroll-employee', { departmentPid: 'DEPARTMENT', positionPid: 'POSITION' });
  });
});

describe('Activation permission boundary', () => {
  it('keeps activation unavailable to a definition reader without runtime management permission', async () => {
    get.mockReset();
    get.mockResolvedValue({ code: '0', data: { pid: 'TEMPLATE', name: 'Disabled colleague', agent_code: 'disabled_colleague', status: 'disabled', agent_type: 'reactive', visibility: 'tenant' } });
    render(<MemoryRouter initialEntries={['/p/c/ai_colleague_detail?agentPid=TEMPLATE']}><AgentDetailTabs /></MemoryRouter>);
    expect(await screen.findByTestId('agent-disabled-help')).toHaveTextContent('Ask an administrator');
    expect(screen.queryByTestId('activate-agent-btn')).not.toBeInTheDocument();
    expect(screen.queryByTestId('enroll-as-employee-btn')).not.toBeInTheDocument();
    expect(get.mock.calls.some(([url]) => String(url).endsWith('/org-placement'))).toBe(false);
  });
});
