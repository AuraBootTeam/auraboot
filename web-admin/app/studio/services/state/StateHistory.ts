/**
 * 状态历史管理器
 *
 * 实现状态变更的历史记录、撤销/重做功能
 */

import type { PageState, StateChangeEvent } from '~/studio/services/state/PageStateManager';

/**
 * 历史记录项
 */
export interface HistoryEntry {
  id: string;
  timestamp: number;
  description: string;
  state: PageState;
  changes: StateChangeEvent[];
  canUndo: boolean;
  canRedo: boolean;
}

/**
 * 历史操作类型
 */
export enum HistoryActionType {
  COMPONENT_ADD = 'component_add',
  COMPONENT_REMOVE = 'component_remove',
  COMPONENT_UPDATE = 'component_update',
  COMPONENT_MOVE = 'component_move',
  COMPONENT_COPY = 'component_copy',
  COMPONENT_PASTE = 'component_paste',
  PROPERTY_CHANGE = 'property_change',
  STYLE_CHANGE = 'style_change',
  LAYOUT_CHANGE = 'layout_change',
  STATE_CHANGE = 'state_change',
}

/**
 * 历史操作
 */
export interface HistoryAction {
  type: HistoryActionType;
  description: string;
  execute: () => Promise<void>;
  undo: () => Promise<void>;
  canMerge?: (other: HistoryAction) => boolean;
  merge?: (other: HistoryAction) => HistoryAction;
}

/**
 * 状态历史管理器
 */
export class StateHistoryManager {
  private history: HistoryEntry[] = [];
  private currentIndex = -1;
  private maxHistorySize = 50;
  private isRecording = true;
  private pendingActions: HistoryAction[] = [];
  private mergeTimeout: NodeJS.Timeout | null = null;

  constructor(maxHistorySize = 50) {
    this.maxHistorySize = maxHistorySize;
  }

  /**
   * 记录状态快照
   */
  recordSnapshot(state: PageState, description: string, changes: StateChangeEvent[] = []): void {
    if (!this.isRecording) return;

    const entry: HistoryEntry = {
      id: this.generateId(),
      timestamp: Date.now(),
      description,
      state: this.deepClone(state),
      changes: [...changes],
      canUndo: true,
      canRedo: false,
    };

    // 移除当前位置之后的历史记录
    if (this.currentIndex < this.history.length - 1) {
      this.history = this.history.slice(0, this.currentIndex + 1);
    }

    // 添加新记录
    this.history.push(entry);
    this.currentIndex = this.history.length - 1;

    // 限制历史记录大小
    if (this.history.length > this.maxHistorySize) {
      this.history = this.history.slice(-this.maxHistorySize);
      this.currentIndex = this.history.length - 1;
    }

    this.updateUndoRedoFlags();
  }

  /**
   * 记录动作
   */
  recordAction(action: HistoryAction): void {
    if (!this.isRecording) return;

    // 尝试合并相似动作
    if (this.canMergeWithPending(action)) {
      this.mergePendingAction(action);
      return;
    }

    // 执行动作
    this.executePendingActions();
    this.pendingActions = [action];

    // 设置合并超时
    if (this.mergeTimeout) {
      clearTimeout(this.mergeTimeout);
    }

    this.mergeTimeout = setTimeout(() => {
      this.executePendingActions();
    }, 500); // 500ms 内的相似动作会被合并
  }

  /**
   * 撤销操作
   */
  async undo(): Promise<HistoryEntry | null> {
    if (!this.canUndo()) return null;

    const currentEntry = this.history[this.currentIndex];
    this.currentIndex--;

    // 执行撤销
    if (this.currentIndex >= 0) {
      const targetEntry = this.history[this.currentIndex];
      this.updateUndoRedoFlags();
      return targetEntry;
    }

    this.updateUndoRedoFlags();
    return null;
  }

  /**
   * 重做操作
   */
  async redo(): Promise<HistoryEntry | null> {
    if (!this.canRedo()) return null;

    this.currentIndex++;
    const targetEntry = this.history[this.currentIndex];
    this.updateUndoRedoFlags();
    return targetEntry;
  }

  /**
   * 检查是否可以撤销
   */
  canUndo(): boolean {
    return this.currentIndex > 0;
  }

  /**
   * 检查是否可以重做
   */
  canRedo(): boolean {
    return this.currentIndex < this.history.length - 1;
  }

  /**
   * 获取历史记录
   */
  getHistory(): HistoryEntry[] {
    return [...this.history];
  }

  /**
   * 获取当前历史记录
   */
  getCurrentEntry(): HistoryEntry | null {
    if (this.currentIndex >= 0 && this.currentIndex < this.history.length) {
      return this.history[this.currentIndex];
    }
    return null;
  }

  /**
   * 清空历史记录
   */
  clear(): void {
    this.history = [];
    this.currentIndex = -1;
    this.pendingActions = [];
    if (this.mergeTimeout) {
      clearTimeout(this.mergeTimeout);
      this.mergeTimeout = null;
    }
  }

  /**
   * 开始记录
   */
  startRecording(): void {
    this.isRecording = true;
  }

  /**
   * 停止记录
   */
  stopRecording(): void {
    this.isRecording = false;
    this.executePendingActions();
  }

  /**
   * 创建组件添加动作
   */
  createComponentAddAction(
    componentId: string,
    componentData: any,
    parentId?: string,
  ): HistoryAction {
    return {
      type: HistoryActionType.COMPONENT_ADD,
      description: `Add component ${componentData.type}`,
      execute: async () => {
        // 实现组件添加逻辑
      },
      undo: async () => {
        // 实现组件删除逻辑
      },
    };
  }

  /**
   * 创建组件删除动作
   */
  createComponentRemoveAction(componentId: string, componentData: any): HistoryAction {
    return {
      type: HistoryActionType.COMPONENT_REMOVE,
      description: `Remove component ${componentData.type}`,
      execute: async () => {
        // 实现组件删除逻辑
      },
      undo: async () => {
        // 实现组件恢复逻辑
      },
    };
  }

  /**
   * 创建属性变更动作
   */
  createPropertyChangeAction(
    componentId: string,
    propertyPath: string,
    oldValue: any,
    newValue: any,
  ): HistoryAction {
    return {
      type: HistoryActionType.PROPERTY_CHANGE,
      description: `Change ${propertyPath}`,
      execute: async () => {
        // 实现属性设置逻辑
      },
      undo: async () => {
        // 实现属性恢复逻辑
      },
      canMerge: (other: HistoryAction) => {
        return (
          other.type === HistoryActionType.PROPERTY_CHANGE &&
          (other as any).componentId === componentId &&
          (other as any).propertyPath === propertyPath
        );
      },
      merge: (other: HistoryAction) => {
        return this.createPropertyChangeAction(
          componentId,
          propertyPath,
          oldValue,
          (other as any).newValue,
        );
      },
    };
  }

  /**
   * 执行待处理的动作
   */
  private async executePendingActions(): Promise<void> {
    if (this.pendingActions.length === 0) return;

    // 合并所有待处理动作
    let mergedAction = this.pendingActions[0];
    for (let i = 1; i < this.pendingActions.length; i++) {
      const action = this.pendingActions[i];
      if (mergedAction.merge && mergedAction.canMerge?.(action)) {
        mergedAction = mergedAction.merge(action);
      }
    }

    // 执行合并后的动作
    await mergedAction.execute();

    this.pendingActions = [];
    if (this.mergeTimeout) {
      clearTimeout(this.mergeTimeout);
      this.mergeTimeout = null;
    }
  }

  /**
   * 检查是否可以与待处理动作合并
   */
  private canMergeWithPending(action: HistoryAction): boolean {
    if (this.pendingActions.length === 0) return false;

    const lastAction = this.pendingActions[this.pendingActions.length - 1];
    return lastAction.canMerge?.(action) || false;
  }

  /**
   * 合并待处理动作
   */
  private mergePendingAction(action: HistoryAction): void {
    const lastAction = this.pendingActions[this.pendingActions.length - 1];
    if (lastAction.merge) {
      this.pendingActions[this.pendingActions.length - 1] = lastAction.merge(action);
    }
  }

  /**
   * 更新撤销/重做标志
   */
  private updateUndoRedoFlags(): void {
    this.history.forEach((entry, index) => {
      entry.canUndo = index > 0;
      entry.canRedo = index < this.history.length - 1;
    });
  }

  /**
   * 生成唯一ID
   */
  private generateId(): string {
    return `history_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`;
  }

  /**
   * 深度克隆对象
   */
  private deepClone<T>(obj: T): T {
    if (obj === null || typeof obj !== 'object') return obj;
    if (obj instanceof Date) return new Date(obj.getTime()) as any;
    if (obj instanceof Array) return obj.map((item) => this.deepClone(item)) as any;
    if (obj instanceof Map) {
      const cloned = new Map();
      obj.forEach((value, key) => {
        cloned.set(key, this.deepClone(value));
      });
      return cloned as any;
    }
    if (obj instanceof Set) {
      const cloned = new Set();
      obj.forEach((value) => {
        cloned.add(this.deepClone(value));
      });
      return cloned as any;
    }
    if (typeof obj === 'object') {
      const cloned: any = {};
      Object.keys(obj).forEach((key) => {
        cloned[key] = this.deepClone((obj as any)[key]);
      });
      return cloned;
    }
    return obj;
  }
}

/**
 * 全局状态历史管理器实例
 */
let globalStateHistoryManager: StateHistoryManager | null = null;

/**
 * 获取状态历史管理器实例
 */
export function getStateHistoryManager(): StateHistoryManager {
  if (!globalStateHistoryManager) {
    globalStateHistoryManager = new StateHistoryManager();
  }
  return globalStateHistoryManager;
}

/**
 * 设置状态历史管理器实例
 */
export function setStateHistoryManager(manager: StateHistoryManager): void {
  globalStateHistoryManager = manager;
}
