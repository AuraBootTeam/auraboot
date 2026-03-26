/**
 * AuraBoot 低代码平台 - 类型系统统一导出
 *
 * 统一导出所有类型定义，提供便捷的导入方式
 */

// ============= 核心 Schema 类型 =============
export * from '~/types/schema';

// ============= 表达式上下文类型 =============
export * from '~/types/context';

// ============= 字段配置类型 =============
export * from '~/types/field';

// ============= API 相关类型（选择性导出以避免与 context.ts 冲突） =============
export type {
  // HTTP 基础类型
  HttpStatusCode,
  ContentType,

  // 请求类型
  ApiHeaders,
  ApiRequestParams,

  // 响应类型
  PaginationRequest,
  PaginationResult,

  // 错误类型
  ApiErrorType,
  ApiErrorDetail,
  ApiError,

  // 批量操作类型
  BatchRequest,
  BatchResult,

  // 文件上传类型
  FileUploadRequest,
  FileUploadResponse,

  // WebSocket 类型
  WebSocketMessageType,
  WebSocketMessage,
  WebSocketConfig,

  // API 客户端类型
  ApiClientConfig,
  ApiClient,

  // 服务接口类型
  CrudService,
} from '~/types/api';

// ============= 现有类型（选择性导出以避免冲突） =============
export type { UserProfile, UpdateUserProfileRequest } from '~/types/profile';

// 从 dynamic.ts 导出不冲突的类型
export type {
  FormSchema as DynamicFormSchema,
  ListSchema as DynamicListSchema,
  PageSchema as DynamicPageSchema,
  ColumnDefinition,
  ActionDefinition,
  FieldDefinition as DynamicFieldDefinition,
} from '~/types/dynamic';

// ============= 类型工具函数 =============

/**
 * 提取对象的键类型
 */
export type KeysOf<T> = keyof T;

/**
 * 提取对象的值类型
 */
export type ValuesOf<T> = T[keyof T];

/**
 * 使对象的所有属性可选
 */
export type PartialDeep<T> = {
  [P in keyof T]?: T[P] extends object ? PartialDeep<T[P]> : T[P];
};

/**
 * 使对象的所有属性必需
 */
export type RequiredDeep<T> = {
  [P in keyof T]-?: T[P] extends object ? RequiredDeep<T[P]> : T[P];
};

/**
 * 排除对象的某些属性
 */
export type OmitDeep<T, K extends keyof any> = {
  [P in keyof T as P extends K ? never : P]: T[P] extends object ? OmitDeep<T[P], K> : T[P];
};

/**
 * 选择对象的某些属性
 */
export type PickDeep<T, K extends keyof any> = {
  [P in keyof T as P extends K ? P : never]: T[P] extends object ? PickDeep<T[P], K> : T[P];
};

/**
 * 类型守卫工具
 */
export type TypeGuard<T> = (value: unknown) => value is T;

/**
 * 创建类型守卫
 */
export function createTypeGuard<T>(validator: (value: unknown) => boolean): TypeGuard<T> {
  return (value: unknown): value is T => validator(value);
}

/**
 * 检查是否为非空值
 */
export function isNonNull<T>(value: T | null | undefined): value is T {
  return value !== null && value !== undefined;
}

/**
 * 检查是否为字符串
 */
export function isString(value: unknown): value is string {
  return typeof value === 'string';
}

/**
 * 检查是否为数字
 */
export function isNumber(value: unknown): value is number {
  return typeof value === 'number' && !isNaN(value);
}

/**
 * 检查是否为布尔值
 */
export function isBoolean(value: unknown): value is boolean {
  return typeof value === 'boolean';
}

/**
 * 检查是否为对象
 */
export function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/**
 * 检查是否为数组
 */
export function isArray<T>(value: unknown): value is T[] {
  return Array.isArray(value);
}

/**
 * 检查是否为函数
 */
export function isFunction(value: unknown): value is Function {
  return typeof value === 'function';
}
