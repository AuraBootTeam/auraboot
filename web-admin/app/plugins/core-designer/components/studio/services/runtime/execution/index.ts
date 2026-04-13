/**
 * 统一动作系统入口文件
 * 导出所有动作相关的类型、组件和工具
 */

import type {
  Action,
  ActionContext,
  ActionResult,
  ActionChain,
  ActionChainResult,
  ActionSchedulerConfig,
} from '~/plugins/core-designer/components/studio/services/runtime/execution/types';
import { ExpressionEvaluator } from '~/plugins/core-designer/components/studio/services/runtime/execution/ExpressionEvaluator';
import { globalActionRegistry } from '~/plugins/core-designer/components/studio/services/runtime/execution/ActionRegistry';
import { globalActionScheduler } from '~/plugins/core-designer/components/studio/services/runtime/execution/ActionScheduler';

// 类型定义
export type {
  ActionType,
  ActionContext,
  BaseActionParams,
  NavigateActionParams,
  FetchDataActionParams,
  FormSubmitActionParams,
  ShowModalActionParams,
  ShowToastActionParams,
  SetStateActionParams,
  EmitEventActionParams,
  IfConditionActionParams,
  SwitchConditionActionParams,
  ForEachActionParams,
  CustomActionParams,
  Action,
  ActionResult,
  ActionExecutor,
  ActionChain,
  ActionChainResult,
  EventListener,
  ActionSchedulerConfig,
  ActionMetrics,
  ActionRegistryEntry,
  ExpressionEvaluator as IExpressionEvaluator,
} from '~/plugins/core-designer/components/studio/services/runtime/execution/types';

// 核心类
export { ExpressionEvaluator } from '~/plugins/core-designer/components/studio/services/runtime/execution/ExpressionEvaluator';
export {
  ActionScheduler,
  globalActionScheduler,
} from '~/plugins/core-designer/components/studio/services/runtime/execution/ActionScheduler';
export {
  ActionRegistry,
  globalActionRegistry,
} from '~/plugins/core-designer/components/studio/services/runtime/execution/ActionRegistry';

// 执行器
export {
  NavigateActionExecutor,
  DataActionExecutor,
  FormActionExecutor,
  UIActionExecutor,
  StateActionExecutor,
  EventActionExecutor,
  ConditionActionExecutor,
} from '~/plugins/core-designer/components/studio/services/runtime/execution/ActionExecutor';

// 工具函数
export const ActionUtils = {
  /**
   * 创建动作上下文
   */
  createContext: (data: Partial<ActionContext> = {}): ActionContext => {
    const defaultUtils = {
      formatDate: (date: Date, format: string) => date.toLocaleDateString(),
      formatNumber: (num: number, format: string) => num.toString(),
      validateEmail: (email: string) => /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email),
      generateId: () => Math.random().toString(36).slice(2),
    };

    return {
      componentId: '',
      pageId: '',
      pageState: {},
      globalState: {},
      componentState: {},
      formData: {},
      eventData: {},
      env: {},
      ...data,
      utils: { ...defaultUtils, ...(data.utils ?? {}) },
    } as ActionContext;
  },

  /**
   * 验证动作
   */
  validateAction: (action: Action): { valid: boolean; errors: string[] } => {
    return globalActionRegistry.validateAction(action);
  },

  /**
   * 创建动作链
   */
  createActionChain: (
    actions: Action[],
    options: {
      id?: string;
      name?: string;
      description?: string;
      stopOnError?: boolean;
    } = {},
  ): ActionChain => ({
    id: options.id || `chain_${Date.now()}`,
    name: options.name || '动作链',
    description: options.description || '',
    actions,
    stopOnError: options.stopOnError ?? true,
  }),

  /**
   * 执行动作
   */
  executeAction: async (
    action: Action,
    context: ActionContext = ActionUtils.createContext(),
  ): Promise<ActionResult> => {
    return globalActionScheduler.executeAction(action, ActionUtils.createContext(context));
  },

  /**
   * 执行动作链
   */
  executeActionChain: async (
    actionChain: ActionChain,
    context: ActionContext = ActionUtils.createContext(),
  ): Promise<ActionChainResult> => {
    return globalActionScheduler.executeActionChain(
      actionChain,
      ActionUtils.createContext(context),
    );
  },

  /**
   * 计算表达式
   */
  evaluateExpression: (expression: any, context: ActionContext): any => {
    return ExpressionEvaluator.evaluate(expression, context);
  },

  /**
   * 验证表达式
   */
  validateExpression: (expression: string): { valid: boolean; error?: string } => {
    return ExpressionEvaluator.getInstance().validateExpression(expression);
  },

  /**
   * 提取表达式变量
   */
  extractVariables: (expression: string): string[] => {
    return ExpressionEvaluator.getInstance().extractVariables(expression);
  },

  /**
   * 创建表达式模板
   */
  createTemplate: (template: string, context: ActionContext): string => {
    return ExpressionEvaluator.getInstance().createTemplate(template, context);
  },
};

// 常量
export const ACTION_CATEGORIES = {
  NAVIGATION: 'navigation',
  DATA: 'data',
  FORM: 'form',
  UI: 'ui',
  STATE: 'state',
  EVENT: 'event',
  CONDITION: 'condition',
  LOOP: 'loop',
  CUSTOM: 'custom',
} as const;

export const DEFAULT_ACTION_CONFIG: ActionSchedulerConfig = {
  maxConcurrentActions: 10,
  defaultTimeout: 30000,
  enableMetrics: true,
  enableLogging: process.env.NODE_ENV === 'development',
  retryPolicy: {
    maxRetries: 3,
    backoffStrategy: 'linear',
    baseDelay: 1000,
  },
};

// React Hooks
export const useActionScheduler = () => {
  return {
    scheduler: globalActionScheduler,
    registry: globalActionRegistry,
    executeAction: ActionUtils.executeAction,
    executeActionChain: ActionUtils.executeActionChain,
    createAction: globalActionRegistry.createAction.bind(globalActionRegistry),
    createActionChain: ActionUtils.createActionChain,
    getMetrics: globalActionScheduler.getMetrics.bind(globalActionScheduler),
  };
};

export const useActionRegistry = () => {
  return {
    registry: globalActionRegistry,
    getAll: globalActionRegistry.getAll.bind(globalActionRegistry),
    getByCategory: globalActionRegistry.getByCategory.bind(globalActionRegistry),
    search: globalActionRegistry.search.bind(globalActionRegistry),
    get: globalActionRegistry.get.bind(globalActionRegistry),
    createAction: globalActionRegistry.createAction.bind(globalActionRegistry),
    validateAction: globalActionRegistry.validateAction.bind(globalActionRegistry),
  };
};

export const useExpressionEvaluator = () => {
  const evaluator = ExpressionEvaluator.getInstance();

  return {
    evaluate: evaluator.evaluate.bind(evaluator),
    validateExpression: evaluator.validateExpression.bind(evaluator),
    extractVariables: evaluator.extractVariables.bind(evaluator),
    createTemplate: evaluator.createTemplate.bind(evaluator),
    evaluateBatch: evaluator.evaluateBatch.bind(evaluator),
  };
};
