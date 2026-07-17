import React from 'react';
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';

import { BPMNDesignerWorkspace } from '~/plugins/core-designer/components/bpmn-designer/BPMNDesigner';

function mockMatchMedia(matches: boolean) {
  Object.defineProperty(window, 'matchMedia', {
    writable: true,
    value: vi.fn().mockImplementation((query: string) => ({
      matches,
      media: query,
      onchange: null,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      addListener: vi.fn(),
      removeListener: vi.fn(),
      dispatchEvent: vi.fn(),
    })),
  });
}

function mockResizeObserver(width: number) {
  class MockResizeObserver {
    private readonly callback: ResizeObserverCallback;

    constructor(callback: ResizeObserverCallback) {
      this.callback = callback;
    }

    observe = () => {
      this.callback(
        [{ contentRect: { width } } as ResizeObserverEntry],
        this as unknown as ResizeObserver,
      );
    };

    unobserve = vi.fn();
    disconnect = vi.fn();
  }

  vi.stubGlobal('ResizeObserver', MockResizeObserver);
}

function renderWorkspace(props: Partial<React.ComponentProps<typeof BPMNDesignerWorkspace>> = {}) {
  return render(
    <BPMNDesignerWorkspace
      palette={<div data-testid="test-palette">palette</div>}
      canvas={<div data-testid="test-canvas">canvas</div>}
      propertyPanel={<div data-testid="test-property">property</div>}
      labels={{
        components: '组件',
        properties: '属性',
        close: '关闭',
      }}
      {...props}
    />,
  );
}

describe('BPMNDesignerWorkspace — responsive shell', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
  });

  afterEach(() => {
    vi.restoreAllMocks();
    document.body.innerHTML = '';
  });

  it('keeps both side panels in normal document flow on wide viewports', () => {
    mockMatchMedia(false);

    renderWorkspace();

    expect(window.matchMedia).toHaveBeenCalledWith('(max-width: 1599px)');
    expect(screen.getByTestId('bpmn-designer-workspace')).toHaveAttribute('data-layout', 'wide');
    expect(screen.getByTestId('bpmn-palette-shell')).toHaveAttribute('data-open', 'true');
    expect(screen.getByTestId('bpmn-inspector-shell')).toHaveAttribute('data-open', 'true');
    expect(screen.getByTestId('bpmn-palette-shell')).not.toHaveClass('absolute');
    expect(screen.getByTestId('bpmn-inspector-shell')).not.toHaveClass('absolute');
    expect(screen.getByTestId('bpmn-canvas-shell')).toHaveClass('flex', 'min-w-0', 'flex-1');
  });

  it('falls back to compact mode when the real workspace is narrower than a useful three-column width', async () => {
    mockMatchMedia(false);
    mockResizeObserver(1344);

    renderWorkspace();

    await waitFor(() => {
      expect(screen.getByTestId('bpmn-designer-workspace')).toHaveAttribute(
        'data-layout',
        'compact',
      );
    });
    expect(screen.getByTestId('bpmn-palette-shell')).toHaveAttribute('data-open', 'false');
    expect(screen.getByTestId('bpmn-inspector-shell')).toHaveAttribute('data-open', 'false');
    expect(screen.getByTestId('bpmn-canvas-shell')).toHaveClass('flex', 'min-w-0', 'flex-1');
  });

  it('starts compact viewports with drawers closed so the canvas is not squeezed', () => {
    mockMatchMedia(true);

    renderWorkspace();

    expect(screen.getByTestId('bpmn-designer-workspace')).toHaveAttribute('data-layout', 'compact');
    expect(screen.getByTestId('bpmn-palette-shell')).toHaveAttribute('data-open', 'false');
    expect(screen.getByTestId('bpmn-inspector-shell')).toHaveAttribute('data-open', 'false');
    expect(screen.getByTestId('bpmn-palette-shell')).toHaveClass('absolute');
    expect(screen.getByTestId('bpmn-inspector-shell')).toHaveClass('absolute');
    expect(screen.getByTestId('bpmn-canvas-shell')).toHaveClass('flex', 'min-w-0', 'flex-1');
  });

  it('opens one compact drawer at a time without changing the canvas flex contract', () => {
    mockMatchMedia(true);

    renderWorkspace();

    fireEvent.click(screen.getByTestId('bpmn-toggle-palette'));
    expect(screen.getByTestId('bpmn-palette-shell')).toHaveAttribute('data-open', 'true');
    expect(screen.getByTestId('bpmn-inspector-shell')).toHaveAttribute('data-open', 'false');
    expect(screen.getByTestId('bpmn-canvas-shell')).toHaveClass('flex', 'min-w-0', 'flex-1');

    fireEvent.click(screen.getByTestId('bpmn-toggle-inspector'));
    expect(screen.getByTestId('bpmn-palette-shell')).toHaveAttribute('data-open', 'false');
    expect(screen.getByTestId('bpmn-inspector-shell')).toHaveAttribute('data-open', 'true');
    expect(screen.getByTestId('bpmn-canvas-shell')).toHaveClass('flex', 'min-w-0', 'flex-1');
  });

  it('opens the inspector drawer when compact mode receives a new selected node or edge', () => {
    mockMatchMedia(true);

    const { rerender } = renderWorkspace({ inspectorFocusKey: null });

    rerender(
      <BPMNDesignerWorkspace
        palette={<div data-testid="test-palette">palette</div>}
        canvas={<div data-testid="test-canvas">canvas</div>}
        propertyPanel={<div data-testid="test-property">property</div>}
        labels={{
          components: '组件',
          properties: '属性',
          close: '关闭',
        }}
        inspectorFocusKey="user-task-1"
      />,
    );

    expect(screen.getByTestId('bpmn-inspector-shell')).toHaveAttribute('data-open', 'true');
    expect(screen.getByTestId('bpmn-palette-shell')).toHaveAttribute('data-open', 'false');
  });
});
