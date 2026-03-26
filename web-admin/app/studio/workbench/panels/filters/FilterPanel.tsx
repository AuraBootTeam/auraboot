import React from 'react';
import { useFilterPresets } from '~/studio/hooks/filters/useFilterPresets';
import { FilterConditionBuilder } from './FilterConditionBuilder';
import { FilterPresetList } from './FilterPresetList';
import { FilterPresetSaveDialog } from './FilterPresetSaveDialog';

interface FilterPanelProps {
  pageCode?: string;
  modelCode?: string;
  fieldOptions?: { code: string; label: string }[];
  readonly?: boolean;
}

/**
 * Filter Panel - configure and save filter conditions for list pages.
 * Integrates condition builder, preset list, and save dialog.
 *
 * @since 3.4.0
 */
export const FilterPanel: React.FC<FilterPanelProps> = ({
  pageCode,
  modelCode,
  fieldOptions = [],
  readonly = false,
}) => {
  const {
    presets,
    loadingPresets,
    conditions,
    logic,
    setLogic,
    addCondition,
    removeCondition,
    updateCondition,
    savePreset,
    loadPreset,
    deletePreset,
    setDefaultPreset,
    showSaveDialog,
    setShowSaveDialog,
  } = useFilterPresets(pageCode, modelCode);

  return (
    <div className="flex h-full flex-col">
      {/* Header */}
      <div className="border-b border-gray-200 p-4">
        <div className="flex items-center justify-between">
          <div>
            <h3 className="text-sm font-semibold text-gray-900">过滤条件</h3>
            <p className="mt-0.5 text-xs text-gray-400">配置列表页筛选条件</p>
          </div>
          {!readonly && conditions.length > 0 && (
            <button
              onClick={() => setShowSaveDialog(true)}
              className="rounded px-2 py-1 text-xs text-blue-600 hover:bg-blue-50"
              title="保存为预设"
            >
              保存
            </button>
          )}
        </div>
      </div>

      {/* No page selected */}
      {!pageCode && (
        <div className="flex flex-1 items-center justify-center p-4">
          <div className="text-center text-gray-400">
            <p className="text-sm">请先关联页面</p>
            <p className="mt-1 text-xs">过滤器依赖页面标识</p>
          </div>
        </div>
      )}

      {pageCode && (
        <div className="flex-1 overflow-y-auto">
          {/* Current conditions */}
          <div className="border-b border-gray-200 p-4">
            <FilterConditionBuilder
              conditions={conditions}
              logic={logic}
              onLogicChange={setLogic}
              onAdd={addCondition}
              onRemove={removeCondition}
              onUpdate={updateCondition}
              fieldOptions={fieldOptions}
              readonly={readonly}
            />
          </div>

          {/* Saved presets */}
          <div className="p-4">
            <div className="mb-2 flex items-center justify-between">
              <span className="text-xs font-medium text-gray-600">已保存过滤器</span>
            </div>
            <FilterPresetList
              presets={presets}
              loading={loadingPresets}
              onLoad={loadPreset}
              onDelete={deletePreset}
              onSetDefault={setDefaultPreset}
            />
          </div>
        </div>
      )}

      {/* Save dialog */}
      {showSaveDialog && (
        <FilterPresetSaveDialog onSave={savePreset} onClose={() => setShowSaveDialog(false)} />
      )}
    </div>
  );
};
