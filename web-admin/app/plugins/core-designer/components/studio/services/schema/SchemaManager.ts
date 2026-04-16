/**
 * Schema Manager
 *
 * Stateless utility for schema operations (add/remove/update/query components).
 * Callers must inject the current schema and a callback to propagate changes.
 */

import type { CanvasSchema, Block } from '~/plugins/core-designer/components/studio/workbench/canvas/types';

/**
 * Schema manager interface
 */
export interface SchemaManager {
  // Component mutations
  addComponent(parentId: string, component: Block, index?: number): Promise<Block>;
  removeComponent(componentId: string): Promise<void>;
  updateComponent(componentId: string, updates: Partial<Block>): Promise<void>;
  getComponent(componentId: string): Promise<Block | null>;
  getComponentParent(componentId: string): Promise<{ parentId: string; index: number } | null>;
  moveComponent(componentId: string, newParentId: string, newIndex?: number): Promise<void>;
  reorderComponents(parentId: string, newOrder: string[]): Promise<void>;

  // Schema mutations
  getSchema(): Promise<CanvasSchema>;
  updateSchema(updates: Partial<CanvasSchema>): Promise<void>;
  updateComponentProps(componentId: string, props: Record<string, any>): Promise<void>;

  // Queries
  findComponents(predicate: (component: Block) => boolean): Promise<Block[]>;
  getComponentsByType(type: string): Promise<Block[]>;
  getComponentPath(componentId: string): Promise<string[]>;
}

/**
 * Schema manager implementation — stateless; schema is injected via bind().
 */
class SchemaManagerImpl implements SchemaManager {
  private _schema: CanvasSchema | null = null;
  private _onChange: ((schema: CanvasSchema) => void) | null = null;

  /**
   * Bind a live schema + onChange callback so the manager knows what to read/write.
   * Call this whenever the parent component re-renders with a new schema reference.
   */
  bind(schema: CanvasSchema, onChange: (schema: CanvasSchema) => void): void {
    this._schema = schema;
    this._onChange = onChange;
  }

  private getSchema_(): CanvasSchema {
    if (!this._schema) {
      throw new Error('SchemaManager: schema not bound — call bind(schema, onChange) first');
    }
    return this._schema;
  }

  private emit(schema: CanvasSchema): void {
    this._schema = schema;
    this._onChange?.(schema);
  }

  // ─── Component queries ────────────────────────────────────────────

  async getComponent(componentId: string): Promise<Block | null> {
    const schema = this.getSchema_();
    if (!schema.components) return null;

    const findComponent = (components: Block[]): Block | null => {
      for (const component of components) {
        if (component.id === componentId) return component;
        if (component.children) {
          const found = findComponent(component.children);
          if (found) return found;
        }
      }
      return null;
    };

    return findComponent(schema.components);
  }

  async getComponentParent(
    componentId: string,
  ): Promise<{ parentId: string; index: number } | null> {
    const schema = this.getSchema_();
    if (!schema.components) return null;

    const findParent = (
      components: Block[],
      parentId: string = 'root',
    ): { parentId: string; index: number } | null => {
      for (let i = 0; i < components.length; i++) {
        const component = components[i];
        if (component.id === componentId) return { parentId, index: i };
        if (component.children) {
          const found = findParent(component.children, component.id);
          if (found) return found;
        }
      }
      return null;
    };

    return findParent(schema.components);
  }

  async findComponents(predicate: (component: Block) => boolean): Promise<Block[]> {
    const schema = this.getSchema_();
    if (!schema.components) return [];

    const results: Block[] = [];

    const searchComponents = (components: Block[]) => {
      for (const component of components) {
        if (predicate(component)) results.push(component);
        if (component.children) searchComponents(component.children);
      }
    };

    searchComponents(schema.components);
    return results;
  }

  async getComponentsByType(type: string): Promise<Block[]> {
    return this.findComponents((component) => component.type === type);
  }

  async getComponentPath(componentId: string): Promise<string[]> {
    const schema = this.getSchema_();
    if (!schema.components) return [];

    const path: string[] = [];

    const findPath = (components: Block[], currentPath: string[] = []): boolean => {
      for (const component of components) {
        const newPath = [...currentPath, component.id];
        if (component.id === componentId) {
          path.push(...newPath);
          return true;
        }
        if (component.children && findPath(component.children, newPath)) return true;
      }
      return false;
    };

    findPath(schema.components);
    return path;
  }

  // ─── Schema queries ───────────────────────────────────────────────

  async getSchema(): Promise<CanvasSchema> {
    return this.getSchema_();
  }

  // ─── Schema mutations (immutable) ────────────────────────────────

  async addComponent(parentId: string, component: Block, index?: number): Promise<Block> {
    const schema = this.getSchema_();

    const newComponent = {
      ...component,
      id: component.id || `component_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`,
    };

    // Deep-clone the component tree to avoid mutation
    const cloneComponents = (components: Block[]): Block[] =>
      components.map((c) => ({
        ...c,
        children: c.children ? cloneComponents(c.children) : c.children,
      }));

    const insertInto = (components: Block[]): Block[] => {
      if (index !== undefined && index >= 0 && index < components.length) {
        const copy = [...components];
        copy.splice(index, 0, newComponent);
        return copy;
      }
      return [...components, newComponent];
    };

    let updatedSchema: CanvasSchema;

    if (!parentId || parentId === 'root') {
      const current = cloneComponents(schema.components || []);
      updatedSchema = { ...schema, components: insertInto(current) };
    } else {
      const addToParent = (components: Block[]): Block[] =>
        components.map((c) => {
          if (c.id === parentId) {
            return { ...c, children: insertInto(c.children ? cloneComponents(c.children) : []) };
          }
          if (c.children) return { ...c, children: addToParent(c.children) };
          return c;
        });

      updatedSchema = { ...schema, components: addToParent(schema.components || []) };
    }

    this.emit(updatedSchema);
    return newComponent;
  }

  async removeComponent(componentId: string): Promise<void> {
    const schema = this.getSchema_();

    const removeFromArray = (components: Block[]): Block[] =>
      components
        .filter((c) => c.id !== componentId)
        .map((c) => ({
          ...c,
          children: c.children ? removeFromArray(c.children) : c.children,
        }));

    const updatedComponents = removeFromArray(schema.components || []);
    this.emit({ ...schema, components: updatedComponents });
  }

  async updateComponent(componentId: string, updates: Partial<Block>): Promise<void> {
    const schema = this.getSchema_();

    const updateInArray = (components: Block[]): Block[] =>
      components.map((c) => {
        if (c.id === componentId) return { ...c, ...updates };
        if (c.children) return { ...c, children: updateInArray(c.children) };
        return c;
      });

    this.emit({ ...schema, components: updateInArray(schema.components || []) });
  }

  async moveComponent(componentId: string, newParentId: string, newIndex?: number): Promise<void> {
    const component = await this.getComponent(componentId);
    if (!component) throw new Error(`Component not found: ${componentId}`);

    // Remove first, then re-add — each step calls emit, so bind schema again after remove
    await this.removeComponent(componentId);
    await this.addComponent(newParentId, component, newIndex);
  }

  async reorderComponents(parentId: string, newOrder: string[]): Promise<void> {
    const schema = this.getSchema_();

    const reorder = (components: Block[]): Block[] => {
      const reordered: Block[] = [];
      for (const id of newOrder) {
        const found = components.find((c) => c.id === id);
        if (found) reordered.push(found);
      }
      // Append any components not in newOrder
      for (const c of components) {
        if (!newOrder.includes(c.id)) reordered.push(c);
      }
      return reordered;
    };

    let updatedSchema: CanvasSchema;

    if (!parentId || parentId === 'root') {
      updatedSchema = { ...schema, components: reorder(schema.components || []) };
    } else {
      const reorderInParent = (components: Block[]): Block[] =>
        components.map((c) => {
          if (c.id === parentId) return { ...c, children: reorder(c.children || []) };
          if (c.children) return { ...c, children: reorderInParent(c.children) };
          return c;
        });

      updatedSchema = { ...schema, components: reorderInParent(schema.components || []) };
    }

    this.emit(updatedSchema);
  }

  async updateSchema(updates: Partial<CanvasSchema>): Promise<void> {
    const schema = this.getSchema_();
    this.emit({ ...schema, ...updates });
  }

  async updateComponentProps(componentId: string, props: Record<string, any>): Promise<void> {
    const component = await this.getComponent(componentId);
    if (!component) throw new Error(`Component not found: ${componentId}`);

    await this.updateComponent(componentId, { props: { ...component.props, ...props } });
  }
}

// ─── Singleton ────────────────────────────────────────────────────

let globalSchemaManager: SchemaManagerImpl | null = null;

/**
 * Get the global SchemaManager instance.
 * Call `getSchemaManager().bind(schema, onChange)` before invoking any mutation methods.
 */
export function getSchemaManager(): SchemaManagerImpl {
  if (!globalSchemaManager) {
    globalSchemaManager = new SchemaManagerImpl();
  }
  return globalSchemaManager;
}

/**
 * Create a fresh SchemaManager instance (useful for tests).
 */
export function createSchemaManager(): SchemaManager {
  return new SchemaManagerImpl();
}

export default getSchemaManager;
