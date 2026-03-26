// 表达式上下文类型定义

export interface ExpressionContext {
  // 表单数据
  $form?: Record<string, any>;

  // 全局变量
  $global?: Record<string, any>;

  // 用户信息
  $user?: {
    id: string;
    name: string;
    roles: string[];
  };

  // 租户信息
  $tenant?: {
    id: string;
    name: string;
  };

  // 工具函数
  $utils?: {
    formatDate?: (date: Date | string, format?: string) => string;
    formatNumber?: (num: number, precision?: number) => string;
    isEmpty?: (value: any) => boolean;
    isNotEmpty?: (value: any) => boolean;
    [key: string]: any;
  };

  // 当前值（在验证规则中使用）
  $value?: any;

  // 其他动态属性
  [key: string]: any;
}
