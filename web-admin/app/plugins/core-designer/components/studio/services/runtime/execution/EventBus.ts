/**
 * 事件总线系统
 * 支持跨 Block 的事件通信和编排
 */

import type { ActionContext, EventListener } from '~/plugins/core-designer/components/studio/services/runtime/execution/types';

export interface EventSubscription {
  id: string;
  eventName: string;
  handler: EventHandler;
  scope: EventScope;
  target?: string;
  once?: boolean;
  priority?: number;
}

export interface EventHandler {
  (data: any, context: ActionContext): Promise<void> | void;
}

export type EventScope = 'global' | 'page' | 'block' | 'component';

export interface EventEmitOptions {
  scope?: EventScope;
  target?: string;
  bubbles?: boolean;
  cancelable?: boolean;
  async?: boolean;
}

export interface EventChain {
  id: string;
  name: string;
  trigger: EventTrigger;
  conditions?: EventCondition[];
  actions: EventAction[];
  enabled: boolean;
}

export interface EventTrigger {
  eventName: string;
  scope: EventScope;
  target?: string;
  debounce?: number;
  throttle?: number;
}

export interface EventCondition {
  expression: string;
  description?: string;
}

export interface EventAction {
  type: 'emit' | 'call' | 'delay' | 'condition';
  config: any;
}

/**
 * 事件总线实现
 */
export class EventBus {
  private static instance: EventBus;
  private subscriptions: Map<string, EventSubscription[]> = new Map();
  private eventChains: Map<string, EventChain> = new Map();
  private eventHistory: Array<{ eventName: string; data: any; timestamp: number }> = [];
  private maxHistorySize = 1000;

  private constructor() {}

  public static getInstance(): EventBus {
    if (!EventBus.instance) {
      EventBus.instance = new EventBus();
    }
    return EventBus.instance;
  }

  /**
   * 订阅事件
   */
  public subscribe(
    eventName: string,
    handler: EventHandler,
    options: {
      scope?: EventScope;
      target?: string;
      once?: boolean;
      priority?: number;
    } = {},
  ): string {
    const subscription: EventSubscription = {
      id: `sub_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`,
      eventName,
      handler,
      scope: options.scope || 'global',
      target: options.target,
      once: options.once || false,
      priority: options.priority || 0,
    };

    if (!this.subscriptions.has(eventName)) {
      this.subscriptions.set(eventName, []);
    }

    const subs = this.subscriptions.get(eventName)!;
    subs.push(subscription);

    // 按优先级排序（高优先级先执行）
    subs.sort((a, b) => (b.priority || 0) - (a.priority || 0));

    return subscription.id;
  }

  /**
   * 取消订阅
   */
  public unsubscribe(subscriptionId: string): boolean {
    for (const [eventName, subs] of this.subscriptions.entries()) {
      const index = subs.findIndex((sub) => sub.id === subscriptionId);
      if (index !== -1) {
        subs.splice(index, 1);
        if (subs.length === 0) {
          this.subscriptions.delete(eventName);
        }
        return true;
      }
    }
    return false;
  }

  /**
   * 发布事件
   */
  public async emit(
    eventName: string,
    data: any = {},
    context?: ActionContext,
    options: EventEmitOptions = {},
  ): Promise<void> {
    const baseContext = context ?? this.createDefaultContext();
    // 记录事件历史
    this.recordEvent(eventName, data);

    // 获取订阅者
    const subscriptions = this.getMatchingSubscriptions(eventName, options);

    if (subscriptions.length === 0) {
      return;
    }

    // 创建事件上下文
    const eventContext: ActionContext = {
      ...baseContext,
      eventData: {
        eventName,
        data,
        timestamp: Date.now(),
        scope: options.scope || 'global',
        target: options.target,
      },
    };

    // 执行订阅者
    if (options.async !== false) {
      // 异步执行
      const promises = subscriptions.map((sub) =>
        this.executeSubscription(sub, data, eventContext),
      );
      await Promise.allSettled(promises);
    } else {
      // 同步执行
      for (const subscription of subscriptions) {
        try {
          await this.executeSubscription(subscription, data, eventContext);
        } catch (error) {
          console.error(`Event handler error for ${eventName}:`, error);
        }
      }
    }

    // 处理事件链
    await this.processEventChains(eventName, data, eventContext);
  }

  private createDefaultContext(): ActionContext {
    return {
      componentId: '',
      pageId: '',
      pageState: {},
      globalState: {},
      env: {},
      utils: {
        formatDate: (date: Date) => date.toLocaleDateString(),
        formatNumber: (num: number) => num.toString(),
        validateEmail: (email: string) => /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email),
        generateId: () => Math.random().toString(36).slice(2),
      },
    };
  }

  /**
   * 获取匹配的订阅
   */
  private getMatchingSubscriptions(
    eventName: string,
    options: EventEmitOptions,
  ): EventSubscription[] {
    const allSubs = this.subscriptions.get(eventName) || [];

    return allSubs.filter((sub) => {
      // 检查作用域匹配
      if (options.scope && sub.scope !== 'global' && sub.scope !== options.scope) {
        return false;
      }

      // 检查目标匹配
      if (options.target && sub.target && sub.target !== options.target) {
        return false;
      }

      return true;
    });
  }

  /**
   * 执行订阅
   */
  private async executeSubscription(
    subscription: EventSubscription,
    data: any,
    context: ActionContext,
  ): Promise<void> {
    try {
      await subscription.handler(data, context);

      // 如果是一次性订阅，执行后移除
      if (subscription.once) {
        this.unsubscribe(subscription.id);
      }
    } catch (error) {
      console.error(`Subscription handler error:`, error);
      throw error;
    }
  }

  /**
   * 处理事件链
   */
  private async processEventChains(
    eventName: string,
    data: any,
    context: ActionContext,
  ): Promise<void> {
    const matchingChains = Array.from(this.eventChains.values()).filter(
      (chain) => chain.enabled && chain.trigger.eventName === eventName,
    );

    for (const chain of matchingChains) {
      try {
        await this.executeEventChain(chain, data, context);
      } catch (error) {
        console.error(`Event chain execution error:`, error);
      }
    }
  }

  /**
   * 执行事件链
   */
  private async executeEventChain(
    chain: EventChain,
    data: any,
    context: ActionContext,
  ): Promise<void> {
    // 检查条件
    if (chain.conditions && chain.conditions.length > 0) {
      const { ExpressionEvaluator } =
        await import('~/plugins/core-designer/components/studio/services/runtime/execution/ExpressionEvaluator');

      for (const condition of chain.conditions) {
        const result = ExpressionEvaluator.evaluate(condition.expression, {
          ...context,
          eventData: { ...context.eventData, data },
        });

        if (!result) {
          return; // 条件不满足，不执行
        }
      }
    }

    // 执行动作
    for (const action of chain.actions) {
      await this.executeEventAction(action, data, context);
    }
  }

  /**
   * 执行事件动作
   */
  private async executeEventAction(
    action: EventAction,
    data: any,
    context: ActionContext,
  ): Promise<void> {
    switch (action.type) {
      case 'emit':
        await this.emit(
          action.config.eventName,
          action.config.data || data,
          context,
          action.config.options || {},
        );
        break;

      case 'call':
        const { globalActionScheduler } =
          await import('~/plugins/core-designer/components/studio/services/runtime/execution/ActionScheduler');
        await globalActionScheduler.executeAction(action.config, context);
        break;

      case 'delay':
        await new Promise((resolve) => setTimeout(resolve, action.config.duration || 0));
        break;

      case 'condition':
        const { ExpressionEvaluator } =
          await import('~/plugins/core-designer/components/studio/services/runtime/execution/ExpressionEvaluator');
        const conditionResult = ExpressionEvaluator.evaluate(action.config.condition, context);

        if (conditionResult && action.config.then) {
          for (const thenAction of action.config.then) {
            await this.executeEventAction(thenAction, data, context);
          }
        } else if (!conditionResult && action.config.else) {
          for (const elseAction of action.config.else) {
            await this.executeEventAction(elseAction, data, context);
          }
        }
        break;
    }
  }

  /**
   * 注册事件链
   */
  public registerEventChain(chain: EventChain): void {
    this.eventChains.set(chain.id, chain);
  }

  /**
   * 取消注册事件链
   */
  public unregisterEventChain(chainId: string): boolean {
    return this.eventChains.delete(chainId);
  }

  /**
   * 获取事件链
   */
  public getEventChain(chainId: string): EventChain | undefined {
    return this.eventChains.get(chainId);
  }

  /**
   * 获取所有事件链
   */
  public getAllEventChains(): EventChain[] {
    return Array.from(this.eventChains.values());
  }

  /**
   * 记录事件历史
   */
  private recordEvent(eventName: string, data: any): void {
    this.eventHistory.push({
      eventName,
      data,
      timestamp: Date.now(),
    });

    // 限制历史记录大小
    if (this.eventHistory.length > this.maxHistorySize) {
      this.eventHistory.shift();
    }
  }

  /**
   * 获取事件历史
   */
  public getEventHistory(
    limit?: number,
  ): Array<{ eventName: string; data: any; timestamp: number }> {
    const history = [...this.eventHistory];
    return limit ? history.slice(-limit) : history;
  }

  /**
   * 清空事件历史
   */
  public clearEventHistory(): void {
    this.eventHistory = [];
  }

  /**
   * 获取订阅统计
   */
  public getSubscriptionStats(): Record<string, number> {
    const stats: Record<string, number> = {};

    for (const [eventName, subs] of this.subscriptions.entries()) {
      stats[eventName] = subs.length;
    }

    return stats;
  }

  /**
   * 销毁事件总线
   */
  public destroy(): void {
    this.subscriptions.clear();
    this.eventChains.clear();
    this.eventHistory = [];
  }
}

/**
 * 全局事件总线实例
 */
export const globalEventBus = EventBus.getInstance();

/**
 * 便捷的事件操作函数
 */
export const events = {
  /**
   * 订阅事件
   */
  on: (eventName: string, handler: EventHandler, options?: any) =>
    globalEventBus.subscribe(eventName, handler, options),

  /**
   * 订阅一次性事件
   */
  once: (eventName: string, handler: EventHandler, options?: any) =>
    globalEventBus.subscribe(eventName, handler, { ...options, once: true }),

  /**
   * 取消订阅
   */
  off: (subscriptionId: string) => globalEventBus.unsubscribe(subscriptionId),

  /**
   * 发布事件
   */
  emit: (eventName: string, data?: any, context?: ActionContext, options?: EventEmitOptions) =>
    globalEventBus.emit(eventName, data, context, options),

  /**
   * 获取事件历史
   */
  history: (limit?: number) => globalEventBus.getEventHistory(limit),

  /**
   * 获取订阅统计
   */
  stats: () => globalEventBus.getSubscriptionStats(),
};
