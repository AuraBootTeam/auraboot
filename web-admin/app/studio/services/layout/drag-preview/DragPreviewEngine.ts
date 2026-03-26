/**
 * 拖拽预览引擎
 * 提供拖拽过程中的预览效果和Ghost元素功能
 */

export interface DragPreviewConfig {
  // Ghost元素配置
  ghostOpacity: number;
  ghostScale: number;
  ghostBlur: number;
  ghostBorderRadius: number;
  ghostShadow: string;

  // 预览配置
  previewOpacity: number;
  previewScale: number;
  previewOffset: { x: number; y: number };
  previewRotation: number;

  // 动画配置
  animationDuration: number;
  animationEasing: string;
  enableSpringAnimation: boolean;
  springTension: number;
  springFriction: number;

  // 视觉效果配置
  enableTrail: boolean;
  trailLength: number;
  trailOpacity: number;
  enableGlow: boolean;
  glowColor: string;
  glowIntensity: number;

  // 交互配置
  followCursor: boolean;
  cursorOffset: { x: number; y: number };
  enableMagnetism: boolean;
  magnetismStrength: number;

  // 性能配置
  useTransform3d: boolean;
  enableGPUAcceleration: boolean;
  throttleDelay: number;
  maxTrailElements: number;
}

export interface DragPreviewState {
  isDragging: boolean;
  isPreviewVisible: boolean;
  isGhostVisible: boolean;
  currentPosition: { x: number; y: number };
  targetPosition: { x: number; y: number };
  velocity: { x: number; y: number };
  scale: number;
  rotation: number;
  opacity: number;
}

export interface DragPreviewElement {
  id: string;
  element: HTMLElement;
  originalElement: HTMLElement;
  type: 'ghost' | 'preview' | 'trail';
  timestamp: number;
  position: { x: number; y: number };
  properties: Record<string, any>;
}

export interface DragPreviewResult {
  ghostElement: HTMLElement | null;
  previewElement: HTMLElement | null;
  trailElements: HTMLElement[];
  position: { x: number; y: number };
  isVisible: boolean;
}

export interface DragPreviewEngineEvents {
  onDragStart: (element: HTMLElement, data: any) => void;
  onDragMove: (position: { x: number; y: number }) => void;
  onDragEnd: () => void;
  onPreviewCreate: (element: HTMLElement) => void;
  onPreviewUpdate: (element: HTMLElement, position: { x: number; y: number }) => void;
  onPreviewDestroy: (element: HTMLElement) => void;
  onError: (error: Error) => void;
}

export class DragPreviewEngine {
  private config: DragPreviewConfig;
  private container: HTMLElement | null = null;
  private state: DragPreviewState;
  private events: Partial<DragPreviewEngineEvents> = {};

  // 预览元素
  private previewElements: Map<string, DragPreviewElement> = new Map();
  private ghostElement: HTMLElement | null = null;
  private previewElement: HTMLElement | null = null;
  private trailElements: HTMLElement[] = [];

  // 动画和性能
  private animationFrame: number | null = null;
  private throttleTimer: number | null = null;
  private lastUpdateTime: number = 0;
  private velocityHistory: Array<{ x: number; y: number; time: number }> = [];

  // 原始元素信息
  private originalElement: HTMLElement | null = null;
  private originalBounds: DOMRect | null = null;
  private dragData: any = null;

  constructor(config: Partial<DragPreviewConfig> = {}) {
    this.config = {
      ghostOpacity: 0.5,
      ghostScale: 0.95,
      ghostBlur: 2,
      ghostBorderRadius: 4,
      ghostShadow: '0 4px 12px rgba(0, 0, 0, 0.15)',
      previewOpacity: 0.8,
      previewScale: 1.05,
      previewOffset: { x: 10, y: 10 },
      previewRotation: 2,
      animationDuration: 200,
      animationEasing: 'cubic-bezier(0.4, 0, 0.2, 1)',
      enableSpringAnimation: true,
      springTension: 300,
      springFriction: 30,
      enableTrail: true,
      trailLength: 5,
      trailOpacity: 0.3,
      enableGlow: false,
      glowColor: '#3b82f6',
      glowIntensity: 10,
      followCursor: true,
      cursorOffset: { x: 0, y: 0 },
      enableMagnetism: false,
      magnetismStrength: 0.1,
      useTransform3d: true,
      enableGPUAcceleration: true,
      throttleDelay: 16,
      maxTrailElements: 10,
      ...config,
    };

    this.state = {
      isDragging: false,
      isPreviewVisible: false,
      isGhostVisible: false,
      currentPosition: { x: 0, y: 0 },
      targetPosition: { x: 0, y: 0 },
      velocity: { x: 0, y: 0 },
      scale: 1,
      rotation: 0,
      opacity: 1,
    };
  }

  /**
   * 初始化容器
   */
  public initializeContainer(container: HTMLElement): void {
    this.container = container;
    this.setupStyles();
  }

  /**
   * 销毁引擎
   */
  public destroy(): void {
    this.endDrag();
    this.clearAllElements();

    if (this.animationFrame) {
      cancelAnimationFrame(this.animationFrame);
    }

    if (this.throttleTimer) {
      clearTimeout(this.throttleTimer);
    }
  }

  /**
   * 开始拖拽
   */
  public startDrag(element: HTMLElement, data: any, startPosition: { x: number; y: number }): void {
    if (this.state.isDragging) {
      this.endDrag();
    }

    this.originalElement = element;
    this.originalBounds = element.getBoundingClientRect();
    this.dragData = data;

    this.state.isDragging = true;
    this.state.currentPosition = startPosition;
    this.state.targetPosition = startPosition;

    this.createGhostElement();
    this.createPreviewElement();

    if (this.config.enableTrail) {
      this.initializeTrail();
    }

    this.startAnimation();
    this.events.onDragStart?.(element, data);
  }

  /**
   * 更新拖拽位置
   */
  public updateDragPosition(position: { x: number; y: number }): void {
    if (!this.state.isDragging) return;

    // 节流处理
    if (this.throttleTimer) {
      clearTimeout(this.throttleTimer);
    }

    this.throttleTimer = window.setTimeout(() => {
      this.performPositionUpdate(position);
    }, this.config.throttleDelay);
  }

  /**
   * 结束拖拽
   */
  public endDrag(): void {
    if (!this.state.isDragging) return;

    this.state.isDragging = false;
    this.state.isPreviewVisible = false;
    this.state.isGhostVisible = false;

    this.animateElementsOut();
    this.stopAnimation();

    // 延迟清理元素，等待动画完成
    setTimeout(() => {
      this.clearAllElements();
    }, this.config.animationDuration);

    this.events.onDragEnd?.();
  }

  /**
   * 设置事件监听器
   */
  public on<K extends keyof DragPreviewEngineEvents>(
    event: K,
    handler: DragPreviewEngineEvents[K],
  ): void {
    this.events[event] = handler;
  }

  /**
   * 移除事件监听器
   */
  public off<K extends keyof DragPreviewEngineEvents>(event: K): void {
    delete this.events[event];
  }

  /**
   * 更新配置
   */
  public updateConfig(updates: Partial<DragPreviewConfig>): void {
    Object.assign(this.config, updates);
    this.refreshElements();
  }

  /**
   * 获取当前状态
   */
  public getState(): DragPreviewState {
    return { ...this.state };
  }

  /**
   * 获取预览结果
   */
  public getPreviewResult(): DragPreviewResult {
    return {
      ghostElement: this.ghostElement,
      previewElement: this.previewElement,
      trailElements: [...this.trailElements],
      position: { ...this.state.currentPosition },
      isVisible: this.state.isPreviewVisible || this.state.isGhostVisible,
    };
  }

  /**
   * 执行位置更新
   */
  private performPositionUpdate(position: { x: number; y: number }): void {
    const now = Date.now();
    const deltaTime = now - this.lastUpdateTime;

    // 计算速度
    if (this.lastUpdateTime > 0 && deltaTime > 0) {
      const velocity = {
        x: ((position.x - this.state.currentPosition.x) / deltaTime) * 1000,
        y: ((position.y - this.state.currentPosition.y) / deltaTime) * 1000,
      };

      this.velocityHistory.push({ ...velocity, time: now });

      // 保持速度历史记录在合理范围内
      if (this.velocityHistory.length > 10) {
        this.velocityHistory.shift();
      }

      this.state.velocity = velocity;
    }

    this.state.targetPosition = position;
    this.lastUpdateTime = now;

    if (this.config.enableTrail) {
      this.updateTrail();
    }

    this.events.onDragMove?.(position);
  }

  /**
   * 创建Ghost元素
   */
  private createGhostElement(): void {
    if (!this.originalElement || !this.container) return;

    this.ghostElement = this.cloneElement(this.originalElement);
    this.ghostElement.className += ' drag-ghost';

    this.applyGhostStyles(this.ghostElement);
    this.container.appendChild(this.ghostElement);

    this.state.isGhostVisible = true;

    // 动画进入
    requestAnimationFrame(() => {
      if (this.ghostElement) {
        this.ghostElement.style.opacity = this.config.ghostOpacity.toString();
        this.ghostElement.style.transform = this.buildTransform({
          scale: this.config.ghostScale,
          blur: this.config.ghostBlur,
        });
      }
    });
  }

  /**
   * 创建预览元素
   */
  private createPreviewElement(): void {
    if (!this.originalElement || !this.container) return;

    this.previewElement = this.cloneElement(this.originalElement);
    this.previewElement.className += ' drag-preview';

    this.applyPreviewStyles(this.previewElement);
    this.container.appendChild(this.previewElement);

    this.state.isPreviewVisible = true;
    this.events.onPreviewCreate?.(this.previewElement);

    // 动画进入
    requestAnimationFrame(() => {
      if (this.previewElement) {
        this.previewElement.style.opacity = this.config.previewOpacity.toString();
        this.previewElement.style.transform = this.buildTransform({
          scale: this.config.previewScale,
          rotation: this.config.previewRotation,
        });
      }
    });
  }

  /**
   * 初始化拖拽轨迹
   */
  private initializeTrail(): void {
    this.trailElements = [];
  }

  /**
   * 更新拖拽轨迹
   */
  private updateTrail(): void {
    if (!this.config.enableTrail || !this.originalElement || !this.container) return;

    // 创建新的轨迹元素
    const trailElement = this.cloneElement(this.originalElement);
    trailElement.className += ' drag-trail';

    this.applyTrailStyles(trailElement, this.trailElements.length);
    this.positionElement(trailElement, this.state.currentPosition);

    this.container.appendChild(trailElement);
    this.trailElements.push(trailElement);

    // 限制轨迹元素数量
    if (this.trailElements.length > this.config.maxTrailElements) {
      const oldElement = this.trailElements.shift();
      if (oldElement) {
        this.animateElementOut(oldElement);
      }
    }

    // 更新现有轨迹元素的透明度
    this.updateTrailOpacity();
  }

  /**
   * 更新轨迹透明度
   */
  private updateTrailOpacity(): void {
    this.trailElements.forEach((element, index) => {
      const opacity = (this.config.trailOpacity * (index + 1)) / this.trailElements.length;
      element.style.opacity = opacity.toString();
    });
  }

  /**
   * 克隆元素
   */
  private cloneElement(element: HTMLElement): HTMLElement {
    const clone = element.cloneNode(true) as HTMLElement;

    // 移除可能影响布局的属性
    clone.removeAttribute('id');
    clone.style.position = 'absolute';
    clone.style.pointerEvents = 'none';
    clone.style.zIndex = '9999';

    return clone;
  }

  /**
   * 应用Ghost样式
   */
  private applyGhostStyles(element: HTMLElement): void {
    element.style.cssText += `
      opacity: 0;
      transform: ${this.buildTransform({ scale: 1 })};
      transition: all ${this.config.animationDuration}ms ${this.config.animationEasing};
      filter: blur(0px);
      border-radius: ${this.config.ghostBorderRadius}px;
      box-shadow: ${this.config.ghostShadow};
    `;

    if (this.config.enableGPUAcceleration) {
      element.style.willChange = 'transform, opacity';
    }
  }

  /**
   * 应用预览样式
   */
  private applyPreviewStyles(element: HTMLElement): void {
    element.style.cssText += `
      opacity: 0;
      transform: ${this.buildTransform({ scale: 1 })};
      transition: all ${this.config.animationDuration}ms ${this.config.animationEasing};
    `;

    if (this.config.enableGlow) {
      element.style.boxShadow = `0 0 ${this.config.glowIntensity}px ${this.config.glowColor}`;
    }

    if (this.config.enableGPUAcceleration) {
      element.style.willChange = 'transform, opacity';
    }
  }

  /**
   * 应用轨迹样式
   */
  private applyTrailStyles(element: HTMLElement, index: number): void {
    const opacity = (this.config.trailOpacity * (index + 1)) / this.config.trailLength;
    const scale = 0.8 + index * 0.05;

    element.style.cssText += `
      opacity: ${opacity};
      transform: ${this.buildTransform({ scale })};
      transition: opacity ${this.config.animationDuration}ms ${this.config.animationEasing};
      pointer-events: none;
      z-index: ${9998 - index};
    `;
  }

  /**
   * 构建变换字符串
   */
  private buildTransform(
    options: {
      scale?: number;
      rotation?: number;
      blur?: number;
      x?: number;
      y?: number;
    } = {},
  ): string {
    const { scale = 1, rotation = 0, blur = 0, x = 0, y = 0 } = options;

    let transform = '';

    if (this.config.useTransform3d) {
      transform += `translate3d(${x}px, ${y}px, 0) `;
    } else {
      transform += `translate(${x}px, ${y}px) `;
    }

    if (scale !== 1) {
      transform += `scale(${scale}) `;
    }

    if (rotation !== 0) {
      transform += `rotate(${rotation}deg) `;
    }

    if (blur > 0) {
      // blur通过filter属性应用，不在transform中
    }

    return transform.trim();
  }

  /**
   * 定位元素
   */
  private positionElement(element: HTMLElement, position: { x: number; y: number }): void {
    if (!this.originalBounds) return;

    const x = position.x - this.originalBounds.width / 2;
    const y = position.y - this.originalBounds.height / 2;

    element.style.left = `${x}px`;
    element.style.top = `${y}px`;
  }

  /**
   * 开始动画循环
   */
  private startAnimation(): void {
    const animate = () => {
      if (!this.state.isDragging) return;

      this.updateElementPositions();
      this.animationFrame = requestAnimationFrame(animate);
    };

    this.animationFrame = requestAnimationFrame(animate);
  }

  /**
   * 停止动画循环
   */
  private stopAnimation(): void {
    if (this.animationFrame) {
      cancelAnimationFrame(this.animationFrame);
      this.animationFrame = null;
    }
  }

  /**
   * 更新元素位置
   */
  private updateElementPositions(): void {
    if (this.config.enableSpringAnimation) {
      this.updateWithSpringAnimation();
    } else {
      this.updateWithLinearAnimation();
    }

    // 更新Ghost元素
    if (this.ghostElement && this.state.isGhostVisible) {
      this.positionElement(this.ghostElement, this.state.currentPosition);
      this.events.onPreviewUpdate?.(this.ghostElement, this.state.currentPosition);
    }

    // 更新预览元素
    if (this.previewElement && this.state.isPreviewVisible) {
      const previewPosition = {
        x: this.state.currentPosition.x + this.config.previewOffset.x,
        y: this.state.currentPosition.y + this.config.previewOffset.y,
      };
      this.positionElement(this.previewElement, previewPosition);
      this.events.onPreviewUpdate?.(this.previewElement, previewPosition);
    }
  }

  /**
   * 使用弹簧动画更新
   */
  private updateWithSpringAnimation(): void {
    const tension = this.config.springTension;
    const friction = this.config.springFriction;

    const dx = this.state.targetPosition.x - this.state.currentPosition.x;
    const dy = this.state.targetPosition.y - this.state.currentPosition.y;

    const ax = (tension * dx - friction * this.state.velocity.x) / 100;
    const ay = (tension * dy - friction * this.state.velocity.y) / 100;

    this.state.velocity.x += ax;
    this.state.velocity.y += ay;

    this.state.currentPosition.x += this.state.velocity.x / 60;
    this.state.currentPosition.y += this.state.velocity.y / 60;
  }

  /**
   * 使用线性动画更新
   */
  private updateWithLinearAnimation(): void {
    const lerp = 0.15; // 插值因子

    this.state.currentPosition.x +=
      (this.state.targetPosition.x - this.state.currentPosition.x) * lerp;
    this.state.currentPosition.y +=
      (this.state.targetPosition.y - this.state.currentPosition.y) * lerp;
  }

  /**
   * 动画元素退出
   */
  private animateElementsOut(): void {
    if (this.ghostElement) {
      this.animateElementOut(this.ghostElement);
    }

    if (this.previewElement) {
      this.animateElementOut(this.previewElement);
    }

    this.trailElements.forEach((element) => {
      this.animateElementOut(element);
    });
  }

  /**
   * 动画单个元素退出
   */
  private animateElementOut(element: HTMLElement): void {
    element.style.transition = `all ${this.config.animationDuration}ms ${this.config.animationEasing}`;
    element.style.opacity = '0';
    element.style.transform += ' scale(0.8)';

    setTimeout(() => {
      if (element.parentNode) {
        element.parentNode.removeChild(element);
      }
    }, this.config.animationDuration);
  }

  /**
   * 清除所有元素
   */
  private clearAllElements(): void {
    if (this.ghostElement) {
      if (this.ghostElement.parentNode) {
        this.ghostElement.parentNode.removeChild(this.ghostElement);
      }
      this.ghostElement = null;
      this.state.isGhostVisible = false;
    }

    if (this.previewElement) {
      this.events.onPreviewDestroy?.(this.previewElement);
      if (this.previewElement.parentNode) {
        this.previewElement.parentNode.removeChild(this.previewElement);
      }
      this.previewElement = null;
      this.state.isPreviewVisible = false;
    }

    this.trailElements.forEach((element) => {
      if (element.parentNode) {
        element.parentNode.removeChild(element);
      }
    });
    this.trailElements = [];

    this.previewElements.clear();
  }

  /**
   * 刷新元素
   */
  private refreshElements(): void {
    if (this.ghostElement) {
      this.applyGhostStyles(this.ghostElement);
    }

    if (this.previewElement) {
      this.applyPreviewStyles(this.previewElement);
    }

    this.trailElements.forEach((element, index) => {
      this.applyTrailStyles(element, index);
    });
  }

  /**
   * 设置样式
   */
  private setupStyles(): void {
    if (!document.getElementById('drag-preview-styles')) {
      const style = document.createElement('style');
      style.id = 'drag-preview-styles';
      style.textContent = `
        .drag-ghost {
          user-select: none;
          -webkit-user-select: none;
          -moz-user-select: none;
          -ms-user-select: none;
        }
        
        .drag-preview {
          user-select: none;
          -webkit-user-select: none;
          -moz-user-select: none;
          -ms-user-select: none;
        }
        
        .drag-trail {
          user-select: none;
          -webkit-user-select: none;
          -moz-user-select: none;
          -ms-user-select: none;
        }
      `;
      document.head.appendChild(style);
    }
  }
}

/**
 * 创建拖拽预览引擎
 */
export function createDragPreviewEngine(config?: Partial<DragPreviewConfig>): DragPreviewEngine {
  return new DragPreviewEngine(config);
}

/**
 * 拖拽预览引擎预设配置
 */
export const DragPreviewEnginePresets = {
  default: {
    ghostOpacity: 0.5,
    ghostScale: 0.95,
    previewOpacity: 0.8,
    previewScale: 1.05,
    animationDuration: 200,
    enableSpringAnimation: true,
    enableTrail: true,
    followCursor: true,
  } as Partial<DragPreviewConfig>,

  minimal: {
    ghostOpacity: 0.3,
    ghostScale: 0.9,
    previewOpacity: 0.6,
    previewScale: 1.0,
    animationDuration: 150,
    enableSpringAnimation: false,
    enableTrail: false,
    followCursor: true,
    enableGlow: false,
  } as Partial<DragPreviewConfig>,

  enhanced: {
    ghostOpacity: 0.6,
    ghostScale: 0.98,
    previewOpacity: 0.9,
    previewScale: 1.1,
    animationDuration: 300,
    enableSpringAnimation: true,
    enableTrail: true,
    trailLength: 8,
    enableGlow: true,
    glowIntensity: 15,
    followCursor: true,
  } as Partial<DragPreviewConfig>,

  performance: {
    ghostOpacity: 0.4,
    ghostScale: 0.95,
    previewOpacity: 0.7,
    previewScale: 1.0,
    animationDuration: 100,
    enableSpringAnimation: false,
    enableTrail: false,
    enableGlow: false,
    useTransform3d: true,
    enableGPUAcceleration: true,
    throttleDelay: 32,
  } as Partial<DragPreviewConfig>,
};
