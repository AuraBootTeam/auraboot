/**
 * Smart组件类型定义
 * 定义所有Smart组件的Props接口和相关类型
 */

import type { ReactNode, CSSProperties, ComponentType } from 'react';
import type { LocalizedText } from '~/meta/schemas/types';

// 基础组件属性
export interface BaseSmartComponentProps {
  id?: string;
  name?: string;
  className?: string;
  style?: CSSProperties;
  disabled?: boolean;
  visible?: boolean | string; // 支持表达式
  children?: ReactNode;
}

// 验证规则
export interface ValidationRule {
  type: 'required' | 'minLength' | 'maxLength' | 'pattern' | 'email' | 'custom';
  value?: any;
  message?: string;
  validator?: string;
}

// 数据源配置 (兼容 meta/schemas/types.ts)
export interface DataSourceConfig {
  id?: string;
  type?: 'api' | 'static' | 'expression';
  endpoint?: string;
  url?: string; // 兼容旧版
  method?: 'get' | 'post' | 'put' | 'delete';
  params?: string | Record<string, any>;
  body?: string | Record<string, any>;
  autoFetch?: boolean;
  pagination?: boolean;
  adaptor?: string;
  valueField?: string;
  labelField?: string;
  data?: any[];
  transform?: string; // 兼容旧版
  cache?: boolean;
}

// 选项项
export interface OptionItem {
  label: string;
  value: any;
  key?: string;
  disabled?: boolean;
  children?: OptionItem[];
}

// 表达式配置
export interface ExpressionConfig {
  visible?: string | boolean;
  disabled?: string | boolean;
  required?: string | boolean;
  helpText?: string;
  [key: string]: any;
}

// 表单字段基础属性
export interface FormFieldProps extends BaseSmartComponentProps {
  name: string;
  label?: string;
  placeholder?: string;
  required?: boolean;
  value?: any;
  defaultValue?: any;
  validationRules?: ValidationRule[];
  onChange?: (value: any) => void;
  onBlur?: () => void;
  onFocus?: () => void;
  context?: any; // ExpressionContext from meta/runtime
  expressions?: ExpressionConfig;
  inline?: boolean;
  onClear?: () => void;
}

// SmartInput 属性
export interface InputProps extends FormFieldProps {
  type?: 'text' | 'password' | 'email' | 'number' | 'tel' | 'url';
  maxLength?: number;
  minLength?: number;
  pattern?: string;
  autoComplete?: string;
  readOnly?: boolean;
  size?: 'small' | 'medium' | 'large';
  variant?: 'default' | 'outline' | 'filled';
  prefix?: ReactNode;
  suffix?: ReactNode;
  clearable?: boolean;
  helpText?: string | LocalizedText;
  inputType?: string;
}

// SmartTextarea 属性
export interface TextareaProps extends FormFieldProps {
  rows?: number;
  cols?: number;
  maxLength?: number;
  minLength?: number;
  resize?: 'none' | 'both' | 'horizontal' | 'vertical';
  autoResize?: boolean;
  size?: 'small' | 'medium' | 'large';
  variant?: 'default' | 'outline' | 'filled';
  showCount?: boolean;
}

// SmartSelect 属性
export interface SelectProps extends FormFieldProps {
  options?: OptionItem[];
  dataSource?: DataSourceConfig;
  multiple?: boolean;
  searchable?: boolean;
  clearable?: boolean;
  loading?: boolean;
  size?: 'small' | 'medium' | 'large';
  variant?: 'default' | 'outline' | 'filled';
  maxTagCount?: number;
  allowCreate?: boolean;
}

// SmartRadio 属性
export interface RadioProps extends FormFieldProps {
  options?: OptionItem[];
  dataSource?: DataSourceConfig;
  direction?: 'horizontal' | 'vertical';
  size?: 'small' | 'medium' | 'large';
  variant?: 'default' | 'button';
}

// SmartCheckbox 属性
export interface CheckboxProps extends FormFieldProps {
  // 单个复选框属性
  checked?: boolean;
  defaultChecked?: boolean;
  indeterminate?: boolean;
  inline?: boolean;

  // 复选框组属性
  options?: OptionItem[];
  dataSource?: DataSourceConfig;
  direction?: 'horizontal' | 'vertical';
  checkAll?: boolean;

  // 通用属性
  size?: 'small' | 'medium' | 'large';
  variant?: 'default' | 'button';
}

// SmartDatePicker 属性
export interface DatePickerProps extends FormFieldProps {
  format?: string;
  showTime?: boolean;
  timeFormat?: string;
  disabledDate?: (date: Date) => boolean;
  disabledTime?: (date: Date) => any;
  size?: 'small' | 'medium' | 'large';
  variant?: 'default' | 'outline' | 'filled';
  picker?: 'date' | 'week' | 'month' | 'quarter' | 'year';
  range?: boolean;
  dateType?: 'date' | 'datetime-local' | 'time' | 'month' | 'week';
  clearable?: boolean;
  minDate?: string | number;
  maxDate?: string | number;
  step?: number;
  showToday?: boolean;
  onTodayClick?: () => void;
}

// SmartTimePicker 属性 (T9.1)
export interface TimePickerProps extends FormFieldProps {
  format?: string;
  showSecond?: boolean;
  use12Hours?: boolean;
  hourStep?: number;
  minuteStep?: number;
  secondStep?: number;
  size?: 'small' | 'medium' | 'large';
  variant?: 'default' | 'outline' | 'filled';
  clearable?: boolean;
  disabledHours?: () => number[];
  disabledMinutes?: (hour: number) => number[];
  disabledSeconds?: (hour: number, minute: number) => number[];
}

// SmartSwitch 属性 (T9.1)
export interface SwitchProps extends FormFieldProps {
  checked?: boolean;
  defaultChecked?: boolean;
  size?: 'small' | 'default' | 'large';
  loading?: boolean;
  checkedText?: string;
  uncheckedText?: string;
  checkedValue?: any;
  uncheckedValue?: any;
  checkedChildren?: ReactNode;
  unCheckedChildren?: ReactNode;
}

// SmartNumberInput 属性 (T9.1)
export interface NumberInputProps extends FormFieldProps {
  min?: number;
  max?: number;
  step?: number;
  precision?: number;
  size?: 'small' | 'medium' | 'large';
  variant?: 'default' | 'outline' | 'filled';
  showButtons?: boolean;
  buttonLayout?: 'stacked' | 'horizontal';
  prefix?: ReactNode | string;
  suffix?: ReactNode | string;
  formatter?: (value: number | string | undefined) => string;
  parser?: (value: string | undefined) => number;
  keyboard?: boolean;
  stringMode?: boolean;
}

// SmartUpload 属性 (T9.1)
export interface UploadFile {
  uid: string;
  name: string;
  status?: 'uploading' | 'done' | 'error' | 'removed';
  url?: string;
  thumbUrl?: string;
  size?: number;
  type?: string;
  percent?: number;
  response?: any;
  error?: any;
}

export interface UploadProps extends FormFieldProps {
  action?: string;
  accept?: string;
  multiple?: boolean;
  maxCount?: number;
  maxSize?: number;
  listType?: 'text' | 'picture' | 'picture-card';
  fileList?: UploadFile[];
  defaultFileList?: UploadFile[];
  showUploadList?: boolean;
  draggable?: boolean;
  buttonText?: string;
  hint?: string;
  headers?: Record<string, string>;
  data?: Record<string, any> | ((file: File) => Record<string, any>);
  withCredentials?: boolean;
  beforeUpload?: (file: File, fileList: File[]) => boolean | Promise<File | boolean>;
  onRemove?: (file: UploadFile) => boolean | Promise<boolean>;
  onPreview?: (file: UploadFile) => void;
  onDownload?: (file: UploadFile) => void;
  customRequest?: (options: any) => void;
}

// SmartButton 属性
export interface ButtonProps extends BaseSmartComponentProps {
  type?: 'button' | 'submit' | 'reset';
  variant?: 'default' | 'primary' | 'secondary' | 'danger' | 'ghost' | 'link';
  size?: 'small' | 'medium' | 'large';
  loading?: boolean;
  icon?: ReactNode;
  iconPosition?: 'left' | 'right';
  block?: boolean;
  href?: string;
  target?: string;
  onClick?: (event: React.MouseEvent) => void;
}

// SmartTable 属性
export interface TableColumn {
  key: string;
  title: string;
  dataIndex?: string;
  width?: number | string;
  align?: 'left' | 'center' | 'right';
  fixed?: 'left' | 'right';
  sortable?: boolean;
  filterable?: boolean;
  render?: (value: any, record: any, index: number) => ReactNode;
}

export interface TableProps extends BaseSmartComponentProps {
  columns: TableColumn[];
  dataSource?: any[];
  loading?: boolean;
  pagination?: boolean | object;
  rowKey?: string | ((record: any) => string);
  rowSelection?: object;
  scroll?: { x?: number | string; y?: number | string };
  size?: 'small' | 'medium' | 'large';
  bordered?: boolean;
  showHeader?: boolean;
  onRow?: (record: any, index: number) => object;
  onChange?: (pagination: any, filters: any, sorter: any) => void;
}

// SmartForm 属性
export interface FormFieldConfig {
  name: string;
  label?: string | LocalizedText;
  type: 'input' | 'select' | 'textarea' | 'checkbox' | 'radio' | 'date';
  placeholder?: string | LocalizedText;
  inputType?: string;
  options?: OptionItem[];
  dataSource?: DataSourceConfig;
  multiple?: boolean;
  searchable?: boolean;
  rows?: number;
  showCount?: boolean;
  format?: string;
  showTime?: boolean;
  minDate?: string;
  maxDate?: string;
  minLength?: number;
  maxLength?: number;
  defaultValue?: any;
  validationRules?: ValidationRule[];
  visible?: string | boolean;
  disabled?: string | boolean;
  required?: string | boolean;
  size?: 'small' | 'medium' | 'large';
  variant?: 'default' | 'outline' | 'filled';
  helpText?: string | LocalizedText;
  span?: number;
  expressions?: ExpressionConfig;
}

export type FormField = FormFieldConfig;

export interface FormActionConfig {
  key: string;
  label?: string | LocalizedText;
  type?: 'submit' | 'reset' | 'button';
  variant?: ButtonProps['variant'];
  size?: ButtonProps['size'];
  visible?: string | boolean;
  disabled?: string | boolean;
  onClick?: string;
}

export interface FormSchema {
  id: string;
  title?: string | LocalizedText;
  description?: string | LocalizedText;
  layout?: 'horizontal' | 'vertical' | 'inline';
  size?: 'small' | 'medium' | 'large';
  variant?: 'default' | 'outline' | 'filled';
  fields: FormFieldConfig[];
  actions?: FormActionConfig[];
}

export interface FormProps extends BaseSmartComponentProps {
  schema: FormSchema;
  data?: Record<string, any>;
  context?: any;
  onSubmit?: (values: Record<string, any>) => Promise<void> | void;
  onFieldChange?: (fieldName: string, value: any, nextValues: Record<string, any>) => void;
  onValidationChange?: (isValid: boolean, errors: Record<string, string[]>) => void;
}

// Layout 类型定义
export type LayoutBreakpoint = 'xs' | 'sm' | 'md' | 'lg' | 'xl';

interface BaseLayoutConfig {
  type: 'grid' | 'flex' | 'stack' | 'absolute';
  className?: string;
  style?: CSSProperties;
}

export interface GridLayoutConfig extends BaseLayoutConfig {
  type: 'grid';
  columns: number;
  gap?: number;
  responsive?: Partial<Record<LayoutBreakpoint, number>>;
}

export interface FlexLayoutConfig extends BaseLayoutConfig {
  type: 'flex';
  direction?: 'row' | 'row-reverse' | 'column' | 'column-reverse';
  wrap?: 'nowrap' | 'wrap' | 'wrap-reverse';
  justify?: 'start' | 'end' | 'center' | 'between' | 'around' | 'evenly';
  align?: 'start' | 'end' | 'center' | 'stretch' | 'baseline';
  gap?: number;
}

export interface StackLayoutConfig extends BaseLayoutConfig {
  type: 'stack';
  direction?: 'horizontal' | 'vertical';
  spacing?: number;
  divider?: boolean;
}

export interface AbsoluteLayoutConfig extends BaseLayoutConfig {
  type: 'absolute';
  width?: number | string;
  height?: number | string;
}

export type LayoutConfig =
  | GridLayoutConfig
  | FlexLayoutConfig
  | StackLayoutConfig
  | AbsoluteLayoutConfig;

export interface LayoutItemPosition {
  top?: number | string;
  right?: number | string;
  bottom?: number | string;
  left?: number | string;
  width?: number | string;
  height?: number | string;
  zIndex?: number;
}

export interface LayoutItemConfig {
  key: string;
  title?: string;
  component?: string;
  props?: Record<string, any>;
  children?: LayoutItemConfig[];
  visible?: boolean | string;
  span?: number;
  offset?: number;
  order?: number;
  flex?: number | string;
  className?: string;
  style?: CSSProperties;
  position?: LayoutItemPosition;
}

export interface LayoutSchema {
  layout: LayoutConfig;
  items: LayoutItemConfig[];
  className?: string;
  padding?: number | string;
  margin?: number | string;
  background?: string;
  border?: boolean;
  borderRadius?: number;
  shadow?: boolean;
  responsive?: boolean;
  breakpoints?: Partial<Record<LayoutBreakpoint, LayoutConfig>>;
}

export interface LayoutProps extends BaseSmartComponentProps {
  name?: string;
  schema: LayoutSchema;
  context?: any;
  components?: Record<string, ComponentType<any>>;
  onItemClick?: (item: LayoutItemConfig, index: number) => void;
  onLayoutChange?: (layout: LayoutConfig) => void;
}

// SmartNavigation 属性
export interface NavigationBadge {
  count?: number | string;
  color?: string;
  dot?: boolean;
}

export interface NavigationItem {
  key: string;
  label: string;
  icon?: string;
  path?: string;
  children?: NavigationItem[];
  visible?: string | boolean;
  disabled?: string | boolean;
  badge?: NavigationBadge;
  onClick?: string;
}

export interface BreadcrumbConfig {
  separator?: string;
  maxItems?: number;
  showHome?: boolean;
  homeText?: string;
  homePath?: string;
}

export interface MenuConfig {
  mode?: 'horizontal' | 'vertical' | 'inline';
  theme?: 'light' | 'dark';
  collapsible?: boolean;
  inlineIndent?: number;
  width?: number;
}

export interface TabConfig {
  type?: 'line' | 'card';
  size?: 'small' | 'default' | 'large';
  position?: 'top' | 'bottom' | 'left' | 'right';
  closable?: boolean;
  addable?: boolean;
}

export interface NavigationSchema {
  type: 'menu' | 'breadcrumb' | 'tabs';
  items: NavigationItem[];
  config?: MenuConfig | BreadcrumbConfig | TabConfig;
  className?: string;
  activeKey?: string;
  defaultActiveKey?: string;
  openKeys?: string[];
  defaultOpenKeys?: string[];
}

export interface NavigationProps extends BaseSmartComponentProps {
  name?: string;
  schema: NavigationSchema;
  context?: any;
  onSelect?: (key: string, item: NavigationItem) => void;
  onOpenChange?: (openKeys: string[]) => void;
  onTabEdit?: (targetKey: string, action: 'add' | 'remove') => void;
}

export type NavigationItemAlias = NavigationItem;

// SmartDisplay 属性
export interface DisplayProps extends BaseSmartComponentProps {
  value?: any;
  format?: string;
  prefix?: ReactNode;
  suffix?: ReactNode;
  copyable?: boolean;
  ellipsis?: boolean | object;
  mark?: boolean;
  code?: boolean;
  delete?: boolean;
  underline?: boolean;
  strong?: boolean;
  italic?: boolean;
  type?: 'secondary' | 'success' | 'warning' | 'danger';
}

// SmartImage 属性
// SmartList 属性
export interface ListItem {
  key?: string;
  title?: ReactNode;
  description?: ReactNode;
  avatar?: ReactNode;
  actions?: ReactNode[];
  extra?: ReactNode;
}

export interface ListProps extends BaseSmartComponentProps {
  dataSource?: ListItem[];
  loading?: boolean;
  size?: 'small' | 'default' | 'large';
  split?: boolean;
  bordered?: boolean;
  header?: ReactNode;
  footer?: ReactNode;
  pagination?: boolean | object;
  grid?: object;
  renderItem?: (item: ListItem, index: number) => ReactNode;
}

// 组件配置类型
export interface ComponentConfig {
  type: string;
  name: string;
  category: 'form' | 'display' | 'interaction' | 'layout' | 'datetime' | 'chart';
  icon: string;
  description?: string;
  defaultProps?: Record<string, any>;
  propertySchema?: Record<string, any>;
}

// 导出所有类型
export type ComponentProps =
  | InputProps
  | TextareaProps
  | SelectProps
  | RadioProps
  | CheckboxProps
  | DatePickerProps
  | TimePickerProps
  | SwitchProps
  | NumberInputProps
  | UploadProps
  | ButtonProps
  | TableProps
  | FormProps
  | LayoutProps
  | NavigationProps
  | DisplayProps
  | ListProps;
