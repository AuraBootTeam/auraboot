/**
 * 页面 Schema 类型定义
 *
 * 基于 AuraBoot 低代码架构规范，定义页面级别的 Schema 结构
 * 支持表单页、列表页、详情页、仪表板等多种页面类型
 */

import type { LowCodeContext } from '~/types/lowcode';

// 页面元数据
export interface PageMeta {
  title: Record<string, string>; // 多语言标题
  name?: string;
  entityCode?: string; // 实体编码
  dslVersion?: string; // DSL 版本
  version?: string; // Schema 版本
  description?: Record<string, string>; // 页面描述
}

// API 端点配置
export interface ApiEndpoint {
  url: string;
  method: 'get' | 'post' | 'put' | 'delete' | 'patch';
  permission?: string; // 权限要求
  timeout?: number;
  retry?: {
    times: number;
    backoffMs: number;
  };
}

// 页面属性
export interface PageProps {
  mode?: 'create' | 'edit' | 'view' | 'list';
  readonly?: boolean;
  [key: string]: any;
}

// 字段配置
export interface FieldConfig {
  code: string;
  type: string;
  props: {
    label?: Record<string, string>;
    placeholder?: Record<string, string>;
    required?: boolean;
    disabled?: boolean;
    readOnly?: boolean | string; // 支持表达式
    visible?: boolean | string; // 支持表达式
    maxLength?: number;
    minLength?: number;
    rows?: number;
    locales?: string[];
    binding?: string;
    options?: Array<{
      value: any;
      label: Record<string, string>;
    }>;
    [key: string]: any;
  };
  layout?: {
    span?: number;
    offset?: number;
    [key: string]: any;
  };
  validation?: Array<{
    type: string;
    value?: any;
    message: Record<string, string>;
  }>;
}

// 表单区域配置
export interface FormRegion {
  type: 'form';
  name?: string;
  title?: Record<string, string>;
  sections: Array<{
    code: string;
    title?: Record<string, string>;
    layout?: {
      type: 'grid' | 'flex' | 'inline';
      columns?: number;
      gap?: 'small' | 'medium' | 'large';
      [key: string]: any;
    };
    fields: FieldConfig[];
    visible?: string; // 表达式控制显示
  }>;
  actions?: Array<{
    code: string;
    props?: Record<string, any>;
    permission?: string;
  }>;
}

// 过滤器区域配置
export interface FiltersRegion {
  type: 'filters';
  fields: FieldConfig[];
  layout?: {
    type: 'grid' | 'flex' | 'inline';
    columns?: number;
    gap?: 'small' | 'medium' | 'large';
  };
}

// 预设区域配置
export interface PresetRegion {
  type: 'preset';
  filters: {
    default?: Record<string, any>;
    contextual?: Array<{
      field: string;
      op: 'EQ' | 'IN' | 'GT' | 'LT' | 'like';
      value: string; // 表达式
      required?: boolean;
    }>;
    security?: Array<{
      field: string;
      op: 'EQ' | 'IN' | 'GT' | 'LT' | 'like';
      value: string; // 表达式
      if?: string; // 条件表达式
    }>;
  };
  pagination?: {
    pageSize: number;
    showSizeChanger?: boolean;
    showQuickJumper?: boolean;
    showTotal?: boolean;
    pageSizeOptions?: string[];
  };
}

// 表格列配置
export interface TableColumn {
  code: string;
  props: {
    label: Record<string, string>;
    width?: number;
    fixed?: 'left' | 'right';
    align?: 'left' | 'center' | 'right';
  };
  sortable?: boolean;
  filterable?: boolean;
  transform?: {
    type: 'fieldMasking' | 'dateFormat' | 'numberFormat' | 'custom';
    mask?: string;
    format?: string;
    if?: string; // 条件表达式
  };
}

// 表格区域配置
export interface TableRegion {
  type: 'table';
  name?: string;
  title?: Record<string, string>;
  layout?: {
    size: 'small' | 'middle' | 'large';
  };
  style?: {
    size: 'small' | 'middle' | 'large';
    scroll?: {
      x?: number;
      y?: number;
    };
  };
  props?: {
    rowKey: string;
    scroll?: {
      x?: number;
      y?: number;
    };
    rowSelection?: {
      type: 'checkbox' | 'radio';
    };
  };
  pagination?: {
    showSizeChanger?: boolean;
    showQuickJumper?: boolean;
    showTotal?: boolean;
    pageSizeOptions?: string[];
  };
  columns: TableColumn[];
  actions?: Array<{
    code: string;
    props: {
      label: Record<string, string>;
      icon?: string;
      type?: 'link' | 'button';
      danger?: boolean;
      confirm?: Record<string, string>;
    };
    permission?: string;
  }>;
  batchActions?: Array<{
    code: string;
    props: {
      label: Record<string, string>;
      icon?: string;
      danger?: boolean;
      confirm?: Record<string, string>;
    };
    permission?: string;
  }>;
}

// 操作区域配置
export interface ActionRegion {
  type: 'action';
  actions: Array<{
    code: string;
    props: {
      label: Record<string, string>;
      primary?: boolean;
      danger?: boolean;
      icon?: string;
      loading?: boolean;
    };
    permission?: string;
    visible?: string; // 表达式控制显示
  }>;
}

// 页面区域联合类型
export type PageRegion = FormRegion | FiltersRegion | PresetRegion | TableRegion | ActionRegion;

// 事件配置
export interface PageEvent {
  on: string; // 事件名称
  if?: string; // 条件表达式
  concurrency?: 'queue' | 'parallel' | 'switch';
  do: Array<{
    type: string;
    target?: string;
    payload?: Record<string, any>;
    query?: string; // 表达式
    assign?: string;
    props?: Record<string, any>;
    [key: string]: any;
  }>;
  catch?: Array<{
    type: string;
    level?: 'success' | 'error' | 'warning' | 'info';
    message: string;
    [key: string]: any;
  }>;
  finally?: Array<{
    type: string;
    target?: string;
    value?: any;
    [key: string]: any;
  }>;
}

// 页面 Schema 主结构
export interface PageSchema {
  meta: PageMeta;
  props?: PageProps;
  endpoint?: ApiEndpoint;
  api?: {
    list?: ApiEndpoint;
    detail?: ApiEndpoint;
    create?: ApiEndpoint;
    update?: ApiEndpoint;
    delete?: ApiEndpoint;
    [key: string]: ApiEndpoint | undefined;
  };
  regions: PageRegion[];
  events?: PageEvent[];
}

// 页面容器属性
export interface PageContainerProps {
  schema: PageSchema;
  context?: LowCodeContext;
  onEvent?: (eventName: string, payload?: any) => void;
  className?: string;
}

// 表单页面容器属性
export interface FormPageContainerProps extends PageContainerProps {
  initialData?: Record<string, any>;
  onSubmit?: (data: Record<string, any>) => Promise<void>;
  onCancel?: () => void;
}

// 列表页面容器属性
export interface ListPageContainerProps extends PageContainerProps {
  dataSource?: any[];
  loading?: boolean;
  onSearch?: (filters: Record<string, any>) => void;
  onReset?: () => void;
  onRowAction?: (action: string, record: any) => void;
  onBatchAction?: (action: string, selectedRows: any[]) => void;
}

// 页面类型枚举
export enum PageType {
  FORM = 'form',
  LIST = 'list',
  DETAIL = 'detail',
}

// 页面容器状态
export interface PageContainerState {
  loading: boolean;
  data: Record<string, any>;
  errors: Record<string, string>;
  selectedRows: any[];
  filters: Record<string, any>;
}
