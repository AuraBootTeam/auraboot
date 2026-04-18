import { describe, it, expect, beforeEach } from 'vitest';
import { installDesignerTestHooks } from '~/plugins/core-designer/components/bpmn-designer/testHooks';

describe('designer test hooks', () => {
  beforeEach(() => {
    (window as any).__bpmDesigner = undefined;
  });

  it('exposes addNode/connect/configureNode/getDesignerJson on window.__bpmDesigner in dev mode', () => {
    const store = {
      addNode: () => 'nodeId',
      addEdge: () => 'edgeId',
      setNodeData: () => {},
      getSnapshot: () => ({ nodes: [], edges: [] }),
    };
    installDesignerTestHooks(store, /* devMode */ true);
    const hooks = (window as any).__bpmDesigner;
    expect(hooks).toBeDefined();
    expect(typeof hooks.addNode).toBe('function');
    expect(typeof hooks.connect).toBe('function');
    expect(typeof hooks.configureNode).toBe('function');
    expect(typeof hooks.getDesignerJson).toBe('function');
  });

  it('does NOT install hooks when devMode=false', () => {
    installDesignerTestHooks({} as any, false);
    expect((window as any).__bpmDesigner).toBeUndefined();
  });
});
