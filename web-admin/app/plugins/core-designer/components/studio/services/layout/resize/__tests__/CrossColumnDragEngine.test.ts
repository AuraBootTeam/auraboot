import { describe, it, expect, beforeEach } from 'vitest';
import {
  CrossColumnDragEngine,
  CrossColumnDragEnginePresets,
  type CrossColumnDragConfig,
  type ResizeTarget,
} from '~/plugins/core-designer/components/studio/services/layout/resize/CrossColumnDragEngine';

const cloneConfig = (): CrossColumnDragConfig =>
  JSON.parse(JSON.stringify(CrossColumnDragEnginePresets.default));

const createContainer = (): HTMLElement => {
  const div = document.createElement('div');
  Object.assign(div.style, { position: 'relative', width: '400px', height: '400px' });
  document.body.appendChild(div);
  return div;
};

const createTarget = (id: string, container: HTMLElement): ResizeTarget => {
  const element = document.createElement('div');
  element.style.width = '100px';
  element.style.height = '80px';
  container.appendChild(element);

  return {
    id,
    element,
    gridArea: { columnStart: 1, columnEnd: 3, rowStart: 1, rowEnd: 2 },
    minWidth: 50,
    minHeight: 40,
    resizable: { column: true, row: true },
  };
};

describe('CrossColumnDragEngine (studio implementation)', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
  });

  it('adds and removes targets with handles', () => {
    const container = createContainer();
    const engine = new CrossColumnDragEngine(cloneConfig());
    engine.initialize(container);

    const target = createTarget('t1', container);
    engine.addTarget(target);

    expect(engine.getTargets()).toHaveLength(1);
    expect(container.querySelectorAll('.resize-handle').length).toBeGreaterThan(0);

    engine.removeTarget(target.id);
    expect(engine.getTargets()).toHaveLength(0);
    expect(container.querySelectorAll('.resize-handle').length).toBe(0);

    engine.destroy();
  });

  it('updates target layout through updateTarget', () => {
    const container = createContainer();
    const engine = new CrossColumnDragEngine(cloneConfig());
    engine.initialize(container);
    const target = createTarget('t2', container);
    engine.addTarget(target);

    engine.updateTarget('t2', {
      gridArea: { columnStart: 2, columnEnd: 4, rowStart: 1, rowEnd: 3 },
    });

    const updated = engine.getTargets()[0];
    expect(updated.gridArea.columnStart).toBe(2);
    expect(updated.gridArea.rowEnd).toBe(3);
    engine.destroy();
  });

  it('updates configuration', () => {
    const container = createContainer();
    const engine = new CrossColumnDragEngine(cloneConfig());
    engine.initialize(container);

    engine.updateConfig({ handleSize: 12 });
    expect(engine.getConfig().handleSize).toBe(12);
    engine.destroy();
  });
});
