/**
 * 拖拽区域管理器
 * 负责管理拖拽过程中的插槽高亮和区域检测
 */

export interface DropZoneConfig {
  /** 插槽高亮延迟时间 */
  highlightDelay: number;
  /** 插槽检测精度 */
  detectionPrecision: number;
  /** 是否启用智能吸附 */
  enableSnapping: boolean;
  /** 吸附距离阈值 */
  snapThreshold: number;
  /** 是否显示网格线 */
  showGridLines: boolean;
  /** 网格大小 */
  gridSize: number;
}

export interface DropZone {
  /** 区域ID */
  id: string;
  /** 区域类型 */
  type: 'container' | 'slot' | 'grid-cell' | 'between';
  /** 区域元素 */
  element: HTMLElement;
  /** 区域边界 */
  bounds: DOMRect;
  /** 位置（可选） */
  position?: { row: number; column: number; x?: number; y?: number };
  /** 尺寸（可选） */
  size?: { width: number; height: number; span?: number };
  /** 容器ID（可选） */
  containerId?: string;
  /** 元数据 */
  metadata?: Record<string, any>;
  /** 父容器ID */
  parentId?: string;
  /** 网格位置 */
  gridPosition?: {
    row: number;
    col: number;
    rowSpan?: number;
    colSpan?: number;
  };
  /** 插入位置 */
  insertIndex?: number;
  /** 是否可接受当前拖拽项 */
  canAccept: boolean;
  /** 优先级 */
  priority: number;
  /** 自定义数据 */
  data?: Record<string, any>;
}

export interface DropZoneState {
  /** 当前激活的插槽 */
  activeZone: DropZone | null;
  /** 候选插槽列表 */
  candidateZones: DropZone[];
  /** 高亮的插槽列表 */
  highlightedZones: DropZone[];
  /** 是否正在拖拽 */
  isDragging: boolean;
  /** 拖拽项信息 */
  dragItem: {
    type: string;
    data: any;
    element: HTMLElement;
  } | null;
  /** 鼠标位置 */
  mousePosition: { x: number; y: number };
}

export interface DropZoneEvents {
  'zone-enter': (zone: DropZone) => void;
  'zone-leave': (zone: DropZone) => void;
  'zone-activate': (zone: DropZone) => void;
  'zone-deactivate': (zone: DropZone) => void;
  'zones-update': (zones: DropZone[]) => void;
  'drag-start': (item: any) => void;
  'drag-end': () => void;
}

/**
 * 拖拽区域管理器
 */
export class DropZoneManager {
  private config: DropZoneConfig;
  private state: DropZoneState;
  private zones: Map<string, DropZone> = new Map();
  private eventListeners: Map<keyof DropZoneEvents, Set<Function>> = new Map();
  private highlightTimer: number | null = null;
  private updateTimer: number | null = null;

  constructor(config: Partial<DropZoneConfig> = {}) {
    this.config = {
      highlightDelay: 100,
      detectionPrecision: 5,
      enableSnapping: true,
      snapThreshold: 10,
      showGridLines: true,
      gridSize: 8,
      ...config,
    };

    this.state = {
      activeZone: null,
      candidateZones: [],
      highlightedZones: [],
      isDragging: false,
      dragItem: null,
      mousePosition: { x: 0, y: 0 },
    };

    this.initializeEventListeners();
  }

  /**
   * 初始化事件监听器
   */
  private initializeEventListeners(): void {
    // 监听鼠标移动
    document.addEventListener('mousemove', this.handleMouseMove.bind(this));

    // 监听拖拽事件
    document.addEventListener('dragover', this.handleDragOver.bind(this));
    document.addEventListener('dragenter', this.handleDragEnter.bind(this));
    document.addEventListener('dragleave', this.handleDragLeave.bind(this));
    document.addEventListener('drop', this.handleDrop.bind(this));
  }

  /**
   * 注册插槽
   */
  registerZone(zone: Omit<DropZone, 'bounds' | 'canAccept'>): void {
    const bounds = zone.element.getBoundingClientRect();
    const fullZone: DropZone = {
      ...zone,
      bounds,
      canAccept: false,
    };

    fullZone.canAccept = this.checkCanAccept(fullZone, this.state.dragItem);

    this.zones.set(zone.id, fullZone);
    this.updateCandidateZones();
    this.emit('zones-update', Array.from(this.zones.values()));
  }

  /**
   * 注销插槽
   */
  unregisterZone(zoneId: string): void {
    const zone = this.zones.get(zoneId);
    if (zone) {
      this.zones.delete(zoneId);

      // 如果是当前激活的插槽，清除激活状态
      if (this.state.activeZone?.id === zoneId) {
        this.deactivateZone();
      }

      // 从高亮列表中移除
      this.state.highlightedZones = this.state.highlightedZones.filter((z) => z.id !== zoneId);

      this.updateCandidateZones();
      this.emit('zones-update', Array.from(this.zones.values()));
    }
  }

  /**
   * 更新插槽边界
   */
  updateZoneBounds(zoneId: string): void {
    const zone = this.zones.get(zoneId);
    if (zone) {
      zone.bounds = zone.element.getBoundingClientRect();
      zone.canAccept = this.checkCanAccept(zone, this.state.dragItem);
      this.updateCandidateZones();
    }
  }

  /**
   * 批量更新所有插槽边界
   */
  updateAllZoneBounds(): void {
    for (const zone of this.zones.values()) {
      zone.bounds = zone.element.getBoundingClientRect();
      zone.canAccept = this.checkCanAccept(zone, this.state.dragItem);
    }
    this.updateCandidateZones();
  }

  /**
   * 开始拖拽
   */
  startDrag(item: { type: string; data: any; element: HTMLElement }): void {
    this.state.isDragging = true;
    this.state.dragItem = item;

    // 更新所有插槽的接受状态
    for (const zone of this.zones.values()) {
      zone.canAccept = this.checkCanAccept(zone, item);
    }

    this.updateCandidateZones();
    this.emit('drag-start', item);
  }

  /**
   * 结束拖拽
   */
  endDrag(): void {
    this.state.isDragging = false;
    this.state.dragItem = null;

    this.deactivateZone();
    this.clearHighlights();
    this.state.candidateZones = [];

    this.emit('drag-end');
  }

  /**
   * 获取当前激活的插槽
   */
  getActiveZone(): DropZone | null {
    return this.state.activeZone;
  }

  /**
   * 获取候选插槽列表
   */
  getCandidateZones(): DropZone[] {
    return this.state.candidateZones;
  }

  /**
   * 获取高亮的插槽列表
   */
  getHighlightedZones(): DropZone[] {
    return this.state.highlightedZones;
  }

  /**
   * 获取状态
   */
  getState(): DropZoneState {
    return { ...this.state };
  }

  /**
   * 更新配置
   */
  updateConfig(newConfig: Partial<DropZoneConfig>): void {
    this.config = { ...this.config, ...newConfig };
  }

  /**
   * 处理鼠标移动
   */
  private handleMouseMove(event: MouseEvent): void {
    this.state.mousePosition = { x: event.clientX, y: event.clientY };

    if (this.state.isDragging) {
      this.scheduleUpdate();
    }
  }

  /**
   * 处理拖拽悬停
   */
  private handleDragOver(event: DragEvent): void {
    event.preventDefault();
    this.state.mousePosition = { x: event.clientX, y: event.clientY };
    this.scheduleUpdate();
  }

  /**
   * 处理拖拽进入
   */
  private handleDragEnter(event: DragEvent): void {
    event.preventDefault();
  }

  /**
   * 处理拖拽离开
   */
  private handleDragLeave(event: DragEvent): void {
    // 检查是否真的离开了拖拽区域
    const rect = document.documentElement.getBoundingClientRect();
    if (
      event.clientX < rect.left ||
      event.clientX > rect.right ||
      event.clientY < rect.top ||
      event.clientY > rect.bottom
    ) {
      this.deactivateZone();
      this.clearHighlights();
    }
  }

  /**
   * 处理放置
   */
  private handleDrop(event: DragEvent): void {
    event.preventDefault();
    // 放置逻辑由外部处理，这里只负责清理状态
    this.endDrag();
  }

  /**
   * 调度更新
   */
  private scheduleUpdate(): void {
    if (this.updateTimer) {
      cancelAnimationFrame(this.updateTimer);
    }

    this.updateTimer = requestAnimationFrame(() => {
      this.updateActiveZone();
      this.updateHighlights();
    });
  }

  /**
   * 更新候选插槽
   */
  private updateCandidateZones(): void {
    if (!this.state.isDragging) {
      this.state.candidateZones = [];
      return;
    }

    this.state.candidateZones = Array.from(this.zones.values())
      .filter((zone) => zone.canAccept)
      .sort((a, b) => b.priority - a.priority);
  }

  /**
   * 更新激活插槽
   */
  private updateActiveZone(): void {
    const { x, y } = this.state.mousePosition;
    let bestZone: DropZone | null = null;
    let bestDistance = Infinity;

    for (const zone of this.state.candidateZones) {
      if (this.isPointInZone(x, y, zone)) {
        const distance = this.getDistanceToZoneCenter(x, y, zone);
        if (distance < bestDistance) {
          bestDistance = distance;
          bestZone = zone;
        }
      }
    }

    if (bestZone !== this.state.activeZone) {
      if (this.state.activeZone) {
        this.emit('zone-deactivate', this.state.activeZone);
      }

      this.state.activeZone = bestZone;

      if (bestZone) {
        this.emit('zone-activate', bestZone);
      }
    }
  }

  /**
   * 更新高亮
   */
  private updateHighlights(): void {
    if (this.highlightTimer) {
      clearTimeout(this.highlightTimer);
    }

    this.highlightTimer = window.setTimeout(() => {
      const newHighlights: DropZone[] = [];

      if (this.state.activeZone) {
        newHighlights.push(this.state.activeZone);

        // 添加相关的插槽到高亮列表
        const relatedZones = this.getRelatedZones(this.state.activeZone);
        newHighlights.push(...relatedZones);
      }

      // 检查高亮变化
      const oldHighlights = this.state.highlightedZones;
      this.state.highlightedZones = newHighlights;

      // 触发进入/离开事件
      for (const zone of oldHighlights) {
        if (!newHighlights.find((z) => z.id === zone.id)) {
          this.emit('zone-leave', zone);
        }
      }

      for (const zone of newHighlights) {
        if (!oldHighlights.find((z) => z.id === zone.id)) {
          this.emit('zone-enter', zone);
        }
      }
    }, this.config.highlightDelay);
  }

  /**
   * 取消激活插槽
   */
  private deactivateZone(): void {
    if (this.state.activeZone) {
      this.emit('zone-deactivate', this.state.activeZone);
      this.state.activeZone = null;
    }
  }

  /**
   * 清除高亮
   */
  private clearHighlights(): void {
    for (const zone of this.state.highlightedZones) {
      this.emit('zone-leave', zone);
    }
    this.state.highlightedZones = [];

    if (this.highlightTimer) {
      clearTimeout(this.highlightTimer);
      this.highlightTimer = null;
    }
  }

  /**
   * 检查点是否在插槽内
   */
  private isPointInZone(x: number, y: number, zone: DropZone): boolean {
    const { bounds } = zone;
    const precision = this.config.detectionPrecision;

    return (
      x >= bounds.left - precision &&
      x <= bounds.right + precision &&
      y >= bounds.top - precision &&
      y <= bounds.bottom + precision
    );
  }

  /**
   * 获取点到插槽中心的距离
   */
  private getDistanceToZoneCenter(x: number, y: number, zone: DropZone): number {
    const { bounds } = zone;
    const centerX = bounds.left + bounds.width / 2;
    const centerY = bounds.top + bounds.height / 2;

    return Math.sqrt(Math.pow(x - centerX, 2) + Math.pow(y - centerY, 2));
  }

  /**
   * 获取相关插槽
   */
  private getRelatedZones(zone: DropZone): DropZone[] {
    const related: DropZone[] = [];

    // 添加同一容器内的其他插槽
    if (zone.parentId) {
      for (const otherZone of this.zones.values()) {
        if (otherZone.parentId === zone.parentId && otherZone.id !== zone.id) {
          related.push(otherZone);
        }
      }
    }

    // 添加网格相邻的插槽
    if (zone.gridPosition) {
      const { row, col } = zone.gridPosition;
      for (const otherZone of this.zones.values()) {
        if (otherZone.gridPosition && otherZone.id !== zone.id) {
          const { row: otherRow, col: otherCol } = otherZone.gridPosition;
          const rowDiff = Math.abs(row - otherRow);
          const colDiff = Math.abs(col - otherCol);

          if ((rowDiff <= 1 && colDiff === 0) || (rowDiff === 0 && colDiff <= 1)) {
            related.push(otherZone);
          }
        }
      }
    }

    return related;
  }

  /**
   * 检查插槽是否可以接受拖拽项
   */
  private checkCanAccept(zone: DropZone, dragItem: any): boolean {
    if (!dragItem) return false;

    // 基本类型检查
    switch (zone.type) {
      case 'container':
        return true; // 容器通常可以接受任何组件
      case 'slot':
        return this.checkSlotCompatibility(zone, dragItem);
      case 'grid-cell':
        return this.checkGridCellCompatibility(zone, dragItem);
      case 'between':
        return true; // 插入位置通常可以接受任何组件
      default:
        return false;
    }
  }

  /**
   * 检查插槽兼容性
   */
  private checkSlotCompatibility(zone: DropZone, dragItem: any): boolean {
    // 可以根据插槽的自定义数据进行更复杂的兼容性检查
    const allowedTypes = zone.data?.allowedTypes;
    if (allowedTypes && Array.isArray(allowedTypes)) {
      return allowedTypes.includes(dragItem.type);
    }
    return true;
  }

  /**
   * 检查网格单元兼容性
   */
  private checkGridCellCompatibility(zone: DropZone, dragItem: any): boolean {
    // 检查网格单元是否已被占用
    if (zone.data?.occupied) {
      return false;
    }

    // 检查组件大小是否适合
    const componentSize = dragItem.data?.size || { width: 1, height: 1 };
    const gridPosition = zone.gridPosition;

    if (gridPosition) {
      const availableWidth = gridPosition.colSpan || 1;
      const availableHeight = gridPosition.rowSpan || 1;

      return componentSize.width <= availableWidth && componentSize.height <= availableHeight;
    }

    return true;
  }

  /**
   * 添加事件监听器
   */
  on<K extends keyof DropZoneEvents>(event: K, listener: DropZoneEvents[K]): void {
    if (!this.eventListeners.has(event)) {
      this.eventListeners.set(event, new Set());
    }
    this.eventListeners.get(event)!.add(listener);
  }

  /**
   * 移除事件监听器
   */
  off<K extends keyof DropZoneEvents>(event: K, listener: DropZoneEvents[K]): void {
    const listeners = this.eventListeners.get(event);
    if (listeners) {
      listeners.delete(listener);
    }
  }

  /**
   * 触发事件
   */
  private emit<K extends keyof DropZoneEvents>(
    event: K,
    ...args: Parameters<DropZoneEvents[K]>
  ): void {
    const listeners = this.eventListeners.get(event);
    if (listeners) {
      for (const listener of listeners) {
        try {
          (listener as any)(...args);
        } catch (error) {
          console.error(`Error in ${event} listener:`, error);
        }
      }
    }
  }

  /**
   * 销毁管理器
   */
  destroy(): void {
    // 清理定时器
    if (this.highlightTimer) {
      clearTimeout(this.highlightTimer);
    }
    if (this.updateTimer) {
      cancelAnimationFrame(this.updateTimer);
    }

    // 移除事件监听器
    document.removeEventListener('mousemove', this.handleMouseMove.bind(this));
    document.removeEventListener('dragover', this.handleDragOver.bind(this));
    document.removeEventListener('dragenter', this.handleDragEnter.bind(this));
    document.removeEventListener('dragleave', this.handleDragLeave.bind(this));
    document.removeEventListener('drop', this.handleDrop.bind(this));

    // 清理状态
    this.zones.clear();
    this.eventListeners.clear();
    this.endDrag();
  }
}

// 单例实例
export const dropZoneManager = new DropZoneManager();
