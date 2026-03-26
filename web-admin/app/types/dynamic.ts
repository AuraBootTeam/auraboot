/**
 * 动态CRUD功能相关类型定义
 */

// ============= 基础类型 =============

/**
 * 分页请求参数
 */
export interface PaginationRequest {
  page: number;
  size: number;
  keyword?: string;
  sortBy?: string;
  sortDirection?: 'asc' | 'desc';
}

/**
 * 分页响应结果
 */
export interface PaginationResult<T> {
  records: T[];
  total: number;
  page: number;
  pageSize: number;
  totalPages: number;
}

/**
 * API响应包装器
 */
export interface ApiResponse<T> {
  code: string;
  desc: string;
  data: T | null;
}

// ============= Schema相关类型 =============

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
  | 'image';

/**
 * 字段验证规则
 */
export interface ValidationRule {
  required?: boolean;
  minLength?: number;
  maxLength?: number;
  min?: number;
  max?: number;
  pattern?: string;
  message?: string;
}

/**
 * 字段选项
 */
export interface FieldOption {
  label: string;
  value: any;
  disabled?: boolean;
}

/**
 * 字段定义
 */
export interface FieldDefinition {
  name: string;
  label: string;
  type: FieldType;
  placeholder?: string;
  defaultValue?: any;
  required?: boolean;
  disabled?: boolean;
  readonly?: boolean;
  hidden?: boolean;
  options?: FieldOption[];
  validation?: ValidationRule;
  size?: 'small' | 'medium' | 'large';
  variant?: 'default' | 'outline' | 'filled';
  helpText?: string;
  dependsOn?: string;
  conditionalLogic?: {
    field: string;
    operator: 'equals' | 'not_equals' | 'contains' | 'not_contains';
    value: any;
    action: 'show' | 'hide' | 'enable' | 'disable';
  }[];
}

/**
 * 布局配置
 */
export interface LayoutConfig {
  columns: number;
  spacing: 'small' | 'medium' | 'large';
  direction: 'horizontal' | 'vertical';
  alignment: 'left' | 'center' | 'right';
}

/**
 * 表单Schema
 */
export interface FormSchema {
  title: string;
  description?: string;
  fields: FieldDefinition[];
  layout?: LayoutConfig;
  submitText?: string;
  cancelText?: string;
  resetText?: string;
}

/**
 * 列定义
 */
export interface ColumnDefinition {
  key: string;
  title: string;
  dataIndex: string;
  width?: number;
  sortable?: boolean;
  filterable?: boolean;
  render?: 'text' | 'number' | 'date' | 'boolean' | 'image' | 'link' | 'custom';
  align?: 'left' | 'center' | 'right';
  fixed?: 'left' | 'right';
  ellipsis?: boolean;
}

/**
 * 操作按钮定义
 */
export interface ActionDefinition {
  key: string;
  label: string;
  type: 'primary' | 'default' | 'danger' | 'link';
  icon?: string;
  permission?: string;
  confirm?: {
    title: string;
    content: string;
  };
}

/**
 * 列表Schema
 */
export interface ListSchema {
  title: string;
  description?: string;
  columns: ColumnDefinition[];
  actions?: {
    row?: ActionDefinition[];
    batch?: ActionDefinition[];
    toolbar?: ActionDefinition[];
  };
  pagination?: {
    pageSize: number;
    showSizeChanger: boolean;
    showQuickJumper: boolean;
  };
  search?: {
    enabled: boolean;
    placeholder?: string;
    fields?: string[];
  };
  filters?: {
    field: string;
    type: 'select' | 'date' | 'daterange';
    options?: FieldOption[];
  }[];
}

/**
 * 页面Schema
 */
export interface PageSchema {
  entityCode: string;
  entityName: string;
  listSchema: ListSchema;
  formSchema: FormSchema;
  permissions?: {
    create?: boolean;
    read?: boolean;
    update?: boolean;
    delete?: boolean;
    export?: boolean;
    import?: boolean;
  };
}

// ============= 动态数据类型 =============

/**
 * 动态实体数据
 */
export interface DynamicEntity {
  [key: string]: any;
  id?: string;
  pid?: string;
  createdAt?: string;
  updatedAt?: string;
  createdBy?: string;
  updatedBy?: string;
}

/**
 * 批量操作请求
 */
export interface BatchOperationRequest {
  ids: string[];
  operation: 'delete' | 'update' | 'export';
  data?: Record<string, any>;
}

/**
 * 自定义查询请求
 */
export interface CustomQueryRequest {
  entityCode: string;
  query: string;
  params?: Record<string, any>;
  pagination?: PaginationRequest;
}

/**
 * 字段选项请求
 */
export interface FieldOptionsRequest {
  entityCode: string;
  fieldName: string;
  keyword?: string;
  dependentValues?: Record<string, any>;
}

/**
 * 关联数据请求
 */
export interface RelatedDataRequest {
  entityCode: string;
  relationField: string;
  targetEntityCode: string;
  keyword?: string;
  pagination?: PaginationRequest;
}

/**
 * 导出请求
 */
export interface ExportRequest {
  entityCode: string;
  format: 'excel' | 'csv' | 'pdf';
  fields?: string[];
  filters?: Record<string, any>;
  ids?: string[];
}

/**
 * 导入请求
 */
export interface ImportRequest {
  entityCode: string;
  file: File;
  mapping?: Record<string, string>;
  options?: {
    skipHeader?: boolean;
    updateExisting?: boolean;
    validateOnly?: boolean;
  };
}

/**
 * 统计数据
 */
export interface EntityStats {
  totalCount: number;
  todayCount: number;
  weekCount: number;
  monthCount: number;
  customStats?: Record<string, number>;
}

// ============= Hook和Service类型 =============

/**
 * API调用状态
 */
export interface ApiState<T> {
  data: T | null;
  loading: boolean;
  error: string | null;
}

/**
 * 动态API Hook返回类型
 */
export interface UseDynamicApiReturn {
  // 基础CRUD操作
  findByPage: (
    entityCode: string,
    request: PaginationRequest,
  ) => Promise<PaginationResult<DynamicEntity>>;
  findById: (entityCode: string, id: string) => Promise<DynamicEntity>;
  create: (entityCode: string, data: Record<string, any>) => Promise<DynamicEntity>;
  update: (entityCode: string, id: string, data: Record<string, any>) => Promise<DynamicEntity>;
  deleteById: (entityCode: string, id: string) => Promise<void>;

  // 批量操作
  batchCreate: (entityCode: string, dataList: Record<string, any>[]) => Promise<DynamicEntity[]>;
  batchUpdate: (
    entityCode: string,
    updates: { id: string; data: Record<string, any> }[],
  ) => Promise<DynamicEntity[]>;
  batchDelete: (entityCode: string, ids: string[]) => Promise<void>;

  // 扩展功能
  getFieldOptions: (request: FieldOptionsRequest) => Promise<FieldOption[]>;
  getRelatedData: (request: RelatedDataRequest) => Promise<PaginationResult<DynamicEntity>>;
  exportData: (request: ExportRequest) => Promise<Blob>;
  importData: (
    request: ImportRequest,
  ) => Promise<{ success: number; failed: number; errors?: string[] }>;
  getStats: (entityCode: string) => Promise<EntityStats>;

  // Schema相关
  getPageSchema: (entityCode: string) => Promise<PageSchema>;

  // 状态管理
  loading: boolean;
  error: string | null;
}

/**
 * 表单状态
 */
export interface FormState {
  values: Record<string, any>;
  errors: Record<string, string>;
  touched: Record<string, boolean>;
  submitting: boolean;
}

/**
 * 列表状态
 */
export interface ListState {
  data: DynamicEntity[];
  pagination: {
    current: number;
    pageSize: number;
    total: number;
  };
  selectedRowKeys: string[];
  filters: Record<string, any>;
  sorter: {
    field?: string;
    order?: 'ascend' | 'descend';
  };
  loading: boolean;
}
