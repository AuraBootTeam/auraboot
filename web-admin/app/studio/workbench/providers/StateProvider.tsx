/**
 * 状态提供者组件
 *
 * 为设计器提供统一的状态管理上下文
 */

import React, { createContext, useContext, useEffect, useRef, useState, useCallback } from 'react';
import { PageStateManager } from '~/studio/services/state/PageStateManager';
import type { PageState, ComponentState } from '~/studio/services/state/types';
import { ActionScheduler } from '~/studio/services/runtime/execution/ActionScheduler';
import type { ActionResult } from '~/studio/services/runtime/execution/types';

/**
 * 状态上下文类型
 */
interface StateContextType {
  stateManager: PageStateManager;
  actionScheduler: ActionScheduler;

  // 状态访问
  state: PageState;
  getState: () => PageState;
  getComponentState: (componentId: string) => ComponentState | null;

  // 状态更新
  setState: (updates: Partial<PageState>, source?: string) => void;
  setComponentState: (componentId: string, state: Partial<ComponentState>, source?: string) => void;
  setGlobalState: (key: string, value: any, source?: string) => void;
  setFormData: (key: string, value: any, source?: string) => void;
  setUIState: (updates: Partial<PageState['uiState']>, source?: string) => void;

  // 动作执行
  executeAction: (actionType: string, config: any, context?: any) => Promise<ActionResult>;
  executeActionChain: (actions: any[], context?: any) => Promise<ActionResult[]>;

  // 订阅和监听
  subscribe: (path: string, callback: Function) => () => void;
  watch: (path: string, callback: (newValue: any, oldValue: any) => void) => () => void;
}

/**
 * 状态上下文
 */
const StateContext = createContext<StateContextType | null>(null);

/**
 * 状态提供者属性
 */
interface StateProviderProps {
  children: React.ReactNode;
  initialState?: Partial<PageState>;
  onStateChange?: (state: PageState) => void;
  onActionResult?: (result: ActionResult) => void;
}

/**
 * 状态提供者组件
 */
export const StateProvider: React.FC<StateProviderProps> = ({
  children,
  initialState,
  onStateChange,
  onActionResult,
}) => {
  const stateManagerRef = useRef<PageStateManager | null>(null);
  const actionSchedulerRef = useRef<ActionScheduler | null>(null);

  // Ensure we have a state manager instance synchronously for the initial state
  if (!stateManagerRef.current) {
    stateManagerRef.current = new PageStateManager(initialState);
  }

  const [state, setState] = useState<PageState>(() => stateManagerRef.current!.getState());

  // 初始化动作调度器
  if (!actionSchedulerRef.current) {
    actionSchedulerRef.current = new ActionScheduler();
  }

  const stateManager = stateManagerRef.current!;
  const actionScheduler = actionSchedulerRef.current!;

  // 监听状态变更
  useEffect(() => {
    const handleStateChange = (newState: PageState) => {
      setState(newState);
      onStateChange?.(newState);
    };

    stateManager.on('stateChange', handleStateChange);
    return () => stateManager.off('stateChange', handleStateChange);
  }, [stateManager, onStateChange]);

  // 状态访问方法
  const getState = useCallback(() => {
    return stateManager.getState();
  }, [stateManager]);

  const getComponentState = useCallback(
    (componentId: string) => {
      return stateManager.getComponentState(componentId);
    },
    [stateManager],
  );

  // 状态更新方法
  const setStateValue = useCallback(
    (updates: Partial<PageState>, source?: string) => {
      stateManager.setState(updates, source);
    },
    [stateManager],
  );

  const setComponentState = useCallback(
    (componentId: string, componentState: Partial<ComponentState>, source?: string) => {
      stateManager.setComponentState(componentId, componentState, source);
    },
    [stateManager],
  );

  const setGlobalState = useCallback(
    (key: string, value: any, source?: string) => {
      stateManager.setGlobalState(key, value, source);
    },
    [stateManager],
  );

  const setFormData = useCallback(
    (key: string, value: any, source?: string) => {
      stateManager.setFormData(key, value, source);
    },
    [stateManager],
  );

  const setUIState = useCallback(
    (updates: Partial<PageState['uiState']>, source?: string) => {
      stateManager.setUIState(updates, source);
    },
    [stateManager],
  );

  // 动作执行方法
  const executeAction = useCallback(
    async (actionType: string, config: any, context?: any) => {
      const actionContext = stateManager.createActionContext(context);
      const result = await actionScheduler.executeAction(
        {
          id: `action_${Date.now()}`,
          params: {
            type: actionType as any,
            ...config,
          },
        },
        actionContext,
      );

      // 处理动作结果
      stateManager.handleActionResult(result, 'action');
      onActionResult?.(result);

      return result;
    },
    [stateManager, actionScheduler, onActionResult],
  );

  const executeActionChain = useCallback(
    async (actions: any[], context?: any) => {
      const actionContext = stateManager.createActionContext(context);
      const chainResult = await actionScheduler.executeActionChain(
        {
          id: `chain_${Date.now()}`,
          name: 'Dynamic Chain',
          actions: actions.map((a, i) => ({
            id: a.id || `action_${i}_${Date.now()}`,
            params: a.params || a,
          })),
        },
        actionContext,
      );

      // 处理所有动作结果
      chainResult.results.forEach((result) => {
        stateManager.handleActionResult(result, 'action-chain');
      });

      return chainResult.results;
    },
    [stateManager, actionScheduler],
  );

  // 订阅和监听方法
  const subscribe = useCallback(
    (path: string, callback: Function) => {
      // Implement path-based subscription using watch
      return stateManager.watch(path, (newValue, oldValue) => callback(newValue, oldValue));
    },
    [stateManager],
  );

  const watch = useCallback(
    (path: string, callback: (newValue: any, oldValue: any) => void) => {
      return stateManager.watch(path, callback);
    },
    [stateManager],
  );

  // 上下文值
  const contextValue: StateContextType = {
    stateManager,
    actionScheduler,
    state,
    getState,
    getComponentState,
    setState: setStateValue,
    setComponentState,
    setGlobalState,
    setFormData,
    setUIState,
    executeAction,
    executeActionChain,
    subscribe,
    watch,
  };

  return <StateContext.Provider value={contextValue}>{children}</StateContext.Provider>;
};

/**
 * 使用状态上下文
 */
export function useStateContext(): StateContextType {
  const context = useContext(StateContext);
  if (!context) {
    throw new Error('useStateContext must be used within a StateProvider');
  }
  return context;
}

/**
 * 使用页面状态
 */
export function usePageState() {
  const { state, setState, getState } = useStateContext();
  return { state, setState, getState };
}

/**
 * 使用组件状态
 */
export function useComponentState(componentId: string) {
  const { getComponentState, setComponentState, subscribe } = useStateContext();
  const [componentState, setLocalComponentState] = useState<ComponentState | null>(
    getComponentState(componentId),
  );

  useEffect(() => {
    const unsubscribe = subscribe(`components.${componentId}`, (newState: ComponentState) => {
      setLocalComponentState(newState);
    });
    return unsubscribe;
  }, [componentId, subscribe]);

  const updateComponentState = useCallback(
    (updates: Partial<ComponentState>, source?: string) => {
      setComponentState(componentId, updates, source);
    },
    [componentId, setComponentState],
  );

  return {
    componentState,
    updateComponentState,
    setComponentState: updateComponentState,
  };
}

/**
 * 使用全局状态
 */
export function useGlobalState(key?: string) {
  const { state, setGlobalState, subscribe } = useStateContext();
  const [globalState, setLocalGlobalState] = useState(
    key ? state.globalState[key] : state.globalState,
  );

  useEffect(() => {
    const path = key ? `globalState.${key}` : 'globalState';
    const unsubscribe = subscribe(path, (newValue: any) => {
      setLocalGlobalState(newValue);
    });
    return unsubscribe;
  }, [key, subscribe]);

  const updateGlobalState = useCallback(
    (keyOrValue: string | Record<string, any>, value?: any, source?: string) => {
      if (typeof keyOrValue === 'string') {
        setGlobalState(keyOrValue, value, source);
      } else {
        // 批量更新
        Object.entries(keyOrValue).forEach(([k, v]) => {
          setGlobalState(k, v, source);
        });
      }
    },
    [setGlobalState],
  );

  return {
    globalState,
    setGlobalState: updateGlobalState,
  };
}

/**
 * 使用表单数据
 */
export function useFormData(key?: string) {
  const { state, setFormData, subscribe } = useStateContext();
  const [formData, setLocalFormData] = useState(key ? state.formData[key] : state.formData);

  useEffect(() => {
    const path = key ? `formData.${key}` : 'formData';
    const unsubscribe = subscribe(path, (newValue: any) => {
      setLocalFormData(newValue);
    });
    return unsubscribe;
  }, [key, subscribe]);

  const updateFormData = useCallback(
    (keyOrValue: string | Record<string, any>, value?: any, source?: string) => {
      if (typeof keyOrValue === 'string') {
        setFormData(keyOrValue, value, source);
      } else {
        // 批量更新
        Object.entries(keyOrValue).forEach(([k, v]) => {
          setFormData(k, v, source);
        });
      }
    },
    [setFormData],
  );

  return {
    formData,
    setFormData: updateFormData,
  };
}

/**
 * 使用 UI 状态
 */
export function useUIState() {
  const { state, setUIState, subscribe } = useStateContext();
  const [uiState, setLocalUIState] = useState(state.uiState);

  useEffect(() => {
    const unsubscribe = subscribe('uiState', (newValue: PageState['uiState']) => {
      setLocalUIState(newValue);
    });
    return unsubscribe;
  }, [subscribe]);

  const updateUIState = useCallback(
    (updates: Partial<PageState['uiState']>, source?: string) => {
      setUIState(updates, source);
    },
    [setUIState],
  );

  return {
    uiState,
    setUIState: updateUIState,
  };
}

/**
 * 使用动作执行
 */
export function useActions() {
  const { executeAction, executeActionChain } = useStateContext();

  return {
    executeAction,
    executeActionChain,
  };
}

/**
 * 使用状态订阅
 */
export function useStateSubscription<T = any>(
  path: string,
  callback: (value: T) => void,
  deps: React.DependencyList = [],
) {
  const { subscribe } = useStateContext();

  useEffect(() => {
    const unsubscribe = subscribe(path, callback);
    return unsubscribe;
  }, [path, subscribe, ...deps]);
}

/**
 * 使用状态监听
 */
export function useStateWatch<T = any>(
  path: string,
  callback: (newValue: T, oldValue: T) => void,
  deps: React.DependencyList = [],
) {
  const { watch } = useStateContext();

  useEffect(() => {
    const unsubscribe = watch(path, callback);
    return unsubscribe;
  }, [path, watch, ...deps]);
}

/**
 * 使用计算状态
 */
export function useComputedState<T = any>(
  selector: (state: PageState) => T,
  deps: React.DependencyList = [],
): T {
  const { state } = useStateContext();

  return React.useMemo(() => {
    return selector(state);
  }, [state, ...deps]);
}
