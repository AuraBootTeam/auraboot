// web-admin/app/flow-designer-sdk/__tests__/NodeRegistry.test.ts
import { describe, it, expect, beforeEach } from 'vitest';
import { NodeRegistry } from '../nodes/NodeRegistry';
import type { FlowNodeDefinition } from '../nodes/types';

describe('NodeRegistry', () => {
  let registry: NodeRegistry;

  const mockNode1: FlowNodeDefinition = {
    type: 'trigger-test',
    label: 'Test Trigger',
    icon: 'icon-trigger',
    category: 'trigger',
    description: 'A test trigger node',
    configSchema: [{ key: 'field1', label: 'Field 1', type: 'text', required: true }],
  };

  const mockNode2: FlowNodeDefinition = {
    type: 'action-test',
    label: 'Test Action',
    icon: 'icon-action',
    category: 'action',
    description: 'A test action node',
  };

  const mockNode3: FlowNodeDefinition = {
    type: 'action-other',
    label: 'Other Action',
    icon: 'icon-other',
    category: 'action',
  };

  const mockNode4: FlowNodeDefinition = {
    type: 'condition-test',
    label: 'Test Condition',
    icon: 'icon-condition',
    category: 'condition',
    description: 'A test condition node',
    defaultConfig: { operator: 'equals' },
  };

  beforeEach(() => {
    registry = new NodeRegistry();
  });

  describe('register', () => {
    it('should register a single node definition', () => {
      registry.register(mockNode1);
      expect(registry.has('trigger-test')).toBe(true);
    });

    it('should register multiple node definitions', () => {
      registry.registerAll([mockNode1, mockNode2, mockNode3]);
      expect(registry.has('trigger-test')).toBe(true);
      expect(registry.has('action-test')).toBe(true);
      expect(registry.has('action-other')).toBe(true);
    });

    it('should overwrite existing definition with same type', () => {
      registry.register(mockNode1);
      const updatedNode: FlowNodeDefinition = {
        ...mockNode1,
        label: 'Updated Trigger',
      };
      registry.register(updatedNode);

      const def = registry.get('trigger-test');
      expect(def?.label).toBe('Updated Trigger');
    });
  });

  describe('get', () => {
    it('should get a registered node definition', () => {
      registry.register(mockNode1);
      const def = registry.get('trigger-test');
      expect(def).toBeDefined();
      expect(def?.label).toBe('Test Trigger');
    });

    it('should return undefined for unregistered type', () => {
      const def = registry.get('non-existent');
      expect(def).toBeUndefined();
    });

    it('should return definition with all properties', () => {
      registry.register(mockNode4);
      const def = registry.get('condition-test');
      expect(def?.type).toBe('condition-test');
      expect(def?.label).toBe('Test Condition');
      expect(def?.icon).toBe('icon-condition');
      expect(def?.category).toBe('condition');
      expect(def?.description).toBe('A test condition node');
      expect(def?.defaultConfig).toEqual({ operator: 'equals' });
    });
  });

  describe('has', () => {
    it('should return true for registered type', () => {
      registry.register(mockNode1);
      expect(registry.has('trigger-test')).toBe(true);
    });

    it('should return false for unregistered type', () => {
      expect(registry.has('non-existent')).toBe(false);
    });
  });

  describe('getAll', () => {
    it('should return all registered definitions', () => {
      registry.registerAll([mockNode1, mockNode2, mockNode3]);
      const all = registry.getAll();
      expect(all).toHaveLength(3);
    });

    it('should return empty array when no definitions registered', () => {
      const all = registry.getAll();
      expect(all).toHaveLength(0);
    });

    it('should return definitions in order', () => {
      registry.registerAll([mockNode1, mockNode2, mockNode3]);
      const all = registry.getAll();
      const types = all.map((d) => d.type);
      expect(types).toContain('trigger-test');
      expect(types).toContain('action-test');
      expect(types).toContain('action-other');
    });
  });

  describe('getByCategory', () => {
    it('should group definitions by category', () => {
      registry.registerAll([mockNode1, mockNode2, mockNode3, mockNode4]);
      const grouped = registry.getByCategory();

      expect(grouped.trigger).toHaveLength(1);
      expect(grouped.action).toHaveLength(2);
      expect(grouped.condition).toHaveLength(1);
    });

    it('should return empty object when no definitions registered', () => {
      const grouped = registry.getByCategory();
      expect(Object.keys(grouped)).toHaveLength(0);
    });

    it('should have correct definitions in each category', () => {
      registry.registerAll([mockNode1, mockNode2, mockNode3]);
      const grouped = registry.getByCategory();

      expect(grouped.trigger[0].type).toBe('trigger-test');
      expect(grouped.action.map((d) => d.type)).toContain('action-test');
      expect(grouped.action.map((d) => d.type)).toContain('action-other');
    });
  });

  describe('getCategories', () => {
    it('should return unique categories', () => {
      registry.registerAll([mockNode1, mockNode2, mockNode3, mockNode4]);
      const categories = registry.getCategories();

      expect(categories).toContain('trigger');
      expect(categories).toContain('action');
      expect(categories).toContain('condition');
      expect(categories).toHaveLength(3);
    });

    it('should return empty array when no definitions registered', () => {
      const categories = registry.getCategories();
      expect(categories).toHaveLength(0);
    });

    it('should not have duplicates', () => {
      registry.registerAll([mockNode2, mockNode3]); // Both are 'action' category
      const categories = registry.getCategories();
      expect(categories).toHaveLength(1);
      expect(categories[0]).toBe('action');
    });
  });

  describe('clear', () => {
    it('should clear all registered definitions', () => {
      registry.registerAll([mockNode1, mockNode2]);
      registry.clear();

      expect(registry.getAll()).toHaveLength(0);
      expect(registry.has('trigger-test')).toBe(false);
      expect(registry.has('action-test')).toBe(false);
    });

    it('should allow re-registering after clear', () => {
      registry.register(mockNode1);
      registry.clear();
      registry.register(mockNode2);

      expect(registry.has('trigger-test')).toBe(false);
      expect(registry.has('action-test')).toBe(true);
    });
  });

  describe('configSchema', () => {
    it('should preserve configSchema in registered definition', () => {
      registry.register(mockNode1);
      const def = registry.get('trigger-test');

      expect(def?.configSchema).toBeDefined();
      expect(def?.configSchema).toHaveLength(1);
      expect(def?.configSchema?.[0].key).toBe('field1');
      expect(def?.configSchema?.[0].type).toBe('text');
      expect(def?.configSchema?.[0].required).toBe(true);
    });

    it('should handle definition without configSchema', () => {
      registry.register(mockNode2);
      const def = registry.get('action-test');
      expect(def?.configSchema).toBeUndefined();
    });
  });
});
