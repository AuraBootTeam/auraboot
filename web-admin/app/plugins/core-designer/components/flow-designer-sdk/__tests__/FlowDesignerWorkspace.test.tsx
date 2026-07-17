import React from 'react';
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';

import { FlowDesignerWorkspace } from '../core/FlowDesigner';

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

function renderWorkspace(props: Partial<React.ComponentProps<typeof FlowDesignerWorkspace>> = {}) {
  return render(
    <FlowDesignerWorkspace
      palette={<div data-testid="test-flow-palette">palette</div>}
      canvas={<div data-testid="test-flow-canvas">canvas</div>}
      propertyPanel={<div data-testid="test-flow-property">property</div>}
      labels={{
        components: '组件库',
        properties: '属性',
        close: '关闭面板',
      }}
      {...props}
    />,
  );
}

describe('FlowDesignerWorkspace — responsive shell', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
  });

  afterEach(() => {
    vi.restoreAllMocks();
    document.body.innerHTML = '';
  });

  it('keeps palette and inspector in document flow on wide viewports', () => {
    mockMatchMedia(false);

    renderWorkspace();

    expect(screen.getByTestId('flow-designer-workspace')).toHaveAttribute('data-layout', 'wide');
    expect(screen.getByTestId('flow-palette-shell')).toHaveAttribute('data-open', 'true');
    expect(screen.getByTestId('flow-inspector-shell')).toHaveAttribute('data-open', 'true');
    expect(screen.getByTestId('flow-palette-shell')).not.toHaveClass('absolute');
    expect(screen.getByTestId('flow-inspector-shell')).not.toHaveClass('absolute');
    expect(screen.getByTestId('flow-canvas-shell')).toHaveClass('flex', 'min-w-0', 'flex-1');
  });

  it('falls back to compact mode when the real workspace is narrower than a useful three-column width', async () => {
    mockMatchMedia(false);
    mockResizeObserver(1344);

    renderWorkspace();

    await waitFor(() => {
      expect(screen.getByTestId('flow-designer-workspace')).toHaveAttribute(
        'data-layout',
        'compact',
      );
    });
    expect(screen.getByTestId('flow-palette-shell')).toHaveAttribute('data-open', 'false');
    expect(screen.getByTestId('flow-inspector-shell')).toHaveAttribute('data-open', 'false');
    expect(screen.getByTestId('flow-canvas-shell')).toHaveClass('flex', 'min-w-0', 'flex-1');
  });

  it('starts compact viewports with drawers closed so the automation canvas keeps width', () => {
    mockMatchMedia(true);

    renderWorkspace();

    expect(screen.getByTestId('flow-designer-workspace')).toHaveAttribute('data-layout', 'compact');
    expect(screen.getByTestId('flow-palette-shell')).toHaveAttribute('data-open', 'false');
    expect(screen.getByTestId('flow-inspector-shell')).toHaveAttribute('data-open', 'false');
    expect(screen.getByTestId('flow-palette-shell')).toHaveClass('absolute');
    expect(screen.getByTestId('flow-inspector-shell')).toHaveClass('absolute');
    expect(screen.getByTestId('flow-canvas-shell')).toHaveClass('flex', 'min-w-0', 'flex-1');
  });

  it('opens one compact drawer at a time without changing the canvas flex contract', () => {
    mockMatchMedia(true);

    renderWorkspace();

    fireEvent.click(screen.getByTestId('flow-toggle-palette'));
    expect(screen.getByTestId('flow-palette-shell')).toHaveAttribute('data-open', 'true');
    expect(screen.getByTestId('flow-inspector-shell')).toHaveAttribute('data-open', 'false');
    expect(screen.getByTestId('flow-canvas-shell')).toHaveClass('flex', 'min-w-0', 'flex-1');

    fireEvent.click(screen.getByTestId('flow-toggle-inspector'));
    expect(screen.getByTestId('flow-palette-shell')).toHaveAttribute('data-open', 'false');
    expect(screen.getByTestId('flow-inspector-shell')).toHaveAttribute('data-open', 'true');
    expect(screen.getByTestId('flow-canvas-shell')).toHaveClass('flex', 'min-w-0', 'flex-1');
  });

  it('opens the inspector drawer when compact mode receives a new selected node', () => {
    mockMatchMedia(true);

    const { rerender } = renderWorkspace({ inspectorFocusKey: null });

    rerender(
      <FlowDesignerWorkspace
        palette={<div data-testid="test-flow-palette">palette</div>}
        canvas={<div data-testid="test-flow-canvas">canvas</div>}
        propertyPanel={<div data-testid="test-flow-property">property</div>}
        labels={{
          components: '组件库',
          properties: '属性',
          close: '关闭面板',
        }}
        inspectorFocusKey="node-1"
      />,
    );

    expect(screen.getByTestId('flow-inspector-shell')).toHaveAttribute('data-open', 'true');
    expect(screen.getByTestId('flow-palette-shell')).toHaveAttribute('data-open', 'false');
  });
});
