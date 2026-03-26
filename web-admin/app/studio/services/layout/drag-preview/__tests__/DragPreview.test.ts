import { describe, it, expect, beforeEach, vi } from 'vitest';
import { DragPreview, DragPreviewPresets } from '~/studio/services/layout/drag-preview/DragPreview';

const createContainer = (): HTMLElement => {
  const div = document.createElement('div');
  Object.assign(div.style, { position: 'static' });
  document.body.appendChild(div);
  return div;
};

describe('DragPreview (studio implementation)', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
  });

  it('initializes with default state', () => {
    const preview = new DragPreview(createContainer());
    expect(preview.getState().isVisible).toBe(false);
  });

  it('starts preview and updates position', () => {
    const container = createContainer();
    const source = document.createElement('div');
    container.appendChild(source);

    const preview = new DragPreview(container, { enableGhost: false });
    preview.startPreview({ id: 'item' }, source, { x: 10, y: 10 });
    preview.updatePosition({ x: 20, y: 30 });

    const state = preview.getState();
    expect(state.isVisible).toBe(true);
    expect(state.mousePosition).toEqual({ x: 20, y: 30 });

    preview.endPreview();
    expect(preview.getState().isVisible).toBe(false);
  });

  it('applies presets', () => {
    expect(DragPreviewPresets.default.enabled).toBe(true);
    expect(DragPreviewPresets.minimal.scale).toBeLessThan(DragPreviewPresets.default.scale);
  });
});
