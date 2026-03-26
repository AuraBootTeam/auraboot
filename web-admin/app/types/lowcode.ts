// 低代码平台核心类型定义

// 低代码上下文接口
export interface LowCodeContext {
  // 表单数据
  formData?: Record<string, any>;

  // 全局变量
  globalVars?: Record<string, any>;

  // 用户信息
  user?: {
    id: string;
    name: string;
    roles: string[];
  };

  // 兼容旧字段命名
  $user?: {
    id: string;
    name: string;
    role?: string;
    roles?: string[];
    permissions?: string[];
  };

  // 租户信息
  tenant?: {
    id: string;
    name: string;
  };

  // 兼容旧字段命名
  $tenant?: {
    id: string;
    name: string;
  };

  // API 配置
  apiConfig?: {
    baseUrl: string;
    headers?: Record<string, string>;
  };

  // 更新表单数据
  updateFormData?: (name: string, value: any) => void;

  // 更新全局变量
  updateGlobalVar?: (name: string, value: any) => void;

  // 获取表达式值
  getExpressionValue?: (expression: string) => any;

  // 执行异步操作
  executeAsync?: (operation: () => Promise<any>) => Promise<any>;
}

// 表达式类型
export type Expression = string;

// 组件状态
export interface ComponentState {
  value: any;
  error?: string;
  loading?: boolean;
  visible?: boolean;
  disabled?: boolean;
}

// 事件处理器
export interface EventHandler {
  type: 'setValue' | 'validate' | 'submit' | 'custom';
  target?: string;
  expression?: Expression;
  params?: Record<string, any>;
}

// 低代码组件基础接口
export interface LowCodeComponent {
  id: string;
  type: string;
  props: Record<string, any>;
  children?: LowCodeComponent[];
  events?: Record<string, EventHandler>;
}

// 页面配置
export interface PageConfig {
  id: string;
  title: string;
  components: LowCodeComponent[];
  dataSource?: Record<string, any>;
  events?: Record<string, EventHandler>;
}

// 应用配置
export interface AppConfig {
  id: string;
  name: string;
  version: string;
  pages: PageConfig[];
  globalVars?: Record<string, any>;
  theme?: Record<string, any>;
}
