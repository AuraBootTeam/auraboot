/**
 * 统一动作协议实现
 * 提供 actions.* 标准 API
 */

import type {
  Action,
  ActionContext,
  ActionResult,
  ActionChain,
  ActionChainResult,
  BaseActionParams,
  NavigateActionParams,
  FetchDataActionParams,
  FormSubmitActionParams,
  ShowModalActionParams,
  ShowToastActionParams,
  SetStateActionParams,
  EmitEventActionParams,
  RefreshActionParams,
  IfConditionActionParams,
} from '~/plugins/core-designer/components/studio/services/runtime/execution/types';
import { ActionType } from '~/plugins/core-designer/components/studio/services/runtime/execution/types';
import { ActionRegistry } from '~/plugins/core-designer/components/studio/services/runtime/execution/ActionRegistry';
import { ActionScheduler } from '~/plugins/core-designer/components/studio/services/runtime/execution/ActionScheduler';
import { ExpressionEvaluator } from '~/plugins/core-designer/components/studio/services/runtime/execution/ExpressionEvaluator';

/**
 * 统一动作协议类
 * 提供标准的 actions.* API
 */
export class ActionProtocol {
  private static instance: ActionProtocol;
  private registry: ActionRegistry;
  private scheduler: ActionScheduler;
  private evaluator: ExpressionEvaluator;

  private constructor() {
    this.registry = ActionRegistry.getInstance();
    this.scheduler = ActionScheduler.getInstance();
    this.evaluator = ExpressionEvaluator.getInstance();
  }

  static getInstance(): ActionProtocol {
    if (!ActionProtocol.instance) {
      ActionProtocol.instance = new ActionProtocol();
    }
    return ActionProtocol.instance;
  }

  /**
   * 导航动作 - actions.navigate(path, options)
   */
  async navigate(
    path: string,
    options: {
      target?: '_self' | '_blank' | '_parent' | '_top';
      replace?: boolean;
      params?: Record<string, any>;
      query?: Record<string, any>;
    } = {},
    context?: ActionContext,
  ): Promise<ActionResult> {
    const action: Action = {
      id: this.generateId(),
      params: {
        type: ActionType.NAVIGATE,
        url: path,
        target: options.target || '_self',
        replace: options.replace || false,
        params: options.params,
        query: options.query,
      } as NavigateActionParams,
    };

    return this.executeAction(action, context);
  }

  /**
   * 页面刷新 - actions.refresh(id?)
   */
  async refresh(id?: string, context?: ActionContext): Promise<ActionResult> {
    if (id) {
      // 刷新特定组件
      return this.emitEvent('component:refresh', { componentId: id }, {}, context);
    } else {
      // 刷新整个页面
      const action: Action = {
        id: this.generateId(),
        params: {
          type: ActionType.REFRESH,
        } as RefreshActionParams,
      };
      return this.executeAction(action, context);
    }
  }

  /**
   * API 调用 - actions.callApi(url, options)
   */
  async callApi(
    url: string,
    options: {
      method?: 'get' | 'post' | 'put' | 'delete' | 'patch';
      headers?: Record<string, string>;
      body?: any;
      params?: Record<string, any>;
      responseType?: 'json' | 'text' | 'blob' | 'arrayBuffer';
      stateKey?: string;
    } = {},
    context?: ActionContext,
  ): Promise<ActionResult> {
    const action: Action = {
      id: this.generateId(),
      params: {
        type: ActionType.FETCH_DATA,
        url,
        method: options.method || 'get',
        headers: options.headers,
        body: options.body,
        params: options.params,
        responseType: options.responseType || 'json',
        stateKey: options.stateKey,
      } as FetchDataActionParams,
    };

    return this.executeAction(action, context);
  }

  /**
   * 状态设置 - actions.setState(patch)
   */
  async setState(
    patch: Record<string, any> | string,
    value?: any,
    options: {
      scope?: 'page' | 'global' | 'session' | 'local';
      merge?: boolean;
    } = {},
    context?: ActionContext,
  ): Promise<ActionResult> {
    let key: string;
    let val: any;

    if (typeof patch === 'string') {
      key = patch;
      val = value;
    } else {
      // 批量设置状态
      const results: ActionResult[] = [];
      for (const [k, v] of Object.entries(patch)) {
        const result = await this.setState(k, v, options, context);
        results.push(result);
      }

      return {
        success: results.every((r) => r.success),
        data: results,
        duration: results.reduce((sum, r) => sum + r.duration, 0),
        timestamp: Date.now(),
      };
    }

    const action: Action = {
      id: this.generateId(),
      params: {
        type: ActionType.SET_STATE,
        key,
        value: val,
        scope: options.scope || 'page',
        merge: options.merge !== false,
      } as SetStateActionParams,
    };

    return this.executeAction(action, context);
  }

  /**
   * 消息提示 - actions.message(type, text, options)
   */
  async message(
    variant: 'success' | 'error' | 'warning' | 'info',
    text: string,
    options: {
      duration?: number;
      position?: 'top' | 'bottom' | 'center';
    } = {},
    context?: ActionContext,
  ): Promise<ActionResult> {
    const action: Action = {
      id: this.generateId(),
      params: {
        type: ActionType.SHOW_TOAST,
        message: text,
        variant,
        duration: options.duration || 3000,
        position: options.position || 'top',
      } as ShowToastActionParams,
    };

    return this.executeAction(action, context);
  }

  /**
   * 显示模态框 - actions.showModal(modalId, options)
   */
  async showModal(
    modalId: string,
    options: {
      title?: string;
      content?: string;
      size?: 'sm' | 'md' | 'lg' | 'xl' | 'full';
      closable?: boolean;
      maskClosable?: boolean;
      data?: any;
    } = {},
    context?: ActionContext,
  ): Promise<ActionResult> {
    const action: Action = {
      id: this.generateId(),
      params: {
        type: ActionType.SHOW_MODAL,
        modalId,
        title: options.title,
        content: options.content,
        size: options.size || 'md',
        closable: options.closable !== false,
        maskClosable: options.maskClosable !== false,
        data: options.data,
      } as ShowModalActionParams,
    };

    return this.executeAction(action, context);
  }

  /**
   * 隐藏模态框 - actions.hideModal(modalId)
   */
  async hideModal(modalId: string, context?: ActionContext): Promise<ActionResult> {
    return this.emitEvent('modal:hide', { modalId }, {}, context);
  }

  /**
   * 表单提交 - actions.submitForm(formId, options)
   */
  async submitForm(
    formId: string,
    options: {
      url?: string;
      method?: 'post' | 'put' | 'patch';
      headers?: Record<string, string>;
      validateBefore?: boolean;
      resetAfter?: boolean;
      redirectAfter?: string;
    } = {},
    context?: ActionContext,
  ): Promise<ActionResult> {
    const action: Action = {
      id: this.generateId(),
      params: {
        type: ActionType.FORM_SUBMIT,
        formId,
        url: options.url || '',
        method: options.method || 'post',
        headers: options.headers,
        validateBefore: options.validateBefore !== false,
        resetAfter: options.resetAfter || false,
        redirectAfter: options.redirectAfter,
      } as FormSubmitActionParams,
    };

    return this.executeAction(action, context);
  }

  /**
   * 发送事件 - actions.emitEvent(eventName, data, options)
   */
  async emitEvent(
    eventName: string,
    data?: any,
    options: {
      scope?: 'component' | 'block' | 'page' | 'global';
      target?: string;
    } = {},
    context?: ActionContext,
  ): Promise<ActionResult> {
    const action: Action = {
      id: this.generateId(),
      params: {
        type: ActionType.EMIT_EVENT,
        eventName,
        data,
        scope: options.scope || 'page',
        target: options.target,
      } as EmitEventActionParams,
    };

    return this.executeAction(action, context);
  }

  /**
   * 条件执行 - actions.if(condition, thenActions, elseActions)
   */
  async if(
    condition: string,
    thenActions: Action[],
    elseActions?: Action[],
    context?: ActionContext,
  ): Promise<ActionResult> {
    const action: Action = {
      id: this.generateId(),
      params: {
        type: ActionType.IF_CONDITION,
        condition,
        then: thenActions,
        else: elseActions,
      } as IfConditionActionParams,
    };

    return this.executeAction(action, context);
  }

  /**
   * 执行动作链 - actions.chain(actions, options)
   */
  async chain(
    actions: Action[],
    options: {
      parallel?: boolean;
      stopOnError?: boolean;
      timeout?: number;
    } = {},
    context?: ActionContext,
  ): Promise<ActionChainResult> {
    const chain: ActionChain = {
      id: this.generateId(),
      name: 'Dynamic Chain',
      actions,
      parallel: options.parallel || false,
      stopOnError: options.stopOnError !== false,
      timeout: options.timeout,
    };

    return this.scheduler.executeChain(chain, context || this.getDefaultContext());
  }

  /**
   * 延迟执行 - actions.delay(ms, action)
   */
  async delay(ms: number, action: Action, context?: ActionContext): Promise<ActionResult> {
    return new Promise((resolve) => {
      setTimeout(async () => {
        const result = await this.executeAction(action, context);
        resolve(result);
      }, ms);
    });
  }

  /**
   * 重试执行 - actions.retry(action, options)
   */
  async retry(
    action: Action,
    options: {
      maxRetries?: number;
      delay?: number;
      backoff?: 'linear' | 'exponential';
    } = {},
    context?: ActionContext,
  ): Promise<ActionResult> {
    const maxRetries = options.maxRetries || 3;
    const baseDelay = options.delay || 1000;
    const backoff = options.backoff || 'linear';

    let lastError: any;

    for (let attempt = 0; attempt <= maxRetries; attempt++) {
      try {
        const result = await this.executeAction(action, context);
        if (result.success) {
          return result;
        }
        lastError = result.error;
      } catch (error) {
        lastError = error;
      }

      if (attempt < maxRetries) {
        const delay =
          backoff === 'exponential' ? baseDelay * Math.pow(2, attempt) : baseDelay * (attempt + 1);

        await new Promise((resolve) => setTimeout(resolve, delay));
      }
    }

    return {
      success: false,
      error: {
        code: 'retry_failed',
        message: `动作执行失败，已重试 ${maxRetries} 次`,
        details: lastError,
      },
      duration: 0,
      timestamp: Date.now(),
    };
  }

  /**
   * 执行单个动作
   */
  private async executeAction(action: Action, context?: ActionContext): Promise<ActionResult> {
    const ctx = context || this.getDefaultContext();
    return this.scheduler.execute(action, ctx);
  }

  /**
   * 获取默认上下文
   */
  private getDefaultContext(): ActionContext {
    return {
      componentId: 'unknown',
      pageId: 'unknown',
      pageState: {},
      globalState: {},
      env: {},
      utils: {
        formatDate: (date: Date, format: string) => date.toLocaleDateString(),
        formatNumber: (num: number, format: string) => num.toString(),
        validateEmail: (email: string) => /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email),
        generateId: () => Math.random().toString(36).substr(2, 9),
      },
    };
  }

  /**
   * 生成唯一 ID
   */
  private generateId(): string {
    return `action_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`;
  }
}

// 导出全局实例
export const actions = ActionProtocol.getInstance();

// 导出便捷方法
export const {
  navigate,
  refresh,
  callApi,
  setState,
  message,
  showModal,
  hideModal,
  submitForm,
  emitEvent,
  if: ifCondition,
  chain,
  delay,
  retry,
} = actions;
