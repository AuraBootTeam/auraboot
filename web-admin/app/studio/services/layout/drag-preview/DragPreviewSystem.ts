import type { ComponentSchema } from '~/studio/domain/schema/types';

/**
 * 拖拽预览配置
 */
export interface DragPreviewConfig {
  /** 是否启用预览 */
  enabled: boolean;
  /** 预览透明度 */
  opacity: number;
  /** 预览缩放比例 */
  scale: number;
  /** 预览偏移 */
  offset: { x: number; y: number };
  /** 是否显示组件信息 */
  showInfo: boolean;
  /** 预览样式 */
  style: 'ghost' | 'outline' | 'solid';
  /** 动画持续时间 */
  animationDuration: number;
}

/**
 * 拖拽预览状态
 */
export interface DragPreviewState {
  /** 是否正在拖拽 */
  isDragging: boolean;
  /** 拖拽的组件 */
  draggedComponent: ComponentSchema | null;
  /** 预览元素 */
  previewElement: HTMLElement | null;
  /** 鼠标位置 */
  mousePosition: { x: number; y: number };
  /** 预览位置 */
  previewPosition: { x: number; y: number };
  /** 拖拽起始位置 */
  startPosition: { x: number; y: number };
  /** 拖拽类型 */
  dragType: 'move' | 'copy' | 'create';
}

/**
 * Ghost效果配置
 */
export interface GhostEffectConfig {
  /** 是否启用Ghost效果 */
  enabled: boolean;
  /** Ghost透明度 */
  opacity: number;
  /** Ghost模糊程度 */
  blur: number;
  /** 是否显示原位置占位符 */
  showPlaceholder: boolean;
  /** 占位符样式 */
  placeholderStyle: 'dashed' | 'dotted' | 'solid';
  /** 占位符颜色 */
  placeholderColor: string;
}

/**
 * 拖拽预览系统
 */
export class DragPreviewSystem {
  private static instance: DragPreviewSystem;
  private config: DragPreviewConfig;
  private ghostConfig: GhostEffectConfig;
  private state: DragPreviewState;
  private previewContainer: HTMLElement | null = null;
  private placeholderElement: HTMLElement | null = null;
  private animationFrame: number | null = null;

  private constructor() {
    this.config = {
      enabled: true,
      opacity: 0.8,
      scale: 0.9,
      offset: { x: 10, y: 10 },
      showInfo: true,
      style: 'ghost',
      animationDuration: 200,
    };

    this.ghostConfig = {
      enabled: true,
      opacity: 0.3,
      blur: 2,
      showPlaceholder: true,
      placeholderStyle: 'dashed',
      placeholderColor: '#3b82f6',
    };

    this.state = {
      isDragging: false,
      draggedComponent: null,
      previewElement: null,
      mousePosition: { x: 0, y: 0 },
      previewPosition: { x: 0, y: 0 },
      startPosition: { x: 0, y: 0 },
      dragType: 'move',
    };

    this.initializePreviewContainer();
    this.bindEvents();
  }

  public static getInstance(): DragPreviewSystem {
    if (!DragPreviewSystem.instance) {
      DragPreviewSystem.instance = new DragPreviewSystem();
    }
    return DragPreviewSystem.instance;
  }

  /**
   * 初始化预览容器
   */
  private initializePreviewContainer(): void {
    this.previewContainer = document.createElement('div');
    this.previewContainer.id = 'drag-preview-container';
    this.previewContainer.style.cssText = `
      position: fixed;
      top: 0;
      left: 0;
      width: 100vw;
      height: 100vh;
      pointer-events: none;
      z-index: 9999;
      overflow: hidden;
    `;
    document.body.appendChild(this.previewContainer);
  }

  /**
   * 绑定事件
   */
  private bindEvents(): void {
    document.addEventListener('mousemove', this.handleMouseMove.bind(this));
    document.addEventListener('mouseup', this.handleMouseUp.bind(this));
  }

  /**
   * 开始拖拽预览
   */
  public startDragPreview(
    component: ComponentSchema,
    sourceElement: HTMLElement,
    mouseEvent: MouseEvent,
    dragType: 'move' | 'copy' | 'create' = 'move',
  ): void {
    if (!this.config.enabled) return;

    this.state.isDragging = true;
    this.state.draggedComponent = component;
    this.state.dragType = dragType;
    this.state.startPosition = { x: mouseEvent.clientX, y: mouseEvent.clientY };
    this.state.mousePosition = { x: mouseEvent.clientX, y: mouseEvent.clientY };

    // 创建预览元素
    this.createPreviewElement(component, sourceElement);

    // 创建占位符
    if (this.ghostConfig.enabled && this.ghostConfig.showPlaceholder && dragType === 'move') {
      this.createPlaceholder(sourceElement);
    }

    // 应用Ghost效果到原元素
    if (this.ghostConfig.enabled && dragType === 'move') {
      this.applyGhostEffect(sourceElement);
    }

    // 开始动画循环
    this.startAnimationLoop();
  }

  /**
   * 创建预览元素
   */
  private createPreviewElement(component: ComponentSchema, sourceElement: HTMLElement): void {
    if (!this.previewContainer) return;

    // 克隆源元素
    const previewElement = sourceElement.cloneNode(true) as HTMLElement;

    // 获取源元素的尺寸和样式
    const rect = sourceElement.getBoundingClientRect();
    const computedStyle = window.getComputedStyle(sourceElement);

    // 设置预览元素样式
    previewElement.style.cssText = `
      position: absolute;
      width: ${rect.width}px;
      height: ${rect.height}px;
      opacity: ${this.config.opacity};
      transform: scale(${this.config.scale});
      transform-origin: top left;
      pointer-events: none;
      z-index: 10000;
      transition: all ${this.config.animationDuration}ms ease-out;
      box-shadow: 0 8px 32px rgba(0, 0, 0, 0.3);
      border-radius: 8px;
      overflow: hidden;
    `;

    // 应用预览样式
    this.applyPreviewStyle(previewElement);

    // 添加组件信息
    if (this.config.showInfo) {
      this.addComponentInfo(previewElement, component);
    }

    // 添加到容器
    this.previewContainer.appendChild(previewElement);
    this.state.previewElement = previewElement;

    // 初始位置
    this.updatePreviewPosition();
  }

  /**
   * 应用预览样式
   */
  private applyPreviewStyle(element: HTMLElement): void {
    switch (this.config.style) {
      case 'ghost':
        element.style.filter = `blur(1px) opacity(${this.config.opacity})`;
        element.style.background = 'rgba(255, 255, 255, 0.9)';
        break;
      case 'outline':
        element.style.background = 'transparent';
        element.style.border = '2px solid #3b82f6';
        element.style.borderRadius = '8px';
        break;
      case 'solid':
        element.style.background = '#ffffff';
        element.style.border = '1px solid #e5e7eb';
        break;
    }
  }

  /**
   * 添加组件信息
   */
  private addComponentInfo(element: HTMLElement, component: ComponentSchema): void {
    const infoElement = document.createElement('div');
    infoElement.style.cssText = `
      position: absolute;
      top: -30px;
      left: 0;
      background: #1f2937;
      color: white;
      padding: 4px 8px;
      border-radius: 4px;
      font-size: 12px;
      font-weight: 500;
      white-space: nowrap;
      z-index: 10001;
    `;
    infoElement.textContent = `${component.type} - ${component.props?.title || component.id.slice(-4)}`;
    element.appendChild(infoElement);
  }

  /**
   * 创建占位符
   */
  private createPlaceholder(sourceElement: HTMLElement): void {
    const rect = sourceElement.getBoundingClientRect();
    const placeholder = document.createElement('div');

    placeholder.style.cssText = `
      position: absolute;
      width: ${rect.width}px;
      height: ${rect.height}px;
      border: 2px ${this.ghostConfig.placeholderStyle} ${this.ghostConfig.placeholderColor};
      background: rgba(59, 130, 246, 0.1);
      border-radius: 4px;
      pointer-events: none;
      z-index: 1;
    `;

    // 插入到源元素的位置
    sourceElement.parentNode?.insertBefore(placeholder, sourceElement);
    this.placeholderElement = placeholder;
  }

  /**
   * 应用Ghost效果
   */
  private applyGhostEffect(element: HTMLElement): void {
    element.style.opacity = this.ghostConfig.opacity.toString();
    element.style.filter = `blur(${this.ghostConfig.blur}px)`;
    element.style.transition = `all ${this.config.animationDuration}ms ease-out`;
  }

  /**
   * 处理鼠标移动
   */
  private handleMouseMove(event: MouseEvent): void {
    if (!this.state.isDragging) return;

    this.state.mousePosition = { x: event.clientX, y: event.clientY };
    this.updatePreviewPosition();
  }

  /**
   * 更新预览位置
   */
  private updatePreviewPosition(): void {
    if (!this.state.previewElement) return;

    const x = this.state.mousePosition.x + this.config.offset.x;
    const y = this.state.mousePosition.y + this.config.offset.y;

    this.state.previewPosition = { x, y };
    this.state.previewElement.style.left = `${x}px`;
    this.state.previewElement.style.top = `${y}px`;
  }

  /**
   * 开始动画循环
   */
  private startAnimationLoop(): void {
    const animate = () => {
      if (this.state.isDragging) {
        // 可以在这里添加额外的动画效果
        this.animationFrame = requestAnimationFrame(animate);
      }
    };
    this.animationFrame = requestAnimationFrame(animate);
  }

  /**
   * 处理鼠标释放
   */
  private handleMouseUp(): void {
    if (!this.state.isDragging) return;
    this.endDragPreview();
  }

  /**
   * 结束拖拽预览
   */
  public endDragPreview(): void {
    if (!this.state.isDragging) return;

    this.state.isDragging = false;

    // 停止动画循环
    if (this.animationFrame) {
      cancelAnimationFrame(this.animationFrame);
      this.animationFrame = null;
    }

    // 移除预览元素
    if (this.state.previewElement && this.previewContainer) {
      this.previewContainer.removeChild(this.state.previewElement);
      this.state.previewElement = null;
    }

    // 移除占位符
    if (this.placeholderElement) {
      this.placeholderElement.parentNode?.removeChild(this.placeholderElement);
      this.placeholderElement = null;
    }

    // 重置状态
    this.state.draggedComponent = null;
    this.state.mousePosition = { x: 0, y: 0 };
    this.state.previewPosition = { x: 0, y: 0 };
    this.state.startPosition = { x: 0, y: 0 };
  }

  /**
   * 更新配置
   */
  public updateConfig(config: Partial<DragPreviewConfig>): void {
    this.config = { ...this.config, ...config };
  }

  /**
   * 更新Ghost配置
   */
  public updateGhostConfig(config: Partial<GhostEffectConfig>): void {
    this.ghostConfig = { ...this.ghostConfig, ...config };
  }

  /**
   * 获取当前状态
   */
  public getState(): DragPreviewState {
    return { ...this.state };
  }

  /**
   * 获取配置
   */
  public getConfig(): DragPreviewConfig {
    return { ...this.config };
  }

  /**
   * 获取Ghost配置
   */
  public getGhostConfig(): GhostEffectConfig {
    return { ...this.ghostConfig };
  }

  /**
   * 销毁系统
   */
  public destroy(): void {
    if (this.animationFrame) {
      cancelAnimationFrame(this.animationFrame);
    }

    if (this.previewContainer) {
      document.body.removeChild(this.previewContainer);
      this.previewContainer = null;
    }

    document.removeEventListener('mousemove', this.handleMouseMove.bind(this));
    document.removeEventListener('mouseup', this.handleMouseUp.bind(this));
  }
}

export default DragPreviewSystem;
