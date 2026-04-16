/**
 * CanvasSchema 版本管理相关的 React Hooks
 */

import { useState, useEffect, useCallback, useRef } from 'react';
import type { CanvasSchema } from '~/plugins/core-designer/components/studio/workbench/canvas/types';
import type {
  PageSchemaVersion,
  CreatePageSchemaVersionRequest,
  UpdatePageSchemaVersionRequest,
  PageSchemaVersionConfig,
} from '~/plugins/core-designer/components/studio/domain/metadata/PageSchemaVersionManager';
import { PageSchemaVersionManager } from '~/plugins/core-designer/components/studio/domain/metadata/PageSchemaVersionManager';
import { getPageSchemaVersionManager } from '~/plugins/core-designer/components/studio/domain/metadata/PageSchemaVersionManager';
import { VersionStatus, VersionType } from '~/plugins/core-designer/components/studio/domain/metadata/types';

/**
 * CanvasSchema 版本管理 Hook
 */
export function usePageSchemaVersion(pageId: string, config?: Partial<PageSchemaVersionConfig>) {
  const [currentSchema, setCurrentSchema] = useState<CanvasSchema | null>(null);
  const [currentVersion, setCurrentVersion] = useState<PageSchemaVersion | null>(null);
  const [publishedVersion, setPublishedVersion] = useState<PageSchemaVersion | null>(null);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [hasUnsavedChanges, setHasUnsavedChanges] = useState(false);

  const versionManager = useRef(getPageSchemaVersionManager(config));
  const autoSaveTimeoutRef = useRef<NodeJS.Timeout | null>(null);

  /**
   * 加载当前草稿版本
   */
  const loadCurrentDraft = useCallback(async () => {
    try {
      setLoading(true);
      setError(null);

      const draft = await versionManager.current.getCurrentDraft(pageId);
      if (draft) {
        setCurrentVersion(draft);
        setCurrentSchema(draft.schema);
      } else {
        setCurrentVersion(null);
        setCurrentSchema(null);
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : '加载草稿失败');
    } finally {
      setLoading(false);
    }
  }, [pageId]);

  /**
   * 加载已发布版本
   */
  const loadPublishedVersion = useCallback(async () => {
    try {
      const published = await versionManager.current.getPublishedVersion(pageId);
      setPublishedVersion(published);
    } catch (err) {
      console.error('Failed to load published version:', err);
    }
  }, [pageId]);

  /**
   * 保存草稿
   */
  const saveDraft = useCallback(
    async (schema: CanvasSchema, description?: string) => {
      try {
        setSaving(true);
        setError(null);

        const version = await versionManager.current.saveDraft(pageId, schema, description);
        setCurrentVersion(version);
        setCurrentSchema(schema);
        setHasUnsavedChanges(false);

        return version;
      } catch (err) {
        const errorMessage = err instanceof Error ? err.message : '保存草稿失败';
        setError(errorMessage);
        throw new Error(errorMessage);
      } finally {
        setSaving(false);
      }
    },
    [pageId],
  );

  /**
   * 创建新版本
   */
  const createVersion = useCallback(
    async (request: CreatePageSchemaVersionRequest) => {
      try {
        setSaving(true);
        setError(null);

        const version = await versionManager.current.createVersion(pageId, request);
        setCurrentVersion(version);
        setCurrentSchema(request.schema);
        setHasUnsavedChanges(false);

        return version;
      } catch (err) {
        const errorMessage = err instanceof Error ? err.message : '创建版本失败';
        setError(errorMessage);
        throw new Error(errorMessage);
      } finally {
        setSaving(false);
      }
    },
    [pageId],
  );

  /**
   * 更新当前版本
   */
  const updateVersion = useCallback(async (request: UpdatePageSchemaVersionRequest) => {
    try {
      setSaving(true);
      setError(null);

      const version = await versionManager.current.updateVersion(request);
      setCurrentVersion(version);
      if (request.schema) {
        setCurrentSchema(request.schema);
      }
      setHasUnsavedChanges(false);

      return version;
    } catch (err) {
      const errorMessage = err instanceof Error ? err.message : '更新版本失败';
      setError(errorMessage);
      throw new Error(errorMessage);
    } finally {
      setSaving(false);
    }
  }, []);

  /**
   * 发布版本
   */
  const publishVersion = useCallback(
    async (versionId?: string, description?: string) => {
      try {
        setSaving(true);
        setError(null);

        const targetVersionId = versionId || currentVersion?.id;
        if (!targetVersionId) {
          throw new Error('没有可发布的版本');
        }

        const version = await versionManager.current.publishVersion(pageId, {
          versionId: targetVersionId,
          description,
        });

        setPublishedVersion(version);

        // 如果发布的是当前版本，更新状态
        if (targetVersionId === currentVersion?.id) {
          setCurrentVersion(version);
        }

        return version;
      } catch (err) {
        const errorMessage = err instanceof Error ? err.message : '发布版本失败';
        setError(errorMessage);
        throw new Error(errorMessage);
      } finally {
        setSaving(false);
      }
    },
    [currentVersion?.id],
  );

  /**
   * 回滚到指定版本
   */
  const rollbackToVersion = useCallback(
    async (targetVersionId: string, description?: string) => {
      try {
        setSaving(true);
        setError(null);

        const version = await versionManager.current.rollbackVersion(pageId, {
          targetVersionId,
          description: description || `回滚到版本 ${targetVersionId}`,
        });

        setCurrentVersion(version);
        setCurrentSchema(version.schema);
        setHasUnsavedChanges(false);

        return version;
      } catch (err) {
        const errorMessage = err instanceof Error ? err.message : '回滚版本失败';
        setError(errorMessage);
        throw new Error(errorMessage);
      } finally {
        setSaving(false);
      }
    },
    [pageId],
  );

  /**
   * 更新 Schema（触发自动保存）
   */
  const updateSchema = useCallback(
    (schema: CanvasSchema) => {
      setCurrentSchema(schema);
      setHasUnsavedChanges(true);

      // 标记变更用于自动保存
      versionManager.current.markSchemaChanged(pageId, schema);

      // 防抖自动保存
      if (autoSaveTimeoutRef.current) {
        clearTimeout(autoSaveTimeoutRef.current);
      }

      autoSaveTimeoutRef.current = setTimeout(() => {
        saveDraft(schema, '自动保存').catch(console.error);
      }, 2000); // 2秒防抖
    },
    [pageId, saveDraft],
  );

  /**
   * 立即保存
   */
  const saveNow = useCallback(async () => {
    if (currentSchema && hasUnsavedChanges) {
      await saveDraft(currentSchema, '手动保存');
    }
  }, [currentSchema, hasUnsavedChanges, saveDraft]);

  /**
   * 清除错误
   */
  const clearError = useCallback(() => {
    setError(null);
  }, []);

  /**
   * 刷新数据
   */
  const refresh = useCallback(async () => {
    await Promise.all([loadCurrentDraft(), loadPublishedVersion()]);
  }, [loadCurrentDraft, loadPublishedVersion]);

  // 初始化加载
  useEffect(() => {
    refresh();
  }, [refresh]);

  // 清理定时器
  useEffect(() => {
    return () => {
      if (autoSaveTimeoutRef.current) {
        clearTimeout(autoSaveTimeoutRef.current);
      }
    };
  }, []);

  return {
    // 状态
    currentSchema,
    currentVersion,
    publishedVersion,
    loading,
    saving,
    error,
    hasUnsavedChanges,

    // 操作
    updateSchema,
    saveDraft,
    saveNow,
    createVersion,
    updateVersion,
    publishVersion,
    rollbackToVersion,
    clearError,
    refresh,
  };
}

/**
 * CanvasSchema 版本列表 Hook
 */
export function usePageSchemaVersionList(pageId: string) {
  const [versions, setVersions] = useState<PageSchemaVersion[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const versionManager = useRef(getPageSchemaVersionManager());

  const loadVersions = useCallback(async () => {
    try {
      setLoading(true);
      setError(null);

      const response = await versionManager.current.getVersions(pageId, {
        page: 1,
        size: 50,
      });

      setVersions(response.versions);
    } catch (err) {
      setError(err instanceof Error ? err.message : '加载版本列表失败');
    } finally {
      setLoading(false);
    }
  }, [pageId]);

  const refresh = useCallback(() => {
    loadVersions();
  }, [loadVersions]);

  useEffect(() => {
    loadVersions();
  }, [loadVersions]);

  return {
    versions,
    loading,
    error,
    refresh,
  };
}

/**
 * CanvasSchema 版本比较 Hook
 */
export function usePageSchemaVersionComparison() {
  const [comparing, setComparing] = useState(false);
  const [comparisonResult, setComparisonResult] = useState<{
    versionA: PageSchemaVersion;
    versionB: PageSchemaVersion;
    differences: any[];
  } | null>(null);

  const versionManager = useRef(getPageSchemaVersionManager());

  const compareVersions = useCallback(async (versionAId: string, versionBId: string) => {
    try {
      setComparing(true);

      const [versionA, versionB] = await Promise.all([
        versionManager.current.getVersion(versionAId),
        versionManager.current.getVersion(versionBId),
      ]);

      if (!versionA || !versionB) {
        throw new Error('版本不存在');
      }

      // 比较 Schema 差异
      const differences = compareSchemas(versionA.schema, versionB.schema);

      setComparisonResult({
        versionA,
        versionB,
        differences,
      });

      return { versionA, versionB, differences };
    } catch (err) {
      console.error('Failed to compare versions:', err);
      throw err;
    } finally {
      setComparing(false);
    }
  }, []);

  const clearComparison = useCallback(() => {
    setComparisonResult(null);
  }, []);

  return {
    comparing,
    comparisonResult,
    compareVersions,
    clearComparison,
  };
}

/**
 * 比较两个 Schema 的差异
 */
function compareSchemas(schemaA: CanvasSchema, schemaB: CanvasSchema): any[] {
  const differences: any[] = [];

  // 比较基本属性
  if (schemaA.title !== schemaB.title) {
    differences.push({
      type: 'property',
      path: 'title',
      oldValue: schemaA.title,
      newValue: schemaB.title,
    });
  }

  if (schemaA.description !== schemaB.description) {
    differences.push({
      type: 'property',
      path: 'description',
      oldValue: schemaA.description,
      newValue: schemaB.description,
    });
  }

  // 比较组件
  const componentsA = schemaA.components || [];
  const componentsB = schemaB.components || [];

  // 找出新增的组件
  const addedComponents = componentsB.filter(
    (compB) => !componentsA.find((compA) => compA.id === compB.id),
  );
  addedComponents.forEach((comp) => {
    differences.push({
      type: 'component_added',
      path: `components.${comp.id}`,
      component: comp,
    });
  });

  // 找出删除的组件
  const removedComponents = componentsA.filter(
    (compA) => !componentsB.find((compB) => compB.id === compA.id),
  );
  removedComponents.forEach((comp) => {
    differences.push({
      type: 'component_removed',
      path: `components.${comp.id}`,
      component: comp,
    });
  });

  // 找出修改的组件
  componentsA.forEach((compA) => {
    const compB = componentsB.find((comp) => comp.id === compA.id);
    if (compB && JSON.stringify(compA) !== JSON.stringify(compB)) {
      differences.push({
        type: 'component_modified',
        path: `components.${compA.id}`,
        oldComponent: compA,
        newComponent: compB,
      });
    }
  });

  // 比较布局
  if (JSON.stringify(schemaA.layout) !== JSON.stringify(schemaB.layout)) {
    differences.push({
      type: 'layout',
      path: 'layout',
      oldValue: schemaA.layout,
      newValue: schemaB.layout,
    });
  }

  // 比较样式
  if (JSON.stringify(schemaA.styles) !== JSON.stringify(schemaB.styles)) {
    differences.push({
      type: 'styles',
      path: 'styles',
      oldValue: schemaA.styles,
      newValue: schemaB.styles,
    });
  }

  return differences;
}

/**
 * CanvasSchema 自动保存 Hook
 */
export function usePageSchemaAutoSave(
  pageId: string,
  schema: CanvasSchema | null,
  enabled: boolean = true,
) {
  const [lastSaved, setLastSaved] = useState<Date | null>(null);
  const [autoSaving, setAutoSaving] = useState(false);

  const versionManager = useRef(getPageSchemaVersionManager());
  const timeoutRef = useRef<NodeJS.Timeout | null>(null);

  const performAutoSave = useCallback(async () => {
    if (!schema || !enabled) return;

    try {
      setAutoSaving(true);
      await versionManager.current.saveDraft(pageId, schema, '自动保存');
      setLastSaved(new Date());
    } catch (error) {
      console.error('Auto-save failed:', error);
    } finally {
      setAutoSaving(false);
    }
  }, [pageId, schema, enabled]);

  // 监听 Schema 变化，触发自动保存
  useEffect(() => {
    if (!schema || !enabled) return;

    // 清除之前的定时器
    if (timeoutRef.current) {
      clearTimeout(timeoutRef.current);
    }

    // 设置新的定时器
    timeoutRef.current = setTimeout(() => {
      performAutoSave();
    }, 2000); // 2秒防抖

    return () => {
      if (timeoutRef.current) {
        clearTimeout(timeoutRef.current);
      }
    };
  }, [schema, enabled, performAutoSave]);

  return {
    lastSaved,
    autoSaving,
    performAutoSave,
  };
}
