/**
 * 历史栈管理系统
 * 提供命令历史记录、撤销重做功能
 */

import type {
  Command,
  CommandResult,
  CommandEvents,
} from '~/studio/services/actions/command/Command';
import { CommandExecutor } from '~/studio/services/actions/command/Command';

/**
 * 历史栈配置
 */
export interface HistoryStackConfig {
  /** 最大历史记录数量 */
  maxSize: number;
  /** 是否启用自动保存 */
  autoSave: boolean;
  /** 自动保存间隔（毫秒） */
  autoSaveInterval: number;
  /** 是否启用压缩 */
  enableCompression: boolean;
  /** 压缩阈值 */
  compressionThreshold: number;
}

/**
 * 历史栈状态
 */
export interface HistoryStackState {
  /** 当前位置 */
  currentIndex: number;
  /** 历史记录总数 */
  totalCount: number;
  /** 是否可以撤销 */
  canUndo: boolean;
  /** 是否可以重做 */
  canRedo: boolean;
  /** 是否正在执行操作 */
  isExecuting: boolean;
  /** 最后执行的命令 */
  lastCommand?: Command;
  /** 最后执行结果 */
  lastResult?: CommandResult;
}

/**
 * 历史栈事件
 */
export interface HistoryStackEvents extends CommandEvents {
  /** 历史栈状态变化 */
  onStateChange: (state: HistoryStackState) => void;
  /** 命令添加到历史栈 */
  onCommandAdded: (command: Command, index: number) => void;
  /** 命令从历史栈移除 */
  onCommandRemoved: (command: Command, index: number) => void;
  /** 历史栈清空 */
  onStackCleared: () => void;
  /** 历史栈压缩 */
  onStackCompressed: (oldSize: number, newSize: number) => void;
  /** 自动保存 */
  onAutoSave: (commands: Command[]) => void;
}

/**
 * 历史记录项
 */
interface HistoryItem {
  /** 命令 */
  command: Command;
  /** 执行结果 */
  result?: CommandResult;
  /** 执行时间 */
  executedAt: number;
  /** 是否已压缩 */
  compressed: boolean;
}

/**
 * 历史栈管理器
 */
export class HistoryStack {
  private config: HistoryStackConfig;
  private history: HistoryItem[] = [];
  private currentIndex: number = -1;
  private executor: CommandExecutor;
  private events?: Partial<HistoryStackEvents>;
  private isExecuting: boolean = false;
  private autoSaveTimer?: NodeJS.Timeout;

  constructor(config: Partial<HistoryStackConfig> = {}, events?: Partial<HistoryStackEvents>) {
    this.config = {
      maxSize: 100,
      autoSave: false,
      autoSaveInterval: 30000, // 30秒
      enableCompression: true,
      compressionThreshold: 50,
      ...config,
    };

    this.events = events;
    this.executor = new CommandExecutor(events);

    if (this.config.autoSave) {
      this.startAutoSave();
    }
  }

  /**
   * 执行命令并添加到历史栈
   */
  async execute(command: Command): Promise<CommandResult> {
    if (this.isExecuting) {
      throw new Error('Another command is currently executing');
    }

    this.isExecuting = true;
    this.notifyStateChange();

    try {
      // 执行命令
      const result = await this.executor.execute(command);

      if (result.success) {
        // 清除当前位置之后的历史记录
        this.clearRedoHistory();

        // 添加到历史栈
        this.addToHistory(command, result);

        // 检查是否需要压缩
        this.checkCompression();

        this.events?.onCommandAdded?.(command, this.currentIndex);
      }

      return result;
    } finally {
      this.isExecuting = false;
      this.notifyStateChange();
    }
  }

  /**
   * 撤销操作
   */
  async undo(): Promise<CommandResult | null> {
    if (!this.canUndo() || this.isExecuting) {
      return null;
    }

    this.isExecuting = true;
    this.notifyStateChange();

    try {
      const historyItem = this.history[this.currentIndex];
      const result = await this.executor.undo(historyItem.command);

      if (result.success) {
        this.currentIndex--;
        historyItem.result = result;
      }

      return result;
    } finally {
      this.isExecuting = false;
      this.notifyStateChange();
    }
  }

  /**
   * 重做操作
   */
  async redo(): Promise<CommandResult | null> {
    if (!this.canRedo() || this.isExecuting) {
      return null;
    }

    this.isExecuting = true;
    this.notifyStateChange();

    try {
      const historyItem = this.history[this.currentIndex + 1];
      const result = await this.executor.redo(historyItem.command);

      if (result.success) {
        this.currentIndex++;
        historyItem.result = result;
      }

      return result;
    } finally {
      this.isExecuting = false;
      this.notifyStateChange();
    }
  }

  /**
   * 批量撤销
   */
  async undoMultiple(count: number): Promise<CommandResult[]> {
    const results: CommandResult[] = [];

    for (let i = 0; i < count && this.canUndo(); i++) {
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

    for (let i = 0; i < count && this.canRedo(); i++) {
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
    if (targetIndex < -1 || targetIndex >= this.currentIndex) {
      throw new Error('Invalid target index');
    }

    const count = this.currentIndex - targetIndex;
    return this.undoMultiple(count);
  }

  /**
   * 重做到指定位置
   */
  async redoTo(targetIndex: number): Promise<CommandResult[]> {
    if (targetIndex <= this.currentIndex || targetIndex >= this.history.length) {
      throw new Error('Invalid target index');
    }

    const count = targetIndex - this.currentIndex;
    return this.redoMultiple(count);
  }

  /**
   * 检查是否可以撤销
   */
  canUndo(): boolean {
    return this.currentIndex >= 0 && !this.isExecuting;
  }

  /**
   * 检查是否可以重做
   */
  canRedo(): boolean {
    return this.currentIndex < this.history.length - 1 && !this.isExecuting;
  }

  /**
   * 获取历史栈状态
   */
  getState(): HistoryStackState {
    return {
      currentIndex: this.currentIndex,
      totalCount: this.history.length,
      canUndo: this.canUndo(),
      canRedo: this.canRedo(),
      isExecuting: this.isExecuting,
      lastCommand: this.history[this.currentIndex]?.command,
      lastResult: this.history[this.currentIndex]?.result,
    };
  }

  /**
   * 获取历史记录
   */
  getHistory(): readonly Command[] {
    return this.history.map((item) => item.command);
  }

  /**
   * 获取指定范围的历史记录
   */
  getHistoryRange(start: number, end: number): readonly Command[] {
    return this.history.slice(start, end).map((item) => item.command);
  }

  /**
   * 获取撤销历史
   */
  getUndoHistory(): readonly Command[] {
    return this.history.slice(0, this.currentIndex + 1).map((item) => item.command);
  }

  /**
   * 获取重做历史
   */
  getRedoHistory(): readonly Command[] {
    return this.history.slice(this.currentIndex + 1).map((item) => item.command);
  }

  /**
   * 清空历史栈
   */
  clear(): void {
    this.history = [];
    this.currentIndex = -1;
    this.events?.onStackCleared?.();
    this.notifyStateChange();
  }

  /**
   * 清除重做历史
   */
  private clearRedoHistory(): void {
    if (this.currentIndex < this.history.length - 1) {
      const removedItems = this.history.splice(this.currentIndex + 1);
      removedItems.forEach((item, index) => {
        this.events?.onCommandRemoved?.(item.command, this.currentIndex + 1 + index);
      });
    }
  }

  /**
   * 添加到历史栈
   */
  private addToHistory(command: Command, result: CommandResult): void {
    const historyItem: HistoryItem = {
      command,
      result,
      executedAt: Date.now(),
      compressed: false,
    };

    this.history.push(historyItem);
    this.currentIndex++;

    // 检查是否超过最大大小
    if (this.history.length > this.config.maxSize) {
      const removedItem = this.history.shift()!;
      this.currentIndex--;
      this.events?.onCommandRemoved?.(removedItem.command, 0);
    }
  }

  /**
   * 检查是否需要压缩
   */
  private checkCompression(): void {
    if (!this.config.enableCompression) {
      return;
    }

    if (this.history.length >= this.config.compressionThreshold) {
      this.compressHistory();
    }
  }

  /**
   * 压缩历史记录
   */
  private compressHistory(): void {
    const oldSize = this.history.length;
    const compressCount = Math.floor(oldSize / 2);

    // 压缩前半部分历史记录
    for (let i = 0; i < compressCount; i++) {
      if (!this.history[i].compressed) {
        this.history[i].compressed = true;
        // 这里可以实现具体的压缩逻辑，比如序列化命令数据
      }
    }

    this.events?.onStackCompressed?.(oldSize, this.history.length);
  }

  /**
   * 开始自动保存
   */
  private startAutoSave(): void {
    if (this.autoSaveTimer) {
      clearInterval(this.autoSaveTimer);
    }

    this.autoSaveTimer = setInterval(() => {
      this.performAutoSave();
    }, this.config.autoSaveInterval);
  }

  /**
   * 停止自动保存
   */
  private stopAutoSave(): void {
    if (this.autoSaveTimer) {
      clearInterval(this.autoSaveTimer);
      this.autoSaveTimer = undefined;
    }
  }

  /**
   * 执行自动保存
   */
  private performAutoSave(): void {
    const commands = this.history.map((item) => item.command);
    this.events?.onAutoSave?.(commands);
  }

  /**
   * 通知状态变化
   */
  private notifyStateChange(): void {
    this.events?.onStateChange?.(this.getState());
  }

  /**
   * 销毁历史栈
   */
  destroy(): void {
    this.stopAutoSave();
    this.clear();
  }

  /**
   * 导出历史记录
   */
  export(): string {
    const exportData = {
      config: this.config,
      history: this.history.map((item) => ({
        command: {
          id: item.command.id,
          type: item.command.type,
          description: item.command.description,
          timestamp: item.command.timestamp,
          metadata: item.command.metadata,
        },
        result: item.result,
        executedAt: item.executedAt,
        compressed: item.compressed,
      })),
      currentIndex: this.currentIndex,
    };

    return JSON.stringify(exportData, null, 2);
  }

  /**
   * 导入历史记录
   */
  import(data: string): void {
    try {
      const importData = JSON.parse(data);

      // 验证数据格式
      if (!importData.history || !Array.isArray(importData.history)) {
        throw new Error('Invalid import data format');
      }

      // 清空当前历史
      this.clear();

      // 导入配置
      if (importData.config) {
        this.config = { ...this.config, ...importData.config };
      }

      // 导入历史记录（注意：这里只导入元数据，不包含实际的命令实现）
      this.currentIndex = importData.currentIndex || -1;

      this.notifyStateChange();
    } catch (error) {
      throw new Error(
        `Failed to import history: ${error instanceof Error ? error.message : String(error)}`,
      );
    }
  }
}

/**
 * 创建历史栈实例
 */
export function createHistoryStack(
  config?: Partial<HistoryStackConfig>,
  events?: Partial<HistoryStackEvents>,
): HistoryStack {
  return new HistoryStack(config, events);
}

/**
 * 历史栈预设配置
 */
export const HistoryStackPresets = {
  /** 默认配置 */
  default: {
    maxSize: 100,
    autoSave: false,
    autoSaveInterval: 30000,
    enableCompression: true,
    compressionThreshold: 50,
  } as HistoryStackConfig,

  /** 高性能配置 */
  performance: {
    maxSize: 50,
    autoSave: false,
    autoSaveInterval: 60000,
    enableCompression: true,
    compressionThreshold: 25,
  } as HistoryStackConfig,

  /** 大容量配置 */
  large: {
    maxSize: 500,
    autoSave: true,
    autoSaveInterval: 15000,
    enableCompression: true,
    compressionThreshold: 100,
  } as HistoryStackConfig,

  /** 简单配置 */
  simple: {
    maxSize: 20,
    autoSave: false,
    autoSaveInterval: 0,
    enableCompression: false,
    compressionThreshold: 0,
  } as HistoryStackConfig,
};
