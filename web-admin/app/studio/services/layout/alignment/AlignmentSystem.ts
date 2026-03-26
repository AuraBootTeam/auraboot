/**
 * 对齐系统
 * 提供组件间的智能对齐功能
 */

export interface AlignmentTarget {
  /** 目标元素 */
  element: HTMLElement;
  /** 边界框 */
  bounds: DOMRect;
  /** 对齐类型 */
  type: 'component' | 'container' | 'guide';
  /** 优先级 */
  priority: number;
}

export interface AlignmentGuide {
  /** 辅助线类型 */
  type: 'horizontal' | 'vertical';
  /** 位置 */
  position: number;
  /** 对齐类型 */
  alignType: AlignmentType;
  /** 关联的目标 */
  targets: AlignmentTarget[];
  /** 是否显示 */
  visible: boolean;
}

export type AlignmentType =
  | 'left'
  | 'right'
  | 'top'
  | 'bottom'
  | 'center-x'
  | 'center-y'
  | 'baseline'
  | 'stretch';

export interface AlignmentConfig {
  /** 是否启用对齐 */
  enabled: boolean;
  /** 对齐阈值 */
  threshold: number;
  /** 启用的对齐类型 */
  alignTypes: AlignmentType[];
  /** 辅助线配置 */
  guides: {
    enabled: boolean;
    showDistance: boolean;
    style: {
      color: string;
      width: number;
      dashArray?: string;
    };
  };
  /** 自动对齐 */
  autoAlign: {
    enabled: boolean;
    delay: number;
  };
}

export interface AlignmentResult {
  /** 是否发生对齐 */
  aligned: boolean;
  /** 对齐后的位置 */
  position: { x: number; y: number };
  /** 对齐类型 */
  alignType: AlignmentType;
  /** 对齐目标 */
  target: AlignmentTarget;
  /** 位置偏移 */
  offset: { x: number; y: number };
}

export interface AlignmentSystemEvents {
  onAlignmentStart?: (targets: AlignmentTarget[]) => void;
  onAlignmentUpdate?: (result: AlignmentResult) => void;
  onAlignmentEnd?: () => void;
  onGuideShow?: (guides: AlignmentGuide[]) => void;
  onGuideHide?: () => void;
}

/**
 * 对齐系统类
 */
export class AlignmentSystem {
  private config: AlignmentConfig;
  private events: AlignmentSystemEvents;
  private container: HTMLElement;
  private targets: AlignmentTarget[] = [];
  private guides: AlignmentGuide[] = [];
  private guideElements: HTMLElement[] = [];
  private autoAlignTimer?: number;

  constructor(
    container: HTMLElement,
    config: Partial<AlignmentConfig> = {},
    events: AlignmentSystemEvents = {},
  ) {
    this.container = container;
    this.events = events;
    this.config = {
      enabled: true,
      threshold: 8,
      alignTypes: ['left', 'right', 'top', 'bottom', 'center-x', 'center-y'],
      guides: {
        enabled: true,
        showDistance: true,
        style: {
          color: '#f59e0b',
          width: 1,
          dashArray: '2 2',
        },
      },
      autoAlign: {
        enabled: false,
        delay: 500,
      },
      ...config,
    };

    this.initializeTargets();
  }

  /**
   * 初始化对齐目标
   */
  private initializeTargets(): void {
    this.targets = [];

    // 添加容器作为对齐目标
    const containerRect = this.container.getBoundingClientRect();
    this.targets.push({
      element: this.container,
      bounds: containerRect,
      type: 'container',
      priority: 1,
    });

    // 添加组件作为对齐目标
    const components = this.container.querySelectorAll('[data-component]');
    components.forEach((component, index) => {
      const rect = component.getBoundingClientRect();
      this.targets.push({
        element: component as HTMLElement,
        bounds: rect,
        type: 'component',
        priority: 2,
      });
    });
  }

  /**
   * 计算对齐结果
   */
  public calculateAlignment(
    element: HTMLElement,
    position: { x: number; y: number },
    size: { width: number; height: number },
  ): AlignmentResult | null {
    if (!this.config.enabled) {
      return null;
    }

    const elementBounds = {
      left: position.x,
      right: position.x + size.width,
      top: position.y,
      bottom: position.y + size.height,
      centerX: position.x + size.width / 2,
      centerY: position.y + size.height / 2,
      width: size.width,
      height: size.height,
    };

    let bestAlignment: AlignmentResult | null = null;
    let minDistance = Infinity;

    // 检查每个对齐目标
    for (const target of this.targets) {
      if (target.element === element) continue;

      const targetBounds = {
        left: target.bounds.left,
        right: target.bounds.right,
        top: target.bounds.top,
        bottom: target.bounds.bottom,
        centerX: target.bounds.left + target.bounds.width / 2,
        centerY: target.bounds.top + target.bounds.height / 2,
        width: target.bounds.width,
        height: target.bounds.height,
      };

      // 检查每种对齐类型
      for (const alignType of this.config.alignTypes) {
        const alignment = this.calculateAlignmentForType(
          elementBounds,
          targetBounds,
          alignType,
          target,
        );

        if (alignment && alignment.offset) {
          const distance = Math.sqrt(alignment.offset.x ** 2 + alignment.offset.y ** 2);

          if (distance <= this.config.threshold && distance < minDistance) {
            minDistance = distance;
            bestAlignment = alignment;
          }
        }
      }
    }

    if (bestAlignment) {
      // 生成辅助线
      this.generateGuides(bestAlignment);

      // 触发事件
      this.events.onAlignmentUpdate?.(bestAlignment);

      // 自动对齐
      if (this.config.autoAlign.enabled) {
        this.scheduleAutoAlign(bestAlignment);
      }
    }

    return bestAlignment;
  }

  /**
   * 计算特定类型的对齐
   */
  private calculateAlignmentForType(
    elementBounds: any,
    targetBounds: any,
    alignType: AlignmentType,
    target: AlignmentTarget,
  ): AlignmentResult | null {
    let alignedPosition: { x: number; y: number } | null = null;
    let offset: { x: number; y: number } = { x: 0, y: 0 };

    switch (alignType) {
      case 'left':
        alignedPosition = {
          x: targetBounds.left,
          y: elementBounds.top,
        };
        offset = {
          x: targetBounds.left - elementBounds.left,
          y: 0,
        };
        break;

      case 'right':
        alignedPosition = {
          x: targetBounds.right - elementBounds.width,
          y: elementBounds.top,
        };
        offset = {
          x: targetBounds.right - elementBounds.width - elementBounds.left,
          y: 0,
        };
        break;

      case 'top':
        alignedPosition = {
          x: elementBounds.left,
          y: targetBounds.top,
        };
        offset = {
          x: 0,
          y: targetBounds.top - elementBounds.top,
        };
        break;

      case 'bottom':
        alignedPosition = {
          x: elementBounds.left,
          y: targetBounds.bottom - elementBounds.height,
        };
        offset = {
          x: 0,
          y: targetBounds.bottom - elementBounds.height - elementBounds.top,
        };
        break;

      case 'center-x':
        alignedPosition = {
          x: targetBounds.centerX - elementBounds.width / 2,
          y: elementBounds.top,
        };
        offset = {
          x: targetBounds.centerX - elementBounds.width / 2 - elementBounds.left,
          y: 0,
        };
        break;

      case 'center-y':
        alignedPosition = {
          x: elementBounds.left,
          y: targetBounds.centerY - elementBounds.height / 2,
        };
        offset = {
          x: 0,
          y: targetBounds.centerY - elementBounds.height / 2 - elementBounds.top,
        };
        break;

      default:
        return null;
    }

    if (!alignedPosition) return null;

    return {
      aligned: true,
      position: alignedPosition,
      alignType,
      target,
      offset,
    };
  }

  /**
   * 生成辅助线
   */
  private generateGuides(alignment: AlignmentResult): void {
    if (!this.config.guides.enabled) return;

    this.clearGuides();

    const guide: AlignmentGuide = {
      type: this.getGuideType(alignment.alignType),
      position: this.getGuidePosition(alignment),
      alignType: alignment.alignType,
      targets: [alignment.target],
      visible: true,
    };

    this.guides = [guide];
    this.showGuides();
  }

  /**
   * 获取辅助线类型
   */
  private getGuideType(alignType: AlignmentType): 'horizontal' | 'vertical' {
    switch (alignType) {
      case 'left':
      case 'right':
      case 'center-x':
        return 'vertical';
      case 'top':
      case 'bottom':
      case 'center-y':
        return 'horizontal';
      default:
        return 'vertical';
    }
  }

  /**
   * 获取辅助线位置
   */
  private getGuidePosition(alignment: AlignmentResult): number {
    const { alignType, target } = alignment;

    switch (alignType) {
      case 'left':
        return target.bounds.left;
      case 'right':
        return target.bounds.right;
      case 'center-x':
        return target.bounds.left + target.bounds.width / 2;
      case 'top':
        return target.bounds.top;
      case 'bottom':
        return target.bounds.bottom;
      case 'center-y':
        return target.bounds.top + target.bounds.height / 2;
      default:
        return 0;
    }
  }

  /**
   * 显示辅助线
   */
  private showGuides(): void {
    this.guides.forEach((guide) => {
      const guideElement = this.createGuideElement(guide);
      if (guideElement) {
        this.guideElements.push(guideElement);
        document.body.appendChild(guideElement);
      }
    });

    this.events.onGuideShow?.(this.guides);
  }

  /**
   * 创建辅助线元素
   */
  private createGuideElement(guide: AlignmentGuide): HTMLElement {
    const element = document.createElement('div');
    element.className = 'alignment-guide';

    const { color, width, dashArray } = this.config.guides.style;

    element.style.position = 'fixed';
    element.style.backgroundColor = color;
    element.style.pointerEvents = 'none';
    element.style.zIndex = '9998';

    if (dashArray) {
      element.style.backgroundImage = `repeating-linear-gradient(
        ${guide.type === 'horizontal' ? '90deg' : '0deg'},
        ${color} 0px,
        ${color} ${dashArray.split(' ')[0]}px,
        transparent ${dashArray.split(' ')[0]}px,
        transparent ${dashArray.split(' ')[1]}px
      )`;
      element.style.backgroundColor = 'transparent';
    }

    if (guide.type === 'horizontal') {
      element.style.left = '0';
      element.style.right = '0';
      element.style.top = `${guide.position}px`;
      element.style.height = `${width}px`;
    } else {
      element.style.top = '0';
      element.style.bottom = '0';
      element.style.left = `${guide.position}px`;
      element.style.width = `${width}px`;
    }

    // 添加距离标签
    if (this.config.guides.showDistance) {
      const label = document.createElement('div');
      label.className = 'alignment-guide-label';
      label.textContent = guide.alignType;
      label.style.position = 'absolute';
      label.style.background = color;
      label.style.color = 'white';
      label.style.padding = '2px 6px';
      label.style.fontSize = '11px';
      label.style.borderRadius = '2px';
      label.style.whiteSpace = 'nowrap';

      if (guide.type === 'horizontal') {
        label.style.left = '10px';
        label.style.top = '-12px';
      } else {
        label.style.top = '10px';
        label.style.left = '-20px';
        label.style.transform = 'rotate(-90deg)';
        label.style.transformOrigin = 'center';
      }

      element.appendChild(label);
    }

    return element;
  }

  /**
   * 清除辅助线
   */
  private clearGuides(): void {
    this.guideElements.forEach((element) => {
      if (element.parentNode) {
        element.parentNode.removeChild(element);
      }
    });
    this.guideElements = [];
    this.guides = [];
    this.events.onGuideHide?.();
  }

  /**
   * 安排自动对齐
   */
  private scheduleAutoAlign(alignment: AlignmentResult): void {
    if (this.autoAlignTimer) {
      clearTimeout(this.autoAlignTimer);
    }

    this.autoAlignTimer = window.setTimeout(() => {
      // 这里可以触发自动对齐事件
      // 实际的对齐操作应该由外部处理
      this.events.onAlignmentUpdate?.(alignment);
    }, this.config.autoAlign.delay);
  }

  /**
   * 开始对齐
   */
  public startAlignment(): void {
    this.initializeTargets();
    this.events.onAlignmentStart?.(this.targets);
  }

  /**
   * 结束对齐
   */
  public endAlignment(): void {
    this.clearGuides();
    if (this.autoAlignTimer) {
      clearTimeout(this.autoAlignTimer);
      this.autoAlignTimer = undefined;
    }
    this.events.onAlignmentEnd?.();
  }

  /**
   * 更新配置
   */
  public updateConfig(config: Partial<AlignmentConfig>): void {
    this.config = { ...this.config, ...config };
    this.initializeTargets();
  }

  /**
   * 获取当前配置
   */
  public getConfig(): AlignmentConfig {
    return { ...this.config };
  }

  /**
   * 获取对齐目标
   */
  public getTargets(): AlignmentTarget[] {
    return [...this.targets];
  }

  /**
   * 获取当前辅助线
   */
  public getGuides(): AlignmentGuide[] {
    return [...this.guides];
  }

  /**
   * 销毁对齐系统
   */
  public destroy(): void {
    this.clearGuides();
    if (this.autoAlignTimer) {
      clearTimeout(this.autoAlignTimer);
    }
    this.targets = [];
  }
}

/**
 * 创建对齐系统实例
 */
export function createAlignmentSystem(
  container: HTMLElement,
  config?: Partial<AlignmentConfig>,
  events?: AlignmentSystemEvents,
): AlignmentSystem {
  return new AlignmentSystem(container, config, events);
}

/**
 * 对齐系统预设配置
 */
export const AlignmentSystemPresets = {
  /** 默认配置 */
  default: {
    enabled: true,
    threshold: 8,
    alignTypes: ['left', 'right', 'top', 'bottom', 'center-x', 'center-y'],
    guides: {
      enabled: true,
      showDistance: true,
      style: { color: '#f59e0b', width: 1, dashArray: '2 2' },
    },
    autoAlign: { enabled: false, delay: 500 },
  } as AlignmentConfig,

  /** 精确模式 */
  precise: {
    enabled: true,
    threshold: 4,
    alignTypes: ['left', 'right', 'top', 'bottom', 'center-x', 'center-y', 'baseline'],
    guides: {
      enabled: true,
      showDistance: true,
      style: { color: '#ef4444', width: 1 },
    },
    autoAlign: { enabled: true, delay: 300 },
  } as AlignmentConfig,

  /** 简单模式 */
  simple: {
    enabled: true,
    threshold: 12,
    alignTypes: ['left', 'right', 'top', 'bottom'],
    guides: {
      enabled: true,
      showDistance: false,
      style: { color: '#10b981', width: 2 },
    },
    autoAlign: { enabled: false, delay: 500 },
  } as AlignmentConfig,

  /** 性能模式 */
  performance: {
    enabled: true,
    threshold: 10,
    alignTypes: ['center-x', 'center-y'],
    guides: {
      enabled: false,
      showDistance: false,
      style: { color: '#6366f1', width: 1 },
    },
    autoAlign: { enabled: false, delay: 1000 },
  } as AlignmentConfig,
};
