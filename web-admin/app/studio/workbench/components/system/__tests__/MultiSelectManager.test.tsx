import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import {
  MultiSelectManager,
  BatchOperationToolbar,
} from '~/studio/workbench/components/system/MultiSelectManager';

const mocks = vi.hoisted(() => {
  const commandManager = {
    executeCommand: vi.fn(),
    startBatch: vi.fn(),
    endBatch: vi.fn(),
  };
  const eventDomain = {
    dispatchEvent: vi.fn(),
    registerDomain: vi.fn(),
    unregisterDomain: vi.fn(),
  };
  const selectComponent = vi.fn();

  return {
    commandManager,
    eventDomain,
    selectComponent,
  };
});

vi.mock('~/studio/services/managers', () => ({
  getCommandManager: () => mocks.commandManager,
  eventDomainManager: mocks.eventDomain,
}));

vi.mock('~/studio/hooks/store/useDesignerStore', () => ({
  useDesignerStore: () => ({
    selectComponent: mocks.selectComponent,
  }),
}));

describe('MultiSelectManager (studio)', () => {
  beforeEach(() => {
    mocks.commandManager.executeCommand.mockReset();
    mocks.commandManager.startBatch.mockReset();
    mocks.commandManager.endBatch.mockReset();
    mocks.eventDomain.dispatchEvent.mockReset();
    (globalThis.confirm as any) = vi.fn().mockReturnValue(true);
  });

  it('selects component on click', () => {
    const containerRef = React.createRef<HTMLDivElement>();
    const onSelectionChange = vi.fn();

    render(
      <div ref={containerRef}>
        <MultiSelectManager
          containerRef={containerRef as React.RefObject<HTMLElement>}
          selectedIds={[]}
          onSelectionChange={onSelectionChange}
        >
          <div data-component-id="cmp-1">component</div>
        </MultiSelectManager>
      </div>,
    );

    fireEvent.mouseDown(screen.getByText('component'));
    expect(onSelectionChange).toHaveBeenCalledWith(['cmp-1']);
  });

  it('runs batch operations from toolbar', async () => {
    const onClearSelection = vi.fn();

    render(<BatchOperationToolbar selectedIds={['c1']} onClearSelection={onClearSelection} />);

    fireEvent.click(screen.getByText('批量删除'));
    await waitFor(() => {
      expect(mocks.commandManager.executeCommand).toHaveBeenCalled();
      expect(onClearSelection).toHaveBeenCalled();
    });

    fireEvent.click(screen.getByText('批量样式'));
    fireEvent.click(screen.getByText('圆角卡片'));
    await waitFor(() => {
      expect(mocks.commandManager.startBatch).toHaveBeenCalled();
      expect(mocks.commandManager.endBatch).toHaveBeenCalled();
    });
  });
});
