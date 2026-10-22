import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { CanvasHost } from '../canvas/CanvasHost';

vi.mock('~/contexts/I18nContext', () => ({ useI18n: () => ({ locale: 'zh-CN' }) }));
vi.mock('@dnd-kit/core', () => ({
  useDroppable: () => ({ setNodeRef: vi.fn(), isOver: false }),
  useDraggable: () => ({ setNodeRef: vi.fn(), attributes: {}, listeners: {} }),
}));
vi.mock('../runtime/platformFieldPreview', async () => {
  const React = await import('react');
  return { DesignerModelFieldsContext: React.createContext([]), EditCanvasFieldPreview: () => null };
});

function pointer(target: EventTarget, type: string, x: number, y: number) {
  const event = new MouseEvent(type, { button: 0, clientX: x, clientY: y, bubbles: true, cancelable: true });
  fireEvent(target, event);
  return event;
}

function canvas() {
  const select = vi.fn();
  render(<CanvasHost
    document={{ id: 'marquee', kind: 'detail', schemaVersion: 3, blocks: [] }}
    mode="edit" selectedBlockId={null} activeDrag={null} activeDropIntent={null}
    rootAccepts={false} onSelect={vi.fn()} onMoveBefore={vi.fn()} onPatchBlock={vi.fn()}
    canDeleteBlock={() => false} onDeleteBlock={vi.fn()} onMarqueeSelect={select}
  />);
  const host = screen.getByTestId('unified-canvas-host');
  host.getBoundingClientRect = () => DOMRect.fromRect({ x: 100, y: 100, width: 300, height: 300 });
  return { host, select };
}

afterEach(() => {
  pointer(window, 'pointercancel', 0, 0);
  cleanup();
});

describe('CanvasHost marquee document gesture', () => {
  it('owns empty-canvas pointer down so native text selection cannot scroll the canvas', () => {
    const { host } = canvas();
    expect(pointer(host, 'pointerdown', 110, 140).defaultPrevented).toBe(true);
    pointer(window, 'pointerup', 110, 140);
  });

  it('anchors the overlay and hit test to content coordinates across a canvas scroll', () => {
    const { host, select } = canvas();
    for (const [id, contentTop] of [['a', 40], ['b', 180]] as const) {
      const block = document.createElement('div');
      block.dataset.testid = `canvas-block-${id}`;
      block.getBoundingClientRect = () => DOMRect.fromRect({
        x: 130, y: 100 + contentTop - host.scrollTop, width: 80, height: 40,
      });
      host.appendChild(block);
    }
    pointer(host, 'pointerdown', 110, 140);
    host.scrollTop = 100;
    pointer(window, 'pointermove', 220, 240);
    expect(screen.getByTestId('marquee-rect')).toHaveStyle({ top: '40px' });
    pointer(window, 'pointerup', 220, 240);
    expect(select).toHaveBeenCalledExactlyOnceWith(['a', 'b']);
  });

  it('preserves native interaction when pointer down starts in a canvas input', () => {
    const { host, select } = canvas();
    const input = document.createElement('input');
    host.appendChild(input);
    expect(pointer(input, 'pointerdown', 140, 140).defaultPrevented).toBe(false);
    pointer(window, 'pointermove', 220, 240);
    pointer(window, 'pointerup', 220, 240);
    expect(select).not.toHaveBeenCalled();
  });
});
