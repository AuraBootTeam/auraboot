/**
 * 拖拽预览管理器
 * 负责管理拖拽过程中的预览效果和Ghost效果
 */

export interface DragPreviewConfig {
  /** 是否启用预览 */
  enabled: boolean;
  /** 预览偏移量 */
  offset: { x: number; y: number };
  /** 预览缩放比例 */
  scale: number;
  /** 预览透明度 */
  opacity: number;
  /** 是否显示原始内容 */
  showOriginalContent: boolean;
  /** 自定义预览内容 */
  customContent?: (item: any) => HTMLElement;
  /** 动画持续时间 */
  animationDuration: number;
  /** 是否启用Ghost效果 */
  enableGhost: boolean;
  /** Ghost透明度 */
  ghostOpacity: number;
}

export interface DragPreviewState {
  /** 是否正在显示预览 */
  isVisible: boolean;
  /** 预览元素 */
  previewElement: HTMLElement | null;
  /** Ghost元素 */
  ghostElement: HTMLElement | null;
  /** 当前拖拽项 */
  dragItem: any;
  /** 鼠标位置 */
  mousePosition: { x: number; y: number };
}

/**
 * 拖拽预览管理器
 */
export class DragPreview {
  private config: DragPreviewConfig;
  private state: DragPreviewState;
  private container: HTMLElement;
  private mouseMoveHandler: (event: MouseEvent) => void;

  constructor(container: HTMLElement, config: Partial<DragPreviewConfig> = {}) {
    this.container = container;
    this.config = {
      enabled: true,
      offset: { x: 10, y: 10 },
      scale: 0.8,
      opacity: 0.9,
      showOriginalContent: true,
      animationDuration: 200,
      enableGhost: true,
      ghostOpacity: 0.3,
      ...config,
    };

    this.state = {
      isVisible: false,
      previewElement: null,
      ghostElement: null,
      dragItem: null,
      mousePosition: { x: 0, y: 0 },
    };

    this.mouseMoveHandler = this.handleMouseMove.bind(this);
    this.initializeContainer();
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
   * 开始拖拽预览
   */
  startPreview(
    item: any,
    sourceElement: HTMLElement,
    initialPosition: { x: number; y: number },
  ): void {
    if (!this.config.enabled) return;

    this.state.dragItem = item;
    this.state.mousePosition = initialPosition;

    // 创建预览元素
    if (this.config.showOriginalContent || this.config.customContent) {
      this.createPreviewElement(item, sourceElement);
    }

    // 创建Ghost效果
    if (this.config.enableGhost) {
      this.createGhostEffect(sourceElement);
    }

    this.state.isVisible = true;

    // 监听鼠标移动
    document.addEventListener('mousemove', this.mouseMoveHandler);
    document.addEventListener('dragover', this.mouseMoveHandler);
  }

  /**
   * 结束拖拽预览
   */
  endPreview(): void {
    this.state.isVisible = false;
    this.state.dragItem = null;

    // 移除预览元素
    this.removePreviewElement();

    // 移除Ghost效果
    this.removeGhostEffect();

    // 移除事件监听器
    document.removeEventListener('mousemove', this.mouseMoveHandler);
    document.removeEventListener('dragover', this.mouseMoveHandler);
  }

  /**
   * 更新预览位置
   */
  updatePosition(position: { x: number; y: number }): void {
    this.state.mousePosition = position;
    this.updatePreviewPosition();
  }

  /**
   * 创建预览元素
   */
  private createPreviewElement(item: any, sourceElement: HTMLElement): void {
    // 移除现有预览元素
    this.removePreviewElement();

    let previewContent: HTMLElement;

    if (this.config.customContent) {
      // 使用自定义内容
      previewContent = this.config.customContent(item);
    } else {
      // 克隆源元素
      previewContent = sourceElement.cloneNode(true) as HTMLElement;
      this.cleanupClonedElement(previewContent);
    }

    // 创建预览容器
    const previewElement = document.createElement('div');
    previewElement.className = 'drag-preview';
    previewElement.style.position = 'fixed';
    previewElement.style.pointerEvents = 'none';
    previewElement.style.zIndex = '10000';
    previewElement.style.opacity = '0';
    previewElement.style.transform = `scale(${this.config.scale})`;
    previewElement.style.transition = `all ${this.config.animationDuration}ms ease-out`;

    // 创建内容容器
    const contentContainer = document.createElement('div');
    contentContainer.className = 'drag-preview__content';
    contentContainer.appendChild(previewContent);

    previewElement.appendChild(contentContainer);
    document.body.appendChild(previewElement);

    this.state.previewElement = previewElement;

    // 触发动画
    requestAnimationFrame(() => {
      if (this.state.previewElement) {
        this.state.previewElement.style.opacity = this.config.opacity.toString();
        this.updatePreviewPosition();
      }
    });
  }

  /**
   * 移除预览元素
   */
  private removePreviewElement(): void {
    if (this.state.previewElement) {
      // 淡出动画
      this.state.previewElement.style.opacity = '0';
      this.state.previewElement.style.transform = `scale(${this.config.scale * 0.9})`;

      setTimeout(() => {
        if (this.state.previewElement && this.state.previewElement.parentNode) {
          this.state.previewElement.parentNode.removeChild(this.state.previewElement);
        }
        this.state.previewElement = null;
      }, this.config.animationDuration);
    }
  }

  /**
   * 创建Ghost效果
   */
  private createGhostEffect(sourceElement: HTMLElement): void {
    this.removeGhostEffect();

    // 添加Ghost样式类
    sourceElement.classList.add('drag-ghost');
    sourceElement.style.opacity = this.config.ghostOpacity.toString();
    sourceElement.style.transition = `all ${this.config.animationDuration}ms ease-out`;

    this.state.ghostElement = sourceElement;
  }

  /**
   * 移除Ghost效果
   */
  private removeGhostEffect(): void {
    if (this.state.ghostElement) {
      this.state.ghostElement.classList.remove('drag-ghost');
      this.state.ghostElement.style.opacity = '';
      this.state.ghostElement.style.transition = '';
      this.state.ghostElement = null;
    }
  }

  /**
   * 更新预览位置
   */
  private updatePreviewPosition(): void {
    if (!this.state.previewElement) return;

    const { x, y } = this.state.mousePosition;
    const { x: offsetX, y: offsetY } = this.config.offset;

    this.state.previewElement.style.left = `${x + offsetX}px`;
    this.state.previewElement.style.top = `${y + offsetY}px`;
  }

  /**
   * 处理鼠标移动
   */
  private handleMouseMove(event: MouseEvent | DragEvent): void {
    this.updatePosition({ x: event.clientX, y: event.clientY });
  }

  /**
   * 清理克隆元素
   */
  private cleanupClonedElement(element: HTMLElement): void {
    // 移除ID属性避免重复
    element.removeAttribute('id');

    // 移除所有子元素的ID
    const elementsWithId = element.querySelectorAll('[id]');
    elementsWithId.forEach((el) => el.removeAttribute('id'));

    // 移除事件监听器相关属性
    element.removeAttribute('draggable');

    // 移除可能影响布局的样式
    element.style.position = 'static';
    element.style.transform = '';
    element.style.zIndex = '';

    // 添加预览样式类
    element.classList.add('drag-preview-content');
  }

  /**
   * 设置自定义预览内容
   */
  setCustomContent(contentGenerator: (item: any) => HTMLElement): void {
    this.config.customContent = contentGenerator;
  }

  /**
   * 更新配置
   */
  updateConfig(newConfig: Partial<DragPreviewConfig>): void {
    this.config = { ...this.config, ...newConfig };
  }

  /**
   * 获取状态
   */
  getState(): DragPreviewState {
    return { ...this.state };
  }

  /**
   * 获取配置
   */
  getConfig(): DragPreviewConfig {
    return { ...this.config };
  }

  /**
   * 销毁预览管理器
   */
  destroy(): void {
    this.endPreview();
  }
}

/**
 * 创建拖拽预览实例
 */
export function createDragPreview(
  container: HTMLElement,
  config?: Partial<DragPreviewConfig>,
): DragPreview {
  return new DragPreview(container, config);
}

/**
 * 预设配置
 */
export const DragPreviewPresets = {
  /** 默认配置 */
  default: {
    enabled: true,
    offset: { x: 10, y: 10 },
    scale: 0.8,
    opacity: 0.9,
    showOriginalContent: true,
    animationDuration: 200,
    enableGhost: true,
    ghostOpacity: 0.3,
  } as DragPreviewConfig,

  /** 简约配置 */
  minimal: {
    enabled: true,
    offset: { x: 5, y: 5 },
    scale: 0.6,
    opacity: 0.7,
    showOriginalContent: false,
    animationDuration: 150,
    enableGhost: true,
    ghostOpacity: 0.5,
  } as Partial<DragPreviewConfig>,

  /** 无预览配置 */
  none: {
    enabled: false,
    enableGhost: true,
    ghostOpacity: 0.4,
  } as Partial<DragPreviewConfig>,

  /** 高性能配置 */
  performance: {
    enabled: true,
    offset: { x: 0, y: 0 },
    scale: 1,
    opacity: 0.8,
    showOriginalContent: false,
    animationDuration: 0,
    enableGhost: false,
  } as Partial<DragPreviewConfig>,
};
