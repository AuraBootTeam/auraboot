/**
 * 状态持久化管理器
 *
 * 负责状态的本地存储、云端同步和冲突解决
 */

import type {
  PageState,
  StateSnapshot,
  StateExportData,
} from '~/studio/services/state/PageStateManager';

/**
 * 持久化配置
 */
export interface PersistenceConfig {
  // 本地存储配置
  localStorage: {
    enabled: boolean;
    keyPrefix: string;
    compression: boolean;
    encryption: boolean;
  };

  // 云端同步配置
  cloudSync: {
    enabled: boolean;
    endpoint: string;
    apiKey?: string;
    syncInterval: number; // 毫秒
    retryAttempts: number;
    retryDelay: number;
  };

  // 冲突解决策略
  conflictResolution: 'local' | 'remote' | 'merge' | 'manual';

  // 自动备份配置
  autoBackup: {
    enabled: boolean;
    interval: number; // 毫秒
    maxBackups: number;
  };
}

/**
 * 同步状态
 */
export enum SyncStatus {
  Idle = 'idle',
  Syncing = 'syncing',
  Success = 'success',
  Error = 'error',
  Conflict = 'conflict',
}

/**
 * 同步结果
 */
export interface SyncResult {
  status: SyncStatus;
  timestamp: Date;
  localVersion: string;
  remoteVersion?: string;
  conflicts?: ConflictInfo[];
  error?: string;
}

/**
 * 冲突信息
 */
export interface ConflictInfo {
  path: string;
  localValue: any;
  remoteValue: any;
  timestamp: Date;
  type: 'update' | 'delete' | 'create';
}

/**
 * 备份信息
 */
export interface BackupInfo {
  id: string;
  timestamp: Date;
  version: string;
  size: number;
  description?: string;
  automatic: boolean;
}

/**
 * 状态持久化管理器
 */
export class StatePersistenceManager {
  private config: PersistenceConfig;
  private syncTimer?: NodeJS.Timeout;
  private backupTimer?: NodeJS.Timeout;
  private syncStatus: SyncStatus = SyncStatus.Idle;
  private lastSyncTime?: Date;
  private pendingChanges: Set<string> = new Set();
  private isOnline = navigator.onLine;

  constructor(config: Partial<PersistenceConfig> = {}) {
    this.config = {
      localStorage: {
        enabled: true,
        keyPrefix: 'auraboot_state_',
        compression: true,
        encryption: false,
        ...config.localStorage,
      },
      cloudSync: {
        enabled: false,
        endpoint: '/api/state/sync',
        syncInterval: 30000, // 30秒
        retryAttempts: 3,
        retryDelay: 1000,
        ...config.cloudSync,
      },
      conflictResolution: config.conflictResolution || 'merge',
      autoBackup: {
        enabled: true,
        interval: 300000, // 5分钟
        maxBackups: 10,
        ...config.autoBackup,
      },
    };

    this.setupEventListeners();
    this.startAutoSync();
    this.startAutoBackup();
  }

  /**
   * 保存状态到本地存储
   */
  async saveToLocal(pageId: string, state: PageState): Promise<boolean> {
    if (!this.config.localStorage.enabled) {
      return false;
    }

    try {
      const key = this.getLocalStorageKey(pageId);
      let data = JSON.stringify({
        state,
        timestamp: new Date().toISOString(),
        version: state.schema?.metadata?.version || '1.0.0',
      });

      // 压缩数据
      if (this.config.localStorage.compression) {
        data = await this.compressData(data);
      }

      // 加密数据
      if (this.config.localStorage.encryption) {
        data = await this.encryptData(data);
      }

      localStorage.setItem(key, data);
      return true;
    } catch (error) {
      console.error('Failed to save state to local storage:', error);
      return false;
    }
  }

  /**
   * 从本地存储加载状态
   */
  async loadFromLocal(pageId: string): Promise<PageState | null> {
    if (!this.config.localStorage.enabled) {
      return null;
    }

    try {
      const key = this.getLocalStorageKey(pageId);
      let data = localStorage.getItem(key);

      if (!data) {
        return null;
      }

      // 解密数据
      if (this.config.localStorage.encryption) {
        data = await this.decryptData(data);
      }

      // 解压数据
      if (this.config.localStorage.compression) {
        data = await this.decompressData(data);
      }

      const parsed = JSON.parse(data);

      // 恢复 Map 类型
      if (parsed.state.components && Array.isArray(parsed.state.components)) {
        parsed.state.components = new Map(parsed.state.components);
      }

      return parsed.state;
    } catch (error) {
      console.error('Failed to load state from local storage:', error);
      return null;
    }
  }

  /**
   * 同步状态到云端
   */
  async syncToCloud(pageId: string, state: PageState): Promise<SyncResult> {
    if (!this.config.cloudSync.enabled || !this.isOnline) {
      return {
        status: SyncStatus.Error,
        timestamp: new Date(),
        localVersion: state.metadata.version,
        error: this.isOnline ? 'Cloud sync disabled' : 'Offline',
      };
    }

    this.syncStatus = SyncStatus.Syncing;

    try {
      const response = await this.makeCloudRequest('post', `/pages/${pageId}/sync`, {
        state,
        version: state.metadata.version,
        timestamp: new Date().toISOString(),
      });

      if (response.conflicts && response.conflicts.length > 0) {
        return {
          status: SyncStatus.Conflict,
          timestamp: new Date(),
          localVersion: state.metadata.version,
          remoteVersion: response.remoteVersion,
          conflicts: response.conflicts,
        };
      }

      this.syncStatus = SyncStatus.Success;
      this.lastSyncTime = new Date();
      this.pendingChanges.clear();

      return {
        status: SyncStatus.Success,
        timestamp: new Date(),
        localVersion: state.metadata.version,
        remoteVersion: response.version,
      };
    } catch (error) {
      this.syncStatus = SyncStatus.Error;
      console.error('Failed to sync to cloud:', error);

      return {
        status: SyncStatus.Error,
        timestamp: new Date(),
        localVersion: state.metadata.version,
        error: error instanceof Error ? error.message : 'Unknown error',
      };
    }
  }

  /**
   * 从云端加载状态
   */
  async loadFromCloud(pageId: string): Promise<PageState | null> {
    if (!this.config.cloudSync.enabled || !this.isOnline) {
      return null;
    }

    try {
      const response = await this.makeCloudRequest('get', `/pages/${pageId}`);

      if (response.state) {
        // 恢复 Map 类型
        if (response.state.components && Array.isArray(response.state.components)) {
          response.state.components = new Map(response.state.components);
        }

        return response.state;
      }

      return null;
    } catch (error) {
      console.error('Failed to load state from cloud:', error);
      return null;
    }
  }

  /**
   * 解决冲突
   */
  async resolveConflicts(
    pageId: string,
    localState: PageState,
    remoteState: PageState,
    conflicts: ConflictInfo[],
  ): Promise<PageState> {
    switch (this.config.conflictResolution) {
      case 'local':
        return localState;

      case 'remote':
        return remoteState;

      case 'merge':
        return this.mergeStates(localState, remoteState, conflicts);

      case 'manual':
        // 触发手动解决冲突的事件
        return await this.requestManualResolution(localState, remoteState, conflicts);

      default:
        return localState;
    }
  }

  /**
   * 创建备份
   */
  async createBackup(pageId: string, state: PageState, description?: string): Promise<BackupInfo> {
    const backupId = `backup_${pageId}_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`;
    const backup: BackupInfo = {
      id: backupId,
      timestamp: new Date(),
      version: state.schema?.metadata?.version || '1.0.0',
      size: 0,
      description,
      automatic: !description,
    };

    try {
      const backupData = {
        ...backup,
        state,
      };

      const serialized = JSON.stringify(backupData);
      backup.size = new Blob([serialized]).size;

      // 保存到本地存储
      localStorage.setItem(`backup_${backupId}`, serialized);

      // 更新备份列表
      const backups = this.getBackupList(pageId);
      backups.push(backup);

      // 限制备份数量
      if (backups.length > this.config.autoBackup.maxBackups) {
        const oldBackups = backups.splice(0, backups.length - this.config.autoBackup.maxBackups);
        oldBackups.forEach((oldBackup) => {
          localStorage.removeItem(`backup_${oldBackup.id}`);
        });
      }

      this.saveBackupList(pageId, backups);

      return backup;
    } catch (error) {
      console.error('Failed to create backup:', error);
      throw error;
    }
  }

  /**
   * 恢复备份
   */
  async restoreBackup(backupId: string): Promise<PageState | null> {
    try {
      const backupData = localStorage.getItem(`backup_${backupId}`);
      if (!backupData) {
        return null;
      }

      const parsed = JSON.parse(backupData);

      // 恢复 Map 类型
      if (parsed.state.components && Array.isArray(parsed.state.components)) {
        parsed.state.components = new Map(parsed.state.components);
      }

      return parsed.state;
    } catch (error) {
      console.error('Failed to restore backup:', error);
      return null;
    }
  }

  /**
   * 获取备份列表
   */
  getBackupList(pageId: string): BackupInfo[] {
    try {
      const backupsData = localStorage.getItem(`backups_${pageId}`);
      return backupsData ? JSON.parse(backupsData) : [];
    } catch (error) {
      console.error('Failed to get backup list:', error);
      return [];
    }
  }

  /**
   * 删除备份
   */
  async deleteBackup(pageId: string, backupId: string): Promise<boolean> {
    try {
      localStorage.removeItem(`backup_${backupId}`);

      const backups = this.getBackupList(pageId);
      const filteredBackups = backups.filter((backup) => backup.id !== backupId);
      this.saveBackupList(pageId, filteredBackups);

      return true;
    } catch (error) {
      console.error('Failed to delete backup:', error);
      return false;
    }
  }

  /**
   * 获取同步状态
   */
  getSyncStatus(): {
    status: SyncStatus;
    lastSyncTime?: Date;
    pendingChanges: number;
    isOnline: boolean;
  } {
    return {
      status: this.syncStatus,
      lastSyncTime: this.lastSyncTime,
      pendingChanges: this.pendingChanges.size,
      isOnline: this.isOnline,
    };
  }

  /**
   * 标记变更待同步
   */
  markPendingChange(path: string): void {
    this.pendingChanges.add(path);
  }

  /**
   * 强制同步
   */
  async forcSync(pageId: string, state: PageState): Promise<SyncResult> {
    return this.syncToCloud(pageId, state);
  }

  /**
   * 清理过期数据
   */
  async cleanup(pageId: string, maxAge: number = 7 * 24 * 60 * 60 * 1000): Promise<void> {
    const cutoffTime = Date.now() - maxAge;

    // 清理过期备份
    const backups = this.getBackupList(pageId);
    const validBackups = backups.filter((backup) => {
      const isValid = backup.timestamp.getTime() > cutoffTime;
      if (!isValid) {
        localStorage.removeItem(`backup_${backup.id}`);
      }
      return isValid;
    });

    this.saveBackupList(pageId, validBackups);
  }

  /**
   * 销毁管理器
   */
  destroy(): void {
    if (this.syncTimer) {
      clearInterval(this.syncTimer);
    }

    if (this.backupTimer) {
      clearInterval(this.backupTimer);
    }

    window.removeEventListener('online', this.handleOnline);
    window.removeEventListener('offline', this.handleOffline);
  }

  // 私有方法

  private setupEventListeners(): void {
    window.addEventListener('online', this.handleOnline);
    window.addEventListener('offline', this.handleOffline);
  }

  private handleOnline = (): void => {
    this.isOnline = true;
  };

  private handleOffline = (): void => {
    this.isOnline = false;
  };

  private startAutoSync(): void {
    if (!this.config.cloudSync.enabled) {
      return;
    }

    this.syncTimer = setInterval(() => {
      if (this.pendingChanges.size > 0 && this.isOnline) {
        // TODO: Trigger sync event for external handling
      }
    }, this.config.cloudSync.syncInterval);
  }

  private startAutoBackup(): void {
    if (!this.config.autoBackup.enabled) {
      return;
    }

    this.backupTimer = setInterval(() => {
      // TODO: Trigger auto backup event for external handling
    }, this.config.autoBackup.interval);
  }

  private getLocalStorageKey(pageId: string): string {
    return `${this.config.localStorage.keyPrefix}${pageId}`;
  }

  private async makeCloudRequest(method: string, path: string, data?: any): Promise<any> {
    const url = `${this.config.cloudSync.endpoint}${path}`;
    const options: RequestInit = {
      method,
      headers: {
        'Content-Type': 'application/json',
        ...(this.config.cloudSync.apiKey && {
          Authorization: `Bearer ${this.config.cloudSync.apiKey}`,
        }),
      },
    };

    if (data) {
      options.body = JSON.stringify(data);
    }

    let lastError: Error;

    for (let attempt = 0; attempt < this.config.cloudSync.retryAttempts; attempt++) {
      try {
        const response = await fetch(url, options);

        if (!response.ok) {
          throw new Error(`HTTP ${response.status}: ${response.statusText}`);
        }

        return await response.json();
      } catch (error) {
        lastError = error instanceof Error ? error : new Error('Unknown error');

        if (attempt < this.config.cloudSync.retryAttempts - 1) {
          await new Promise((resolve) =>
            setTimeout(resolve, this.config.cloudSync.retryDelay * (attempt + 1)),
          );
        }
      }
    }

    throw lastError!;
  }

  private mergeStates(
    localState: PageState,
    remoteState: PageState,
    conflicts: ConflictInfo[],
  ): PageState {
    // 简单的合并策略：优先使用最新的时间戳
    const mergedState = { ...localState };

    conflicts.forEach((conflict) => {
      // 根据时间戳决定使用哪个值
      if (remoteState.metadata.updatedAt > localState.metadata.updatedAt) {
        this.setNestedValue(mergedState, conflict.path, conflict.remoteValue);
      }
    });

    // 更新元数据
    mergedState.metadata = {
      ...mergedState.metadata,
      updatedAt: new Date(),
      updatedBy: 'merge',
    };

    return mergedState;
  }

  private async requestManualResolution(
    localState: PageState,
    remoteState: PageState,
    conflicts: ConflictInfo[],
  ): Promise<PageState> {
    // 触发手动解决冲突的事件
    return new Promise((resolve) => {
      const event = new CustomEvent('stateConflictResolution', {
        detail: {
          localState,
          remoteState,
          conflicts,
          resolve,
        },
      });

      window.dispatchEvent(event);
    });
  }

  private setNestedValue(obj: any, path: string, value: any): void {
    const keys = path.split('.');
    const lastKey = keys.pop()!;

    const target = keys.reduce((current, key) => {
      if (!current[key] || typeof current[key] !== 'object') {
        current[key] = {};
      }
      return current[key];
    }, obj);

    target[lastKey] = value;
  }

  private saveBackupList(pageId: string, backups: BackupInfo[]): void {
    localStorage.setItem(`backups_${pageId}`, JSON.stringify(backups));
  }

  private async compressData(data: string): Promise<string> {
    try {
      // 使用 encodeURIComponent 处理 Unicode 字符，然后进行 Base64 编码
      const encodedData = encodeURIComponent(data);
      return btoa(encodedData);
    } catch (error) {
      console.error('Failed to compress data:', error);
      // 降级方案：直接返回原始数据
      return data;
    }
  }

  private async decompressData(data: string): Promise<string> {
    try {
      // 先进行 Base64 解码，然后使用 decodeURIComponent 处理 Unicode 字符
      const decodedData = atob(data);
      return decodeURIComponent(decodedData);
    } catch (error) {
      console.error('Failed to decompress data:', error);
      // 降级方案：假设数据未压缩，直接返回
      return data;
    }
  }

  private async encryptData(data: string): Promise<string> {
    try {
      // 使用 encodeURIComponent 处理 Unicode 字符，然后进行 Base64 编码
      // 注意：这只是简单的编码，实际项目中应该使用更安全的加密算法
      const encodedData = encodeURIComponent(data);
      return btoa(encodedData);
    } catch (error) {
      console.error('Failed to encrypt data:', error);
      // 降级方案：直接返回原始数据
      return data;
    }
  }

  private async decryptData(data: string): Promise<string> {
    try {
      // 先进行 Base64 解码，然后使用 decodeURIComponent 处理 Unicode 字符
      const decodedData = atob(data);
      return decodeURIComponent(decodedData);
    } catch (error) {
      console.error('Failed to decrypt data:', error);
      // 降级方案：假设数据未加密，直接返回
      return data;
    }
  }
}

// 全局持久化管理器实例
let globalPersistenceManager: StatePersistenceManager | null = null;

/**
 * 获取全局持久化管理器
 */
export function getPersistenceManager(): StatePersistenceManager | null {
  return globalPersistenceManager;
}

/**
 * 设置全局持久化管理器
 */
export function setPersistenceManager(manager: StatePersistenceManager): void {
  globalPersistenceManager = manager;
}

/**
 * 创建持久化管理器
 */
export function createPersistenceManager(
  config?: Partial<PersistenceConfig>,
): StatePersistenceManager {
  return new StatePersistenceManager(config);
}
