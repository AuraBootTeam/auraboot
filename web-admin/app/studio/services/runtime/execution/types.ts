/**
 * 统一动作系统类型定义
 * 定义 actions.* 协议和事件编排机制
 */

// 动作类型枚举
export enum ActionType {
  // 导航动作
  NAVIGATE = 'actions.navigate',
  REDIRECT = 'actions.redirect',
  BACK = 'actions.back',
  FORWARD = 'actions.forward',
  REFRESH = 'actions.refresh',

  // 数据动作
  FETCH_DATA = 'actions.data.fetch',
  SUBMIT_DATA = 'actions.data.submit',
  UPDATE_DATA = 'actions.data.update',
  DELETE_DATA = 'actions.data.delete',
  VALIDATE_DATA = 'actions.data.validate',

  // 表单动作
  FORM_SUBMIT = 'actions.form.submit',
  FORM_RESET = 'actions.form.reset',
  FORM_VALIDATE = 'actions.form.validate',
  FORM_SET_VALUE = 'actions.form.setValue',
  FORM_GET_VALUE = 'actions.form.getValue',

  // UI 动作
  SHOW_MODAL = 'actions.ui.showModal',
  HIDE_MODAL = 'actions.ui.hideModal',
  SHOW_TOAST = 'actions.ui.showToast',
  SHOW_LOADING = 'actions.ui.showLoading',
  HIDE_LOADING = 'actions.ui.hideLoading',
  TOGGLE_VISIBILITY = 'actions.ui.toggleVisibility',

  // 状态动作
  SET_STATE = 'actions.state.set',
  GET_STATE = 'actions.state.get',
  UPDATE_STATE = 'actions.state.update',
  RESET_STATE = 'actions.state.reset',

  // 事件动作
  EMIT_EVENT = 'actions.event.emit',
  LISTEN_EVENT = 'actions.event.listen',
  UNLISTEN_EVENT = 'actions.event.unlisten',

  // 条件动作
  IF_CONDITION = 'actions.condition.if',
  SWITCH_CONDITION = 'actions.condition.switch',

  // 循环动作
  FOR_EACH = 'actions.loop.forEach',
  WHILE_LOOP = 'actions.loop.while',

  // 自定义动作
  CUSTOM = 'actions.custom',
}

// 动作执行上下文
export interface ActionContext {
  // 当前组件/Block 信息
  componentId: string;
  blockId?: string;
  pageId: string;

  // 事件信息
  event?: Event;
  eventType?: string;
  eventData?: any;

  // 用户信息
  user?: {
    id: string;
    name: string;
    roles: string[];
  };

  // 页面状态
  pageState: Record<string, any>;

  // 全局状态
  globalState: Record<string, any>;

  // 表单数据
  formData?: Record<string, any>;

  // 环境变量
  env: Record<string, any>;

  // 工具函数
  utils: {
    formatDate: (date: Date, format: string) => string;
    formatNumber: (num: number, format: string) => string;
    validateEmail: (email: string) => boolean;
    generateId: () => string;
    [key: string]: any;
  };

  // Runtime context extensions
  route?: any;
  vars?: Record<string, any>;
  componentState?: Record<string, any>;
}

// 动作参数基础接口
export interface BaseActionParams {
  type: ActionType;
  id?: string;
  name?: string;
  description?: string;
  condition?: string; // 执行条件表达式
  async?: boolean;
  timeout?: number;
  retries?: number;
  onSuccess?: Action[];
  onError?: Action[];
  onFinally?: Action[];
}

// 导航动作参数
export interface NavigateActionParams extends BaseActionParams {
  type: ActionType.NAVIGATE;
  url: string;
  target?: '_self' | '_blank' | '_parent' | '_top';
  replace?: boolean;
  params?: Record<string, any>;
  query?: Record<string, any>;
}

// 数据获取动作参数
export interface FetchDataActionParams extends BaseActionParams {
  type: ActionType.FETCH_DATA;
  url: string;
  method?: 'get' | 'post' | 'put' | 'delete' | 'patch';
  headers?: Record<string, string>;
  body?: any;
  params?: Record<string, any>;
  responseType?: 'json' | 'text' | 'blob' | 'arrayBuffer';
  cache?: boolean;
  stateKey?: string; // 存储到状态的键名
}

// 表单提交动作参数
export interface FormSubmitActionParams extends BaseActionParams {
  type: ActionType.FORM_SUBMIT;
  formId?: string;
  url: string;
  method?: 'post' | 'put' | 'patch';
  headers?: Record<string, string>;
  validateBefore?: boolean;
  resetAfter?: boolean;
  redirectAfter?: string;
}

// UI 动作参数
export interface ShowModalActionParams extends BaseActionParams {
  type: ActionType.SHOW_MODAL;
  modalId: string;
  title?: string;
  content?: string;
  size?: 'sm' | 'md' | 'lg' | 'xl' | 'full';
  closable?: boolean;
  maskClosable?: boolean;
  data?: any;
}

export interface ShowToastActionParams extends BaseActionParams {
  type: ActionType.SHOW_TOAST;
  message: string;
  variant?: 'success' | 'error' | 'warning' | 'info';
  duration?: number;
  position?: 'top' | 'bottom' | 'center';
}

// 状态动作参数
export interface SetStateActionParams extends BaseActionParams {
  type: ActionType.SET_STATE;
  key: string;
  value: any;
  scope?: 'page' | 'global' | 'session' | 'local';
  merge?: boolean;
}

// 事件动作参数
export interface EmitEventActionParams extends BaseActionParams {
  type: ActionType.EMIT_EVENT;
  eventName: string;
  data?: any;
  scope?: 'component' | 'block' | 'page' | 'global';
  target?: string; // 目标组件/Block ID
}

// 刷新动作参数
export interface RefreshActionParams extends BaseActionParams {
  type: ActionType.REFRESH;
}

// 条件动作参数
export interface IfConditionActionParams extends BaseActionParams {
  type: ActionType.IF_CONDITION;
  condition: string;
  then: Action[];
  else?: Action[];
}

export interface SwitchConditionActionParams extends BaseActionParams {
  type: ActionType.SWITCH_CONDITION;
  expression: string;
  cases: Array<{
    value: any;
    actions: Action[];
  }>;
  default?: Action[];
}

// 循环动作参数
export interface ForEachActionParams extends BaseActionParams {
  type: ActionType.FOR_EACH;
  items: string; // 数据源表达式
  itemVar?: string; // 循环项变量名，默认 $item
  indexVar?: string; // 索引变量名，默认 $index
  actions: Action[];
}

// 自定义动作参数
export interface CustomActionParams extends BaseActionParams {
  type: ActionType.CUSTOM;
  handler: string; // 自定义处理函数名
  params?: Record<string, any>;
}

// 动作参数联合类型
export type ActionParams =
  | NavigateActionParams
  | FetchDataActionParams
  | FormSubmitActionParams
  | ShowModalActionParams
  | ShowToastActionParams
  | SetStateActionParams
  | EmitEventActionParams
  | RefreshActionParams
  | IfConditionActionParams
  | SwitchConditionActionParams
  | ForEachActionParams
  | CustomActionParams;

// 动作定义
export interface Action {
  id: string;
  params: ActionParams;
  name?: string;
  description?: string;
  enabled?: boolean;
  type?: ActionType;
  metadata?: {
    createdAt: string;
    updatedAt: string;
    version: string;
    tags: string[];
  };
}

// 动作执行结果
export interface ActionResult {
  success: boolean;
  data?: any;
  error?: {
    code: string;
    message: string;
    details?: any;
  };
  duration: number;
  timestamp: number;
}

// 动作执行器接口
export interface ActionExecutor {
  execute(action: Action, context: ActionContext): Promise<ActionResult>;
  canExecute(actionType: ActionType): boolean;
  getDescription(): string;
}

// 动作链定义
export interface ActionChain {
  id: string;
  name: string;
  description?: string;
  actions: Action[];
  parallel?: boolean; // 是否并行执行
  stopOnError?: boolean; // 遇到错误是否停止
  timeout?: number;
  metadata?: {
    createdAt: string;
    updatedAt: string;
    version: string;
    tags: string[];
  };
}

// 动作链执行结果
export interface ActionChainResult {
  success: boolean;
  results: ActionResult[];
  totalDuration: number;
  timestamp: number;
  error?: {
    actionId: string;
    error: ActionResult['error'];
  };
}

// 事件监听器定义
export interface EventListener {
  id: string;
  eventName: string;
  scope: 'component' | 'block' | 'page' | 'global';
  target?: string;
  condition?: string;
  actions: Action[];
  once?: boolean;
  enabled: boolean;
}

// 动作调度器配置
export interface ActionSchedulerConfig {
  maxConcurrentActions: number;
  defaultTimeout: number;
  enableLogging: boolean;
  enableMetrics: boolean;
  retryPolicy: {
    maxRetries: number;
    backoffStrategy: 'linear' | 'exponential';
    baseDelay: number;
  };
}

// 动作执行统计
export interface ActionMetrics {
  totalExecutions: number;
  successCount: number;
  errorCount: number;
  averageDuration: number;
  lastExecuted: number;
  errorRate: number;
}

// 动作注册表项
export interface ActionRegistryEntry {
  type: ActionType;
  name: string;
  executor: ActionExecutor;
  schema: any; // JSON Schema for validation
  parameterSchema?: any;
  category: string;
  icon: string;
  description: string;
  examples: Action[];
  metrics: ActionMetrics;
}

// 表达式求值器接口
export interface ExpressionEvaluator {
  evaluate(expression: string, context: ActionContext): any;
  validate(expression: string): { valid: boolean; error?: string };
  getAvailableVariables(context: ActionContext): string[];
  getAvailableFunctions(): string[];
}
