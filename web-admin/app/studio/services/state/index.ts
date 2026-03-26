/**
 * 状态管理系统入口
 *
 * 导出所有状态管理相关的类型、组件和工具
 */

import type { PageState, ComponentState } from '~/studio/services/state/types';
import { PageStateManager } from '~/studio/services/state/PageStateManager';

// 核心类型
export type {
  PageState,
  ComponentState,
  StateChange,
  StateHistory,
  StateSubscriptionOptions,
  StateSelector,
  StateUpdater,
} from '~/studio/services/state/types';

// 核心类
export { PageStateManager } from '~/studio/services/state/PageStateManager';

// 工具函数
export const StateUtils = {
  /**
   * 创建默认的页面状态
   */
  createDefaultPageState: (overrides?: Partial<PageState>): PageState => ({
    pageInfo: {
      id: '',
      title: '未命名页面',
      description: '',
      version: '1.0.0',
      createdAt: new Date(),
      updatedAt: new Date(),
      ...overrides?.pageInfo,
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
    ...overrides,
  }),

  /**
   * 创建默认的组件状态
   */
  createDefaultComponentState: (
    type: string,
    props: Record<string, any> = {},
    overrides?: Partial<ComponentState>,
  ): ComponentState => ({
    type,
    props,
    internalState: {},
    styles: {},
    isVisible: true,
    isSelected: false,
    isHovered: false,
    isDragging: false,
    validation: {
      isValid: true,
      errors: [],
      warnings: [],
    },
    loading: {
      isLoading: false,
      loadingText: '',
    },
    error: null,
    metadata: {
      createdAt: new Date(),
      updatedAt: new Date(),
    },
    ...overrides,
  }),

  /**
   * 深度克隆状态对象
   */
  cloneState: <T>(state: T): T => {
    return JSON.parse(JSON.stringify(state));
  },

  /**
   * 合并状态对象
   */
  mergeState: <T extends Record<string, any>>(target: T, source: Partial<T>): T => {
    const result: Record<string, any> = { ...target };

    Object.keys(source).forEach((key) => {
      const sourceValue = source[key];
      const targetValue = target[key];

      if (sourceValue && typeof sourceValue === 'object' && !Array.isArray(sourceValue)) {
        if (targetValue && typeof targetValue === 'object' && !Array.isArray(targetValue)) {
          result[key] = StateUtils.mergeState(targetValue, sourceValue);
        } else {
          result[key] = sourceValue;
        }
      } else {
        result[key] = sourceValue;
      }
    });

    return result as T;
  },

  /**
   * 获取状态路径的值
   */
  getValueByPath: (obj: any, path: string): any => {
    return path.split('.').reduce((current, key) => current?.[key], obj);
  },

  /**
   * 设置状态路径的值
   */
  setValueByPath: (obj: any, path: string, value: any): any => {
    const keys = path.split('.');
    const lastKey = keys.pop()!;
    const target = keys.reduce((current, key) => {
      if (!current[key] || typeof current[key] !== 'object') {
        current[key] = {};
      }
      return current[key];
    }, obj);

    target[lastKey] = value;
    return obj;
  },

  /**
   * 验证状态结构
   */
  validateState: (state: any): { isValid: boolean; errors: string[] } => {
    const errors: string[] = [];

    // 检查必需的顶级属性
    const requiredProps = ['pageInfo', 'components', 'globalState', 'formData', 'uiState'];
    requiredProps.forEach((prop) => {
      if (!state[prop]) {
        errors.push(`缺少必需属性: ${prop}`);
      }
    });

    // 检查 pageInfo 结构
    if (state.pageInfo) {
      if (!state.pageInfo.id) {
        errors.push('pageInfo.id 不能为空');
      }
      if (!state.pageInfo.title) {
        errors.push('pageInfo.title 不能为空');
      }
    }

    // 检查组件状态结构
    if (state.components) {
      Object.entries(state.components).forEach(([componentId, componentState]: [string, any]) => {
        if (!componentState.type) {
          errors.push(`组件 ${componentId} 缺少 type 属性`);
        }
        if (!componentState.props) {
          errors.push(`组件 ${componentId} 缺少 props 属性`);
        }
      });
    }

    return {
      isValid: errors.length === 0,
      errors,
    };
  },

  /**
   * 计算状态差异
   */
  diffStates: (oldState: PageState, newState: PageState) => {
    const changes: Array<{
      path: string;
      type: 'added' | 'modified' | 'deleted';
      oldValue?: any;
      newValue?: any;
    }> = [];

    const diff = (obj1: any, obj2: any, path: string = '') => {
      const keys1 = Object.keys(obj1 || {});
      const keys2 = Object.keys(obj2 || {});
      const allKeys = new Set([...keys1, ...keys2]);

      allKeys.forEach((key) => {
        const currentPath = path ? `${path}.${key}` : key;
        const val1 = obj1?.[key];
        const val2 = obj2?.[key];

        if (val1 === undefined && val2 !== undefined) {
          changes.push({
            path: currentPath,
            type: 'added',
            newValue: val2,
          });
        } else if (val1 !== undefined && val2 === undefined) {
          changes.push({
            path: currentPath,
            type: 'deleted',
            oldValue: val1,
          });
        } else if (val1 !== val2) {
          if (
            typeof val1 === 'object' &&
            typeof val2 === 'object' &&
            val1 !== null &&
            val2 !== null
          ) {
            diff(val1, val2, currentPath);
          } else {
            changes.push({
              path: currentPath,
              type: 'modified',
              oldValue: val1,
              newValue: val2,
            });
          }
        }
      });
    };

    diff(oldState, newState);
    return changes;
  },

  /**
   * 序列化状态
   */
  serializeState: (state: PageState): string => {
    return JSON.stringify(state, (key, value) => {
      // 处理特殊类型
      if (value instanceof Date) {
        return { __type: 'Date', value: value.toISOString() };
      }
      if (value instanceof RegExp) {
        return { __type: 'RegExp', value: value.toString() };
      }
      return value;
    });
  },

  /**
   * 反序列化状态
   */
  deserializeState: (serializedState: string): PageState => {
    return JSON.parse(serializedState, (key, value) => {
      // 处理特殊类型
      if (value && typeof value === 'object' && value.__type) {
        switch (value.__type) {
          case 'Date':
            return new Date(value.value);
          case 'RegExp':
            const match = value.value.match(/^\/(.*)\/([gimuy]*)$/);
            return match ? new RegExp(match[1], match[2]) : new RegExp(value.value);
        }
      }
      return value;
    });
  },
};

// 常量
export const STATE_CONSTANTS = {
  // 默认配置
  DEFAULT_CONFIG: {
    historySize: 50,
    autoSaveInterval: 5000,
    debounceDelay: 300,
    maxSubscriptions: 1000,
  },

  // 事件类型
  EVENTS: {
    STATE_CHANGE: 'stateChange',
    COMPONENT_CHANGE: 'componentChange',
    GLOBAL_STATE_CHANGE: 'globalStateChange',
    FORM_DATA_CHANGE: 'formDataChange',
    UI_STATE_CHANGE: 'uiStateChange',
  },

  // 状态路径
  PATHS: {
    PAGE_INFO: 'pageInfo',
    COMPONENTS: 'components',
    GLOBAL_STATE: 'globalState',
    FORM_DATA: 'formData',
    UI_STATE: 'uiState',
    USER_STATE: 'userState',
    ENVIRONMENT: 'environment',
    TEMPORARY_STATE: 'temporaryState',
  },

  // 错误代码
  ERROR_CODES: {
    INVALID_STATE: 'invalid_state',
    COMPONENT_NOT_FOUND: 'component_not_found',
    INVALID_PATH: 'invalid_path',
    SUBSCRIPTION_LIMIT_EXCEEDED: 'subscription_limit_exceeded',
    SERIALIZATION_ERROR: 'serialization_error',
  },
};

// 工厂函数
export const createPageStateManager = (initialState?: Partial<PageState>) => {
  return new PageStateManager(initialState);
};

// Zustand Designer Store
export {
  useDesignerStore,
  usePageSchema,
  useLayoutConfig,
  useSelectedComponent,
  useIsLoading,
  useComponents,
  useDesignerActions,
  type DesignerState,
  type DesignerActions,
  type DesignerStore,
} from '~/studio/hooks/store/useDesignerStore';
