/**
 * 画布事件域管理器
 * 负责管理画布区域内的所有事件处理，包括选择、拖拽、多选和快捷键
 * 防止事件冒泡到属性面板等其他域
 */
export class CanvasEventDomain {
  private canvasElement: HTMLElement | null = null;
  private isActive = false;
  private eventHandlers: Map<string, EventListener> = new Map();
  private selectionHandlers: Map<string, (id: string) => void> = new Map();
  private dragHandlers: Map<string, (event: DragEvent) => void> = new Map();

  constructor() {
    this.initializeEventHandlers();
  }

  /**
   * 初始化画布事件域
   */
  public initialize(canvasElement: HTMLElement): void {
    this.canvasElement = canvasElement;
    this.attachEventListeners();
    this.isActive = true;
  }

  /**
   * 销毁画布事件域
   */
  public destroy(): void {
    this.detachEventListeners();
    this.canvasElement = null;
    this.isActive = false;
  }

  /**
   * 注册选择事件处理器
   */
  public onSelection(handler: (id: string) => void): string {
    const id = this.generateId('selection');
    this.selectionHandlers.set(id, handler);
    return id;
  }

  /**
   * 注册拖拽事件处理器
   */
  public onDrag(handler: (event: DragEvent) => void): string {
    const id = this.generateId('drag');
    this.dragHandlers.set(id, handler);
    return id;
  }

  /**
   * 移除事件处理器
   */
  public off(handlerId: string): void {
    this.selectionHandlers.delete(handlerId);
    this.dragHandlers.delete(handlerId);
  }

  /**
   * 处理画布点击事件
   */
  private handleCanvasClick = (event: MouseEvent): void => {
    // 只处理画布内的点击，忽略属性面板等区域的点击
    const target = event.target as HTMLElement;
    const componentId = this.findComponentId(target);

    if (componentId) {
      event.preventDefault();
      event.stopPropagation();

      // 触发所有选择处理器
      this.selectionHandlers.forEach((handler) => {
        handler(componentId);
      });
    }
  };

  /**
   * 处理画布拖拽事件
   */
  private handleCanvasDragStart = (event: DragEvent): void => {
    const target = event.target as HTMLElement;
    const componentId = this.findComponentId(target);

    if (componentId) {
      event.preventDefault();
      event.stopPropagation();

      // 触发所有拖拽处理器
      this.dragHandlers.forEach((handler) => {
        handler(event);
      });
    }
  };

  /**
   * 处理画布键盘事件
   */
  private handleCanvasKeyDown = (event: KeyboardEvent): void => {
    // 确保事件来自画布域
    if (!this.isEventFromCanvas(event)) {
      return;
    }

    const key = event.key;

    switch (key) {
      case 'Delete':
      case 'Backspace':
        event.preventDefault();
        this.handleDelete();
        break;
      case 'Escape':
        event.preventDefault();
        this.handleEscape();
        break;
      case 'a':
        if (event.ctrlKey || event.metaKey) {
          event.preventDefault();
          this.handleSelectAll();
        }
        break;
      case 'z':
        if (event.ctrlKey || event.metaKey) {
          event.preventDefault();
          this.handleUndo();
        }
        break;
      case 'y':
        if (event.ctrlKey || event.metaKey) {
          event.preventDefault();
          this.handleRedo();
        }
        break;
    }
  };

  /**
   * 查找组件ID
   */
  private findComponentId(element: HTMLElement): string | null {
    let current: HTMLElement | null = element;

    while (current && current !== this.canvasElement) {
      const componentId = current.getAttribute('data-component-id');
      if (componentId) {
        return componentId;
      }
      current = current.parentElement;
    }

    return null;
  }

  /**
   * 检查事件是否来自画布域
   */
  private isEventFromCanvas(event: Event): boolean {
    const target = event.target as HTMLElement;
    return (target && this.canvasElement?.contains(target)) || false;
  }

  /**
   * 处理删除操作
   */
  private handleDelete(): void {
    // 触发删除操作，由外部处理器实现具体逻辑
    this.dispatchCanvasEvent('canvas:delete');
  }

  /**
   * 处理取消选择操作
   */
  private handleEscape(): void {
    this.dispatchCanvasEvent('canvas:escape');
  }

  /**
   * 处理全选操作
   */
  private handleSelectAll(): void {
    this.dispatchCanvasEvent('canvas:select-all');
  }

  /**
   * 处理撤销操作
   */
  private handleUndo(): void {
    this.dispatchCanvasEvent('canvas:undo');
  }

  /**
   * 处理重做操作
   */
  private handleRedo(): void {
    this.dispatchCanvasEvent('canvas:redo');
  }

  /**
   * 分发画布事件
   */
  private dispatchCanvasEvent(eventType: string): void {
    const event = new CustomEvent(eventType, {
      bubbles: false, // 防止冒泡到其他域
      cancelable: true,
      detail: { domain: 'canvas' },
    });

    this.canvasElement?.dispatchEvent(event);
  }

  /**
   * 初始化事件处理器映射
   */
  private initializeEventHandlers(): void {
    this.eventHandlers.set('click', this.handleCanvasClick as EventListener);
    this.eventHandlers.set('dragstart', this.handleCanvasDragStart as EventListener);
    this.eventHandlers.set('keydown', this.handleCanvasKeyDown as EventListener);
  }

  /**
   * 附加事件监听器
   */
  private attachEventListeners(): void {
    if (!this.canvasElement) return;

    this.eventHandlers.forEach((handler, eventType) => {
      this.canvasElement?.addEventListener(eventType, handler as EventListener, true);
    });
  }

  /**
   * 移除事件监听器
   */
  private detachEventListeners(): void {
    if (!this.canvasElement) return;

    this.eventHandlers.forEach((handler, eventType) => {
      this.canvasElement?.removeEventListener(eventType, handler as EventListener, true);
    });
  }

  /**
   * 生成唯一ID
   */
  private generateId(prefix: string): string {
    return `${prefix}-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
  }

  /**
   * 获取当前状态
   */
  public getStatus(): { isActive: boolean; hasCanvas: boolean } {
    return {
      isActive: this.isActive,
      hasCanvas: !!this.canvasElement,
    };
  }
}

// 单例实例
export const canvasEventDomain = new CanvasEventDomain();
