import React from 'react';
import type { FilterPreset } from './types';

interface FilterPresetListProps {
  presets: FilterPreset[];
  loading: boolean;
  onLoad: (preset: FilterPreset) => void;
  onDelete: (id: number) => void;
  onSetDefault: (id: number) => void;
}

/**
 * Filter Preset List - shows saved filter presets with load/delete actions.
 */
export const FilterPresetList: React.FC<FilterPresetListProps> = ({
  presets,
  loading,
  onLoad,
  onDelete,
  onSetDefault,
}) => {
  if (loading) {
    return (
      <div className="flex items-center justify-center py-4">
        <div className="h-4 w-4 animate-spin rounded-full border-2 border-blue-500 border-t-transparent" />
        <span className="ml-2 text-xs text-gray-400">加载中...</span>
      </div>
    );
  }

  if (presets.length === 0) {
    return <div className="py-3 text-center text-xs text-gray-400">暂无已保存的过滤器</div>;
  }

  return (
    <div className="space-y-1">
      {presets.map((preset) => (
        <div
          key={preset.id}
          className="group flex cursor-pointer items-center justify-between rounded px-2.5 py-1.5 hover:bg-gray-50"
          onClick={() => onLoad(preset)}
        >
          <div className="flex min-w-0 items-center gap-2">
            <span className="truncate text-sm text-gray-700">{preset.name}</span>
            {preset.isDefault && (
              <span className="rounded bg-blue-100 px-1 py-0.5 text-[10px] text-blue-600">
                默认
              </span>
            )}
            {preset.scope === 'global' && (
              <span className="rounded bg-green-100 px-1 py-0.5 text-[10px] text-green-600">
                全局
              </span>
            )}
            <span className="text-[10px] text-gray-400">{preset.conditions.length} 条件</span>
          </div>

          <div className="flex items-center gap-0.5 opacity-0 transition-opacity group-hover:opacity-100">
            {!preset.isDefault && preset.id && (
              <button
                onClick={(e) => {
                  e.stopPropagation();
                  onSetDefault(preset.id!);
                }}
                className="p-0.5 text-gray-400 hover:text-blue-500"
                title="设为默认"
              >
                <svg className="h-3.5 w-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={2}
                    d="M11.049 2.927c.3-.921 1.603-.921 1.902 0l1.519 4.674a1 1 0 00.95.69h4.915c.969 0 1.371 1.24.588 1.81l-3.976 2.888a1 1 0 00-.363 1.118l1.518 4.674c.3.922-.755 1.688-1.538 1.118l-3.976-2.888a1 1 0 00-1.176 0l-3.976 2.888c-.783.57-1.838-.197-1.538-1.118l1.518-4.674a1 1 0 00-.363-1.118l-3.976-2.888c-.784-.57-.38-1.81.588-1.81h4.914a1 1 0 00.951-.69l1.519-4.674z"
                  />
                </svg>
              </button>
            )}
            {preset.id && (
              <button
                onClick={(e) => {
                  e.stopPropagation();
                  onDelete(preset.id!);
                }}
                className="p-0.5 text-gray-400 hover:text-red-500"
                title="删除"
              >
                <svg className="h-3.5 w-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={2}
                    d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16"
                  />
                </svg>
              </button>
            )}
          </div>
        </div>
      ))}
    </div>
  );
};
