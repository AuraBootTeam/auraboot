import React from 'react';
import { useViewModelSelector } from '~/plugins/core-designer/components/studio/hooks/viewmodel/useViewModelSelector';
import type { ViewModelMode } from '~/plugins/core-designer/components/studio/domain/viewmodel/types';

interface ViewModelSelectorProps {
  value?: string | null;
  onChange: (code: string | null) => void;
}

const MODE_LABELS: Record<ViewModelMode, { label: string; color: string }> = {
  inherit: { label: 'Inherit', color: 'bg-blue-100 text-blue-700' },
  compose: { label: 'Compose', color: 'bg-green-100 text-green-700' },
  free: { label: 'Free', color: 'bg-purple-100 text-purple-700' },
};

/**
 * ViewModel selector dropdown.
 * Displays available ViewModels with mode tags.
 *
 * @since 3.2.0
 */
export const ViewModelSelector: React.FC<ViewModelSelectorProps> = ({ value, onChange }) => {
  const { viewModels, loading, error, refresh } = useViewModelSelector();

  if (loading) {
    return <div className="px-4 py-2 text-sm text-gray-500">Loading ViewModels...</div>;
  }

  if (error) {
    return (
      <div className="px-4 py-2">
        <p className="text-sm text-red-500">{error}</p>
        <button onClick={refresh} className="mt-1 text-xs text-blue-600 hover:underline">
          Retry
        </button>
      </div>
    );
  }

  return (
    <div className="px-4 py-2">
      <label className="mb-1 block text-xs font-medium text-gray-500">ViewModel</label>
      <select
        value={value ?? ''}
        onChange={(e) => onChange(e.target.value || null)}
        className="w-full rounded-md border border-gray-300 px-2 py-1.5 text-sm focus:border-transparent focus:ring-2 focus:ring-blue-500 focus:outline-none"
      >
        <option value="">-- None --</option>
        {viewModels.map((vm) => {
          const modeInfo = MODE_LABELS[vm.mode] || {
            label: vm.mode,
            color: 'bg-gray-100 text-gray-700',
          };
          return (
            <option key={vm.code} value={vm.code}>
              {vm.displayName || vm.code} [{modeInfo.label}]
            </option>
          );
        })}
      </select>

      {value && (
        <div className="mt-2 flex items-center gap-2">
          {viewModels
            .filter((vm) => vm.code === value)
            .map((vm) => {
              const modeInfo = MODE_LABELS[vm.mode] || {
                label: vm.mode,
                color: 'bg-gray-100 text-gray-700',
              };
              return (
                <span
                  key={vm.code}
                  className={`inline-flex items-center rounded px-2 py-0.5 text-xs font-medium ${modeInfo.color}`}
                >
                  {modeInfo.label}
                </span>
              );
            })}
          <a
            href={`/admin/meta/models?type=VIEW`}
            target="_blank"
            rel="noopener noreferrer"
            className="text-xs text-blue-600 hover:underline"
          >
            Manage
          </a>
        </div>
      )}
    </div>
  );
};
