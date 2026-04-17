/**
 * 版本管理器实现
 */

import type {
  Version,
  VersionManager,
  CreateVersionRequest,
  UpdateVersionRequest,
  PublishVersionRequest,
  RollbackVersionRequest,
  VersionQueryParams,
  VersionListResponse,
  VersionDiff,
  VersionDifference,
  VersionEvent,
  VersionEventListener,
  VersionConfig,
  VersionStorage,
  VersionSync,
  SyncStatus,
  VersionLock,
} from '~/plugins/core-designer/components/studio/domain/metadata/types';
import { VersionStatus, VersionType, VersionEventType } from '~/plugins/core-designer/components/studio/domain/metadata/types';
import { ApiService } from '~/shared/services/ApiService';

/**
 * 版本管理器实现类
 */
export class VersionManagerImpl implements VersionManager {
  private static _warnedGetCurrentUser = false;

  private apiService: ApiService;
  private eventListeners: Map<VersionEventType, VersionEventListener[]> = new Map();
  private config: VersionConfig;
  private storage?: VersionStorage;
  private locks: Map<string, VersionLock> = new Map();
  private syncStatus: Map<string, VersionSync> = new Map();

  constructor(config: VersionConfig, storage?: VersionStorage) {
    this.config = config;
    this.storage = storage;
    this.apiService = new ApiService({
      baseURL: config.apiBaseUrl || '/api',
    });

    this.initializeEventTypes();
  }

  /**
   * 初始化事件类型
   */
  private initializeEventTypes(): void {
    Object.values(VersionEventType).forEach((eventType) => {
      this.eventListeners.set(eventType, []);
    });
  }

  /**
   * 创建版本
   */
  async createVersion(pageId: string, request: CreateVersionRequest): Promise<Version> {
    try {
      // 验证请求
      this.validateCreateRequest(request);

      // 生成版本号
      const version = await this.generateVersionNumber(pageId, request.type, request.baseVersionId);

      // 创建版本对象
      const newVersion: Version = {
        id: this.generateVersionId(),
        version,
        status: VersionStatus.draft,
        type: request.type,
        schema: request.schema,
        createdAt: new Date(),
        updatedAt: new Date(),
        createdBy: this.getCurrentUser(),
        updatedBy: this.getCurrentUser(),
        description: request.description,
        changelog: request.changelog,
        tags: request.tags || [],
        parentVersionId: request.baseVersionId,
      };

      // 保存到存储
      if (this.storage) {
        await this.storage.saveVersion(newVersion);
      }

      // 调用API
      const response = await this.apiService.post<Version>(`/pages/${pageId}/versions`, newVersion);
      const savedVersion = response.data;

      // 触发事件
      await this.emitEvent({
        type: VersionEventType.VERSION_CREATED,
        pageId,
        versionId: savedVersion.id,
        version: savedVersion,
        timestamp: new Date(),
        operator: this.getCurrentUser(),
      });

      return savedVersion;
    } catch (error) {
      console.error('Failed to create version:', error);
      throw error;
    }
  }

  /**
   * 更新版本
   */
  async updateVersion(request: UpdateVersionRequest): Promise<Version> {
    try {
      // 检查版本锁定
      await this.checkVersionLock(request.versionId);

      // 获取现有版本
      const existingVersion = await this.getVersion(request.versionId);

      // 检查版本状态
      if (existingVersion.status === VersionStatus.published) {
        throw new Error('Cannot update published version');
      }

      // 更新版本对象
      const updatedVersion: Version = {
        ...existingVersion,
        schema: request.schema || existingVersion.schema,
        description: request.description || existingVersion.description,
        changelog: request.changelog || existingVersion.changelog,
        tags: request.tags || existingVersion.tags,
        updatedAt: new Date(),
        updatedBy: this.getCurrentUser(),
      };

      // 保存到存储
      if (this.storage) {
        await this.storage.saveVersion(updatedVersion);
      }

      // 调用API
      const response = await this.apiService.put<Version>(
        `/versions/${request.versionId}`,
        updatedVersion,
      );
      const savedVersion = response.data;

      // 触发事件
      await this.emitEvent({
        type: VersionEventType.VERSION_UPDATED,
        pageId: savedVersion.schema.id,
        versionId: savedVersion.id,
        version: savedVersion,
        timestamp: new Date(),
        operator: this.getCurrentUser(),
      });

      return savedVersion;
    } catch (error) {
      console.error('Failed to update version:', error);
      throw error;
    }
  }

  /**
   * 删除版本
   */
  async deleteVersion(pageId: string, versionId: string): Promise<void> {
    try {
      // 检查版本锁定
      await this.checkVersionLock(versionId);

      // 获取版本信息
      const version = await this.getVersion(versionId);

      // 检查版本状态
      if (version.status === VersionStatus.published) {
        throw new Error('Cannot delete published version');
      }

      // 从存储删除
      if (this.storage) {
        await this.storage.deleteVersion(versionId);
      }

      // 调用API
      await this.apiService.delete(`/versions/${versionId}`);

      // 触发事件
      await this.emitEvent({
        type: VersionEventType.VERSION_DELETED,
        pageId: version.schema.id,
        versionId: version.id,
        version,
        timestamp: new Date(),
        operator: this.getCurrentUser(),
      });
    } catch (error) {
      console.error('Failed to delete version:', error);
      throw error;
    }
  }

  /**
   * 获取版本详情
   */
  async getVersion(versionId: string): Promise<Version> {
    try {
      // 先从存储获取
      if (this.storage && (await this.storage.versionExists(versionId))) {
        return await this.storage.loadVersion(versionId);
      }

      // 从API获取
      const response = await this.apiService.get<Version>(`/versions/${versionId}`);
      return response.data;
    } catch (error) {
      console.error('Failed to get version:', error);
      throw error;
    }
  }

  /**
   * 获取版本列表
   */
  async getVersions(pageId: string, params?: VersionQueryParams): Promise<VersionListResponse> {
    try {
      // 构建查询参数
      const queryParams = new URLSearchParams();
      if (params) {
        Object.entries(params).forEach(([key, value]) => {
          if (value !== undefined && value !== null) {
            queryParams.append(key, String(value));
          }
        });
      }

      // 调用API
      const response = await this.apiService.get<VersionListResponse>(`/pages/${pageId}/versions`, {
        params,
      });
      return response.data;
    } catch (error) {
      console.error('Failed to get versions:', error);
      throw error;
    }
  }

  /**
   * 获取当前版本
   */
  async getCurrentVersion(pageId: string): Promise<Version> {
    try {
      const response = await this.apiService.get<Version>(`/pages/${pageId}/versions/current`);
      return response.data;
    } catch (error) {
      console.error('Failed to get current version:', error);
      throw error;
    }
  }

  /**
   * 获取已发布版本
   */
  async getPublishedVersion(pageId: string): Promise<Version> {
    try {
      const response = await this.apiService.get<Version>(`/pages/${pageId}/versions/published`);
      return response.data;
    } catch (error) {
      console.error('Failed to get published version:', error);
      throw error;
    }
  }

  /**
   * 发布版本
   */
  async publishVersion(
    pageId: string,
    versionId: string,
    request: PublishVersionRequest,
  ): Promise<Version> {
    try {
      // 检查版本锁定
      await this.checkVersionLock(request.versionId);

      // 获取版本信息
      const version = await this.getVersion(versionId);

      // 检查版本状态
      if (version.status === VersionStatus.published && !request.force) {
        throw new Error('Version is already published');
      }

      // 验证版本Schema
      await this.validateVersionSchema(version.schema);

      // 更新版本状态
      const publishedVersion: Version = {
        ...version,
        status: VersionStatus.published,
        publishedAt: new Date(),
        publishedBy: this.getCurrentUser(),
        updatedAt: new Date(),
        updatedBy: this.getCurrentUser(),
      };

      // 调用API
      const response = await this.apiService.post<Version>(`/versions/${versionId}/publish`, {
        description: request.description,
        force: request.force,
      });
      const result = response.data;

      // 触发事件
      await this.emitEvent({
        type: VersionEventType.VERSION_PUBLISHED,
        pageId: result.schema.id,
        versionId: result.id,
        version: result,
        timestamp: new Date(),
        operator: this.getCurrentUser(),
      });

      return result;
    } catch (error) {
      console.error('Failed to publish version:', error);
      throw error;
    }
  }

  /**
   * 取消发布
   */
  async unpublishVersion(versionId: string): Promise<Version> {
    try {
      // 检查版本锁定
      await this.checkVersionLock(versionId);

      // 调用API
      const response = await this.apiService.post<Version>(`/versions/${versionId}/unpublish`);
      const result = response.data;

      // 触发事件
      await this.emitEvent({
        type: VersionEventType.VERSION_UNPUBLISHED,
        pageId: result.schema.id,
        versionId: result.id,
        version: result,
        timestamp: new Date(),
        operator: this.getCurrentUser(),
      });

      return result;
    } catch (error) {
      console.error('Failed to unpublish version:', error);
      throw error;
    }
  }

  /**
   * 回滚版本
   */
  async rollbackVersion(pageId: string, request: RollbackVersionRequest): Promise<Version> {
    try {
      // 获取目标版本
      const targetVersion = await this.getVersion(request.targetVersionId);

      // 检查目标版本状态
      if (targetVersion.status !== VersionStatus.published) {
        throw new Error('Can only rollback to published version');
      }

      let result: Version;

      if (request.createNewVersion) {
        // 创建新版本
        result = await this.createVersion(pageId, {
          schema: targetVersion.schema,
          type: VersionType.PATCH,
          description: request.description || `Rollback to version ${targetVersion.version}`,
          baseVersionId: request.targetVersionId,
        });
      } else {
        // 直接回滚
        const response = await this.apiService.post<Version>(`/pages/${pageId}/rollback`, {
          targetVersionId: request.targetVersionId,
          description: request.description,
        });
        result = response.data;
      }

      // 触发事件
      await this.emitEvent({
        type: VersionEventType.VERSION_ROLLED_BACK,
        pageId,
        versionId: result.id,
        version: result,
        timestamp: new Date(),
        operator: this.getCurrentUser(),
        data: { targetVersionId: request.targetVersionId },
      });

      return result;
    } catch (error) {
      console.error('Failed to rollback version:', error);
      throw error;
    }
  }

  /**
   * 比较版本
   */
  async compareVersions(
    pageId: string,
    versionAId: string,
    versionBId: string,
  ): Promise<VersionDiff> {
    try {
      // 获取两个版本
      const [versionA, versionB] = await Promise.all([
        this.getVersion(versionAId),
        this.getVersion(versionBId),
      ]);

      // 计算差异
      const differences = this.calculateDifferences(versionA.schema, versionB.schema);

      // 统计差异
      const stats = {
        added: differences.filter((d) => d.type === 'added').length,
        modified: differences.filter((d) => d.type === 'modified').length,
        deleted: differences.filter((d) => d.type === 'deleted').length,
      };

      return {
        versionA,
        versionB,
        differences,
        stats,
      };
    } catch (error) {
      console.error('Failed to compare versions:', error);
      throw error;
    }
  }

  /**
   * 复制版本
   */
  async duplicateVersion(versionId: string, description?: string): Promise<Version> {
    try {
      // 获取源版本
      const sourceVersion = await this.getVersion(versionId);

      // 创建新版本
      return await this.createVersion(sourceVersion.schema.id, {
        schema: sourceVersion.schema,
        type: VersionType.MINOR,
        description: description || `Copy of version ${sourceVersion.version}`,
        baseVersionId: versionId,
      });
    } catch (error) {
      console.error('Failed to duplicate version:', error);
      throw error;
    }
  }

  /**
   * 归档版本
   */
  async archiveVersion(versionId: string): Promise<Version> {
    try {
      const response = await this.apiService.post<Version>(`/versions/${versionId}/archive`);
      const result = response.data;

      await this.emitEvent({
        type: VersionEventType.VERSION_ARCHIVED,
        pageId: result.schema.id,
        versionId: result.id,
        version: result,
        timestamp: new Date(),
        operator: this.getCurrentUser(),
      });

      return result;
    } catch (error) {
      console.error('Failed to archive version:', error);
      throw error;
    }
  }

  /**
   * 恢复版本
   */
  async restoreVersion(versionId: string): Promise<Version> {
    try {
      const response = await this.apiService.post<Version>(`/versions/${versionId}/restore`);
      const result = response.data;

      await this.emitEvent({
        type: VersionEventType.VERSION_RESTORED,
        pageId: result.schema.id,
        versionId: result.id,
        version: result,
        timestamp: new Date(),
        operator: this.getCurrentUser(),
      });

      return result;
    } catch (error) {
      console.error('Failed to restore version:', error);
      throw error;
    }
  }

  /**
   * 添加事件监听器
   */
  addEventListener(listener: VersionEventListener): void {
    const listeners = this.eventListeners.get(listener.eventType) || [];
    listeners.push(listener);
    this.eventListeners.set(listener.eventType, listeners);
  }

  /**
   * 移除事件监听器
   */
  removeEventListener(listener: VersionEventListener): void {
    const listeners = this.eventListeners.get(listener.eventType) || [];
    const index = listeners.indexOf(listener);
    if (index > -1) {
      listeners.splice(index, 1);
    }
  }

  /**
   * 触发事件
   */
  private async emitEvent(event: VersionEvent): Promise<void> {
    const listeners = this.eventListeners.get(event.type) || [];

    for (const listener of listeners) {
      try {
        await listener.handler(event);
      } catch (error) {
        console.error('Error in version event listener:', error);
      }
    }
  }

  /**
   * 验证创建请求
   */
  private validateCreateRequest(request: CreateVersionRequest): void {
    if (!request.schema) {
      throw new Error('Schema is required');
    }

    if (!Object.values(VersionType).includes(request.type)) {
      throw new Error('Invalid version type');
    }
  }

  /**
   * 生成版本号
   */
  private async generateVersionNumber(
    pageId: string,
    type: VersionType,
    baseVersionId?: string,
  ): Promise<string> {
    try {
      let baseVersion = '0.0.0';

      if (baseVersionId) {
        const base = await this.getVersion(baseVersionId);
        baseVersion = base.version;
      } else {
        // 获取最新版本
        const versions = await this.getVersions(pageId, {
          sortBy: 'version',
          sortOrder: 'desc',
          size: 1,
        });
        if (versions.versions.length > 0) {
          baseVersion = versions.versions[0].version;
        }
      }

      return this.incrementVersion(baseVersion, type);
    } catch (error) {
      console.error('Failed to generate version number:', error);
      return '1.0.0';
    }
  }

  /**
   * 递增版本号
   */
  private incrementVersion(version: string, type: VersionType): string {
    const parts = version.split('.').map(Number);
    const [major, minor, patch] = parts;

    switch (type) {
      case VersionType.MAJOR:
        return `${major + 1}.0.0`;
      case VersionType.MINOR:
        return `${major}.${minor + 1}.0`;
      case VersionType.PATCH:
        return `${major}.${minor}.${patch + 1}`;
      case VersionType.SNAPSHOT:
        return `${version}-SNAPSHOT-${Date.now()}`;
      default:
        return `${major}.${minor}.${patch + 1}`;
    }
  }

  /**
   * 生成版本ID
   */
  private generateVersionId(): string {
    return `version_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`;
  }

  /**
   * @experimental Not wired to auth. Returns a placeholder actor string.
   *   Data created via this manager will have polluted createdBy/updatedBy —
   *   follow-up task: have public API accept `actor` param explicitly.
   */
  private getCurrentUser(): string {
    if (!VersionManagerImpl._warnedGetCurrentUser) {
      VersionManagerImpl._warnedGetCurrentUser = true;
      // eslint-disable-next-line no-console
      console.warn('[VersionManager] getCurrentUser() is not wired to auth — returning placeholder actor. (warned once)');
    }
    return 'current_user';
  }

  /**
   * 检查版本锁定
   */
  private async checkVersionLock(versionId: string): Promise<void> {
    const lock = this.locks.get(versionId);
    if (lock && lock.expiresAt && lock.expiresAt > new Date()) {
      throw new Error(`Version is locked by ${lock.lockedBy}`);
    }
  }

  /**
   * @experimental Deep schema validation is TODO. Currently only checks that
   *   a non-empty `id` exists. Do not rely on this for correctness.
   *   See plan 2026-04-17-studio-v2-cleanup.md T4.
   */
  private async validateVersionSchema(schema: any): Promise<void> {
    if (!schema || !schema.id) {
      throw new Error('Invalid schema');
    }
    // Deep validation intentionally not implemented — see plan 2026-04-17-studio-v2-cleanup.
  }

  /**
   * @experimental Deep diff not implemented. Always throws to prevent the UI
   *   from silently displaying "no differences" when the diff is simply
   *   unimplemented. UI callers MUST catch and render an 'unavailable' state.
   *   See plan 2026-04-17-studio-v2-cleanup.md T4.
   */
  private calculateDifferences(_schemaA: any, _schemaB: any): VersionDifference[] {
    throw new Error(
      'VersionManager.calculateDifferences is not implemented. ' +
      'Callers must catch this error and render an unavailable/placeholder state.'
    );
  }

  /**
   * 锁定版本
   */
  async lockVersion(versionId: string, reason?: string, expiresAt?: Date): Promise<void> {
    const lock: VersionLock = {
      versionId,
      lockedBy: this.getCurrentUser(),
      lockedAt: new Date(),
      reason,
      expiresAt,
    };

    this.locks.set(versionId, lock);
  }

  /**
   * 解锁版本
   */
  async unlockVersion(versionId: string): Promise<void> {
    this.locks.delete(versionId);
  }

  /**
   * 获取同步状态
   */
  getSyncStatus(versionId: string): VersionSync | undefined {
    return this.syncStatus.get(versionId);
  }

  /**
   * 更新同步状态
   */
  updateSyncStatus(versionId: string, status: SyncStatus, error?: string): void {
    const sync: VersionSync = {
      versionId,
      status,
      lastSyncAt: new Date(),
      error,
    };

    this.syncStatus.set(versionId, sync);
  }
}

/**
 * 全局版本管理器实例
 */
let globalVersionManager: VersionManagerImpl | null = null;

/**
 * 获取版本管理器实例
 */
export function getVersionManager(config?: VersionConfig): VersionManagerImpl {
  if (!globalVersionManager) {
    globalVersionManager = new VersionManagerImpl(config || {});
  }
  return globalVersionManager;
}

/**
 * 设置版本管理器实例
 */
export function setVersionManager(manager: VersionManagerImpl): void {
  globalVersionManager = manager;
}
