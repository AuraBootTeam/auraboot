// 智能表单组件的类型定义
import type { LowCodeContext } from '~/types/lowcode';

// 基础组件属性接口
export interface BaseSmartComponentProps {
  name: string;
  label?: string;
  placeholder?: string;
  required?: boolean;
  disabled?: boolean;
  size?: 'small' | 'medium' | 'large';
  variant?: 'default' | 'outline' | 'filled';
  className?: string;
  context: LowCodeContext;
}

// 验证规则接口
export interface ValidationRule {
  type: 'required' | 'minLength' | 'maxLength' | 'pattern' | 'custom';
  value?: any;
  message: string;
  expression?: string; // 支持表达式验证
}

// 数据源配置接口
export interface DataSourceConfig {
  type: 'static' | 'api' | 'expression';
  data?: any[];
  url?: string;
  method?: 'get' | 'post';
  params?: Record<string, any>;
  expression?: string;
  labelField?: string;
  valueField?: string;
}

// 选项接口
export interface Option {
  label: string;
  value: any;
  disabled?: boolean;
}

// 智能输入框属性
export interface InputProps extends BaseSmartComponentProps {
  type?: 'text' | 'password' | 'email' | 'number' | 'tel' | 'url';
  value?: string;
  defaultValue?: string;
  maxLength?: number;
  minLength?: number;
  pattern?: string;
  validationRules?: ValidationRule[];
  helpText?: string;
  inputType?: string; // todo Custom prop that should not be passed to DOM to add compennt
  onChange?: (value: string) => void;
  onBlur?: () => void;
  onFocus?: () => void;
}

// 智能选择器属性
export interface SelectProps extends BaseSmartComponentProps {
  value?: any;
  defaultValue?: any;
  multiple?: boolean;
  options?: Option[];
  dataSource?: DataSourceConfig;
  searchable?: boolean;
  clearable?: boolean;
  validationRules?: ValidationRule[];
  onChange?: (value: any) => void;
  onSearch?: (keyword: string) => void;
}

// 智能文本域属性
export interface TextareaProps extends BaseSmartComponentProps {
  value?: string;
  defaultValue?: string;
  rows?: number;
  maxLength?: number;
  minLength?: number;
  autoResize?: boolean;
  validationRules?: ValidationRule[];
  onChange?: (value: string) => void;
}

// 智能复选框属性
export interface CheckboxProps extends BaseSmartComponentProps {
  checked?: boolean;
  defaultChecked?: boolean;
  value?: any;
  indeterminate?: boolean;
  validationRules?: ValidationRule[];
  onChange?: (checked: boolean, value?: any) => void;
}

// 智能单选框属性
export interface RadioProps extends BaseSmartComponentProps {
  value?: any;
  defaultValue?: any;
  options?: Option[];
  dataSource?: DataSourceConfig;
  direction?: 'horizontal' | 'vertical';
  validationRules?: ValidationRule[];
  onChange?: (value: any) => void;
}

// 智能日期选择器属性
export interface DatePickerProps extends BaseSmartComponentProps {
  value?: Date | string;
  defaultValue?: Date | string;
  format?: string;
  showTime?: boolean;
  minDate?: Date | string;
  maxDate?: Date | string;
  disabledDates?: Date[] | string[];
  validationRules?: ValidationRule[];
  onChange?: (date: Date | string) => void;
}

// 表格列配置接口
export interface TableColumnConfig {
  key: string;
  title: string;
  dataIndex?: string;
  width?: number | string;
  align?: 'left' | 'center' | 'right';
  sortable?: boolean;
  filterable?: boolean;
  render?: string; // 表达式渲染
  visible?: string; // 表达式控制可见性
  fixed?: 'left' | 'right';
  ellipsis?: boolean;
  valueType?:
    | 'text'
    | 'number'
    | 'date'
    | 'datetime'
    | 'time'
    | 'currency'
    | 'percent'
    | 'tag'
    | 'badge'
    | 'progress'
    | 'image'
    | 'link'
    | 'code'; // 值类型
  valueEnum?: Record<string, { text: string; status?: string }>; // 枚举值映射（用于 tag/badge）
  copyable?: boolean; // 是否支持复制
}

// 表格操作配置接口
export interface TableActionConfig {
  key: string;
  label: string;
  type?: 'primary' | 'secondary' | 'danger' | 'success' | 'warning';
  icon?: string;
  visible?: string; // 表达式控制可见性
  disabled?: string; // 表达式控制禁用状态
  confirm?: {
    title: string;
    content?: string;
  };
  onClick: string; // 表达式处理点击事件
}

// 表格过滤配置接口
export interface TableFilterConfig {
  key: string;
  type: 'input' | 'select' | 'dateRange' | 'numberRange';
  label?: string;
  options?: Option[];
  placeholder?: string;
  condition: string; // 过滤条件表达式
}

// 表格排序配置接口
export interface SortConfig {
  column: string;
  direction: 'asc' | 'desc';
}

// 分页配置接口
export interface PaginationConfig {
  current: number;
  pageSize: number;
  total: number;
  showSizeChanger?: boolean;
  showQuickJumper?: boolean;
  showTotal?: boolean;
  pageSizeOptions?: number[];
}

// 表格Schema接口
export interface TableSchema {
  columns: TableColumnConfig[];
  actions?: TableActionConfig[];
  filters?: TableFilterConfig[];
  pagination?: Partial<PaginationConfig>;
  selection?: {
    type: 'checkbox' | 'radio';
    fixed?: boolean;
    onChange?: string; // 表达式处理选择变化
  };
  expandable?: {
    expandedRowRender?: string; // 表达式渲染展开内容
    rowExpandable?: string; // 表达式控制是否可展开
  };
  scroll?: {
    x?: number | string;
    y?: number | string;
  };
  size?: 'small' | 'medium' | 'large';
  bordered?: boolean;
  striped?: boolean;
  loading?: boolean;
}

// 智能表格属性
export interface TableProps extends BaseSmartComponentProps {
  schema: TableSchema;
  data?: any[];
  dataSource?: DataSourceConfig;
  selectedRowKeys?: (string | number)[];
  expandedRowKeys?: (string | number)[];
  loading?: boolean;
  onAction?: (action: TableActionConfig, record: any, index: number) => void;
  onSelectionChange?: (selectedRowKeys: (string | number)[], selectedRows: any[]) => void;
  onExpand?: (expanded: boolean, record: any) => void;
  onSort?: (sortConfig: SortConfig) => void;
  onFilter?: (filters: Record<string, any>) => void;
  onPageChange?: (page: number, pageSize: number) => void;
}

// 表单字段配置接口
export interface FormFieldConfig {
  type: 'input' | 'select' | 'textarea' | 'checkbox' | 'radio' | 'datepicker';
  name: string;
  label?: string;
  props: Record<string, any>;
  validationRules?: ValidationRule[];
  visible?: string; // 表达式控制可见性
  disabled?: string; // 表达式控制禁用状态
}

// 表单配置接口
export interface FormSchema {
  fields: FormFieldConfig[];
  layout?: 'vertical' | 'horizontal' | 'inline';
  labelWidth?: string;
  size?: 'small' | 'medium' | 'large';
  validateOnChange?: boolean;
  validateOnBlur?: boolean;
}

// 验证结果接口
export interface ValidationResult {
  valid: boolean;
  errors: Record<string, string[]>;
}

// 表单状态接口
export interface FormState {
  values: Record<string, any>;
  errors: Record<string, string[]>;
  touched: Record<string, boolean>;
  submitting: boolean;
  validating: boolean;
}

// Layout 相关类型定义
export interface LayoutItemConfig {
  key: string;
  component?: string;
  props?: Record<string, any>;
  children?: LayoutItemConfig[];
  span?: number;
  offset?: number;
  order?: number;
  flex?: string | number;
  visible?: string; // 表达式
  className?: string;
  style?: Record<string, any>;
}

export interface GridLayoutConfig {
  type: 'grid';
  columns: number;
  gap?: number;
  responsive?: {
    xs?: number;
    sm?: number;
    md?: number;
    lg?: number;
    xl?: number;
  };
}

export interface FlexLayoutConfig {
  type: 'flex';
  direction?: 'row' | 'column' | 'row-reverse' | 'column-reverse';
  wrap?: 'nowrap' | 'wrap' | 'wrap-reverse';
  justify?: 'start' | 'end' | 'center' | 'between' | 'around' | 'evenly';
  align?: 'start' | 'end' | 'center' | 'baseline' | 'stretch';
  gap?: number;
}

export interface StackLayoutConfig {
  type: 'stack';
  direction?: 'vertical' | 'horizontal';
  spacing?: number;
  divider?: boolean;
}

export interface AbsoluteLayoutConfig {
  type: 'absolute';
  width?: string | number;
  height?: string | number;
}

export type LayoutConfig =
  | GridLayoutConfig
  | FlexLayoutConfig
  | StackLayoutConfig
  | AbsoluteLayoutConfig;

export interface LayoutSchema {
  layout: LayoutConfig;
  items: LayoutItemConfig[];
  className?: string;
  style?: Record<string, any>;
  padding?: number | string;
  margin?: number | string;
  background?: string;
  border?: boolean;
  borderRadius?: number;
  shadow?: boolean;
  responsive?: boolean;
  breakpoints?: {
    xs?: LayoutConfig;
    sm?: LayoutConfig;
    md?: LayoutConfig;
    lg?: LayoutConfig;
    xl?: LayoutConfig;
  };
}

export interface LayoutProps extends BaseSmartComponentProps {
  schema: LayoutSchema;
  components?: Record<string, React.ComponentType<any>>;
  onItemClick?: (item: LayoutItemConfig, index: number) => void;
  onLayoutChange?: (layout: LayoutConfig) => void;
}

// Navigation 相关类型定义
export interface NavigationItem {
  key: string;
  label: string;
  icon?: string;
  path?: string;
  children?: NavigationItem[];
  visible?: string; // 表达式
  disabled?: string; // 表达式
  badge?: {
    count?: number | string; // 支持表达式
    color?: string;
    dot?: boolean;
  };
  onClick?: string; // 表达式
}

export interface BreadcrumbConfig {
  separator?: string;
  maxItems?: number;
  showHome?: boolean;
  homeText?: string;
  homePath?: string;
}

export interface TabConfig {
  type?: 'line' | 'card' | 'editable-card';
  size?: 'small' | 'medium' | 'large';
  position?: 'top' | 'bottom' | 'left' | 'right';
  closable?: boolean;
  addable?: boolean;
}

export interface MenuConfig {
  mode?: 'horizontal' | 'vertical' | 'inline';
  theme?: 'light' | 'dark';
  collapsed?: boolean;
  collapsible?: boolean;
  width?: number;
  inlineIndent?: number;
}

export interface NavigationSchema {
  type: 'menu' | 'breadcrumb' | 'tabs';
  items: NavigationItem[];
  config?: MenuConfig | BreadcrumbConfig | TabConfig;
  className?: string;
  style?: Record<string, any>;
  activeKey?: string;
  defaultActiveKey?: string;
  openKeys?: string[];
  defaultOpenKeys?: string[];
}

export interface NavigationProps extends BaseSmartComponentProps {
  schema: NavigationSchema;
  onSelect?: (key: string, item: NavigationItem) => void;
  onOpenChange?: (openKeys: string[]) => void;
  onTabEdit?: (targetKey: string, action: 'add' | 'remove') => void;
}
