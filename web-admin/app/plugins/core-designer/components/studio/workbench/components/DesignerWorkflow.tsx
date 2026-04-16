/**
 * 设计器工作流集成组件
 *
 * 集成版本管理、自动保存、协作编辑、预览发布等完整工作流
 */

import React, { useState, useCallback, useRef } from 'react';
import {
  CommandToolbar,
  useCommandShortcuts,
} from '~/plugins/core-designer/components/studio/workbench/components/toolbar/CommandToolbar';
import {
  MultiSelectManager,
  BatchOperationToolbar,
} from '~/plugins/core-designer/components/studio/workbench/components/system/MultiSelectManager';
import {
  CollaborationProvider,
  useCollaboration,
} from '~/plugins/core-designer/components/studio/services/collaboration/CollaborationProvider';
import { ConflictList } from '~/plugins/core-designer/components/studio/services/collaboration/ConflictResolver';
import { UserCursors } from '~/plugins/core-designer/components/studio/services/collaboration/UserCursor';
import { VersionPanel } from '~/plugins/core-designer/components/studio/workbench/panels/version/VersionPanel';
import { AutoSave } from '~/plugins/core-designer/components/studio/workbench/components/system/AutoSave';
import { getVersionManager } from '~/plugins/core-designer/components/studio/services/managers';
import { useSchemaIO } from '~/plugins/core-designer/components/studio/hooks/workbench/useSchemaIO';
import { notificationService } from '~/plugins/core-designer/components/studio/services/workflow/notifications';
import type { CanvasSchema } from '~/plugins/core-designer/components/studio/workbench/canvas/types';

/**
 * 设计器工作流属性
 */
export interface DesignerWorkflowProps {
  /** 页面ID */
  pageId: string;
  /** 当前Schema */
  schema: CanvasSchema;
  /** Schema变更回调 */
  onSchemaChange: (schema: CanvasSchema) => void;
  /** 发布回调 */
  onPublish?: () => Promise<void>;
  /** 预览模式 */
  previewMode?: boolean;
  /** 是否只读 */
  readonly?: boolean;
  /** 协作配置 */
  collaborationConfig?: {
    enabled: boolean;
    websocketUrl?: string;
    userId: string;
    userName: string;
    userAvatar?: string;
  };
  /** 子组件 */
  children: React.ReactNode;
}

/**
 * 设计器工作流组件
 */
export const DesignerWorkflow: React.FC<DesignerWorkflowProps> = ({
  pageId,
  schema,
  onSchemaChange,
  onPublish,
  previewMode = false,
  readonly = false,
  collaborationConfig,
  children,
}) => {
  const [selectedIds, setSelectedIds] = useState<string[]>([]);
  const [showVersionPanel, setShowVersionPanel] = useState(false);
  const [showPreview, setShowPreview] = useState(false);

  const containerRef = useRef<HTMLDivElement>(null);
  const versionManager = getVersionManager();

  // 启用命令快捷键
  useCommandShortcuts();

  // 监听Schema变更
  const handleSchemaChange = useCallback(
    (newSchema: CanvasSchema) => {
      onSchemaChange(newSchema);
    },
    [onSchemaChange],
  );

  // 发布页面
  const handlePublish = useCallback(async () => {
    if (onPublish) {
      await onPublish();
      return;
    }

    try {
      const version = await versionManager.getCurrentVersion(pageId);
      if (version) {
        await versionManager.publishVersion(pageId, version.id, {
          versionId: version.id,
          description: '发布页面',
        });
        notificationService.success('页面发布成功！');
      }
    } catch (error) {
      console.error('Publish failed:', error);
      notificationService.error('发布失败，请重试');
    }
  }, [onPublish, pageId, versionManager]);

  // 预览页面
  const handlePreview = useCallback(() => {
    setShowPreview(true);
  }, []);

  const { exportSchema, importSchema } = useSchemaIO({
    pageId,
    onSchemaImported: handleSchemaChange,
  });

  const handleExport = useCallback(async () => {
    try {
      await exportSchema();
    } catch (error) {
      console.error('[DesignerWorkflow] Export failed:', error);
      notificationService.error('导出失败，请重试');
    }
  }, [exportSchema]);

  // 交换组件位置
  const handleSwapComponents = useCallback(() => {
    if (selectedIds.length !== 2) {
      notificationService.info('请选择两个组件进行交换');
      return;
    }

    const [id1, id2] = selectedIds;
    const comp1 = (schema.components || []).find((c) => c.id === id1);
    const comp2 = (schema.components || []).find((c) => c.id === id2);

    if (!comp1 || !comp2 || !comp1.position || !comp2.position) return;

    const updatedComponents = (schema.components || []).map((comp) => {
      if (comp.id === id1) return { ...comp, position: comp2.position };
      if (comp.id === id2) return { ...comp, position: comp1.position };
      return comp;
    });

    onSchemaChange({ ...schema, components: updatedComponents });
    notificationService.success('组件位置已交换');
  }, [selectedIds, schema, onSchemaChange]);

  // 导入页面
  const handleImport = useCallback(
    async (event: React.ChangeEvent<HTMLInputElement>) => {
      const file = event.target.files?.[0];
      if (!file) return;

      try {
        await importSchema(file);
        notificationService.success('导入成功！');
      } catch (error) {
        console.error('Import failed:', error);
        notificationService.error('导入失败，请检查文件格式');
      } finally {
        event.target.value = '';
      }
    },
    [importSchema],
  );

  const workflowContent = (
    <div className="flex h-full flex-col">
      {/* 顶部工具栏已合并到 DesignerToolbar，此处隐藏 */}

      {/* 主要内容区域 */}
      <div className="relative flex-1 overflow-hidden">
        {/* 多选管理器 */}
        {!previewMode && !readonly ? (
          <MultiSelectManager
            containerRef={containerRef as React.RefObject<HTMLElement>}
            selectedIds={selectedIds}
            onSelectionChange={setSelectedIds}
            enableBoxSelect={true}
            enableMultiSelect={true}
          >
            <div ref={containerRef} className="relative h-full w-full">
              {children}

              {/* 用户光标 */}
              {collaborationConfig?.enabled && (
                <CollaborationCursors containerRef={containerRef as React.RefObject<HTMLElement>} />
              )}
            </div>
          </MultiSelectManager>
        ) : (
          <div ref={containerRef} className="h-full w-full">
            {children}
          </div>
        )}

        {/* 批量操作工具栏 */}
        {!previewMode && !readonly && (
          <BatchOperationToolbar
            selectedIds={selectedIds}
            onClearSelection={() => setSelectedIds([])}
          />
        )}

        {/* 协作冲突提示 */}
        {collaborationConfig?.enabled && <ConflictListContainer />}
      </div>

      {/* 版本管理面板 */}
      {showVersionPanel && (
        <VersionPanel pageId={pageId} onClose={() => setShowVersionPanel(false)} />
      )}

      {/* 预览模态框 */}
      {showPreview && <PreviewModal schema={schema} onClose={() => setShowPreview(false)} />}
    </div>
  );

  // 如果启用协作，包装在协作提供者中
  if (collaborationConfig?.enabled) {
    return (
      <CollaborationProvider
        websocketUrl={collaborationConfig.websocketUrl ?? 'ws://localhost:3001'}
        userId={collaborationConfig.userId}
        userName={collaborationConfig.userName}
        userAvatar={collaborationConfig.userAvatar}
      >
        <CollaborationSessionBridge pageId={pageId} />
        {workflowContent}
      </CollaborationProvider>
    );
  }

  return workflowContent;
};

/**
 * 协作冲突列表容器
 */
const ConflictListContainer: React.FC = () => {
  const { conflicts, resolveConflict } = useCollaboration();

  return (
    <div className="absolute top-4 right-4 z-40 max-w-md">
      <ConflictList conflicts={conflicts} onResolveConflict={resolveConflict} />
    </div>
  );
};

const CollaborationSessionBridge: React.FC<{ pageId: string }> = ({ pageId }) => {
  const { connect, disconnect } = useCollaboration();

  React.useEffect(() => {
    connect(pageId).catch((error) => {
      console.error('Failed to connect collaboration session:', error);
    });
    return () => disconnect();
  }, [connect, disconnect, pageId]);

  return null;
};

const CollaborationCursors: React.FC<{ containerRef: React.RefObject<HTMLElement> }> = ({
  containerRef,
}) => {
  const { cursors, onlineUsers, currentUser } = useCollaboration();
  const userMap = React.useMemo(
    () => new Map(onlineUsers.map((user) => [user.id, user])),
    [onlineUsers],
  );

  if (!currentUser) {
    return null;
  }

  return (
    <UserCursors
      cursors={cursors}
      users={userMap}
      containerRef={containerRef}
      currentUserId={currentUser.id}
    />
  );
};

/**
 * 预览模态框属性
 */
interface PreviewModalProps {
  schema: CanvasSchema;
  onClose: () => void;
}

/**
 * 预览模态框组件
 */
const PreviewModal: React.FC<PreviewModalProps> = ({ schema, onClose }) => {
  return (
    <div className="bg-opacity-50 fixed inset-0 z-50 flex items-center justify-center bg-black">
      <div className="flex h-full max-h-[90vh] w-full max-w-6xl flex-col rounded-lg bg-white shadow-xl">
        {/* 头部 */}
        <div className="flex items-center justify-between border-b border-gray-200 p-4">
          <h2 className="text-lg font-semibold text-gray-900">页面预览</h2>
          <div className="flex items-center space-x-3">
            <span className="text-sm text-gray-500">{schema.title || '未命名页面'}</span>
            <button onClick={onClose} className="text-gray-400 hover:text-gray-600">
              <svg className="h-6 w-6" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M6 18L18 6M6 6l12 12"
                />
              </svg>
            </button>
          </div>
        </div>

        {/* 预览内容 */}
        <div className="flex-1 overflow-auto bg-gray-50 p-6">
          <div className="mx-auto max-w-4xl rounded-lg bg-white p-6 shadow-sm">
            {/* 这里应该渲染实际的页面内容 */}
            <div className="text-center text-gray-500">
              <svg
                className="mx-auto mb-4 h-16 w-16 text-gray-300"
                fill="none"
                stroke="currentColor"
                viewBox="0 0 24 24"
              >
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M15 12a3 3 0 11-6 0 3 3 0 016 0z"
                />
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z"
                />
              </svg>
              <p className="mb-2 text-lg font-medium">页面预览</p>
              <p className="text-sm">这里将显示根据 Schema 渲染的实际页面内容</p>

              {/* Schema 信息 */}
              <div className="mt-6 text-left">
                <h3 className="mb-2 text-sm font-medium text-gray-700">Schema 信息:</h3>
                <pre className="max-h-40 overflow-auto rounded bg-gray-100 p-3 text-xs">
                  {JSON.stringify(schema, null, 2)}
                </pre>
              </div>
            </div>
          </div>
        </div>

        {/* 底部 */}
        <div className="border-t border-gray-200 bg-gray-50 p-4">
          <div className="flex justify-end">
            <button
              onClick={onClose}
              className="rounded-md border border-gray-300 bg-white px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50"
            >
              关闭预览
            </button>
          </div>
        </div>
      </div>
    </div>
  );
};
