import { act, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ReportDesigner } from '../ReportDesigner';
import { useReportStore } from '../store/useReportStore';

const mocks = vi.hoisted(() => ({ save: vi.fn(), error: vi.fn(), canManage: true }));
vi.mock('~/utils/i18n', () => ({ useSmartText: () => (value: { en: string }) => value.en }));
vi.mock('~/contexts/AuthContext', () => ({ usePermission: () => mocks.canManage }));
vi.mock('~/contexts/ToastContext', () => ({ useToastContext: () => ({ showErrorToast: mocks.error }) }));
vi.mock('~/shared/versioning', () => ({
  useVersioning: () => ({ versions: [] }), VersionHistoryPanel: () => null,
}));
vi.mock('~/shared/versioning/versionService', () => ({ reportVersionService: {} }));
vi.mock('../services/reportDesignerService', () => ({ reportDesignerService: { save: mocks.save } }));
vi.mock('../services/useReportQuery', () => ({ useReportQuery: () => ({ data: {}, canExport: false }) }));
vi.mock('../components/BlockPalette', () => ({ BlockPalette: () => null }));
vi.mock('../components/BlockPropertyPanel', () => ({ BlockPropertyPanel: () => null }));
vi.mock('../components/ReportCanvas', () => ({ ReportCanvas: () => null }));
vi.mock('../components/ReportTemplateActions', () => ({ ReportTemplateActions: () => null }));
vi.mock('../components/ReportToolbar', async () => {
  const { useReportDocument } = await import('../state/ReportDocumentProvider');
  return { ReportToolbar: ({ onSave, readOnly }: { onSave: () => void; readOnly?: boolean }) => {
    const document = useReportDocument();
    return <>
      <input aria-label="Title" value={document.report?.title ?? ''}
        onChange={event => document.updateTitle(event.target.value)} />
      <output data-testid="dirty">{String(document.isDirty)}</output>
      <button disabled={readOnly} onClick={onSave}>Save</button>
    </>;
  } };
});

describe('report save completion (hermetic component)', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.clearAllMocks();
    mocks.canManage = true;
    useReportStore.getState().reset();
    mocks.save.mockResolvedValue('saved-report-pid');
  });
  afterEach(() => { vi.useRealTimers(); vi.restoreAllMocks(); });

  it.each(['button', 'keyboard', 'auto-save'])('notifies the host after a successful %s save', async trigger => {
    const completed = vi.fn();
    const props = { initialTitle: 'Delivery brief', onSaveComplete: completed };
    render(<ReportDesigner {...props} />);
    if (trigger === 'auto-save') await act(async () => { await vi.advanceTimersByTimeAsync(30_000); });
    else await act(async () => {
      if (trigger === 'button') fireEvent.click(screen.getByRole('button', { name: 'Save' }));
      else fireEvent.keyDown(window, { key: 's', ctrlKey: true });
    });
    expect(mocks.save).toHaveBeenCalledExactlyOnceWith(expect.objectContaining({ title: 'Delivery brief' }), undefined);
    expect(completed).toHaveBeenCalledExactlyOnceWith('saved-report-pid');
    expect(useReportStore.getState().pageId).toBe('saved-report-pid');
    expect(screen.getByTestId('dirty')).toHaveTextContent('false');
    fireEvent.change(screen.getByLabelText('Title'), { target: { value: 'Updated brief' } });
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'Save' })); });
    expect(mocks.save).toHaveBeenLastCalledWith(expect.objectContaining({ title: 'Updated brief' }), 'saved-report-pid');
  });

  it('keeps an in-flight save unpublished until persistence succeeds', async () => {
    let resolve!: (pid: string) => void;
    mocks.save.mockReturnValue(new Promise<string>(done => { resolve = done; }));
    const completed = vi.fn();
    render(<ReportDesigner {...{ initialTitle: 'Pending brief', onSaveComplete: completed }} />);
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'Save' })); });
    expect(completed).not.toHaveBeenCalled();
    expect(useReportStore.getState().pageId).toBeNull();
    expect(useReportStore.getState().isSaving).toBe(true);
    expect(screen.getByTestId('dirty')).toHaveTextContent('true');
    await act(async () => { resolve('persisted-pid'); });
    expect(completed).toHaveBeenCalledExactlyOnceWith('persisted-pid');
  });

  it('preserves a failed draft and never notifies the host of a nonexistent report', async () => {
    mocks.save.mockRejectedValue(new Error('Save rejected'));
    vi.spyOn(console, 'error').mockImplementation(() => {});
    const completed = vi.fn();
    render(<ReportDesigner {...{ initialTitle: 'Keep my draft', onSaveComplete: completed }} />);
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'Save' })); });
    expect(completed).not.toHaveBeenCalled();
    expect(useReportStore.getState().pageId).toBeNull();
    expect(useReportStore.getState().isSaving).toBe(false);
    expect(screen.getByLabelText('Title')).toHaveValue('Keep my draft');
    expect(screen.getByTestId('dirty')).toHaveTextContent('true');
    expect(mocks.error).toHaveBeenCalledOnce();
  });

  it('does not persist or notify for a read-only host', async () => {
    mocks.canManage = false;
    const completed = vi.fn();
    render(<ReportDesigner {...{ initialTitle: 'Read only', onSaveComplete: completed }} />);
    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled();
    await act(async () => {
      fireEvent.keyDown(window, { key: 's', ctrlKey: true });
      await vi.advanceTimersByTimeAsync(31_000);
    });
    expect(mocks.save).not.toHaveBeenCalled();
    expect(completed).not.toHaveBeenCalled();
  });
});
