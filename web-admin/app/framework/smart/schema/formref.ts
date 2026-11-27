/**
 * FormRef 契约系统类型定义
 * 支持独立表单的引用和嵌入
 */

// Local Component interface definition to avoid circular dependency
interface Position {
  row: number;
  column: number;
}

interface Size {
  width: number;
  height: number;
  span?: number;
}

interface Component {
  id: string;
  type: string;
  name?: string;
  position: Position;
  size?: Size;
  span?: number;
  props: Record<string, any>;
  children?: Component[];
  visible?: boolean;
  locked?: boolean;
}

// FormRef 基础接口
export interface FormRef {
  id: string;
  name: string;
  title: string;
  description?: string;
  version: string;
  schema: FormSchema;
  metadata?: Record<string, any>;
  createdAt: string;
  updatedAt: string;
}

// 表单字段定义
export interface FormField {
  name: string;
  type: string;
  label: string;
  placeholder?: string;
  required?: boolean;
  disabled?: boolean;
  visible?: boolean;
  defaultValue?: any;
  validation?: ValidationRule[];
  props?: Record<string, any>;
  dependencies?: FieldDependency[];
}

// 验证规则
export interface ValidationRule {
  type: 'required' | 'min' | 'max' | 'pattern' | 'custom';
  value?: any;
  message: string;
  validator?: (value: any, formData: Record<string, any>) => boolean | Promise<boolean>;
}

// 字段依赖关系
export interface FieldDependency {
  field: string;
  condition: string; // 表达式，如 "field1 === 'value'"
  action: 'show' | 'hide' | 'enable' | 'disable' | 'require' | 'optional';
}

// 表单布局配置
export interface FormLayout {
  type: 'vertical' | 'horizontal' | 'inline' | 'grid';
  columns?: number;
  gap?: number;
  labelWidth?: number | string;
  labelAlign?: 'left' | 'right' | 'top';
}

// 表单动作定义
export interface FormAction {
  id: string;
  type: 'submit' | 'reset' | 'cancel' | 'custom';
  label: string;
  variant?: 'primary' | 'secondary' | 'outline' | 'ghost';
  disabled?: boolean;
  visible?: boolean;
  loading?: boolean;
  handler?: string; // 动作处理器表达式
}

// 表单 Schema
export interface FormSchema {
  fields: FormField[];
  layout: FormLayout;
  actions: FormAction[];
  validation?: {
    mode: 'onChange' | 'onBlur' | 'onSubmit';
    revalidateMode?: 'onChange' | 'onBlur' | 'onSubmit';
  };
  data?: {
    source?: string; // 数据源表达式
    initialValues?: Record<string, any>;
  };
}

// FormRef 组件属性
export interface FormRefComponentProps {
  formRefId: string;
  formRef?: FormRef;
  mode?: 'embedded' | 'modal' | 'drawer' | 'page';
  title?: string;
  width?: number | string;
  height?: number | string;
  closable?: boolean;
  maskClosable?: boolean;
  data?: Record<string, any>;
  onSubmit?: (data: Record<string, any>) => void | Promise<void>;
  onCancel?: () => void;
  onValuesChange?: (changedValues: Record<string, any>, allValues: Record<string, any>) => void;
  onFieldsChange?: (changedFields: any[], allFields: any[]) => void;
}

// FormRef 引用组件（设计器中的组件）
export interface FormRefComponent extends Component {
  type: 'formref';
  props: FormRefComponentProps & {
    // 设计器特有属性
    placeholder?: string;
    showTitle?: boolean;
    showBorder?: boolean;
    padding?: number;
  };
}

// FormRef 管理器接口
export interface FormRefManager {
  // 获取表单引用
  getFormRef(id: string): Promise<FormRef | null>;

  // 获取所有表单引用
  getAllFormRefs(): Promise<FormRef[]>;

  // 创建表单引用
  createFormRef(formRef: Omit<FormRef, 'id' | 'createdAt' | 'updatedAt'>): Promise<FormRef>;

  // 更新表单引用
  updateFormRef(id: string, updates: Partial<FormRef>): Promise<FormRef>;

  // 删除表单引用
  deleteFormRef(id: string): Promise<void>;

  // 验证表单引用
  validateFormRef(formRef: FormRef): Promise<ValidationResult>;

  // 渲染表单引用
  renderFormRef(formRefId: string, props?: Partial<FormRefComponentProps>): React.ReactElement;
}

// 验证结果
export interface ValidationResult {
  valid: boolean;
  errors: ValidationError[];
  warnings: ValidationWarning[];
}

export interface ValidationError {
  field?: string;
  message: string;
  code: string;
}

export interface ValidationWarning {
  field?: string;
  message: string;
  code: string;
}

// FormRef 上下文
export interface FormRefContext {
  formRef: FormRef | null;
  formData: Record<string, any>;
  errors: Record<string, string>;
  loading: boolean;
  submitting: boolean;

  // 方法
  setFieldValue: (name: string, value: any) => void;
  getFieldValue: (name: string) => any;
  setFieldError: (name: string, error: string) => void;
  clearFieldError: (name: string) => void;
  validateField: (name: string) => Promise<boolean>;
  validateForm: () => Promise<boolean>;
  submitForm: () => Promise<void>;
  resetForm: () => void;
}

// FormRef 事件类型
export type FormRefEvent =
  | { type: 'field_change'; payload: { name: string; value: any } }
  | { type: 'field_blur'; payload: { name: string } }
  | { type: 'field_focus'; payload: { name: string } }
  | { type: 'form_submit'; payload: Record<string, any> }
  | { type: 'form_reset'; payload: {} }
  | { type: 'form_validate'; payload: { field?: string } }
  | { type: 'form_error'; payload: { errors: Record<string, string> } };

// FormRef 状态
export interface FormRefState {
  formRef: FormRef | null;
  formData: Record<string, any>;
  errors: Record<string, string>;
  touched: Record<string, boolean>;
  loading: boolean;
  submitting: boolean;
  dirty: boolean;
  valid: boolean;
}
