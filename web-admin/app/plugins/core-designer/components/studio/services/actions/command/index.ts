import type { Component, PageSchema } from '~/plugins/core-designer/components/studio/workbench/canvas/types';

/**
 * 命令接口 - 所有命令都必须实现此接口
 */
export interface Command {
  /** 命令唯一标识 */
  id: string;
  /** 命令类型 */
  type: string;
  /** 命令描述 */
  description: string;
  /** 执行时间戳 */
  timestamp: number;
  /** 执行命令 */
  execute(): void;
  /** 撤销命令 */
  undo(): void;
  /** 重做命令 */
  redo(): void;
  /** 是否可以撤销 */
  canUndo(): boolean;
  /** 是否可以重做 */
  canRedo(): boolean;
}

/**
 * 抽象命令基类
 */
export abstract class BaseCommand implements Command {
  public readonly id: string;
  public readonly type: string;
  public readonly description: string;
  public readonly timestamp: number;
  protected executed: boolean = false;

  constructor(type: string, description: string) {
    this.id = `cmd_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`;
    this.type = type;
    this.description = description;
    this.timestamp = Date.now();
  }

  abstract execute(): void;
  abstract undo(): void;

  redo(): void {
    this.execute();
  }

  canUndo(): boolean {
    return this.executed;
  }

  canRedo(): boolean {
    return !this.executed;
  }
}

/**
 * 添加组件命令
 */
export class AddComponentCommand extends BaseCommand {
  private component: Component;
  private targetIndex: number;
  private pageSchema: PageSchema;

  constructor(component: Component, targetIndex: number, pageSchema: PageSchema) {
    super('add_component', `添加组件: ${component.type}`);
    this.component = component;
    this.targetIndex = targetIndex;
    this.pageSchema = pageSchema;
  }

  execute(): void {
    if (this.executed) return;

    this.pageSchema.components.splice(this.targetIndex, 0, this.component);
    this.executed = true;
  }

  undo(): void {
    if (!this.executed) return;

    this.pageSchema.components.splice(this.targetIndex, 1);
    this.executed = false;
  }
}

/**
 * 删除组件命令
 */
export class RemoveComponentCommand extends BaseCommand {
  private component: Component;
  private originalIndex: number;
  private pageSchema: PageSchema;

  constructor(component: Component, originalIndex: number, pageSchema: PageSchema) {
    super('remove_component', `删除组件: ${component.type}`);
    this.component = component;
    this.originalIndex = originalIndex;
    this.pageSchema = pageSchema;
  }

  execute(): void {
    if (this.executed) return;

    this.pageSchema.components.splice(this.originalIndex, 1);
    this.executed = true;
  }

  undo(): void {
    if (!this.executed) return;

    this.pageSchema.components.splice(this.originalIndex, 0, this.component);
    this.executed = false;
  }
}

/**
 * 移动组件命令
 */
export class MoveComponentCommand extends BaseCommand {
  private componentId: string;
  private fromIndex: number;
  private toIndex: number;
  private pageSchema: PageSchema;

  constructor(componentId: string, fromIndex: number, toIndex: number, pageSchema: PageSchema) {
    super('move_component', `移动组件: ${componentId}`);
    this.componentId = componentId;
    this.fromIndex = fromIndex;
    this.toIndex = toIndex;
    this.pageSchema = pageSchema;
  }

  execute(): void {
    if (this.executed) return;

    const component = this.pageSchema.components.splice(this.fromIndex, 1)[0];
    this.pageSchema.components.splice(this.toIndex, 0, component);
    this.executed = true;
  }

  undo(): void {
    if (!this.executed) return;

    const component = this.pageSchema.components.splice(this.toIndex, 1)[0];
    this.pageSchema.components.splice(this.fromIndex, 0, component);
    this.executed = false;
  }
}

/**
 * 更新组件属性命令
 */
export class UpdateComponentCommand extends BaseCommand {
  private componentId: string;
  private oldProps: Record<string, any>;
  private newProps: Record<string, any>;
  private pageSchema: PageSchema;

  constructor(
    componentId: string,
    oldProps: Record<string, any>,
    newProps: Record<string, any>,
    pageSchema: PageSchema,
  ) {
    super('update_component', `更新组件属性: ${componentId}`);
    this.componentId = componentId;
    this.oldProps = { ...oldProps };
    this.newProps = { ...newProps };
    this.pageSchema = pageSchema;
  }

  execute(): void {
    if (this.executed) return;

    const component = this.pageSchema.components.find((c) => c.id === this.componentId);
    if (component) {
      Object.assign(component.props, this.newProps);
      this.executed = true;
    }
  }

  undo(): void {
    if (!this.executed) return;

    const component = this.pageSchema.components.find((c) => c.id === this.componentId);
    if (component) {
      Object.assign(component.props, this.oldProps);
      this.executed = false;
    }
  }
}

/**
 * 批量命令 - 用于执行多个命令作为一个原子操作
 */
export class BatchCommand extends BaseCommand {
  private commands: Command[];

  constructor(commands: Command[], description: string = '批量操作') {
    super('batch_command', description);
    this.commands = commands;
  }

  execute(): void {
    if (this.executed) return;

    this.commands.forEach((command) => command.execute());
    this.executed = true;
  }

  undo(): void {
    if (!this.executed) return;

    // 逆序撤销
    this.commands
      .slice()
      .reverse()
      .forEach((command) => command.undo());
    this.executed = false;
  }
}

/**
 * 命令调用器 - 负责执行命令和管理命令历史
 */
export class CommandInvoker {
  private static instance: CommandInvoker;
  private currentCommand: Command | null = null;

  private constructor() {}

  static getInstance(): CommandInvoker {
    if (!CommandInvoker.instance) {
      CommandInvoker.instance = new CommandInvoker();
    }
    return CommandInvoker.instance;
  }

  /**
   * 执行命令
   */
  execute(command: Command): void {
    try {
      command.execute();
      this.currentCommand = command;
    } catch (error) {
      console.error('命令执行失败:', error);
      throw error;
    }
  }

  /**
   * 撤销当前命令
   */
  undo(): void {
    if (this.currentCommand && this.currentCommand.canUndo()) {
      try {
        this.currentCommand.undo();
      } catch (error) {
        console.error('命令撤销失败:', error);
        throw error;
      }
    }
  }

  /**
   * 重做当前命令
   */
  redo(): void {
    if (this.currentCommand && this.currentCommand.canRedo()) {
      try {
        this.currentCommand.redo();
      } catch (error) {
        console.error('命令重做失败:', error);
        throw error;
      }
    }
  }

  /**
   * 获取当前命令
   */
  getCurrentCommand(): Command | null {
    return this.currentCommand;
  }

  /**
   * 清除当前命令
   */
  clear(): void {
    this.currentCommand = null;
  }
}

/**
 * 命令工厂 - 用于创建各种类型的命令
 */
export class CommandFactory {
  static createAddComponentCommand(
    component: Component,
    targetIndex: number,
    pageSchema: PageSchema,
  ): AddComponentCommand {
    return new AddComponentCommand(component, targetIndex, pageSchema);
  }

  static createRemoveComponentCommand(
    component: Component,
    originalIndex: number,
    pageSchema: PageSchema,
  ): RemoveComponentCommand {
    return new RemoveComponentCommand(component, originalIndex, pageSchema);
  }

  static createMoveComponentCommand(
    componentId: string,
    fromIndex: number,
    toIndex: number,
    pageSchema: PageSchema,
  ): MoveComponentCommand {
    return new MoveComponentCommand(componentId, fromIndex, toIndex, pageSchema);
  }

  static createUpdateComponentCommand(
    componentId: string,
    oldProps: Record<string, any>,
    newProps: Record<string, any>,
    pageSchema: PageSchema,
  ): UpdateComponentCommand {
    return new UpdateComponentCommand(componentId, oldProps, newProps, pageSchema);
  }

  static createBatchCommand(commands: Command[], description?: string): BatchCommand {
    return new BatchCommand(commands, description);
  }
}

// Re-export commands from DesignerCommands
export * from '~/plugins/core-designer/components/studio/services/actions/command/DesignerCommands';
