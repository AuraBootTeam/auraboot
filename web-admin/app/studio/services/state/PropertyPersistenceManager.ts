/**
 * 属性持久化管理器
 * 负责组件属性的自动保存、导出和状态管理
 * 集成撤销/重做功能
 */

import type { Component } from '~/studio/domain/schema/types';
import { UpdatePropertyCommand } from '~/studio/services/actions/command/DesignerCommands';

/**
 * 属性保存状态
 */
export enum PropertySaveStatus {
  Idle = 'idle',
  Saving = 'saving',
  Saved = 'saved',
  Error = 'error',
}

/**
 * 属性变更记录
 */
export interface PropertyChange {
  componentId: string;
  propertyName: string;
  oldValue: any;
  newValue: any;
  timestamp: number;
}

/**
 * 属性导出数据
 */
export interface PropertyExportData {
  componentId: string;
  componentType: string;
  componentName?: string;
  properties: Record<string, any>;
  exportedAt: string;
  version: string;
}

/**
 * 属性持久化配置
 */
export interface PropertyPersistenceConfig {
  /** 防抖延迟（毫秒） */
  debounceDelay: number;
  /** 自动保存间隔（毫秒） */
  autoSaveInterval: number;
  /** 是否启用本地存储 */
  enableLocalStorage: boolean;
  /** 最大历史记录数 */
  maxHistorySize: number;
  /** 是否启用撤销/重做 */
  enableUndoRedo: boolean;
}

/**
 * 属性持久化管理器
 */
export class PropertyPersistenceManager {
  private config: PropertyPersistenceConfig;
  private changeHistory: PropertyChange[] = [];
  private saveStatus: PropertySaveStatus = PropertySaveStatus.Idle;
  private saveError: string | null = null;
  private debounceTimers: Map<string, NodeJS.Timeout> = new Map();
  private autoSaveTimer: NodeJS.Timeout | null = null;
  private statusChangeListeners: Set<(status: PropertySaveStatus, error?: string) => void> =
    new Set();

  // 撤销/重做支持
  private undoRedoActions: any = null;
  private isUndoRedoEnabled: boolean = false;

  constructor(config: Partial<PropertyPersistenceConfig> = {}) {
    this.config = {
      debounceDelay: 500,
      autoSaveInterval: 30000,
      enableLocalStorage: true,
      maxHistorySize: 100,
      enableUndoRedo: true,
      ...config,
    };

    this.isUndoRedoEnabled = this.config.enableUndoRedo;
    this.startAutoSave();
  }

  /**
   * 设置撤销/重做操作
   */
  setUndoRedoActions(actions: any) {
    this.undoRedoActions = actions;
  }

  /**
   * 保存属性变更（带防抖）
   */
  async savePropertyChange(
    componentId: string,
    propertyName: string,
    newValue: any,
    oldValue?: any,
  ): Promise<void> {
    const key = `${componentId}-${propertyName}`;

    // 清除之前的防抖定时器
    if (this.debounceTimers.has(key)) {
      clearTimeout(this.debounceTimers.get(key)!);
    }

    // 设置新的防抖定时器
    const timer = setTimeout(async () => {
      await this.debouncedSave(componentId, propertyName, newValue, oldValue);
      this.debounceTimers.delete(key);
    }, this.config.debounceDelay);

    this.debounceTimers.set(key, timer);
  }

  /**
   * 防抖保存实现
   */
  private async debouncedSave(
    componentId: string,
    propertyName: string,
    newValue: any,
    oldValue?: any,
  ): Promise<void> {
    try {
      this.setSaveStatus(PropertySaveStatus.Saving);

      // 创建撤销/重做命令
      // 注意：UpdatePropertyCommand 会在 execute 时自动获取 oldValue
      if (this.isUndoRedoEnabled && this.undoRedoActions) {
        try {
          const command = new UpdatePropertyCommand(
            componentId,
            `props.${propertyName}`, // 属性路径，需要加上 props 前缀
            newValue,
            { oldValue }, // metadata 中包含 oldValue 用于日志
          );

          // 执行命令（这会触发实际的属性更新）
          await this.undoRedoActions.execute(command);
        } catch (cmdError) {
          // 如果命令执行失败，记录警告但继续保存流程
          console.warn('UndoRedo command execution failed, continuing with save:', cmdError);
        }
      }

      await this.performSave(componentId, propertyName, newValue, oldValue);
      this.setSaveStatus(PropertySaveStatus.Saved);

      // 3秒后重置状态
      setTimeout(() => {
        if (this.saveStatus === PropertySaveStatus.Saved) {
          this.setSaveStatus(PropertySaveStatus.Idle);
        }
      }, 3000);
    } catch (error) {
      console.error('Property save failed:', error);
      this.setSaveStatus(
        PropertySaveStatus.Error,
        error instanceof Error ? error.message : '保存失败',
      );
    }
  }

  /**
   * 执行保存操作
   */
  private async performSave(
    componentId: string,
    propertyName: string,
    newValue: any,
    oldValue?: any,
  ): Promise<void> {
    // 添加到历史记录
    this.addToHistory({
      componentId,
      propertyName,
      oldValue,
      newValue,
      timestamp: Date.now(),
    });

    // 保存到本地存储
    if (this.config.enableLocalStorage) {
      try {
        const storageKey = this.getStorageKey(componentId);
        const existingData = (await this.loadComponentProperties(componentId)) || {};
        existingData[propertyName] = newValue;

        // 直接使用 localStorage API
        localStorage.setItem(storageKey, JSON.stringify(existingData));
      } catch (error) {
        console.error('[PropertyPersistenceManager] Failed to save to localStorage:', error);
        throw error;
      }
    }
  }

  /**
   * 加载组件属性
   */
  async loadComponentProperties(componentId: string): Promise<Record<string, any> | null> {
    if (!this.config.enableLocalStorage) return null;

    try {
      const storageKey = this.getStorageKey(componentId);
      const data = localStorage.getItem(storageKey);

      if (!data) {
        return null;
      }

      const properties = JSON.parse(data);
      return properties;
    } catch (error) {
      console.error('[PropertyPersistenceManager] Failed to load component properties:', error);
      return null;
    }
  }

  /**
   * 导出组件属性
   */
  async exportComponentProperties(component: Component): Promise<PropertyExportData> {
    // 优先从 localStorage 加载最新的属性
    const savedProperties = await this.loadComponentProperties(component.id);
    const properties = savedProperties || component.props || {};

    return {
      componentId: component.id,
      componentType: component.type,
      componentName: component.name,
      properties,
      exportedAt: new Date().toISOString(),
      version: '1.0.0',
    };
  }

  /**
   * 导出为JSON文件
   */
  async exportToJsonFile(component: Component): Promise<void> {
    try {
      const exportData = await this.exportComponentProperties(component);
      const jsonString = JSON.stringify(exportData, null, 2);
      const blob = new Blob([jsonString], { type: 'application/json' });
      const url = URL.createObjectURL(blob);

      const fileName = `${component.name || component.type}-properties-${Date.now()}.json`;
      const link = document.createElement('a');
      link.href = url;
      link.download = fileName;
      document.body.appendChild(link);
      link.click();
      document.body.removeChild(link);

      URL.revokeObjectURL(url);
    } catch (error) {
      console.error('[PropertyPersistenceManager] Export to JSON failed:', error);
      throw error;
    }
  }

  /**
   * 复制到剪贴板
   */
  async copyToClipboard(component: Component): Promise<boolean> {
    try {
      const exportData = await this.exportComponentProperties(component);
      const jsonString = JSON.stringify(exportData, null, 2);

      if (navigator.clipboard && navigator.clipboard.writeText) {
        await navigator.clipboard.writeText(jsonString);
        return true;
      } else {
        // 降级方案
        const textArea = document.createElement('textarea');
        textArea.value = jsonString;
        textArea.style.position = 'fixed';
        textArea.style.opacity = '0';
        document.body.appendChild(textArea);
        textArea.select();
        const success = document.execCommand('copy');
        document.body.removeChild(textArea);
        return success;
      }
    } catch (error) {
      console.error('Copy to clipboard failed:', error);
      return false;
    }
  }

  /**
   * 获取变更历史
   */
  getChangeHistory(): readonly PropertyChange[] {
    return [...this.changeHistory];
  }

  /**
   * 清空历史记录
   */
  clearHistory(): void {
    this.changeHistory = [];
  }

  /**
   * 获取保存状态
   */
  getSaveStatus(): PropertySaveStatus {
    return this.saveStatus;
  }

  /**
   * 监听保存状态变化
   */
  onSaveStatusChange(listener: (status: PropertySaveStatus, error?: string) => void): () => void {
    this.statusChangeListeners.add(listener);
    return () => {
      this.statusChangeListeners.delete(listener);
    };
  }

  /**
   * 撤销最后一个属性变更
   */
  async undoLastChange(): Promise<boolean> {
    if (!this.isUndoRedoEnabled || !this.undoRedoActions) {
      console.warn('Undo/Redo is not enabled or actions not set');
      return false;
    }

    try {
      const result = await this.undoRedoActions.undo();
      return result !== null;
    } catch (error) {
      console.error('Undo failed:', error);
      return false;
    }
  }

  /**
   * 重做最后一个撤销的变更
   */
  async redoLastChange(): Promise<boolean> {
    if (!this.isUndoRedoEnabled || !this.undoRedoActions) {
      console.warn('Undo/Redo is not enabled or actions not set');
      return false;
    }

    try {
      const result = await this.undoRedoActions.redo();
      return result !== null;
    } catch (error) {
      console.error('Redo failed:', error);
      return false;
    }
  }

  /**
   * 检查是否可以撤销
   */
  canUndo(): boolean {
    return (this.isUndoRedoEnabled && this.undoRedoActions?.canUndo) || false;
  }

  /**
   * 检查是否可以重做
   */
  canRedo(): boolean {
    return (this.isUndoRedoEnabled && this.undoRedoActions?.canRedo) || false;
  }

  /**
   * 销毁管理器
   */
  destroy(): void {
    // 清除所有防抖定时器
    this.debounceTimers.forEach((timer) => clearTimeout(timer));
    this.debounceTimers.clear();

    // 清除自动保存定时器
    if (this.autoSaveTimer) {
      clearInterval(this.autoSaveTimer);
      this.autoSaveTimer = null;
    }

    // 清空监听器
    this.statusChangeListeners.clear();

    // 清空历史记录
    this.changeHistory = [];
  }

  /**
   * 添加到历史记录
   */
  private addToHistory(change: PropertyChange): void {
    this.changeHistory.push(change);

    // 限制历史记录大小
    if (this.changeHistory.length > this.config.maxHistorySize) {
      this.changeHistory = this.changeHistory.slice(-this.config.maxHistorySize);
    }
  }

  /**
   * 设置保存状态
   */
  private setSaveStatus(status: PropertySaveStatus, error?: string): void {
    this.saveStatus = status;
    this.saveError = error || null;

    // 通知所有监听器
    this.statusChangeListeners.forEach((listener) => {
      try {
        listener(status, error);
      } catch (err) {
        console.error('Status change listener error:', err);
      }
    });
  }

  /**
   * 获取存储键
   */
  private getStorageKey(componentId: string): string {
    return `component-properties-${componentId}`;
  }

  /**
   * 启动自动保存
   */
  private startAutoSave(): void {
    if (this.config.autoSaveInterval > 0) {
      this.autoSaveTimer = setInterval(() => {
        // 这里可以实现定期保存逻辑
        // 例如：保存所有未保存的更改
      }, this.config.autoSaveInterval);
    }
  }
}

// 全局实例管理
let globalPropertyPersistenceManager: PropertyPersistenceManager | null = null;

/**
 * 获取全局属性持久化管理器实例
 */
export function getPropertyPersistenceManager(
  config?: Partial<PropertyPersistenceConfig>,
): PropertyPersistenceManager {
  if (!globalPropertyPersistenceManager) {
    globalPropertyPersistenceManager = new PropertyPersistenceManager(config);
  }
  return globalPropertyPersistenceManager;
}

/**
 * 重置全局属性持久化管理器实例
 */
export function resetPropertyPersistenceManager(): void {
  if (globalPropertyPersistenceManager) {
    globalPropertyPersistenceManager.destroy();
    globalPropertyPersistenceManager = null;
  }
}
