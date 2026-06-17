/**
 * 版本详情组件
 *
 * 显示模型的版本历史、版本对比和回滚功能
 *
 * 功能特性:
 * - 版本历史列表展示
 * - 版本详情查看
 * - 版本对比功能
 * - 版本回滚功能
 *
 * 需求: 12.1-12.9
 */

import React, { useState, useCallback, useEffect } from 'react';
import type { MetaModelDTO, ModelVersion, VersionDiff } from '~/types/model';
import { modelService } from '~/shared/services/modelService';
import { useToastContext } from '~/contexts/ToastContext';

/**
 * 版本详情Props
 */
interface VersionDetailProps {
  /** 是否显示 */
  visible: boolean;
  /** Model信息 */
  model: MetaModelDTO | null;
  /** 关闭回调 */
  onClose: () => void;
  /** 回滚成功回调 */
  onRollbackSuccess?: (newModel: MetaModelDTO) => void;
}

/**
 * 版本详情组件
 */
export function VersionDetail({ visible, model, onClose, onRollbackSuccess }: VersionDetailProps) {
  const { showSuccessToast, showErrorToast, showInfoToast } = useToastContext();

  // 版本历史列表
  const [versions, setVersions] = useState<ModelVersion[]>([]);

  // 选中的版本（用于对比）
  const [selectedVersions, setSelectedVersions] = useState<number[]>([]);

  // 版本对比结果
  const [versionDiff, setVersionDiff] = useState<VersionDiff | null>(null);

  // 是否显示对比结果
  const [showDiff, setShowDiff] = useState(false);

  // 加载状态
  const [loading, setLoading] = useState(false);

  // 回滚确认对话框
  const [rollbackConfirm, setRollbackConfirm] = useState<{
    show: boolean;
    version: number;
  }>({ show: false, version: 0 });

  /**
   * 加载版本历史
   */
  useEffect(() => {
    if (visible && model) {
      loadVersionHistory();
    }
  }, [visible, model]);

  /**
   * 加载版本历史
   */
  const loadVersionHistory = useCallback(async () => {
    if (!model) return;

    setLoading(true);
    try {
      const history = await modelService.getVersionHistory(model.code);
      setVersions(history);
    } catch (error) {
      console.error('Failed to load version history:', error);
      showErrorToast('加载版本历史失败');
    } finally {
      setLoading(false);
    }
  }, [model]);

  /**
   * 选择版本（用于对比）
   */
  const toggleVersionSelection = useCallback((version: number) => {
    setSelectedVersions((prev) => {
      if (prev.includes(version)) {
        return prev.filter((v) => v !== version);
      } else {
        // 最多选择2个版本
        if (prev.length >= 2) {
          return [prev[1], version];
        }
        return [...prev, version];
      }
    });
  }, []);

  /**
   * 对比版本
   */
  const handleCompareVersions = useCallback(async () => {
    if (!model || selectedVersions.length !== 2) {
      showInfoToast('请选择两个版本进行对比');
      return;
    }

    setLoading(true);
    try {
      const [v1, v2] = selectedVersions.sort((a, b) => a - b);
      const diff = await modelService.compareVersions(model.code, v1, v2);
      setVersionDiff(diff);
      setShowDiff(true);
    } catch (error) {
      console.error('Failed to compare versions:', error);
      showErrorToast('版本对比失败');
    } finally {
      setLoading(false);
    }
  }, [model, selectedVersions]);

  /**
   * 显示回滚确认对话框
   */
  const showRollbackConfirm = useCallback((version: number) => {
    setRollbackConfirm({ show: true, version });
  }, []);

  /**
   * 执行回滚
   */
  const handleRollback = useCallback(async () => {
    if (!model || !rollbackConfirm.version) return;

    setLoading(true);
    try {
      const newModel = await modelService.rollbackToVersion(model.code, rollbackConfirm.version);

      showSuccessToast('回滚成功');
      setRollbackConfirm({ show: false, version: 0 });

      // 重新加载版本历史
      await loadVersionHistory();

      // 通知父组件
      if (onRollbackSuccess) {
        onRollbackSuccess(newModel);
      }
    } catch (error) {
      console.error('Failed to rollback version:', error);
      showErrorToast('回滚失败');
    } finally {
      setLoading(false);
    }
  }, [model, rollbackConfirm.version, loadVersionHistory, onRollbackSuccess]);

  /**
   * 格式化变更类型
   */
  const formatChangeType = useCallback((type: string): string => {
    const typeMap: Record<string, string> = {
      added: '新增',
      modified: '修改',
      removed: '删除',
    };
    return typeMap[type] || type;
  }, []);

  /**
   * 获取变更类型样式
   */
  const getChangeTypeStyle = useCallback((type: string): string => {
    const styleMap: Record<string, string> = {
      added: 'bg-green-100 text-green-800',
      modified: 'bg-blue-100 text-blue-800',
      removed: 'bg-red-100 text-red-800',
    };
    return styleMap[type] || 'bg-subtle text-text';
  }, []);

  /**
   * 格式化值
   */
  const formatValue = useCallback((value: any): string => {
    if (value === null || value === undefined) {
      return '-';
    }
    if (typeof value === 'object') {
      return JSON.stringify(value, null, 2);
    }
    return String(value);
  }, []);

  if (!visible || !model) return null;

  return (
    <div className="fixed inset-0 z-50 overflow-y-auto">
      {/* 遮罩层 */}
      <div className="bg-opacity-50 fixed inset-0 bg-black" onClick={onClose} />

      {/* 对话框 */}
      <div className="flex min-h-screen items-center justify-center p-4">
        <div className="rounded-card bg-panel relative flex max-h-[90vh] w-full max-w-6xl flex-col shadow-xl">
          {/* 标题栏 */}
          <div className="border-border border-b px-6 py-4">
            <h2 className="text-text text-lg font-semibold">版本历史</h2>
            <p className="text-text-2 mt-1 text-sm">
              模型 <span className="text-accent font-mono">{model.code}</span> 的版本历史记录
            </p>
          </div>

          {/* 工具栏 */}
          {!showDiff && (
            <div className="border-border bg-subtle border-b px-6 py-3">
              <div className="flex items-center justify-between">
                <div className="text-text-2 text-sm">
                  {selectedVersions.length > 0 && (
                    <span>已选择 {selectedVersions.length} 个版本</span>
                  )}
                </div>
                <button
                  onClick={handleCompareVersions}
                  disabled={selectedVersions.length !== 2 || loading}
                  className="rounded-control bg-accent hover:bg-accent-hover px-4 py-2 text-white disabled:cursor-not-allowed disabled:opacity-50"
                >
                  对比版本
                </button>
              </div>
            </div>
          )}

          {/* 内容区域 */}
          <div className="flex-1 overflow-y-auto px-6 py-4">
            {loading ? (
              <div className="flex items-center justify-center py-12">
                <div className="rounded-pill border-accent h-8 w-8 animate-spin border-b-2"></div>
                <span className="text-text-2 ml-3">加载中...</span>
              </div>
            ) : showDiff && versionDiff ? (
              // 版本对比视图
              <div>
                <div className="mb-4 flex items-center justify-between">
                  <h3 className="text-text text-base font-medium">
                    版本对比: v{versionDiff.fromVersion} → v{versionDiff.toVersion}
                  </h3>
                  <button
                    onClick={() => {
                      setShowDiff(false);
                      setVersionDiff(null);
                    }}
                    className="text-accent hover:text-accent text-sm"
                  >
                    返回版本列表
                  </button>
                </div>

                {versionDiff.changes.length === 0 ? (
                  <div className="text-text-2 py-12 text-center">两个版本之间没有差异</div>
                ) : (
                  <div className="overflow-x-auto">
                    <table className="divide-border min-w-full divide-y">
                      <thead className="bg-subtle">
                        <tr>
                          <th className="text-text-2 px-6 py-3 text-left text-xs font-medium uppercase">
                            字段
                          </th>
                          <th className="text-text-2 px-6 py-3 text-left text-xs font-medium uppercase">
                            变更类型
                          </th>
                          <th className="text-text-2 px-6 py-3 text-left text-xs font-medium uppercase">
                            旧值
                          </th>
                          <th className="text-text-2 px-6 py-3 text-left text-xs font-medium uppercase">
                            新值
                          </th>
                        </tr>
                      </thead>
                      <tbody className="divide-border bg-panel divide-y">
                        {versionDiff.changes.map((change, index) => (
                          <tr key={index}>
                            <td className="text-text px-6 py-4 text-sm font-medium whitespace-nowrap">
                              {change.field}
                            </td>
                            <td className="px-6 py-4 text-sm whitespace-nowrap">
                              <span
                                className={`rounded-pill px-2 py-1 text-xs font-medium ${getChangeTypeStyle(change.changeType)}`}
                              >
                                {formatChangeType(change.changeType)}
                              </span>
                            </td>
                            <td className="text-text-2 px-6 py-4 text-sm">
                              <pre className="whitespace-pre-wrap">
                                {formatValue(change.oldValue)}
                              </pre>
                            </td>
                            <td className="text-text px-6 py-4 text-sm">
                              <pre className="whitespace-pre-wrap">
                                {formatValue(change.newValue)}
                              </pre>
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
              </div>
            ) : (
              // 版本列表视图
              <div>
                {versions.length === 0 ? (
                  <div className="text-text-2 py-12 text-center">暂无版本历史</div>
                ) : (
                  <div className="space-y-4">
                    {versions.map((version) => (
                      <div
                        key={version.version}
                        className={`rounded-card border p-4 ${
                          selectedVersions.includes(version.version)
                            ? 'bg-accent-weak border-accent'
                            : 'border-border hover:border-border-strong'
                        }`}
                      >
                        <div className="flex items-start justify-between">
                          <div className="flex flex-1 items-start gap-4">
                            {/* 选择框 */}
                            <input
                              type="checkbox"
                              checked={selectedVersions.includes(version.version)}
                              onChange={() => toggleVersionSelection(version.version)}
                              className="border-border-strong text-accent focus-visible:shadow-focus mt-1 h-4 w-4 rounded focus:outline-none"
                            />

                            {/* 版本信息 */}
                            <div className="flex-1">
                              <div className="flex items-center gap-3">
                                <h4 className="text-text text-base font-medium">
                                  版本 {version.version}
                                </h4>
                                {version.isCurrent && (
                                  <span className="rounded-pill bg-green-100 px-2 py-1 text-xs font-medium text-green-800">
                                    当前版本
                                  </span>
                                )}
                                <span
                                  className={`rounded-pill px-2 py-1 text-xs font-medium ${
                                    version.status === 'published'
                                      ? 'bg-blue-100 text-blue-800'
                                      : version.status === 'draft'
                                        ? 'bg-subtle text-text'
                                        : 'text-status-amber bg-yellow-100'
                                  }`}
                                >
                                  {version.status === 'published'
                                    ? '已发布'
                                    : version.status === 'draft'
                                      ? '草稿'
                                      : '已归档'}
                                </span>
                              </div>

                              {version.versionNote && (
                                <p className="text-text-2 mt-2 text-sm">{version.versionNote}</p>
                              )}

                              <div className="text-text-2 mt-2 flex items-center gap-4 text-xs">
                                <span>
                                  创建时间: {new Date(version.createdAt).toLocaleString('zh-CN')}
                                </span>
                                <span>创建人: {version.createdBy}</span>
                              </div>
                            </div>
                          </div>

                          {/* 操作按钮 */}
                          <div className="flex items-center gap-2">
                            {!version.isCurrent && (
                              <button
                                onClick={() => showRollbackConfirm(version.version)}
                                disabled={loading}
                                className="rounded-control text-accent hover:bg-accent-weak hover:text-accent px-3 py-1 text-sm disabled:opacity-50"
                              >
                                回滚
                              </button>
                            )}
                          </div>
                        </div>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            )}
          </div>

          {/* 底部按钮 */}
          <div className="border-border flex justify-end border-t px-6 py-4">
            <button
              onClick={onClose}
              className="rounded-control border-border-strong text-text-2 hover:bg-subtle border px-4 py-2"
            >
              关闭
            </button>
          </div>
        </div>
      </div>

      {/* 回滚确认对话框 */}
      {rollbackConfirm.show && (
        <div className="fixed inset-0 z-[60] overflow-y-auto">
          <div className="bg-opacity-50 fixed inset-0 bg-black" />
          <div className="flex min-h-screen items-center justify-center p-4">
            <div className="rounded-card bg-panel relative w-full max-w-md p-6 shadow-xl">
              <h3 className="text-text mb-4 text-lg font-semibold">确认回滚</h3>
              <p className="text-text-2 mb-6 text-sm">
                确认回滚到版本 {rollbackConfirm.version}？此操作将创建一个新版本。
              </p>
              <div className="flex justify-end gap-3">
                <button
                  onClick={() => setRollbackConfirm({ show: false, version: 0 })}
                  disabled={loading}
                  className="rounded-control border-border-strong text-text-2 hover:bg-subtle border px-4 py-2 disabled:opacity-50"
                >
                  取消
                </button>
                <button
                  onClick={handleRollback}
                  disabled={loading}
                  className="rounded-control bg-accent hover:bg-accent-hover px-4 py-2 text-white disabled:opacity-50"
                >
                  {loading ? '处理中...' : '确认回滚'}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
