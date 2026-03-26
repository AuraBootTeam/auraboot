/**
 * 撤销重做管理器
 * 整合命令系统和历史栈，提供完整的撤销重做功能
 */

import {
  HistoryStack,
  type HistoryStackConfig,
  type HistoryStackEvents,
  createHistoryStack,
} from '~/studio/services/actions/command/HistoryStack';
import type {
  Command,
  CommandResult,
  CommandEvents,
} from '~/studio/services/actions/command/Command';
import { CommandExecutor } from '~/studio/services/actions/command/Command';
import {
  DesignerCommandFactory,
  registerDesignerCommandFactories,
} from '~/studio/services/actions/command/DesignerCommands';

/**
 * 撤销重做管理器配置
 */
export interface UndoRedoManagerConfig {
  /** 历史栈配置 */
  historyStack: Partial<HistoryStackConfig>;
  /** 是否启用自动合并相似命令 */
  enableCommandMerging: boolean;
  /** 命令合并时间窗口（毫秒） */
  commandMergeWindow: number;
  /** 是否启用命令验证 */
  enableCommandValidation: boolean;
  /** 是否启用性能监控 */
  enablePerformanceMonitoring: boolean;
}

/**
 * 撤销重做管理器状态
 */
export interface UndoRedoManagerState {
  /** 是否可以撤销 */
  canUndo: boolean;
  /** 是否可以重做 */
  canRedo: boolean;
  /** 是否正在执行操作 */
  isExecuting: boolean;
  /** 当前历史位置 */
  currentIndex: number;
  /** 历史记录总数 */
  totalCount: number;
  /** 最后执行的命令描述 */
  lastCommandDescription?: string;
  /** 下一个可重做的命令描述 */
  nextRedoCommandDescription?: string;
  /** 性能统计 */
  performanceStats: {
    totalExecutions: number;
    totalUndos: number;
    totalRedos: number;
    averageExecutionTime: number;
    averageUndoTime: number;
    averageRedoTime: number;
  };
}

/**
 * 撤销重做管理器事件
 */
export interface UndoRedoManagerEvents
  extends CommandEvents, Omit<HistoryStackEvents, 'onStateChange'> {
  /** 状态变化 */
  onStateChange: (state: UndoRedoManagerState) => void;
  /** 命令合并 */
  onCommandMerged: (originalCommand: Command, mergedCommand: Command) => void;
  /** 命令验证失败 */
  onCommandValidationFailed: (command: Command, error: Error) => void;
  /** 性能警告 */
  onPerformanceWarning: (operation: string, duration: number, threshold: number) => void;
}

/**
 * 命令合并策略
 */
interface CommandMergeStrategy {
  /** 检查是否可以合并 */
  canMerge: (command1: Command, command2: Command) => boolean;
  /** 合并命令 */
  merge: (command1: Command, command2: Command) => Command;
}

/**
 * 撤销重做管理器
 */
export class UndoRedoManager {
  private config: UndoRedoManagerConfig;
  private historyStack: HistoryStack;
  private events?: Partial<UndoRedoManagerEvents>;
  private commandFactory: DesignerCommandFactory;
  private mergeStrategies: Map<string, CommandMergeStrategy> = new Map();
  private lastCommandTime: number = 0;
  private performanceStats = {
    totalExecutions: 0,
    totalUndos: 0,
    totalRedos: 0,
    totalExecutionTime: 0,
    totalUndoTime: 0,
    totalRedoTime: 0,
  };

  constructor(
    config: Partial<UndoRedoManagerConfig> = {},
    events?: Partial<UndoRedoManagerEvents>,
  ) {
    this.config = {
      historyStack: {},
      enableCommandMerging: true,
      commandMergeWindow: 1000, // 1秒
      enableCommandValidation: true,
      enablePerformanceMonitoring: true,
      ...config,
    };

    this.events = events;
    this.commandFactory = new DesignerCommandFactory();

    // 注册设计器命令工厂
    registerDesignerCommandFactories();

    // 创建历史栈
    this.historyStack = createHistoryStack(this.config.historyStack, {
      ...events,
      onStateChange: () => {
        this.notifyStateChange();
        events?.onStateChange?.(this.getState());
      },
    });

    // 初始化命令合并策略
    this.initializeMergeStrategies();
  }

  /**
   * 执行命令
   */
  async execute(command: Command): Promise<CommandResult> {
    const startTime = performance.now();

    try {
      // 命令验证
      if (this.config.enableCommandValidation) {
        await this.validateCommand(command);
      }

      // 检查是否可以与上一个命令合并
      let commandToExecute = command;
      if (this.config.enableCommandMerging) {
        commandToExecute = await this.tryMergeCommand(command);
      }

      // 执行命令
      const result = await this.historyStack.execute(commandToExecute);

      // 更新统计信息
      if (this.config.enablePerformanceMonitoring) {
        this.updateExecutionStats(startTime);
      }

      this.lastCommandTime = Date.now();
      return result;
    } catch (error) {
      if (this.config.enableCommandValidation && error instanceof Error) {
        this.events?.onCommandValidationFailed?.(command, error);
      }
      throw error;
    }
  }

  /**
   * 撤销操作
   */
  async undo(): Promise<CommandResult | null> {
    const startTime = performance.now();

    try {
      const result = await this.historyStack.undo();

      if (this.config.enablePerformanceMonitoring && result) {
        this.updateUndoStats(startTime);
      }

      return result;
    } catch (error) {
      throw error;
    }
  }

  /**
   * 重做操作
   */
  async redo(): Promise<CommandResult | null> {
    const startTime = performance.now();

    try {
      const result = await this.historyStack.redo();

      if (this.config.enablePerformanceMonitoring && result) {
        this.updateRedoStats(startTime);
      }

      return result;
    } catch (error) {
      throw error;
    }
  }

  /**
   * 批量撤销
   */
  async undoMultiple(count: number): Promise<CommandResult[]> {
    const results: CommandResult[] = [];

    for (let i = 0; i < count; i++) {
      const result = await this.undo();
      if (result) {
        results.push(result);
      } else {
        break;
      }
    }

    return results;
  }

  /**
   * 批量重做
   */
  async redoMultiple(count: number): Promise<CommandResult[]> {
    const results: CommandResult[] = [];

    for (let i = 0; i < count; i++) {
      const result = await this.redo();
      if (result) {
        results.push(result);
      } else {
        break;
      }
    }

    return results;
  }

  /**
   * 撤销到指定位置
   */
  async undoTo(targetIndex: number): Promise<CommandResult[]> {
    return this.historyStack.undoTo(targetIndex);
  }

  /**
   * 重做到指定位置
   */
  async redoTo(targetIndex: number): Promise<CommandResult[]> {
    return this.historyStack.redoTo(targetIndex);
  }

  /**
   * 获取管理器状态
   */
  getState(): UndoRedoManagerState {
    const stackState = this.historyStack.getState();
    const history = this.historyStack.getHistory();
    const redoHistory = this.historyStack.getRedoHistory();

    return {
      canUndo: stackState.canUndo,
      canRedo: stackState.canRedo,
      isExecuting: stackState.isExecuting,
      currentIndex: stackState.currentIndex,
      totalCount: stackState.totalCount,
      lastCommandDescription: stackState.lastCommand?.description,
      nextRedoCommandDescription: redoHistory[0]?.description,
      performanceStats: {
        totalExecutions: this.performanceStats.totalExecutions,
        totalUndos: this.performanceStats.totalUndos,
        totalRedos: this.performanceStats.totalRedos,
        averageExecutionTime:
          this.performanceStats.totalExecutions > 0
            ? this.performanceStats.totalExecutionTime / this.performanceStats.totalExecutions
            : 0,
        averageUndoTime:
          this.performanceStats.totalUndos > 0
            ? this.performanceStats.totalUndoTime / this.performanceStats.totalUndos
            : 0,
        averageRedoTime:
          this.performanceStats.totalRedos > 0
            ? this.performanceStats.totalRedoTime / this.performanceStats.totalRedos
            : 0,
      },
    };
  }

  /**
   * 获取历史记录
   */
  getHistory(): readonly Command[] {
    return this.historyStack.getHistory();
  }

  /**
   * 获取撤销历史
   */
  getUndoHistory(): readonly Command[] {
    return this.historyStack.getUndoHistory();
  }

  /**
   * 获取重做历史
   */
  getRedoHistory(): readonly Command[] {
    return this.historyStack.getRedoHistory();
  }

  /**
   * 清空历史记录
   */
  clear(): void {
    this.historyStack.clear();
    this.resetPerformanceStats();
  }

  /**
   * 添加命令合并策略
   */
  addMergeStrategy(commandType: string, strategy: CommandMergeStrategy): void {
    this.mergeStrategies.set(commandType, strategy);
  }

  /**
   * 移除命令合并策略
   */
  removeMergeStrategy(commandType: string): void {
    this.mergeStrategies.delete(commandType);
  }

  /**
   * 导出历史记录
   */
  export(): string {
    return this.historyStack.export();
  }

  /**
   * 导入历史记录
   */
  import(data: string): void {
    this.historyStack.import(data);
    this.resetPerformanceStats();
  }

  /**
   * 销毁管理器
   */
  destroy(): void {
    this.historyStack.destroy();
    this.mergeStrategies.clear();
    this.resetPerformanceStats();
  }

  /**
   * 验证命令
   */
  private async validateCommand(command: Command): Promise<void> {
    // 检查命令基本属性
    if (!command.id || !command.type || !command.description) {
      throw new Error('Invalid command: missing required properties');
    }

    // 检查命令是否已过期
    const now = Date.now();
    const commandAge = now - command.timestamp;
    const maxAge = 5 * 60 * 1000; // 5分钟

    if (commandAge > maxAge) {
      throw new Error('Command has expired');
    }

    // 可以添加更多验证逻辑
  }

  /**
   * 尝试合并命令
   */
  private async tryMergeCommand(command: Command): Promise<Command> {
    const history = this.historyStack.getHistory();

    if (history.length === 0) {
      return command;
    }

    const lastCommand = history[history.length - 1];
    const timeDiff = Date.now() - this.lastCommandTime;

    // 检查时间窗口
    if (timeDiff > this.config.commandMergeWindow) {
      return command;
    }

    // 检查是否有合并策略
    const strategy = this.mergeStrategies.get(command.type);
    if (!strategy || !strategy.canMerge(lastCommand, command)) {
      return command;
    }

    // 合并命令
    const mergedCommand = strategy.merge(lastCommand, command);
    this.events?.onCommandMerged?.(command, mergedCommand);

    return mergedCommand;
  }

  /**
   * 初始化命令合并策略
   */
  private initializeMergeStrategies(): void {
    // 移动命令合并策略
    this.addMergeStrategy('move', {
      canMerge: (cmd1, cmd2) => {
        return (
          cmd1.type === 'move' &&
          cmd2.type === 'move' &&
          cmd1.metadata?.componentId === cmd2.metadata?.componentId
        );
      },
      merge: (cmd1, cmd2) => ({
        ...cmd2,
        description: `Move component from ${cmd1.metadata?.originalPosition} to ${cmd2.metadata?.newPosition}`,
        metadata: {
          ...cmd2.metadata,
          originalPosition: cmd1.metadata?.originalPosition,
        },
      }),
    });

    // 调整大小命令合并策略
    this.addMergeStrategy('resize', {
      canMerge: (cmd1, cmd2) => {
        return (
          cmd1.type === 'resize' &&
          cmd2.type === 'resize' &&
          cmd1.metadata?.componentId === cmd2.metadata?.componentId
        );
      },
      merge: (cmd1, cmd2) => ({
        ...cmd2,
        description: `Resize component from ${cmd1.metadata?.originalSize} to ${cmd2.metadata?.newSize}`,
        metadata: {
          ...cmd2.metadata,
          originalSize: cmd1.metadata?.originalSize,
        },
      }),
    });

    // 属性更新命令合并策略
    this.addMergeStrategy('updateProps', {
      canMerge: (cmd1, cmd2) => {
        return (
          cmd1.type === 'updateProps' &&
          cmd2.type === 'updateProps' &&
          cmd1.metadata?.componentId === cmd2.metadata?.componentId
        );
      },
      merge: (cmd1, cmd2) => ({
        ...cmd2,
        description: `Update component properties`,
        metadata: {
          ...cmd2.metadata,
          originalProps: cmd1.metadata?.originalProps,
        },
      }),
    });
  }

  /**
   * 更新执行统计
   */
  private updateExecutionStats(startTime: number): void {
    const duration = performance.now() - startTime;
    this.performanceStats.totalExecutions++;
    this.performanceStats.totalExecutionTime += duration;

    // 性能警告
    const threshold = 100; // 100ms
    if (duration > threshold) {
      this.events?.onPerformanceWarning?.('execute', duration, threshold);
    }
  }

  /**
   * 更新撤销统计
   */
  private updateUndoStats(startTime: number): void {
    const duration = performance.now() - startTime;
    this.performanceStats.totalUndos++;
    this.performanceStats.totalUndoTime += duration;

    // 性能警告
    const threshold = 50; // 50ms
    if (duration > threshold) {
      this.events?.onPerformanceWarning?.('undo', duration, threshold);
    }
  }

  /**
   * 更新重做统计
   */
  private updateRedoStats(startTime: number): void {
    const duration = performance.now() - startTime;
    this.performanceStats.totalRedos++;
    this.performanceStats.totalRedoTime += duration;

    // 性能警告
    const threshold = 50; // 50ms
    if (duration > threshold) {
      this.events?.onPerformanceWarning?.('redo', duration, threshold);
    }
  }

  /**
   * 重置性能统计
   */
  private resetPerformanceStats(): void {
    this.performanceStats = {
      totalExecutions: 0,
      totalUndos: 0,
      totalRedos: 0,
      totalExecutionTime: 0,
      totalUndoTime: 0,
      totalRedoTime: 0,
    };
  }

  /**
   * 通知状态变化
   */
  private notifyStateChange(): void {
    this.events?.onStateChange?.(this.getState());
  }
}

/**
 * 创建撤销重做管理器
 */
export function createUndoRedoManager(
  config?: Partial<UndoRedoManagerConfig>,
  events?: Partial<UndoRedoManagerEvents>,
): UndoRedoManager {
  return new UndoRedoManager(config, events);
}

/**
 * 撤销重做管理器预设配置
 */
export const UndoRedoManagerPresets = {
  /** 默认配置 */
  default: {
    historyStack: { maxSize: 100, enableCompression: true },
    enableCommandMerging: true,
    commandMergeWindow: 1000,
    enableCommandValidation: true,
    enablePerformanceMonitoring: true,
  } as UndoRedoManagerConfig,

  /** 高性能配置 */
  performance: {
    historyStack: { maxSize: 50, enableCompression: true },
    enableCommandMerging: true,
    commandMergeWindow: 500,
    enableCommandValidation: false,
    enablePerformanceMonitoring: false,
  } as UndoRedoManagerConfig,

  /** 调试配置 */
  debug: {
    historyStack: { maxSize: 200, enableCompression: false },
    enableCommandMerging: false,
    commandMergeWindow: 0,
    enableCommandValidation: true,
    enablePerformanceMonitoring: true,
  } as UndoRedoManagerConfig,
};
