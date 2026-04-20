export interface DesignerStoreLike {
  addNode: (node: { id: string; type: string; position: { x: number; y: number }; data: Record<string, unknown> }) => string;
  addEdge: (edge: { id?: string; source: string; target: string; data?: Record<string, unknown> }) => string;
  setNodeData: (id: string, patch: Record<string, unknown>) => void;
  getSnapshot: () => { nodes: unknown[]; edges: unknown[] };
}

export function installDesignerTestHooks(store: DesignerStoreLike, devMode: boolean) {
  if (!devMode) return;
  (window as any).__bpmDesigner = {
    addNode: store.addNode.bind(store),
    connect: (from: string, to: string, condition?: string) =>
      store.addEdge({
        source: from,
        target: to,
        data: condition
          ? { condition: { type: 'expression', content: condition } }
          : undefined,
      }),
    configureNode: (id: string, patch: Record<string, unknown>) => store.setNodeData(id, patch),
    getDesignerJson: () => store.getSnapshot(),
  };
}
