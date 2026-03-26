/**
 * 跨列拖拽调整 Hook
 * 提供跨列拖拽调整功能的 React Hook
 */

import { useRef, useEffect, useCallback, useState } from 'react';
import {
  CrossColumnDragEngine,
  type CrossColumnDragConfig,
  type ResizeTarget,
  type ResizeOperation,
  type CrossColumnDragResult,
  type CrossColumnDragEngineEvents,
  createCrossColumnDragEngine,
  CrossColumnDragEnginePresets,
} from '~/studio/services/layout/resize/CrossColumnDragEngine';

export interface CrossColumnDragState {
  isResizing: boolean;
  currentOperation: ResizeOperation | null;
  lastResult: CrossColumnDragResult | null;
  conflicts: string[];
  hoveredHandle: string | null;
  targets: ResizeTarget[];
}

export interface CrossColumnDragActions {
  addTarget: (target: ResizeTarget) => void;
  removeTarget: (targetId: string) => void;
  updateTarget: (targetId: string, updates: Partial<ResizeTarget>) => void;
  updateConfig: (updates: Partial<CrossColumnDragConfig>) => void;
  clearTargets: () => void;
  getTargets: () => ResizeTarget[];
  getCurrentOperation: () => ResizeOperation | null;
}

export interface UseCrossColumnDragOptions {
  config?: Partial<CrossColumnDragConfig>;
  container?: HTMLElement | null;
  onResizeStart?: (operation: ResizeOperation) => void;
  onResizeMove?: (operation: ResizeOperation, result: CrossColumnDragResult) => void;
  onResizeEnd?: (result: CrossColumnDragResult) => void;
  onHandleHover?: (handleId: string | null) => void;
  onConflictDetected?: (conflicts: string[]) => void;
  autoUpdateTargets?: boolean;
}

export function useCrossColumnDrag(options: UseCrossColumnDragOptions = {}) {
  const {
    config = CrossColumnDragEnginePresets.default,
    container,
    onResizeStart,
    onResizeMove,
    onResizeEnd,
    onHandleHover,
    onConflictDetected,
    autoUpdateTargets = true,
  } = options;

  const engineRef = useRef<CrossColumnDragEngine | null>(null);
  const [state, setState] = useState<CrossColumnDragState>({
    isResizing: false,
    currentOperation: null,
    lastResult: null,
    conflicts: [],
    hoveredHandle: null,
    targets: [],
  });

  // 初始化引擎
  useEffect(() => {
    const events: Partial<CrossColumnDragEngineEvents> = {
      onResizeStart: (operation) => {
        setState((prev) => ({
          ...prev,
          isResizing: true,
          currentOperation: operation,
        }));
        onResizeStart?.(operation);
      },
      onResizeMove: (operation, result) => {
        setState((prev) => ({
          ...prev,
          currentOperation: operation,
          lastResult: result,
          conflicts: result.conflicts,
        }));
        onResizeMove?.(operation, result);
      },
      onResizeEnd: (result) => {
        setState((prev) => ({
          ...prev,
          isResizing: false,
          currentOperation: null,
          lastResult: result,
          conflicts: [],
        }));
        onResizeEnd?.(result);
      },
      onHandleHover: (handle) => {
        setState((prev) => ({
          ...prev,
          hoveredHandle: handle?.id || null,
        }));
        onHandleHover?.(handle?.id || null);
      },
      onConflictDetected: (conflicts) => {
        setState((prev) => ({
          ...prev,
          conflicts,
        }));
        onConflictDetected?.(conflicts);
      },
    };

    engineRef.current = createCrossColumnDragEngine(config as CrossColumnDragConfig, events);

    return () => {
      engineRef.current?.destroy();
    };
  }, []);

  // 初始化容器
  useEffect(() => {
    if (engineRef.current && container) {
      engineRef.current.initialize(container);
    }
  }, [container]);

  // 添加目标
  const addTarget = useCallback(
    (target: ResizeTarget) => {
      if (engineRef.current) {
        engineRef.current.addTarget(target);
        if (autoUpdateTargets) {
          setState((prev) => ({
            ...prev,
            targets: [...prev.targets.filter((t) => t.id !== target.id), target],
          }));
        }
      }
    },
    [autoUpdateTargets],
  );

  // 移除目标
  const removeTarget = useCallback(
    (targetId: string) => {
      if (engineRef.current) {
        engineRef.current.removeTarget(targetId);
        if (autoUpdateTargets) {
          setState((prev) => ({
            ...prev,
            targets: prev.targets.filter((t) => t.id !== targetId),
          }));
        }
      }
    },
    [autoUpdateTargets],
  );

  // 更新目标
  const updateTarget = useCallback(
    (targetId: string, updates: Partial<ResizeTarget>) => {
      if (engineRef.current) {
        engineRef.current.updateTarget(targetId, updates);
        if (autoUpdateTargets) {
          setState((prev) => ({
            ...prev,
            targets: prev.targets.map((t) => (t.id === targetId ? { ...t, ...updates } : t)),
          }));
        }
      }
    },
    [autoUpdateTargets],
  );

  // 更新配置
  const updateConfig = useCallback((updates: Partial<CrossColumnDragConfig>) => {
    if (engineRef.current) {
      engineRef.current.updateConfig(updates);
    }
  }, []);

  // 清除所有目标
  const clearTargets = useCallback(() => {
    if (engineRef.current) {
      state.targets.forEach((target) => {
        engineRef.current?.removeTarget(target.id);
      });
      setState((prev) => ({
        ...prev,
        targets: [],
      }));
    }
  }, [state.targets]);

  // 获取所有目标
  const getTargets = useCallback((): ResizeTarget[] => {
    return engineRef.current?.getTargets() || [];
  }, []);

  // 获取当前操作
  const getCurrentOperation = useCallback((): ResizeOperation | null => {
    return engineRef.current?.getCurrentOperation() || null;
  }, []);

  const actions: CrossColumnDragActions = {
    addTarget,
    removeTarget,
    updateTarget,
    updateConfig,
    clearTargets,
    getTargets,
    getCurrentOperation,
  };

  return { state, actions };
}

/**
 * 简化的跨列拖拽 Hook
 * 用于单个目标的快速设置
 */
export function useSimpleCrossColumnDrag(
  target: ResizeTarget | null,
  container: HTMLElement | null,
  config?: Partial<CrossColumnDragConfig>,
) {
  const { state, actions } = useCrossColumnDrag({
    config,
    container,
    autoUpdateTargets: true,
  });

  // 同步目标
  useEffect(() => {
    if (target) {
      actions.addTarget(target);
    }

    return () => {
      if (target) {
        actions.removeTarget(target.id);
      }
    };
  }, [target, actions]);

  return {
    isResizing: state.isResizing,
    currentOperation: state.currentOperation,
    conflicts: state.conflicts,
    updateTarget: (updates: Partial<ResizeTarget>) => {
      if (target) {
        actions.updateTarget(target.id, updates);
      }
    },
    updateConfig: actions.updateConfig,
  };
}

/**
 * 批量跨列拖拽 Hook
 * 用于管理多个目标
 */
export function useBatchCrossColumnDrag(
  targets: ResizeTarget[],
  container: HTMLElement | null,
  config?: Partial<CrossColumnDragConfig>,
) {
  const { state, actions } = useCrossColumnDrag({
    config,
    container,
    autoUpdateTargets: true,
  });

  // 同步目标列表
  useEffect(() => {
    // 清除现有目标
    actions.clearTargets();

    // 添加新目标
    targets.forEach((target) => {
      actions.addTarget(target);
    });
  }, [targets, actions]);

  return {
    state,
    actions,
    addTarget: actions.addTarget,
    removeTarget: actions.removeTarget,
    updateTarget: actions.updateTarget,
    updateConfig: actions.updateConfig,
  };
}

/**
 * 跨列拖拽配置 Hook
 * 用于管理配置预设和自定义配置
 */
export function useCrossColumnDragConfig(initialConfig?: Partial<CrossColumnDragConfig>) {
  const [config, setConfig] = useState<CrossColumnDragConfig>({
    ...CrossColumnDragEnginePresets.default,
    ...initialConfig,
  });

  const updateConfig = useCallback((updates: Partial<CrossColumnDragConfig>) => {
    setConfig((prev) => ({ ...prev, ...updates }));
  }, []);

  const resetConfig = useCallback(() => {
    setConfig({ ...CrossColumnDragEnginePresets.default, ...initialConfig });
  }, [initialConfig]);

  const applyPreset = useCallback(
    (preset: keyof typeof CrossColumnDragEnginePresets) => {
      setConfig({ ...CrossColumnDragEnginePresets[preset], ...initialConfig });
    },
    [initialConfig],
  );

  return {
    config,
    updateConfig,
    resetConfig,
    applyPreset,
    presets: CrossColumnDragEnginePresets,
  };
}

/**
 * 跨列拖拽目标管理 Hook
 * 用于管理目标的创建、更新和删除
 */
export function useCrossColumnDragTargets() {
  const [targets, setTargets] = useState<ResizeTarget[]>([]);

  const addTarget = useCallback((target: ResizeTarget) => {
    setTargets((prev) => [...prev.filter((t) => t.id !== target.id), target]);
  }, []);

  const removeTarget = useCallback((targetId: string) => {
    setTargets((prev) => prev.filter((t) => t.id !== targetId));
  }, []);

  const updateTarget = useCallback((targetId: string, updates: Partial<ResizeTarget>) => {
    setTargets((prev) => prev.map((t) => (t.id === targetId ? { ...t, ...updates } : t)));
  }, []);

  const clearTargets = useCallback(() => {
    setTargets([]);
  }, []);

  const getTarget = useCallback(
    (targetId: string): ResizeTarget | undefined => {
      return targets.find((t) => t.id === targetId);
    },
    [targets],
  );

  const createTarget = useCallback(
    (
      id: string,
      element: HTMLElement,
      gridArea: ResizeTarget['gridArea'],
      options: Partial<Omit<ResizeTarget, 'id' | 'element' | 'gridArea'>> = {},
    ): ResizeTarget => {
      return {
        id,
        element,
        gridArea,
        minWidth: 50,
        minHeight: 50,
        resizable: { column: true, row: true },
        ...options,
      };
    },
    [],
  );

  return {
    targets,
    addTarget,
    removeTarget,
    updateTarget,
    clearTargets,
    getTarget,
    createTarget,
  };
}

/**
 * 跨列拖拽结果处理 Hook
 * 用于处理拖拽结果和状态更新
 */
export function useCrossColumnDragResult() {
  const [results, setResults] = useState<CrossColumnDragResult[]>([]);
  const [currentResult, setCurrentResult] = useState<CrossColumnDragResult | null>(null);

  const addResult = useCallback((result: CrossColumnDragResult) => {
    setResults((prev) => [...prev, result]);
    setCurrentResult(result);
  }, []);

  const clearResults = useCallback(() => {
    setResults([]);
    setCurrentResult(null);
  }, []);

  const getResultsForTarget = useCallback(
    (targetId: string): CrossColumnDragResult[] => {
      return results.filter((r) => r.targetId === targetId);
    },
    [results],
  );

  const getLastResultForTarget = useCallback(
    (targetId: string): CrossColumnDragResult | null => {
      const targetResults = getResultsForTarget(targetId);
      return targetResults.length > 0 ? targetResults[targetResults.length - 1] : null;
    },
    [getResultsForTarget],
  );

  const hasConflicts = useCallback(
    (targetId?: string): boolean => {
      if (targetId) {
        const lastResult = getLastResultForTarget(targetId);
        return lastResult ? lastResult.conflicts.length > 0 : false;
      }
      return currentResult ? currentResult.conflicts.length > 0 : false;
    },
    [currentResult, getLastResultForTarget],
  );

  return {
    results,
    currentResult,
    addResult,
    clearResults,
    getResultsForTarget,
    getLastResultForTarget,
    hasConflicts,
  };
}
