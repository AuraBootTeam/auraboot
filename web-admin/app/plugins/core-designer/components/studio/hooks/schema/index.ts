/**
 * CanvasSchema version management React hooks
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
import { useAuth } from '~/contexts/AuthContext';

/**
 * CanvasSchema version management hook
 */
export function usePageSchemaVersion(pageId: string, config?: Partial<PageSchemaVersionConfig>) {
  const { user } = useAuth();
  const [currentSchema, setCurrentSchema] = useState<CanvasSchema | null>(null);
  const [currentVersion, setCurrentVersion] = useState<PageSchemaVersion | null>(null);
  const [publishedVersion, setPublishedVersion] = useState<PageSchemaVersion | null>(null);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [hasUnsavedChanges, setHasUnsavedChanges] = useState(false);

  const versionManager = useRef(getPageSchemaVersionManager(config));
  const autoSaveTimeoutRef = useRef<NodeJS.Timeout | null>(null);

  const getActor = useCallback(
    () => user?.email ?? user?.name ?? 'unknown',
    [user],
  );

  /**
   * Load current draft version
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
   * Load published version
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
   * Save draft
   */
  const saveDraft = useCallback(
    async (schema: CanvasSchema, description?: string) => {
      try {
        setSaving(true);
        setError(null);

        const version = await versionManager.current.saveDraft(pageId, schema, getActor(), description);
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
    [pageId, getActor],
  );

  /**
   * Create new version
   */
  const createVersion = useCallback(
    async (request: CreatePageSchemaVersionRequest) => {
      try {
        setSaving(true);
        setError(null);

        const version = await versionManager.current.createVersion(pageId, request, getActor());
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
    [pageId, getActor],
  );

  /**
   * Update current version
   */
  const updateVersion = useCallback(async (request: UpdatePageSchemaVersionRequest) => {
    try {
      setSaving(true);
      setError(null);

      const version = await versionManager.current.updateVersion(request, getActor());
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
  }, [getActor]);

  /**
   * Publish version
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
        }, getActor());

        setPublishedVersion(version);

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
    [currentVersion?.id, getActor],
  );

  /**
   * Rollback to a specific version
   */
  const rollbackToVersion = useCallback(
    async (targetVersionId: string, description?: string) => {
      try {
        setSaving(true);
        setError(null);

        const version = await versionManager.current.rollbackVersion(pageId, {
          targetVersionId,
          description: description || `回滚到版本 ${targetVersionId}`,
        }, getActor());

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
    [pageId, getActor],
  );

  /**
   * Update schema (triggers auto-save)
   */
  const updateSchema = useCallback(
    (schema: CanvasSchema) => {
      setCurrentSchema(schema);
      setHasUnsavedChanges(true);

      versionManager.current.markSchemaChanged(pageId, schema, getActor());

      if (autoSaveTimeoutRef.current) {
        clearTimeout(autoSaveTimeoutRef.current);
      }

      autoSaveTimeoutRef.current = setTimeout(() => {
        saveDraft(schema, '自动保存').catch(console.error);
      }, 2000);
    },
    [pageId, saveDraft, getActor],
  );

  /**
   * Save immediately
   */
  const saveNow = useCallback(async () => {
    if (currentSchema && hasUnsavedChanges) {
      await saveDraft(currentSchema, '手动保存');
    }
  }, [currentSchema, hasUnsavedChanges, saveDraft]);

  /**
   * Clear error
   */
  const clearError = useCallback(() => {
    setError(null);
  }, []);

  /**
   * Refresh data
   */
  const refresh = useCallback(async () => {
    await Promise.all([loadCurrentDraft(), loadPublishedVersion()]);
  }, [loadCurrentDraft, loadPublishedVersion]);

  useEffect(() => {
    refresh();
  }, [refresh]);

  useEffect(() => {
    return () => {
      if (autoSaveTimeoutRef.current) {
        clearTimeout(autoSaveTimeoutRef.current);
      }
    };
  }, []);

  return {
    currentSchema,
    currentVersion,
    publishedVersion,
    loading,
    saving,
    error,
    hasUnsavedChanges,
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
 * CanvasSchema version list hook
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
 * CanvasSchema version comparison hook
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
 * Compare two schemas and return a list of differences
 */
function compareSchemas(schemaA: CanvasSchema, schemaB: CanvasSchema): any[] {
  const differences: any[] = [];

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

  const componentsA = schemaA.components || [];
  const componentsB = schemaB.components || [];

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

  if (JSON.stringify(schemaA.layout) !== JSON.stringify(schemaB.layout)) {
    differences.push({
      type: 'layout',
      path: 'layout',
      oldValue: schemaA.layout,
      newValue: schemaB.layout,
    });
  }

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
 * CanvasSchema auto-save hook
 */
export function usePageSchemaAutoSave(
  pageId: string,
  schema: CanvasSchema | null,
  enabled: boolean = true,
) {
  const { user } = useAuth();
  const [lastSaved, setLastSaved] = useState<Date | null>(null);
  const [autoSaving, setAutoSaving] = useState(false);

  const versionManager = useRef(getPageSchemaVersionManager());
  const timeoutRef = useRef<NodeJS.Timeout | null>(null);

  const performAutoSave = useCallback(async () => {
    if (!schema || !enabled) return;

    try {
      setAutoSaving(true);
      const actor = user?.email ?? user?.name ?? 'unknown';
      await versionManager.current.saveDraft(pageId, schema, actor, '自动保存');
      setLastSaved(new Date());
    } catch (error) {
      console.error('Auto-save failed:', error);
    } finally {
      setAutoSaving(false);
    }
  }, [pageId, schema, enabled, user]);

  useEffect(() => {
    if (!schema || !enabled) return;

    if (timeoutRef.current) {
      clearTimeout(timeoutRef.current);
    }

    timeoutRef.current = setTimeout(() => {
      performAutoSave();
    }, 2000);

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
