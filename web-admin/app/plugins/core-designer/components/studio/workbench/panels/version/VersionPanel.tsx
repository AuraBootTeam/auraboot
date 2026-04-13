/**
 * 版本管理面板组件
 *
 * 提供版本历史查看、创建、发布、回滚等功能的用户界面
 */

import React, { useState, useEffect, useCallback } from 'react';
import {
  Clock,
  GitBranch,
  Tag,
  Eye,
  Upload,
  RotateCcw,
  Plus,
  Edit3,
  CheckCircle,
  Circle,
  AlertCircle,
  Archive,
  RefreshCw,
} from 'lucide-react';
import { getVersionManager } from '~/plugins/core-designer/components/studio/services/managers';
import {
  VersionStatus,
  VersionType,
  type Version,
  type CreateVersionRequest,
  type PublishVersionRequest,
} from '~/plugins/core-designer/components/studio/domain/metadata/types';
import type { PageSchema } from '~/plugins/core-designer/components/studio/domain/schema/types';

/**
 * 版本面板属性
 */
export interface VersionPanelProps {
  pageId: string;
  schema?: PageSchema;
  onVersionChange?: (version: Version) => void;
  onClose?: () => void;
  className?: string;
}

/**
 * 版本状态图标
 */
const StatusIcon = ({ status }: { status: VersionStatus }) => {
  switch (status) {
    case VersionStatus.published:
      return <CheckCircle className="h-4 w-4 text-green-500" />;
    case VersionStatus.draft:
      return <Edit3 className="h-4 w-4 text-blue-500" />;
    case VersionStatus.archived:
      return <Archive className="h-4 w-4 text-gray-500" />;
    default:
      return <Circle className="h-4 w-4 text-gray-400" />;
  }
};

/**
 * 版本类型标签
 */
const TypeBadge = ({ type }: { type: VersionType }) => {
  const colors: Record<VersionType, string> = {
    [VersionType.MAJOR]: 'bg-red-100 text-red-800',
    [VersionType.MINOR]: 'bg-yellow-100 text-yellow-800',
    [VersionType.PATCH]: 'bg-green-100 text-green-800',
    [VersionType.HOTFIX]: 'bg-purple-100 text-purple-800',
    [VersionType.SNAPSHOT]: 'bg-gray-100 text-gray-800',
  };

  return <span className={`rounded-full px-2 py-1 text-xs ${colors[type]}`}>{type}</span>;
};

/**
 * 版本管理面板
 */
export function VersionPanel({
  pageId,
  schema,
  onVersionChange,
  onClose,
  className = '',
}: VersionPanelProps) {
  const [versions, setVersions] = useState<Version[]>([]);
  const [currentVersion, setCurrentVersion] = useState<Version | null>(null);
  const [publishedVersion, setPublishedVersion] = useState<Version | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // 创建版本对话框状态
  const [showCreateDialog, setShowCreateDialog] = useState(false);
  const [createForm, setCreateForm] = useState({
    type: 'patch' as VersionType,
    description: '',
    changelog: '',
  });

  // 发布对话框状态
  const [showPublishDialog, setShowPublishDialog] = useState(false);
  const [publishForm, setPublishForm] = useState({
    versionId: '',
    description: '',
  });

  const versionManager = getVersionManager();

  /**
   * 加载版本列表
   */
  const loadVersions = useCallback(async () => {
    try {
      setLoading(true);
      setError(null);

      const [versionList, current, published] = await Promise.all([
        versionManager.getVersions(pageId, {
          page: 1,
          size: 50,
          sortBy: 'createdAt',
          sortOrder: 'desc',
        }),
        versionManager.getCurrentVersion(pageId),
        versionManager.getPublishedVersion(pageId),
      ]);

      setVersions(versionList.versions);
      setCurrentVersion(current);
      setPublishedVersion(published);
    } catch (err) {
      setError(err instanceof Error ? err.message : '加载版本失败');
    } finally {
      setLoading(false);
    }
  }, [pageId, versionManager]);

  /**
   * 创建新版本
   */
  const handleCreateVersion = useCallback(async () => {
    try {
      setLoading(true);

      const schemaToSave = schema ?? currentVersion?.schema ?? publishedVersion?.schema;
      if (!schemaToSave) {
        throw new Error('当前没有可用于创建版本的 Schema');
      }

      const request: CreateVersionRequest = {
        schema: schemaToSave,
        type: createForm.type,
        description: createForm.description,
        changelog: createForm.changelog,
      };

      const newVersion = await versionManager.createVersion(pageId, request);

      setShowCreateDialog(false);
      setCreateForm({ type: VersionType.PATCH, description: '', changelog: '' });

      await loadVersions();
      onVersionChange?.(newVersion);
    } catch (err) {
      setError(err instanceof Error ? err.message : '创建版本失败');
    } finally {
      setLoading(false);
    }
  }, [
    pageId,
    schema,
    currentVersion,
    publishedVersion,
    createForm,
    versionManager,
    loadVersions,
    onVersionChange,
  ]);

  /**
   * 发布版本
   */
  const handlePublishVersion = useCallback(async () => {
    try {
      setLoading(true);

      const request: PublishVersionRequest = {
        versionId: publishForm.versionId,
        description: publishForm.description,
      };

      await versionManager.publishVersion(pageId, publishForm.versionId, request);

      setShowPublishDialog(false);
      setPublishForm({ versionId: '', description: '' });

      await loadVersions();
    } catch (err) {
      setError(err instanceof Error ? err.message : '发布版本失败');
    } finally {
      setLoading(false);
    }
  }, [pageId, publishForm, versionManager, loadVersions]);

  /**
   * 回滚到指定版本
   */
  const handleRollback = useCallback(
    async (versionId: string) => {
      if (!confirm('确定要回滚到此版本吗？这将创建一个新的版本。')) {
        return;
      }

      try {
        setLoading(true);

        const newVersion = await versionManager.rollbackVersion(pageId, {
          targetVersionId: versionId,
          description: `回滚到版本 ${versionId}`,
          createNewVersion: true,
        });

        await loadVersions();
        onVersionChange?.(newVersion);
      } catch (err) {
        setError(err instanceof Error ? err.message : '回滚失败');
      } finally {
        setLoading(false);
      }
    },
    [pageId, versionManager, loadVersions, onVersionChange],
  );

  /**
   * 切换到指定版本
   */
  const handleSwitchVersion = useCallback(
    async (version: Version) => {
      try {
        setLoading(true);

        // TODO: 实现版本切换逻辑
        setCurrentVersion(version);
        onVersionChange?.(version);
      } catch (err) {
        setError(err instanceof Error ? err.message : '切换版本失败');
      } finally {
        setLoading(false);
      }
    },
    [onVersionChange],
  );

  /**
   * 归档版本
   */
  const handleArchiveVersion = useCallback(
    async (versionId: string) => {
      if (!confirm('确定要归档此版本吗？')) {
        return;
      }

      try {
        setLoading(true);
        await versionManager.archiveVersion(versionId);
        await loadVersions();
      } catch (err) {
        setError(err instanceof Error ? err.message : '归档失败');
      } finally {
        setLoading(false);
      }
    },
    [versionManager, loadVersions],
  );

  // 初始化加载
  useEffect(() => {
    loadVersions();
  }, [loadVersions]);

  return (
    <div className={`flex h-full flex-col border-l border-gray-200 bg-white ${className}`}>
      {/* 头部 */}
      <div className="flex items-center justify-between border-b border-gray-200 p-4">
        <div className="flex items-center gap-2">
          <GitBranch className="h-5 w-5 text-gray-600" />
          <h3 className="font-medium text-gray-900">版本管理</h3>
        </div>

        <div className="flex items-center gap-2">
          <button
            onClick={() => setShowCreateDialog(true)}
            disabled={loading}
            className="flex items-center gap-1 rounded bg-blue-500 px-3 py-1.5 text-sm text-white hover:bg-blue-600 disabled:opacity-50"
          >
            <Plus className="h-4 w-4" />
            新建版本
          </button>

          {onClose && (
            <button onClick={onClose} className="rounded p-1.5 hover:bg-gray-100">
              ×
            </button>
          )}
        </div>
      </div>

      {/* 当前版本信息 */}
      {currentVersion && (
        <div className="border-b border-gray-200 bg-blue-50 p-4">
          <div className="flex items-center justify-between">
            <div>
              <div className="flex items-center gap-2">
                <StatusIcon status={currentVersion.status} />
                <span className="font-medium">当前版本: {currentVersion.version}</span>
                <TypeBadge type={currentVersion.type} />
              </div>
              <p className="mt-1 text-sm text-gray-600">{currentVersion.description}</p>
            </div>

            {currentVersion.status === 'draft' && (
              <button
                onClick={() => {
                  setPublishForm({ versionId: currentVersion.id, description: '' });
                  setShowPublishDialog(true);
                }}
                className="flex items-center gap-1 rounded bg-green-500 px-3 py-1.5 text-sm text-white hover:bg-green-600"
              >
                <Upload className="h-4 w-4" />
                发布
              </button>
            )}
          </div>
        </div>
      )}

      {/* 已发布版本信息 */}
      {publishedVersion && publishedVersion.id !== currentVersion?.id && (
        <div className="border-b border-gray-200 bg-green-50 p-4">
          <div className="flex items-center gap-2">
            <CheckCircle className="h-4 w-4 text-green-500" />
            <span className="font-medium">已发布: {publishedVersion.version}</span>
            <TypeBadge type={publishedVersion.type} />
          </div>
          <p className="mt-1 text-sm text-gray-600">{publishedVersion.description}</p>
        </div>
      )}

      {/* 错误提示 */}
      {error && (
        <div className="border-b border-red-200 bg-red-50 p-4">
          <div className="flex items-center gap-2 text-red-700">
            <AlertCircle className="h-4 w-4" />
            <span className="text-sm">{error}</span>
          </div>
        </div>
      )}

      {/* 版本列表 */}
      <div className="flex-1 overflow-y-auto">
        {loading && versions.length === 0 ? (
          <div className="flex items-center justify-center p-8">
            <RefreshCw className="h-6 w-6 animate-spin text-gray-400" />
          </div>
        ) : (
          <div className="divide-y divide-gray-200">
            {versions.map((version) => (
              <div key={version.id} className="p-4 hover:bg-gray-50">
                <div className="flex items-start justify-between">
                  <div className="flex-1">
                    <div className="mb-2 flex items-center gap-2">
                      <StatusIcon status={version.status} />
                      <span className="font-medium">{version.version}</span>
                      <TypeBadge type={version.type} />

                      {version.tags && version.tags.length > 0 && (
                        <div className="flex gap-1">
                          {version.tags.map((tag) => (
                            <span
                              key={tag}
                              className="rounded bg-gray-100 px-1.5 py-0.5 text-xs text-gray-600"
                            >
                              <Tag className="mr-1 inline h-3 w-3" />
                              {tag}
                            </span>
                          ))}
                        </div>
                      )}
                    </div>

                    <p className="mb-2 text-sm text-gray-600">{version.description}</p>

                    <div className="flex items-center gap-4 text-xs text-gray-500">
                      <span className="flex items-center gap-1">
                        <Clock className="h-3 w-3" />
                        {new Date(version.createdAt).toLocaleString()}
                      </span>
                      <span>by {version.createdBy}</span>
                      {version.publishedAt && (
                        <span className="flex items-center gap-1">
                          <CheckCircle className="h-3 w-3" />
                          {new Date(version.publishedAt).toLocaleString()}
                        </span>
                      )}
                    </div>
                  </div>

                  <div className="ml-4 flex items-center gap-1">
                    {/* 预览 */}
                    <button
                      onClick={() => handleSwitchVersion(version)}
                      className="rounded p-1.5 hover:bg-gray-100"
                      title="切换到此版本"
                    >
                      <Eye className="h-4 w-4 text-gray-600" />
                    </button>

                    {/* 回滚 */}
                    {version.status !== 'archived' && (
                      <button
                        onClick={() => handleRollback(version.id)}
                        className="rounded p-1.5 hover:bg-gray-100"
                        title="回滚到此版本"
                      >
                        <RotateCcw className="h-4 w-4 text-gray-600" />
                      </button>
                    )}

                    {/* 归档 */}
                    {version.status !== 'archived' && version.status !== 'published' && (
                      <button
                        onClick={() => handleArchiveVersion(version.id)}
                        className="rounded p-1.5 hover:bg-gray-100"
                        title="归档版本"
                      >
                        <Archive className="h-4 w-4 text-gray-600" />
                      </button>
                    )}
                  </div>
                </div>

                {/* 变更日志 */}
                {version.changelog && (
                  <div className="mt-3 rounded bg-gray-50 p-3 text-sm">
                    <div className="mb-1 font-medium text-gray-700">变更日志:</div>
                    <div className="whitespace-pre-wrap text-gray-600">{version.changelog}</div>
                  </div>
                )}
              </div>
            ))}
          </div>
        )}
      </div>

      {/* 创建版本对话框 */}
      {showCreateDialog && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50">
          <div className="w-96 max-w-full rounded-lg bg-white p-6">
            <h3 className="mb-4 text-lg font-medium">创建新版本</h3>

            <div className="space-y-4">
              <div>
                <label className="mb-1 block text-sm font-medium text-gray-700">版本类型</label>
                <select
                  value={createForm.type}
                  onChange={(e) =>
                    setCreateForm((prev) => ({
                      ...prev,
                      type: e.target.value as VersionType,
                    }))
                  }
                  className="w-full rounded border border-gray-300 px-3 py-2"
                >
                  <option value="patch">补丁版本 (Patch)</option>
                  <option value="minor">次要版本 (Minor)</option>
                  <option value="major">主要版本 (Major)</option>
                  <option value="snapshot">快照版本 (Snapshot)</option>
                </select>
              </div>

              <div>
                <label className="mb-1 block text-sm font-medium text-gray-700">版本描述</label>
                <input
                  type="text"
                  value={createForm.description}
                  onChange={(e) =>
                    setCreateForm((prev) => ({
                      ...prev,
                      description: e.target.value,
                    }))
                  }
                  placeholder="简要描述此版本的变更..."
                  className="w-full rounded border border-gray-300 px-3 py-2"
                />
              </div>

              <div>
                <label className="mb-1 block text-sm font-medium text-gray-700">变更日志</label>
                <textarea
                  value={createForm.changelog}
                  onChange={(e) =>
                    setCreateForm((prev) => ({
                      ...prev,
                      changelog: e.target.value,
                    }))
                  }
                  placeholder="详细的变更记录..."
                  rows={4}
                  className="w-full rounded border border-gray-300 px-3 py-2"
                />
              </div>
            </div>

            <div className="mt-6 flex justify-end gap-2">
              <button
                onClick={() => setShowCreateDialog(false)}
                className="rounded px-4 py-2 text-gray-600 hover:bg-gray-100"
              >
                取消
              </button>
              <button
                onClick={handleCreateVersion}
                disabled={loading || !createForm.description}
                className="rounded bg-blue-500 px-4 py-2 text-white hover:bg-blue-600 disabled:opacity-50"
              >
                创建版本
              </button>
            </div>
          </div>
        </div>
      )}

      {/* 发布版本对话框 */}
      {showPublishDialog && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50">
          <div className="w-96 max-w-full rounded-lg bg-white p-6">
            <h3 className="mb-4 text-lg font-medium">发布版本</h3>

            <div className="space-y-4">
              <div>
                <label className="mb-1 block text-sm font-medium text-gray-700">发布说明</label>
                <textarea
                  value={publishForm.description}
                  onChange={(e) =>
                    setPublishForm((prev) => ({
                      ...prev,
                      description: e.target.value,
                    }))
                  }
                  placeholder="发布说明（可选）..."
                  rows={3}
                  className="w-full rounded border border-gray-300 px-3 py-2"
                />
              </div>
            </div>

            <div className="mt-6 flex justify-end gap-2">
              <button
                onClick={() => setShowPublishDialog(false)}
                className="rounded px-4 py-2 text-gray-600 hover:bg-gray-100"
              >
                取消
              </button>
              <button
                onClick={handlePublishVersion}
                disabled={loading}
                className="rounded bg-green-500 px-4 py-2 text-white hover:bg-green-600 disabled:opacity-50"
              >
                发布
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
