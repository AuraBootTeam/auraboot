import React, { useState, useEffect } from 'react';

export interface Conflict {
  id: string;
  type: 'component_update' | 'component_delete' | 'layout_change' | 'property_change';
  componentId: string;
  localChange: any;
  remoteChange: any;
  timestamp: number;
  user: {
    id: string;
    name: string;
  };
}

export interface ConflictResolution {
  conflictId: string;
  resolution: 'accept_local' | 'accept_remote' | 'merge' | 'custom';
  customData?: any;
}

export interface ConflictResolverProps {
  conflicts: Conflict[];
  onResolve: (resolution: ConflictResolution) => void;
  onResolveAll: (resolutions: ConflictResolution[]) => void;
}

export function ConflictResolver({ conflicts, onResolve, onResolveAll }: ConflictResolverProps) {
  const [selectedConflict, setSelectedConflict] = useState<Conflict | null>(null);
  const [resolutions, setResolutions] = useState<Map<string, ConflictResolution>>(new Map());

  useEffect(() => {
    if (conflicts.length > 0 && !selectedConflict) {
      setSelectedConflict(conflicts[0]);
    }
  }, [conflicts, selectedConflict]);

  const handleResolveConflict = (
    conflict: Conflict,
    resolution: ConflictResolution['resolution'],
    customData?: any,
  ) => {
    const conflictResolution: ConflictResolution = {
      conflictId: conflict.id,
      resolution,
      customData,
    };

    setResolutions((prev) => new Map(prev.set(conflict.id, conflictResolution)));
    onResolve(conflictResolution);
  };

  const handleResolveAll = () => {
    onResolveAll(Array.from(resolutions.values()));
  };

  const getConflictTypeLabel = (type: Conflict['type']) => {
    switch (type) {
      case 'component_update':
        return '组件更新冲突';
      case 'component_delete':
        return '组件删除冲突';
      case 'layout_change':
        return '布局变更冲突';
      case 'property_change':
        return '属性变更冲突';
      default:
        return '未知冲突';
    }
  };

  if (conflicts.length === 0) {
    return null;
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50">
      <div className="mx-4 max-h-[80vh] w-full max-w-4xl overflow-hidden rounded-lg bg-white shadow-xl">
        <div className="border-b p-6">
          <h2 className="text-xl font-semibold text-gray-900">
            解决协作冲突 ({conflicts.length} 个冲突)
          </h2>
          <p className="mt-1 text-sm text-gray-600">
            检测到多个用户同时修改了相同的内容，请选择如何处理这些冲突。
          </p>
        </div>

        <div className="flex h-96">
          <div className="w-1/3 overflow-y-auto border-r">
            <div className="p-4">
              <h3 className="mb-3 font-medium text-gray-900">冲突列表</h3>
              <div className="space-y-2">
                {conflicts.map((conflict) => (
                  <button
                    key={conflict.id}
                    className={`w-full rounded-lg p-3 text-left transition-colors ${
                      selectedConflict?.id === conflict.id
                        ? 'border border-blue-200 bg-blue-50'
                        : 'bg-gray-50 hover:bg-gray-100'
                    }`}
                    onClick={() => setSelectedConflict(conflict)}
                  >
                    <div className="text-sm font-medium text-gray-900">
                      {getConflictTypeLabel(conflict.type)}
                    </div>
                    <div className="mt-1 text-xs text-gray-600">组件: {conflict.componentId}</div>
                    <div className="mt-1 text-xs text-gray-500">用户: {conflict.user.name}</div>
                  </button>
                ))}
              </div>
            </div>
          </div>

          <div className="flex-1 overflow-y-auto">
            {selectedConflict && (
              <div className="p-4">
                <h3 className="mb-3 font-medium text-gray-900">
                  {getConflictTypeLabel(selectedConflict.type)}
                </h3>

                <div className="mb-6 grid grid-cols-2 gap-4">
                  <div className="rounded-lg border p-3">
                    <h4 className="mb-2 text-sm font-medium text-gray-900">您的更改</h4>
                    <pre className="overflow-auto rounded bg-gray-50 p-2 text-xs">
                      {JSON.stringify(selectedConflict.localChange, null, 2)}
                    </pre>
                  </div>

                  <div className="rounded-lg border p-3">
                    <h4 className="mb-2 text-sm font-medium text-gray-900">
                      {selectedConflict.user.name} 的更改
                    </h4>
                    <pre className="overflow-auto rounded bg-gray-50 p-2 text-xs">
                      {JSON.stringify(selectedConflict.remoteChange, null, 2)}
                    </pre>
                  </div>
                </div>

                <div className="space-y-2">
                  <h4 className="font-medium text-gray-900">选择解决方案:</h4>

                  <button
                    className="w-full rounded-lg border p-3 text-left transition-colors hover:bg-gray-50"
                    onClick={() => handleResolveConflict(selectedConflict, 'accept_local')}
                  >
                    <div className="text-sm font-medium">保留我的更改</div>
                    <div className="text-xs text-gray-600">使用您的版本，丢弃其他用户的更改</div>
                  </button>

                  <button
                    className="w-full rounded-lg border p-3 text-left transition-colors hover:bg-gray-50"
                    onClick={() => handleResolveConflict(selectedConflict, 'accept_remote')}
                  >
                    <div className="text-sm font-medium">
                      接受 {selectedConflict.user.name} 的更改
                    </div>
                    <div className="text-xs text-gray-600">使用其他用户的版本，丢弃您的更改</div>
                  </button>

                  <button
                    className="w-full rounded-lg border p-3 text-left transition-colors hover:bg-gray-50"
                    onClick={() => handleResolveConflict(selectedConflict, 'merge')}
                  >
                    <div className="text-sm font-medium">尝试自动合并</div>
                    <div className="text-xs text-gray-600">系统将尝试智能合并两个版本</div>
                  </button>
                </div>
              </div>
            )}
          </div>
        </div>

        <div className="flex justify-between border-t bg-gray-50 p-6">
          <div className="text-sm text-gray-600">
            已解决: {resolutions.size} / {conflicts.length}
          </div>

          <div className="space-x-3">
            <button
              className="rounded-lg border border-gray-300 px-4 py-2 text-gray-700 transition-colors hover:bg-gray-50"
              onClick={() => setSelectedConflict(null)}
            >
              稍后处理
            </button>

            <button
              className={`rounded-lg px-4 py-2 transition-colors ${
                resolutions.size === conflicts.length
                  ? 'bg-blue-600 text-white hover:bg-blue-700'
                  : 'cursor-not-allowed bg-gray-300 text-gray-500'
              }`}
              disabled={resolutions.size !== conflicts.length}
              onClick={handleResolveAll}
            >
              应用所有解决方案
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}

export default ConflictResolver;
