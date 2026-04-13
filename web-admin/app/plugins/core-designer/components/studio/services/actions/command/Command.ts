/**
 * 命令模式核心接口和基础实现
 * 提供可撤销的操作命令系统
 */

/**
 * 命令接口
 * 所有可撤销的操作都必须实现此接口
 */
export interface Command {
  /** 命令唯一标识 */
  readonly id: string;
  /** 命令名称 */
  readonly name?: string;
  /** 命令类型 */
  readonly type: string;
  /** 命令描述 */
  readonly description: string;
  /** 命令创建时间 */
  readonly timestamp: number;
  /** 命令元数据 */
  readonly metadata?: Record<string, any>;
  /** 是否允许执行 */
  canExecute?: () => boolean;

  /** 执行命令 */
  execute(): Promise<void> | void;
  /** 撤销命令 */
  undo(): Promise<void> | void;
  /** 重做命令 */
  redo(): Promise<void> | void;
  /** 检查命令是否可以撤销 */
  canUndo(): boolean;
  /** 检查命令是否可以重做 */
  canRedo(): boolean;
  /** 获取命令状态 */
  getState(): CommandState;
}

/**
 * 命令状态枚举
 */
export enum CommandState {
  /** 未执行 */
  pending = 'pending',
  /** 已执行 */
  EXECUTED = 'executed',
  /** 已撤销 */
  UNDONE = 'undone',
  /** 执行失败 */
  failed = 'failed',
}

/**
 * 命令执行结果
 */
export interface CommandResult {
  /** 是否成功 */
  success: boolean;
  /** 错误信息 */
  error?: string;
  /** 执行数据 */
  data?: any;
  /** 执行时间 */
  duration: number;
}

/**
 * 命令事件类型
 */
export interface CommandEvents {
  /** 命令开始执行 */
  onExecuteStart: (command: Command) => void;
  /** 命令执行完成 */
  onExecuteComplete: (command: Command, result: CommandResult) => void;
  /** 命令开始撤销 */
  onUndoStart: (command: Command) => void;
  /** 命令撤销完成 */
  onUndoComplete: (command: Command, result: CommandResult) => void;
  /** 命令开始重做 */
  onRedoStart: (command: Command) => void;
  /** 命令重做完成 */
  onRedoComplete: (command: Command, result: CommandResult) => void;
  /** 命令执行失败 */
  onError: (command: Command, error: Error) => void;
}

/**
 * 抽象命令基类
 * 提供命令的基础实现
 */
export abstract class BaseCommand implements Command {
  public readonly id: string;
  public readonly type: string;
  public readonly description: string;
  public readonly timestamp: number;
  public readonly metadata?: Record<string, any>;

  protected state: CommandState = CommandState.pending;
  protected executeData?: any;
  protected undoData?: any;

  constructor(type: string, description: string, metadata?: Record<string, any>) {
    this.id = this.generateId();
    this.type = type;
    this.description = description;
    this.timestamp = Date.now();
    this.metadata = metadata;
  }

  /**
   * 生成唯一ID
   */
  protected generateId(): string {
    return `cmd_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`;
  }

  /**
   * 执行命令
   */
  async execute(): Promise<void> {
    if (this.state !== CommandState.pending && this.state !== CommandState.UNDONE) {
      throw new Error(`Command ${this.id} cannot be executed in state ${this.state}`);
    }

    try {
      this.executeData = await this.doExecute();
      this.state = CommandState.EXECUTED;
    } catch (error) {
      this.state = CommandState.failed;
      throw error;
    }
  }

  /**
   * 撤销命令
   */
  async undo(): Promise<void> {
    if (!this.canUndo()) {
      throw new Error(`Command ${this.id} cannot be undone in state ${this.state}`);
    }

    try {
      this.undoData = await this.doUndo();
      this.state = CommandState.UNDONE;
    } catch (error) {
      this.state = CommandState.failed;
      throw error;
    }
  }

  /**
   * 重做命令
   */
  async redo(): Promise<void> {
    if (!this.canRedo()) {
      throw new Error(`Command ${this.id} cannot be redone in state ${this.state}`);
    }

    try {
      this.executeData = await this.doRedo();
      this.state = CommandState.EXECUTED;
    } catch (error) {
      this.state = CommandState.failed;
      throw error;
    }
  }

  /**
   * 检查是否可以撤销
   */
  canUndo(): boolean {
    return this.state === CommandState.EXECUTED;
  }

  /**
   * 检查是否可以重做
   */
  canRedo(): boolean {
    return this.state === CommandState.UNDONE;
  }

  /**
   * 获取命令状态
   */
  getState(): CommandState {
    return this.state;
  }

  /**
   * 子类需要实现的执行逻辑
   */
  protected abstract doExecute(): Promise<any> | any;

  /**
   * 子类需要实现的撤销逻辑
   */
  protected abstract doUndo(): Promise<any> | any;

  /**
   * 子类可以重写的重做逻辑，默认调用执行逻辑
   */
  protected doRedo(): Promise<any> | any {
    return this.doExecute();
  }
}

/**
 * 复合命令
 * 可以包含多个子命令的命令
 */
export class CompositeCommand extends BaseCommand {
  private commands: Command[] = [];

  constructor(description: string, commands: Command[] = [], metadata?: Record<string, any>) {
    super('composite', description, metadata);
    this.commands = [...commands];
  }

  /**
   * 添加子命令
   */
  addCommand(command: Command): void {
    this.commands.push(command);
  }

  /**
   * 移除子命令
   */
  removeCommand(commandId: string): boolean {
    const index = this.commands.findIndex((cmd) => cmd.id === commandId);
    if (index >= 0) {
      this.commands.splice(index, 1);
      return true;
    }
    return false;
  }

  /**
   * 获取子命令列表
   */
  getCommands(): readonly Command[] {
    return this.commands;
  }

  /**
   * 执行所有子命令
   */
  protected async doExecute(): Promise<any> {
    const results: any[] = [];
    for (const command of this.commands) {
      const result = await command.execute();
      results.push(result);
    }
    return results;
  }

  /**
   * 按相反顺序撤销所有子命令
   */
  protected async doUndo(): Promise<any> {
    const results: any[] = [];
    for (let i = this.commands.length - 1; i >= 0; i--) {
      const command = this.commands[i];
      if (command.canUndo()) {
        const result = await command.undo();
        results.push(result);
      }
    }
    return results;
  }

  /**
   * 重做所有子命令
   */
  protected async doRedo(): Promise<any> {
    const results: any[] = [];
    for (const command of this.commands) {
      if (command.canRedo()) {
        const result = await command.redo();
        results.push(result);
      }
    }
    return results;
  }

  /**
   * 检查是否可以撤销
   */
  canUndo(): boolean {
    return super.canUndo() && this.commands.some((cmd) => cmd.canUndo());
  }

  /**
   * 检查是否可以重做
   */
  canRedo(): boolean {
    return super.canRedo() && this.commands.some((cmd) => cmd.canRedo());
  }
}

/**
 * 空命令（空对象模式）
 * 用于表示无操作的命令
 */
export class NullCommand extends BaseCommand {
  constructor() {
    super('null', 'No operation');
  }

  protected doExecute(): void {
    // 无操作
  }

  protected doUndo(): void {
    // 无操作
  }

  canUndo(): boolean {
    return false;
  }

  canRedo(): boolean {
    return false;
  }
}

/**
 * 命令工厂接口
 */
export interface CommandFactory<T extends Command = Command> {
  /** 创建命令 */
  createCommand(...args: any[]): T;
  /** 获取命令类型 */
  getCommandType(): string;
}

/**
 * 命令注册表
 * 管理命令工厂的注册和创建
 */
export class CommandRegistry {
  private static instance: CommandRegistry;
  private factories = new Map<string, CommandFactory>();

  private constructor() {}

  /**
   * 获取单例实例
   */
  static getInstance(): CommandRegistry {
    if (!CommandRegistry.instance) {
      CommandRegistry.instance = new CommandRegistry();
    }
    return CommandRegistry.instance;
  }

  /**
   * 注册命令工厂
   */
  registerFactory(type: string, factory: CommandFactory): void {
    this.factories.set(type, factory);
  }

  /**
   * 注销命令工厂
   */
  unregisterFactory(type: string): boolean {
    return this.factories.delete(type);
  }

  /**
   * 创建命令
   */
  createCommand(type: string, ...args: any[]): Command {
    const factory = this.factories.get(type);
    if (!factory) {
      throw new Error(`No factory registered for command type: ${type}`);
    }
    return factory.createCommand(...args);
  }

  /**
   * 获取已注册的命令类型
   */
  getRegisteredTypes(): string[] {
    return Array.from(this.factories.keys());
  }

  /**
   * 检查命令类型是否已注册
   */
  hasFactory(type: string): boolean {
    return this.factories.has(type);
  }
}

/**
 * 命令执行器
 * 负责执行命令并处理事件
 */
export class CommandExecutor {
  private events?: Partial<CommandEvents>;

  constructor(events?: Partial<CommandEvents>) {
    this.events = events;
  }

  /**
   * 执行命令
   */
  async execute(command: Command): Promise<CommandResult> {
    const startTime = Date.now();

    try {
      this.events?.onExecuteStart?.(command);

      await command.execute();

      const duration = Date.now() - startTime;
      const result: CommandResult = {
        success: true,
        duration,
      };

      this.events?.onExecuteComplete?.(command, result);
      return result;
    } catch (error) {
      const duration = Date.now() - startTime;
      const result: CommandResult = {
        success: false,
        error: error instanceof Error ? error.message : String(error),
        duration,
      };

      this.events?.onError?.(command, error instanceof Error ? error : new Error(String(error)));
      return result;
    }
  }

  /**
   * 撤销命令
   */
  async undo(command: Command): Promise<CommandResult> {
    const startTime = Date.now();

    try {
      this.events?.onUndoStart?.(command);

      await command.undo();

      const duration = Date.now() - startTime;
      const result: CommandResult = {
        success: true,
        duration,
      };

      this.events?.onUndoComplete?.(command, result);
      return result;
    } catch (error) {
      const duration = Date.now() - startTime;
      const result: CommandResult = {
        success: false,
        error: error instanceof Error ? error.message : String(error),
        duration,
      };

      this.events?.onError?.(command, error instanceof Error ? error : new Error(String(error)));
      return result;
    }
  }

  /**
   * 重做命令
   */
  async redo(command: Command): Promise<CommandResult> {
    const startTime = Date.now();

    try {
      this.events?.onRedoStart?.(command);

      await command.redo();

      const duration = Date.now() - startTime;
      const result: CommandResult = {
        success: true,
        duration,
      };

      this.events?.onRedoComplete?.(command, result);
      return result;
    } catch (error) {
      const duration = Date.now() - startTime;
      const result: CommandResult = {
        success: false,
        error: error instanceof Error ? error.message : String(error),
        duration,
      };

      this.events?.onError?.(command, error instanceof Error ? error : new Error(String(error)));
      return result;
    }
  }
}
