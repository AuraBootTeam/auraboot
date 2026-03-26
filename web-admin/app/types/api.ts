/**
 * AuraBoot 低代码平台 - API 相关类型定义
 *
 * 定义了与后端 API 交互相关的类型，包括：
 * - HTTP 请求和响应类型
 * - 分页和搜索类型
 * - 错误处理类型
 * - API 配置类型
 */

// ============= HTTP 基础类型 =============

/**
 * HTTP 方法枚举
 */
export type HttpMethod = 'get' | 'post' | 'put' | 'delete' | 'patch' | 'head' | 'options';

/**
 * HTTP 状态码枚举
 */
export type HttpStatusCode =
  | 200
  | 201
  | 202
  | 204
  | 400
  | 401
  | 403
  | 404
  | 409
  | 422
  | 429
  | 500
  | 502
  | 503
  | 504;

/**
 * 内容类型枚举
 */
export type ContentType =
  | 'application/json'
  | 'application/x-www-form-urlencoded'
  | 'multipart/form-data'
  | 'text/plain'
  | 'text/html'
  | 'application/xml';

// ============= 请求类型 =============

/**
 * API 请求头
 */
export interface ApiHeaders {
  'Content-Type'?: ContentType;
  Authorization?: string;
  Accept?: string;
  'Accept-Language'?: string;
  'X-Tenant-Id'?: string;
  'X-Request-Id'?: string;
  'X-Client-Version'?: string;
  [key: string]: string | undefined;
}

/**
 * API 请求参数
 */
export interface ApiRequestParams {
  [key: string]: string | number | boolean | string[] | number[] | undefined;
}

/**
 * API 请求配置
 */
export interface ApiRequestConfig {
  /** 请求 URL */
  url: string;

  /** HTTP 方法 */
  method?: HttpMethod;

  /** 请求头 */
  headers?: ApiHeaders;

  /** URL 参数 */
  params?: ApiRequestParams;

  /** 请求体数据 */
  data?: unknown;

  /** 超时时间（毫秒） */
  timeout?: number;

  /** 是否携带凭证 */
  withCredentials?: boolean;

  /** 响应类型 */
  responseType?: 'json' | 'text' | 'blob' | 'arraybuffer';

  /** 请求拦截器 */
  requestInterceptor?: (config: ApiRequestConfig) => ApiRequestConfig | Promise<ApiRequestConfig>;

  /** 响应拦截器 */
  responseInterceptor?: (
    response: ApiResponse<unknown>,
  ) => ApiResponse<unknown> | Promise<ApiResponse<unknown>>;

  /** 错误拦截器 */
  errorInterceptor?: (error: ApiError) => ApiError | Promise<ApiError>;

  /** 重试配置 */
  retry?: {
    times: number;
    delay: number;
    condition?: (error: ApiError) => boolean;
  };

  /** 缓存配置 */
  cache?: {
    enabled: boolean;
    duration?: number; // 秒
    key?: string;
  };

  /** 是否显示加载状态 */
  loading?: boolean;

  /** 是否显示错误提示 */
  showError?: boolean;

  /** 自定义元数据 */
  meta?: Record<string, unknown>;
}

// ============= 响应类型 =============

/**
 * API 响应基础接口
 */
export interface ApiResponse<T = unknown> {
  /** 响应数据 */
  data: T;

  /** 响应消息 */
  message?: string;

  /** 响应代码 */
  code?: string;

  /** 时间戳 */
  timestamp?: number;

  /** 请求 ID */
  requestId?: string;

  /** 服务器版本 */
  serverVersion?: string;

  /** 额外元数据 */
  meta?: Record<string, unknown>;
}

/**
 * 分页请求参数
 */
export interface PaginationRequest {
  /** 页码（从 1 开始） */
  page: number;

  /** 每页大小 */
  size: number;

  /** 搜索关键词 */
  keyword?: string;

  /** 排序字段 */
  sortBy?: string;

  /** 排序方向 */
  sortOrder?: 'asc' | 'desc';

  /** 过滤条件 */
  filters?: Record<string, unknown>;

  /** 搜索字段 */
  searchFields?: string[];

  /** 日期范围 */
  dateRange?: {
    field: string;
    start?: string;
    end?: string;
  };

  /** 额外参数 */
  extra?: Record<string, unknown>;
}

/**
 * 分页响应结果
 */
export interface PaginationResult<T = unknown> {
  /** 数据列表 */
  records: T[];

  /** 总记录数 */
  total: number;

  /** 当前页码 */
  page: number;

  /** 每页大小 */
  pageSize: number;

  /** 总页数 */
  totalPages: number;
}

// ============= 错误类型 =============

/**
 * API 错误类型枚举
 */
export type ApiErrorType =
  | 'network_error'
  | 'timeout_error'
  | 'parse_error'
  | 'validation_error'
  | 'authentication_error'
  | 'authorization_error'
  | 'not_found_error'
  | 'conflict_error'
  | 'server_error'
  | 'unknown_error';

/**
 * API 错误详情
 */
export interface ApiErrorDetail {
  /** 错误字段 */
  field?: string;

  /** 错误代码 */
  code: string;

  /** 错误消息 */
  message: string;

  /** 错误值 */
  rejectedValue?: unknown;

  /** 错误路径 */
  path?: string;
}

/**
 * API 错误接口
 */
export interface ApiError {
  /** 错误类型 */
  type: ApiErrorType;

  /** 错误代码 */
  code: string;

  /** 错误消息 */
  message: string;

  /** HTTP 状态码 */
  status?: HttpStatusCode;

  /** 错误详情 */
  details?: ApiErrorDetail[];

  /** 原始错误 */
  originalError?: unknown;

  /** 请求配置 */
  config?: ApiRequestConfig;

  /** 时间戳 */
  timestamp?: number;

  /** 请求 ID */
  requestId?: string;

  /** 堆栈信息 */
  stack?: string;

  /** 是否可重试 */
  retryable?: boolean;

  /** 建议操作 */
  suggestion?: string;
}

// ============= 批量操作类型 =============

/**
 * 批量操作请求
 */
export interface BatchRequest<T = unknown> {
  /** 操作类型 */
  operation: 'create' | 'update' | 'delete';

  /** 操作数据 */
  items: T[];

  /** 批量大小 */
  batchSize?: number;

  /** 是否并行执行 */
  parallel?: boolean;

  /** 失败策略 */
  failureStrategy?: 'stop' | 'continue' | 'rollback';

  /** 额外参数 */
  options?: Record<string, unknown>;
}

/**
 * 批量操作结果
 */
export interface BatchResult<T = unknown> {
  /** 总数量 */
  total: number;

  /** 成功数量 */
  success: number;

  /** 失败数量 */
  failed: number;

  /** 跳过数量 */
  skipped: number;

  /** 成功项目 */
  successItems: T[];

  /** 失败项目 */
  failedItems: Array<{
    item: T;
    error: ApiError;
  }>;

  /** 跳过项目 */
  skippedItems: Array<{
    item: T;
    reason: string;
  }>;

  /** 执行时间（毫秒） */
  duration: number;

  /** 是否部分成功 */
  partialSuccess: boolean;
}

// ============= 文件上传类型 =============

/**
 * 文件上传请求
 */
export interface FileUploadRequest {
  /** 文件对象 */
  file: File;

  /** 上传路径 */
  path?: string;

  /** 文件名 */
  filename?: string;

  /** 文件类型 */
  contentType?: string;

  /** 额外参数 */
  params?: Record<string, unknown>;

  /** 上传进度回调 */
  onProgress?: (progress: number) => void;

  /** 分片上传配置 */
  chunk?: {
    enabled: boolean;
    size: number; // 字节
  };
}

/**
 * 文件上传响应
 */
export interface FileUploadResponse {
  /** 文件 ID */
  id: string;

  /** 文件名 */
  filename: string;

  /** 原始文件名 */
  originalFilename: string;

  /** 文件大小 */
  size: number;

  /** 文件类型 */
  contentType: string;

  /** 文件路径 */
  path: string;

  /** 访问 URL */
  url: string;

  /** 缩略图 URL */
  thumbnailUrl?: string;

  /** 文件哈希 */
  hash?: string;

  /** 上传时间 */
  uploadTime: string;

  /** 额外元数据 */
  metadata?: Record<string, unknown>;
}

// ============= WebSocket 类型 =============

/**
 * WebSocket 消息类型
 */
export type WebSocketMessageType =
  | 'ping'
  | 'pong'
  | 'subscribe'
  | 'unsubscribe'
  | 'notification'
  | 'data'
  | 'error'
  | 'close';

/**
 * WebSocket 消息
 */
export interface WebSocketMessage<T = unknown> {
  /** 消息 ID */
  id: string;

  /** 消息类型 */
  type: WebSocketMessageType;

  /** 消息主题 */
  topic?: string;

  /** 消息数据 */
  data: T;

  /** 时间戳 */
  timestamp: number;

  /** 发送者 */
  sender?: string;

  /** 接收者 */
  receiver?: string;
}

/**
 * WebSocket 配置
 */
export interface WebSocketConfig {
  /** WebSocket URL */
  url: string;

  /** 协议 */
  protocols?: string[];

  /** 重连配置 */
  reconnect?: {
    enabled: boolean;
    maxAttempts: number;
    delay: number;
    backoff: number;
  };

  /** 心跳配置 */
  heartbeat?: {
    enabled: boolean;
    interval: number;
    timeout: number;
  };

  /** 消息队列大小 */
  queueSize?: number;

  /** 是否自动连接 */
  autoConnect?: boolean;
}

// ============= API 客户端类型 =============

/**
 * API 客户端配置
 */
export interface ApiClientConfig {
  /** 基础 URL */
  baseURL: string;

  /** 默认请求头 */
  defaultHeaders?: ApiHeaders;

  /** 默认超时时间 */
  timeout?: number;

  /** 是否携带凭证 */
  withCredentials?: boolean;

  /** 请求拦截器 */
  requestInterceptors?: Array<
    (config: ApiRequestConfig) => ApiRequestConfig | Promise<ApiRequestConfig>
  >;

  /** 响应拦截器 */
  responseInterceptors?: Array<
    (response: ApiResponse<unknown>) => ApiResponse<unknown> | Promise<ApiResponse<unknown>>
  >;

  /** 错误拦截器 */
  errorInterceptors?: Array<(error: ApiError) => ApiError | Promise<ApiError>>;

  /** 重试配置 */
  retry?: {
    times: number;
    delay: number;
    condition?: (error: ApiError) => boolean;
  };

  /** 缓存配置 */
  cache?: {
    enabled: boolean;
    storage: 'memory' | 'localStorage' | 'sessionStorage';
    defaultDuration: number;
  };

  /** 日志配置 */
  logging?: {
    enabled: boolean;
    level: 'debug' | 'info' | 'warn' | 'error';
    includeHeaders: boolean;
    includeData: boolean;
  };
}

/**
 * API 客户端接口
 */
export interface ApiClient {
  /** 发送 GET 请求 */
  get<T = unknown>(url: string, config?: Partial<ApiRequestConfig>): Promise<ApiResponse<T>>;

  /** 发送 POST 请求 */
  post<T = unknown>(
    url: string,
    data?: unknown,
    config?: Partial<ApiRequestConfig>,
  ): Promise<ApiResponse<T>>;

  /** 发送 PUT 请求 */
  put<T = unknown>(
    url: string,
    data?: unknown,
    config?: Partial<ApiRequestConfig>,
  ): Promise<ApiResponse<T>>;

  /** 发送 DELETE 请求 */
  delete<T = unknown>(url: string, config?: Partial<ApiRequestConfig>): Promise<ApiResponse<T>>;

  /** 发送 PATCH 请求 */
  patch<T = unknown>(
    url: string,
    data?: unknown,
    config?: Partial<ApiRequestConfig>,
  ): Promise<ApiResponse<T>>;

  /** 发送通用请求 */
  request<T = unknown>(config: ApiRequestConfig): Promise<ApiResponse<T>>;

  /** 批量请求 */
  batch<T = unknown>(requests: ApiRequestConfig[]): Promise<ApiResponse<T>[]>;

  /** 上传文件 */
  upload(request: FileUploadRequest): Promise<ApiResponse<FileUploadResponse>>;

  /** 下载文件 */
  download(url: string, filename?: string): Promise<void>;

  /** 设置默认头部 */
  setDefaultHeader(key: string, value: string): void;

  /** 移除默认头部 */
  removeDefaultHeader(key: string): void;

  /** 添加请求拦截器 */
  addRequestInterceptor(
    interceptor: (config: ApiRequestConfig) => ApiRequestConfig | Promise<ApiRequestConfig>,
  ): void;

  /** 添加响应拦截器 */
  addResponseInterceptor(
    interceptor: (
      response: ApiResponse<unknown>,
    ) => ApiResponse<unknown> | Promise<ApiResponse<unknown>>,
  ): void;

  /** 添加错误拦截器 */
  addErrorInterceptor(interceptor: (error: ApiError) => ApiError | Promise<ApiError>): void;
}

// ============= 服务接口类型 =============

/**
 * CRUD 服务接口
 */
export interface CrudService<T, CreateRequest, UpdateRequest> {
  /** 获取列表 */
  list(request: PaginationRequest): Promise<PaginationResult<T>>;

  /** 根据 ID 获取详情 */
  getById(id: string): Promise<T>;

  /** 创建记录 */
  create(request: CreateRequest): Promise<T>;

  /** 更新记录 */
  update(id: string, request: UpdateRequest): Promise<T>;

  /** 删除记录 */
  delete(id: string): Promise<void>;

  /** 批量删除 */
  batchDelete(ids: string[]): Promise<BatchResult<string>>;

  /** 批量创建 */
  batchCreate(requests: CreateRequest[]): Promise<BatchResult<T>>;

  /** 批量更新 */
  batchUpdate(updates: Array<{ id: string; data: UpdateRequest }>): Promise<BatchResult<T>>;
}

// ============= 类型已通过接口定义自动导出 =============
// 所有接口和类型别名已通过 export 关键字自动导出，无需重复导出
