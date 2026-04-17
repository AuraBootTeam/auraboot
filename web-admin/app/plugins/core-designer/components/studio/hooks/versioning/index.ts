/**
 * 版本管理相关的 React Hooks
 */

import { useState, useEffect, useCallback, useRef } from 'react';
import type {
  Version,
  VersionListResponse,
  VersionQueryParams,
  CreateVersionRequest,
  UpdateVersionRequest,
  PublishVersionRequest,
  RollbackVersionRequest,
  VersionEvent,
  VersionEventListener,
  VersionSync,
} from '~/plugins/core-designer/components/studio/domain/metadata/types';
import {
  VersionStatus,
  VersionType,
  VersionEventType,
  SyncStatus,
} from '~/plugins/core-designer/components/studio/domain/metadata/types';
import { getVersionManager } from '~/plugins/core-designer/components/studio/services/managers';
import { useAuth } from '~/contexts/AuthContext';

/**
 * 版本列表 Hook
 */
export function useVersionList(pageId: string, params?: VersionQueryParams) {
  const [versions, setVersions] = useState<Version[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(params?.page || 1);
  const [size, setSize] = useState(params?.size || 20);

  const versionManager = getVersionManager();

  const loadVersions = useCallback(
    async (queryParams?: VersionQueryParams) => {
      try {
        setLoading(true);
        setError(null);

        const finalParams = { ...params, ...queryParams, page, size };
        const response = await versionManager.getVersions(pageId, finalParams);

        setVersions(response.versions);
        setTotal(response.total);
      } catch (err) {
        setError(err instanceof Error ? err.message : '加载版本列表失败');
      } finally {
        setLoading(false);
      }
    },
    [pageId, params, page, size, versionManager],
  );

  const refresh = useCallback(() => {
    loadVersions();
  }, [loadVersions]);

  const nextPage = useCallback(() => {
    setPage((prev) => prev + 1);
  }, []);

  const prevPage = useCallback(() => {
    setPage((prev) => Math.max(1, prev - 1));
  }, []);

  const goToPage = useCallback((targetPage: number) => {
    setPage(Math.max(1, targetPage));
  }, []);

  useEffect(() => {
    loadVersions();
  }, [loadVersions, page]);

  return {
    versions,
    loading,
    error,
    total,
    page,
    size,
    totalPages: Math.ceil(total / size),
    refresh,
    nextPage,
    prevPage,
    goToPage,
    setSize: (newSize: number) => {
      setSize(newSize);
      setPage(1);
    },
  };
}

/**
 * 版本详情 Hook
 */
export function useVersion(versionId: string | null) {
  const [version, setVersion] = useState<Version | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const versionManager = getVersionManager();

  const loadVersion = useCallback(
    async (id: string) => {
      try {
        setLoading(true);
        setError(null);

        const versionData = await versionManager.getVersion(id);
        setVersion(versionData);
      } catch (err) {
        setError(err instanceof Error ? err.message : '加载版本详情失败');
        setVersion(null);
      } finally {
        setLoading(false);
      }
    },
    [versionManager],
  );

  const refresh = useCallback(() => {
    if (versionId) {
      loadVersion(versionId);
    }
  }, [versionId, loadVersion]);

  useEffect(() => {
    if (versionId) {
      loadVersion(versionId);
    } else {
      setVersion(null);
      setError(null);
    }
  }, [versionId, loadVersion]);

  return {
    version,
    loading,
    error,
    refresh,
  };
}

/**
 * Version operations hook
 */
export function useVersionOperations(pageId: string) {
  const { user } = useAuth();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const versionManager = getVersionManager();

  const getActor = useCallback(
    () => user?.email ?? user?.name ?? 'unknown',
    [user],
  );

  const createVersion = useCallback(
    async (request: CreateVersionRequest): Promise<Version> => {
      try {
        setLoading(true);
        setError(null);

        const newVersion = await versionManager.createVersion(pageId, request, getActor());
        return newVersion;
      } catch (err) {
        const errorMessage = err instanceof Error ? err.message : '创建版本失败';
        setError(errorMessage);
        throw new Error(errorMessage);
      } finally {
        setLoading(false);
      }
    },
    [pageId, versionManager, getActor],
  );

  const updateVersion = useCallback(
    async (request: UpdateVersionRequest): Promise<Version> => {
      try {
        setLoading(true);
        setError(null);

        const updatedVersion = await versionManager.updateVersion(request, getActor());
        return updatedVersion;
      } catch (err) {
        const errorMessage = err instanceof Error ? err.message : '更新版本失败';
        setError(errorMessage);
        throw new Error(errorMessage);
      } finally {
        setLoading(false);
      }
    },
    [versionManager, getActor],
  );

  const deleteVersion = useCallback(
    async (versionId: string): Promise<void> => {
      try {
        setLoading(true);
        setError(null);

        await versionManager.deleteVersion(pageId, versionId, getActor());
      } catch (err) {
        const errorMessage = err instanceof Error ? err.message : '删除版本失败';
        setError(errorMessage);
        throw new Error(errorMessage);
      } finally {
        setLoading(false);
      }
    },
    [versionManager, getActor],
  );

  const publishVersion = useCallback(
    async (request: PublishVersionRequest): Promise<Version> => {
      try {
        setLoading(true);
        setError(null);

        const publishedVersion = await versionManager.publishVersion(
          pageId,
          request.versionId,
          request,
          getActor(),
        );
        return publishedVersion;
      } catch (err) {
        const errorMessage = err instanceof Error ? err.message : '发布版本失败';
        setError(errorMessage);
        throw new Error(errorMessage);
      } finally {
        setLoading(false);
      }
    },
    [versionManager, getActor],
  );

  const unpublishVersion = useCallback(
    async (versionId: string): Promise<Version> => {
      try {
        setLoading(true);
        setError(null);

        const unpublishedVersion = await versionManager.unpublishVersion(versionId, getActor());
        return unpublishedVersion;
      } catch (err) {
        const errorMessage = err instanceof Error ? err.message : '取消发布失败';
        setError(errorMessage);
        throw new Error(errorMessage);
      } finally {
        setLoading(false);
      }
    },
    [versionManager, getActor],
  );

  const rollbackVersion = useCallback(
    async (request: RollbackVersionRequest): Promise<Version> => {
      try {
        setLoading(true);
        setError(null);

        const rolledBackVersion = await versionManager.rollbackVersion(pageId, request, getActor());
        return rolledBackVersion;
      } catch (err) {
        const errorMessage = err instanceof Error ? err.message : '回滚版本失败';
        setError(errorMessage);
        throw new Error(errorMessage);
      } finally {
        setLoading(false);
      }
    },
    [pageId, versionManager, getActor],
  );

  const duplicateVersion = useCallback(
    async (versionId: string, description?: string): Promise<Version> => {
      try {
        setLoading(true);
        setError(null);

        const duplicatedVersion = await versionManager.duplicateVersion(versionId, getActor(), description);
        return duplicatedVersion;
      } catch (err) {
        const errorMessage = err instanceof Error ? err.message : '复制版本失败';
        setError(errorMessage);
        throw new Error(errorMessage);
      } finally {
        setLoading(false);
      }
    },
    [versionManager, getActor],
  );

  const archiveVersion = useCallback(
    async (versionId: string): Promise<Version> => {
      try {
        setLoading(true);
        setError(null);

        const archivedVersion = await versionManager.archiveVersion(versionId, getActor());
        return archivedVersion;
      } catch (err) {
        const errorMessage = err instanceof Error ? err.message : '归档版本失败';
        setError(errorMessage);
        throw new Error(errorMessage);
      } finally {
        setLoading(false);
      }
    },
    [versionManager, getActor],
  );

  const restoreVersion = useCallback(
    async (versionId: string): Promise<Version> => {
      try {
        setLoading(true);
        setError(null);

        const restoredVersion = await versionManager.restoreVersion(versionId, getActor());
        return restoredVersion;
      } catch (err) {
        const errorMessage = err instanceof Error ? err.message : '恢复版本失败';
        setError(errorMessage);
        throw new Error(errorMessage);
      } finally {
        setLoading(false);
      }
    },
    [versionManager, getActor],
  );

  const clearError = useCallback(() => {
    setError(null);
  }, []);

  return {
    loading,
    error,
    clearError,
    createVersion,
    updateVersion,
    deleteVersion,
    publishVersion,
    unpublishVersion,
    rollbackVersion,
    duplicateVersion,
    archiveVersion,
    restoreVersion,
  };
}

/**
 * 版本事件监听 Hook
 */
export function useVersionEvents(
  eventTypes: VersionEventType[],
  handler: (event: VersionEvent) => void,
) {
  const versionManager = getVersionManager();
  const listenersRef = useRef<VersionEventListener[]>([]);

  useEffect(() => {
    // 清理旧的监听器
    listenersRef.current.forEach((listener) => {
      versionManager.removeEventListener(listener);
    });
    listenersRef.current = [];

    // 添加新的监听器
    eventTypes.forEach((eventType) => {
      const listener: VersionEventListener = {
        eventType,
        handler,
      };

      versionManager.addEventListener(listener);
      listenersRef.current.push(listener);
    });

    // 清理函数
    return () => {
      listenersRef.current.forEach((listener) => {
        versionManager.removeEventListener(listener);
      });
      listenersRef.current = [];
    };
  }, [eventTypes, handler, versionManager]);
}

/**
 * 当前版本 Hook
 */
export function useCurrentVersion(pageId: string) {
  const [currentVersion, setCurrentVersion] = useState<Version | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const versionManager = getVersionManager();

  const loadCurrentVersion = useCallback(async () => {
    try {
      setLoading(true);
      setError(null);

      const version = await versionManager.getCurrentVersion(pageId);
      setCurrentVersion(version);
    } catch (err) {
      setError(err instanceof Error ? err.message : '加载当前版本失败');
      setCurrentVersion(null);
    } finally {
      setLoading(false);
    }
  }, [pageId, versionManager]);

  const refresh = useCallback(() => {
    loadCurrentVersion();
  }, [loadCurrentVersion]);

  useEffect(() => {
    loadCurrentVersion();
  }, [loadCurrentVersion]);

  // 监听版本变更事件
  useVersionEvents(
    [VersionEventType.VERSION_PUBLISHED, VersionEventType.VERSION_ROLLED_BACK],
    () => {
      loadCurrentVersion();
    },
  );

  return {
    currentVersion,
    loading,
    error,
    refresh,
  };
}

/**
 * 已发布版本 Hook
 */
export function usePublishedVersion(pageId: string) {
  const [publishedVersion, setPublishedVersion] = useState<Version | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const versionManager = getVersionManager();

  const loadPublishedVersion = useCallback(async () => {
    try {
      setLoading(true);
      setError(null);

      const version = await versionManager.getPublishedVersion(pageId);
      setPublishedVersion(version);
    } catch (err) {
      setError(err instanceof Error ? err.message : '加载已发布版本失败');
      setPublishedVersion(null);
    } finally {
      setLoading(false);
    }
  }, [pageId, versionManager]);

  const refresh = useCallback(() => {
    loadPublishedVersion();
  }, [loadPublishedVersion]);

  useEffect(() => {
    loadPublishedVersion();
  }, [loadPublishedVersion]);

  // 监听发布相关事件
  useVersionEvents(
    [VersionEventType.VERSION_PUBLISHED, VersionEventType.VERSION_UNPUBLISHED],
    () => {
      loadPublishedVersion();
    },
  );

  return {
    publishedVersion,
    loading,
    error,
    refresh,
  };
}

/**
 * 版本同步状态 Hook
 */
export function useVersionSync(versionId: string | null) {
  const [syncStatus, setSyncStatus] = useState<VersionSync | null>(null);
  const [syncing, setSyncing] = useState(false);

  const versionManager = getVersionManager();

  const checkSyncStatus = useCallback(() => {
    if (versionId) {
      const status = versionManager.getSyncStatus(versionId);
      setSyncStatus(status || null);
    } else {
      setSyncStatus(null);
    }
  }, [versionId, versionManager]);

  const updateSyncStatus = useCallback(
    (status: SyncStatus, error?: string) => {
      if (versionId) {
        versionManager.updateSyncStatus(versionId, status, error);
        checkSyncStatus();
      }
    },
    [versionId, versionManager, checkSyncStatus],
  );

  const startSync = useCallback(() => {
    setSyncing(true);
    updateSyncStatus(SyncStatus.SYNCING);
  }, [updateSyncStatus]);

  const completeSync = useCallback(
    (success: boolean, error?: string) => {
      setSyncing(false);
      updateSyncStatus(success ? SyncStatus.SYNCED : SyncStatus.failed, error);
    },
    [updateSyncStatus],
  );

  useEffect(() => {
    checkSyncStatus();
  }, [checkSyncStatus]);

  return {
    syncStatus,
    syncing,
    startSync,
    completeSync,
    updateSyncStatus,
    refresh: checkSyncStatus,
  };
}

/**
 * 版本锁定 Hook
 */
export function useVersionLock(versionId: string | null) {
  const { user } = useAuth();
  const [locked, setLocked] = useState(false);
  const [lockInfo, setLockInfo] = useState<{ lockedBy: string; reason?: string } | null>(null);

  const versionManager = getVersionManager();

  const lockVersion = useCallback(
    async (reason?: string, expiresAt?: Date) => {
      if (versionId) {
        const actor = user?.email ?? user?.name ?? 'unknown';
        try {
          await versionManager.lockVersion(versionId, actor, reason, expiresAt);
          setLocked(true);
          setLockInfo({ lockedBy: actor, reason });
        } catch (err) {
          console.error('Failed to lock version:', err);
        }
      }
    },
    [versionId, versionManager, user],
  );

  const unlockVersion = useCallback(async () => {
    if (versionId) {
      try {
        await versionManager.unlockVersion(versionId);
        setLocked(false);
        setLockInfo(null);
      } catch (err) {
        console.error('Failed to unlock version:', err);
      }
    }
  }, [versionId, versionManager]);

  return {
    locked,
    lockInfo,
    lockVersion,
    unlockVersion,
  };
}

/**
 * 版本统计 Hook
 */
export function useVersionStats(pageId: string) {
  const [stats, setStats] = useState<{
    total: number;
    draft: number;
    published: number;
    archived: number;
  }>({
    total: 0,
    draft: 0,
    published: 0,
    archived: 0,
  });
  const [loading, setLoading] = useState(false);

  const versionManager = getVersionManager();

  const loadStats = useCallback(async () => {
    try {
      setLoading(true);

      const [draftVersions, publishedVersions, archivedVersions] = await Promise.all([
        versionManager.getVersions(pageId, { status: VersionStatus.draft }),
        versionManager.getVersions(pageId, { status: VersionStatus.published }),
        versionManager.getVersions(pageId, { status: VersionStatus.archived }),
      ]);

      setStats({
        total: draftVersions.total + publishedVersions.total + archivedVersions.total,
        draft: draftVersions.total,
        published: publishedVersions.total,
        archived: archivedVersions.total,
      });
    } catch (err) {
      console.error('Failed to load version stats:', err);
    } finally {
      setLoading(false);
    }
  }, [pageId, versionManager]);

  useEffect(() => {
    loadStats();
  }, [loadStats]);

  // 监听版本变更事件
  useVersionEvents(
    [
      VersionEventType.VERSION_CREATED,
      VersionEventType.VERSION_DELETED,
      VersionEventType.VERSION_PUBLISHED,
      VersionEventType.VERSION_UNPUBLISHED,
      VersionEventType.VERSION_ARCHIVED,
      VersionEventType.VERSION_RESTORED,
    ],
    () => {
      loadStats();
    },
  );

  return {
    stats,
    loading,
    refresh: loadStats,
  };
}
