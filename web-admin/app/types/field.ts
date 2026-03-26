/**
 * AuraBoot 低代码平台 - 字段配置和验证规则类型定义
 *
 * 定义了字段相关的详细类型，包括：
 * - 字段配置扩展
 * - 验证规则详细定义
 * - 字段组件属性
 * - 字段事件处理
 */

import type {
  I18nText,
  FieldType,
  FieldOption,
  ValidationRule,
  ConditionalLogic,
} from '~/types/schema';

// ============= 字段尺寸和变体 =============

/**
 * 组件尺寸枚举
 */
export type ComponentSize = 'small' | 'medium' | 'large';

/**
 * 组件变体枚举
 */
export type ComponentVariant = 'default' | 'outline' | 'filled' | 'ghost' | 'link';

/**
 * 字段状态枚举
 */
export type FieldState = 'normal' | 'loading' | 'disabled' | 'readonly' | 'error' | 'warning';

// ============= 验证规则扩展 =============

/**
 * 验证规则类型枚举
 */
export type ValidationRuleType =
  | 'required'
  | 'minLength'
  | 'maxLength'
  | 'min'
  | 'max'
  | 'pattern'
  | 'email'
  | 'url'
  | 'phone'
  | 'idCard'
  | 'custom';

/**
 * 验证触发时机
 */
export type ValidationTrigger = 'onChange' | 'onBlur' | 'onSubmit' | 'manual';

/**
 * 扩展验证规则
 */
export interface ExtendedValidationRule extends ValidationRule {
  /** 验证规则类型 */
  type: ValidationRuleType;

  /** 触发时机 */
  trigger?: ValidationTrigger[];

  /** 验证函数表达式 */
  validator?: string;

  /** 异步验证函数表达式 */
  asyncValidator?: string;

  /** 验证依赖字段 */
  dependencies?: string[];

  /** 是否跳过验证 */
  skip?: string; // 表达式

  /** 验证优先级 */
  priority?: number;

  /** 自定义验证参数 */
  params?: Record<string, any>;
}

/**
 * 字段验证结果
 */
export interface FieldValidationResult {
  /** 是否有效 */
  valid: boolean;

  /** 错误消息 */
  message?: string;

  /** 错误类型 */
  type?: string;

  /** 验证规则 */
  rule?: ExtendedValidationRule;

  /** 验证值 */
  value?: any;
}

// ============= 字段选项扩展 =============

/**
 * 扩展字段选项
 */
export interface ExtendedFieldOption extends FieldOption {
  /** 选项描述 */
  description?: I18nText;

  /** 选项图标 */
  icon?: string;

  /** 选项颜色 */
  color?: string;

  /** 选项标签颜色 */
  labelColor?: string;

  /** 选项背景色 */
  backgroundColor?: string;

  /** 选项样式类 */
  className?: string;

  /** 选项内联样式 */
  style?: Record<string, any>;

  /** 选项数据 */
  data?: Record<string, any>;

  /** 选项权限 */
  permission?: string;

  /** 选项显示条件 */
  visible?: string; // 表达式

  /** 选项分组 */
  group?: string;

  /** 选项排序 */
  order?: number;
}

/**
 * 选项数据源配置
 */
export interface OptionDataSource {
  /** 数据源类型 */
  type: 'static' | 'api' | 'expression' | 'dictionary';

  /** 静态数据 */
  data?: ExtendedFieldOption[];

  /** API 配置 */
  api?: {
    url: string;
    method?: 'get' | 'post';
    params?: Record<string, any>;
    headers?: Record<string, string>;
    labelField?: string;
    valueField?: string;
    childrenField?: string;
  };

  /** 表达式 */
  expression?: string;

  /** 字典配置 */
  dictionary?: {
    code: string;
    labelField?: string;
    valueField?: string;
  };

  /** 缓存配置 */
  cache?: {
    enabled: boolean;
    duration?: number; // 秒
    key?: string;
  };

  /** 依赖字段 */
  dependencies?: string[];
}

// ============= 字段事件 =============

/**
 * 字段事件类型
 */
export type FieldEventType =
  | 'onChange'
  | 'onBlur'
  | 'onFocus'
  | 'onEnter'
  | 'onClear'
  | 'onSearch'
  | 'onSelect'
  | 'onDeselect'
  | 'onExpand'
  | 'onCollapse'
  | 'onUpload'
  | 'onRemove'
  | 'onPreview'
  | 'onDownload';

/**
 * 字段事件配置
 */
export interface FieldEventConfig {
  /** 事件类型 */
  type: FieldEventType;

  /** 事件处理表达式 */
  handler: string;

  /** 事件参数 */
  params?: Record<string, any>;

  /** 防抖延迟 */
  debounce?: number;

  /** 节流延迟 */
  throttle?: number;

  /** 是否阻止默认行为 */
  preventDefault?: boolean;

  /** 是否阻止事件冒泡 */
  stopPropagation?: boolean;
}

// ============= 字段布局 =============

/**
 * 字段布局配置
 */
export interface FieldLayoutConfig {
  /** 标签位置 */
  labelPosition?: 'top' | 'left' | 'right' | 'bottom';

  /** 标签宽度 */
  labelWidth?: number | string;

  /** 标签对齐方式 */
  labelAlign?: 'left' | 'center' | 'right';

  /** 字段宽度 */
  width?: number | string;

  /** 字段高度 */
  height?: number | string;

  /** 栅格配置 */
  grid?: {
    span?: number;
    offset?: number;
    push?: number;
    pull?: number;
    xs?: number;
    sm?: number;
    md?: number;
    lg?: number;
    xl?: number;
    xxl?: number;
  };

  /** 是否换行 */
  wrap?: boolean;

  /** 边距 */
  margin?: string | number;

  /** 内边距 */
  padding?: string | number;
}

// ============= 字段组件属性 =============

/**
 * 输入框组件属性
 */
export interface InputFieldProps {
  /** 输入类型 */
  inputType?: 'text' | 'password' | 'email' | 'url' | 'tel' | 'search';

  /** 最大长度 */
  maxLength?: number;

  /** 是否显示字符计数 */
  showCount?: boolean;

  /** 前缀图标 */
  prefixIcon?: string;

  /** 后缀图标 */
  suffixIcon?: string;

  /** 前缀文本 */
  addonBefore?: string;

  /** 后缀文本 */
  addonAfter?: string;

  /** 是否允许清除 */
  allowClear?: boolean;

  /** 自动完成 */
  autoComplete?: string;

  /** 自动聚焦 */
  autoFocus?: boolean;
}

/**
 * 数字输入框组件属性
 */
export interface NumberFieldProps {
  /** 最小值 */
  min?: number;

  /** 最大值 */
  max?: number;

  /** 步长 */
  step?: number;

  /** 精度 */
  precision?: number;

  /** 格式化函数 */
  formatter?: string; // 表达式

  /** 解析函数 */
  parser?: string; // 表达式

  /** 是否显示控制按钮 */
  controls?: boolean;

  /** 键盘行为 */
  keyboard?: boolean;
}

/**
 * 选择器组件属性
 */
export interface SelectFieldProps {
  /** 是否多选 */
  multiple?: boolean;

  /** 是否允许搜索 */
  searchable?: boolean;

  /** 是否允许清除 */
  clearable?: boolean;

  /** 是否允许创建新选项 */
  creatable?: boolean;

  /** 最大选择数量 */
  maxCount?: number;

  /** 下拉框最大高度 */
  maxHeight?: number;

  /** 选项过滤函数 */
  filterOption?: string; // 表达式

  /** 选项排序函数 */
  sortOption?: string; // 表达式

  /** 选项分组 */
  groupBy?: string;

  /** 虚拟滚动 */
  virtual?: boolean;

  /** 远程搜索 */
  remoteSearch?: {
    enabled: boolean;
    debounce?: number;
    minLength?: number;
    searchParam?: string;
  };
}

/**
 * 日期选择器组件属性
 */
export interface DateFieldProps {
  /** 日期格式 */
  format?: string;

  /** 显示格式 */
  displayFormat?: string;

  /** 是否显示时间 */
  showTime?: boolean;

  /** 时间格式 */
  timeFormat?: string;

  /** 是否范围选择 */
  range?: boolean;

  /** 禁用日期函数 */
  disabledDate?: string; // 表达式

  /** 禁用时间函数 */
  disabledTime?: string; // 表达式

  /** 快捷选择 */
  shortcuts?: Array<{
    label: string;
    value: string; // 表达式
  }>;

  /** 是否显示今天按钮 */
  showToday?: boolean;

  /** 是否显示清除按钮 */
  showClear?: boolean;
}

/**
 * 文件上传组件属性
 */
export interface FileFieldProps {
  /** 接受的文件类型 */
  accept?: string;

  /** 是否多选 */
  multiple?: boolean;

  /** 最大文件数量 */
  maxCount?: number;

  /** 最大文件大小（字节） */
  maxSize?: number;

  /** 上传 URL */
  uploadUrl?: string;

  /** 上传方法 */
  uploadMethod?: 'post' | 'put';

  /** 上传头部 */
  uploadHeaders?: Record<string, string>;

  /** 上传参数 */
  uploadParams?: Record<string, any>;

  /** 文件名字段 */
  nameField?: string;

  /** 是否显示上传列表 */
  showUploadList?: boolean;

  /** 列表类型 */
  listType?: 'text' | 'picture' | 'picture-card';

  /** 是否支持拖拽上传 */
  dragger?: boolean;

  /** 预览配置 */
  preview?: {
    enabled: boolean;
    width?: number;
    height?: number;
  };
}

// ============= 扩展字段配置 =============

/**
 * 扩展字段配置
 */
export interface ExtendedFieldConfig {
  /** 字段名称 */
  name: string;

  /** 显示标签 */
  label: I18nText;

  /** 字段类型 */
  type: FieldType;

  /** 字段状态 */
  state?: FieldState;

  /** 占位符 */
  placeholder?: I18nText;

  /** 默认值 */
  defaultValue?: any;

  /** 是否必填 */
  required?: boolean;

  /** 是否禁用 */
  disabled?: boolean | string; // 支持表达式

  /** 是否只读 */
  readonly?: boolean | string; // 支持表达式

  /** 是否隐藏 */
  hidden?: boolean | string; // 支持表达式

  /** 字段选项 */
  options?: ExtendedFieldOption[];

  /** 选项数据源 */
  optionDataSource?: OptionDataSource;

  /** 验证规则 */
  validation?: ExtendedValidationRule[];

  /** 组件尺寸 */
  size?: ComponentSize;

  /** 组件变体 */
  variant?: ComponentVariant;

  /** 帮助文本 */
  helpText?: I18nText;

  /** 错误文本 */
  errorText?: I18nText;

  /** 警告文本 */
  warningText?: I18nText;

  /** 依赖字段 */
  dependsOn?: string[];

  /** 条件逻辑 */
  conditionalLogic?: ConditionalLogic[];

  /** 字段事件 */
  events?: FieldEventConfig[];

  /** 布局配置 */
  layout?: FieldLayoutConfig;

  /** 组件属性 */
  componentProps?:
    | InputFieldProps
    | NumberFieldProps
    | SelectFieldProps
    | DateFieldProps
    | FileFieldProps
    | Record<string, any>;

  /** 自定义属性 */
  props?: Record<string, any>;

  /** 样式类名 */
  className?: string;

  /** 内联样式 */
  style?: Record<string, any>;

  /** 权限控制 */
  permission?: string;

  /** 字段分组 */
  group?: string;

  /** 字段排序 */
  order?: number;

  /** 字段描述 */
  description?: I18nText;

  /** 字段提示 */
  tooltip?: I18nText;

  /** 是否可搜索 */
  searchable?: boolean;

  /** 是否可排序 */
  sortable?: boolean;

  /** 是否可过滤 */
  filterable?: boolean;

  /** 是否可导出 */
  exportable?: boolean;

  /** 字段元数据 */
  meta?: Record<string, any>;
}

// ============= 字段组 =============

/**
 * 字段组配置
 */
export interface FieldGroupConfig {
  /** 组名称 */
  name: string;

  /** 组标题 */
  title: I18nText;

  /** 组描述 */
  description?: I18nText;

  /** 组图标 */
  icon?: string;

  /** 是否可折叠 */
  collapsible?: boolean;

  /** 默认是否展开 */
  defaultExpanded?: boolean;

  /** 字段列表 */
  fields: ExtendedFieldConfig[];

  /** 布局配置 */
  layout?: FieldLayoutConfig;

  /** 样式类名 */
  className?: string;

  /** 内联样式 */
  style?: Record<string, any>;

  /** 权限控制 */
  permission?: string;

  /** 显示条件 */
  visible?: string; // 表达式

  /** 组排序 */
  order?: number;
}

// ============= 字段渲染器 =============

/**
 * 字段渲染器接口
 */
export interface FieldRenderer {
  /** 渲染器名称 */
  name: string;

  /** 支持的字段类型 */
  supportedTypes: FieldType[];

  /** 渲染字段 */
  render(field: ExtendedFieldConfig, value: any, context: any): React.ReactElement;

  /** 验证字段值 */
  validate?(field: ExtendedFieldConfig, value: any): FieldValidationResult;

  /** 格式化字段值 */
  format?(field: ExtendedFieldConfig, value: any): any;

  /** 解析字段值 */
  parse?(field: ExtendedFieldConfig, value: any): any;
}

/**
 * 字段渲染器注册表
 */
export interface FieldRendererRegistry {
  /** 注册渲染器 */
  register(renderer: FieldRenderer): void;

  /** 获取渲染器 */
  get(type: FieldType): FieldRenderer | undefined;

  /** 获取所有渲染器 */
  getAll(): FieldRenderer[];

  /** 注销渲染器 */
  unregister(name: string): void;
}

// ============= Meta Field DTO =============

/**
 * Meta Field DTO - Field实体的数据传输对象
 */
export interface MetaFieldDTO {
  id: number;
  pid: string;
  code: string;
  name: string;
  dataType: string;
  required: boolean;
  description?: string;
  defaultValue?: any;
  validationRules?: ValidationRule[];
  dictCode?: string;
  dictName?: string;
  extension?: Record<string, any>;
  createdAt: string;
  updatedAt: string;
  createdBy?: string;
  updatedBy?: string;
}
