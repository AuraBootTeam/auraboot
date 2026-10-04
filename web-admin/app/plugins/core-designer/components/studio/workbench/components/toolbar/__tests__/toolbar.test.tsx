import React from 'react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import {
  CommandToolbar,
  DesignerToolbar,
} from '~/plugins/core-designer/components/studio/workbench/components/toolbar/index';

import { DESIGNER_I18N } from '~/shared/designer/designerI18n';

let mockLocale: 'zh-CN' | 'en-US' = 'zh-CN';

const mockManager = {
  canUndo: vi.fn(),
  canRedo: vi.fn(),
  getHistory: vi.fn(),
  on: vi.fn(),
  off: vi.fn(),
  undo: vi.fn().mockResolvedValue(undefined),
  redo: vi.fn().mockResolvedValue(undefined),
  clear: vi.fn(),
  undoCommand: vi.fn(),
  redoCommand: vi.fn(),
};

vi.mock('~/plugins/core-designer/components/studio/services/managers', () => ({
  getCommandManager: () => mockManager,
  CommandEventType: {
    COMMAND_EXECUTED: 'exec',
    COMMAND_UNDONE: 'undone',
    COMMAND_REDONE: 'redone',
    HISTORY_CHANGED: 'history',
  },
}));

vi.mock('~/contexts/AuthContext', () => ({
  usePermissions: () => ({
    hasPermission: () => true,
  }),
}));

vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({
    locale: mockLocale,
  }),
}));

vi.mock('~/plugins/core-designer/components/studio/components/SaveAsTemplateDialog', () => ({
  SaveAsTemplateDialog: () => null,
}));

vi.mock('~/plugins/core-designer/components/studio/components/AiPageGenerateDialog', () => ({
  AiPageGenerateDialog: () => null,
}));

describe('CommandToolbar (studio implementation)', () => {
  beforeEach(() => {
    mockLocale = 'zh-CN';
    mockManager.canUndo.mockReturnValue(true);
    mockManager.canRedo.mockReturnValue(true);
    mockManager.getHistory.mockReturnValue({ commands: [{}] });
    mockManager.on.mockClear();
    mockManager.off.mockClear();
    mockManager.undo.mockClear();
    mockManager.redo.mockClear();
    mockManager.clear.mockClear();
  });

  it('renders buttons and handles undo/redo', async () => {
    mockManager.canUndo.mockReturnValue(true);
    mockManager.canRedo.mockReturnValue(true);
    mockManager.getHistory.mockReturnValue({ commands: [{}] });
    render(<CommandToolbar />);
    const undoButton = screen.getByRole('button', { name: '撤销' });
    const redoButton = screen.getByRole('button', { name: '重做' });
    expect(undoButton).toBeInTheDocument();
    expect(redoButton).toBeInTheDocument();

    await waitFor(() => {
      expect(undoButton).not.toBeDisabled();
      expect(redoButton).not.toBeDisabled();
    });

    fireEvent.click(undoButton);
    expect(mockManager.undo).toHaveBeenCalled();

    fireEvent.click(redoButton);
    expect(mockManager.redo).toHaveBeenCalled();
  });

  it.each(['zh-CN', 'en-US'] as const)(
    'shows persisted save metadata after loading in %s',
    (locale) => {
      mockLocale = locale;
      render(
        <DesignerToolbar
          pageMeta={{
            id: 'saved',
            title: 'Saved page',
            kind: 'list',
            status: 'draft',
            version: '0.1.0',
            createdAt: '2026-10-01T00:00:00Z',
            updatedAt: '2026-10-02T00:00:00Z',
          }}
        />,
      );
      expect(screen.getByTestId('toolbar-draft-state')).toHaveTextContent(
        DESIGNER_I18N.autoSave.synced[locale],
      );
      expect(screen.getByTestId('toolbar-save-status')).toHaveTextContent(
        DESIGNER_I18N.autoSave.saved[locale],
      );
      expect(screen.getByTestId('toolbar-save')).toBeDisabled();
    },
  );

  it.each(['zh-CN', 'en-US'] as const)(
    'shows current edits ahead of an earlier saved timestamp in %s',
    (locale) => {
      mockLocale = locale;
      render(<DesignerToolbar hasUnsavedChanges lastSavedAt="2026-10-02T00:00:00Z" />);
      expect(screen.getByTestId('toolbar-save-status')).toHaveTextContent(
        DESIGNER_I18N.autoSave.currentEdits[locale],
      );
    },
  );

  it('renders the redesigned page toolbar with grouped workbench controls', () => {
    render(
      <DesignerToolbar
        pageMeta={
          {
            id: 'page-1',
            title: '请假单',
            code: 'leave_form',
            type: 'detail',
            status: 'draft',
            updatedAt: new Date().toISOString(),
          } as any
        }
        hasUnsavedChanges
        canUndo
        canRedo
        zoomLevel={125}
        currentDevice="tablet"
        lastSavedAt={new Date().toISOString()}
        onUndo={vi.fn()}
        onRedo={vi.fn()}
        onPreview={vi.fn()}
        onSave={vi.fn()}
        onPublish={vi.fn()}
      />,
    );

    expect(screen.getByText('页面设计器')).toBeInTheDocument();
    expect(screen.getByText('当前设备：平板')).toBeInTheDocument();
    expect(screen.getByTestId('toolbar-draft-state')).toHaveTextContent('待保存');
    expect(screen.getByText('工作台')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '预览页面' })).toBeInTheDocument();
    expect(screen.getByTestId('toolbar-publish')).toBeInTheDocument();
  });
});
