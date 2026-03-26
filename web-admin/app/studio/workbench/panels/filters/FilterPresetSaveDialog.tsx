import React, { useState } from 'react';

interface FilterPresetSaveDialogProps {
  onSave: (name: string, scope: 'global' | 'personal', isDefault: boolean) => void;
  onClose: () => void;
}

/**
 * Filter Preset Save Dialog - dialog to name and save current filter conditions.
 */
export const FilterPresetSaveDialog: React.FC<FilterPresetSaveDialogProps> = ({
  onSave,
  onClose,
}) => {
  const [name, setName] = useState('');
  const [scope, setScope] = useState<'global' | 'personal'>('personal');
  const [isDefault, setIsDefault] = useState(false);

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (name.trim()) {
      onSave(name.trim(), scope, isDefault);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/30">
      <div className="w-80 rounded-lg bg-white p-4 shadow-xl">
        <h3 className="mb-3 text-sm font-semibold text-gray-900">保存过滤器</h3>

        <form onSubmit={handleSubmit} className="space-y-3">
          <div>
            <label className="mb-1 block text-xs text-gray-600">名称</label>
            <input
              type="text"
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="输入过滤器名称"
              className="w-full rounded-md border border-gray-300 px-3 py-1.5 text-sm focus:ring-1 focus:ring-blue-400 focus:outline-none"
              autoFocus
            />
          </div>

          <div>
            <label className="mb-1 block text-xs text-gray-600">范围</label>
            <div className="flex items-center gap-3">
              <label className="flex items-center gap-1.5 text-xs">
                <input
                  type="radio"
                  value="personal"
                  checked={scope === 'personal'}
                  onChange={() => setScope('personal')}
                />
                仅自己可见
              </label>
              <label className="flex items-center gap-1.5 text-xs">
                <input
                  type="radio"
                  value="global"
                  checked={scope === 'global'}
                  onChange={() => setScope('global')}
                />
                全局可见
              </label>
            </div>
          </div>

          <label className="flex items-center gap-2">
            <input
              type="checkbox"
              checked={isDefault}
              onChange={(e) => setIsDefault(e.target.checked)}
              className="rounded"
            />
            <span className="text-xs text-gray-600">设为默认过滤器</span>
          </label>

          <div className="flex items-center justify-end gap-2 pt-2">
            <button
              type="button"
              onClick={onClose}
              className="px-3 py-1.5 text-xs text-gray-600 hover:text-gray-800"
            >
              取消
            </button>
            <button
              type="submit"
              disabled={!name.trim()}
              className="rounded bg-blue-500 px-3 py-1.5 text-xs text-white hover:bg-blue-600 disabled:cursor-not-allowed disabled:opacity-50"
            >
              保存
            </button>
          </div>
        </form>
      </div>
    </div>
  );
};
