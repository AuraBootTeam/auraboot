/**
 * PageSchema 版本管理器
 *
 * 负责管理页面设计器的 Schema 版本，包括：
 * - 草稿保存和自动保存
 * - 版本发布和回滚
 * - 与后端 API 的同步
 * - 本地缓存和离线支持
 */

import type { PageSchema } from '~/plugins/core-designer/components/studio/workbench/canvas/types';
import type {
  Version,
  VersionManager,
  CreateVersionRequest,
  UpdateVersionRequest,
  PublishVersionRequest,
  RollbackVersionRequest,
  VersionConfig,
} from '~/plugins/core-designer/components/studio/domain/metadata/types';
import { VersionStatus, VersionType } from '~/plugins/core-designer/components/studio/domain/metadata/types';
import { getVersionManager } from '~/plugins/core-designer/components/studio/domain/metadata/VersionManager';

/**
 * PageSchema 版本数据
 */
export interface PageSchemaVersion extends Version {
  schema: PageSchema;
  previewUrl?: string;
  thumbnailUrl?: string;
  dependencies?: string[];
  compatibility?: {
    minVersion: string;
    maxVersion: string;
  };
}

/**
 * PageSchema 版本创建请求
 */
export interface CreatePageSchemaVersionRequest extends Omit<CreateVersionRequest, 'schema'> {
  schema: PageSchema;
  autoSave?: boolean;
  generatePreview?: boolean;
  generateThumbnail?: boolean;
}

/**
 * PageSchema 版本更新请求
 */
export interface UpdatePageSchemaVersionRequest extends Omit<UpdateVersionRequest, 'schema'> {
  schema?: PageSchema;
  generatePreview?: boolean;
  generateThumbnail?: boolean;
}

/**
 * PageSchema 版本管理配置
 */
export interface PageSchemaVersionConfig extends VersionConfig {
  // 自动保存配置
  autoSave: boolean;
  autoSaveInterval: number;
  autoSaveDebounce: number;

  // 锁配置
  lockTimeout?: number;
  enableSync?: boolean;
  syncInterval?: number;
  storageType?: 'local' | 'session' | 'indexeddb' | 'memory';

  // 预览配置
  generatePreview: boolean;
  previewWidth: number;
  previewHeight: number;

  // 缩略图配置
  generateThumbnail: boolean;
  thumbnailWidth: number;
  thumbnailHeight: number;

  // API 配置
  apiEndpoint: string;
  apiTimeout: number;

  // 缓存配置
  enableCache: boolean;
  cacheSize: number;
  cacheTTL: number;
}

/**
 * PageSchema 版本管理器
 */
export class PageSchemaVersionManager {
  private versionManager: VersionManager;
  private config: PageSchemaVersionConfig;
  private autoSaveTimer: NodeJS.Timeout | null = null;
  private schemaCache = new Map<string, PageSchemaVersion>();
  private pendingChanges = new Map<string, PageSchema>();

  constructor(config: Partial<PageSchemaVersionConfig> = {}) {
    this.config = {
      // 默认配置
      autoSave: true,
      autoSaveInterval: 30000, // 30秒
      autoSaveDebounce: 2000, // 2秒防抖
      maxVersions: 50,
      lockTimeout: 300000, // 5分钟
      enableSync: true,
      syncInterval: 60000, // 1分钟
      storageType: 'indexeddb',

      // 预览配置
      generatePreview: true,
      previewWidth: 1200,
      previewHeight: 800,

      // 缩略图配置
      generateThumbnail: true,
      thumbnailWidth: 300,
      thumbnailHeight: 200,

      // API 配置
      apiEndpoint: '/api/page-schemas',
      apiTimeout: 10000,

      // 缓存配置
      enableCache: true,
      cacheSize: 100,
      cacheTTL: 3600000, // 1小时

      ...config,
    };

    this.versionManager = getVersionManager();
    this.initializeAutoSave();
  }

  /**
   * 初始化自动保存
   */
  private initializeAutoSave(): void {
    if (this.config.autoSave) {
      this.autoSaveTimer = setInterval(() => {
        this.processPendingChanges();
      }, this.config.autoSaveInterval);
    }
  }

  /**
   * 处理待保存的变更
   */
  private async processPendingChanges(): Promise<void> {
    if (this.pendingChanges.size === 0) return;

    const changes = Array.from(this.pendingChanges.entries());
    this.pendingChanges.clear();

    for (const [pageId, schema] of changes) {
      try {
        await this.saveDraft(pageId, schema);
      } catch (error) {
        console.error(`Auto-save failed for page ${pageId}:`, error);
        // 重新加入待保存队列
        this.pendingChanges.set(pageId, schema);
      }
    }
  }

  /**
   * 创建新的 PageSchema 版本
   */
  async createVersion(
    pageId: string,
    request: CreatePageSchemaVersionRequest,
  ): Promise<PageSchemaVersion> {
    try {
      // 验证 Schema
      this.validateSchema(request.schema);

      // 生成预览和缩略图
      const previewUrl =
        request.generatePreview !== false && this.config.generatePreview
          ? await this.generatePreview(request.schema)
          : undefined;

      const thumbnailUrl =
        request.generateThumbnail !== false && this.config.generateThumbnail
          ? await this.generateThumbnail(request.schema)
          : undefined;

      // 创建版本
      const versionRequest: CreateVersionRequest = {
        ...request,
        schema: request.schema,
      };

      const version = await this.versionManager.createVersion(pageId, versionRequest);

      const pageSchemaVersion: PageSchemaVersion = {
        ...version,
        schema: request.schema,
        previewUrl,
        thumbnailUrl,
        dependencies: this.extractDependencies(request.schema),
        compatibility: this.checkCompatibility(request.schema),
      };

      // 缓存版本
      if (this.config.enableCache) {
        this.schemaCache.set(version.id, pageSchemaVersion);
      }

      // 如果是自动保存，清除待保存状态
      if (request.autoSave) {
        this.pendingChanges.delete(pageId);
      }

      return pageSchemaVersion;
    } catch (error) {
      console.error('Failed to create PageSchema version:', error);
      throw error;
    }
  }

  /**
   * 更新 PageSchema 版本
   */
  async updateVersion(request: UpdatePageSchemaVersionRequest): Promise<PageSchemaVersion> {
    try {
      // 验证 Schema
      if (request.schema) {
        this.validateSchema(request.schema);
      }

      // 获取当前版本
      const currentVersion = await this.versionManager.getVersion(request.versionId);
      const currentPageVersion = currentVersion as PageSchemaVersion;

      // 生成预览和缩略图
      const schema = request.schema || currentPageVersion.schema;
      const previewUrl =
        request.generatePreview !== false && this.config.generatePreview
          ? await this.generatePreview(schema)
          : currentPageVersion.previewUrl;

      const thumbnailUrl =
        request.generateThumbnail !== false && this.config.generateThumbnail
          ? await this.generateThumbnail(schema)
          : currentPageVersion.thumbnailUrl;

      // 更新版本
      const versionRequest: UpdateVersionRequest = {
        ...request,
        schema: request.schema,
      };

      const version = await this.versionManager.updateVersion(versionRequest);

      const pageSchemaVersion: PageSchemaVersion = {
        ...version,
        schema: request.schema || currentPageVersion.schema,
        previewUrl,
        thumbnailUrl,
        dependencies: request.schema
          ? this.extractDependencies(request.schema)
          : currentPageVersion.dependencies,
        compatibility: request.schema
          ? this.checkCompatibility(request.schema)
          : currentPageVersion.compatibility,
      };

      // 更新缓存
      if (this.config.enableCache) {
        this.schemaCache.set(version.id, pageSchemaVersion);
      }

      return pageSchemaVersion;
    } catch (error) {
      console.error('Failed to update PageSchema version:', error);
      throw error;
    }
  }

  /**
   * 保存草稿
   */
  async saveDraft(
    pageId: string,
    schema: PageSchema,
    description?: string,
  ): Promise<PageSchemaVersion> {
    try {
      // 查找现有草稿
      const existingDraft = await this.getCurrentDraft(pageId);

      if (existingDraft) {
        // 更新现有草稿
        return await this.updateVersion({
          versionId: existingDraft.id,
          schema,
          description: description || `自动保存 - ${new Date().toLocaleString()}`,
        });
      } else {
        // 创建新草稿
        return await this.createVersion(pageId, {
          schema,
          description: description || '草稿版本',
          type: VersionType.PATCH,
          autoSave: true,
        });
      }
    } catch (error) {
      console.error('Failed to save draft:', error);
      throw error;
    }
  }

  /**
   * 发布版本
   */
  async publishVersion(pageId: string, request: PublishVersionRequest): Promise<PageSchemaVersion> {
    try {
      const version = await this.versionManager.publishVersion(pageId, request.versionId, request);
      const pageSchemaVersion = version as PageSchemaVersion;

      // 更新缓存
      if (this.config.enableCache) {
        this.schemaCache.set(version.id, pageSchemaVersion);
      }

      // 同步到后端
      if (this.config.enableSync) {
        await this.syncToBackend(pageSchemaVersion);
      }

      return pageSchemaVersion;
    } catch (error) {
      console.error('Failed to publish version:', error);
      throw error;
    }
  }

  /**
   * 回滚版本
   */
  async rollbackVersion(
    pageId: string,
    request: RollbackVersionRequest,
  ): Promise<PageSchemaVersion> {
    try {
      const version = await this.versionManager.rollbackVersion(pageId, request);
      const pageSchemaVersion = version as PageSchemaVersion;

      // 更新缓存
      if (this.config.enableCache) {
        this.schemaCache.set(version.id, pageSchemaVersion);
      }

      return pageSchemaVersion;
    } catch (error) {
      console.error('Failed to rollback version:', error);
      throw error;
    }
  }

  /**
   * 获取当前草稿
   */
  async getCurrentDraft(pageId: string): Promise<PageSchemaVersion | null> {
    try {
      const versions = await this.versionManager.getVersions(pageId, {
        status: VersionStatus.draft,
        size: 1,
        page: 1,
      });

      return versions.versions.length > 0 ? (versions.versions[0] as PageSchemaVersion) : null;
    } catch (error) {
      console.error('Failed to get current draft:', error);
      return null;
    }
  }

  /**
   * 获取已发布版本
   */
  async getPublishedVersion(pageId: string): Promise<PageSchemaVersion | null> {
    try {
      return (await this.versionManager.getPublishedVersion(pageId)) as PageSchemaVersion | null;
    } catch (error) {
      console.error('Failed to get published version:', error);
      return null;
    }
  }

  /**
   * 获取版本详情
   */
  async getVersion(versionId: string): Promise<PageSchemaVersion | null> {
    try {
      // 先从缓存获取
      if (this.config.enableCache && this.schemaCache.has(versionId)) {
        return this.schemaCache.get(versionId)!;
      }

      const version = await this.versionManager.getVersion(versionId);
      const pageSchemaVersion = version as PageSchemaVersion;

      // 缓存版本
      if (this.config.enableCache) {
        this.schemaCache.set(versionId, pageSchemaVersion);
      }

      return pageSchemaVersion;
    } catch (error) {
      console.error('Failed to get version:', error);
      return null;
    }
  }

  /**
   * 获取版本列表
   */
  async getVersions(
    pageId: string,
    options?: any,
  ): Promise<{ versions: PageSchemaVersion[]; total: number }> {
    try {
      const result = await this.versionManager.getVersions(pageId, options);
      return {
        versions: result.versions as PageSchemaVersion[],
        total: result.total,
      };
    } catch (error) {
      console.error('Failed to get versions:', error);
      throw error;
    }
  }

  /**
   * 标记 Schema 变更（用于自动保存）
   */
  markSchemaChanged(pageId: string, schema: PageSchema): void {
    if (this.config.autoSave) {
      this.pendingChanges.set(pageId, schema);
    }
  }

  /**
   * 立即保存所有待保存的变更
   */
  async saveAllPendingChanges(): Promise<void> {
    await this.processPendingChanges();
  }

  /**
   * 验证 Schema
   */
  private validateSchema(schema: PageSchema): void {
    if (!schema) {
      throw new Error('Schema cannot be empty');
    }

    if (!schema.id) {
      throw new Error('Schema must have an id');
    }

    if (!schema.version) {
      throw new Error('Schema must have a version');
    }

    // 验证组件引用
    if (schema.components) {
      for (const component of schema.components) {
        if (!component.id || !component.type) {
          throw new Error('All components must have id and type');
        }
      }
    }

    // 验证布局配置
    if (schema.layout && schema.layout.type) {
      // 根据布局类型验证配置
      switch (schema.layout.type as string) {
        case 'grid':
          if (!schema.layout.columns) {
            throw new Error('Grid layout must have columns configuration');
          }
          break;
        case 'flex':
          // Flex layout config check if needed, type definition suggests simple properties
          break;
      }
    }
  }

  /**
   * 生成预览图
   */
  private async generatePreview(schema: PageSchema): Promise<string> {
    try {
      // 这里应该调用预览生成服务
      // 暂时返回占位符
      return `${this.config.apiEndpoint}/preview/${schema.id}?width=${this.config.previewWidth}&height=${this.config.previewHeight}`;
    } catch (error) {
      console.error('Failed to generate preview:', error);
      return '';
    }
  }

  /**
   * 生成缩略图
   */
  private async generateThumbnail(schema: PageSchema): Promise<string> {
    try {
      // 这里应该调用缩略图生成服务
      // 暂时返回占位符
      return `${this.config.apiEndpoint}/thumbnail/${schema.id}?width=${this.config.thumbnailWidth}&height=${this.config.thumbnailHeight}`;
    } catch (error) {
      console.error('Failed to generate thumbnail:', error);
      return '';
    }
  }

  /**
   * 提取依赖项
   */
  private extractDependencies(schema: PageSchema): string[] {
    const dependencies: Set<string> = new Set();

    // 提取组件依赖
    if (schema.components) {
      for (const component of schema.components) {
        dependencies.add(component.type);

        // 提取子组件依赖
        if (component.children) {
          for (const child of component.children) {
            dependencies.add(child.type);
          }
        }
      }
    }

    // 提取样式依赖
    if (schema.styles) {
      for (const style of schema.styles) {
        if (style.imports) {
          for (const importPath of style.imports) {
            dependencies.add(importPath);
          }
        }
      }
    }

    return Array.from(dependencies);
  }

  /**
   * 检查兼容性
   */
  private checkCompatibility(schema: PageSchema): { minVersion: string; maxVersion: string } {
    // 根据 Schema 特性确定兼容性
    let minVersion = '1.0.0';
    let maxVersion = '999.999.999';

    // 检查是否使用了新特性
    if (schema.actions && schema.actions.length > 0) {
      minVersion = '2.0.0'; // 动作系统需要 2.0.0+
    }

    if ((schema.layout?.type as string) === 'flex') {
      minVersion = '1.5.0'; // Flex 布局需要 1.5.0+
    }

    return { minVersion, maxVersion };
  }

  /**
   * 同步到后端
   */
  private async syncToBackend(version: PageSchemaVersion): Promise<void> {
    try {
      // Ensure pageId is available or fallback
      const pageId = (version as any).pageId || version.schema.id;

      const response = await fetch(`${this.config.apiEndpoint}/${pageId}/versions`, {
        method: 'post',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          versionId: version.id,
          schema: version.schema,
          version: version.version,
          status: version.status,
          type: version.type,
          description: version.description,
          previewUrl: version.previewUrl,
          thumbnailUrl: version.thumbnailUrl,
          dependencies: version.dependencies,
          compatibility: version.compatibility,
        }),
        signal: AbortSignal.timeout(this.config.apiTimeout),
      });

      if (!response.ok) {
        throw new Error(`Sync failed: ${response.status} ${response.statusText}`);
      }
    } catch (error) {
      console.error('Failed to sync to backend:', error);
      // Suppress sync error to avoid breaking main flow if sync is optional
      if (!this.config.enableSync) throw error;
    }
  }

  /**
   * 清理资源
   */
  dispose(): void {
    if (this.autoSaveTimer) {
      clearInterval(this.autoSaveTimer);
      this.autoSaveTimer = null;
    }

    this.schemaCache.clear();
    this.pendingChanges.clear();
  }
}

// 全局实例
let globalPageSchemaVersionManager: PageSchemaVersionManager | null = null;

/**
 * 获取全局 PageSchema 版本管理器实例
 */
export function getPageSchemaVersionManager(
  config?: Partial<PageSchemaVersionConfig>,
): PageSchemaVersionManager {
  if (!globalPageSchemaVersionManager) {
    globalPageSchemaVersionManager = new PageSchemaVersionManager(config);
  }
  return globalPageSchemaVersionManager;
}

/**
 * 创建新的 PageSchema 版本管理器实例
 */
export function createPageSchemaVersionManager(
  config?: Partial<PageSchemaVersionConfig>,
): PageSchemaVersionManager {
  return new PageSchemaVersionManager(config);
}
