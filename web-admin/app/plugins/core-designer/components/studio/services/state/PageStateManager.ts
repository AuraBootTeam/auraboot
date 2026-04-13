/**
 * Page State Manager
 *
 * 管理页面设计器的状态系统
 */

import type {
  PageState,
  StateChange,
  StateHistory,
  ComponentState,
  StateSnapshot,
  StateChangeEvent,
} from '~/plugins/core-designer/components/studio/services/state/types';

export type { PageState, StateSnapshot, StateChangeEvent, ComponentState };

export interface StateExportData {
  state: PageState;
  exportMetadata: {
    exportedAt: number;
    exportVersion: string;
    exportedBy: string;
  };
}

/**
 * 页面状态管理器接口
 */
export interface IPageStateManager {
  // 状态管理
  getState(): PageState;
  setState(state: Partial<PageState>, source?: string): void;
  updateState(changes: StateChange[]): void;

  // 组件状态 (Added for compatibility)
  getComponentState(componentId: string): ComponentState | null;
  setComponentState(componentId: string, state: Partial<ComponentState>, source?: string): void;

  // 其他状态 updates (Added for compatibility)
  setGlobalState(key: string, value: any, source?: string): void;
  setFormData(key: string, value: any, source?: string): void;
  setUIState(updates: Partial<PageState['uiState']>, source?: string): void;

  // 状态历史
  getHistory(): StateHistory;
  saveSnapshot(description?: string): void;
  restoreSnapshot(id: string): void;

  // 状态查询
  hasUnsavedChanges(): boolean;
  getLastSavedState(): PageState | null;

  // 状态监听
  subscribe(listener: (state: PageState) => void): () => void;
  on(event: string, callback: Function): void;
  off(event: string, callback: Function): void;
  watch(path: string, callback: (newValue: any, oldValue: any) => void): () => void;

  // Action Context
  createActionContext(context?: any): any;
  handleActionResult(result: any, source: string): void;

  // 导入导出
  exportState(): Promise<any>;
  importState(data: any): Promise<void>;

  // 初始化
  initialize(initialState?: Partial<PageState>): Promise<void>;
  reset(): void;
}

/**
 * 页面状态管理器实现
 */
class PageStateManagerImpl implements IPageStateManager {
  private currentState: PageState;
  private history: StateHistory;
  private listeners: Set<(state: PageState) => void> = new Set();
  private lastSavedState: PageState | null = null;

  constructor(initialState?: Partial<PageState>) {
    this.currentState = { ...this.createDefaultState(), ...initialState };
    this.history = {
      snapshots: [],
      currentIndex: -1,
      maxSize: 20,
    };
  }

  async initialize(initialState?: Partial<PageState>): Promise<void> {
    if (initialState) {
      this.currentState = { ...this.currentState, ...initialState };
    }

    this.lastSavedState = { ...this.currentState };
    this.saveSnapshot('Initial state');
  }

  getState(): PageState {
    return { ...this.currentState };
  }

  setState(state: Partial<PageState>, source?: string): void {
    const previousState = { ...this.currentState };
    this.currentState = { ...this.currentState, ...state };

    // 更新修改时间
    this.currentState.lastModified = Date.now();

    // 通知监听器
    this.notifyListeners();
  }

  updateState(changes: StateChange[]): void {
    const previousState = { ...this.currentState };

    // 应用所有变更
    changes.forEach((change) => {
      this.applyStateChange(change);
    });

    // 更新修改时间
    this.currentState.lastModified = Date.now();

    // 通知监听器
    this.notifyListeners();
  }

  getHistory(): StateHistory {
    return { ...this.history };
  }

  saveSnapshot(description?: string): void {
    const snapshot = {
      id: `snapshot_${Date.now()}`,
      state: { ...this.currentState },
      timestamp: Date.now(),
      description: description || `Snapshot at ${new Date().toLocaleTimeString()}`,
    };

    // 如果当前不在历史末尾，则删除后续快照
    if (this.history.currentIndex < this.history.snapshots.length - 1) {
      this.history.snapshots.splice(this.history.currentIndex + 1);
    }

    // 添加新快照
    this.history.snapshots.push(snapshot);
    this.history.currentIndex++;

    // 如果超过最大大小，则删除最旧的快照
    if (this.history.snapshots.length > this.history.maxSize) {
      this.history.snapshots.shift();
      this.history.currentIndex--;
    }
  }

  restoreSnapshot(id: string): void {
    const snapshot = this.history.snapshots.find((s) => s.id === id);
    if (!snapshot) {
      throw new Error(`Snapshot not found: ${id}`);
    }

    this.currentState = { ...snapshot.state };
    this.notifyListeners();
  }

  hasUnsavedChanges(): boolean {
    if (!this.lastSavedState) {
      return true;
    }

    return JSON.stringify(this.currentState) !== JSON.stringify(this.lastSavedState);
  }

  getLastSavedState(): PageState | null {
    return this.lastSavedState ? { ...this.lastSavedState } : null;
  }

  subscribe(listener: (state: PageState) => void): () => void {
    this.listeners.add(listener);

    // 返回取消订阅函数
    return () => {
      this.listeners.delete(listener);
    };
  }

  reset(): void {
    this.currentState = this.createDefaultState();
    this.history = {
      snapshots: [],
      currentIndex: -1,
      maxSize: 20,
    };
    this.lastSavedState = null;
    this.notifyListeners();
  }

  private createDefaultState(): PageState {
    return {
      pageInfo: {
        id: `page_${Date.now()}`,
        title: 'Untitled Page',
        description: '',
        version: '1.0.0',
        createdAt: new Date(),
        updatedAt: new Date(),
      },
      components: {},
      globalState: {},
      formData: {},
      uiState: {
        selectedComponentId: null,
        hoveredComponentId: null,
        draggedComponentId: null,
        isPreviewMode: false,
        zoom: 1,
        viewport: { width: 1200, height: 800 },
        showGrid: true,
        showRuler: false,
        showOutline: false,
      },
      userState: {
        preferences: {},
        permissions: [],
        role: 'editor',
      },
      environment: {
        mode: 'development',
        theme: 'light',
        locale: 'zh-CN',
        device: 'desktop',
      },
      temporaryState: {},
      // Backwards compatibility
      schema: {
        id: `page_${Date.now()}`,
        version: '1.0.0',
        title: 'Untitled Page',
        description: '',
        components: [],
        layout: {
          type: 'grid',
          columns: 12,
          gap: 16,
          padding: 24,
        },
        styles: {
          global: {},
          components: {},
          themes: {
            default: {
              colors: {
                primary: '#3B82F6',
                background: '#FFFFFF',
                text: '#1F2937',
              },
              fonts: {},
              spacing: {},
              borderRadius: {
                default: 8,
              },
            },
          },
        },
        scripts: [],
        metadata: {
          title: 'Untitled Page',
          description: '',
          tags: [],
          createdAt: Date.now(),
          updatedAt: Date.now(),
          version: '1.0.0',
        },
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
      },
      selectedComponents: [],
      clipboard: [],
      isDirty: false,
      isLoading: false,
    };
  }

  private applyStateChange(change: StateChange): void {
    switch (change.type) {
      case 'component_add':
        // 添加组件逻辑
        break;
      case 'component_remove':
        // 删除组件逻辑
        break;
      case 'component_update':
        // 更新组件逻辑
        break;
      case 'layout_change':
        // 布局变更逻辑
        break;
      case 'theme_change':
        // 主题变更逻辑
        break;
      default:
        console.warn('Unknown state change type:', change.type);
    }
  }

  private notifyListeners(): void {
    this.listeners.forEach((listener) => {
      try {
        listener(this.currentState);
      } catch (error) {
        console.error('Error in state listener:', error);
      }
    });
  }

  async exportState(): Promise<any> {
    try {
      const exportData = {
        pageSchema: this.currentState.schema,
        exportMetadata: {
          exportedAt: Date.now(),
          exportVersion: '1.0.0',
          exportedBy: 'PageStateManager',
        },
        stateSnapshot: {
          isDirty: this.currentState.isDirty,
          isLoading: this.currentState.isLoading,
          selectedComponents: this.currentState.selectedComponents,
          clipboard: this.currentState.clipboard,
          error: this.currentState.error,
        },
      };

      return exportData;
    } catch (error) {
      console.error('Export state failed:', error);
      throw new Error(`导出状态失败: ${error instanceof Error ? error.message : '未知错误'}`);
    }
  }

  async importState(data: any): Promise<void> {
    try {
      if (!data) {
        throw new Error('导入数据不能为空');
      }

      // 验证导入数据结构
      if (!data.pageSchema) {
        throw new Error('导入数据缺少 pageSchema');
      }

      const { pageSchema, stateSnapshot } = data;

      // 更新页面状态
      const newState: Partial<PageState> = {
        schema: pageSchema,
        isDirty: true, // 标记为已修改
      };

      // 如果有状态快照，也恢复相关状态
      if (stateSnapshot) {
        newState.selectedComponents = stateSnapshot.selectedComponents || [];
        newState.clipboard = stateSnapshot.clipboard || [];
        newState.isLoading = stateSnapshot.isLoading || false;
        newState.error = stateSnapshot.error;
      }

      // 应用新状态
      this.setState(newState);

      // 保存快照
      this.saveSnapshot('导入状态');
    } catch (error) {
      console.error('Import state failed:', error);
      throw new Error(`导入状态失败: ${error instanceof Error ? error.message : '未知错误'}`);
    }
  }
  getComponentState(componentId: string): ComponentState | null {
    return this.currentState.components[componentId] || null;
  }

  setComponentState(componentId: string, state: Partial<ComponentState>, source?: string): void {
    const currentCompState = this.getComponentState(componentId);
    if (!currentCompState) {
      console.warn(`Component ${componentId} not found`);
      return;
    }

    const newCompState = {
      ...currentCompState,
      ...state,
      metadata: { ...currentCompState.metadata, updatedAt: new Date() },
    };

    this.setState({
      components: {
        ...this.currentState.components,
        [componentId]: newCompState,
      },
    });
  }

  setGlobalState(key: string, value: any, source?: string): void {
    this.setState({
      globalState: {
        ...this.currentState.globalState,
        [key]: value,
      },
    });
  }

  setFormData(key: string, value: any, source?: string): void {
    this.setState({
      formData: {
        ...this.currentState.formData,
        [key]: value,
      },
    });
  }

  setUIState(updates: Partial<PageState['uiState']>, source?: string): void {
    this.setState({
      uiState: {
        ...this.currentState.uiState,
        ...updates,
      },
    });
  }

  // Event Emitter methods
  on(event: string, callback: Function): void {
    if (event === 'stateChange') {
      this.subscribe(callback as (state: PageState) => void);
    }
    // Add other events if needed
  }

  off(event: string, callback: Function): void {
    if (event === 'stateChange') {
      this.listeners.delete(callback as (state: PageState) => void);
    }
  }

  watch(path: string, callback: (newValue: any, oldValue: any) => void): () => void {
    // Simple implementation of watch
    let currentValue = this.getValueByPath(this.currentState, path);

    const listener = (newState: PageState) => {
      const newValue = this.getValueByPath(newState, path);
      if (newValue !== currentValue) {
        callback(newValue, currentValue);
        currentValue = newValue;
      }
    };

    return this.subscribe(listener);
  }

  createActionContext(context?: any): any {
    return {
      state: this.currentState,
      stateManager: this,
      ...context,
    };
  }

  handleActionResult(_result: any, _source: string): void {
    // TODO: Implement specific logic based on result type if needed
  }

  private getValueByPath(obj: any, path: string): any {
    return path.split('.').reduce((acc, key) => acc && acc[key], obj);
  }
}

// 全局页面状态管理器实例
let globalPageStateManager: IPageStateManager | null = null;

/**
 * 获取全局页面状态管理器实例（单例模式）
 */
export function getPageStateManager(): IPageStateManager {
  if (!globalPageStateManager) {
    globalPageStateManager = new PageStateManagerImpl();
  }
  return globalPageStateManager;
}

/**
 * 创建新的页面状态管理器实例
 */
export function createPageStateManager(): IPageStateManager {
  return new PageStateManagerImpl();
}

// Export the implementation class as PageStateManager for direct instantiation
export { PageStateManagerImpl as PageStateManager };

export default getPageStateManager;
