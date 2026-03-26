import { describe, it, expect, beforeEach, vi } from 'vitest';
import {
  AlignmentSystem,
  AlignmentSystemPresets,
  type AlignmentConfig,
} from '~/studio/services/layout/alignment/AlignmentSystem';

const createElementWithRect = (rect: {
  left: number;
  top: number;
  width: number;
  height: number;
}): HTMLElement => {
  const element = document.createElement('div');
  Object.assign(element.style, { position: 'absolute' });
  (element as any).getBoundingClientRect = () =>
    new DOMRect(rect.left, rect.top, rect.width, rect.height);
  return element;
};

describe('AlignmentSystem (studio implementation)', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
  });

  it('initializes targets including container and components', () => {
    const container = createElementWithRect({
      left: 0,
      top: 0,
      width: 400,
      height: 400,
    });
    const child = createElementWithRect({
      left: 100,
      top: 100,
      width: 50,
      height: 50,
    });
    child.setAttribute('data-component', 'true');
    container.appendChild(child);

    const events = {
      onAlignmentStart: vi.fn(),
    };

    const system = new AlignmentSystem(container, undefined, events);
    system.startAlignment();

    expect(events.onAlignmentStart).toHaveBeenCalled();
    const targets = system.getTargets();
    expect(targets.length).toBeGreaterThanOrEqual(2);
    expect(targets.some((t) => t.element === container)).toBe(true);
    expect(targets.some((t) => t.element === child)).toBe(true);
  });

  it('returns alignment result when near a target', () => {
    const container = createElementWithRect({
      left: 0,
      top: 0,
      width: 400,
      height: 400,
    });
    const target = createElementWithRect({
      left: 100,
      top: 50,
      width: 80,
      height: 40,
    });
    target.setAttribute('data-component', 'true');
    container.appendChild(target);

    const system = new AlignmentSystem(container, {
      threshold: 20,
    } as Partial<AlignmentConfig>);

    const result = system.calculateAlignment(
      document.createElement('div'),
      { x: 90, y: 90 },
      { width: 80, height: 40 },
    );

    expect(result).not.toBeNull();
    expect(result?.alignType).toBe('left');
    expect(result?.position.x).toBe(100);
    expect(system.getGuides().length).toBe(1);
  });

  it('updates configuration via updateConfig', () => {
    const container = createElementWithRect({
      left: 0,
      top: 0,
      width: 100,
      height: 100,
    });
    const system = new AlignmentSystem(container);
    system.updateConfig({ threshold: 2 });
    expect(system.getConfig().threshold).toBe(2);
  });

  it('exposes presets consistent with defaults', () => {
    expect(AlignmentSystemPresets.default.enabled).toBe(true);
    expect(AlignmentSystemPresets.precise.alignTypes).toContain('baseline');
  });
});
