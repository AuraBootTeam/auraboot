// web-admin/app/flow-designer-sdk/__tests__/useFlowStore.test.ts
import { describe, it, expect, beforeEach } from 'vitest';
import { useFlowStore } from '../store/useFlowStore';

describe('useFlowStore', () => {
  beforeEach(() => {
    // Reset store before each test
    useFlowStore.getState().reset();
  });

  describe('node operations', () => {
    it('should add a node', () => {
      const store = useFlowStore.getState();
      const nodeId = store.addNode({
        type: 'trigger-manual',
        position: { x: 100, y: 100 },
        data: { label: 'Test Node', config: {} },
      });

      const currentStore = useFlowStore.getState();
      expect(nodeId).toBeDefined();
      expect(currentStore.nodes).toHaveLength(1);
      expect(currentStore.nodes[0].type).toBe('trigger-manual');
      expect(currentStore.isDirty).toBe(true);
    });

    it('should update a node', () => {
      const store = useFlowStore.getState();
      const nodeId = store.addNode({
        type: 'test',
        position: { x: 0, y: 0 },
        data: { label: 'Original', config: {} },
      });

      store.updateNode(nodeId, { position: { x: 200, y: 200 } });

      const updatedStore = useFlowStore.getState();
      const node = updatedStore.nodes.find((n) => n.id === nodeId);
      expect(node?.position).toEqual({ x: 200, y: 200 });
    });

    it('should update node config', () => {
      const store = useFlowStore.getState();
      const nodeId = store.addNode({
        type: 'test',
        position: { x: 0, y: 0 },
        data: { label: 'Test', config: { value: 1 } },
      });

      store.updateNodeConfig(nodeId, { value: 2, newField: 'added' });

      const updatedStore = useFlowStore.getState();
      const node = updatedStore.nodes.find((n) => n.id === nodeId);
      expect(node?.data.config).toEqual({ value: 2, newField: 'added' });
    });

    it('should delete a node and its edges', () => {
      const store = useFlowStore.getState();
      const node1Id = store.addNode({
        type: 'test1',
        position: { x: 0, y: 0 },
        data: { label: 'Node 1', config: {} },
      });
      const node2Id = store.addNode({
        type: 'test2',
        position: { x: 100, y: 100 },
        data: { label: 'Node 2', config: {} },
      });

      store.addEdge({ source: node1Id, target: node2Id });

      let currentStore = useFlowStore.getState();
      expect(currentStore.edges).toHaveLength(1);

      store.deleteNode(node1Id);

      currentStore = useFlowStore.getState();
      expect(currentStore.nodes).toHaveLength(1);
      expect(currentStore.edges).toHaveLength(0); // Edge should be deleted too
    });

    it('should select and deselect nodes', () => {
      const store = useFlowStore.getState();
      const nodeId = store.addNode({
        type: 'test',
        position: { x: 0, y: 0 },
        data: { label: 'Test', config: {} },
      });

      store.selectNode(nodeId);
      expect(useFlowStore.getState().selectedNodeId).toBe(nodeId);

      store.selectNode(null);
      expect(useFlowStore.getState().selectedNodeId).toBeNull();
    });

    it('should clear selectedNodeId when selected node is deleted', () => {
      const store = useFlowStore.getState();
      const nodeId = store.addNode({
        type: 'test',
        position: { x: 0, y: 0 },
        data: { label: 'Test', config: {} },
      });

      store.selectNode(nodeId);
      expect(useFlowStore.getState().selectedNodeId).toBe(nodeId);

      store.deleteNode(nodeId);
      expect(useFlowStore.getState().selectedNodeId).toBeNull();
    });
  });

  describe('edge operations', () => {
    it('should add an edge', () => {
      const store = useFlowStore.getState();
      const edgeId = store.addEdge({
        source: 'node1',
        target: 'node2',
      });

      expect(edgeId).toBeDefined();
      const currentStore = useFlowStore.getState();
      expect(currentStore.edges).toHaveLength(1);
      expect(currentStore.edges[0].source).toBe('node1');
      expect(currentStore.edges[0].target).toBe('node2');
    });

    it('should update an edge', () => {
      const store = useFlowStore.getState();
      const edgeId = store.addEdge({
        source: 'node1',
        target: 'node2',
      });

      store.updateEdge(edgeId, { data: { label: 'Updated Label' } });

      const currentStore = useFlowStore.getState();
      const edge = currentStore.edges.find((e) => e.id === edgeId);
      expect(edge?.data?.label).toBe('Updated Label');
    });

    it('should delete an edge', () => {
      const store = useFlowStore.getState();
      const edgeId = store.addEdge({
        source: 'node1',
        target: 'node2',
      });

      store.deleteEdge(edgeId);
      expect(useFlowStore.getState().edges).toHaveLength(0);
    });

    it('should add edge with handles', () => {
      const store = useFlowStore.getState();
      const edgeId = store.addEdge({
        source: 'node1',
        target: 'node2',
        sourceHandle: 'output-1',
        targetHandle: 'input-1',
      });

      const currentStore = useFlowStore.getState();
      const edge = currentStore.edges.find((e) => e.id === edgeId);
      expect(edge?.sourceHandle).toBe('output-1');
      expect(edge?.targetHandle).toBe('input-1');
    });
  });

  describe('validation', () => {
    it('should set validation result', () => {
      const store = useFlowStore.getState();
      const validationResult = {
        valid: false,
        errors: [{ nodeId: 'node1', message: 'Missing required field', type: 'error' as const }],
      };

      store.setValidationResult(validationResult);
      expect(useFlowStore.getState().validationResult).toEqual(validationResult);
    });

    it('should clear validation result', () => {
      const store = useFlowStore.getState();
      store.setValidationResult({
        valid: true,
        errors: [],
      });

      store.setValidationResult(null);
      expect(useFlowStore.getState().validationResult).toBeNull();
    });
  });

  describe('import/export', () => {
    it('should export data', () => {
      const store = useFlowStore.getState();
      store.addNode({
        type: 'test',
        position: { x: 0, y: 0 },
        data: { label: 'Test', config: {} },
      });

      const data = useFlowStore.getState().exportData();
      expect(data.nodes).toHaveLength(1);
      expect(data.edges).toHaveLength(0);
    });

    it('should import data', () => {
      const store = useFlowStore.getState();
      const importData = {
        nodes: [
          {
            id: 'n1',
            type: 'test',
            position: { x: 0, y: 0 },
            data: { label: 'Imported', config: {} },
          },
        ],
        edges: [],
      };

      store.importData(importData);
      const currentStore = useFlowStore.getState();
      expect(currentStore.nodes).toHaveLength(1);
      expect(currentStore.nodes[0].id).toBe('n1');
      expect(currentStore.isDirty).toBe(false); // Import should reset dirty flag
    });

    it('should import data with edges', () => {
      const store = useFlowStore.getState();
      const importData = {
        nodes: [
          {
            id: 'n1',
            type: 'test1',
            position: { x: 0, y: 0 },
            data: { label: 'Node 1', config: {} },
          },
          {
            id: 'n2',
            type: 'test2',
            position: { x: 100, y: 100 },
            data: { label: 'Node 2', config: {} },
          },
        ],
        edges: [{ id: 'e1', source: 'n1', target: 'n2' }],
      };

      store.importData(importData);
      const currentStore = useFlowStore.getState();
      expect(currentStore.nodes).toHaveLength(2);
      expect(currentStore.edges).toHaveLength(1);
    });

    it('should reset state', () => {
      const store = useFlowStore.getState();
      store.addNode({
        type: 'test',
        position: { x: 0, y: 0 },
        data: { label: 'Test', config: {} },
      });
      store.setValidationResult({ valid: true, errors: [] });

      store.reset();
      const currentStore = useFlowStore.getState();
      expect(currentStore.nodes).toHaveLength(0);
      expect(currentStore.edges).toHaveLength(0);
      expect(currentStore.selectedNodeId).toBeNull();
      expect(currentStore.isDirty).toBe(false);
      expect(currentStore.validationResult).toBeNull();
    });
  });

  describe('undo/redo', () => {
    it('should not undo when history is empty', () => {
      const store = useFlowStore.getState();
      expect(store.canUndo()).toBe(false);
      store.undo(); // should not throw
      expect(useFlowStore.getState().nodes).toHaveLength(0);
    });

    it('should not redo when at latest state', () => {
      const store = useFlowStore.getState();
      expect(store.canRedo()).toBe(false);
      store.redo(); // should not throw
      expect(useFlowStore.getState().nodes).toHaveLength(0);
    });

    it('should initialize history on importData', () => {
      const store = useFlowStore.getState();
      store.importData({
        nodes: [
          { id: 'n1', type: 'test', position: { x: 0, y: 0 }, data: { label: 'N1', config: {} } },
        ],
        edges: [],
      });

      const state = useFlowStore.getState();
      expect(state.history).toHaveLength(1);
      expect(state.historyIndex).toBe(0);
      expect(state.canUndo()).toBe(false);
      expect(state.canRedo()).toBe(false);
    });

    it('should push snapshot on addNode', () => {
      const store = useFlowStore.getState();
      store.importData({ nodes: [], edges: [] });
      store.addNode({
        type: 'test',
        position: { x: 0, y: 0 },
        data: { label: 'Test', config: {} },
      });

      const state = useFlowStore.getState();
      expect(state.history).toHaveLength(2); // initial + addNode
      expect(state.historyIndex).toBe(1);
      expect(state.canUndo()).toBe(true);
      expect(state.canRedo()).toBe(false);
    });

    it('should undo addNode', () => {
      const store = useFlowStore.getState();
      store.importData({ nodes: [], edges: [] });

      store.addNode({
        type: 'test',
        position: { x: 0, y: 0 },
        data: { label: 'Test', config: {} },
      });
      expect(useFlowStore.getState().nodes).toHaveLength(1);

      store.undo();
      expect(useFlowStore.getState().nodes).toHaveLength(0);
      expect(useFlowStore.getState().canUndo()).toBe(false);
      expect(useFlowStore.getState().canRedo()).toBe(true);
    });

    it('should redo after undo', () => {
      const store = useFlowStore.getState();
      store.importData({ nodes: [], edges: [] });

      store.addNode({
        type: 'test',
        position: { x: 0, y: 0 },
        data: { label: 'Test', config: {} },
      });

      store.undo();
      expect(useFlowStore.getState().nodes).toHaveLength(0);

      store.redo();
      expect(useFlowStore.getState().nodes).toHaveLength(1);
      expect(useFlowStore.getState().canRedo()).toBe(false);
    });

    it('should truncate redo stack on new operation after undo', () => {
      const store = useFlowStore.getState();
      store.importData({ nodes: [], edges: [] });

      store.addNode({
        type: 'first',
        position: { x: 0, y: 0 },
        data: { label: 'First', config: {} },
      });
      store.addNode({
        type: 'second',
        position: { x: 100, y: 100 },
        data: { label: 'Second', config: {} },
      });

      // Undo second node
      store.undo();
      expect(useFlowStore.getState().nodes).toHaveLength(1);

      // New operation should truncate redo
      store.addNode({
        type: 'third',
        position: { x: 200, y: 200 },
        data: { label: 'Third', config: {} },
      });

      expect(useFlowStore.getState().canRedo()).toBe(false);
      expect(useFlowStore.getState().nodes).toHaveLength(2);
      expect(useFlowStore.getState().nodes[1].type).toBe('third');
    });

    it('should respect MAX_HISTORY limit', () => {
      const store = useFlowStore.getState();
      store.importData({ nodes: [], edges: [] });

      // Add 55 nodes (exceeding MAX_HISTORY of 50)
      for (let i = 0; i < 55; i++) {
        store.addNode({
          type: `type_${i}`,
          position: { x: i * 10, y: 0 },
          data: { label: `Node ${i}`, config: {} },
        });
      }

      const state = useFlowStore.getState();
      // 1 initial + 55 addNode = 56, but capped at 50
      expect(state.history.length).toBeLessThanOrEqual(50);
    });

    it('should undo deleteNode', () => {
      const store = useFlowStore.getState();
      store.importData({ nodes: [], edges: [] });

      const nodeId = store.addNode({
        type: 'test',
        position: { x: 0, y: 0 },
        data: { label: 'Test', config: {} },
      });

      store.deleteNode(nodeId);
      expect(useFlowStore.getState().nodes).toHaveLength(0);

      store.undo();
      expect(useFlowStore.getState().nodes).toHaveLength(1);
    });

    it('should reset history on reset()', () => {
      const store = useFlowStore.getState();
      store.importData({ nodes: [], edges: [] });
      store.addNode({
        type: 'test',
        position: { x: 0, y: 0 },
        data: { label: 'Test', config: {} },
      });

      store.reset();
      const state = useFlowStore.getState();
      expect(state.history).toHaveLength(0);
      expect(state.historyIndex).toBe(-1);
    });
  });

  describe('dirty flag', () => {
    it('should set dirty flag manually', () => {
      const store = useFlowStore.getState();
      expect(store.isDirty).toBe(false);

      store.setDirty(true);
      expect(useFlowStore.getState().isDirty).toBe(true);

      store.setDirty(false);
      expect(useFlowStore.getState().isDirty).toBe(false);
    });

    it('should be dirty after adding node', () => {
      const store = useFlowStore.getState();
      expect(store.isDirty).toBe(false);

      store.addNode({
        type: 'test',
        position: { x: 0, y: 0 },
        data: { label: 'Test', config: {} },
      });

      expect(useFlowStore.getState().isDirty).toBe(true);
    });

    it('should be dirty after adding edge', () => {
      const store = useFlowStore.getState();
      store.addEdge({ source: 'n1', target: 'n2' });
      expect(useFlowStore.getState().isDirty).toBe(true);
    });
  });
});
