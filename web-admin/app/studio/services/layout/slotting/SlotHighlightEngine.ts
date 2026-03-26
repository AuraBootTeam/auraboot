/**
 * 智能插槽高亮引擎
 * 提供拖拽过程中的插槽高亮和视觉反馈功能
 */

export interface SlotHighlightConfig {
  // 高亮样式配置
  highlightColor: string;
  highlightOpacity: number;
  highlightBorderWidth: number;
  highlightBorderStyle: 'solid' | 'dashed' | 'dotted';

  // 动画配置
  animationDuration: number;
  animationEasing: string;
  enablePulseAnimation: boolean;
  pulseInterval: number;

  // 检测配置
  detectionRadius: number;
  snapThreshold: number;
  enableSmartDetection: boolean;

  // 视觉反馈配置
  showDropZoneIndicator: boolean;
  showGridLines: boolean;
  showDimensions: boolean;
  showTooltips: boolean;

  // 性能配置
  throttleDelay: number;
  maxHighlightedSlots: number;
  enableVirtualization: boolean;
}

export interface SlotInfo {
  id: string;
  element: HTMLElement;
  bounds: DOMRect;
  gridArea: {
    row: number;
    column: number;
    rowSpan: number;
    columnSpan: number;
  };
  type: 'empty' | 'occupied' | 'partial';
  priority: number;
  metadata?: Record<string, any>;
}

export interface HighlightState {
  activeSlots: Set<string>;
  hoveredSlot: string | null;
  targetSlot: string | null;
  isHighlighting: boolean;
  lastUpdate: number;
}

export interface SlotHighlightResult {
  highlightedSlots: SlotInfo[];
  bestSlot: SlotInfo | null;
  confidence: number;
  suggestions: SlotInfo[];
}

export interface SlotHighlightEngineEvents {
  onSlotHighlight: (slots: SlotInfo[]) => void;
  onSlotHover: (slot: SlotInfo | null) => void;
  onSlotSelect: (slot: SlotInfo | null) => void;
  onHighlightStart: () => void;
  onHighlightEnd: () => void;
  onError: (error: Error) => void;
}

export class SlotHighlightEngine {
  private config: SlotHighlightConfig;
  private container: HTMLElement | null = null;
  private slots: Map<string, SlotInfo> = new Map();
  private state: HighlightState;
  private events: Partial<SlotHighlightEngineEvents> = {};

  // 性能优化
  private throttleTimer: number | null = null;
  private animationFrame: number | null = null;
  private observer: IntersectionObserver | null = null;

  // 视觉元素
  private highlightElements: Map<string, HTMLElement> = new Map();
  private gridLinesElement: HTMLElement | null = null;
  private tooltipElement: HTMLElement | null = null;

  constructor(config: Partial<SlotHighlightConfig> = {}) {
    this.config = {
      highlightColor: '#3b82f6',
      highlightOpacity: 0.3,
      highlightBorderWidth: 2,
      highlightBorderStyle: 'solid',
      animationDuration: 200,
      animationEasing: 'ease-out',
      enablePulseAnimation: true,
      pulseInterval: 1000,
      detectionRadius: 20,
      snapThreshold: 10,
      enableSmartDetection: true,
      showDropZoneIndicator: true,
      showGridLines: true,
      showDimensions: false,
      showTooltips: true,
      throttleDelay: 16,
      maxHighlightedSlots: 50,
      enableVirtualization: true,
      ...config,
    };

    this.state = {
      activeSlots: new Set(),
      hoveredSlot: null,
      targetSlot: null,
      isHighlighting: false,
      lastUpdate: 0,
    };

    this.initializeObserver();
  }

  /**
   * 初始化容器
   */
  public initializeContainer(container: HTMLElement): void {
    this.container = container;
    this.setupEventListeners();
    this.createVisualElements();
  }

  /**
   * 销毁引擎
   */
  public destroy(): void {
    this.clearHighlights();
    this.removeEventListeners();
    this.destroyVisualElements();

    if (this.observer) {
      this.observer.disconnect();
      this.observer = null;
    }

    if (this.throttleTimer) {
      clearTimeout(this.throttleTimer);
    }

    if (this.animationFrame) {
      cancelAnimationFrame(this.animationFrame);
    }
  }

  /**
   * 添加插槽
   */
  public addSlot(slot: SlotInfo): void {
    this.slots.set(slot.id, slot);

    if (this.config.enableVirtualization && this.observer) {
      this.observer.observe(slot.element);
    }
  }

  /**
   * 移除插槽
   */
  public removeSlot(slotId: string): void {
    const slot = this.slots.get(slotId);
    if (slot) {
      this.slots.delete(slotId);
      this.clearSlotHighlight(slotId);

      if (this.observer) {
        this.observer.unobserve(slot.element);
      }
    }
  }

  /**
   * 更新插槽信息
   */
  public updateSlot(slotId: string, updates: Partial<SlotInfo>): void {
    const slot = this.slots.get(slotId);
    if (slot) {
      Object.assign(slot, updates);
      this.refreshSlotHighlight(slotId);
    }
  }

  /**
   * 清空所有插槽
   */
  public clearSlots(): void {
    this.clearHighlights();
    this.slots.clear();

    if (this.observer) {
      this.observer.disconnect();
      this.initializeObserver();
    }
  }

  /**
   * 开始高亮检测
   */
  public startHighlighting(dragElement: HTMLElement, dragData: any): void {
    if (this.state.isHighlighting) return;

    this.state.isHighlighting = true;
    this.state.lastUpdate = Date.now();

    this.events.onHighlightStart?.();
    this.showGridLines();
  }

  /**
   * 结束高亮检测
   */
  public endHighlighting(): void {
    if (!this.state.isHighlighting) return;

    this.state.isHighlighting = false;
    this.clearHighlights();
    this.hideGridLines();
    this.hideTooltip();

    this.events.onHighlightEnd?.();
  }

  /**
   * 更新鼠标位置并检测插槽
   */
  public updateMousePosition(x: number, y: number): SlotHighlightResult {
    if (!this.state.isHighlighting) {
      return {
        highlightedSlots: [],
        bestSlot: null,
        confidence: 0,
        suggestions: [],
      };
    }

    // 节流处理
    if (this.throttleTimer) {
      clearTimeout(this.throttleTimer);
    }

    this.throttleTimer = window.setTimeout(() => {
      this.performSlotDetection(x, y);
    }, this.config.throttleDelay);

    return this.getCurrentHighlightResult();
  }

  /**
   * 设置事件监听器
   */
  public on<K extends keyof SlotHighlightEngineEvents>(
    event: K,
    handler: SlotHighlightEngineEvents[K],
  ): void {
    this.events[event] = handler;
  }

  /**
   * 移除事件监听器
   */
  public off<K extends keyof SlotHighlightEngineEvents>(event: K): void {
    delete this.events[event];
  }

  /**
   * 更新配置
   */
  public updateConfig(updates: Partial<SlotHighlightConfig>): void {
    Object.assign(this.config, updates);
    this.refreshAllHighlights();
  }

  /**
   * 获取当前状态
   */
  public getState(): HighlightState {
    return { ...this.state };
  }

  /**
   * 获取所有插槽
   */
  public getSlots(): SlotInfo[] {
    return Array.from(this.slots.values());
  }

  /**
   * 执行插槽检测
   */
  private performSlotDetection(x: number, y: number): void {
    try {
      const candidates = this.findCandidateSlots(x, y);
      const scored = this.scoreSlots(candidates, x, y);
      const filtered = this.filterSlots(scored);

      this.updateHighlights(filtered);
      this.updateHoveredSlot(x, y);
      this.updateTooltip(x, y);

      this.state.lastUpdate = Date.now();
    } catch (error) {
      this.events.onError?.(error as Error);
    }
  }

  /**
   * 查找候选插槽
   */
  private findCandidateSlots(x: number, y: number): SlotInfo[] {
    const candidates: SlotInfo[] = [];
    const detectionRadius = this.config.detectionRadius;

    for (const slot of this.slots.values()) {
      const bounds = slot.bounds;
      const distance = this.calculateDistance(x, y, bounds);

      if (distance <= detectionRadius || this.isPointInBounds(x, y, bounds)) {
        candidates.push(slot);
      }
    }

    return candidates;
  }

  /**
   * 为插槽评分
   */
  private scoreSlots(slots: SlotInfo[], x: number, y: number): Array<SlotInfo & { score: number }> {
    return slots
      .map((slot) => {
        let score = slot.priority;

        // 距离评分
        const distance = this.calculateDistance(x, y, slot.bounds);
        score += Math.max(0, 100 - distance);

        // 类型评分
        switch (slot.type) {
          case 'empty':
            score += 50;
            break;
          case 'partial':
            score += 25;
            break;
          case 'occupied':
            score -= 25;
            break;
        }

        // 智能检测评分
        if (this.config.enableSmartDetection) {
          score += this.calculateSmartScore(slot, x, y);
        }

        return { ...slot, score };
      })
      .sort((a, b) => b.score - a.score);
  }

  /**
   * 过滤插槽
   */
  private filterSlots(scoredSlots: Array<SlotInfo & { score: number }>): SlotInfo[] {
    return scoredSlots
      .filter((slot) => slot.score > 0)
      .slice(0, this.config.maxHighlightedSlots)
      .map(({ score, ...slot }) => slot);
  }

  /**
   * 更新高亮显示
   */
  private updateHighlights(slots: SlotInfo[]): void {
    // 清除旧的高亮
    const currentSlots = new Set(slots.map((s) => s.id));
    for (const slotId of this.state.activeSlots) {
      if (!currentSlots.has(slotId)) {
        this.clearSlotHighlight(slotId);
      }
    }

    // 添加新的高亮
    for (const slot of slots) {
      if (!this.state.activeSlots.has(slot.id)) {
        this.createSlotHighlight(slot);
      } else {
        this.updateSlotHighlight(slot);
      }
    }

    this.state.activeSlots = currentSlots;
    this.events.onSlotHighlight?.(slots);
  }

  /**
   * 创建插槽高亮
   */
  private createSlotHighlight(slot: SlotInfo): void {
    const highlight = document.createElement('div');
    highlight.className = 'slot-highlight';
    highlight.style.cssText = `
      position: absolute;
      pointer-events: none;
      background-color: ${this.config.highlightColor};
      opacity: ${this.config.highlightOpacity};
      border: ${this.config.highlightBorderWidth}px ${this.config.highlightBorderStyle} ${this.config.highlightColor};
      transition: all ${this.config.animationDuration}ms ${this.config.animationEasing};
      z-index: 1000;
    `;

    this.positionHighlight(highlight, slot.bounds);

    if (this.config.enablePulseAnimation) {
      this.addPulseAnimation(highlight);
    }

    this.container?.appendChild(highlight);
    this.highlightElements.set(slot.id, highlight);
  }

  /**
   * 更新插槽高亮
   */
  private updateSlotHighlight(slot: SlotInfo): void {
    const highlight = this.highlightElements.get(slot.id);
    if (highlight) {
      this.positionHighlight(highlight, slot.bounds);
    }
  }

  /**
   * 清除插槽高亮
   */
  private clearSlotHighlight(slotId: string): void {
    const highlight = this.highlightElements.get(slotId);
    if (highlight) {
      highlight.remove();
      this.highlightElements.delete(slotId);
    }
    this.state.activeSlots.delete(slotId);
  }

  /**
   * 清除所有高亮
   */
  private clearHighlights(): void {
    for (const highlight of this.highlightElements.values()) {
      highlight.remove();
    }
    this.highlightElements.clear();
    this.state.activeSlots.clear();
  }

  /**
   * 定位高亮元素
   */
  private positionHighlight(element: HTMLElement, bounds: DOMRect): void {
    if (!this.container) return;

    const containerBounds = this.container.getBoundingClientRect();

    element.style.left = `${bounds.left - containerBounds.left}px`;
    element.style.top = `${bounds.top - containerBounds.top}px`;
    element.style.width = `${bounds.width}px`;
    element.style.height = `${bounds.height}px`;
  }

  /**
   * 添加脉冲动画
   */
  private addPulseAnimation(element: HTMLElement): void {
    const keyframes = `
      @keyframes slot-pulse {
        0% { opacity: ${this.config.highlightOpacity}; }
        50% { opacity: ${this.config.highlightOpacity * 1.5}; }
        100% { opacity: ${this.config.highlightOpacity}; }
      }
    `;

    if (!document.getElementById('slot-pulse-keyframes')) {
      const style = document.createElement('style');
      style.id = 'slot-pulse-keyframes';
      style.textContent = keyframes;
      document.head.appendChild(style);
    }

    element.style.animation = `slot-pulse ${this.config.pulseInterval}ms infinite`;
  }

  /**
   * 更新悬停插槽
   */
  private updateHoveredSlot(x: number, y: number): void {
    let hoveredSlot: SlotInfo | null = null;

    for (const slot of this.slots.values()) {
      if (this.isPointInBounds(x, y, slot.bounds)) {
        hoveredSlot = slot;
        break;
      }
    }

    if (this.state.hoveredSlot !== hoveredSlot?.id) {
      this.state.hoveredSlot = hoveredSlot?.id || null;
      this.events.onSlotHover?.(hoveredSlot);
    }
  }

  /**
   * 显示网格线
   */
  private showGridLines(): void {
    if (!this.config.showGridLines || this.gridLinesElement) return;

    this.gridLinesElement = document.createElement('div');
    this.gridLinesElement.className = 'grid-lines';
    this.gridLinesElement.style.cssText = `
      position: absolute;
      top: 0;
      left: 0;
      right: 0;
      bottom: 0;
      pointer-events: none;
      z-index: 999;
    `;

    this.drawGridLines();
    this.container?.appendChild(this.gridLinesElement);
  }

  /**
   * 隐藏网格线
   */
  private hideGridLines(): void {
    if (this.gridLinesElement) {
      this.gridLinesElement.remove();
      this.gridLinesElement = null;
    }
  }

  /**
   * 绘制网格线
   */
  private drawGridLines(): void {
    if (!this.gridLinesElement || !this.container) return;

    // 这里可以根据实际的网格布局绘制网格线
    // 简化实现，实际应该根据CSS Grid的配置来绘制
  }

  /**
   * 更新工具提示
   */
  private updateTooltip(x: number, y: number): void {
    if (!this.config.showTooltips) return;

    const hoveredSlot = this.state.hoveredSlot ? this.slots.get(this.state.hoveredSlot) : null;

    if (hoveredSlot) {
      this.showTooltip(hoveredSlot, x, y);
    } else {
      this.hideTooltip();
    }
  }

  /**
   * 显示工具提示
   */
  private showTooltip(slot: SlotInfo, x: number, y: number): void {
    if (!this.tooltipElement) {
      this.tooltipElement = document.createElement('div');
      this.tooltipElement.className = 'slot-tooltip';
      this.tooltipElement.style.cssText = `
        position: absolute;
        background: rgba(0, 0, 0, 0.8);
        color: white;
        padding: 8px 12px;
        border-radius: 4px;
        font-size: 12px;
        pointer-events: none;
        z-index: 1001;
        white-space: nowrap;
      `;
      this.container?.appendChild(this.tooltipElement);
    }

    this.tooltipElement.textContent = `${slot.type} slot (${slot.gridArea.column}, ${slot.gridArea.row})`;
    this.tooltipElement.style.left = `${x + 10}px`;
    this.tooltipElement.style.top = `${y - 30}px`;
    this.tooltipElement.style.display = 'block';
  }

  /**
   * 隐藏工具提示
   */
  private hideTooltip(): void {
    if (this.tooltipElement) {
      this.tooltipElement.style.display = 'none';
    }
  }

  /**
   * 计算距离
   */
  private calculateDistance(x: number, y: number, bounds: DOMRect): number {
    const centerX = bounds.left + bounds.width / 2;
    const centerY = bounds.top + bounds.height / 2;
    return Math.sqrt(Math.pow(x - centerX, 2) + Math.pow(y - centerY, 2));
  }

  /**
   * 检查点是否在边界内
   */
  private isPointInBounds(x: number, y: number, bounds: DOMRect): boolean {
    return x >= bounds.left && x <= bounds.right && y >= bounds.top && y <= bounds.bottom;
  }

  /**
   * 计算智能评分
   */
  private calculateSmartScore(slot: SlotInfo, x: number, y: number): number {
    // 这里可以实现更复杂的智能评分算法
    // 比如考虑组件类型匹配、布局规则等
    return 0;
  }

  /**
   * 获取当前高亮结果
   */
  private getCurrentHighlightResult(): SlotHighlightResult {
    const highlightedSlots = Array.from(this.state.activeSlots)
      .map((id) => this.slots.get(id))
      .filter(Boolean) as SlotInfo[];

    const bestSlot = highlightedSlots[0] || null;
    const confidence = bestSlot ? 0.8 : 0;

    return {
      highlightedSlots,
      bestSlot,
      confidence,
      suggestions: highlightedSlots.slice(1, 4),
    };
  }

  /**
   * 初始化观察器
   */
  private initializeObserver(): void {
    if (!this.config.enableVirtualization) return;

    this.observer = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          const slotId = Array.from(this.slots.entries()).find(
            ([, slot]) => slot.element === entry.target,
          )?.[0];

          if (slotId) {
            if (entry.isIntersecting) {
              // 插槽进入视口
            } else {
              // 插槽离开视口，清除高亮
              this.clearSlotHighlight(slotId);
            }
          }
        }
      },
      { threshold: 0.1 },
    );
  }

  /**
   * 设置事件监听器
   */
  private setupEventListeners(): void {
    // 这里可以添加必要的事件监听器
  }

  /**
   * 移除事件监听器
   */
  private removeEventListeners(): void {
    // 清理事件监听器
  }

  /**
   * 创建视觉元素
   */
  private createVisualElements(): void {
    // 创建必要的视觉元素
  }

  /**
   * 销毁视觉元素
   */
  private destroyVisualElements(): void {
    this.hideGridLines();
    this.hideTooltip();
    this.clearHighlights();
  }

  /**
   * 刷新插槽高亮
   */
  private refreshSlotHighlight(slotId: string): void {
    const slot = this.slots.get(slotId);
    if (slot && this.state.activeSlots.has(slotId)) {
      this.updateSlotHighlight(slot);
    }
  }

  /**
   * 刷新所有高亮
   */
  private refreshAllHighlights(): void {
    for (const slotId of this.state.activeSlots) {
      this.refreshSlotHighlight(slotId);
    }
  }
}

/**
 * 创建插槽高亮引擎
 */
export function createSlotHighlightEngine(
  config?: Partial<SlotHighlightConfig>,
): SlotHighlightEngine {
  return new SlotHighlightEngine(config);
}

/**
 * 插槽高亮引擎预设配置
 */
export const SlotHighlightEnginePresets = {
  default: {
    highlightColor: '#3b82f6',
    highlightOpacity: 0.3,
    animationDuration: 200,
    enablePulseAnimation: true,
    detectionRadius: 20,
    enableSmartDetection: true,
    showGridLines: true,
    showTooltips: true,
  } as Partial<SlotHighlightConfig>,

  minimal: {
    highlightColor: '#6b7280',
    highlightOpacity: 0.2,
    animationDuration: 100,
    enablePulseAnimation: false,
    detectionRadius: 10,
    enableSmartDetection: false,
    showGridLines: false,
    showTooltips: false,
  } as Partial<SlotHighlightConfig>,

  enhanced: {
    highlightColor: '#10b981',
    highlightOpacity: 0.4,
    animationDuration: 300,
    enablePulseAnimation: true,
    detectionRadius: 30,
    enableSmartDetection: true,
    showGridLines: true,
    showTooltips: true,
    showDimensions: true,
  } as Partial<SlotHighlightConfig>,

  performance: {
    highlightColor: '#f59e0b',
    highlightOpacity: 0.25,
    animationDuration: 150,
    enablePulseAnimation: false,
    detectionRadius: 15,
    enableSmartDetection: false,
    showGridLines: false,
    showTooltips: false,
    throttleDelay: 32,
    maxHighlightedSlots: 20,
    enableVirtualization: true,
  } as Partial<SlotHighlightConfig>,
};
