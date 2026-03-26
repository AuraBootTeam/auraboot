import { VersionStatus, VersionType } from '~/studio/domain/metadata/types';

// 常量
export const VERSION_CONSTANTS = {
  // 默认配置
  DEFAULT_CONFIG: {
    autoSave: true,
    autoSaveInterval: 30000, // 30秒
    maxVersions: 100,
    enableVersionLock: true,
    lockTimeout: 300000, // 5分钟
    enableSync: true,
    syncInterval: 60000, // 1分钟
    storageType: 'indexeddb' as const,
  },

  // 版本号规则
  VERSION_RULES: {
    MIN_VERSION: '0.0.1',
    MAX_VERSION: '999.999.999',
    DEFAULT_VERSION: '1.0.0',
  },

  // 存储键名
  STORAGE_KEYS: {
    VERSIONS: 'designer_versions',
    CONFIG: 'designer_version_config',
    SYNC_STATUS: 'designer_sync_status',
    LOCKS: 'designer_version_locks',
  },

  // 事件名称
  EVENTS: {
    VERSION_CREATED: 'version:created',
    VERSION_UPDATED: 'version:updated',
    VERSION_DELETED: 'version:deleted',
    VERSION_PUBLISHED: 'version:published',
    VERSION_UNPUBLISHED: 'version:unpublished',
    VERSION_ROLLED_BACK: 'version:rolled_back',
    VERSION_ARCHIVED: 'version:archived',
    VERSION_RESTORED: 'version:restored',
    VERSION_LOCKED: 'version:locked',
    VERSION_UNLOCKED: 'version:unlocked',
    SYNC_STARTED: 'sync:started',
    SYNC_COMPLETED: 'sync:completed',
    SYNC_FAILED: 'sync:failed',
  },

  // 错误代码
  ERROR_CODES: {
    VERSION_NOT_FOUND: 'version_not_found',
    VERSION_LOCKED: 'version_locked',
    VERSION_ALREADY_PUBLISHED: 'version_already_published',
    VERSION_NOT_PUBLISHABLE: 'version_not_publishable',
    INVALID_VERSION_NUMBER: 'invalid_version_number',
    SYNC_FAILED: 'sync_failed',
    STORAGE_ERROR: 'storage_error',
    PERMISSION_DENIED: 'permission_denied',
  },
};
