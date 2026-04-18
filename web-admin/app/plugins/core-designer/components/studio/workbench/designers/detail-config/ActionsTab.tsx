import React, { useState } from 'react';
import { SchemaBlockConfigPanel } from '../SchemaBlockConfigPanel';
import { detailCustomButtonSchemas } from './schema';
import type { DetailViewModel, CustomButton } from './mapper';
import type { ModelCapabilities } from '~/shared/hooks/useModelCapabilities';

export interface ActionsTabProps {
  vm: DetailViewModel;
  setVm: (next: DetailViewModel) => void;
  capabilities: ModelCapabilities | undefined;
  readonly?: boolean;
}

export const ActionsTab: React.FC<ActionsTabProps> = ({ vm, setVm, capabilities, readonly }) => {
  const [selectedBtnIdx, setSelectedBtnIdx] = useState<number | null>(null);

  const togglePreset = (preset: 'edit' | 'delete') => {
    if (readonly) return;
    const has = vm.actions.presets.includes(preset);
    const presets = has
      ? vm.actions.presets.filter((p) => p !== preset)
      : [...vm.actions.presets, preset];
    setVm({ ...vm, actions: { ...vm.actions, presets } });
  };

  const addButton = () => {
    const newBtn: CustomButton = { label: '新按钮', command: '' };
    const next = [...vm.actions.customButtons, newBtn];
    setVm({ ...vm, actions: { ...vm.actions, customButtons: next } });
    setSelectedBtnIdx(next.length - 1);
  };

  const removeButton = (idx: number) => {
    const next = vm.actions.customButtons.filter((_, i) => i !== idx);
    setVm({ ...vm, actions: { ...vm.actions, customButtons: next } });
    if (selectedBtnIdx === idx) setSelectedBtnIdx(null);
  };

  const updateButton = (idx: number, patch: Partial<CustomButton>) => {
    const next = vm.actions.customButtons.map((b, i) => (i === idx ? { ...b, ...patch } : b));
    setVm({ ...vm, actions: { ...vm.actions, customButtons: next } });
  };

  const editEnabled = capabilities?.update ?? false;
  const deleteEnabled = capabilities?.delete ?? false;
  const selectedBtn = selectedBtnIdx !== null ? vm.actions.customButtons[selectedBtnIdx] : null;

  return (
    <div>
      <h2 className="mb-4 text-lg font-medium">操作按钮</h2>

      <section className="mb-6 rounded border p-4">
        <h3 className="mb-3 text-sm font-medium text-gray-700">预设按钮</h3>
        <label className={`flex items-center gap-2 py-1 ${editEnabled ? '' : 'opacity-50'}`} title={editEnabled ? '' : 'Model 不支持 update'}>
          <input
            type="checkbox"
            checked={vm.actions.presets.includes('edit')}
            onChange={() => togglePreset('edit')}
            disabled={!editEnabled || readonly}
          />
          <span>编辑</span>
        </label>
        <label className={`flex items-center gap-2 py-1 ${deleteEnabled ? '' : 'opacity-50'}`} title={deleteEnabled ? '' : 'Model 不支持 delete'}>
          <input
            type="checkbox"
            checked={vm.actions.presets.includes('delete')}
            onChange={() => togglePreset('delete')}
            disabled={!deleteEnabled || readonly}
          />
          <span>删除</span>
        </label>
      </section>

      <section className="rounded border p-4">
        <div className="mb-3 flex items-center justify-between">
          <h3 className="text-sm font-medium text-gray-700">自定义按钮</h3>
          <button onClick={addButton} disabled={readonly} className="rounded border px-2 py-1 text-xs disabled:opacity-50">+ 添加</button>
        </div>
        {vm.actions.customButtons.length === 0 ? (
          <div className="text-xs text-gray-400">暂无</div>
        ) : (
          <ul className="space-y-2">
            {vm.actions.customButtons.map((b, i) => (
              <li key={i} className={`rounded border p-2 text-sm ${selectedBtnIdx === i ? 'border-blue-400 bg-blue-50' : 'border-gray-200'}`}>
                <div className="flex items-center justify-between">
                  <button onClick={() => setSelectedBtnIdx(selectedBtnIdx === i ? null : i)} className="flex-1 text-left">
                    {b.label || '(未命名)'} <span className="text-xs text-gray-500 ml-2">{b.command}</span>
                  </button>
                  <button onClick={() => removeButton(i)} disabled={readonly} className="px-2 text-xs text-red-600 disabled:opacity-30">删除</button>
                </div>
              </li>
            ))}
          </ul>
        )}
        {selectedBtn && selectedBtnIdx !== null && (
          <div className="mt-4 border-t pt-4">
            <SchemaBlockConfigPanel
              schemas={detailCustomButtonSchemas}
              value={selectedBtn as unknown as Record<string, unknown>}
              onChange={(next) => updateButton(selectedBtnIdx, next as Partial<CustomButton>)}
              readonly={readonly}
            />
          </div>
        )}
      </section>
    </div>
  );
};
