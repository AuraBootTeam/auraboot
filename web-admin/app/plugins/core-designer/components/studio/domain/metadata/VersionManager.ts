/**
 * Version manager implementation
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
 * Version manager implementation class.
 *
 * All mutation methods that record authorship now require an explicit `actor`
 * argument (the caller's email/username from auth context). The old
 * `getCurrentUser()` silent-placeholder path has been removed — see GAP-221.
 */
export class VersionManagerImpl implements VersionManager {
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
   * Initialize event type listener maps
   */
  private initializeEventTypes(): void {
    Object.values(VersionEventType).forEach((eventType) => {
      this.eventListeners.set(eventType, []);
    });
  }

  /**
   * Create a new version.
   *
   * @param actor - caller identity (email or username) from auth context
   */
  async createVersion(pageId: string, request: CreateVersionRequest, actor: string): Promise<Version> {
    try {
      this.validateCreateRequest(request);

      const version = await this.generateVersionNumber(pageId, request.type, request.baseVersionId);

      const newVersion: Version = {
        id: this.generateVersionId(),
        version,
        status: VersionStatus.draft,
        type: request.type,
        schema: request.schema,
        createdAt: new Date(),
        updatedAt: new Date(),
        createdBy: actor,
        updatedBy: actor,
        description: request.description,
        changelog: request.changelog,
        tags: request.tags || [],
        parentVersionId: request.baseVersionId,
      };

      if (this.storage) {
        await this.storage.saveVersion(newVersion);
      }

      const response = await this.apiService.post<Version>(`/pages/${pageId}/versions`, newVersion);
      const savedVersion = response.data;

      await this.emitEvent({
        type: VersionEventType.VERSION_CREATED,
        pageId,
        versionId: savedVersion.id,
        version: savedVersion,
        timestamp: new Date(),
        operator: actor,
      });

      return savedVersion;
    } catch (error) {
      console.error('Failed to create version:', error);
      throw error;
    }
  }

  /**
   * Update a version.
   *
   * @param actor - caller identity from auth context
   */
  async updateVersion(request: UpdateVersionRequest, actor: string): Promise<Version> {
    try {
      await this.checkVersionLock(request.versionId);

      const existingVersion = await this.getVersion(request.versionId);

      if (existingVersion.status === VersionStatus.published) {
        throw new Error('Cannot update published version');
      }

      const updatedVersion: Version = {
        ...existingVersion,
        schema: request.schema || existingVersion.schema,
        description: request.description || existingVersion.description,
        changelog: request.changelog || existingVersion.changelog,
        tags: request.tags || existingVersion.tags,
        updatedAt: new Date(),
        updatedBy: actor,
      };

      if (this.storage) {
        await this.storage.saveVersion(updatedVersion);
      }

      const response = await this.apiService.put<Version>(
        `/versions/${request.versionId}`,
        updatedVersion,
      );
      const savedVersion = response.data;

      await this.emitEvent({
        type: VersionEventType.VERSION_UPDATED,
        pageId: savedVersion.schema.id,
        versionId: savedVersion.id,
        version: savedVersion,
        timestamp: new Date(),
        operator: actor,
      });

      return savedVersion;
    } catch (error) {
      console.error('Failed to update version:', error);
      throw error;
    }
  }

  /**
   * Delete a version.
   *
   * @param actor - caller identity from auth context
   */
  async deleteVersion(pageId: string, versionId: string, actor: string): Promise<void> {
    try {
      await this.checkVersionLock(versionId);

      const version = await this.getVersion(versionId);

      if (version.status === VersionStatus.published) {
        throw new Error('Cannot delete published version');
      }

      if (this.storage) {
        await this.storage.deleteVersion(versionId);
      }

      await this.apiService.delete(`/versions/${versionId}`);

      await this.emitEvent({
        type: VersionEventType.VERSION_DELETED,
        pageId: version.schema.id,
        versionId: version.id,
        version,
        timestamp: new Date(),
        operator: actor,
      });
    } catch (error) {
      console.error('Failed to delete version:', error);
      throw error;
    }
  }

  /**
   * Get version details
   */
  async getVersion(versionId: string): Promise<Version> {
    try {
      if (this.storage && (await this.storage.versionExists(versionId))) {
        return await this.storage.loadVersion(versionId);
      }

      const response = await this.apiService.get<Version>(`/versions/${versionId}`);
      return response.data;
    } catch (error) {
      console.error('Failed to get version:', error);
      throw error;
    }
  }

  /**
   * Get version list
   */
  async getVersions(pageId: string, params?: VersionQueryParams): Promise<VersionListResponse> {
    try {
      const queryParams = new URLSearchParams();
      if (params) {
        Object.entries(params).forEach(([key, value]) => {
          if (value !== undefined && value !== null) {
            queryParams.append(key, String(value));
          }
        });
      }

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
   * Get current version
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
   * Get published version
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
   * Publish a version.
   *
   * @param actor - caller identity from auth context
   */
  async publishVersion(
    pageId: string,
    versionId: string,
    request: PublishVersionRequest,
    actor: string,
  ): Promise<Version> {
    try {
      await this.checkVersionLock(request.versionId);

      const version = await this.getVersion(versionId);

      if (version.status === VersionStatus.published && !request.force) {
        throw new Error('Version is already published');
      }

      await this.validateVersionSchema(version.schema);

      // publishedBy / updatedBy are set server-side; the local object is not used
      // for the final response — we just need the API call result.
      const response = await this.apiService.post<Version>(`/versions/${versionId}/publish`, {
        description: request.description,
        force: request.force,
      });
      const result = response.data;

      await this.emitEvent({
        type: VersionEventType.VERSION_PUBLISHED,
        pageId: result.schema.id,
        versionId: result.id,
        version: result,
        timestamp: new Date(),
        operator: actor,
      });

      return result;
    } catch (error) {
      console.error('Failed to publish version:', error);
      throw error;
    }
  }

  /**
   * Unpublish a version.
   *
   * @param actor - caller identity from auth context
   */
  async unpublishVersion(versionId: string, actor: string): Promise<Version> {
    try {
      await this.checkVersionLock(versionId);

      const response = await this.apiService.post<Version>(`/versions/${versionId}/unpublish`);
      const result = response.data;

      await this.emitEvent({
        type: VersionEventType.VERSION_UNPUBLISHED,
        pageId: result.schema.id,
        versionId: result.id,
        version: result,
        timestamp: new Date(),
        operator: actor,
      });

      return result;
    } catch (error) {
      console.error('Failed to unpublish version:', error);
      throw error;
    }
  }

  /**
   * Rollback to a previous version.
   *
   * @param actor - caller identity from auth context
   */
  async rollbackVersion(pageId: string, request: RollbackVersionRequest, actor: string): Promise<Version> {
    try {
      const targetVersion = await this.getVersion(request.targetVersionId);

      if (targetVersion.status !== VersionStatus.published) {
        throw new Error('Can only rollback to published version');
      }

      let result: Version;

      if (request.createNewVersion) {
        result = await this.createVersion(pageId, {
          schema: targetVersion.schema,
          type: VersionType.PATCH,
          description: request.description || `Rollback to version ${targetVersion.version}`,
          baseVersionId: request.targetVersionId,
        }, actor);
      } else {
        const response = await this.apiService.post<Version>(`/pages/${pageId}/rollback`, {
          targetVersionId: request.targetVersionId,
          description: request.description,
        });
        result = response.data;
      }

      await this.emitEvent({
        type: VersionEventType.VERSION_ROLLED_BACK,
        pageId,
        versionId: result.id,
        version: result,
        timestamp: new Date(),
        operator: actor,
        data: { targetVersionId: request.targetVersionId },
      });

      return result;
    } catch (error) {
      console.error('Failed to rollback version:', error);
      throw error;
    }
  }

  /**
   * Compare two versions (diff is not yet implemented).
   *
   * @experimental calculateDifferences always throws — callers must catch and
   *   render an unavailable state. See plan 2026-04-17-studio-v2-cleanup.md T4.
   */
  async compareVersions(
    pageId: string,
    versionAId: string,
    versionBId: string,
  ): Promise<VersionDiff> {
    try {
      const [versionA, versionB] = await Promise.all([
        this.getVersion(versionAId),
        this.getVersion(versionBId),
      ]);

      const differences = this.calculateDifferences(versionA.schema, versionB.schema);

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
   * Duplicate a version.
   *
   * @param actor - caller identity from auth context
   */
  async duplicateVersion(versionId: string, actor: string, description?: string): Promise<Version> {
    try {
      const sourceVersion = await this.getVersion(versionId);

      return await this.createVersion(sourceVersion.schema.id, {
        schema: sourceVersion.schema,
        type: VersionType.MINOR,
        description: description || `Copy of version ${sourceVersion.version}`,
        baseVersionId: versionId,
      }, actor);
    } catch (error) {
      console.error('Failed to duplicate version:', error);
      throw error;
    }
  }

  /**
   * Archive a version.
   *
   * @param actor - caller identity from auth context
   */
  async archiveVersion(versionId: string, actor: string): Promise<Version> {
    try {
      const response = await this.apiService.post<Version>(`/versions/${versionId}/archive`);
      const result = response.data;

      await this.emitEvent({
        type: VersionEventType.VERSION_ARCHIVED,
        pageId: result.schema.id,
        versionId: result.id,
        version: result,
        timestamp: new Date(),
        operator: actor,
      });

      return result;
    } catch (error) {
      console.error('Failed to archive version:', error);
      throw error;
    }
  }

  /**
   * Restore an archived version.
   *
   * @param actor - caller identity from auth context
   */
  async restoreVersion(versionId: string, actor: string): Promise<Version> {
    try {
      const response = await this.apiService.post<Version>(`/versions/${versionId}/restore`);
      const result = response.data;

      await this.emitEvent({
        type: VersionEventType.VERSION_RESTORED,
        pageId: result.schema.id,
        versionId: result.id,
        version: result,
        timestamp: new Date(),
        operator: actor,
      });

      return result;
    } catch (error) {
      console.error('Failed to restore version:', error);
      throw error;
    }
  }

  /**
   * Add an event listener
   */
  addEventListener(listener: VersionEventListener): void {
    const listeners = this.eventListeners.get(listener.eventType) || [];
    listeners.push(listener);
    this.eventListeners.set(listener.eventType, listeners);
  }

  /**
   * Remove an event listener
   */
  removeEventListener(listener: VersionEventListener): void {
    const listeners = this.eventListeners.get(listener.eventType) || [];
    const index = listeners.indexOf(listener);
    if (index > -1) {
      listeners.splice(index, 1);
    }
  }

  /**
   * Emit an event to all registered listeners
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
   * Validate create request
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
   * Generate next version number
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
   * Increment a semver string according to version type
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
   * Generate a unique version ID
   */
  private generateVersionId(): string {
    return `version_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`;
  }

  /**
   * Check whether a version is locked
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
   * Lock a version.
   *
   * @param actor - caller identity from auth context
   */
  async lockVersion(versionId: string, actor: string, reason?: string, expiresAt?: Date): Promise<void> {
    const lock: VersionLock = {
      versionId,
      lockedBy: actor,
      lockedAt: new Date(),
      reason,
      expiresAt,
    };

    this.locks.set(versionId, lock);
  }

  /**
   * Unlock a version
   */
  async unlockVersion(versionId: string): Promise<void> {
    this.locks.delete(versionId);
  }

  /**
   * Get sync status for a version
   */
  getSyncStatus(versionId: string): VersionSync | undefined {
    return this.syncStatus.get(versionId);
  }

  /**
   * Update sync status for a version
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
 * Global version manager singleton
 */
let globalVersionManager: VersionManagerImpl | null = null;

/**
 * Get (or lazily create) the global version manager instance
 */
export function getVersionManager(config?: VersionConfig): VersionManagerImpl {
  if (!globalVersionManager) {
    globalVersionManager = new VersionManagerImpl(config || {});
  }
  return globalVersionManager;
}

/**
 * Replace the global version manager instance (for testing)
 */
export function setVersionManager(manager: VersionManagerImpl): void {
  globalVersionManager = manager;
}
