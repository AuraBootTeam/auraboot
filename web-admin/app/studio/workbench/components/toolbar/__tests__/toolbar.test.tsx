import React from 'react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { CommandToolbar } from '~/studio/workbench/components/toolbar/index';

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

vi.mock('~/studio/services/managers', () => ({
  getCommandManager: () => mockManager,
  CommandEventType: {
    COMMAND_EXECUTED: 'exec',
    COMMAND_UNDONE: 'undone',
    COMMAND_REDONE: 'redone',
    HISTORY_CHANGED: 'history',
  },
}));

describe('CommandToolbar (studio implementation)', () => {
  beforeEach(() => {
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
    render(<CommandToolbar />);
    expect(screen.getByText('撤销')).toBeInTheDocument();
    expect(screen.getByText('重做')).toBeInTheDocument();

    fireEvent.click(screen.getByText('撤销'));
    expect(mockManager.undo).toHaveBeenCalled();

    fireEvent.click(screen.getByText('重做'));
    expect(mockManager.redo).toHaveBeenCalled();
  });
});
