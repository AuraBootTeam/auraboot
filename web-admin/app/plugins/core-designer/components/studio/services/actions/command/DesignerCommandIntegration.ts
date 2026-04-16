/**
 * 设计器命令集成系统
 * 将Command Pattern和HistoryStack与Zustand状态管理集成
 */

import { BaseCommand } from '~/plugins/core-designer/components/studio/services/actions/command/Command';
import {
  HistoryStack,
  type HistoryStackConfig,
} from '~/plugins/core-designer/components/studio/services/actions/command/HistoryStack';
import type { Component, PageSchema, Position } from '~/plugins/core-designer/components/studio/workbench/canvas/types';

// 设计器状态接口
export interface DesignerState {
  pageSchema: PageSchema;
  selectedComponents: string[];
  focusedComponent: string | null;
  layoutConfig: any;
}

// 设计器操作接口
export interface DesignerActions {
  setPageSchema: (schema: PageSchema) => void;
  addComponent: (component: Component, position?: Position) => void;
  removeComponent: (componentId: string) => void;
  updateComponent: (componentId: string, updates: Partial<Component>) => void;
  moveComponent: (componentId: string, newPosition: Position) => void;
  selectComponent: (componentId: string, multiSelect?: boolean) => void;
  clearSelection: () => void;
  setLayoutConfig: (config: any) => void;
}

/**
 * 添加组件命令
 */
export class AddComponentCommand extends BaseCommand {
  private component: Component;
  private position?: Position;
  private designerActions: DesignerActions;
  private addedComponentId?: string;

  constructor(
    component: Component,
    designerActions: DesignerActions,
    position?: Position,
    metadata?: Record<string, any>,
  ) {
    super('add_component', `添加组件: ${component.type}`, metadata);
    this.component = component;
    this.position = position;
    this.designerActions = designerActions;
  }

  protected async doExecute(): Promise<void> {
    const newComponent: Component = {
      ...this.component,
      id: this.component.id || `comp_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`,
      position: this.position || { row: 0, column: 0 },
    };

    this.addedComponentId = newComponent.id;
    this.designerActions.addComponent(newComponent, this.position);
  }

  protected async doUndo(): Promise<void> {
    if (this.addedComponentId) {
      this.designerActions.removeComponent(this.addedComponentId);
    }
  }

  getAddedComponentId(): string | undefined {
    return this.addedComponentId;
  }
}

/**
 * 移除组件命令
 */
export class RemoveComponentCommand extends BaseCommand {
  private componentId: string;
  private designerActions: DesignerActions;
  private getDesignerState: () => DesignerState;
  private removedComponent?: Component;

  constructor(
    componentId: string,
    designerActions: DesignerActions,
    getDesignerState: () => DesignerState,
    metadata?: Record<string, any>,
  ) {
    super('remove_component', `移除组件: ${componentId}`, metadata);
    this.componentId = componentId;
    this.designerActions = designerActions;
    this.getDesignerState = getDesignerState;
  }

  protected async doExecute(): Promise<void> {
    // 保存要移除的组件信息
    const state = this.getDesignerState();
    this.removedComponent = state.pageSchema.components.find(
      (comp) => comp.id === this.componentId,
    );

    // 移除组件
    this.designerActions.removeComponent(this.componentId);
  }

  protected async doUndo(): Promise<void> {
    if (this.removedComponent) {
      this.designerActions.addComponent(this.removedComponent, this.removedComponent.position);
    }
  }

  getRemovedComponent(): Component | undefined {
    return this.removedComponent;
  }
}

/**
 * 更新组件属性命令
 */
export class UpdateComponentPropsCommand extends BaseCommand {
  private componentId: string;
  private newProps: Record<string, any>;
  private designerActions: DesignerActions;
  private getDesignerState: () => DesignerState;
  private oldProps?: Record<string, any>;

  constructor(
    componentId: string,
    newProps: Record<string, any>,
    designerActions: DesignerActions,
    getDesignerState: () => DesignerState,
    metadata?: Record<string, any>,
  ) {
    super('update_component_props', `更新组件属性: ${componentId}`, metadata);
    this.componentId = componentId;
    this.newProps = newProps;
    this.designerActions = designerActions;
    this.getDesignerState = getDesignerState;
  }

  protected async doExecute(): Promise<void> {
    // 保存旧属性
    const state = this.getDesignerState();
    const component = state.pageSchema.components.find((comp) => comp.id === this.componentId);
    if (component) {
      this.oldProps = { ...component.props };
    }

    // 更新属性
    this.designerActions.updateComponent(this.componentId, { props: this.newProps });
  }

  protected async doUndo(): Promise<void> {
    if (this.oldProps) {
      this.designerActions.updateComponent(this.componentId, { props: this.oldProps });
    }
  }

  getOldProps(): Record<string, any> | undefined {
    return this.oldProps;
  }

  getNewProps(): Record<string, any> {
    return this.newProps;
  }
}

/**
 * 移动组件命令
 */
export class MoveComponentCommand extends BaseCommand {
  private componentId: string;
  private newPosition: Position;
  private designerActions: DesignerActions;
  private getDesignerState: () => DesignerState;
  private oldPosition?: Position;

  constructor(
    componentId: string,
    newPosition: Position,
    designerActions: DesignerActions,
    getDesignerState: () => DesignerState,
    metadata?: Record<string, any>,
  ) {
    super('move_component', `移动组件: ${componentId}`, metadata);
    this.componentId = componentId;
    this.newPosition = newPosition;
    this.designerActions = designerActions;
    this.getDesignerState = getDesignerState;
  }

  protected async doExecute(): Promise<void> {
    // 保存旧位置
    const state = this.getDesignerState();
    const component = state.pageSchema.components.find((comp) => comp.id === this.componentId);
    if (component?.position) {
      this.oldPosition = { ...component.position };
    }

    // 移动组件
    this.designerActions.moveComponent(this.componentId, this.newPosition);
  }

  protected async doUndo(): Promise<void> {
    if (this.oldPosition) {
      this.designerActions.moveComponent(this.componentId, this.oldPosition);
    }
  }

  getOldPosition(): Position | undefined {
    return this.oldPosition;
  }

  getNewPosition(): Position {
    return this.newPosition;
  }
}

/**
 * 设计器命令管理器
 * 集成HistoryStack和设计器状态管理
 */
export class DesignerCommandManager {
  private historyStack: HistoryStack;
  private designerActions: DesignerActions;
  private getDesignerState: () => DesignerState;

  constructor(
    designerActions: DesignerActions,
    getDesignerState: () => DesignerState,
    config?: Partial<HistoryStackConfig>,
  ) {
    this.designerActions = designerActions;
    this.getDesignerState = getDesignerState;

    // 创建历史栈
    this.historyStack = new HistoryStack({
      maxSize: 50,
      autoSave: false,
      enableCompression: true,
      compressionThreshold: 25,
      ...config,
    });
  }

  /**
   * 执行添加组件命令
   */
  async addComponent(component: Component, position?: Position): Promise<void> {
    const command = new AddComponentCommand(component, this.designerActions, position);
    await this.historyStack.execute(command);
  }

  /**
   * 执行移除组件命令
   */
  async removeComponent(componentId: string): Promise<void> {
    const command = new RemoveComponentCommand(
      componentId,
      this.designerActions,
      this.getDesignerState,
    );
    await this.historyStack.execute(command);
  }

  /**
   * 执行更新组件属性命令
   */
  async updateComponentProps(componentId: string, newProps: Record<string, any>): Promise<void> {
    const command = new UpdateComponentPropsCommand(
      componentId,
      newProps,
      this.designerActions,
      this.getDesignerState,
    );
    await this.historyStack.execute(command);
  }

  /**
   * 执行移动组件命令
   */
  async moveComponent(componentId: string, newPosition: Position): Promise<void> {
    const command = new MoveComponentCommand(
      componentId,
      newPosition,
      this.designerActions,
      this.getDesignerState,
    );
    await this.historyStack.execute(command);
  }

  /**
   * 撤销操作
   */
  async undo(): Promise<boolean> {
    const result = await this.historyStack.undo();
    return result?.success || false;
  }

  /**
   * 重做操作
   */
  async redo(): Promise<boolean> {
    const result = await this.historyStack.redo();
    return result?.success || false;
  }

  /**
   * 检查是否可以撤销
   */
  canUndo(): boolean {
    return this.historyStack.canUndo();
  }

  /**
   * 检查是否可以重做
   */
  canRedo(): boolean {
    return this.historyStack.canRedo();
  }

  /**
   * 获取历史栈状态
   */
  getHistoryState() {
    return this.historyStack.getState();
  }

  /**
   * 清空历史记录
   */
  clearHistory(): void {
    this.historyStack.clear();
  }

  /**
   * 获取历史记录
   */
  getHistory() {
    return this.historyStack.getHistory();
  }
}
