/**
 * 智能吸附引擎
 * 提供拖拽过程中的智能吸附和对齐功能
 */

export interface SnapPoint {
  /** 吸附点类型 */
  type: 'grid' | 'edge' | 'center' | 'guide' | 'component';
  /** 坐标 */
  x: number;
  y: number;
  /** 方向 */
  direction: 'horizontal' | 'vertical' | 'both';
  /** 优先级 */
  priority: number;
  /** 吸附距离阈值 */
  threshold: number;
  /** 关联元素 */
  element?: HTMLElement;
  /** 额外数据 */
  data?: any;
}

export interface SnapResult {
  /** 是否发生吸附 */
  snapped: boolean;
  /** 吸附后的位置 */
  position: { x: number; y: number };
  /** 吸附点信息 */
  snapPoints: SnapPoint[];
  /** 吸附偏移量 */
  offset: { x: number; y: number };
}

export interface SnapConfig {
  /** 是否启用吸附 */
  enabled: boolean;
  /** 网格吸附 */
  grid: {
    enabled: boolean;
    size: number;
    offset: { x: number; y: number };
  };
  /** 边缘吸附 */
  edges: {
    enabled: boolean;
    threshold: number;
    types: ('container' | 'component' | 'viewport')[];
  };
  /** 中心对齐 */
  center: {
    enabled: boolean;
    threshold: number;
    types: ('horizontal' | 'vertical' | 'both')[];
  };
  /** 辅助线 */
  guides: {
    enabled: boolean;
    threshold: number;
    showLines: boolean;
    lineStyle: {
      color: string;
      width: number;
      dashArray?: string;
    };
  };
  /** 组件对齐 */
  components: {
    enabled: boolean;
    threshold: number;
    alignTypes: ('left' | 'right' | 'top' | 'bottom' | 'center-x' | 'center-y')[];
  };
}

export interface SnapEngineEvents {
  onSnapStart?: (snapPoints: SnapPoint[]) => void;
  onSnapUpdate?: (result: SnapResult) => void;
  onSnapEnd?: () => void;
  onGuideShow?: (guides: SnapPoint[]) => void;
  onGuideHide?: () => void;
}

/**
 * 智能吸附引擎类
 */
export class SnapEngine {
  private config: SnapConfig;
  private events: SnapEngineEvents;
  private container: HTMLElement;
  private snapPoints: SnapPoint[] = [];
  private activeSnapPoints: SnapPoint[] = [];
  private guideLines: HTMLElement[] = [];

  constructor(
    container: HTMLElement,
    config: Partial<SnapConfig> = {},
    events: SnapEngineEvents = {},
  ) {
    this.container = container;
    this.events = events;
    this.config = {
      enabled: true,
      grid: {
        enabled: true,
        size: 8,
        offset: { x: 0, y: 0 },
      },
      edges: {
        enabled: true,
        threshold: 10,
        types: ['container', 'component'],
      },
      center: {
        enabled: true,
        threshold: 15,
        types: ['both'],
      },
      guides: {
        enabled: true,
        threshold: 5,
        showLines: true,
        lineStyle: {
          color: '#6366f1',
          width: 1,
          dashArray: '4 4',
        },
      },
      components: {
        enabled: true,
        threshold: 8,
        alignTypes: ['left', 'right', 'top', 'bottom', 'center-x', 'center-y'],
      },
      ...config,
    };

    this.initializeSnapPoints();
  }

  /**
   * 初始化吸附点
   */
  private initializeSnapPoints(): void {
    this.snapPoints = [];

    // 生成网格吸附点
    if (this.config.grid.enabled) {
      this.generateGridSnapPoints();
    }

    // 生成边缘吸附点
    if (this.config.edges.enabled) {
      this.generateEdgeSnapPoints();
    }

    // 生成组件吸附点
    if (this.config.components.enabled) {
      this.generateComponentSnapPoints();
    }
  }

  /**
   * 生成网格吸附点
   */
  private generateGridSnapPoints(): void {
    const rect = this.container.getBoundingClientRect();
    const { size, offset } = this.config.grid;

    for (let x = offset.x; x <= rect.width; x += size) {
      for (let y = offset.y; y <= rect.height; y += size) {
        this.snapPoints.push({
          type: 'grid',
          x: x + rect.left,
          y: y + rect.top,
          direction: 'both',
          priority: 1,
          threshold: size / 2,
        });
      }
    }
  }

  /**
   * 生成边缘吸附点
   */
  private generateEdgeSnapPoints(): void {
    const rect = this.container.getBoundingClientRect();
    const { threshold, types } = this.config.edges;

    if (types.includes('container')) {
      // 容器边缘
      this.snapPoints.push(
        {
          type: 'edge',
          x: rect.left,
          y: rect.top + rect.height / 2,
          direction: 'vertical',
          priority: 3,
          threshold,
        },
        {
          type: 'edge',
          x: rect.right,
          y: rect.top + rect.height / 2,
          direction: 'vertical',
          priority: 3,
          threshold,
        },
        {
          type: 'edge',
          x: rect.left + rect.width / 2,
          y: rect.top,
          direction: 'horizontal',
          priority: 3,
          threshold,
        },
        {
          type: 'edge',
          x: rect.left + rect.width / 2,
          y: rect.bottom,
          direction: 'horizontal',
          priority: 3,
          threshold,
        },
      );
    }

    if (types.includes('viewport')) {
      // 视口边缘
      this.snapPoints.push(
        {
          type: 'edge',
          x: 0,
          y: window.innerHeight / 2,
          direction: 'vertical',
          priority: 2,
          threshold,
        },
        {
          type: 'edge',
          x: window.innerWidth,
          y: window.innerHeight / 2,
          direction: 'vertical',
          priority: 2,
          threshold,
        },
        {
          type: 'edge',
          x: window.innerWidth / 2,
          y: 0,
          direction: 'horizontal',
          priority: 2,
          threshold,
        },
        {
          type: 'edge',
          x: window.innerWidth / 2,
          y: window.innerHeight,
          direction: 'horizontal',
          priority: 2,
          threshold,
        },
      );
    }
  }

  /**
   * 生成组件吸附点
   */
  private generateComponentSnapPoints(): void {
    const components = this.container.querySelectorAll('[data-component]');
    const { threshold, alignTypes } = this.config.components;

    components.forEach((component) => {
      const rect = component.getBoundingClientRect();

      alignTypes.forEach((alignType) => {
        let snapPoint: SnapPoint;

        switch (alignType) {
          case 'left':
            snapPoint = {
              type: 'component',
              x: rect.left,
              y: rect.top + rect.height / 2,
              direction: 'vertical',
              priority: 4,
              threshold,
              element: component as HTMLElement,
            };
            break;
          case 'right':
            snapPoint = {
              type: 'component',
              x: rect.right,
              y: rect.top + rect.height / 2,
              direction: 'vertical',
              priority: 4,
              threshold,
              element: component as HTMLElement,
            };
            break;
          case 'top':
            snapPoint = {
              type: 'component',
              x: rect.left + rect.width / 2,
              y: rect.top,
              direction: 'horizontal',
              priority: 4,
              threshold,
              element: component as HTMLElement,
            };
            break;
          case 'bottom':
            snapPoint = {
              type: 'component',
              x: rect.left + rect.width / 2,
              y: rect.bottom,
              direction: 'horizontal',
              priority: 4,
              threshold,
              element: component as HTMLElement,
            };
            break;
          case 'center-x':
            snapPoint = {
              type: 'component',
              x: rect.left + rect.width / 2,
              y: rect.top + rect.height / 2,
              direction: 'vertical',
              priority: 5,
              threshold,
              element: component as HTMLElement,
            };
            break;
          case 'center-y':
            snapPoint = {
              type: 'component',
              x: rect.left + rect.width / 2,
              y: rect.top + rect.height / 2,
              direction: 'horizontal',
              priority: 5,
              threshold,
              element: component as HTMLElement,
            };
            break;
          default:
            return;
        }

        this.snapPoints.push(snapPoint);
      });
    });
  }

  /**
   * 计算吸附结果
   */
  public calculateSnap(
    position: { x: number; y: number },
    elementSize: { width: number; height: number },
  ): SnapResult {
    if (!this.config.enabled) {
      return {
        snapped: false,
        position,
        snapPoints: [],
        offset: { x: 0, y: 0 },
      };
    }

    const candidateSnapPoints: SnapPoint[] = [];

    // 查找候选吸附点
    this.snapPoints.forEach((snapPoint) => {
      const distance = this.calculateDistance(position, snapPoint);

      if (distance <= snapPoint.threshold) {
        candidateSnapPoints.push({
          ...snapPoint,
          data: { distance },
        });
      }
    });

    // 按优先级和距离排序
    candidateSnapPoints.sort((a, b) => {
      if (a.priority !== b.priority) {
        return b.priority - a.priority;
      }
      return (a.data?.distance || 0) - (b.data?.distance || 0);
    });

    // 选择最佳吸附点
    const bestSnapPoints = this.selectBestSnapPoints(candidateSnapPoints);

    if (bestSnapPoints.length === 0) {
      return {
        snapped: false,
        position,
        snapPoints: [],
        offset: { x: 0, y: 0 },
      };
    }

    // 计算吸附后的位置
    const snappedPosition = this.calculateSnappedPosition(position, bestSnapPoints, elementSize);

    const result: SnapResult = {
      snapped: true,
      position: snappedPosition,
      snapPoints: bestSnapPoints,
      offset: {
        x: snappedPosition.x - position.x,
        y: snappedPosition.y - position.y,
      },
    };

    // 更新活动吸附点
    this.activeSnapPoints = bestSnapPoints;

    // 显示辅助线
    if (this.config.guides.enabled && this.config.guides.showLines) {
      this.showGuideLines(bestSnapPoints);
    }

    // 触发事件
    this.events.onSnapUpdate?.(result);

    return result;
  }

  /**
   * 计算两点之间的距离
   */
  private calculateDistance(pos1: { x: number; y: number }, snapPoint: SnapPoint): number {
    if (snapPoint.direction === 'horizontal') {
      return Math.abs(pos1.y - snapPoint.y);
    } else if (snapPoint.direction === 'vertical') {
      return Math.abs(pos1.x - snapPoint.x);
    } else {
      return Math.sqrt(Math.pow(pos1.x - snapPoint.x, 2) + Math.pow(pos1.y - snapPoint.y, 2));
    }
  }

  /**
   * 选择最佳吸附点
   */
  private selectBestSnapPoints(candidates: SnapPoint[]): SnapPoint[] {
    const selected: SnapPoint[] = [];
    let hasHorizontal = false;
    let hasVertical = false;

    for (const candidate of candidates) {
      if (candidate.direction === 'horizontal' && !hasHorizontal) {
        selected.push(candidate);
        hasHorizontal = true;
      } else if (candidate.direction === 'vertical' && !hasVertical) {
        selected.push(candidate);
        hasVertical = true;
      } else if (candidate.direction === 'both' && !hasHorizontal && !hasVertical) {
        selected.push(candidate);
        hasHorizontal = true;
        hasVertical = true;
      }

      if (hasHorizontal && hasVertical) {
        break;
      }
    }

    return selected;
  }

  /**
   * 计算吸附后的位置
   */
  private calculateSnappedPosition(
    originalPosition: { x: number; y: number },
    snapPoints: SnapPoint[],
    elementSize: { width: number; height: number },
  ): { x: number; y: number } {
    let x = originalPosition.x;
    let y = originalPosition.y;

    snapPoints.forEach((snapPoint) => {
      if (snapPoint.direction === 'horizontal' || snapPoint.direction === 'both') {
        y = snapPoint.y;
      }
      if (snapPoint.direction === 'vertical' || snapPoint.direction === 'both') {
        x = snapPoint.x;
      }
    });

    return { x, y };
  }

  /**
   * 显示辅助线
   */
  private showGuideLines(snapPoints: SnapPoint[]): void {
    this.hideGuideLines();

    snapPoints.forEach((snapPoint) => {
      const guideLine = this.createGuideLine(snapPoint);
      if (guideLine) {
        this.guideLines.push(guideLine);
        document.body.appendChild(guideLine);
      }
    });

    this.events.onGuideShow?.(snapPoints);
  }

  /**
   * 创建辅助线元素
   */
  private createGuideLine(snapPoint: SnapPoint): HTMLElement | null {
    const line = document.createElement('div');
    line.className = 'snap-guide-line';

    const { color, width, dashArray } = this.config.guides.lineStyle;

    line.style.position = 'fixed';
    line.style.backgroundColor = color;
    line.style.pointerEvents = 'none';
    line.style.zIndex = '9999';

    if (dashArray) {
      line.style.backgroundImage = `repeating-linear-gradient(
        ${snapPoint.direction === 'horizontal' ? '90deg' : '0deg'},
        ${color} 0px,
        ${color} ${dashArray.split(' ')[0]}px,
        transparent ${dashArray.split(' ')[0]}px,
        transparent ${dashArray.split(' ')[1]}px
      )`;
      line.style.backgroundColor = 'transparent';
    }

    if (snapPoint.direction === 'horizontal') {
      line.style.left = '0';
      line.style.right = '0';
      line.style.top = `${snapPoint.y}px`;
      line.style.height = `${width}px`;
    } else if (snapPoint.direction === 'vertical') {
      line.style.top = '0';
      line.style.bottom = '0';
      line.style.left = `${snapPoint.x}px`;
      line.style.width = `${width}px`;
    } else {
      // 对于 'both' 类型，创建十字线
      const horizontalLine = this.createGuideLine({
        ...snapPoint,
        direction: 'horizontal',
      });
      const verticalLine = this.createGuideLine({
        ...snapPoint,
        direction: 'vertical',
      });

      if (horizontalLine && verticalLine) {
        this.guideLines.push(horizontalLine, verticalLine);
        document.body.appendChild(horizontalLine);
        document.body.appendChild(verticalLine);
      }

      return null;
    }

    return line;
  }

  /**
   * 隐藏辅助线
   */
  private hideGuideLines(): void {
    this.guideLines.forEach((line) => {
      if (line.parentNode) {
        line.parentNode.removeChild(line);
      }
    });
    this.guideLines = [];
    this.events.onGuideHide?.();
  }

  /**
   * 开始吸附
   */
  public startSnap(): void {
    this.initializeSnapPoints();
    this.events.onSnapStart?.(this.snapPoints);
  }

  /**
   * 结束吸附
   */
  public endSnap(): void {
    this.hideGuideLines();
    this.activeSnapPoints = [];
    this.events.onSnapEnd?.();
  }

  /**
   * 更新配置
   */
  public updateConfig(config: Partial<SnapConfig>): void {
    this.config = { ...this.config, ...config };
    this.initializeSnapPoints();
  }

  /**
   * 获取当前配置
   */
  public getConfig(): SnapConfig {
    return { ...this.config };
  }

  /**
   * 获取所有吸附点
   */
  public getSnapPoints(): SnapPoint[] {
    return [...this.snapPoints];
  }

  /**
   * 获取活动吸附点
   */
  public getActiveSnapPoints(): SnapPoint[] {
    return [...this.activeSnapPoints];
  }

  /**
   * 销毁引擎
   */
  public destroy(): void {
    this.hideGuideLines();
    this.snapPoints = [];
    this.activeSnapPoints = [];
  }
}

/**
 * 创建吸附引擎实例
 */
export function createSnapEngine(
  container: HTMLElement,
  config?: Partial<SnapConfig>,
  events?: SnapEngineEvents,
): SnapEngine {
  return new SnapEngine(container, config, events);
}

/**
 * 吸附引擎预设配置
 */
export const SnapEnginePresets = {
  /** 默认配置 */
  default: {
    enabled: true,
    grid: { enabled: true, size: 8, offset: { x: 0, y: 0 } },
    edges: { enabled: true, threshold: 10, types: ['container', 'component'] },
    center: { enabled: true, threshold: 15, types: ['both'] },
    guides: {
      enabled: true,
      threshold: 5,
      showLines: true,
      lineStyle: { color: '#6366f1', width: 1, dashArray: '4 4' },
    },
    components: {
      enabled: true,
      threshold: 8,
      alignTypes: ['left', 'right', 'top', 'bottom', 'center-x', 'center-y'],
    },
  } as SnapConfig,

  /** 精确模式 */
  precise: {
    enabled: true,
    grid: { enabled: true, size: 4, offset: { x: 0, y: 0 } },
    edges: { enabled: true, threshold: 5, types: ['container', 'component', 'viewport'] },
    center: { enabled: true, threshold: 8, types: ['both'] },
    guides: {
      enabled: true,
      threshold: 3,
      showLines: true,
      lineStyle: { color: '#ef4444', width: 1 },
    },
    components: {
      enabled: true,
      threshold: 4,
      alignTypes: ['left', 'right', 'top', 'bottom', 'center-x', 'center-y'],
    },
  } as SnapConfig,

  /** 宽松模式 */
  loose: {
    enabled: true,
    grid: { enabled: true, size: 16, offset: { x: 0, y: 0 } },
    edges: { enabled: true, threshold: 20, types: ['container'] },
    center: { enabled: true, threshold: 25, types: ['both'] },
    guides: {
      enabled: true,
      threshold: 10,
      showLines: true,
      lineStyle: { color: '#10b981', width: 2, dashArray: '8 8' },
    },
    components: { enabled: false, threshold: 15, alignTypes: ['center-x', 'center-y'] },
  } as SnapConfig,

  /** 性能模式 */
  performance: {
    enabled: true,
    grid: { enabled: true, size: 12, offset: { x: 0, y: 0 } },
    edges: { enabled: false, threshold: 15, types: ['container'] },
    center: { enabled: false, threshold: 20, types: ['both'] },
    guides: {
      enabled: false,
      threshold: 8,
      showLines: false,
      lineStyle: { color: '#6366f1', width: 1 },
    },
    components: { enabled: false, threshold: 12, alignTypes: ['left', 'right', 'top', 'bottom'] },
  } as SnapConfig,

  /** 禁用模式 */
  disabled: {
    enabled: false,
    grid: { enabled: false, size: 8, offset: { x: 0, y: 0 } },
    edges: { enabled: false, threshold: 10, types: [] },
    center: { enabled: false, threshold: 15, types: [] },
    guides: {
      enabled: false,
      threshold: 5,
      showLines: false,
      lineStyle: { color: '#6366f1', width: 1 },
    },
    components: { enabled: false, threshold: 8, alignTypes: [] },
  } as SnapConfig,
};
