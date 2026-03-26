/**
 * 事件域总管理器
 * 协调各域之间的事件传递，管理事件域的生命周期
 */
import { canvasEventDomain } from '~/studio/services/actions/event/CanvasEventDomain';
import { globalShortcutManager } from '~/studio/services/actions/event/GlobalShortcutManager';

export class EventDomainManager {
  private isInitialized = false;
  private domains: Map<string, EventDomain> = new Map();
  private eventInterceptors: Map<string, EventInterceptor[]> = new Map();

  constructor() {
    this.initializeDefaultDomains();
  }

  /**
   * 初始化事件域管理器
   */
  public initialize(): void {
    if (this.isInitialized) return;

    // 启动各个域管理器
    globalShortcutManager.start();

    // 设置事件拦截器
    this.setupEventInterceptors();

    this.isInitialized = true;
  }

  /**
   * 销毁事件域管理器
   */
  public destroy(): void {
    if (!this.isInitialized) return;

    // 停止各个域管理器
    globalShortcutManager.stop();
    canvasEventDomain.destroy();

    // 清理事件拦截器
    this.cleanupEventInterceptors();

    this.domains.clear();
    this.eventInterceptors.clear();

    this.isInitialized = false;
  }

  /**
   * 注册事件域
   */
  public registerDomain(domain: EventDomain): void {
    this.domains.set(domain.name, domain);

    // 如果是画布域，初始化画布事件
    if (domain.name === 'canvas' && domain.element) {
      canvasEventDomain.initialize(domain.element);
    }
  }

  /**
   * 注销事件域
   */
  public unregisterDomain(domainName: string): void {
    const domain = this.domains.get(domainName);
    if (domain) {
      if (domainName === 'canvas') {
        canvasEventDomain.destroy();
      }
      this.domains.delete(domainName);
    }
  }

  /**
   * 获取事件域
   */
  public getDomain(domainName: string): EventDomain | undefined {
    return this.domains.get(domainName);
  }

  /**
   * 注册事件拦截器
   */
  public registerEventInterceptor(eventType: string, interceptor: EventInterceptor): void {
    if (!this.eventInterceptors.has(eventType)) {
      this.eventInterceptors.set(eventType, []);
    }
    this.eventInterceptors.get(eventType)!.push(interceptor);
  }

  /**
   * 分发事件到指定域
   */
  public dispatchEvent(event: DomainEvent): void {
    const { targetDomain, eventType, data } = event;

    // 执行事件拦截器
    this.executeInterceptors(event);

    // 根据事件类型和目标域进行分发
    switch (eventType) {
      case 'component:selected':
        this.handleComponentSelected(data);
        break;
      case 'component:deselected':
        this.handleComponentDeselected(data);
        break;
      case 'component:deleted':
        this.handleComponentDeleted(data);
        break;
      case 'canvas:cleared':
        this.handleCanvasCleared();
        break;
      case 'property:updated':
        this.handlePropertyUpdated(data);
        break;
      default:
        this.dispatchToDomain(targetDomain, event);
    }
  }

  /**
   * 获取当前活动域
   */
  public getActiveDomain(): string {
    return globalShortcutManager.getCurrentFocusDomain();
  }

  /**
   * 获取所有域的状态
   */
  public getDomainStatus(): DomainStatus {
    return {
      isInitialized: this.isInitialized,
      activeDomain: this.getActiveDomain(),
      domains: Array.from(this.domains.keys()),
      canvasStatus: canvasEventDomain.getStatus(),
      shortcutStatus: globalShortcutManager.getStatus(),
    };
  }

  /**
   * 初始化默认事件域
   */
  private initializeDefaultDomains(): void {
    // 画布域
    this.registerDomain({
      name: 'canvas',
      element: null,
      isActive: false,
      handlers: {},
    });

    // 属性面板域
    this.registerDomain({
      name: 'property-panel',
      element: null,
      isActive: false,
      handlers: {},
    });

    // 输入域
    this.registerDomain({
      name: 'input',
      element: null,
      isActive: false,
      handlers: {},
    });
  }

  /**
   * 设置事件拦截器
   */
  private setupEventInterceptors(): void {
    // 画布事件拦截器
    this.registerEventInterceptor('canvas:click', (event) => {
      return event;
    });

    // 属性更新拦截器
    this.registerEventInterceptor('property:updated', (event) => {
      return event;
    });
  }

  /**
   * 清理事件拦截器
   */
  private cleanupEventInterceptors(): void {
    this.eventInterceptors.clear();
  }

  /**
   * 执行事件拦截器
   */
  private executeInterceptors(event: DomainEvent): DomainEvent {
    const interceptors = this.eventInterceptors.get(event.eventType) || [];

    return interceptors.reduce((processedEvent, interceptor) => {
      return interceptor(processedEvent);
    }, event);
  }

  /**
   * 分发事件到指定域
   */
  private dispatchToDomain(targetDomain: string, event: DomainEvent): void {
    const domain = this.domains.get(targetDomain);
    if (domain && domain.handlers[event.eventType]) {
      domain.handlers[event.eventType](event);
    }
  }

  /**
   * 处理组件选择事件
   */
  private handleComponentSelected(data: any): void {
    // 同步到其他相关域
    this.syncComponentState('selected', data);
  }

  /**
   * 处理组件取消选择事件
   */
  private handleComponentDeselected(data: any): void {
    // 同步到其他相关域
    this.syncComponentState('deselected', data);
  }

  /**
   * 处理组件删除事件
   */
  private handleComponentDeleted(data: any): void {
    // 清理相关域的状态
    this.cleanupComponentState(data);
  }

  /**
   * 处理画布清空事件
   */
  private handleCanvasCleared(): void {
    // 重置所有组件相关的状态
    this.resetComponentStates();
  }

  /**
   * 处理属性更新事件
   */
  private handlePropertyUpdated(data: any): void {
    // 同步到画布域
    const canvasDomain = this.domains.get('canvas');
    if (canvasDomain && canvasDomain.handlers['property:updated']) {
      canvasDomain.handlers['property:updated']({ data });
    }
  }

  /**
   * 同步组件状态
   */
  private syncComponentState(state: string, data: any): void {
    // 将组件状态同步到属性面板域
    const propertyPanelDomain = this.domains.get('property-panel');
    if (propertyPanelDomain && propertyPanelDomain.handlers[`component:${state}`]) {
      propertyPanelDomain.handlers[`component:${state}`]({ data });
    }
  }

  /**
   * 清理组件状态
   */
  private cleanupComponentState(data: any): void {
    // 清理属性面板域中的组件状态
    const propertyPanelDomain = this.domains.get('property-panel');
    if (propertyPanelDomain && propertyPanelDomain.handlers['component:cleanup']) {
      propertyPanelDomain.handlers['component:cleanup']({ data });
    }
  }

  /**
   * 重置组件状态
   */
  private resetComponentStates(): void {
    // 重置所有域的组件状态
    this.domains.forEach((domain, domainName) => {
      if (domain.handlers['canvas:reset']) {
        domain.handlers['canvas:reset']();
      }
    });
  }
}

// 类型定义
export interface EventDomain {
  id?: string;
  name: string;
  element: HTMLElement | null;
  isActive: boolean;
  handlers: Record<string, (event?: any) => void>;
  priority?: number;
}

export interface DomainEvent {
  eventType: string;
  targetDomain: string;
  data?: any;
  timestamp?: number;
}

export interface EventInterceptor {
  (event: DomainEvent): DomainEvent;
}

export interface DomainStatus {
  isInitialized: boolean;
  activeDomain: string;
  domains: string[];
  canvasStatus: any;
  shortcutStatus: any;
}

// 单例实例
export const eventDomainManager = new EventDomainManager();
