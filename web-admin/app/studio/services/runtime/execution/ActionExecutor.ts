/**
 * 动作执行器实现
 * 负责执行各种类型的动作
 */

import {
  ActionType,
  type Action,
  type ActionContext,
  type ActionResult,
  type ActionExecutor,
  type BaseActionParams,
  type NavigateActionParams,
  type FetchDataActionParams,
  type FormSubmitActionParams,
  type ShowModalActionParams,
  type ShowToastActionParams,
  type IfConditionActionParams,
  type SwitchConditionActionParams,
} from '~/studio/services/runtime/execution/types';
import { ExpressionEvaluator } from '~/studio/services/runtime/execution/ExpressionEvaluator';
import {
  dispatchToast,
  dispatchModal,
  dispatchLoading,
  dispatchVisibility,
} from '~/studio/services/runtime/execution/UIBridge';

/**
 * Generic action params for executors that handle multiple action types
 * without dedicated param interfaces for each type.
 */
type GenericActionParams = BaseActionParams & Record<string, unknown>;

/**
 * 导航动作执行器
 */
export class NavigateActionExecutor implements ActionExecutor {
  canExecute(actionType: ActionType): boolean {
    return [
      ActionType.NAVIGATE,
      ActionType.REDIRECT,
      ActionType.BACK,
      ActionType.FORWARD,
      ActionType.REFRESH,
    ].includes(actionType);
  }

  getDescription(): string {
    return '处理页面导航相关的动作';
  }

  async execute(action: Action, context: ActionContext): Promise<ActionResult> {
    const startTime = Date.now();

    try {
      const params = action.params as GenericActionParams;

      switch (params.type) {
        case ActionType.NAVIGATE:
        case ActionType.REDIRECT:
          await this.handleNavigate(params as unknown as NavigateActionParams, context);
          break;
        case ActionType.BACK:
          window.history.back();
          break;
        case ActionType.FORWARD:
          window.history.forward();
          break;
        case ActionType.REFRESH:
          window.location.reload();
          break;
      }

      return {
        success: true,
        duration: Date.now() - startTime,
        timestamp: Date.now(),
      };
    } catch (error) {
      return {
        success: false,
        error: {
          code: 'navigation_error',
          message: error instanceof Error ? error.message : '导航失败',
          details: error,
        },
        duration: Date.now() - startTime,
        timestamp: Date.now(),
      };
    }
  }

  private async handleNavigate(
    params: NavigateActionParams,
    context: ActionContext,
  ): Promise<void> {
    let url = params.url;

    // 处理参数替换
    if (params.params) {
      for (const [key, value] of Object.entries(params.params)) {
        const evaluatedValue = ExpressionEvaluator.evaluate(value, context);
        url = url.replace(`:${key}`, encodeURIComponent(evaluatedValue));
      }
    }

    // 处理查询参数
    if (params.query) {
      const queryParams = new URLSearchParams();
      for (const [key, value] of Object.entries(params.query)) {
        const evaluatedValue = ExpressionEvaluator.evaluate(value, context);
        queryParams.append(key, evaluatedValue);
      }
      url += (url.includes('?') ? '&' : '?') + queryParams.toString();
    }

    // 执行导航
    if (params.target === '_blank') {
      window.open(url, '_blank');
    } else if (params.replace) {
      window.location.replace(url);
    } else {
      window.location.href = url;
    }
  }
}

/**
 * 数据动作执行器
 */
export class DataActionExecutor implements ActionExecutor {
  canExecute(actionType: ActionType): boolean {
    return [
      ActionType.FETCH_DATA,
      ActionType.SUBMIT_DATA,
      ActionType.UPDATE_DATA,
      ActionType.DELETE_DATA,
      ActionType.VALIDATE_DATA,
    ].includes(actionType);
  }

  getDescription(): string {
    return '处理数据获取、提交、更新等操作';
  }

  async execute(action: Action, context: ActionContext): Promise<ActionResult> {
    const startTime = Date.now();

    try {
      const params = action.params as FetchDataActionParams;
      const result = await this.handleDataAction(params, context);

      return {
        success: true,
        data: result,
        duration: Date.now() - startTime,
        timestamp: Date.now(),
      };
    } catch (error) {
      return {
        success: false,
        error: {
          code: 'data_action_error',
          message: error instanceof Error ? error.message : '数据操作失败',
          details: error,
        },
        duration: Date.now() - startTime,
        timestamp: Date.now(),
      };
    }
  }

  private async handleDataAction(
    params: FetchDataActionParams,
    context: ActionContext,
  ): Promise<unknown> {
    let url = params.url;

    // 处理 URL 参数
    if (params.params) {
      const queryParams = new URLSearchParams();
      for (const [key, value] of Object.entries(params.params)) {
        const evaluatedValue = ExpressionEvaluator.evaluate(value, context);
        queryParams.append(key, evaluatedValue);
      }
      url += (url.includes('?') ? '&' : '?') + queryParams.toString();
    }

    // 构建请求选项
    const requestOptions: RequestInit = {
      method: params.method || 'get',
      headers: {
        'Content-Type': 'application/json',
        ...params.headers,
      },
    };

    // 处理请求体
    if (params.body && ['post', 'put', 'patch'].includes(params.method || 'get')) {
      requestOptions.body =
        typeof params.body === 'string'
          ? params.body
          : JSON.stringify(ExpressionEvaluator.evaluate(params.body, context));
    }

    // 发送请求
    const response = await fetch(url, requestOptions);

    if (!response.ok) {
      throw new Error(`HTTP ${response.status}: ${response.statusText}`);
    }

    // 处理响应
    let result;
    switch (params.responseType || 'json') {
      case 'json':
        result = await response.json();
        break;
      case 'text':
        result = await response.text();
        break;
      case 'blob':
        result = await response.blob();
        break;
      case 'arrayBuffer':
        result = await response.arrayBuffer();
        break;
      default:
        result = await response.json();
    }

    // 存储到状态
    if (params.stateKey) {
      // 这里需要调用状态管理器来存储数据
      // StateManager.setState(params.stateKey, result, 'page');
    }

    return result;
  }
}

/**
 * 表单动作执行器
 */
export class FormActionExecutor implements ActionExecutor {
  canExecute(actionType: ActionType): boolean {
    return [
      ActionType.FORM_SUBMIT,
      ActionType.FORM_RESET,
      ActionType.FORM_VALIDATE,
      ActionType.FORM_SET_VALUE,
      ActionType.FORM_GET_VALUE,
    ].includes(actionType);
  }

  getDescription(): string {
    return '处理表单相关的操作';
  }

  async execute(action: Action, context: ActionContext): Promise<ActionResult> {
    const startTime = Date.now();

    try {
      const params = action.params as FormSubmitActionParams;
      const result = await this.handleFormAction(params, context);

      return {
        success: true,
        data: result,
        duration: Date.now() - startTime,
        timestamp: Date.now(),
      };
    } catch (error) {
      return {
        success: false,
        error: {
          code: 'form_action_error',
          message: error instanceof Error ? error.message : '表单操作失败',
          details: error,
        },
        duration: Date.now() - startTime,
        timestamp: Date.now(),
      };
    }
  }

  private async handleFormAction(
    params: FormSubmitActionParams,
    context: ActionContext,
  ): Promise<unknown> {
    // 获取表单数据
    const formData = context.formData || {};

    // 表单验证
    if (params.validateBefore) {
      const isValid = await this.validateForm(formData);
      if (!isValid) {
        throw new Error('表单验证失败');
      }
    }

    // 提交表单
    const response = await fetch(params.url, {
      method: params.method || 'post',
      headers: {
        'Content-Type': 'application/json',
        ...params.headers,
      },
      body: JSON.stringify(formData),
    });

    if (!response.ok) {
      throw new Error(`HTTP ${response.status}: ${response.statusText}`);
    }

    const result = await response.json();

    // 重置表单
    if (params.resetAfter) {
      // FormManager.resetForm(params.formId);
    }

    // 重定向
    if (params.redirectAfter) {
      window.location.href = params.redirectAfter;
    }

    return result;
  }

  private async validateForm(_formData: Record<string, unknown>): Promise<boolean> {
    // 实现表单验证逻辑
    return true;
  }
}

/**
 * UI 动作执行器
 */
export class UIActionExecutor implements ActionExecutor {
  canExecute(actionType: ActionType): boolean {
    return [
      ActionType.SHOW_MODAL,
      ActionType.HIDE_MODAL,
      ActionType.SHOW_TOAST,
      ActionType.SHOW_LOADING,
      ActionType.HIDE_LOADING,
      ActionType.TOGGLE_VISIBILITY,
    ].includes(actionType);
  }

  getDescription(): string {
    return '处理 UI 界面相关的操作';
  }

  async execute(action: Action, context: ActionContext): Promise<ActionResult> {
    const startTime = Date.now();

    try {
      await this.handleUIAction(action.params as GenericActionParams, context);

      return {
        success: true,
        duration: Date.now() - startTime,
        timestamp: Date.now(),
      };
    } catch (error) {
      return {
        success: false,
        error: {
          code: 'ui_action_error',
          message: error instanceof Error ? error.message : 'UI 操作失败',
          details: error,
        },
        duration: Date.now() - startTime,
        timestamp: Date.now(),
      };
    }
  }

  private async handleUIAction(params: GenericActionParams, context: ActionContext): Promise<void> {
    switch (params.type) {
      case ActionType.SHOW_MODAL:
        await this.showModal(params as unknown as ShowModalActionParams, context);
        break;
      case ActionType.HIDE_MODAL:
        await this.hideModal(params.modalId as string);
        break;
      case ActionType.SHOW_TOAST:
        await this.showToast(params as unknown as ShowToastActionParams, context);
        break;
      case ActionType.SHOW_LOADING:
        await this.showLoading();
        break;
      case ActionType.HIDE_LOADING:
        await this.hideLoading();
        break;
      case ActionType.TOGGLE_VISIBILITY:
        await this.toggleVisibility(
          params.targetId as string,
          params.visible as boolean | undefined,
        );
        break;
    }
  }

  private async showModal(params: ShowModalActionParams, _context: ActionContext): Promise<void> {
    dispatchModal({
      action: 'show',
      modalId: params.modalId,
      title: params.title,
      content: params.content,
      size: params.size,
      closable: params.closable,
      maskClosable: params.maskClosable,
      data: params.data,
    });
  }

  private async hideModal(modalId: string): Promise<void> {
    dispatchModal({ action: 'hide', modalId });
  }

  private async showToast(params: ShowToastActionParams, _context: ActionContext): Promise<void> {
    dispatchToast({
      message: params.message,
      variant: params.variant || 'info',
      duration: params.duration,
    });
  }

  private async showLoading(): Promise<void> {
    dispatchLoading({ visible: true });
  }

  private async hideLoading(): Promise<void> {
    dispatchLoading({ visible: false });
  }

  private async toggleVisibility(targetId: string, visible?: boolean): Promise<void> {
    dispatchVisibility({ targetId, visible });
  }
}

/**
 * 状态动作执行器
 */
export class StateActionExecutor implements ActionExecutor {
  canExecute(actionType: ActionType): boolean {
    return [
      ActionType.SET_STATE,
      ActionType.GET_STATE,
      ActionType.UPDATE_STATE,
      ActionType.RESET_STATE,
    ].includes(actionType);
  }

  getDescription(): string {
    return '处理状态管理相关的操作';
  }

  async execute(action: Action, context: ActionContext): Promise<ActionResult> {
    const startTime = Date.now();

    try {
      const params = action.params as GenericActionParams;

      let result;
      switch (params.type) {
        case ActionType.SET_STATE:
          result = await this.setState(
            params.key as string,
            ExpressionEvaluator.evaluate(params.value, context),
            (params.scope as string) || 'page',
            (params.merge as boolean) || false,
          );
          break;
        case ActionType.GET_STATE:
          result = await this.getState(params.key as string, (params.scope as string) || 'page');
          break;
        case ActionType.UPDATE_STATE:
          result = await this.updateState(
            params.key as string,
            ExpressionEvaluator.evaluate(params.value, context),
            (params.scope as string) || 'page',
          );
          break;
        case ActionType.RESET_STATE:
          result = await this.resetState(params.key as string, (params.scope as string) || 'page');
          break;
      }

      return {
        success: true,
        data: result,
        duration: Date.now() - startTime,
        timestamp: Date.now(),
      };
    } catch (error) {
      return {
        success: false,
        error: {
          code: 'state_action_error',
          message: error instanceof Error ? error.message : '状态操作失败',
          details: error,
        },
        duration: Date.now() - startTime,
        timestamp: Date.now(),
      };
    }
  }

  private async setState(
    _key: string,
    _value: unknown,
    _scope: string,
    _merge: boolean,
  ): Promise<unknown> {
    throw new Error(
      'Not yet implemented: StateActionExecutor.setState — requires StateManager integration',
    );
  }

  private async getState(_key: string, _scope: string): Promise<unknown> {
    throw new Error(
      'Not yet implemented: StateActionExecutor.getState — requires StateManager integration',
    );
  }

  private async updateState(_key: string, _value: unknown, _scope: string): Promise<unknown> {
    throw new Error(
      'Not yet implemented: StateActionExecutor.updateState — requires StateManager integration',
    );
  }

  private async resetState(_key: string, _scope: string): Promise<unknown> {
    throw new Error(
      'Not yet implemented: StateActionExecutor.resetState — requires StateManager integration',
    );
  }
}

/**
 * 事件动作执行器
 */
export class EventActionExecutor implements ActionExecutor {
  canExecute(actionType: ActionType): boolean {
    return [ActionType.EMIT_EVENT, ActionType.LISTEN_EVENT, ActionType.UNLISTEN_EVENT].includes(
      actionType,
    );
  }

  getDescription(): string {
    return '处理事件发送和监听';
  }

  async execute(action: Action, context: ActionContext): Promise<ActionResult> {
    const startTime = Date.now();

    try {
      const params = action.params as GenericActionParams;

      switch (params.type) {
        case ActionType.EMIT_EVENT:
          await this.emitEvent(
            params.eventName as string,
            params.data ? ExpressionEvaluator.evaluate(params.data, context) : undefined,
            (params.scope as string) || 'page',
            params.target as string | undefined,
          );
          break;
        case ActionType.LISTEN_EVENT:
          await this.listenEvent(
            params.eventName as string,
            (params.scope as string) || 'page',
            params.target as string | undefined,
          );
          break;
        case ActionType.UNLISTEN_EVENT:
          await this.unlistenEvent(
            params.eventName as string,
            (params.scope as string) || 'page',
            params.target as string | undefined,
          );
          break;
      }

      return {
        success: true,
        duration: Date.now() - startTime,
        timestamp: Date.now(),
      };
    } catch (error) {
      return {
        success: false,
        error: {
          code: 'event_action_error',
          message: error instanceof Error ? error.message : '事件操作失败',
          details: error,
        },
        duration: Date.now() - startTime,
        timestamp: Date.now(),
      };
    }
  }

  private async emitEvent(
    _eventName: string,
    _data: unknown,
    _scope: string,
    _target?: string,
  ): Promise<void> {
    throw new Error(
      'Not yet implemented: EventActionExecutor.emitEvent — requires EventBus integration',
    );
  }

  private async listenEvent(_eventName: string, _scope: string, _target?: string): Promise<void> {
    throw new Error(
      'Not yet implemented: EventActionExecutor.listenEvent — requires EventBus integration',
    );
  }

  private async unlistenEvent(_eventName: string, _scope: string, _target?: string): Promise<void> {
    throw new Error(
      'Not yet implemented: EventActionExecutor.unlistenEvent — requires EventBus integration',
    );
  }
}

/**
 * 条件动作执行器
 */
export class ConditionActionExecutor implements ActionExecutor {
  canExecute(actionType: ActionType): boolean {
    return [ActionType.IF_CONDITION, ActionType.SWITCH_CONDITION].includes(actionType);
  }

  getDescription(): string {
    return '处理条件判断和分支执行';
  }

  async execute(action: Action, context: ActionContext): Promise<ActionResult> {
    const startTime = Date.now();

    try {
      const params = action.params as GenericActionParams;
      let result;

      switch (params.type) {
        case ActionType.IF_CONDITION:
          result = await this.handleIfCondition(
            params as unknown as IfConditionActionParams,
            context,
          );
          break;
        case ActionType.SWITCH_CONDITION:
          result = await this.handleSwitchCondition(
            params as unknown as SwitchConditionActionParams,
            context,
          );
          break;
      }

      return {
        success: true,
        data: result,
        duration: Date.now() - startTime,
        timestamp: Date.now(),
      };
    } catch (error) {
      return {
        success: false,
        error: {
          code: 'condition_action_error',
          message: error instanceof Error ? error.message : '条件操作失败',
          details: error,
        },
        duration: Date.now() - startTime,
        timestamp: Date.now(),
      };
    }
  }

  private async handleIfCondition(
    params: IfConditionActionParams,
    context: ActionContext,
  ): Promise<ActionResult[] | undefined> {
    const conditionResult = ExpressionEvaluator.evaluate(params.condition, context);

    if (conditionResult) {
      // 执行 then 分支
      return this.executeActions(params.then, context);
    } else if (params.else) {
      // 执行 else 分支
      return this.executeActions(params.else, context);
    }
  }

  private async handleSwitchCondition(
    params: SwitchConditionActionParams,
    context: ActionContext,
  ): Promise<ActionResult[] | undefined> {
    const expressionResult = ExpressionEvaluator.evaluate(params.expression, context);

    // 查找匹配的 case
    for (const caseItem of params.cases) {
      if (caseItem.value === expressionResult) {
        return this.executeActions(caseItem.actions, context);
      }
    }

    // 执行默认分支
    if (params.default) {
      return this.executeActions(params.default, context);
    }
  }

  private async executeActions(actions: Action[], context: ActionContext): Promise<ActionResult[]> {
    const { globalActionScheduler } =
      await import('~/studio/services/runtime/execution/ActionScheduler');
    const results: ActionResult[] = [];

    for (const action of actions) {
      const result = await globalActionScheduler.executeAction(action, context);
      results.push(result);

      // 如果动作失败且需要停止，则中断执行
      if (!result.success) {
        break;
      }
    }

    return results;
  }
}
