/**
 * 动作调度器
 * 负责管理和执行动作链
 */

import type {
  Action,
  ActionContext,
  ActionResult,
  ActionExecutor,
  ActionChain,
  ActionChainResult,
  ActionSchedulerConfig,
  ActionMetrics,
  EventListener,
} from '~/plugins/core-designer/components/studio/services/runtime/execution/types';

import {
  NavigateActionExecutor,
  DataActionExecutor,
  FormActionExecutor,
  UIActionExecutor,
  StateActionExecutor,
  EventActionExecutor,
  ConditionActionExecutor,
} from '~/plugins/core-designer/components/studio/services/runtime/execution/ActionExecutor';

/**
 * 动作调度器实现
 */
export class ActionScheduler {
  private static instance: ActionScheduler | null = null;

  static getInstance(): ActionScheduler {
    if (!ActionScheduler.instance) {
      ActionScheduler.instance = new ActionScheduler({
        enableMetrics: true,
        enableLogging: process.env.NODE_ENV === 'development',
      });
    }
    return ActionScheduler.instance;
  }
  private executors: Map<string, ActionExecutor> = new Map();
  private eventListeners: Map<string, EventListener[]> = new Map();
  private config: ActionSchedulerConfig;
  private metrics: ActionMetrics = {
    totalExecutions: 0,
    successCount: 0,
    errorCount: 0,
    averageDuration: 0,
    lastExecuted: 0,
    errorRate: 0,
  };
  // Internal history not in interface
  private executionHistory: Array<{
    timestamp: number;
    duration: number;
    success: boolean;
    actionType: string;
  }> = [];

  constructor(config: Partial<ActionSchedulerConfig> = {}) {
    this.config = {
      maxConcurrentActions: 10,
      defaultTimeout: 30000,
      enableLogging: true,
      enableMetrics: true,
      retryPolicy: {
        maxRetries: 3,
        backoffStrategy: 'exponential',
        baseDelay: 1000,
      },
      ...config,
    } as ActionSchedulerConfig;

    this.initializeExecutors();
  }

  /**
   * 初始化执行器
   */
  private initializeExecutors(): void {
    const executors = [
      new NavigateActionExecutor(),
      new DataActionExecutor(),
      new FormActionExecutor(),
      new UIActionExecutor(),
      new StateActionExecutor(),
      new EventActionExecutor(),
      new ConditionActionExecutor(),
    ];

    executors.forEach((executor) => {
      this.registerExecutor(executor);
    });
  }

  /**
   * 注册执行器
   */
  public registerExecutor(executor: ActionExecutor): void {
    const key = executor.constructor.name;
    this.executors.set(key, executor);
  }

  /**
   * 执行单个动作
   */
  public async executeAction(action: Action, context: ActionContext): Promise<ActionResult> {
    const startTime = Date.now();

    try {
      // 查找合适的执行器
      const executor = this.findExecutor(action.params.type);
      if (!executor) {
        throw new Error(`No executor found for action type: ${action.params.type}`);
      }

      // 设置超时
      const timeoutPromise = new Promise<ActionResult>((_, reject) => {
        setTimeout(() => {
          reject(new Error(`Action timeout after ${this.config.defaultTimeout}ms`));
        }, this.config.defaultTimeout);
      });

      // 执行动作
      const executionPromise = this.executeWithRetry(executor, action, context);
      const result = await Promise.race([executionPromise, timeoutPromise]);

      // 更新指标
      this.updateMetrics(result, Date.now() - startTime);

      // 记录日志

      return result;
    } catch (error) {
      const result: ActionResult = {
        success: false,
        error: {
          code: 'execution_error',
          message: error instanceof Error ? error.message : '动作执行失败',
          details: error,
        },
        duration: Date.now() - startTime,
        timestamp: Date.now(),
      };

      this.updateMetrics(result, result.duration);
      return result;
    }
  }

  /**
   * 执行动作链
   */
  public async executeActionChain(
    actionChain: ActionChain,
    context: ActionContext,
  ): Promise<ActionChainResult> {
    const startTime = Date.now();
    const results: ActionResult[] = [];
    let currentContext = { ...context };

    try {
      for (const action of actionChain.actions) {
        // 检查是否应该继续执行
        if (actionChain.stopOnError && results.some((r) => !r.success)) {
          break;
        }

        // 执行动作
        const result = await this.executeAction(action, currentContext);
        results.push(result);

        // 更新上下文
        if (result.success && result.data) {
          currentContext = this.mergeContext(currentContext, result.data);
        }

        // 处理并发限制
        if (results.length >= this.config.maxConcurrentActions) {
          await this.waitForCompletion(results);
        }
      }

      const chainResult: ActionChainResult = {
        success: results.every((r) => r.success),
        results,
        totalDuration: Date.now() - startTime,
        timestamp: Date.now(),
      };

      if (!chainResult.success) {
        chainResult.error = {
          actionId: results.find((r) => !r.success)?.data?.id || 'unknown',
          error: results.find((r) => !r.success)?.error,
        };
      }

      return chainResult;
    } catch (error) {
      return {
        success: false,
        results,
        error: {
          actionId: 'unknown',
          error: {
            code: 'chain_execution_error',
            message: error instanceof Error ? error.message : '动作链执行失败',
            details: error,
          },
        },
        totalDuration: Date.now() - startTime,
        timestamp: Date.now(),
      };
    }
  }

  /**
   * 查找执行器
   */
  private findExecutor(actionType: string): ActionExecutor | undefined {
    for (const executor of this.executors.values()) {
      if (executor.canExecute(actionType as any)) {
        return executor;
      }
    }
    return undefined;
  }

  /**
   * 带重试的执行
   */
  private async executeWithRetry(
    executor: ActionExecutor,
    action: Action,
    context: ActionContext,
  ): Promise<ActionResult> {
    let lastError: Error | undefined;
    const maxRetries = this.config.retryPolicy.maxRetries;

    for (let attempt = 0; attempt <= maxRetries; attempt++) {
      try {
        return await executor.execute(action, context);
      } catch (error) {
        lastError = error instanceof Error ? error : new Error(String(error));

        if (attempt < maxRetries) {
          await this.delay(this.config.retryPolicy.baseDelay * Math.pow(2, attempt));
        }
      }
    }

    throw lastError;
  }

  /**
   * 延迟函数
   */
  private delay(ms: number): Promise<void> {
    return new Promise((resolve) => setTimeout(resolve, ms));
  }

  /**
   * 合并上下文
   */
  private mergeContext(currentContext: ActionContext, newData: any): ActionContext {
    return {
      ...currentContext,
      pageState: {
        ...currentContext.pageState,
        ...newData,
      },
    };
  }

  /**
   * 等待完成
   */
  private async waitForCompletion(results: ActionResult[]): Promise<void> {
    // 简单实现，实际可以更复杂
    await new Promise((resolve) => setTimeout(resolve, 100));
  }

  /**
   * 更新指标
   */
  private updateMetrics(result: ActionResult, duration: number): void {
    if (!this.config.enableMetrics) return;

    this.metrics.totalExecutions++;
    this.metrics.lastExecuted = Date.now();

    if (result.success) {
      this.metrics.successCount++;
    } else {
      this.metrics.errorCount++;
    }

    // 更新平均执行时间
    const totalTime = this.metrics.averageDuration * (this.metrics.totalExecutions - 1) + duration;
    this.metrics.averageDuration = totalTime / this.metrics.totalExecutions;

    // 更新错误率
    this.metrics.errorRate = this.metrics.errorCount / this.metrics.totalExecutions;

    // 记录执行历史（保留最近100条）
    this.executionHistory.push({
      timestamp: Date.now(),
      duration,
      success: result.success,
      actionType: result.error?.code || 'success',
    });

    if (this.executionHistory.length > 100) {
      this.executionHistory.shift();
    }
  }

  /**
   * 添加事件监听器
   */
  public addEventListener(eventName: string, listener: EventListener): void {
    if (!this.eventListeners.has(eventName)) {
      this.eventListeners.set(eventName, []);
    }
    this.eventListeners.get(eventName)!.push(listener);
  }

  /**
   * 移除事件监听器
   */
  public removeEventListener(eventName: string, listener: EventListener): void {
    const listeners = this.eventListeners.get(eventName);
    if (listeners) {
      const index = listeners.indexOf(listener);
      if (index > -1) {
        listeners.splice(index, 1);
      }
    }
  }

  /**
   * 触发事件
   */
  public async emitEvent(eventName: string, data: any, context: ActionContext): Promise<void> {
    const listeners = this.eventListeners.get(eventName);
    if (!listeners || listeners.length === 0) return;

    const promises = listeners.map((listener) => {
      // Create an ad-hoc action chain for the event listener actions
      const actionChain: ActionChain = {
        id: `event_chain_${Date.now()}_${Math.random()}`,
        name: `Event Chain: ${eventName}`,
        actions: listener.actions,
        stopOnError: true,
      };

      return this.executeActionChain(actionChain, { ...context, eventData: data });
    });

    await Promise.allSettled(promises);
  }

  /**
   * 获取指标
   */
  public getMetrics(): ActionMetrics {
    return { ...this.metrics };
  }

  /**
   * 重置指标
   */
  public resetMetrics(): void {
    this.metrics = {
      totalExecutions: 0,
      successCount: 0,
      errorCount: 0,
      averageDuration: 0,
      lastExecuted: 0,
      errorRate: 0,
    };
    this.executionHistory = [];
  }

  /**
   * 获取已注册的执行器
   */
  public getRegisteredExecutors(): string[] {
    return Array.from(this.executors.keys());
  }

  /**
   * 销毁调度器
   */
  public destroy(): void {
    this.executors.clear();
    this.eventListeners.clear();
    this.resetMetrics();
  }

  public async execute(action: Action, context: ActionContext): Promise<ActionResult> {
    return this.executeAction(action, context);
  }

  public async executeChain(
    actionChain: ActionChain,
    context: ActionContext,
  ): Promise<ActionChainResult> {
    return this.executeActionChain(actionChain, context);
  }
}

/**
 * 全局动作调度器实例
 */
export const globalActionScheduler = ActionScheduler.getInstance();
