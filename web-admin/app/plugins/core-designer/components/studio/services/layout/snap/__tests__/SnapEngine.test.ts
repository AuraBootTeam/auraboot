import { describe, it, expect, beforeEach, vi } from 'vitest';
import { SnapEngine } from '~/plugins/core-designer/components/studio/services/layout/snap/SnapEngine';

const createContainer = (rect: { left: number; top: number; width: number; height: number }) => {
  const element = document.createElement('div');
  (element as any).getBoundingClientRect = () =>
    new DOMRect(rect.left, rect.top, rect.width, rect.height);
  document.body.appendChild(element);
  return element;
};

describe('SnapEngine (studio implementation)', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
  });

  it('initializes snap points and triggers start event', () => {
    const container = createContainer({ left: 0, top: 0, width: 200, height: 200 });
    const events = { onSnapStart: vi.fn() };
    const engine = new SnapEngine(container, {}, events);

    engine.startSnap();

    expect(events.onSnapStart).toHaveBeenCalled();
    expect(engine.getSnapPoints().length).toBeGreaterThan(0);
  });

  it('snaps position to nearest grid point', () => {
    const container = createContainer({ left: 0, top: 0, width: 200, height: 200 });
    const events = { onSnapUpdate: vi.fn() };
    const engine = new SnapEngine(
      container,
      {
        grid: { enabled: true, size: 50, offset: { x: 0, y: 0 } },
        guides: {
          enabled: false,
          threshold: 5,
          showLines: false,
          lineStyle: { color: '#000', width: 1 },
        },
        edges: { enabled: false, threshold: 10, types: [] },
        components: { enabled: false, threshold: 8, alignTypes: [] },
      },
      events,
    );

    const result = engine.calculateSnap({ x: 5, y: 6 }, { width: 10, height: 10 });

    expect(result.snapped).toBe(true);
    expect(result.position).toEqual({ x: 0, y: 0 });
    expect(events.onSnapUpdate).toHaveBeenCalled();
  });

  it('updates configuration', () => {
    const container = createContainer({ left: 0, top: 0, width: 100, height: 100 });
    const engine = new SnapEngine(container);
    engine.updateConfig({ grid: { enabled: true, size: 20, offset: { x: 0, y: 0 } } });
    expect(engine.getConfig().grid.size).toBe(20);
  });
});
