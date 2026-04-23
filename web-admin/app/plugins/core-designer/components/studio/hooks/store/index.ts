/**
 * 状态管理相关的 React Hooks
 *
 * 提供更细粒度的状态管理功能
 */

import { useState, useEffect, useCallback, useRef, useMemo } from 'react';
import { useStateContext } from '~/plugins/core-designer/components/studio/hooks/store/StateProvider';
import type {
  PageState,
  ComponentState,
  StateChangeEvent,
} from '~/plugins/core-designer/components/studio/hooks/store/PageStateManager';

/**
 * 使用状态选择器
 *
 * 允许选择状态的特定部分，并在该部分变化时重新渲染
 */
export function useStateSelector<T>(
  selector: (state: PageState) => T,
  equalityFn?: (a: T, b: T) => boolean,
): T {
  const { state, subscribe } = useStateContext();
  const [selectedState, setSelectedState] = useState(() => selector(state));
  const selectorRef = useRef(selector);
  const equalityRef = useRef(equalityFn);

  // 更新引用
  selectorRef.current = selector;
  equalityRef.current = equalityFn;

  useEffect(() => {
    const checkForUpdates = (newState: PageState) => {
      const newSelectedState = selectorRef.current(newState);

      if (equalityRef.current) {
        if (!equalityRef.current(selectedState, newSelectedState)) {
          setSelectedState(newSelectedState);
        }
      } else if (selectedState !== newSelectedState) {
        setSelectedState(newSelectedState);
      }
    };

    const unsubscribe = subscribe('*', checkForUpdates);
    return unsubscribe;
  }, [subscribe, selectedState]);

  return selectedState;
}

/**
 * 使用状态历史
 *
 * 提供撤销/重做功能
 */
export function useStateHistory() {
  const { stateManager } = useStateContext();
  const [canUndo, setCanUndo] = useState(false);
  const [canRedo, setCanRedo] = useState(false);
  const [historySize, setHistorySize] = useState(0);

  useEffect(() => {
    const updateHistoryStatus = () => {
      const history = stateManager.getHistory();
      setCanUndo(history.canUndo);
      setCanRedo(history.canRedo);
      setHistorySize(history.size);
    };

    // 初始更新
    updateHistoryStatus();

    // 监听状态变化
    stateManager.on('stateChange', updateHistoryStatus);
    return () => stateManager.off('stateChange', updateHistoryStatus);
  }, [stateManager]);

  const undo = useCallback(() => {
    stateManager.undo();
  }, [stateManager]);

  const redo = useCallback(() => {
    stateManager.redo();
  }, [stateManager]);

  const clearHistory = useCallback(() => {
    stateManager.clearHistory();
  }, [stateManager]);

  return {
    canUndo,
    canRedo,
    historySize,
    undo,
    redo,
    clearHistory,
  };
}

/**
 * 使用组件状态批量操作
 */
export function useComponentStateBatch() {
  const { stateManager } = useStateContext();

  const batchUpdateComponents = useCallback(
    (updates: Array<{ componentId: string; state: Partial<ComponentState> }>, source?: string) => {
      stateManager.batchUpdateComponents(updates, source);
    },
    [stateManager],
  );

  const batchRemoveComponents = useCallback(
    (componentIds: string[], source?: string) => {
      componentIds.forEach((id) => {
        stateManager.removeComponentState(id, source);
      });
    },
    [stateManager],
  );

  const getAllComponentStates = useCallback(() => {
    return stateManager.getState().components;
  }, [stateManager]);

  const getComponentsByType = useCallback(
    (type: string) => {
      const components = stateManager.getState().components;
      return Object.entries(components)
        .filter(([_, state]) => state.type === type)
        .reduce(
          (acc, [id, state]) => {
            acc[id] = state;
            return acc;
          },
          {} as Record<string, ComponentState>,
        );
    },
    [stateManager],
  );

  return {
    batchUpdateComponents,
    batchRemoveComponents,
    getAllComponentStates,
    getComponentsByType,
  };
}

/**
 * 使用状态持久化
 */
export function useStatePersistence(key: string = 'designer-state') {
  const { stateManager } = useStateContext();

  const saveState = useCallback(() => {
    const state = stateManager.serialize();
    localStorage.setItem(key, state);
  }, [stateManager, key]);

  const loadState = useCallback(() => {
    const savedState = localStorage.getItem(key);
    if (savedState) {
      stateManager.deserialize(savedState);
    }
  }, [stateManager, key]);

  const clearSavedState = useCallback(() => {
    localStorage.removeItem(key);
  }, [key]);

  const hasSavedState = useCallback(() => {
    return localStorage.getItem(key) !== null;
  }, [key]);

  return {
    saveState,
    loadState,
    clearSavedState,
    hasSavedState,
  };
}

/**
 * 使用状态验证
 */
export function useStateValidation() {
  const { state } = useStateContext();
  const [validationErrors, setValidationErrors] = useState<Record<string, string[]>>({});

  const validateComponent = useCallback((componentId: string, componentState: ComponentState) => {
    const errors: string[] = [];

    // 基本验证
    if (!componentState.type) {
      errors.push('组件类型不能为空');
    }

    // 属性验证
    if (componentState.validation?.required) {
      const requiredFields = componentState.validation.required;
      requiredFields.forEach((field: string) => {
        if (!componentState.props[field]) {
          errors.push(`${field} 是必填字段`);
        }
      });
    }

    // 自定义验证规则
    if (componentState.validation?.rules) {
      componentState.validation.rules.forEach((rule) => {
        const value = componentState.props[rule.field];
        if (!rule.validator(value)) {
          errors.push(rule.message);
        }
      });
    }

    return errors;
  }, []);

  const validateAllComponents = useCallback(() => {
    const errors: Record<string, string[]> = {};

    Object.entries(state.components).forEach(([componentId, componentState]) => {
      const componentErrors = validateComponent(componentId, componentState);
      if (componentErrors.length > 0) {
        errors[componentId] = componentErrors;
      }
    });

    setValidationErrors(errors);
    return errors;
  }, [state.components, validateComponent]);

  const isValid = useMemo(() => {
    return Object.keys(validationErrors).length === 0;
  }, [validationErrors]);

  const getComponentErrors = useCallback(
    (componentId: string) => {
      return validationErrors[componentId] || [];
    },
    [validationErrors],
  );

  return {
    validationErrors,
    isValid,
    validateComponent,
    validateAllComponents,
    getComponentErrors,
  };
}

/**
 * 使用状态同步
 *
 * 用于与外部系统同步状态
 */
export function useStateSync(syncConfig?: {
  endpoint?: string;
  interval?: number;
  onSync?: (state: PageState) => void;
  onError?: (error: Error) => void;
}) {
  const { stateManager } = useStateContext();
  const [isSyncing, setIsSyncing] = useState(false);
  const [lastSyncTime, setLastSyncTime] = useState<Date | null>(null);
  const [syncError, setSyncError] = useState<Error | null>(null);

  const syncState = useCallback(async () => {
    if (!syncConfig?.endpoint) return;

    try {
      setIsSyncing(true);
      setSyncError(null);

      const state = stateManager.getState();
      const response = await fetch(syncConfig.endpoint, {
        method: 'post',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify(state),
      });

      if (!response.ok) {
        throw new Error(`同步失败: ${response.statusText}`);
      }

      setLastSyncTime(new Date());
      syncConfig.onSync?.(state);
    } catch (error) {
      const syncError = error instanceof Error ? error : new Error('同步失败');
      setSyncError(syncError);
      syncConfig.onError?.(syncError);
    } finally {
      setIsSyncing(false);
    }
  }, [stateManager, syncConfig]);

  // 自动同步
  useEffect(() => {
    if (!syncConfig?.interval) return;

    const intervalId = setInterval(syncState, syncConfig.interval);
    return () => clearInterval(intervalId);
  }, [syncState, syncConfig?.interval]);

  return {
    isSyncing,
    lastSyncTime,
    syncError,
    syncState,
  };
}

/**
 * 使用状态调试
 */
export function useStateDebug(enabled: boolean = process.env.NODE_ENV === 'development') {
  const { stateManager } = useStateContext();
  const [debugInfo, setDebugInfo] = useState<{
    stateChanges: StateChangeEvent[];
    performance: {
      renderCount: number;
      lastRenderTime: number;
    };
  }>({
    stateChanges: [],
    performance: {
      renderCount: 0,
      lastRenderTime: 0,
    },
  });

  useEffect(() => {
    if (!enabled) return;

    const handleStateChange = (event: StateChangeEvent) => {
      setDebugInfo((prev) => ({
        ...prev,
        stateChanges: [...prev.stateChanges.slice(-99), event], // 保留最近100次变更
        performance: {
          renderCount: prev.performance.renderCount + 1,
          lastRenderTime: Date.now(),
        },
      }));
    };

    stateManager.on('stateChange', handleStateChange);
    return () => stateManager.off('stateChange', handleStateChange);
  }, [stateManager, enabled]);

  const logState = useCallback(() => {
    if (enabled) {
      console.group('🔍 State Debug Info');
      console.log('Current State:', stateManager.getState());
      console.log('Recent Changes:', debugInfo.stateChanges.slice(-10));
      console.log('Performance:', debugInfo.performance);
      console.groupEnd();
    }
  }, [stateManager, debugInfo, enabled]);

  const clearDebugInfo = useCallback(() => {
    setDebugInfo({
      stateChanges: [],
      performance: {
        renderCount: 0,
        lastRenderTime: 0,
      },
    });
  }, []);

  return {
    debugInfo,
    logState,
    clearDebugInfo,
  };
}

/**
 * 使用状态快照
 */
export function useStateSnapshot() {
  const { stateManager } = useStateContext();
  const [snapshots, setSnapshots] = useState<
    Array<{
      id: string;
      name: string;
      state: PageState;
      timestamp: Date;
    }>
  >([]);

  const createSnapshot = useCallback(
    (name: string) => {
      const snapshot = {
        id: `snapshot-${Date.now()}`,
        name,
        state: stateManager.getState(),
        timestamp: new Date(),
      };

      setSnapshots((prev) => [...prev, snapshot]);
      return snapshot.id;
    },
    [stateManager],
  );

  const restoreSnapshot = useCallback(
    (snapshotId: string) => {
      const snapshot = snapshots.find((s) => s.id === snapshotId);
      if (snapshot) {
        stateManager.setState(snapshot.state, 'snapshot-restore');
      }
    },
    [snapshots, stateManager],
  );

  const deleteSnapshot = useCallback((snapshotId: string) => {
    setSnapshots((prev) => prev.filter((s) => s.id !== snapshotId));
  }, []);

  const clearSnapshots = useCallback(() => {
    setSnapshots([]);
  }, []);

  return {
    snapshots,
    createSnapshot,
    restoreSnapshot,
    deleteSnapshot,
    clearSnapshots,
  };
}

/**
 * 使用状态比较
 */
export function useStateComparison() {
  const { state } = useStateContext();
  const previousStateRef = useRef<PageState>(state);
  const [changes, setChanges] = useState<
    Array<{
      path: string;
      oldValue: any;
      newValue: any;
      type: 'added' | 'modified' | 'deleted';
    }>
  >([]);

  useEffect(() => {
    const compareStates = (oldState: PageState, newState: PageState) => {
      const changes: Array<{
        path: string;
        oldValue: any;
        newValue: any;
        type: 'added' | 'modified' | 'deleted';
      }> = [];

      const compare = (obj1: any, obj2: any, path: string = '') => {
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
              oldValue: val1,
              newValue: val2,
              type: 'added',
            });
          } else if (val1 !== undefined && val2 === undefined) {
            changes.push({
              path: currentPath,
              oldValue: val1,
              newValue: val2,
              type: 'deleted',
            });
          } else if (val1 !== val2) {
            if (
              typeof val1 === 'object' &&
              typeof val2 === 'object' &&
              val1 !== null &&
              val2 !== null
            ) {
              compare(val1, val2, currentPath);
            } else {
              changes.push({
                path: currentPath,
                oldValue: val1,
                newValue: val2,
                type: 'modified',
              });
            }
          }
        });
      };

      compare(oldState, newState);
      return changes;
    };

    const newChanges = compareStates(previousStateRef.current, state);
    setChanges(newChanges);
    previousStateRef.current = state;
  }, [state]);

  return {
    changes,
    hasChanges: changes.length > 0,
  };
}
