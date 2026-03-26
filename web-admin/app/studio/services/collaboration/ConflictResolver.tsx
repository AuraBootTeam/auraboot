/**
 * 冲突解决器组件
 *
 * 处理协作编辑中的操作冲突
 */

import React, { useState, useCallback } from 'react';
import type {
  OperationConflict,
  CollaborationOperation,
} from '~/studio/services/collaboration/CollaborationManager';
import { useCollaboration } from '~/studio/services/collaboration/CollaborationProvider';

/**
 * 冲突解决器属性
 */
export interface ConflictResolverProps {
  conflict: OperationConflict;
  onResolve: (
    resolution: 'accept_local' | 'accept_remote' | 'merge' | 'manual',
    mergedData?: any,
  ) => void;
  onCancel: () => void;
}

/**
 * 冲突解决器组件
 */
export const ConflictResolver: React.FC<ConflictResolverProps> = ({
  conflict,
  onResolve,
  onCancel,
}) => {
  const [selectedResolution, setSelectedResolution] = useState<
    'accept_local' | 'accept_remote' | 'merge' | 'manual'
  >('accept_local');
  const [mergedData, setMergedData] = useState<any>(null);
  const [isResolving, setIsResolving] = useState(false);

  const handleResolve = useCallback(async () => {
    setIsResolving(true);
    try {
      await onResolve(selectedResolution, mergedData);
    } finally {
      setIsResolving(false);
    }
  }, [selectedResolution, mergedData, onResolve]);

  const renderOperationPreview = (operation: CollaborationOperation) => {
    return (
      <div className="rounded-lg border bg-gray-50 p-4">
        <div className="mb-2 flex items-center justify-between">
          <span className="text-sm font-medium text-gray-700">
            {operation.type.toUpperCase()} 操作
          </span>
          <span className="text-xs text-gray-500">{operation.timestamp.toLocaleString()}</span>
        </div>

        <div className="mb-2 text-sm text-gray-600">
          路径: <code className="rounded bg-gray-200 px-1">{operation.path}</code>
        </div>

        {operation.metadata?.description && (
          <div className="mb-2 text-sm text-gray-600">描述: {operation.metadata.description}</div>
        )}

        <div className="space-y-2">
          {operation.previousData && (
            <div>
              <div className="mb-1 text-xs font-medium text-red-600">原值:</div>
              <pre className="overflow-x-auto rounded bg-red-50 p-2 text-xs">
                {JSON.stringify(operation.previousData, null, 2)}
              </pre>
            </div>
          )}

          <div>
            <div className="mb-1 text-xs font-medium text-green-600">新值:</div>
            <pre className="overflow-x-auto rounded bg-green-50 p-2 text-xs">
              {JSON.stringify(operation.data, null, 2)}
            </pre>
          </div>
        </div>
      </div>
    );
  };

  const renderMergeEditor = () => {
    if (selectedResolution !== 'merge' && selectedResolution !== 'manual') {
      return null;
    }

    return (
      <div className="mt-4">
        <label className="mb-2 block text-sm font-medium text-gray-700">合并后的数据:</label>
        <textarea
          className="h-32 w-full rounded-md border border-gray-300 p-3 font-mono text-sm"
          value={mergedData ? JSON.stringify(mergedData, null, 2) : ''}
          onChange={(e) => {
            try {
              const parsed = JSON.parse(e.target.value);
              setMergedData(parsed);
            } catch {
              // 忽略解析错误，继续编辑
            }
          }}
          placeholder="请输入合并后的 JSON 数据..."
        />
      </div>
    );
  };

  return (
    <div className="bg-opacity-50 fixed inset-0 z-50 flex items-center justify-center bg-black">
      <div className="mx-4 max-h-[90vh] w-full max-w-4xl overflow-y-auto rounded-lg bg-white shadow-xl">
        {/* 头部 */}
        <div className="border-b border-gray-200 px-6 py-4">
          <div className="flex items-center justify-between">
            <h2 className="text-lg font-semibold text-gray-900">解决操作冲突</h2>
            <button onClick={onCancel} className="text-gray-400 hover:text-gray-600">
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

          <div className="mt-2 text-sm text-gray-600">
            <div className="flex items-center space-x-4">
              <span>
                冲突类型: <span className="font-medium">{conflict.conflictType}</span>
              </span>
              <span>
                操作数量: <span className="font-medium">{conflict.operations.length}</span>
              </span>
              <span>
                时间: <span className="font-medium">{conflict.timestamp.toLocaleString()}</span>
              </span>
            </div>
          </div>
        </div>

        {/* 内容 */}
        <div className="px-6 py-4">
          {/* 冲突描述 */}
          <div className="mb-6">
            <h3 className="mb-2 text-sm font-medium text-gray-700">冲突描述</h3>
            <p className="rounded-md bg-yellow-50 p-3 text-sm text-gray-600">
              {conflict.description}
            </p>
          </div>

          {/* 冲突操作列表 */}
          <div className="mb-6">
            <h3 className="mb-3 text-sm font-medium text-gray-700">冲突操作</h3>
            <div className="space-y-4">
              {conflict.operations.map((operation, index) => (
                <div key={operation.id}>
                  <div className="mb-2 text-sm font-medium text-gray-700">
                    操作 {index + 1} (用户: {operation.userId})
                  </div>
                  {renderOperationPreview(operation)}
                </div>
              ))}
            </div>
          </div>

          {/* 解决方案选择 */}
          <div className="mb-6">
            <h3 className="mb-3 text-sm font-medium text-gray-700">解决方案</h3>
            <div className="space-y-3">
              <label className="flex items-center">
                <input
                  type="radio"
                  name="resolution"
                  value="accept_local"
                  checked={selectedResolution === 'accept_local'}
                  onChange={(e) => setSelectedResolution(e.target.value as any)}
                  className="mr-3"
                />
                <div>
                  <div className="font-medium">接受本地更改</div>
                  <div className="text-sm text-gray-600">保留你的更改，忽略其他用户的更改</div>
                </div>
              </label>

              <label className="flex items-center">
                <input
                  type="radio"
                  name="resolution"
                  value="accept_remote"
                  checked={selectedResolution === 'accept_remote'}
                  onChange={(e) => setSelectedResolution(e.target.value as any)}
                  className="mr-3"
                />
                <div>
                  <div className="font-medium">接受远程更改</div>
                  <div className="text-sm text-gray-600">接受其他用户的更改，丢弃你的更改</div>
                </div>
              </label>

              <label className="flex items-center">
                <input
                  type="radio"
                  name="resolution"
                  value="merge"
                  checked={selectedResolution === 'merge'}
                  onChange={(e) => setSelectedResolution(e.target.value as any)}
                  className="mr-3"
                />
                <div>
                  <div className="font-medium">自动合并</div>
                  <div className="text-sm text-gray-600">尝试自动合并两个更改</div>
                </div>
              </label>

              <label className="flex items-center">
                <input
                  type="radio"
                  name="resolution"
                  value="manual"
                  checked={selectedResolution === 'manual'}
                  onChange={(e) => setSelectedResolution(e.target.value as any)}
                  className="mr-3"
                />
                <div>
                  <div className="font-medium">手动合并</div>
                  <div className="text-sm text-gray-600">手动编辑合并后的数据</div>
                </div>
              </label>
            </div>
          </div>

          {/* 合并编辑器 */}
          {renderMergeEditor()}
        </div>

        {/* 底部按钮 */}
        <div className="flex justify-end space-x-3 border-t border-gray-200 px-6 py-4">
          <button
            onClick={onCancel}
            className="rounded-md bg-gray-100 px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-200"
          >
            取消
          </button>
          <button
            onClick={handleResolve}
            disabled={isResolving || (selectedResolution === 'manual' && !mergedData)}
            className="rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:bg-gray-400"
          >
            {isResolving ? '解决中...' : '解决冲突'}
          </button>
        </div>
      </div>
    </div>
  );
};

/**
 * 冲突列表组件
 */
export interface ConflictListProps {
  conflicts: OperationConflict[];
  onResolveConflict: (
    conflictId: string,
    resolution: 'accept_local' | 'accept_remote' | 'merge' | 'manual',
    mergedData?: any,
  ) => void;
}

/**
 * 冲突列表组件
 */
export const ConflictList: React.FC<ConflictListProps> = ({ conflicts, onResolveConflict }) => {
  const [selectedConflict, setSelectedConflict] = useState<OperationConflict | null>(null);

  const handleResolve = useCallback(
    (resolution: 'accept_local' | 'accept_remote' | 'merge' | 'manual', mergedData?: any) => {
      if (selectedConflict) {
        onResolveConflict(selectedConflict.id, resolution, mergedData);
        setSelectedConflict(null);
      }
    },
    [selectedConflict, onResolveConflict],
  );

  if (conflicts.length === 0) {
    return null;
  }

  return (
    <>
      <div className="rounded-lg border border-yellow-200 bg-yellow-50 p-4">
        <div className="mb-3 flex items-center">
          <svg
            className="mr-2 h-5 w-5 text-yellow-600"
            fill="none"
            stroke="currentColor"
            viewBox="0 0 24 24"
          >
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={2}
              d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-2.5L13.732 4c-.77-.833-1.964-.833-2.732 0L3.732 16.5c-.77.833.192 2.5 1.732 2.5z"
            />
          </svg>
          <h3 className="text-sm font-medium text-yellow-800">
            检测到 {conflicts.length} 个操作冲突
          </h3>
        </div>

        <div className="space-y-2">
          {conflicts.map((conflict) => (
            <div
              key={conflict.id}
              className="flex items-center justify-between rounded border bg-white p-3"
            >
              <div className="flex-1">
                <div className="text-sm font-medium text-gray-900">{conflict.description}</div>
                <div className="mt-1 text-xs text-gray-500">
                  {conflict.conflictType} · {conflict.operations.length} 个操作 ·{' '}
                  {conflict.timestamp.toLocaleString()}
                </div>
              </div>

              <button
                onClick={() => setSelectedConflict(conflict)}
                className="ml-3 rounded bg-yellow-100 px-3 py-1 text-xs font-medium text-yellow-800 hover:bg-yellow-200"
              >
                解决
              </button>
            </div>
          ))}
        </div>
      </div>

      {/* 冲突解决器弹窗 */}
      {selectedConflict && (
        <ConflictResolver
          conflict={selectedConflict}
          onResolve={handleResolve}
          onCancel={() => setSelectedConflict(null)}
        />
      )}
    </>
  );
};

/**
 * 冲突通知组件
 */
export interface ConflictNotificationProps {
  conflict: OperationConflict;
  onResolve: () => void;
  onDismiss: () => void;
}

/**
 * 冲突通知组件
 */
export const ConflictNotification: React.FC<ConflictNotificationProps> = ({
  conflict,
  onResolve,
  onDismiss,
}) => {
  return (
    <div className="fixed top-4 right-4 z-50 max-w-sm rounded-lg border border-yellow-200 bg-white p-4 shadow-lg">
      <div className="flex items-start">
        <svg
          className="mt-0.5 mr-3 h-5 w-5 flex-shrink-0 text-yellow-600"
          fill="none"
          stroke="currentColor"
          viewBox="0 0 24 24"
        >
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth={2}
            d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-2.5L13.732 4c-.77-.833-1.964-.833-2.732 0L3.732 16.5c-.77.833.192 2.5 1.732 2.5z"
          />
        </svg>

        <div className="flex-1">
          <h4 className="mb-1 text-sm font-medium text-gray-900">操作冲突</h4>
          <p className="mb-3 text-sm text-gray-600">{conflict.description}</p>

          <div className="flex space-x-2">
            <button
              onClick={onResolve}
              className="rounded bg-yellow-100 px-3 py-1 text-xs font-medium text-yellow-800 hover:bg-yellow-200"
            >
              解决
            </button>
            <button
              onClick={onDismiss}
              className="rounded bg-gray-100 px-3 py-1 text-xs font-medium text-gray-600 hover:bg-gray-200"
            >
              忽略
            </button>
          </div>
        </div>

        <button onClick={onDismiss} className="ml-2 text-gray-400 hover:text-gray-600">
          <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
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
  );
};
