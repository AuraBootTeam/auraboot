/**
 * AuraBoot 低代码平台 - DSL Schema 核心类型定义
 *
 * 定义了低代码平台的核心 Schema 结构，包括：
 * - DSL Schema 主体结构
 * - 元信息配置
 * - 区域和字段配置
 * - 事件和动作配置
 */

// ============= 基础类型 =============

/**
 * 国际化文本类型
 */
export interface I18nText {
  zh_CN: string;
  en_US?: string;
  [locale: string]: string | undefined;
}

/**
 * 页面类型枚举
 */
export type PageType = 'list' | 'form' | 'view' | 'manifest';

/**
 * 字段类型枚举
 */
export type FieldType =
  | 'text'
  | 'number'
  | 'email'
  | 'password'
  | 'textarea'
  | 'select'
  | 'multiselect'
  | 'checkbox'
  | 'radio'
  | 'date'
  | 'datetime'
  | 'file'
  | 'image'
  | 'cascader'
  | 'tree-select'
  | 'switch'
  | 'slider'
  | 'rate'
  | 'color-picker';

/**
 * 区域类型枚举
 */
export type RegionType =
  | 'header'
  | 'toolbar'
  | 'search'
  | 'table'
  | 'form'
  | 'detail'
  | 'footer'
  | 'sidebar'
  | 'modal'
  | 'drawer';

/**
 * 动作类型枚举
 */
export type ActionType =
  | 'api-call'
  | 'navigate'
  | 'toast'
  | 'modal'
  | 'drawer'
  | 'table-update'
  | 'form-validate'
  | 'form-submit'
  | 'form-reset'
  | 'custom';

/**
 * 并发策略枚举
 */
export type ConcurrencyStrategy = 'queue' | 'parallel' | 'switch';

// ============= 元信息配置 =============

/**
 * 页面元信息
 */
export interface MetaInfo {
  /** 页面标题 */
  title: I18nText;
  /** 页面描述 */
  description?: I18nText;
  /** 页面图标 */
  icon?: string;
  /** 页面标签 */
  tags?: string[];
  /** 创建者 */
  author?: string;
  /** 版本号 */
  version?: string;
  /** 创建时间 */
  createdAt?: string;
  /** 更新时间 */
  updatedAt?: string;
}

/**
 * API 端点配置
 */
export interface EndpointConfig {
  /** 基础 URL */
  baseUrl?: string;
  /** 列表查询接口 */
  list?: string;
  /** 详情查询接口 */
  detail?: string;
  /** 创建接口 */
  create?: string;
  /** 更新接口 */
  update?: string;
  /** 删除接口 */
  delete?: string;
  /** 批量操作接口 */
  batch?: string;
  /** 自定义接口 */
  custom?: Record<string, string>;
}

// ============= 字段配置 =============

/**
 * 字段验证规则
 */
export interface ValidationRule {
  /** 是否必填 */
  required?: boolean;
  /** 最小长度 */
  minLength?: number;
  /** 最大长度 */
  maxLength?: number;
  /** 最小值 */
  min?: number;
  /** 最大值 */
  max?: number;
  /** 正则表达式 */
  pattern?: string;
  /** 自定义验证函数表达式 */
  validator?: string;
  /** 错误消息 */
  message?: I18nText;
}

/**
 * 字段选项
 */
export interface FieldOption {
  /** 显示标签 */
  label: I18nText;
  /** 选项值 */
  value: any;
  /** 是否禁用 */
  disabled?: boolean;
  /** 子选项（用于级联选择） */
  children?: FieldOption[];
  /** 图标 */
  icon?: string;
  /** 颜色 */
  color?: string;
}

/**
 * 字段配置
 */
export interface FieldConfig {
  /** 字段名称 */
  name: string;
  /** 显示标签 */
  label: I18nText;
  /** 字段类型 */
  type: FieldType;
  /** 占位符 */
  placeholder?: I18nText;
  /** 默认值 */
  defaultValue?: any;
  /** 是否必填 */
  required?: boolean;
  /** 是否禁用 */
  disabled?: boolean;
  /** 是否只读 */
  readonly?: boolean;
  /** 是否隐藏 */
  hidden?: boolean;
  /** 字段选项 */
  options?: FieldOption[];
  /** 验证规则 */
  validation?: ValidationRule[];
  /** 组件尺寸 */
  size?: 'small' | 'medium' | 'large';
  /** 组件变体 */
  variant?: 'default' | 'outline' | 'filled';
  /** 帮助文本 */
  helpText?: I18nText;
  /** 依赖字段 */
  dependsOn?: string[];
  /** 条件逻辑 */
  conditionalLogic?: ConditionalLogic[];
  /** 自定义属性 */
  props?: Record<string, any>;
  /** 样式类名 */
  className?: string;
  /** 内联样式 */
  style?: Record<string, any>;
}

/**
 * 条件逻辑配置
 */
export interface ConditionalLogic {
  /** 条件表达式 */
  condition: string;
  /** 执行动作 */
  action: 'show' | 'hide' | 'enable' | 'disable' | 'require' | 'optional';
  /** 目标字段（为空则作用于当前字段） */
  target?: string;
}

// ============= 动作配置 =============

/**
 * 动作配置
 */
export interface ActionConfig {
  /** 动作类型 */
  type: ActionType;
  /** 动作标识 */
  key?: string;
  /** 显示标签 */
  label?: I18nText;
  /** 图标 */
  icon?: string;
  /** 按钮类型 */
  buttonType?: 'primary' | 'default' | 'danger' | 'link' | 'text';
  /** 目标（API 地址、路由路径等） */
  target?: string;
  /** 传递数据的表达式 */
  data?: string;
  /** 结果赋值变量 */
  assign?: string;
  /** 确认对话框 */
  confirm?: {
    title: I18nText;
    content: I18nText;
  };
  /** 权限控制 */
  permission?: string;
  /** 条件表达式 */
  if?: string;
  /** 自定义属性 */
  props?: Record<string, any>;
}

/**
 * 事件配置
 */
export interface EventConfig {
  /** 事件名称 */
  name: string;
  /** 触发条件 */
  if?: string;
  /** 执行动作列表 */
  do: ActionConfig[];
  /** 异常处理动作 */
  catch?: ActionConfig[];
  /** 最终执行动作 */
  finally?: ActionConfig[];
  /** 并发策略 */
  concurrency?: ConcurrencyStrategy;
  /** 防抖延迟（毫秒） */
  debounce?: number;
  /** 节流延迟（毫秒） */
  throttle?: number;
}

// ============= 区域配置 =============

/**
 * 布局配置
 */
export interface LayoutConfig {
  /** 列数 */
  columns?: number;
  /** 间距 */
  spacing?: 'small' | 'medium' | 'large';
  /** 方向 */
  direction?: 'horizontal' | 'vertical';
  /** 对齐方式 */
  alignment?: 'left' | 'center' | 'right';
  /** 响应式配置 */
  responsive?: {
    xs?: number;
    sm?: number;
    md?: number;
    lg?: number;
    xl?: number;
  };
}

/**
 * 区域配置
 */
export interface Region {
  /** 区域类型 */
  type: RegionType;
  /** 区域标题 */
  title?: I18nText;
  /** 是否可见 */
  visible?: boolean;
  /** 字段列表 */
  fields?: FieldConfig[];
  /** 动作列表 */
  actions?: ActionConfig[];
  /** 布局配置 */
  layout?: LayoutConfig;
  /** 自定义属性 */
  props?: Record<string, any>;
  /** 样式类名 */
  className?: string;
  /** 内联样式 */
  style?: Record<string, any>;
  /** 子区域 */
  children?: Region[];
}

// ============= 主 Schema 结构 =============

/**
 * DSL Schema 主体结构
 */
export interface DSLSchema {
  /** 元信息 */
  meta: MetaInfo;
  /** API 端点配置 */
  endpoint?: EndpointConfig;
  /** 区域配置列表 */
  regions: Region[];
  /** 全局事件配置 */
  events?: Record<string, EventConfig>;
  /** 全局数据 */
  data?: Record<string, any>;
  /** 全局样式 */
  styles?: Record<string, any>;
  /** 扩展配置 */
  extensions?: Record<string, any>;
}

// ============= 解析后的结构 =============

/**
 * 解析后的字段
 */
export interface ParsedField extends FieldConfig {
  /** 对应的组件名称 */
  component: string;
  /** 解析后的属性 */
  parsedProps: Record<string, any>;
  /** 解析后的验证规则 */
  parsedValidation: ValidationRule[];
}

/**
 * 解析后的区域
 */
export interface ParsedRegion extends Region {
  /** 对应的组件名称 */
  component: string;
  /** 解析后的字段 */
  parsedFields?: ParsedField[];
  /** 解析后的动作 */
  parsedActions?: ActionConfig[];
  /** 解析后的属性 */
  parsedProps: Record<string, any>;
}

/**
 * 解析后的 Schema
 */
export interface ParsedSchema {
  /** 元信息 */
  meta: MetaInfo;
  /** API 端点配置 */
  endpoint?: EndpointConfig;
  /** 解析后的区域列表 */
  regions: ParsedRegion[];
  /** 全局事件配置 */
  events?: Record<string, EventConfig>;
  /** 全局数据 */
  data?: Record<string, any>;
}
