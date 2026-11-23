import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AgentColleaguesGrid } from '../AgentColleaguesGrid';
const { get } = vi.hoisted(() => ({ get: vi.fn() }));
vi.mock('~/shared/services/http-client', () => ({ get }));
vi.mock('~/contexts/I18nContext', () => { const t = (key: string, _params: unknown, fallback: string) => fallback || key; return { useI18n: () => ({ t }) }; });
vi.mock('~/contexts/ToastContext', () => { const toast = { showErrorToast: vi.fn() }; return { useToastContext: () => toast }; });
vi.mock('react-router', () => { const navigate = vi.fn(); return { useNavigate: () => navigate }; });
describe('AI colleague list request states', () => {
  beforeEach(() => get.mockReset());
  it('shows permission denial without an empty state or create action', async () => {
    get.mockResolvedValue({ code: '403', data: null });
    render(<AgentColleaguesGrid />);
    expect(await screen.findByRole('alert')).toHaveTextContent('You do not have permission');
    expect(screen.queryByTestId('create-agent-btn')).not.toBeInTheDocument();
    expect(screen.queryByText('No AI colleagues yet')).not.toBeInTheDocument();
  });
  it('offers explicit retry after a failure and renders an empty state only after successful empty data', async () => {
    get.mockRejectedValueOnce(new Error('network unavailable')).mockResolvedValueOnce({ code: '0', data: { records: [], total: 0 } });
    render(<AgentColleaguesGrid />);
    expect(await screen.findByRole('alert')).toHaveTextContent('Failed to load AI colleagues');
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
    await waitFor(() => expect(screen.queryByRole('alert')).not.toBeInTheDocument());
    expect(await screen.findByTestId('create-agent-btn')).toBeVisible();
  });
});
