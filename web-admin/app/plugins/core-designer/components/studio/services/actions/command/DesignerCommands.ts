/**
 * 设计器专用命令实现
 *
 * 包含所有设计器操作的命令实现
 */

import { BaseCommand } from '~/plugins/core-designer/components/studio/services/actions/command/Command';
import type { FormSchema, Block } from '~/plugins/core-designer/components/studio/workbench/canvas/types';
import { getSchemaManager } from '~/plugins/core-designer/components/studio/services/schema/SchemaManager';

/**
 * 添加组件命令
 */
export class AddComponentCommand extends BaseCommand {
  private parentId: string;
  private component: Block;
  private index?: number;
  private addedComponent?: Block;

  constructor(parentId: string, component: Block, index?: number, metadata?: Record<string, any>) {
    super('add_component', `添加组件: ${component.type}`, metadata);
    this.parentId = parentId;
    this.component = component;
    this.index = index;
  }

  protected async doExecute(): Promise<void> {
    const schemaManager = getSchemaManager();
    this.addedComponent = await schemaManager.addComponent(
      this.parentId,
      this.component,
      this.index,
    );
  }

  protected async doUndo(): Promise<void> {
    if (this.addedComponent?.id) {
      const schemaManager = getSchemaManager();
      await schemaManager.removeComponent(this.addedComponent.id);
    }
  }
}

/**
 * 删除组件命令
 */
export class RemoveComponentCommand extends BaseCommand {
  private componentId: string;
  private removedComponent?: Block;
  private parentId?: string;
  private index?: number;

  constructor(componentId: string, metadata?: Record<string, any>) {
    super('remove_component', `删除组件: ${componentId}`, metadata);
    this.componentId = componentId;
  }

  protected async doExecute(): Promise<void> {
    const schemaManager = getSchemaManager();

    // 保存组件信息用于撤销
    const component = await schemaManager.getComponent(this.componentId);
    this.removedComponent = component || undefined;
    const parentInfo = await schemaManager.getComponentParent(this.componentId);

    if (parentInfo) {
      this.parentId = parentInfo.parentId;
      this.index = parentInfo.index;
    }

    await schemaManager.removeComponent(this.componentId);
  }

  protected async doUndo(): Promise<void> {
    if (this.removedComponent && this.parentId !== undefined) {
      const schemaManager = getSchemaManager();
      await schemaManager.addComponent(this.parentId, this.removedComponent, this.index);
    }
  }
}

/**
 * 更新组件属性命令
 */
export class UpdateComponentPropsCommand extends BaseCommand {
  private componentId: string;
  private newProps: Record<string, any>;
  private oldProps?: Record<string, any>;

  constructor(componentId: string, newProps: Record<string, any>, metadata?: Record<string, any>) {
    super('update_component_props', `更新组件属性: ${componentId}`, metadata);
    this.componentId = componentId;
    this.newProps = newProps;
  }

  protected async doExecute(): Promise<void> {
    const schemaManager = getSchemaManager();
    const component = await schemaManager.getComponent(this.componentId);

    if (component) {
      this.oldProps = { ...component.props };
      await schemaManager.updateComponentProps(this.componentId, this.newProps);
    }
  }

  protected async doUndo(): Promise<void> {
    if (this.oldProps) {
      const schemaManager = getSchemaManager();
      await schemaManager.updateComponentProps(this.componentId, this.oldProps);
    }
  }
}

/**
 * 移动组件命令
 */
export class MoveComponentCommand extends BaseCommand {
  private componentId: string;
  private newParentId: string;
  private newIndex?: number;
  private oldParentId?: string;
  private oldIndex?: number;

  constructor(
    componentId: string,
    newParentId: string,
    newIndex?: number,
    metadata?: Record<string, any>,
  ) {
    super('move_component', `移动组件: ${componentId}`, metadata);
    this.componentId = componentId;
    this.newParentId = newParentId;
    this.newIndex = newIndex;
  }

  protected async doExecute(): Promise<void> {
    const schemaManager = getSchemaManager();

    // 保存原始位置信息
    const parentInfo = await schemaManager.getComponentParent(this.componentId);
    if (parentInfo) {
      this.oldParentId = parentInfo.parentId;
      this.oldIndex = parentInfo.index;
    }

    await schemaManager.moveComponent(this.componentId, this.newParentId, this.newIndex);
  }

  protected async doUndo(): Promise<void> {
    if (this.oldParentId !== undefined) {
      const schemaManager = getSchemaManager();
      await schemaManager.moveComponent(this.componentId, this.oldParentId, this.oldIndex);
    }
  }
}

/**
 * 更新页面配置命令
 */
export class UpdatePageConfigCommand extends BaseCommand {
  private newConfig: Partial<FormSchema>;
  private oldConfig?: Partial<FormSchema>;

  constructor(newConfig: Partial<FormSchema>, metadata?: Record<string, any>) {
    super('update_page_config', '更新页面配置', metadata);
    this.newConfig = newConfig;
  }

  protected async doExecute(): Promise<void> {
    const schemaManager = getSchemaManager();
    const currentSchema = await schemaManager.getSchema();

    // 保存当前配置
    this.oldConfig = {
      title: currentSchema.title,
      description: currentSchema.description,
      version: currentSchema.version,
      theme: currentSchema.theme,
      layout: currentSchema.layout,
    };

    await schemaManager.updateSchema(this.newConfig);
  }

  protected async doUndo(): Promise<void> {
    if (this.oldConfig) {
      const schemaManager = getSchemaManager();
      await schemaManager.updateSchema(this.oldConfig);
    }
  }
}

/**
 * 批量删除组件命令
 */
export class BatchRemoveComponentsCommand extends BaseCommand {
  private componentIds: string[];
  private removedComponents: Array<{
    component: Block;
    parentId: string;
    index: number;
  }> = [];

  constructor(componentIds: string[], metadata?: Record<string, any>) {
    super('batch_remove_components', `批量删除组件: ${componentIds.length} 个`, metadata);
    this.componentIds = componentIds;
  }

  protected async doExecute(): Promise<void> {
    const schemaManager = getSchemaManager();

    // 保存所有组件信息用于撤销
    for (const componentId of this.componentIds) {
      const component = await schemaManager.getComponent(componentId);
      const parentInfo = await schemaManager.getComponentParent(componentId);

      if (component && parentInfo) {
        this.removedComponents.push({
          component,
          parentId: parentInfo.parentId,
          index: parentInfo.index,
        });
      }
    }

    // 按索引倒序删除，避免索引变化影响
    const sortedComponents = [...this.removedComponents].sort((a, b) => b.index - a.index);

    for (const { component } of sortedComponents) {
      await schemaManager.removeComponent(component.id);
    }
  }

  protected async doUndo(): Promise<void> {
    const schemaManager = getSchemaManager();

    // 按原始索引顺序恢复组件
    const sortedComponents = [...this.removedComponents].sort((a, b) => a.index - b.index);

    for (const { component, parentId, index } of sortedComponents) {
      await schemaManager.addComponent(parentId, component, index);
    }
  }
}

/**
 * 交换组件位置命令
 */
export class SwapComponentsCommand extends BaseCommand {
  private componentId1: string;
  private componentId2: string;

  constructor(componentId1: string, componentId2: string, metadata?: Record<string, any>) {
    super('swap_components', `交换组件位置: ${componentId1} <-> ${componentId2}`, metadata);
    this.componentId1 = componentId1;
    this.componentId2 = componentId2;
  }

  protected async doExecute(): Promise<void> {
    const schemaManager = getSchemaManager();

    // 获取两个组件
    const comp1 = await schemaManager.getComponent(this.componentId1);
    const comp2 = await schemaManager.getComponent(this.componentId2);

    if (!comp1 || !comp2) {
      throw new Error(`Component not found: ${!comp1 ? this.componentId1 : this.componentId2}`);
    }

    // 交换组件的所有属性（除了 id）
    const temp = {
      type: comp1.type,
      name: comp1.name,
      props: { ...comp1.props },
      children: comp1.children,
      layout: comp1.layout,
      styles: comp1.styles,
      visible: comp1.visible,
      locked: comp1.locked,
    };

    // comp1 获得 comp2 的属性
    comp1.type = comp2.type;
    comp1.name = comp2.name;
    comp1.props = { ...comp2.props };
    comp1.children = comp2.children;
    comp1.layout = comp2.layout;
    comp1.styles = comp2.styles;
    comp1.visible = comp2.visible;
    comp1.locked = comp2.locked;

    // comp2 获得 comp1 的属性（从 temp）
    comp2.type = temp.type;
    comp2.name = temp.name;
    comp2.props = temp.props;
    comp2.children = temp.children;
    comp2.layout = temp.layout;
    comp2.styles = temp.styles;
    comp2.visible = temp.visible;
    comp2.locked = temp.locked;

    // 更新两个组件
    await schemaManager.updateComponent(this.componentId1, comp1);
    await schemaManager.updateComponent(this.componentId2, comp2);
  }

  protected async doUndo(): Promise<void> {
    // 交换操作是对称的，再次执行相同的交换即可撤销
    await this.doExecute();
  }
}

/**
 * 更新属性命令
 */
export class UpdatePropertyCommand extends BaseCommand {
  private componentId: string;
  private propertyPath: string;
  private newValue: any;
  private oldValue?: any;

  constructor(
    componentId: string,
    propertyPath: string,
    newValue: any,
    metadata?: Record<string, any>,
  ) {
    super('update_property', `更新属性: ${propertyPath}`, metadata);
    this.componentId = componentId;
    this.propertyPath = propertyPath;
    this.newValue = newValue;
  }

  protected async doExecute(): Promise<void> {
    const schemaManager = getSchemaManager();
    const component = await schemaManager.getComponent(this.componentId);

    if (component) {
      // 保存旧值
      this.oldValue = this.getNestedProperty(component, this.propertyPath);

      // 设置新值
      this.setNestedProperty(component, this.propertyPath, this.newValue);

      // 更新组件
      await schemaManager.updateComponent(this.componentId, component);
    }
  }

  protected async doUndo(): Promise<void> {
    if (this.oldValue !== undefined) {
      const schemaManager = getSchemaManager();
      const component = await schemaManager.getComponent(this.componentId);

      if (component) {
        this.setNestedProperty(component, this.propertyPath, this.oldValue);
        await schemaManager.updateComponent(this.componentId, component);
      }
    }
  }

  private getNestedProperty(obj: any, path: string): any {
    return path.split('.').reduce((current, key) => current?.[key], obj);
  }

  private setNestedProperty(obj: any, path: string, value: any): void {
    const keys = path.split('.');
    const lastKey = keys.pop()!;
    const target = keys.reduce((current, key) => {
      if (!current[key]) current[key] = {};
      return current[key];
    }, obj);
    target[lastKey] = value;
  }
}

/**
 * 设计器命令工厂
 */
export class DesignerCommandFactory {
  /**
   * 创建添加组件命令
   */
  createAddComponentCommand(
    parentId: string,
    component: Block,
    index?: number,
    metadata?: Record<string, any>,
  ): AddComponentCommand {
    return new AddComponentCommand(parentId, component, index, metadata);
  }

  /**
   * 创建删除组件命令
   */
  createRemoveComponentCommand(
    componentId: string,
    metadata?: Record<string, any>,
  ): RemoveComponentCommand {
    return new RemoveComponentCommand(componentId, metadata);
  }

  /**
   * 创建更新组件属性命令
   */
  createUpdateComponentPropsCommand(
    componentId: string,
    newProps: Record<string, any>,
    metadata?: Record<string, any>,
  ): UpdateComponentPropsCommand {
    return new UpdateComponentPropsCommand(componentId, newProps, metadata);
  }

  /**
   * 创建移动组件命令
   */
  createMoveComponentCommand(
    componentId: string,
    newParentId: string,
    newIndex?: number,
    metadata?: Record<string, any>,
  ): MoveComponentCommand {
    return new MoveComponentCommand(componentId, newParentId, newIndex, metadata);
  }

  /**
   * 创建更新页面配置命令
   */
  createUpdatePageConfigCommand(
    newConfig: Partial<FormSchema>,
    metadata?: Record<string, any>,
  ): UpdatePageConfigCommand {
    return new UpdatePageConfigCommand(newConfig, metadata);
  }

  /**
   * 创建批量删除组件命令
   */
  createBatchRemoveComponentsCommand(
    componentIds: string[],
    metadata?: Record<string, any>,
  ): BatchRemoveComponentsCommand {
    return new BatchRemoveComponentsCommand(componentIds, metadata);
  }

  /**
   * 创建交换组件位置命令
   */
  createSwapComponentsCommand(
    componentId1: string,
    componentId2: string,
    metadata?: Record<string, any>,
  ): SwapComponentsCommand {
    return new SwapComponentsCommand(componentId1, componentId2, metadata);
  }
}

/**
 * 注册设计器命令工厂
 */
export function registerDesignerCommandFactories(): void {
  // 这里可以注册命令工厂到全局注册表
  // 目前暂时为空实现
}
