/**
 * 拖拽和放置 Hook
 * 集成 DropZoneManager 和 SlotHighlighter 提供完整的拖拽功能
 */

import { useEffect, useRef, useState, useCallback } from 'react';
import {
  DropZoneManager,
  type DropZone,
  type DropZoneConfig,
} from '~/plugins/core-designer/components/studio/services/layout/slotting/DropZoneManager';
import {
  SlotHighlighter,
  type HighlightConfig,
} from '~/plugins/core-designer/components/studio/services/layout/slotting/SlotHighlighter';

export interface DragItem {
  /** 拖拽项类型 */
  type: string;
  /** 拖拽项数据 */
  data: any;
  /** 拖拽项元素 */
  element: HTMLElement;
}

export interface DropResult {
  /** 目标插槽 */
  zone: DropZone;
  /** 拖拽项 */
  item: DragItem;
  /** 放置位置 */
  position: { x: number; y: number };
}

export interface DragAndDropConfig {
  /** 拖拽区域配置 */
  dropZone?: Partial<DropZoneConfig>;
  /** 高亮配置 */
  highlight?: Partial<HighlightConfig>;
  /** 是否启用网格线 */
  enableGridLines?: boolean;
  /** 网格配置 */
  gridConfig?: {
    rows: number;
    cols: number;
    cellSize: { width: number; height: number };
  };
}

export interface DragAndDropState {
  /** 是否正在拖拽 */
  isDragging: boolean;
  /** 当前拖拽项 */
  dragItem: DragItem | null;
  /** 当前激活的插槽 */
  activeZone: DropZone | null;
  /** 候选插槽列表 */
  candidateZones: DropZone[];
  /** 高亮的插槽列表 */
  highlightedZones: DropZone[];
  /** 鼠标位置 */
  mousePosition: { x: number; y: number };
}

export interface DragAndDropActions {
  /** 注册插槽 */
  registerZone: (zone: Omit<DropZone, 'bounds' | 'canAccept'>) => void;
  /** 注销插槽 */
  unregisterZone: (zoneId: string) => void;
  /** 更新插槽边界 */
  updateZoneBounds: (zoneId: string) => void;
  /** 批量更新所有插槽边界 */
  updateAllZoneBounds: () => void;
  /** 开始拖拽 */
  startDrag: (item: DragItem) => void;
  /** 结束拖拽 */
  endDrag: () => void;
  /** 显示网格线 */
  showGridLines: () => void;
  /** 隐藏网格线 */
  hideGridLines: () => void;
  /** 更新配置 */
  updateConfig: (config: Partial<DragAndDropConfig>) => void;
}

export interface DragAndDropCallbacks {
  /** 拖拽开始回调 */
  onDragStart?: (item: DragItem) => void;
  /** 拖拽结束回调 */
  onDragEnd?: () => void;
  /** 插槽激活回调 */
  onZoneActivate?: (zone: DropZone) => void;
  /** 插槽取消激活回调 */
  onZoneDeactivate?: (zone: DropZone) => void;
  /** 插槽进入回调 */
  onZoneEnter?: (zone: DropZone) => void;
  /** 插槽离开回调 */
  onZoneLeave?: (zone: DropZone) => void;
  /** 放置回调 */
  onDrop?: (result: DropResult) => void;
}

/**
 * 拖拽和放置 Hook
 */
export function useDragAndDrop(
  containerRef: React.RefObject<HTMLElement>,
  config: DragAndDropConfig = {},
  callbacks: DragAndDropCallbacks = {},
) {
  const dropZoneManagerRef = useRef<DropZoneManager | null>(null);
  const slotHighlighterRef = useRef<SlotHighlighter | null>(null);

  const [state, setState] = useState<DragAndDropState>({
    isDragging: false,
    dragItem: null,
    activeZone: null,
    candidateZones: [],
    highlightedZones: [],
    mousePosition: { x: 0, y: 0 },
  });

  // 初始化管理器
  useEffect(() => {
    if (!containerRef.current) return;

    const container = containerRef.current;

    // 创建拖拽区域管理器
    dropZoneManagerRef.current = new DropZoneManager(config.dropZone);

    // 创建插槽高亮器
    slotHighlighterRef.current = new SlotHighlighter(container, config.highlight);

    // 设置事件监听器
    const dropZoneManager = dropZoneManagerRef.current;

    dropZoneManager.on('drag-start', (item) => {
      setState((prev) => ({ ...prev, isDragging: true, dragItem: item }));
      callbacks.onDragStart?.(item);
    });

    dropZoneManager.on('drag-end', () => {
      setState((prev) => ({
        ...prev,
        isDragging: false,
        dragItem: null,
        activeZone: null,
        candidateZones: [],
        highlightedZones: [],
      }));
      slotHighlighterRef.current?.clearAllHighlights();
      callbacks.onDragEnd?.();
    });

    dropZoneManager.on('zone-activate', (zone) => {
      setState((prev) => ({ ...prev, activeZone: zone }));
      slotHighlighterRef.current?.highlightZone(zone, 'active');
      callbacks.onZoneActivate?.(zone);
    });

    dropZoneManager.on('zone-deactivate', (zone) => {
      setState((prev) => ({ ...prev, activeZone: null }));
      slotHighlighterRef.current?.removeHighlight(zone.id);
      callbacks.onZoneDeactivate?.(zone);
    });

    dropZoneManager.on('zone-enter', (zone) => {
      const highlightType = zone.canAccept ? 'acceptable' : 'rejected';
      slotHighlighterRef.current?.highlightZone(zone, highlightType);
      callbacks.onZoneEnter?.(zone);
    });

    dropZoneManager.on('zone-leave', (zone) => {
      slotHighlighterRef.current?.removeHighlight(zone.id);
      callbacks.onZoneLeave?.(zone);
    });

    dropZoneManager.on('zones-update', (zones) => {
      setState((prev) => ({ ...prev, candidateZones: zones }));
    });

    // 监听放置事件
    const handleDrop = (event: DragEvent) => {
      event.preventDefault();
      const activeZone = dropZoneManagerRef.current?.getActiveZone();
      const dragItem = dropZoneManagerRef.current?.getState().dragItem;

      if (activeZone && dragItem && callbacks.onDrop) {
        const result: DropResult = {
          zone: activeZone,
          item: dragItem,
          position: { x: event.clientX, y: event.clientY },
        };
        callbacks.onDrop(result);
      }
    };

    container.addEventListener('drop', handleDrop);

    return () => {
      container.removeEventListener('drop', handleDrop);
      dropZoneManagerRef.current?.destroy();
      slotHighlighterRef.current?.destroy();
    };
  }, [containerRef.current]);

  // 更新状态
  useEffect(() => {
    if (!dropZoneManagerRef.current) return;

    const updateState = () => {
      const managerState = dropZoneManagerRef.current!.getState();
      setState((prev) => ({
        ...prev,
        isDragging: managerState.isDragging,
        dragItem: managerState.dragItem,
        activeZone: managerState.activeZone,
        candidateZones: managerState.candidateZones,
        highlightedZones: managerState.highlightedZones,
        mousePosition: managerState.mousePosition,
      }));
    };

    const interval = setInterval(updateState, 16); // ~60fps
    return () => clearInterval(interval);
  }, []);

  // 显示网格线
  const showGridLines = useCallback(() => {
    if (config.enableGridLines && config.gridConfig && slotHighlighterRef.current) {
      slotHighlighterRef.current.showGridLines(config.gridConfig);
    }
  }, [config.enableGridLines, config.gridConfig]);

  // 隐藏网格线
  const hideGridLines = useCallback(() => {
    slotHighlighterRef.current?.hideGridLines();
  }, []);

  // 动作对象
  const actions: DragAndDropActions = {
    registerZone: useCallback((zone) => {
      dropZoneManagerRef.current?.registerZone(zone);
    }, []),

    unregisterZone: useCallback((zoneId) => {
      dropZoneManagerRef.current?.unregisterZone(zoneId);
    }, []),

    updateZoneBounds: useCallback((zoneId) => {
      dropZoneManagerRef.current?.updateZoneBounds(zoneId);
    }, []),

    updateAllZoneBounds: useCallback(() => {
      dropZoneManagerRef.current?.updateAllZoneBounds();
    }, []),

    startDrag: useCallback(
      (item) => {
        dropZoneManagerRef.current?.startDrag(item);
        if (config.enableGridLines) {
          showGridLines();
        }
      },
      [showGridLines, config.enableGridLines],
    ),

    endDrag: useCallback(() => {
      dropZoneManagerRef.current?.endDrag();
      hideGridLines();
    }, [hideGridLines]),

    showGridLines,
    hideGridLines,

    updateConfig: useCallback(
      (newConfig) => {
        if (newConfig.dropZone) {
          dropZoneManagerRef.current?.updateConfig(newConfig.dropZone);
        }
        if (newConfig.highlight) {
          slotHighlighterRef.current?.updateConfig(newConfig.highlight);
        }
        // 更新本地配置
        Object.assign(config, newConfig);
      },
      [config],
    ),
  };

  return {
    state,
    actions,
    managers: {
      dropZoneManager: dropZoneManagerRef.current,
      slotHighlighter: slotHighlighterRef.current,
    },
  };
}

/**
 * 简化的拖拽 Hook
 */
export function useSimpleDrag(
  containerRef: React.RefObject<HTMLElement>,
  onDrop?: (result: DropResult) => void,
) {
  return useDragAndDrop(
    containerRef,
    {
      enableGridLines: true,
      gridConfig: {
        rows: 12,
        cols: 12,
        cellSize: { width: 40, height: 40 },
      },
    },
    { onDrop },
  );
}

/**
 * 插槽注册 Hook
 */
export function useDropZone(
  elementRef: React.RefObject<HTMLElement>,
  zone: Omit<DropZone, 'element' | 'bounds' | 'canAccept'>,
  dragAndDrop: ReturnType<typeof useDragAndDrop>,
) {
  useEffect(() => {
    if (!elementRef.current) return;

    const fullZone = {
      ...zone,
      element: elementRef.current,
    };

    dragAndDrop.actions.registerZone(fullZone);

    return () => {
      dragAndDrop.actions.unregisterZone(zone.id);
    };
  }, [elementRef.current, zone.id]);

  // 更新边界
  useEffect(() => {
    if (elementRef.current) {
      dragAndDrop.actions.updateZoneBounds(zone.id);
    }
  });
}

/**
 * 拖拽项 Hook
 */
export function useDragItem(
  elementRef: React.RefObject<HTMLElement>,
  item: Omit<DragItem, 'element'>,
  dragAndDrop: ReturnType<typeof useDragAndDrop>,
) {
  useEffect(() => {
    if (!elementRef.current) return;

    const element = elementRef.current;

    const handleDragStart = (event: DragEvent) => {
      const fullItem: DragItem = {
        ...item,
        element,
      };

      dragAndDrop.actions.startDrag(fullItem);

      // 设置拖拽数据
      if (event.dataTransfer) {
        event.dataTransfer.effectAllowed = 'move';
        event.dataTransfer.setData('text/plain', JSON.stringify(item));
      }
    };

    const handleDragEnd = () => {
      dragAndDrop.actions.endDrag();
    };

    element.draggable = true;
    element.addEventListener('dragstart', handleDragStart);
    element.addEventListener('dragend', handleDragEnd);

    return () => {
      element.removeEventListener('dragstart', handleDragStart);
      element.removeEventListener('dragend', handleDragEnd);
    };
  }, [elementRef.current, item.type, item.data]);
}
