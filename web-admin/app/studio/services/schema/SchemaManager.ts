/**
 * Schema Manager
 *
 * 管理页面设计器的 Schema 操作，包括组件的增删改查等
 */

import type { FormSchema, Block } from '~/studio/domain/schema/types';
import { useDesignerStore } from '~/studio/hooks/store/useDesignerStore';

/**
 * Schema 管理器接口
 */
export interface SchemaManager {
  // 组件操作
  addComponent(parentId: string, component: Block, index?: number): Promise<Block>;
  removeComponent(componentId: string): Promise<void>;
  updateComponent(componentId: string, updates: Partial<Block>): Promise<void>;
  getComponent(componentId: string): Promise<Block | null>;
  getComponentParent(componentId: string): Promise<{ parentId: string; index: number } | null>;
  moveComponent(componentId: string, newParentId: string, newIndex?: number): Promise<void>;
  reorderComponents(parentId: string, newOrder: string[]): Promise<void>;

  // Schema 操作
  getSchema(): Promise<FormSchema>;
  updateSchema(updates: Partial<FormSchema>): Promise<void>;
  updateComponentProps(componentId: string, props: Record<string, any>): Promise<void>;

  // 查询操作
  findComponents(predicate: (component: Block) => boolean): Promise<Block[]>;
  getComponentsByType(type: string): Promise<Block[]>;
  getComponentPath(componentId: string): Promise<string[]>;
}

/**
 * Schema 管理器实现
 */
class SchemaManagerImpl implements SchemaManager {
  private getStore() {
    return useDesignerStore.getState();
  }

  async addComponent(parentId: string, component: Block, index?: number): Promise<Block> {
    const store = this.getStore();
    const schema = store.pageSchema;

    if (!schema) {
      throw new Error('No schema available');
    }

    // 生成唯一ID
    if (!component.id) {
      component.id = `component_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`;
    }

    // 如果是根级组件
    if (parentId === 'root' || !parentId) {
      if (!schema.components) {
        schema.components = [];
      }

      if (index !== undefined && index >= 0 && index < schema.components.length) {
        schema.components.splice(index, 0, component);
      } else {
        schema.components.push(component);
      }
    } else {
      // 查找父组件
      const parent = await this.getComponent(parentId);
      if (!parent) {
        throw new Error(`Parent component not found: ${parentId}`);
      }

      if (!parent.children) {
        parent.children = [];
      }

      if (index !== undefined && index >= 0 && index < parent.children.length) {
        parent.children.splice(index, 0, component);
      } else {
        parent.children.push(component);
      }
    }

    // 更新 store
    store.setPageSchema({ ...schema });
    store.addComponent(component);

    return component;
  }

  async removeComponent(componentId: string): Promise<void> {
    const store = this.getStore();
    const schema = store.pageSchema;

    if (!schema) {
      throw new Error('No schema available');
    }

    // 递归查找并移除组件
    const removeFromArray = (components: Block[]): boolean => {
      for (let i = 0; i < components.length; i++) {
        if (components[i].id === componentId) {
          components.splice(i, 1);
          return true;
        }

        if (components[i].children && removeFromArray(components[i].children!)) {
          return true;
        }
      }
      return false;
    };

    if (schema.components) {
      removeFromArray(schema.components);
    }

    // 更新 store
    store.setPageSchema({ ...schema });
    store.removeComponent(componentId);
  }

  async updateComponent(componentId: string, updates: Partial<Block>): Promise<void> {
    const component = await this.getComponent(componentId);
    if (!component) {
      throw new Error(`Component not found: ${componentId}`);
    }

    Object.assign(component, updates);

    const store = this.getStore();
    store.updateComponent(componentId, updates);
  }

  async getComponent(componentId: string): Promise<Block | null> {
    const store = this.getStore();
    const schema = store.pageSchema;

    if (!schema || !schema.components) {
      return null;
    }

    // 递归查找组件
    const findComponent = (components: Block[]): Block | null => {
      for (const component of components) {
        if (component.id === componentId) {
          return component;
        }

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
    const store = this.getStore();
    const schema = store.pageSchema;

    if (!schema || !schema.components) {
      return null;
    }

    // 递归查找父组件
    const findParent = (
      components: Block[],
      parentId: string = 'root',
    ): { parentId: string; index: number } | null => {
      for (let i = 0; i < components.length; i++) {
        const component = components[i];

        if (component.id === componentId) {
          return { parentId, index: i };
        }

        if (component.children) {
          const found = findParent(component.children, component.id);
          if (found) return found;
        }
      }
      return null;
    };

    return findParent(schema.components);
  }

  async moveComponent(componentId: string, newParentId: string, newIndex?: number): Promise<void> {
    // 先获取组件
    const component = await this.getComponent(componentId);
    if (!component) {
      throw new Error(`Component not found: ${componentId}`);
    }

    // 移除组件
    await this.removeComponent(componentId);

    // 添加到新位置
    await this.addComponent(newParentId, component, newIndex);
  }

  async reorderComponents(parentId: string, newOrder: string[]): Promise<void> {
    const store = this.getStore();
    const schema = store.pageSchema;

    if (!schema) {
      throw new Error('No schema available');
    }

    let targetComponents: Block[];

    if (parentId === 'root' || !parentId) {
      if (!schema.components) return;
      targetComponents = schema.components;
    } else {
      const parent = await this.getComponent(parentId);
      if (!parent || !parent.children) {
        throw new Error(`Parent component not found or has no children: ${parentId}`);
      }
      targetComponents = parent.children;
    }

    // 按新顺序重新排列
    const reorderedComponents: Block[] = [];

    for (const componentId of newOrder) {
      const component = targetComponents.find((c) => c.id === componentId);
      if (component) {
        reorderedComponents.push(component);
      }
    }

    // 添加不在新顺序中的组件
    for (const component of targetComponents) {
      if (!newOrder.includes(component.id)) {
        reorderedComponents.push(component);
      }
    }

    // 更新数组
    if (parentId === 'root' || !parentId) {
      schema.components = reorderedComponents;
    } else {
      const parent = await this.getComponent(parentId);
      if (parent) {
        parent.children = reorderedComponents;
      }
    }

    // 更新 store
    store.setPageSchema({ ...schema });
  }

  async getSchema(): Promise<FormSchema> {
    const store = this.getStore();
    const schema = store.pageSchema;

    if (!schema) {
      throw new Error('No schema available');
    }

    return schema;
  }

  async updateSchema(updates: Partial<FormSchema>): Promise<void> {
    const store = this.getStore();
    const currentSchema = store.pageSchema;

    if (!currentSchema) {
      throw new Error('No schema available');
    }

    const updatedSchema = { ...currentSchema, ...updates };
    store.setPageSchema(updatedSchema);
  }

  async updateComponentProps(componentId: string, props: Record<string, any>): Promise<void> {
    const component = await this.getComponent(componentId);
    if (!component) {
      throw new Error(`Component not found: ${componentId}`);
    }

    component.props = { ...component.props, ...props };

    const store = this.getStore();
    store.updateComponent(componentId, { props: component.props });
  }

  async findComponents(predicate: (component: Block) => boolean): Promise<Block[]> {
    const store = this.getStore();
    const schema = store.pageSchema;

    if (!schema || !schema.components) {
      return [];
    }

    const results: Block[] = [];

    const searchComponents = (components: Block[]) => {
      for (const component of components) {
        if (predicate(component)) {
          results.push(component);
        }

        if (component.children) {
          searchComponents(component.children);
        }
      }
    };

    searchComponents(schema.components);
    return results;
  }

  async getComponentsByType(type: string): Promise<Block[]> {
    return this.findComponents((component) => component.type === type);
  }

  async getComponentPath(componentId: string): Promise<string[]> {
    const store = this.getStore();
    const schema = store.pageSchema;

    if (!schema || !schema.components) {
      return [];
    }

    const path: string[] = [];

    const findPath = (components: Block[], currentPath: string[] = []): boolean => {
      for (const component of components) {
        const newPath = [...currentPath, component.id];

        if (component.id === componentId) {
          path.push(...newPath);
          return true;
        }

        if (component.children && findPath(component.children, newPath)) {
          return true;
        }
      }
      return false;
    };

    findPath(schema.components);
    return path;
  }
}

// 全局实例
let globalSchemaManager: SchemaManager | null = null;

/**
 * 获取全局 Schema 管理器实例
 */
export function getSchemaManager(): SchemaManager {
  if (!globalSchemaManager) {
    globalSchemaManager = new SchemaManagerImpl();
  }
  return globalSchemaManager;
}

/**
 * 创建新的 Schema 管理器实例
 */
export function createSchemaManager(): SchemaManager {
  return new SchemaManagerImpl();
}

export default getSchemaManager;
