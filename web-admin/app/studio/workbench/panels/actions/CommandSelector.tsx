import React, { useState } from 'react';
import type { CommandDefinitionDTO } from './types';

interface CommandSelectorProps {
  commands: CommandDefinitionDTO[];
  loading: boolean;
  error: string | null;
  value?: string;
  onChange: (commandCode: string, command: CommandDefinitionDTO) => void;
  onRefresh: () => void;
}

/**
 * Command Selector - dropdown to pick a command from the model's command list.
 */
export const CommandSelector: React.FC<CommandSelectorProps> = ({
  commands,
  loading,
  error,
  value,
  onChange,
  onRefresh,
}) => {
  const [open, setOpen] = useState(false);
  const selected = commands.find((c) => c.code === value);

  return (
    <div className="relative">
      <label className="mb-1 block text-xs font-medium text-gray-500">关联命令</label>
      <div className="flex items-center gap-1">
        <button
          onClick={() => setOpen(!open)}
          className="flex flex-1 items-center justify-between rounded-md border border-gray-300 bg-white px-3 py-2 text-sm hover:border-blue-400 focus:ring-1 focus:ring-blue-400 focus:outline-none"
          disabled={loading}
        >
          <span className={selected ? 'text-gray-900' : 'text-gray-400'}>
            {loading
              ? '加载中...'
              : selected
                ? selected.displayName || selected.code
                : '选择命令...'}
          </span>
          <svg
            className="h-4 w-4 text-gray-400"
            fill="none"
            stroke="currentColor"
            viewBox="0 0 24 24"
          >
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 9l-7 7-7-7" />
          </svg>
        </button>
        <button
          onClick={onRefresh}
          className="rounded p-2 text-gray-400 hover:bg-gray-100 hover:text-gray-600"
          title="刷新命令列表"
        >
          <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={2}
              d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15"
            />
          </svg>
        </button>
      </div>

      {error && <p className="mt-1 text-xs text-red-500">{error}</p>}

      {/* Dropdown */}
      {open && !loading && (
        <div className="absolute z-10 mt-1 max-h-60 w-full overflow-y-auto rounded-md border border-gray-200 bg-white shadow-lg">
          {commands.length === 0 ? (
            <div className="px-3 py-4 text-center text-sm text-gray-400">暂无可用命令</div>
          ) : (
            commands.map((cmd) => (
              <button
                key={cmd.pid}
                onClick={() => {
                  onChange(cmd.code, cmd);
                  setOpen(false);
                }}
                className={`w-full px-3 py-2 text-left text-sm hover:bg-blue-50 ${
                  cmd.code === value ? 'bg-blue-50 text-blue-700' : 'text-gray-700'
                }`}
              >
                <div className="font-medium">{cmd.displayName || cmd.code}</div>
                {cmd.description && (
                  <div className="mt-0.5 truncate text-xs text-gray-400">{cmd.description}</div>
                )}
                <div className="mt-0.5 font-mono text-xs text-gray-300">{cmd.code}</div>
              </button>
            ))
          )}
        </div>
      )}
    </div>
  );
};
