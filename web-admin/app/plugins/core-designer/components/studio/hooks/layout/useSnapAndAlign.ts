/**
 * 吸附和对齐Hook
 * 集成吸附引擎和对齐系统的React Hook
 */

import { useRef, useEffect, useState, useCallback } from 'react';
import {
  SnapEngine,
  type SnapConfig,
  type SnapResult,
  SnapEnginePresets,
} from '~/plugins/core-designer/components/studio/services/layout/snap/SnapEngine';
import {
  AlignmentSystem,
  type AlignmentConfig,
  type AlignmentResult,
  AlignmentSystemPresets,
} from '~/plugins/core-designer/components/studio/services/layout/alignment/AlignmentSystem';

export interface SnapAndAlignConfig {
  /** 吸附配置 */
  snap: Partial<SnapConfig>;
  /** 对齐配置 */
  alignment: Partial<AlignmentConfig>;
  /** 是否启用吸附 */
  enableSnap: boolean;
  /** 是否启用对齐 */
  enableAlignment: boolean;
  /** 优先级：'snap' | 'alignment' | 'both' */
  priority: 'snap' | 'alignment' | 'both';
}

export interface SnapAndAlignState {
  /** 是否正在吸附 */
  isSnapping: boolean;
  /** 是否正在对齐 */
  isAligning: boolean;
  /** 当前吸附结果 */
  snapResult: SnapResult | null;
  /** 当前对齐结果 */
  alignmentResult: AlignmentResult | null;
  /** 最终位置 */
  finalPosition: { x: number; y: number } | null;
}

export interface SnapAndAlignActions {
  /** 开始吸附和对齐 */
  start: () => void;
  /** 结束吸附和对齐 */
  end: () => void;
  /** 计算吸附和对齐 */
  calculate: (
    element: HTMLElement,
    position: { x: number; y: number },
    size: { width: number; height: number },
  ) => { x: number; y: number };
  /** 更新配置 */
  updateConfig: (config: Partial<SnapAndAlignConfig>) => void;
  /** 切换吸附 */
  toggleSnap: () => void;
  /** 切换对齐 */
  toggleAlignment: () => void;
}

/**
 * 吸附和对齐Hook
 */
export function useSnapAndAlign(
  containerRef: React.RefObject<HTMLElement>,
  initialConfig: Partial<SnapAndAlignConfig> = {},
): [SnapAndAlignState, SnapAndAlignActions] {
  const snapEngineRef = useRef<SnapEngine | null>(null);
  const alignmentSystemRef = useRef<AlignmentSystem | null>(null);

  const [config, setConfig] = useState<SnapAndAlignConfig>({
    snap: SnapEnginePresets.default,
    alignment: AlignmentSystemPresets.default,
    enableSnap: true,
    enableAlignment: true,
    priority: 'both',
    ...initialConfig,
  });

  const [state, setState] = useState<SnapAndAlignState>({
    isSnapping: false,
    isAligning: false,
    snapResult: null,
    alignmentResult: null,
    finalPosition: null,
  });

  // 初始化引擎
  useEffect(() => {
    if (!containerRef.current) return;

    const container = containerRef.current;

    // 初始化吸附引擎
    if (config.enableSnap) {
      snapEngineRef.current = new SnapEngine(container, config.snap, {
        onSnapStart: () => setState((prev) => ({ ...prev, isSnapping: true })),
        onSnapUpdate: (result) => setState((prev) => ({ ...prev, snapResult: result })),
        onSnapEnd: () => setState((prev) => ({ ...prev, isSnapping: false, snapResult: null })),
      });
    }

    // 初始化对齐系统
    if (config.enableAlignment) {
      alignmentSystemRef.current = new AlignmentSystem(container, config.alignment, {
        onAlignmentStart: () => setState((prev) => ({ ...prev, isAligning: true })),
        onAlignmentUpdate: (result) => setState((prev) => ({ ...prev, alignmentResult: result })),
        onAlignmentEnd: () =>
          setState((prev) => ({ ...prev, isAligning: false, alignmentResult: null })),
      });
    }

    return () => {
      snapEngineRef.current?.destroy();
      alignmentSystemRef.current?.destroy();
    };
  }, [containerRef.current, config.enableSnap, config.enableAlignment]);

  // 更新配置
  useEffect(() => {
    if (snapEngineRef.current && config.enableSnap) {
      snapEngineRef.current.updateConfig(config.snap);
    }
    if (alignmentSystemRef.current && config.enableAlignment) {
      alignmentSystemRef.current.updateConfig(config.alignment);
    }
  }, [config]);

  const start = useCallback(() => {
    if (config.enableSnap && snapEngineRef.current) {
      snapEngineRef.current.startSnap();
    }
    if (config.enableAlignment && alignmentSystemRef.current) {
      alignmentSystemRef.current.startAlignment();
    }
  }, [config.enableSnap, config.enableAlignment]);

  const end = useCallback(() => {
    if (snapEngineRef.current) {
      snapEngineRef.current.endSnap();
    }
    if (alignmentSystemRef.current) {
      alignmentSystemRef.current.endAlignment();
    }
    setState((prev) => ({
      ...prev,
      isSnapping: false,
      isAligning: false,
      snapResult: null,
      alignmentResult: null,
      finalPosition: null,
    }));
  }, []);

  const calculate = useCallback(
    (
      element: HTMLElement,
      position: { x: number; y: number },
      size: { width: number; height: number },
    ): { x: number; y: number } => {
      let finalPosition = { ...position };
      let snapResult: SnapResult | null = null;
      let alignmentResult: AlignmentResult | null = null;

      // 计算吸附
      if (config.enableSnap && snapEngineRef.current) {
        snapResult = snapEngineRef.current.calculateSnap(position, size);
      }

      // 计算对齐
      if (config.enableAlignment && alignmentSystemRef.current) {
        alignmentResult = alignmentSystemRef.current.calculateAlignment(element, position, size);
      }

      // 根据优先级决定最终位置
      if (config.priority === 'snap' && snapResult?.snapped) {
        finalPosition = snapResult.position;
      } else if (config.priority === 'alignment' && alignmentResult?.aligned) {
        finalPosition = alignmentResult.position;
      } else if (config.priority === 'both') {
        // 选择距离更近的结果
        const snapDistance = snapResult?.snapped
          ? Math.sqrt(snapResult.offset.x ** 2 + snapResult.offset.y ** 2)
          : Infinity;
        const alignDistance = alignmentResult?.aligned
          ? Math.sqrt(alignmentResult.offset.x ** 2 + alignmentResult.offset.y ** 2)
          : Infinity;

        if (snapDistance <= alignDistance && snapResult?.snapped) {
          finalPosition = snapResult.position;
        } else if (alignmentResult?.aligned) {
          finalPosition = alignmentResult.position;
        }
      }

      // 更新状态
      setState((prev) => ({
        ...prev,
        snapResult,
        alignmentResult,
        finalPosition,
      }));

      return finalPosition;
    },
    [config],
  );

  const updateConfig = useCallback((newConfig: Partial<SnapAndAlignConfig>) => {
    setConfig((prev) => ({ ...prev, ...newConfig }));
  }, []);

  const toggleSnap = useCallback(() => {
    setConfig((prev) => ({ ...prev, enableSnap: !prev.enableSnap }));
  }, []);

  const toggleAlignment = useCallback(() => {
    setConfig((prev) => ({ ...prev, enableAlignment: !prev.enableAlignment }));
  }, []);

  const actions: SnapAndAlignActions = {
    start,
    end,
    calculate,
    updateConfig,
    toggleSnap,
    toggleAlignment,
  };

  return [state, actions];
}

/**
 * 简化的吸附Hook
 */
export function useSnap(
  containerRef: React.RefObject<HTMLElement>,
  config: Partial<SnapConfig> = {},
) {
  const [state, actions] = useSnapAndAlign(containerRef, {
    snap: config,
    enableSnap: true,
    enableAlignment: false,
    priority: 'snap',
  });

  return {
    isSnapping: state.isSnapping,
    snapResult: state.snapResult,
    start: actions.start,
    end: actions.end,
    calculate: actions.calculate,
  };
}

/**
 * 简化的对齐Hook
 */
export function useAlignment(
  containerRef: React.RefObject<HTMLElement>,
  config: Partial<AlignmentConfig> = {},
) {
  const [state, actions] = useSnapAndAlign(containerRef, {
    alignment: config,
    enableSnap: false,
    enableAlignment: true,
    priority: 'alignment',
  });

  return {
    isAligning: state.isAligning,
    alignmentResult: state.alignmentResult,
    start: actions.start,
    end: actions.end,
    calculate: actions.calculate,
  };
}

/**
 * 拖拽元素的吸附和对齐Hook
 */
export function useDragSnapAlign(
  elementRef: React.RefObject<HTMLElement>,
  containerRef: React.RefObject<HTMLElement>,
  options: {
    config?: Partial<SnapAndAlignConfig>;
    onPositionChange?: (position: { x: number; y: number }) => void;
    enabled?: boolean;
  } = {},
) {
  const { config = {}, onPositionChange, enabled = true } = options;
  const [state, actions] = useSnapAndAlign(containerRef, config);
  const [isDragging, setIsDragging] = useState(false);

  useEffect(() => {
    if (!elementRef.current || !enabled) return;

    const element = elementRef.current;
    let startPosition = { x: 0, y: 0 };
    let currentPosition = { x: 0, y: 0 };

    const handleDragStart = (event: DragEvent) => {
      setIsDragging(true);
      const rect = element.getBoundingClientRect();
      startPosition = { x: rect.left, y: rect.top };
      currentPosition = startPosition;
      actions.start();
    };

    const handleDrag = (event: DragEvent) => {
      if (!isDragging) return;

      const newPosition = {
        x: event.clientX - startPosition.x + currentPosition.x,
        y: event.clientY - startPosition.y + currentPosition.y,
      };

      const rect = element.getBoundingClientRect();
      const size = { width: rect.width, height: rect.height };

      const finalPosition = actions.calculate(element, newPosition, size);

      currentPosition = finalPosition;
      onPositionChange?.(finalPosition);
    };

    const handleDragEnd = () => {
      setIsDragging(false);
      actions.end();
    };

    element.addEventListener('dragstart', handleDragStart);
    element.addEventListener('drag', handleDrag);
    element.addEventListener('dragend', handleDragEnd);

    return () => {
      element.removeEventListener('dragstart', handleDragStart);
      element.removeEventListener('drag', handleDrag);
      element.removeEventListener('dragend', handleDragEnd);
    };
  }, [elementRef.current, enabled, isDragging, actions, onPositionChange]);

  return {
    isDragging,
    state,
    actions,
  };
}

/**
 * 吸附和对齐配置Hook
 */
export function useSnapAlignConfig() {
  const [snapPreset, setSnapPreset] = useState<keyof typeof SnapEnginePresets>('default');
  const [alignPreset, setAlignPreset] = useState<keyof typeof AlignmentSystemPresets>('default');
  const [customConfig, setCustomConfig] = useState<Partial<SnapAndAlignConfig>>({});

  const getConfig = useCallback((): SnapAndAlignConfig => {
    return {
      snap: { ...SnapEnginePresets[snapPreset], ...customConfig.snap },
      alignment: { ...AlignmentSystemPresets[alignPreset], ...customConfig.alignment },
      enableSnap: customConfig.enableSnap ?? true,
      enableAlignment: customConfig.enableAlignment ?? true,
      priority: customConfig.priority ?? 'both',
    };
  }, [snapPreset, alignPreset, customConfig]);

  const updateSnapPreset = useCallback((preset: keyof typeof SnapEnginePresets) => {
    setSnapPreset(preset);
  }, []);

  const updateAlignPreset = useCallback((preset: keyof typeof AlignmentSystemPresets) => {
    setAlignPreset(preset);
  }, []);

  const updateCustomConfig = useCallback((config: Partial<SnapAndAlignConfig>) => {
    setCustomConfig((prev) => ({ ...prev, ...config }));
  }, []);

  return {
    snapPreset,
    alignPreset,
    customConfig,
    config: getConfig(),
    updateSnapPreset,
    updateAlignPreset,
    updateCustomConfig,
  };
}
