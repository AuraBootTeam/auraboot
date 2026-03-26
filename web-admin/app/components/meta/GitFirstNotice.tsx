/**
 * Git-First工作流提示组件
 *
 * 显示Git-First模式的工作流说明和Release状态跟踪
 *
 * 功能特性:
 * - Git-First工作流说明
 * - Release状态实时跟踪
 * - 工作流步骤展示
 * - 错误信息显示
 *
 * 需求: 11.1-11.8
 */

import React, { useState, useEffect, useCallback } from 'react';
import type { ReleaseStatus } from '~/types/model';

/**
 * Git-First提示Props
 */
interface GitFirstNoticeProps {
  /** 是否显示 */
  visible: boolean;
  /** Release ID（用于跟踪状态） */
  releaseId?: number;
  /** 关闭回调 */
  onClose: () => void;
}

/**
 * Release状态信息
 */
interface ReleaseStatusInfo {
  releaseId: number;
  status: ReleaseStatus;
  message?: string;
  errorMessage?: string;
  createdAt: string;
  updatedAt: string;
}

/**
 * Git-First提示组件
 */
export function GitFirstNotice({ visible, releaseId, onClose }: GitFirstNoticeProps) {
  // Release状态
  const [releaseStatus, setReleaseStatus] = useState<ReleaseStatusInfo | null>(null);

  // 加载状态
  const [loading, setLoading] = useState(false);

  // 轮询定时器
  const [pollingTimer, setPollingTimer] = useState<NodeJS.Timeout | null>(null);

  /**
   * 加载Release状态
   */
  const loadReleaseStatus = useCallback(async () => {
    if (!releaseId) return;

    setLoading(true);
    try {
      // TODO: 调用API获取Release状态
      // const status = await gitReleaseService.getReleaseStatus(releaseId);
      // setReleaseStatus(status);

      // 模拟数据
      const mockStatus: ReleaseStatusInfo = {
        releaseId,
        status: 'generating',
        message: '正在生成DSL文件...',
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
      };
      setReleaseStatus(mockStatus);
    } catch (error) {
      console.error('Failed to load release status:', error);
    } finally {
      setLoading(false);
    }
  }, [releaseId]);

  /**
   * 初始化和轮询
   */
  useEffect(() => {
    if (visible && releaseId) {
      // 立即加载一次
      loadReleaseStatus();

      // 如果状态不是终态，开始轮询
      if (releaseStatus && !isTerminalStatus(releaseStatus.status)) {
        const timer = setInterval(() => {
          loadReleaseStatus();
        }, 3000); // 每3秒轮询一次

        setPollingTimer(timer);

        return () => {
          clearInterval(timer);
        };
      }
    }

    return () => {
      if (pollingTimer) {
        clearInterval(pollingTimer);
      }
    };
  }, [visible, releaseId, releaseStatus?.status, loadReleaseStatus]);

  /**
   * 判断是否为终态
   */
  const isTerminalStatus = useCallback((status: ReleaseStatus): boolean => {
    return status === 'published' || status === 'failed';
  }, []);

  /**
   * 获取状态显示文本
   */
  const getStatusText = useCallback((status: ReleaseStatus): string => {
    const statusMap: Record<ReleaseStatus, string> = {
      pending: '等待处理',
      generating: '生成中',
      validated: '已验证',
      projecting: '投影中',
      published: '已发布',
      failed: '失败',
    };
    return statusMap[status] || status;
  }, []);

  /**
   * 获取状态样式
   */
  const getStatusStyle = useCallback((status: ReleaseStatus): string => {
    const styleMap: Record<ReleaseStatus, string> = {
      pending: 'bg-gray-100 text-gray-800',
      generating: 'bg-blue-100 text-blue-800',
      validated: 'bg-green-100 text-green-800',
      projecting: 'bg-blue-100 text-blue-800',
      published: 'bg-green-100 text-green-800',
      failed: 'bg-red-100 text-red-800',
    };
    return styleMap[status] || 'bg-gray-100 text-gray-800';
  }, []);

  /**
   * 获取工作流步骤状态
   */
  const getStepStatus = useCallback(
    (
      stepStatus: ReleaseStatus,
      currentStatus: ReleaseStatus,
    ): 'completed' | 'active' | 'pending' => {
      const statusOrder: ReleaseStatus[] = [
        'pending',
        'generating',
        'validated',
        'projecting',
        'published',
      ];
      const stepIndex = statusOrder.indexOf(stepStatus);
      const currentIndex = statusOrder.indexOf(currentStatus);

      if (currentStatus === 'failed') {
        return stepIndex <= currentIndex ? 'completed' : 'pending';
      }

      if (stepIndex < currentIndex) return 'completed';
      if (stepIndex === currentIndex) return 'active';
      return 'pending';
    },
    [],
  );

  /**
   * 渲染工作流步骤
   */
  const renderWorkflowSteps = useCallback(() => {
    if (!releaseStatus) return null;

    const steps: Array<{ status: ReleaseStatus; label: string; description: string }> = [
      { status: 'pending', label: '等待处理', description: 'Release已创建，等待处理' },
      { status: 'generating', label: 'DSL生成', description: '生成DSL文件并提交到Git' },
      { status: 'validated', label: '验证完成', description: '验证DSL文件格式和依赖' },
      { status: 'projecting', label: '运行时投影', description: '将DSL投影到运行时表' },
      { status: 'published', label: '发布完成', description: 'Release已发布，可以使用' },
    ];

    return (
      <div className="space-y-4">
        {steps.map((step, index) => {
          const stepStatus = getStepStatus(step.status, releaseStatus.status);

          return (
            <div key={step.status} className="flex items-start gap-4">
              {/* 步骤图标 */}
              <div className="flex flex-col items-center">
                <div
                  className={`flex h-8 w-8 items-center justify-center rounded-full ${
                    stepStatus === 'completed'
                      ? 'bg-green-500 text-white'
                      : stepStatus === 'active'
                        ? 'bg-blue-500 text-white'
                        : 'bg-gray-200 text-gray-500'
                  }`}
                >
                  {stepStatus === 'completed' ? (
                    <svg className="h-5 w-5" fill="currentColor" viewBox="0 0 20 20">
                      <path
                        fillRule="evenodd"
                        d="M16.707 5.293a1 1 0 010 1.414l-8 8a1 1 0 01-1.414 0l-4-4a1 1 0 011.414-1.414L8 12.586l7.293-7.293a1 1 0 011.414 0z"
                        clipRule="evenodd"
                      />
                    </svg>
                  ) : stepStatus === 'active' ? (
                    <div className="h-4 w-4 animate-spin rounded-full border-b-2 border-white"></div>
                  ) : (
                    <span className="text-sm font-medium">{index + 1}</span>
                  )}
                </div>
                {index < steps.length - 1 && (
                  <div
                    className={`h-12 w-0.5 ${
                      stepStatus === 'completed' ? 'bg-green-500' : 'bg-gray-200'
                    }`}
                  />
                )}
              </div>

              {/* 步骤信息 */}
              <div className="flex-1 pb-8">
                <h4
                  className={`text-sm font-medium ${
                    stepStatus === 'active' ? 'text-blue-900' : 'text-gray-900'
                  }`}
                >
                  {step.label}
                </h4>
                <p className="mt-1 text-sm text-gray-500">{step.description}</p>
                {stepStatus === 'active' && releaseStatus.message && (
                  <p className="mt-2 text-sm text-blue-600">{releaseStatus.message}</p>
                )}
              </div>
            </div>
          );
        })}
      </div>
    );
  }, [releaseStatus, getStepStatus]);

  if (!visible) return null;

  return (
    <div className="fixed inset-0 z-50 overflow-y-auto">
      {/* 遮罩层 */}
      <div className="bg-opacity-50 fixed inset-0 bg-black" onClick={onClose} />

      {/* 对话框 */}
      <div className="flex min-h-screen items-center justify-center p-4">
        <div className="relative flex max-h-[90vh] w-full max-w-2xl flex-col rounded-lg bg-white shadow-xl">
          {/* 标题栏 */}
          <div className="border-b border-gray-200 px-6 py-4">
            <div className="flex items-center gap-3">
              <div className="flex h-10 w-10 items-center justify-center rounded-full bg-blue-100">
                <svg
                  className="h-6 w-6 text-blue-600"
                  fill="none"
                  stroke="currentColor"
                  viewBox="0 0 24 24"
                >
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={2}
                    d="M13 10V3L4 14h7v7l9-11h-7z"
                  />
                </svg>
              </div>
              <div>
                <h2 className="text-lg font-semibold text-gray-900">Git-First工作流</h2>
                <p className="text-sm text-gray-500">所有变更将通过Git流程处理</p>
              </div>
            </div>
          </div>

          {/* 内容区域 */}
          <div className="flex-1 overflow-y-auto px-6 py-4">
            {/* Git-First说明 */}
            <div className="mb-6 rounded-lg border border-blue-200 bg-blue-50 p-4">
              <h3 className="mb-2 text-sm font-medium text-blue-900">什么是Git-First模式？</h3>
              <p className="mb-3 text-sm text-blue-700">
                Git-First模式将Git作为唯一的数据源，所有模型定义的变更都会：
              </p>
              <ol className="list-inside list-decimal space-y-1 text-sm text-blue-700">
                <li>生成DSL文件并提交到Git仓库</li>
                <li>创建Release进行版本管理</li>
                <li>自动验证DSL格式和依赖关系</li>
                <li>投影到运行时表供系统使用</li>
                <li>支持快速回滚到任意历史版本</li>
              </ol>
            </div>

            {/* Release状态跟踪 */}
            {releaseId && (
              <div className="mb-6">
                <div className="mb-4 flex items-center justify-between">
                  <h3 className="text-base font-medium text-gray-900">Release状态跟踪</h3>
                  {releaseStatus && (
                    <span
                      className={`rounded-full px-3 py-1 text-sm font-medium ${getStatusStyle(releaseStatus.status)}`}
                    >
                      {getStatusText(releaseStatus.status)}
                    </span>
                  )}
                </div>

                {loading && !releaseStatus ? (
                  <div className="flex items-center justify-center py-8">
                    <div className="h-8 w-8 animate-spin rounded-full border-b-2 border-blue-600"></div>
                    <span className="ml-3 text-gray-600">加载中...</span>
                  </div>
                ) : releaseStatus ? (
                  <div>
                    {/* 工作流步骤 */}
                    {renderWorkflowSteps()}

                    {/* 错误信息 */}
                    {releaseStatus.status === 'failed' && releaseStatus.errorMessage && (
                      <div className="mt-4 rounded-lg border border-red-200 bg-red-50 p-4">
                        <h4 className="mb-2 text-sm font-medium text-red-900">处理失败</h4>
                        <p className="text-sm text-red-700">{releaseStatus.errorMessage}</p>
                      </div>
                    )}

                    {/* 成功提示 */}
                    {releaseStatus.status === 'published' && (
                      <div className="mt-4 rounded-lg border border-green-200 bg-green-50 p-4">
                        <h4 className="mb-2 text-sm font-medium text-green-900">发布成功</h4>
                        <p className="text-sm text-green-700">
                          模型已成功发布，现在可以在系统中使用了。
                        </p>
                      </div>
                    )}
                  </div>
                ) : (
                  <div className="py-8 text-center text-gray-500">无法加载Release状态</div>
                )}
              </div>
            )}

            {/* 注意事项 */}
            <div className="rounded-lg border border-yellow-200 bg-yellow-50 p-4">
              <h3 className="mb-2 text-sm font-medium text-yellow-900">注意事项</h3>
              <ul className="list-inside list-disc space-y-1 text-sm text-yellow-700">
                <li>Release处理通常需要几秒到几分钟时间</li>
                <li>处理期间请勿关闭此窗口</li>
                <li>如果处理失败，可以查看错误信息并重试</li>
                <li>所有变更都会记录完整的审计日志</li>
              </ul>
            </div>
          </div>

          {/* 底部按钮 */}
          <div className="flex justify-end gap-3 border-t border-gray-200 px-6 py-4">
            {releaseStatus && isTerminalStatus(releaseStatus.status) && (
              <button
                onClick={onClose}
                className="rounded-md bg-blue-600 px-4 py-2 text-white hover:bg-blue-700"
              >
                完成
              </button>
            )}
            {(!releaseStatus || !isTerminalStatus(releaseStatus.status)) && (
              <button
                onClick={onClose}
                className="rounded-md border border-gray-300 px-4 py-2 text-gray-700 hover:bg-gray-50"
              >
                后台运行
              </button>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
