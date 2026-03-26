/**
 * 全局快捷键管理器
 * 基于当前焦点域智能路由快捷键事件，避免不同域之间的快捷键冲突
 */
export class GlobalShortcutManager {
  private isActive = false;
  private domainHandlers: Map<string, DomainHandler> = new Map();
  private currentFocusDomain = 'global';

  constructor() {
    this.initializeDomainHandlers();
  }

  /**
   * 启动全局快捷键管理器
   */
  public start(): void {
    if (this.isActive) return;

    document.addEventListener('keydown', this.handleGlobalKeyDown, true);
    document.addEventListener('focusin', this.handleFocusChange, true);
    document.addEventListener('focusout', this.handleFocusChange, true);

    this.isActive = true;
  }

  /**
   * 停止全局快捷键管理器
   */
  public stop(): void {
    if (!this.isActive) return;

    document.removeEventListener('keydown', this.handleGlobalKeyDown, true);
    document.removeEventListener('focusin', this.handleFocusChange, true);
    document.removeEventListener('focusout', this.handleFocusChange, true);

    this.isActive = false;
  }

  /**
   * 初始化（兼容旧调用）
   */
  public initialize(): void {
    this.start();
  }

  /**
   * 销毁（兼容旧调用）
   */
  public destroy(): void {
    this.stop();
  }

  /**
   * 注册域特定的快捷键处理器
   */
  public registerDomainHandler(domain: string, shortcuts: Record<string, ShortcutHandler>): void {
    this.domainHandlers.set(domain, {
      domain,
      shortcuts,
      priority: this.getDomainPriority(domain),
    });
  }

  /**
   * 移除域快捷键处理器
   */
  public unregisterDomainHandler(domain: string): void {
    this.domainHandlers.delete(domain);
  }

  /**
   * 注册域快捷键处理器（兼容方法）
   */
  public registerDomain(domain: string, shortcuts: Record<string, ShortcutHandler>): void {
    this.registerDomainHandler(domain, shortcuts);
  }

  /**
   * 取消注册域快捷键处理器（兼容方法）
   */
  public unregisterDomain(domain: string): void {
    this.unregisterDomainHandler(domain);
  }

  /**
   * 获取当前焦点域
   */
  public getCurrentFocusDomain(): string {
    return this.currentFocusDomain;
  }

  /**
   * 处理全局键盘事件
   */
  private handleGlobalKeyDown = (event: KeyboardEvent): void => {
    const target = event.target as HTMLElement;
    const domain = this.determineEventDomain(event);

    // 根据当前焦点域路由快捷键
    const handler = this.getDomainHandler(domain);
    if (handler && this.shouldHandleShortcut(event, domain)) {
      const shortcut = this.buildShortcutString(event);
      const shortcutHandler = handler.shortcuts[shortcut];

      if (shortcutHandler) {
        event.preventDefault();
        shortcutHandler(event, target);
      }
    }
  };

  /**
   * 处理焦点变化事件
   */
  private handleFocusChange = (event: FocusEvent): void => {
    const target = event.target as HTMLElement;
    const newDomain = this.determineElementDomain(target);

    if (newDomain !== this.currentFocusDomain) {
      const oldDomain = this.currentFocusDomain;
      this.currentFocusDomain = newDomain;

      // 触发域切换事件
      this.dispatchDomainChangeEvent(oldDomain, newDomain);
    }
  };

  /**
   * 确定事件所属的域
   */
  private determineEventDomain(event: Event): string {
    const target = event.target as HTMLElement;
    return this.determineElementDomain(target);
  }

  /**
   * 确定元素所属的域
   */
  private determineElementDomain(element: HTMLElement): string {
    if (!element) return 'global';

    // 检查元素是否在画布域内
    if (this.isInCanvasDomain(element)) {
      return 'canvas';
    }

    // 检查元素是否在属性面板域内
    if (this.isInPropertyPanelDomain(element)) {
      return 'property-panel';
    }

    // 检查元素是否在输入域内
    if (this.isInInputDomain(element)) {
      return 'input';
    }

    return 'global';
  }

  /**
   * 检查元素是否在画布域内
   */
  private isInCanvasDomain(element: HTMLElement): boolean {
    return this.findDomainElement(element, 'canvas') !== null;
  }

  /**
   * 检查元素是否在属性面板域内
   */
  private isInPropertyPanelDomain(element: HTMLElement): boolean {
    return this.findDomainElement(element, 'property-panel') !== null;
  }

  /**
   * 检查元素是否在输入域内
   */
  private isInInputDomain(element: HTMLElement): boolean {
    const tagName = element.tagName.toLowerCase();
    const inputTypes = ['input', 'textarea', 'select'];

    return (
      inputTypes.includes(tagName) ||
      element.isContentEditable ||
      element.closest('.form-control, .input-field, .property-editor') !== null
    );
  }

  /**
   * 查找指定域的元素
   */
  private findDomainElement(element: HTMLElement, domain: string): HTMLElement | null {
    let current: HTMLElement | null = element;

    while (current && current !== document.body) {
      const dataDomain = current.getAttribute('data-domain');
      if (dataDomain === domain) {
        return current;
      }
      current = current.parentElement;
    }

    return null;
  }

  /**
   * 获取域处理器
   */
  private getDomainHandler(domain: string): DomainHandler | null {
    // 优先使用特定域的处理器
    const specificHandler = this.domainHandlers.get(domain);
    if (specificHandler) {
      return specificHandler;
    }

    // 回退到全局处理器
    return this.domainHandlers.get('global') || null;
  }

  /**
   * 判断是否应处理快捷键
   */
  private shouldHandleShortcut(event: KeyboardEvent, domain: string): boolean {
    // 输入域内的快捷键需要特殊处理
    if (domain === 'input') {
      // 只允许特定的系统快捷键（如Ctrl+Z, Ctrl+Y等）
      const isSystemShortcut = event.ctrlKey || event.metaKey;
      const allowedShortcuts = ['z', 'y', 'x', 'c', 'v', 'a'];

      if (isSystemShortcut && allowedShortcuts.includes(event.key.toLowerCase())) {
        return false; // 允许系统快捷键，不拦截
      }

      return true; // 其他输入域快捷键需要处理
    }

    return true; // 其他域的快捷键都处理
  }

  /**
   * 构建快捷键字符串
   */
  private buildShortcutString(event: KeyboardEvent): string {
    const parts: string[] = [];

    if (event.ctrlKey || event.metaKey) parts.push('ctrl');
    if (event.altKey) parts.push('alt');
    if (event.shiftKey) parts.push('shift');

    parts.push(event.key.toLowerCase());

    return parts.join('+');
  }

  /**
   * 获取域优先级
   */
  private getDomainPriority(domain: string): number {
    const priorities: Record<string, number> = {
      canvas: 3,
      'property-panel': 2,
      input: 1,
      global: 0,
    };

    return priorities[domain] || 0;
  }

  /**
   * 初始化默认域处理器
   */
  private initializeDomainHandlers(): void {
    // 全局默认处理器
    this.registerDomainHandler('global', {
      escape: (_event, _target) => {
        // Global escape handler - no-op by default
      },
    });
  }

  /**
   * 分发域切换事件
   */
  private dispatchDomainChangeEvent(oldDomain: string, newDomain: string): void {
    const event = new CustomEvent('domain:changed', {
      bubbles: false,
      cancelable: true,
      detail: { oldDomain, newDomain },
    });

    document.dispatchEvent(event);
  }

  /**
   * 获取状态信息
   */
  public getStatus(): { isActive: boolean; currentDomain: string; handlerCount: number } {
    return {
      isActive: this.isActive,
      currentDomain: this.currentFocusDomain,
      handlerCount: this.domainHandlers.size,
    };
  }
}

// 类型定义
export interface ShortcutHandler {
  (event: KeyboardEvent, target: HTMLElement): void;
}

export interface DomainHandler {
  domain: string;
  shortcuts: Record<string, ShortcutHandler>;
  priority: number;
}

// 单例实例
export const globalShortcutManager = new GlobalShortcutManager();
