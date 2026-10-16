import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
const { confirm, success, error } = vi.hoisted(() => ({
  confirm: vi.fn(),
  success: vi.fn(),
  error: vi.fn(),
}));
vi.mock('~/utils/i18n', () => ({
  useSmartText: () => (text: { zh: string }) => text.zh,
}));
vi.mock('~/contexts/ConfirmDialogContext', () => ({
  useConfirmDialog: () => ({ confirm }),
}));
vi.mock('~/contexts/ToastContext', () => ({
  useToastContext: () => ({ showSuccessToast: success, showErrorToast: error }),
}));
vi.mock('../../services/dashboardService', () => ({ dashboardService: {} }));
import { DashboardDocumentActions } from '../DashboardDocumentActions';
import { useDashboardStore } from '../../store/useDashboardStore';
const source = {
  format: 'auraboot.dashboard',
  version: 1,
  dashboard: {
    title: 'Imported',
    layoutConfig: { columns: 12, rowHeight: 80, gap: 16 },
    widgets: [],
  },
};
function select(text: string, size = text.length) {
  const file = new File([''], 'design.json', { type: 'application/json' });
  Object.defineProperties(file, {
    text: { value: async () => text },
    size: { value: size },
  });
  fireEvent.change(screen.getByTestId('dashboard-design-import-file'), {
    target: { files: [file] },
  });
}
describe('dashboard design file actions', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useDashboardStore.getState().reset();
    useDashboardStore.getState().createDashboard('Keep');
  });
  it('validates before confirmation and leaves the canvas unchanged on invalid input', async () => {
    render(<DashboardDocumentActions />);
    select('{');
    await waitFor(() => expect(error).toHaveBeenCalledOnce());
    expect(confirm).not.toHaveBeenCalled();
    expect(useDashboardStore.getState().dashboard?.title).toBe('Keep');
  });
  it('rejects oversized files before reading or changing a design', async () => {
    render(<DashboardDocumentActions />);
    select('{}', 3 * 1024 * 1024);
    await waitFor(() =>
      expect(error).toHaveBeenCalledWith('设计文件不能超过 2 MB。'),
    );
    expect(confirm).not.toHaveBeenCalled();
  });
  it('requires confirmation before discarding current unsaved design', async () => {
    confirm.mockResolvedValue(false);
    render(<DashboardDocumentActions />);
    select(JSON.stringify(source));
    await waitFor(() => expect(confirm).toHaveBeenCalledOnce());
    expect(useDashboardStore.getState().dashboard?.title).toBe('Keep');
    expect(success).not.toHaveBeenCalled();
  });
  it('imports a confirmed document as an independent personal draft', async () => {
    confirm.mockResolvedValue(true);
    render(<DashboardDocumentActions />);
    select(JSON.stringify(source));
    await waitFor(() => expect(success).toHaveBeenCalledOnce());
    const draft = useDashboardStore.getState().dashboard;
    expect(draft?.title).toBe('Imported');
    expect(draft?.scope).toBe('personal');
    expect(draft?.status).toBe('draft');
    expect(draft?.pid).toBeUndefined();
  });
});
