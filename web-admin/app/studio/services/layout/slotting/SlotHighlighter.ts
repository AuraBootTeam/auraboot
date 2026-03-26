/**
 * 插槽高亮器
 * 负责管理拖拽过程中的视觉反馈和高亮效果
 */

import type { DropZone } from '~/studio/services/layout/slotting/DropZoneManager';

export interface HighlightStyle {
  /** 边框颜色 */
  borderColor: string;
  /** 边框宽度 */
  borderWidth: number;
  /** 边框样式 */
  borderStyle: 'solid' | 'dashed' | 'dotted';
  /** 背景颜色 */
  backgroundColor: string;
  /** 阴影 */
  boxShadow: string;
  /** 透明度 */
  opacity: number;
  /** 动画持续时间 */
  animationDuration: number;
  /** 圆角 */
  borderRadius: number;
}

export interface HighlightConfig {
  /** 默认高亮样式 */
  default: HighlightStyle;
  /** 激活状态样式 */
  active: HighlightStyle;
  /** 可接受状态样式 */
  acceptable: HighlightStyle;
  /** 不可接受状态样式 */
  rejected: HighlightStyle;
  /** 插入位置样式 */
  insertion: HighlightStyle;
  /** 网格线样式 */
  gridLine: HighlightStyle;
}

export interface HighlightElement {
  /** 高亮元素 */
  element: HTMLElement;
  /** 对应的插槽 */
  zone: DropZone;
  /** 高亮类型 */
  type: 'default' | 'active' | 'acceptable' | 'rejected' | 'insertion' | 'gridLine';
  /** 是否正在动画 */
  animating: boolean;
}

/**
 * 插槽高亮器
 */
export class SlotHighlighter {
  private config: HighlightConfig;
  private highlights: Map<string, HighlightElement> = new Map();
  private container: HTMLElement;
  private gridOverlay: HTMLElement | null = null;

  constructor(container: HTMLElement, config?: Partial<HighlightConfig>) {
    this.container = container;
    this.config = this.createDefaultConfig(config);
    this.initializeContainer();
  }

  /**
   * 创建默认配置
   */
  private createDefaultConfig(customConfig?: Partial<HighlightConfig>): HighlightConfig {
    const defaultStyle: HighlightStyle = {
      borderColor: '#3b82f6',
      borderWidth: 2,
      borderStyle: 'solid',
      backgroundColor: 'rgba(59, 130, 246, 0.1)',
      boxShadow: '0 0 0 1px rgba(59, 130, 246, 0.2)',
      opacity: 1,
      animationDuration: 200,
      borderRadius: 4,
    };

    const baseConfig: HighlightConfig = {
      default: { ...defaultStyle },
      active: {
        ...defaultStyle,
        borderColor: '#10b981',
        backgroundColor: 'rgba(16, 185, 129, 0.15)',
        boxShadow: '0 0 0 2px rgba(16, 185, 129, 0.3), 0 4px 12px rgba(16, 185, 129, 0.2)',
        borderWidth: 3,
      },
      acceptable: {
        ...defaultStyle,
        borderColor: '#06b6d4',
        backgroundColor: 'rgba(6, 182, 212, 0.1)',
        boxShadow: '0 0 0 1px rgba(6, 182, 212, 0.2)',
      },
      rejected: {
        ...defaultStyle,
        borderColor: '#ef4444',
        backgroundColor: 'rgba(239, 68, 68, 0.1)',
        boxShadow: '0 0 0 1px rgba(239, 68, 68, 0.2)',
        borderStyle: 'dashed',
      },
      insertion: {
        ...defaultStyle,
        borderColor: '#8b5cf6',
        backgroundColor: 'rgba(139, 92, 246, 0.2)',
        boxShadow: '0 0 8px rgba(139, 92, 246, 0.4)',
        borderWidth: 3,
        borderStyle: 'dashed',
      },
      gridLine: {
        ...defaultStyle,
        borderColor: '#d1d5db',
        backgroundColor: 'transparent',
        boxShadow: 'none',
        borderWidth: 1,
        borderStyle: 'dashed',
        opacity: 0.6,
      },
    };

    return this.mergeConfig(baseConfig, customConfig);
  }

  /**
   * 合并配置
   */
  private mergeConfig(base: HighlightConfig, custom?: Partial<HighlightConfig>): HighlightConfig {
    if (!custom) return base;

    const result = { ...base };
    for (const [key, value] of Object.entries(custom)) {
      if (value && typeof value === 'object') {
        result[key as keyof HighlightConfig] = {
          ...base[key as keyof HighlightConfig],
          ...value,
        };
      }
    }
    return result;
  }

  /**
   * 初始化容器
   */
  private initializeContainer(): void {
    // 确保容器有相对定位
    const containerStyle = getComputedStyle(this.container);
    if (containerStyle.position === 'static') {
      this.container.style.position = 'relative';
    }
  }

  /**
   * 高亮插槽
   */
  highlightZone(zone: DropZone, type: HighlightElement['type'] = 'default'): void {
    // 如果已经存在相同的高亮，先移除
    this.removeHighlight(zone.id);

    const highlightElement = this.createHighlightElement(zone, type);
    const highlight: HighlightElement = {
      element: highlightElement,
      zone,
      type,
      animating: true,
    };

    this.highlights.set(zone.id, highlight);
    this.container.appendChild(highlightElement);

    // 触发动画
    requestAnimationFrame(() => {
      this.animateIn(highlight);
    });
  }

  /**
   * 移除高亮
   */
  removeHighlight(zoneId: string): void {
    const highlight = this.highlights.get(zoneId);
    if (highlight) {
      this.animateOut(highlight, () => {
        if (highlight.element.parentNode) {
          highlight.element.parentNode.removeChild(highlight.element);
        }
        this.highlights.delete(zoneId);
      });
    }
  }

  /**
   * 更新高亮类型
   */
  updateHighlightType(zoneId: string, type: HighlightElement['type']): void {
    const highlight = this.highlights.get(zoneId);
    if (highlight && highlight.type !== type) {
      highlight.type = type;
      this.applyStyle(highlight.element, this.config[type]);
    }
  }

  /**
   * 清除所有高亮
   */
  clearAllHighlights(): void {
    const highlights = Array.from(this.highlights.values());
    for (const highlight of highlights) {
      this.removeHighlight(highlight.zone.id);
    }
  }

  /**
   * 显示网格线
   */
  showGridLines(gridConfig: {
    rows: number;
    cols: number;
    cellSize: { width: number; height: number };
  }): void {
    this.hideGridLines();

    this.gridOverlay = document.createElement('div');
    this.gridOverlay.className = 'slot-highlighter-grid-overlay';
    this.applyGridStyle(this.gridOverlay, gridConfig);

    this.container.appendChild(this.gridOverlay);
  }

  /**
   * 隐藏网格线
   */
  hideGridLines(): void {
    if (this.gridOverlay) {
      if (this.gridOverlay.parentNode) {
        this.gridOverlay.parentNode.removeChild(this.gridOverlay);
      }
      this.gridOverlay = null;
    }
  }

  /**
   * 创建高亮元素
   */
  private createHighlightElement(zone: DropZone, type: HighlightElement['type']): HTMLElement {
    const element = document.createElement('div');
    element.className = `slot-highlighter-overlay slot-highlighter-overlay--${type}`;

    // 设置位置和大小
    this.updateElementPosition(element, zone);

    // 应用样式
    this.applyStyle(element, this.config[type]);

    // 设置初始透明度为0，用于动画
    element.style.opacity = '0';
    element.style.transform = 'scale(0.95)';

    return element;
  }

  /**
   * 更新元素位置
   */
  private updateElementPosition(element: HTMLElement, zone: DropZone): void {
    const containerRect = this.container.getBoundingClientRect();
    const zoneRect = zone.bounds;

    const left = zoneRect.left - containerRect.left;
    const top = zoneRect.top - containerRect.top;
    const width = zoneRect.width;
    const height = zoneRect.height;

    element.style.position = 'absolute';
    element.style.left = `${left}px`;
    element.style.top = `${top}px`;
    element.style.width = `${width}px`;
    element.style.height = `${height}px`;
    element.style.pointerEvents = 'none';
    element.style.zIndex = '1000';
  }

  /**
   * 应用样式
   */
  private applyStyle(element: HTMLElement, style: HighlightStyle): void {
    element.style.border = `${style.borderWidth}px ${style.borderStyle} ${style.borderColor}`;
    element.style.backgroundColor = style.backgroundColor;
    element.style.boxShadow = style.boxShadow;
    element.style.borderRadius = `${style.borderRadius}px`;
    element.style.transition = `all ${style.animationDuration}ms ease-out`;
  }

  /**
   * 应用网格样式
   */
  private applyGridStyle(
    element: HTMLElement,
    gridConfig: { rows: number; cols: number; cellSize: { width: number; height: number } },
  ): void {
    const { rows, cols, cellSize } = gridConfig;
    const style = this.config.gridLine;

    element.style.position = 'absolute';
    element.style.top = '0';
    element.style.left = '0';
    element.style.width = `${cols * cellSize.width}px`;
    element.style.height = `${rows * cellSize.height}px`;
    element.style.pointerEvents = 'none';
    element.style.zIndex = '999';
    element.style.opacity = style.opacity.toString();

    // 创建网格线背景
    const gridLines = [];

    // 垂直线
    for (let i = 1; i < cols; i++) {
      gridLines.push(`${i * cellSize.width}px 0, ${i * cellSize.width}px 100%`);
    }

    // 水平线
    for (let i = 1; i < rows; i++) {
      gridLines.push(`0 ${i * cellSize.height}px, 100% ${i * cellSize.height}px`);
    }

    if (gridLines.length > 0) {
      element.style.background = `
        linear-gradient(to right, ${style.borderColor} 1px, transparent 1px),
        linear-gradient(to bottom, ${style.borderColor} 1px, transparent 1px)
      `;
      element.style.backgroundSize = `${cellSize.width}px ${cellSize.height}px`;
    }
  }

  /**
   * 动画进入
   */
  private animateIn(highlight: HighlightElement): void {
    const { element } = highlight;
    const style = this.config[highlight.type];

    element.style.opacity = style.opacity.toString();
    element.style.transform = 'scale(1)';

    setTimeout(() => {
      highlight.animating = false;
    }, style.animationDuration);
  }

  /**
   * 动画退出
   */
  private animateOut(highlight: HighlightElement, callback: () => void): void {
    const { element } = highlight;
    const style = this.config[highlight.type];

    highlight.animating = true;
    element.style.opacity = '0';
    element.style.transform = 'scale(0.95)';

    setTimeout(callback, style.animationDuration);
  }

  /**
   * 更新配置
   */
  updateConfig(newConfig: Partial<HighlightConfig>): void {
    this.config = this.mergeConfig(this.config, newConfig);

    // 更新现有高亮的样式
    for (const highlight of this.highlights.values()) {
      this.applyStyle(highlight.element, this.config[highlight.type]);
    }
  }

  /**
   * 获取高亮元素
   */
  getHighlight(zoneId: string): HighlightElement | undefined {
    return this.highlights.get(zoneId);
  }

  /**
   * 获取所有高亮
   */
  getAllHighlights(): HighlightElement[] {
    return Array.from(this.highlights.values());
  }

  /**
   * 检查是否有高亮
   */
  hasHighlight(zoneId: string): boolean {
    return this.highlights.has(zoneId);
  }

  /**
   * 获取高亮数量
   */
  getHighlightCount(): number {
    return this.highlights.size;
  }

  /**
   * 销毁高亮器
   */
  destroy(): void {
    this.clearAllHighlights();
    this.hideGridLines();
  }
}

/**
 * 创建高亮器实例
 */
export function createSlotHighlighter(
  container: HTMLElement,
  config?: Partial<HighlightConfig>,
): SlotHighlighter {
  return new SlotHighlighter(container, config);
}
