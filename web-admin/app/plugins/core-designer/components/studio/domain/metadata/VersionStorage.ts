/**
 * 版本存储实现
 */

import type {
  Version,
  VersionStorage,
  VersionQueryParams,
  VersionListResponse,
} from '~/plugins/core-designer/components/studio/domain/metadata/types';
import { VersionStatus } from '~/plugins/core-designer/components/studio/domain/metadata/types';

/**
 * 本地存储版本管理器
 */
export class LocalVersionStorage implements VersionStorage {
  private storageKey = 'designer_versions';
  private indexKey = 'designer_version_index';

  /**
   * 保存版本
   */
  async saveVersion(version: Version): Promise<void> {
    try {
      // 获取现有版本数据
      const versions = this.loadVersionsFromStorage();

      // 更新或添加版本
      const existingIndex = versions.findIndex((v) => v.id === version.id);
      if (existingIndex >= 0) {
        versions[existingIndex] = version;
      } else {
        versions.push(version);
      }

      // 保存到localStorage
      localStorage.setItem(this.storageKey, JSON.stringify(versions));

      // 更新索引
      await this.updateIndex(version);
    } catch (error) {
      console.error('Failed to save version to local storage:', error);
      throw error;
    }
  }

  /**
   * 加载版本
   */
  async loadVersion(versionId: string): Promise<Version> {
    try {
      const versions = this.loadVersionsFromStorage();
      const version = versions.find((v) => v.id === versionId);

      if (!version) {
        throw new Error(`Version ${versionId} not found`);
      }

      return this.deserializeVersion(version);
    } catch (error) {
      console.error('Failed to load version from local storage:', error);
      throw error;
    }
  }

  /**
   * 删除版本
   */
  async deleteVersion(versionId: string): Promise<void> {
    try {
      const versions = this.loadVersionsFromStorage();
      const filteredVersions = versions.filter((v) => v.id !== versionId);

      localStorage.setItem(this.storageKey, JSON.stringify(filteredVersions));

      // 更新索引
      await this.removeFromIndex(versionId);
    } catch (error) {
      console.error('Failed to delete version from local storage:', error);
      throw error;
    }
  }

  /**
   * 列出版本
   */
  async listVersions(pageId: string, params?: VersionQueryParams): Promise<VersionListResponse> {
    try {
      let versions = this.loadVersionsFromStorage()
        .filter((v) => v.schema.id === pageId)
        .map((v) => this.deserializeVersion(v));

      // 应用过滤条件
      if (params) {
        versions = this.applyFilters(versions, params);
      }

      // 排序
      versions = this.applySorting(versions, params);

      // 分页
      const { page = 1, size = 20 } = params || {};
      const startIndex = (page - 1) * size;
      const endIndex = startIndex + size;
      const paginatedVersions = versions.slice(startIndex, endIndex);

      return {
        versions: paginatedVersions,
        total: versions.length,
        page,
        size,
        totalPages: Math.ceil(versions.length / size),
      };
    } catch (error) {
      console.error('Failed to list versions from local storage:', error);
      throw error;
    }
  }

  /**
   * 检查版本是否存在
   */
  async versionExists(versionId: string): Promise<boolean> {
    try {
      const versions = this.loadVersionsFromStorage();
      return versions.some((v) => v.id === versionId);
    } catch (error) {
      console.error('Failed to check version existence:', error);
      return false;
    }
  }

  /**
   * 从localStorage加载版本数据
   */
  private loadVersionsFromStorage(): any[] {
    try {
      const data = localStorage.getItem(this.storageKey);
      return data ? JSON.parse(data) : [];
    } catch (error) {
      console.error('Failed to parse versions from localStorage:', error);
      return [];
    }
  }

  /**
   * 反序列化版本对象
   */
  private deserializeVersion(versionData: any): Version {
    return {
      ...versionData,
      createdAt: new Date(versionData.createdAt),
      updatedAt: new Date(versionData.updatedAt),
      publishedAt: versionData.publishedAt ? new Date(versionData.publishedAt) : undefined,
    };
  }

  /**
   * 应用过滤条件
   */
  private applyFilters(versions: Version[], params: VersionQueryParams): Version[] {
    let filtered = versions;

    if (params.status) {
      filtered = filtered.filter((v) => v.status === params.status);
    }

    if (params.type) {
      filtered = filtered.filter((v) => v.type === params.type);
    }

    if (params.createdBy) {
      filtered = filtered.filter((v) => v.createdBy === params.createdBy);
    }

    if (params.tags && params.tags.length > 0) {
      filtered = filtered.filter((v) => params.tags!.some((tag) => v.tags?.includes(tag)));
    }

    if (params.startDate) {
      filtered = filtered.filter((v) => v.createdAt >= params.startDate!);
    }

    if (params.endDate) {
      filtered = filtered.filter((v) => v.createdAt <= params.endDate!);
    }

    return filtered;
  }

  /**
   * 应用排序
   */
  private applySorting(versions: Version[], params?: VersionQueryParams): Version[] {
    if (!params?.sortBy) {
      return versions.sort((a, b) => b.createdAt.getTime() - a.createdAt.getTime());
    }

    const { sortBy, sortOrder = 'desc' } = params;

    return versions.sort((a, b) => {
      let aValue: any;
      let bValue: any;

      switch (sortBy) {
        case 'version':
          aValue = a.version;
          bValue = b.version;
          break;
        case 'createdAt':
          aValue = a.createdAt.getTime();
          bValue = b.createdAt.getTime();
          break;
        case 'updatedAt':
          aValue = a.updatedAt.getTime();
          bValue = b.updatedAt.getTime();
          break;
        case 'status':
          aValue = a.status;
          bValue = b.status;
          break;
        default:
          aValue = a.createdAt.getTime();
          bValue = b.createdAt.getTime();
      }

      if (sortOrder === 'asc') {
        return aValue > bValue ? 1 : -1;
      } else {
        return aValue < bValue ? 1 : -1;
      }
    });
  }

  /**
   * 更新索引
   */
  private async updateIndex(version: Version): Promise<void> {
    try {
      const index = this.loadIndexFromStorage();

      // 更新页面索引
      if (!index.pages[version.schema.id]) {
        index.pages[version.schema.id] = [];
      }

      const pageVersions = index.pages[version.schema.id];
      const existingIndex = pageVersions.findIndex((v: { id: string }) => v.id === version.id);

      const versionInfo = {
        id: version.id,
        version: version.version,
        status: version.status,
        createdAt: version.createdAt.toISOString(),
        updatedAt: version.updatedAt.toISOString(),
      };

      if (existingIndex >= 0) {
        pageVersions[existingIndex] = versionInfo;
      } else {
        pageVersions.push(versionInfo);
      }

      // 更新状态索引
      if (!index.status[version.status]) {
        index.status[version.status] = [];
      }

      if (!index.status[version.status].includes(version.id)) {
        index.status[version.status].push(version.id);
      }

      // 保存索引
      localStorage.setItem(this.indexKey, JSON.stringify(index));
    } catch (error) {
      console.error('Failed to update index:', error);
    }
  }

  /**
   * 从索引中移除
   */
  private async removeFromIndex(versionId: string): Promise<void> {
    try {
      const index = this.loadIndexFromStorage();

      // 从页面索引中移除
      Object.keys(index.pages).forEach((pageId: string) => {
        index.pages[pageId] = index.pages[pageId].filter((v: { id: string }) => v.id !== versionId);
      });

      // 从状态索引中移除
      Object.keys(index.status).forEach((status: string) => {
        index.status[status] = index.status[status].filter((id: string) => id !== versionId);
      });

      // 保存索引
      localStorage.setItem(this.indexKey, JSON.stringify(index));
    } catch (error) {
      console.error('Failed to remove from index:', error);
    }
  }

  /**
   * 从localStorage加载索引
   */
  private loadIndexFromStorage(): any {
    try {
      const data = localStorage.getItem(this.indexKey);
      return data ? JSON.parse(data) : { pages: {}, status: {} };
    } catch (error) {
      console.error('Failed to parse index from localStorage:', error);
      return { pages: {}, status: {} };
    }
  }

  /**
   * 清理存储
   */
  async cleanup(): Promise<void> {
    try {
      localStorage.removeItem(this.storageKey);
      localStorage.removeItem(this.indexKey);
    } catch (error) {
      console.error('Failed to cleanup storage:', error);
    }
  }

  /**
   * 获取存储统计信息
   */
  async getStorageStats(): Promise<{
    totalVersions: number;
    totalSize: number;
    pageCount: number;
    statusBreakdown: Record<string, number>;
  }> {
    try {
      const versions = this.loadVersionsFromStorage();
      const index = this.loadIndexFromStorage();

      const storageData = localStorage.getItem(this.storageKey) || '';
      const totalSize = new Blob([storageData]).size;

      const statusBreakdown: Record<string, number> = {};
      Object.values(VersionStatus).forEach((status) => {
        statusBreakdown[status] = (index.status[status] || []).length;
      });

      return {
        totalVersions: versions.length,
        totalSize,
        pageCount: Object.keys(index.pages).length,
        statusBreakdown,
      };
    } catch (error) {
      console.error('Failed to get storage stats:', error);
      return {
        totalVersions: 0,
        totalSize: 0,
        pageCount: 0,
        statusBreakdown: {},
      };
    }
  }
}

/**
 * IndexedDB 版本存储器
 */
export class IndexedDBVersionStorage implements VersionStorage {
  private dbName = 'DesignerVersions';
  private dbVersion = 1;
  private storeName = 'versions';
  private db: IDBDatabase | null = null;

  constructor() {
    this.initDB();
  }

  /**
   * 初始化数据库
   */
  private async initDB(): Promise<void> {
    return new Promise((resolve, reject) => {
      const request = indexedDB.open(this.dbName, this.dbVersion);

      request.onerror = () => reject(request.error);
      request.onsuccess = () => {
        this.db = request.result;
        resolve();
      };

      request.onupgradeneeded = (event) => {
        const db = (event.target as IDBOpenDBRequest).result;

        if (!db.objectStoreNames.contains(this.storeName)) {
          const store = db.createObjectStore(this.storeName, { keyPath: 'id' });
          store.createIndex('pageId', 'schema.id', { unique: false });
          store.createIndex('status', 'status', { unique: false });
          store.createIndex('createdAt', 'createdAt', { unique: false });
        }
      };
    });
  }

  /**
   * 保存版本
   */
  async saveVersion(version: Version): Promise<void> {
    if (!this.db) await this.initDB();

    return new Promise((resolve, reject) => {
      const transaction = this.db!.transaction([this.storeName], 'readwrite');
      const store = transaction.objectStore(this.storeName);
      const request = store.put(version);

      request.onerror = () => reject(request.error);
      request.onsuccess = () => resolve();
    });
  }

  /**
   * 加载版本
   */
  async loadVersion(versionId: string): Promise<Version> {
    if (!this.db) await this.initDB();

    return new Promise((resolve, reject) => {
      const transaction = this.db!.transaction([this.storeName], 'readonly');
      const store = transaction.objectStore(this.storeName);
      const request = store.get(versionId);

      request.onerror = () => reject(request.error);
      request.onsuccess = () => {
        if (request.result) {
          resolve(this.deserializeVersion(request.result));
        } else {
          reject(new Error(`Version ${versionId} not found`));
        }
      };
    });
  }

  /**
   * 删除版本
   */
  async deleteVersion(versionId: string): Promise<void> {
    if (!this.db) await this.initDB();

    return new Promise((resolve, reject) => {
      const transaction = this.db!.transaction([this.storeName], 'readwrite');
      const store = transaction.objectStore(this.storeName);
      const request = store.delete(versionId);

      request.onerror = () => reject(request.error);
      request.onsuccess = () => resolve();
    });
  }

  /**
   * 列出版本
   */
  async listVersions(pageId: string, params?: VersionQueryParams): Promise<VersionListResponse> {
    if (!this.db) await this.initDB();

    return new Promise((resolve, reject) => {
      const transaction = this.db!.transaction([this.storeName], 'readonly');
      const store = transaction.objectStore(this.storeName);
      const index = store.index('pageId');
      const request = index.getAll(pageId);

      request.onerror = () => reject(request.error);
      request.onsuccess = () => {
        let versions = request.result.map((v) => this.deserializeVersion(v));

        // 应用过滤和排序
        if (params) {
          versions = this.applyFilters(versions, params);
          versions = this.applySorting(versions, params);
        }

        // 分页
        const { page = 1, size = 20 } = params || {};
        const startIndex = (page - 1) * size;
        const endIndex = startIndex + size;
        const paginatedVersions = versions.slice(startIndex, endIndex);

        resolve({
          versions: paginatedVersions,
          total: versions.length,
          page,
          size,
          totalPages: Math.ceil(versions.length / size),
        });
      };
    });
  }

  /**
   * 检查版本是否存在
   */
  async versionExists(versionId: string): Promise<boolean> {
    if (!this.db) await this.initDB();

    return new Promise((resolve, reject) => {
      const transaction = this.db!.transaction([this.storeName], 'readonly');
      const store = transaction.objectStore(this.storeName);
      const request = store.count(versionId);

      request.onerror = () => reject(request.error);
      request.onsuccess = () => resolve(request.result > 0);
    });
  }

  /**
   * 反序列化版本对象
   */
  private deserializeVersion(versionData: any): Version {
    return {
      ...versionData,
      createdAt: new Date(versionData.createdAt),
      updatedAt: new Date(versionData.updatedAt),
      publishedAt: versionData.publishedAt ? new Date(versionData.publishedAt) : undefined,
    };
  }

  /**
   * 应用过滤条件
   */
  private applyFilters(versions: Version[], params: VersionQueryParams): Version[] {
    let filtered = versions;

    if (params.status) {
      filtered = filtered.filter((v) => v.status === params.status);
    }

    if (params.type) {
      filtered = filtered.filter((v) => v.type === params.type);
    }

    if (params.createdBy) {
      filtered = filtered.filter((v) => v.createdBy === params.createdBy);
    }

    if (params.tags && params.tags.length > 0) {
      filtered = filtered.filter((v) => params.tags!.some((tag) => v.tags?.includes(tag)));
    }

    if (params.startDate) {
      filtered = filtered.filter((v) => v.createdAt >= params.startDate!);
    }

    if (params.endDate) {
      filtered = filtered.filter((v) => v.createdAt <= params.endDate!);
    }

    return filtered;
  }

  /**
   * 应用排序
   */
  private applySorting(versions: Version[], params?: VersionQueryParams): Version[] {
    if (!params?.sortBy) {
      return versions.sort((a, b) => b.createdAt.getTime() - a.createdAt.getTime());
    }

    const { sortBy, sortOrder = 'desc' } = params;

    return versions.sort((a, b) => {
      let aValue: any;
      let bValue: any;

      switch (sortBy) {
        case 'version':
          aValue = a.version;
          bValue = b.version;
          break;
        case 'createdAt':
          aValue = a.createdAt.getTime();
          bValue = b.createdAt.getTime();
          break;
        case 'updatedAt':
          aValue = a.updatedAt.getTime();
          bValue = b.updatedAt.getTime();
          break;
        case 'status':
          aValue = a.status;
          bValue = b.status;
          break;
        default:
          aValue = a.createdAt.getTime();
          bValue = b.createdAt.getTime();
      }

      if (sortOrder === 'asc') {
        return aValue > bValue ? 1 : -1;
      } else {
        return aValue < bValue ? 1 : -1;
      }
    });
  }
}
