import React from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { SavedView } from '~/framework/smart/types/savedView';
import { savedViewService } from '~/shared/services/savedViewService';
import { ViewManagePanel } from '../ViewManagePanel';

vi.mock('~/shared/services/savedViewService', () => ({
  savedViewService: {
    getMyTeams: vi.fn(async () => [{ pid: 'team-a', name: 'Team A' }]),
    getAuditEvents: vi.fn(async () => []),
  },
}));

function makeView(overrides: Partial<SavedView> = {}): SavedView {
  return {
    pid: 'view-1',
    name: 'Default View',
    modelCode: 'order',
    pageKey: 'order_list',
    scope: 'personal',
    viewType: 'table',
    viewConfig: {},
    ...overrides,
  };
}

function renderPanel(
  overrides: Partial<React.ComponentProps<typeof ViewManagePanel>> = {},
): React.ComponentProps<typeof ViewManagePanel> {
  const props: React.ComponentProps<typeof ViewManagePanel> = {
    open: true,
    onClose: vi.fn(),
    views: [],
    currentView: null,
    onCreateView: vi.fn(async (request) => ({
      pid: 'new-view',
      name: request.name,
      modelCode: request.modelCode,
      pageKey: request.pageKey,
      scope: request.scope ?? 'personal',
      viewType: request.viewType,
      viewConfig: request.viewConfig ?? {},
    }) as SavedView),
    onDeleteView: vi.fn(),
    onDuplicateView: vi.fn(),
    onSetDefaultView: vi.fn(),
    onSelectView: vi.fn(),
    modelCode: 'order',
    pageKey: 'order_list',
    fields: [],
    ...overrides,
  };

  render(<ViewManagePanel {...props} />);
  return props;
}

describe('ViewManagePanel capability gate', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(savedViewService.getMyTeams).mockResolvedValue([
      { pid: 'team-a', name: 'Team A' },
    ]);
    vi.mocked(savedViewService.getAuditEvents).mockResolvedValue([]);
  });

  it('creates a team view with a real selected team id', async () => {
    vi.mocked(savedViewService.getMyTeams).mockResolvedValueOnce([
      { pid: 'team-a', name: 'Team A' },
    ]);
    const props = renderPanel();

    fireEvent.click(screen.getByRole('button', { name: /New View/i }));
    await screen.findByText('Team A');
    fireEvent.change(screen.getByLabelText('Scope'), { target: { value: 'team' } });
    fireEvent.change(screen.getByLabelText('Team'), { target: { value: 'team-a' } });
    fireEvent.click(screen.getByRole('button', { name: /Table/i }));

    await waitFor(() => expect(props.onCreateView).toHaveBeenCalledOnce());
    expect(props.onCreateView).toHaveBeenCalledWith(
      expect.objectContaining({
        scope: 'team',
        teamId: 'team-a',
      }),
    );
  });

  it('blocks creating calendar views when the model has no date fields', () => {
    const props = renderPanel({
      fields: [{ code: 'name', name: 'Name', dataType: 'text' }],
    });

    fireEvent.click(screen.getByRole('button', { name: /New View/i }));
    fireEvent.click(screen.getByRole('button', { name: /Calendar/i }));

    expect(screen.getByTestId('view-capability-blocked-calendar')).toHaveTextContent(
      /requires at least one date/i,
    );
    expect(props.onCreateView).not.toHaveBeenCalled();
  });

  it('creates advanced views only after required field mapping is complete', async () => {
    const props = renderPanel({
      fields: [
        { code: 'start_date', name: 'Start Date', dataType: 'date' },
        { code: 'end_date', name: 'End Date', dataType: 'date' },
        { code: 'title', name: 'Title', dataType: 'text' },
      ],
    });

    fireEvent.click(screen.getByRole('button', { name: /New View/i }));
    fireEvent.click(screen.getByRole('button', { name: /Gantt/i }));

    expect(props.onCreateView).not.toHaveBeenCalled();
    expect(screen.getByText(/Configure Gantt View/i)).toBeInTheDocument();

    const selects = screen.getAllByRole('combobox');
    fireEvent.change(selects[0], { target: { value: 'start_date' } });
    fireEvent.change(selects[1], { target: { value: 'end_date' } });
    fireEvent.change(selects[2], { target: { value: 'title' } });
    fireEvent.click(screen.getByRole('button', { name: /^Done$/i }));

    await waitFor(() => expect(props.onCreateView).toHaveBeenCalledOnce());
    expect(props.onCreateView).toHaveBeenCalledWith(
      expect.objectContaining({
        modelCode: 'order',
        pageKey: 'order_list',
        viewType: 'gantt',
        viewConfig: expect.objectContaining({
          ganttStartDateField: 'start_date',
          ganttEndDateField: 'end_date',
          ganttTitleField: 'title',
        }),
      }),
    );
    expect(props.onSelectView).toHaveBeenCalledWith('new-view');
  });

  it('requires gallery image mapping before creating a gallery view', async () => {
    const props = renderPanel({
      fields: [
        { code: 'cover', name: 'Cover', dataType: 'image' },
        { code: 'title', name: 'Title', dataType: 'text' },
      ],
    });

    fireEvent.click(screen.getByRole('button', { name: /New View/i }));
    fireEvent.click(screen.getByRole('button', { name: /Gallery/i }));

    expect(screen.getByText(/Configure Gallery View/i)).toBeInTheDocument();
    const selects = screen.getAllByRole('combobox');
    const done = screen.getByRole('button', { name: /^Done$/i });

    expect(selects[0]).toHaveValue('cover');
    expect(done).toBeEnabled();

    fireEvent.change(selects[0], { target: { value: '' } });
    expect(done).toBeDisabled();
    fireEvent.click(done);
    expect(props.onCreateView).not.toHaveBeenCalled();

    fireEvent.change(selects[0], { target: { value: 'cover' } });
    fireEvent.click(done);

    await waitFor(() => expect(props.onCreateView).toHaveBeenCalledOnce());
    expect(props.onCreateView).toHaveBeenCalledWith(
      expect.objectContaining({
        viewType: 'gallery',
        viewConfig: expect.objectContaining({
          galleryImageField: 'cover',
        }),
      }),
    );
  });

  it('shows audit events for shared views from the management panel', async () => {
    const teamView = makeView({
      pid: 'team-view',
      name: 'Team Board',
      scope: 'team',
      teamId: 'team-a',
      teamName: 'Team A',
    });
    vi.mocked(savedViewService.getAuditEvents).mockResolvedValueOnce([
      {
        entityPid: 'team-view',
        operationType: 'UPDATE',
        actorName: 'Alice',
        changedFields: ['viewConfig'],
        metadata: { summary: 'Saved shared view configuration' },
        timestamp: '2026-06-22T06:00:00Z',
      },
    ]);

    renderPanel({ views: [teamView], currentView: teamView });

    fireEvent.click(screen.getByTestId('view-audit-team-view'));

    await waitFor(() =>
      expect(savedViewService.getAuditEvents).toHaveBeenCalledWith('team-view'),
    );
    expect(screen.getByTestId('saved-view-audit-panel')).toHaveTextContent('Audit: Team Board');
    expect(screen.getByTestId('saved-view-audit-event')).toHaveTextContent('UPDATE');
    expect(screen.getByTestId('saved-view-audit-event')).toHaveTextContent('Alice');
    expect(screen.getByTestId('saved-view-audit-event')).toHaveTextContent(
      'Saved shared view configuration',
    );
  });

  it('renders locked plugin presets as read-only while keeping copy and audit actions', () => {
    const lockedPreset = makeView({
      pid: 'preset-view',
      name: 'Plugin Preset',
      scope: 'team',
      viewConfig: {
        meta: {
          managedBy: 'plugin',
          locked: true,
          allowUserCopy: true,
        },
      },
    });
    const onEditView = vi.fn();

    renderPanel({
      views: [lockedPreset],
      currentView: lockedPreset,
      onEditView,
    });

    expect(screen.getByTestId('view-locked-preset-preset-view')).toHaveTextContent('Preset');
    screen.getAllByTitle('Plugin preset is locked').forEach((button: HTMLElement) => {
      expect(button).toBeDisabled();
    });
    expect(screen.getByTitle('Duplicate view')).toBeEnabled();
    expect(screen.getByTestId('view-audit-preset-view')).toBeEnabled();
    expect(screen.queryByTitle('Delete view')).not.toBeInTheDocument();
  });

  it('uses server actions to disable shared view management controls', () => {
    const teamViewerView = makeView({
      pid: 'team-viewer',
      name: 'Team Viewer',
      scope: 'team',
      actions: ['view', 'copy'],
    });

    renderPanel({
      views: [teamViewerView],
      currentView: teamViewerView,
      onEditView: vi.fn(),
    });

    expect(screen.getByTitle('Set as default')).toBeDisabled();
    expect(screen.getByTitle('Edit view')).toBeDisabled();
    expect(screen.getByTitle('Duplicate view')).toBeEnabled();
    expect(screen.getByTitle('Share view')).toBeDisabled();
    expect(screen.getByTitle('Delete view')).toBeDisabled();
  });
});
