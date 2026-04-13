/**
 * 版本管理系统类型定义
 */

import type { PageSchema } from '~/plugins/core-designer/components/studio/domain/schema/types';

/**
 * 版本状态枚举
 */
export enum VersionStatus {
  draft = 'draft', // 草稿
  published = 'published', // 已发布
  archived = 'archived', // 已归档
}

/**
 * 版本类型枚举
 */
export enum VersionType {
  MAJOR = 'major', // 主版本
  MINOR = 'minor', // 次版本
  PATCH = 'patch', // 补丁版本
  HOTFIX = 'hotfix', // 热修复
  SNAPSHOT = 'snapshot', // 快照版本
}

/**
 * 版本信息接口
 */
export interface Version {
  /** 版本ID */
  id: string;
  /** 版本号 */
  version: string;
  /** 版本状态 */
  status: VersionStatus;
  /** 版本类型 */
  type: VersionType;
  /** 页面Schema */
  schema: PageSchema;
  /** 创建时间 */
  createdAt: Date;
  /** 更新时间 */
  updatedAt: Date;
  /** 创建者 */
  createdBy: string;
  /** 更新者 */
  updatedBy: string;
  /** 版本描述 */
  description?: string;
  /** 变更日志 */
  changelog?: string;
  /** 标签 */
  tags?: string[];
  /** 是否为当前版本 */
  isCurrent?: boolean;
  /** 父版本ID */
  parentVersionId?: string;
  /** 发布时间 */
  publishedAt?: Date;
  /** 发布者 */
  publishedBy?: string;
}

/**
 * 版本比较结果
 */
export interface VersionDiff {
  /** 版本A */
  versionA: Version;
  /** 版本B */
  versionB: Version;
  /** 差异详情 */
  differences: VersionDifference[];
  /** 差异统计 */
  stats: {
    added: number;
    modified: number;
    deleted: number;
  };
}

/**
 * 版本差异项
 */
export interface VersionDifference {
  /** 差异类型 */
  type: 'added' | 'modified' | 'deleted';
  /** 路径 */
  path: string;
  /** 旧值 */
  oldValue?: any;
  /** 新值 */
  newValue?: any;
  /** 描述 */
  description: string;
}

/**
 * 版本创建请求
 */
export interface CreateVersionRequest {
  /** 页面Schema */
  schema: PageSchema;
  /** 版本类型 */
  type: VersionType;
  /** 版本描述 */
  description?: string;
  /** 变更日志 */
  changelog?: string;
  /** 标签 */
  tags?: string[];
  /** 基于的父版本ID */
  baseVersionId?: string;
}

/**
 * 版本更新请求
 */
export interface UpdateVersionRequest {
  /** 版本ID */
  versionId: string;
  /** 页面Schema */
  schema?: PageSchema;
  /** 版本描述 */
  description?: string;
  /** 变更日志 */
  changelog?: string;
  /** 标签 */
  tags?: string[];
}

/**
 * 版本发布请求
 */
export interface PublishVersionRequest {
  /** 版本ID */
  versionId: string;
  /** 发布描述 */
  description?: string;
  /** 是否强制发布 */
  force?: boolean;
}

/**
 * 版本回滚请求
 */
export interface RollbackVersionRequest {
  /** 目标版本ID */
  targetVersionId: string;
  /** 回滚描述 */
  description?: string;
  /** 是否创建新版本 */
  createNewVersion?: boolean;
}

/**
 * 版本查询参数
 */
export interface VersionQueryParams {
  /** 页面ID */
  pageId?: string;
  /** 版本状态 */
  status?: VersionStatus;
  /** 版本类型 */
  type?: VersionType;
  /** 创建者 */
  createdBy?: string;
  /** 标签 */
  tags?: string[];
  /** 开始时间 */
  startDate?: Date;
  /** 结束时间 */
  endDate?: Date;
  /** 页码 */
  page?: number;
  /** 页大小 */
  size?: number;
  /** 排序字段 */
  sortBy?: string;
  /** 排序方向 */
  sortOrder?: 'asc' | 'desc';
}

/**
 * 版本列表响应
 */
export interface VersionListResponse {
  /** 版本列表 */
  versions: Version[];
  /** 总数 */
  total: number;
  /** 当前页 */
  page: number;
  /** 页大小 */
  size: number;
  /** 总页数 */
  totalPages: number;
}

/**
 * 版本管理器接口
 */
export interface VersionManager {
  /** 创建版本 */
  createVersion(pageId: string, request: CreateVersionRequest): Promise<Version>;

  /** 更新版本 */
  updateVersion(request: UpdateVersionRequest): Promise<Version>;

  /** 删除版本 */
  deleteVersion(pageId: string, versionId: string): Promise<void>;

  /** 获取版本详情 */
  getVersion(versionId: string): Promise<Version>;

  /** 获取版本列表 */
  getVersions(pageId: string, params?: VersionQueryParams): Promise<VersionListResponse>;

  /** 获取当前版本 */
  getCurrentVersion(pageId: string): Promise<Version>;

  /** 获取已发布版本 */
  getPublishedVersion(pageId: string): Promise<Version>;

  /** 发布版本 */
  publishVersion(
    pageId: string,
    versionId: string,
    request: PublishVersionRequest,
  ): Promise<Version>;

  /** 取消发布 */
  unpublishVersion(versionId: string): Promise<Version>;

  /** 回滚版本 */
  rollbackVersion(pageId: string, request: RollbackVersionRequest): Promise<Version>;

  /** 比较版本 */
  compareVersions(pageId: string, versionAId: string, versionBId: string): Promise<VersionDiff>;

  /** 复制版本 */
  duplicateVersion(versionId: string, description?: string): Promise<Version>;

  /** 归档版本 */
  archiveVersion(versionId: string): Promise<Version>;

  /** 恢复版本 */
  restoreVersion(versionId: string): Promise<Version>;
}

/**
 * 版本事件类型
 */
export enum VersionEventType {
  VERSION_CREATED = 'version_created',
  VERSION_UPDATED = 'version_updated',
  VERSION_DELETED = 'version_deleted',
  VERSION_PUBLISHED = 'version_published',
  VERSION_UNPUBLISHED = 'version_unpublished',
  VERSION_ROLLED_BACK = 'version_rolled_back',
  VERSION_ARCHIVED = 'version_archived',
  VERSION_RESTORED = 'version_restored',
}

/**
 * 版本事件
 */
export interface VersionEvent {
  /** 事件类型 */
  type: VersionEventType;
  /** 页面ID */
  pageId: string;
  /** 版本ID */
  versionId: string;
  /** 版本信息 */
  version: Version;
  /** 事件时间 */
  timestamp: Date;
  /** 操作者 */
  operator: string;
  /** 事件数据 */
  data?: any;
}

/**
 * 版本事件监听器
 */
export interface VersionEventListener {
  /** 事件类型 */
  eventType: VersionEventType;
  /** 处理函数 */
  handler: (event: VersionEvent) => void | Promise<void>;
}

/**
 * 版本配置
 */
export interface VersionConfig {
  /** 最大版本数量 */
  maxVersions?: number;
  /** 自动清理旧版本 */
  autoCleanup?: boolean;
  /** 清理保留天数 */
  cleanupRetentionDays?: number;
  /** 启用版本压缩 */
  enableCompression?: boolean;
  /** 启用增量存储 */
  enableIncrementalStorage?: boolean;
  /** API 基础URL */
  apiBaseUrl?: string;
  /** 认证令牌 */
  authToken?: string;
}

/**
 * 版本存储接口
 */
export interface VersionStorage {
  /** 保存版本 */
  saveVersion(version: Version): Promise<void>;

  /** 加载版本 */
  loadVersion(versionId: string): Promise<Version>;

  /** 删除版本 */
  deleteVersion(versionId: string): Promise<void>;

  /** 列出版本 */
  listVersions(pageId: string, params?: VersionQueryParams): Promise<VersionListResponse>;

  /** 检查版本是否存在 */
  versionExists(versionId: string): Promise<boolean>;
}

/**
 * 版本同步状态
 */
export enum SyncStatus {
  SYNCED = 'synced', // 已同步
  pending = 'pending', // 待同步
  SYNCING = 'syncing', // 同步中
  failed = 'failed', // 同步失败
  CONFLICT = 'conflict', // 冲突
}

/**
 * 版本同步信息
 */
export interface VersionSync {
  /** 版本ID */
  versionId: string;
  /** 同步状态 */
  status: SyncStatus;
  /** 最后同步时间 */
  lastSyncAt?: Date;
  /** 同步错误信息 */
  error?: string;
  /** 冲突详情 */
  conflicts?: VersionDifference[];
}

/**
 * 版本锁定信息
 */
export interface VersionLock {
  /** 版本ID */
  versionId: string;
  /** 锁定者 */
  lockedBy: string;
  /** 锁定时间 */
  lockedAt: Date;
  /** 锁定原因 */
  reason?: string;
  /** 锁定过期时间 */
  expiresAt?: Date;
}
