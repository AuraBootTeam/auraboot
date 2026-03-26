/**
 * Command Manager
 *
 * 管理页面设计器的命令系统
 */

import type { Command } from '~/studio/services/actions/command/Command';

export interface CommandHistory {
  commands: Command[];
  currentIndex: number;
  maxSize: number;
}

export interface CommandEvent {
  type: string;
  command: Command;
  timestamp: number;
}

export enum CommandEventType {
  HISTORY_CHANGED = 'history_changed',
  BATCH_STARTED = 'batch_started',
  BATCH_ENDED = 'batch_ended',
  COMMAND_EXECUTED = 'command_executed',
  COMMAND_UNDONE = 'command_undone',
  COMMAND_REDONE = 'command_redone',
}

/**
 * 命令管理器接口
 */
export interface CommandManager {
  // 命令执行
  execute(command: Command): Promise<void>;
  executeCommand(command: Command): Promise<void>;
  startBatch(batchId: string): void;
  endBatch(): void;
  undo(): Promise<void>;
  redo(): Promise<void>;

  // 命令查询
  canUndo(): boolean;
  canRedo(): boolean;
  getHistory(): CommandHistory;

  // 命令管理
  clear(): void;
  setMaxSize(size: number): void;

  // 事件监听
  on(event: string, listener: (event: CommandEvent) => void): void;
  off(event: string, listener: (event: CommandEvent) => void): void;

  // 初始化
  initialize(): Promise<void>;
}

/**
 * 命令管理器实现
 */
class CommandManagerImpl implements CommandManager {
  private history: CommandHistory;
  private listeners: Map<string, ((event: CommandEvent) => void)[]> = new Map();
  private batchId: string | null = null;
  private batchCommands: Command[] = [];

  constructor() {
    this.history = {
      commands: [],
      currentIndex: -1,
      maxSize: 50,
    };
  }

  async initialize(): Promise<void> {
    // Initialized
  }

  async execute(command: Command): Promise<void> {
    try {
      if (this.batchId) {
        this.batchCommands.push(command);
        return;
      }
      // ... existing execute logic ...
      // 检查是否可以执行
      if (command.canExecute && !command.canExecute()) {
        throw new Error(`Command cannot be executed: ${command.name}`);
      }

      // 执行命令
      await command.execute();

      // 添加到历史记录
      this.addToHistory(command);

      // 触发事件
      this.emit(CommandEventType.COMMAND_EXECUTED, {
        type: CommandEventType.COMMAND_EXECUTED,
        command,
        timestamp: Date.now(),
      });
    } catch (error) {
      console.error(`Failed to execute command: ${command.name}`, error);
      throw error;
    }
  }

  async executeCommand(command: Command): Promise<void> {
    return this.execute(command);
  }

  startBatch(batchId: string): void {
    this.batchId = batchId;
    this.batchCommands = [];
    this.emit(CommandEventType.BATCH_STARTED, {
      type: CommandEventType.BATCH_STARTED,
      command: {
        id: batchId,
        name: 'Batch',
        execute: async () => {},
        undo: async () => {},
      } as Command,
      timestamp: Date.now(),
    });
  }

  endBatch(): void {
    this.batchId = null;
    // Here we should ideally create a CompositeCommand from batchCommands and add to history
    // For now, just clearing
    this.batchCommands = [];
    this.emit(CommandEventType.BATCH_ENDED, {
      type: CommandEventType.BATCH_ENDED,
      command: {
        id: 'batch_end',
        name: 'Batch End',
        execute: async () => {},
        undo: async () => {},
      } as Command,
      timestamp: Date.now(),
    });
  }

  async undo(): Promise<void> {
    if (!this.canUndo()) {
      throw new Error('Cannot undo: no commands in history');
    }

    const command = this.history.commands[this.history.currentIndex];

    try {
      // 检查是否可以撤销
      if (command.canUndo && !command.canUndo()) {
        throw new Error(`Command cannot be undone: ${command.name}`);
      }

      // 执行撤销
      if (command.undo) {
        await command.undo();
      }

      // 更新历史索引
      this.history.currentIndex--;

      // 触发事件
      this.emit(CommandEventType.COMMAND_UNDONE, {
        type: CommandEventType.COMMAND_UNDONE,
        command,
        timestamp: Date.now(),
      });
    } catch (error) {
      console.error(`Failed to undo command: ${command.name}`, error);
      throw error;
    }
  }

  async redo(): Promise<void> {
    if (!this.canRedo()) {
      throw new Error('Cannot redo: no commands to redo');
    }

    const command = this.history.commands[this.history.currentIndex + 1];

    try {
      // 检查是否可以执行
      if (command.canExecute && !command.canExecute()) {
        throw new Error(`Command cannot be executed: ${command.name}`);
      }

      // 重新执行命令
      await command.execute();

      // 更新历史索引
      this.history.currentIndex++;

      // 触发事件
      this.emit(CommandEventType.COMMAND_REDONE, {
        type: CommandEventType.COMMAND_REDONE,
        command,
        timestamp: Date.now(),
      });
    } catch (error) {
      console.error(`Failed to redo command: ${command.name}`, error);
      throw error;
    }
  }

  canUndo(): boolean {
    return this.history.currentIndex >= 0;
  }

  canRedo(): boolean {
    return this.history.currentIndex < this.history.commands.length - 1;
  }

  getHistory(): CommandHistory {
    return { ...this.history };
  }

  clear(): void {
    this.history.commands = [];
    this.history.currentIndex = -1;
  }

  setMaxSize(size: number): void {
    this.history.maxSize = size;

    // 如果当前历史超过最大大小，则截断
    if (this.history.commands.length > size) {
      const removeCount = this.history.commands.length - size;
      this.history.commands.splice(0, removeCount);
      this.history.currentIndex = Math.max(-1, this.history.currentIndex - removeCount);
    }
  }

  on(event: string, listener: (event: CommandEvent) => void): void {
    if (!this.listeners.has(event)) {
      this.listeners.set(event, []);
    }
    this.listeners.get(event)!.push(listener);
  }

  off(event: string, listener: (event: CommandEvent) => void): void {
    const listeners = this.listeners.get(event);
    if (listeners) {
      const index = listeners.indexOf(listener);
      if (index >= 0) {
        listeners.splice(index, 1);
      }
    }
  }

  private addToHistory(command: Command): void {
    // 如果当前不在历史末尾，则删除后续命令
    if (this.history.currentIndex < this.history.commands.length - 1) {
      this.history.commands.splice(this.history.currentIndex + 1);
    }

    // 添加新命令
    this.history.commands.push(command);
    this.history.currentIndex++;

    // 如果超过最大大小，则删除最旧的命令
    if (this.history.commands.length > this.history.maxSize) {
      this.history.commands.shift();
      this.history.currentIndex--;
    }
  }

  private emit(event: string, eventData: CommandEvent): void {
    const listeners = this.listeners.get(event);
    if (listeners) {
      listeners.forEach((listener) => {
        try {
          listener(eventData);
        } catch (error) {
          console.error(`Error in command event listener:`, error);
        }
      });
    }
  }
}

// 全局命令管理器实例
let globalCommandManager: CommandManager | null = null;

/**
 * 获取全局命令管理器实例
 */
export function getCommandManager(): CommandManager {
  if (!globalCommandManager) {
    globalCommandManager = new CommandManagerImpl();
  }
  return globalCommandManager;
}

/**
 * 创建新的命令管理器实例
 */
export function createCommandManager(): CommandManager {
  return new CommandManagerImpl();
}

export default getCommandManager;
