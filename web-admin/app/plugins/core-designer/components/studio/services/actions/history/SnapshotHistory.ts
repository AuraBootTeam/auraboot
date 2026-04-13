import type { Command } from '~/plugins/core-designer/components/studio/services/actions/command/Command';
import type { PageSchema } from '~/plugins/core-designer/components/studio/domain/schema/types';

/**
 * 历史状态快照
 */
export interface HistorySnapshot {
  /** 快照ID */
  id: string;
  /** 快照时间戳 */
  timestamp: number;
  /** 页面Schema状态 */
  pageSchema: PageSchema;
  /** 关联的命令 */
  command?: Command;
  /** 快照描述 */
  description: string;
}

/**
 * 历史栈配置
 */
export interface HistoryConfig {
  /** 最大历史记录数量 */
  maxSize: number;
  /** 是否启用自动快照 */
  autoSnapshot: boolean;
  /** 自动快照间隔（毫秒） */
  snapshotInterval: number;
  /** 是否启用压缩 */
  enableCompression: boolean;
}

/**
 * 历史栈管理器
 */
export class HistoryStack {
  private static instance: HistoryStack;
  private undoStack: HistorySnapshot[] = [];
  private redoStack: HistorySnapshot[] = [];
  private currentSnapshot: HistorySnapshot | null = null;
  private config: HistoryConfig;
  private autoSnapshotTimer: NodeJS.Timeout | null = null;

  private constructor(config: Partial<HistoryConfig> = {}) {
    this.config = {
      maxSize: 50,
      autoSnapshot: false,
      snapshotInterval: 30000, // 30秒
      enableCompression: false,
      ...config,
    };

    if (this.config.autoSnapshot) {
      this.startAutoSnapshot();
    }
  }

  static getInstance(config?: Partial<HistoryConfig>): HistoryStack {
    if (!HistoryStack.instance) {
      HistoryStack.instance = new HistoryStack(config);
    }
    return HistoryStack.instance;
  }

  /**
   * 创建快照
   */
  createSnapshot(
    pageSchema: PageSchema,
    command?: Command,
    description: string = '状态快照',
  ): HistorySnapshot {
    const snapshot: HistorySnapshot = {
      id: `snapshot_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`,
      timestamp: Date.now(),
      pageSchema: this.deepClone(pageSchema),
      command,
      description,
    };

    return snapshot;
  }

  /**
   * 推送快照到撤销栈
   */
  push(snapshot: HistorySnapshot): void {
    // 清空重做栈
    this.redoStack = [];

    // 添加到撤销栈
    this.undoStack.push(snapshot);

    // 限制栈大小
    if (this.undoStack.length > this.config.maxSize) {
      this.undoStack.shift();
    }

    this.currentSnapshot = snapshot;
  }

  /**
   * 推送命令到历史栈
   */
  pushCommand(command: Command, pageSchema: PageSchema, description?: string): void {
    const snapshot = this.createSnapshot(pageSchema, command, description || command.description);
    this.push(snapshot);
  }

  /**
   * 撤销操作
   */
  undo(): HistorySnapshot | null {
    if (this.undoStack.length === 0) {
      return null;
    }

    const snapshot = this.undoStack.pop()!;

    // 如果有关联命令，执行撤销
    if (snapshot.command && snapshot.command.canUndo()) {
      try {
        snapshot.command.undo();
      } catch (error) {
        console.error('撤销命令失败:', error);
        // 撤销失败，重新放回栈中
        this.undoStack.push(snapshot);
        return null;
      }
    }

    // 移动到重做栈
    this.redoStack.push(snapshot);

    // 更新当前快照
    this.currentSnapshot =
      this.undoStack.length > 0 ? this.undoStack[this.undoStack.length - 1] : null;

    return snapshot;
  }

  /**
   * 重做操作
   */
  redo(): HistorySnapshot | null {
    if (this.redoStack.length === 0) {
      return null;
    }

    const snapshot = this.redoStack.pop()!;

    // 如果有关联命令，执行重做
    if (snapshot.command && snapshot.command.canRedo()) {
      try {
        snapshot.command.redo();
      } catch (error) {
        console.error('重做命令失败:', error);
        // 重做失败，重新放回栈中
        this.redoStack.push(snapshot);
        return null;
      }
    }

    // 移动到撤销栈
    this.undoStack.push(snapshot);
    this.currentSnapshot = snapshot;

    return snapshot;
  }

  /**
   * 检查是否可以撤销
   */
  canUndo(): boolean {
    return this.undoStack.length > 0;
  }

  /**
   * 检查是否可以重做
   */
  canRedo(): boolean {
    return this.redoStack.length > 0;
  }

  /**
   * 获取撤销栈大小
   */
  getUndoStackSize(): number {
    return this.undoStack.length;
  }

  /**
   * 获取重做栈大小
   */
  getRedoStackSize(): number {
    return this.redoStack.length;
  }

  /**
   * 获取当前快照
   */
  getCurrentSnapshot(): HistorySnapshot | null {
    return this.currentSnapshot;
  }

  /**
   * 获取撤销栈历史
   */
  getUndoHistory(): HistorySnapshot[] {
    return [...this.undoStack];
  }

  /**
   * 获取重做栈历史
   */
  getRedoHistory(): HistorySnapshot[] {
    return [...this.redoStack];
  }

  /**
   * 清空历史栈
   */
  clear(): void {
    this.undoStack = [];
    this.redoStack = [];
    this.currentSnapshot = null;
  }

  /**
   * 获取历史统计信息
   */
  getStats(): {
    undoCount: number;
    redoCount: number;
    totalSize: number;
    currentSnapshotId: string | null;
  } {
    return {
      undoCount: this.undoStack.length,
      redoCount: this.redoStack.length,
      totalSize: this.undoStack.length + this.redoStack.length,
      currentSnapshotId: this.currentSnapshot?.id || null,
    };
  }

  /**
   * 启动自动快照
   */
  private startAutoSnapshot(): void {
    if (this.autoSnapshotTimer) {
      clearInterval(this.autoSnapshotTimer);
    }

    this.autoSnapshotTimer = setInterval(() => {
      // TODO: Inject page state getter to enable auto snapshots
    }, this.config.snapshotInterval);
  }

  /**
   * 停止自动快照
   */
  private stopAutoSnapshot(): void {
    if (this.autoSnapshotTimer) {
      clearInterval(this.autoSnapshotTimer);
      this.autoSnapshotTimer = null;
    }
  }

  /**
   * 深度克隆对象
   */
  private deepClone<T>(obj: T): T {
    if (obj === null || typeof obj !== 'object') {
      return obj;
    }

    if (obj instanceof Date) {
      return new Date(obj.getTime()) as unknown as T;
    }

    if (obj instanceof Array) {
      return obj.map((item) => this.deepClone(item)) as unknown as T;
    }

    if (typeof obj === 'object') {
      const cloned = {} as T;
      for (const key in obj) {
        if (obj.hasOwnProperty(key)) {
          cloned[key] = this.deepClone(obj[key]);
        }
      }
      return cloned;
    }

    return obj;
  }

  /**
   * 销毁历史栈
   */
  destroy(): void {
    this.stopAutoSnapshot();
    this.clear();
    HistoryStack.instance = null as any;
  }
}

/**
 * 历史管理器 - 整合命令模式和历史栈
 */
export class HistoryManager {
  private static instance: HistoryManager;
  private historyStack: HistoryStack;
  private pageSchemaGetter: (() => PageSchema) | null = null;
  private pageSchemaUpdater: ((schema: PageSchema) => void) | null = null;

  private constructor() {
    this.historyStack = HistoryStack.getInstance();
  }

  static getInstance(): HistoryManager {
    if (!HistoryManager.instance) {
      HistoryManager.instance = new HistoryManager();
    }
    return HistoryManager.instance;
  }

  /**
   * 设置页面Schema获取器
   */
  setPageSchemaGetter(getter: () => PageSchema): void {
    this.pageSchemaGetter = getter;
  }

  /**
   * 设置页面Schema更新器
   */
  setPageSchemaUpdater(updater: (schema: PageSchema) => void): void {
    this.pageSchemaUpdater = updater;
  }

  /**
   * 执行命令并记录历史
   */
  executeCommand(command: Command): void {
    if (!this.pageSchemaGetter) {
      throw new Error('页面Schema获取器未设置');
    }

    // 获取执行前的状态
    const beforeSchema = this.pageSchemaGetter();

    // 创建快照
    const snapshot = this.historyStack.createSnapshot(beforeSchema, command, command.description);

    try {
      // 执行命令
      command.execute();

      // 推送到历史栈
      this.historyStack.push(snapshot);
    } catch (error) {
      console.error('命令执行失败:', error);
      throw error;
    }
  }

  /**
   * 撤销操作
   */
  undo(): boolean {
    if (!this.pageSchemaUpdater) {
      throw new Error('页面Schema更新器未设置');
    }

    const snapshot = this.historyStack.undo();
    if (snapshot) {
      // 恢复页面状态
      this.pageSchemaUpdater(snapshot.pageSchema);
      return true;
    }
    return false;
  }

  /**
   * 重做操作
   */
  redo(): boolean {
    if (!this.pageSchemaUpdater || !this.pageSchemaGetter) {
      throw new Error('页面Schema获取器或更新器未设置');
    }

    const snapshot = this.historyStack.redo();
    if (snapshot) {
      // 获取重做后的状态
      const currentSchema = this.pageSchemaGetter();
      this.pageSchemaUpdater(currentSchema);
      return true;
    }
    return false;
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
   * 获取历史统计
   */
  getStats() {
    return this.historyStack.getStats();
  }

  /**
   * 清空历史
   */
  clear(): void {
    this.historyStack.clear();
  }
}
