/**
 * Shortcuts Help Panel
 *
 * Displays available keyboard shortcuts.
 *
 * @since 3.2.0
 */

import React from 'react';
import { SHORTCUTS, getShortcutDisplay } from '~/studio/hooks/shortcuts/useDesignerShortcuts';

interface ShortcutsHelpPanelProps {
  isOpen: boolean;
  onClose: () => void;
}

const CATEGORY_LABELS: Record<string, string> = {
  edit: '编辑',
  view: '视图',
  selection: '选择',
  navigation: '导航',
};

/**
 * Shortcuts Help Panel Component
 */
export const ShortcutsHelpPanel: React.FC<ShortcutsHelpPanelProps> = ({ isOpen, onClose }) => {
  if (!isOpen) return null;

  // Group shortcuts by category
  const groupedShortcuts = SHORTCUTS.reduce(
    (acc, shortcut) => {
      if (!acc[shortcut.category]) {
        acc[shortcut.category] = [];
      }
      acc[shortcut.category].push(shortcut);
      return acc;
    },
    {} as Record<string, typeof SHORTCUTS>,
  );

  return (
    <>
      {/* Backdrop */}
      <div className="fixed inset-0 z-40 bg-black/20" onClick={onClose} />

      {/* Panel */}
      <div className="fixed top-1/2 left-1/2 z-50 max-h-[80vh] w-[400px] -translate-x-1/2 -translate-y-1/2 overflow-hidden rounded-xl bg-white shadow-2xl">
        {/* Header */}
        <div className="flex items-center justify-between border-b border-gray-100 px-5 py-4">
          <div className="flex items-center gap-2">
            <svg
              className="h-5 w-5 text-gray-600"
              fill="none"
              stroke="currentColor"
              viewBox="0 0 24 24"
            >
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M12 6V4m0 2a2 2 0 100 4m0-4a2 2 0 110 4m-6 8a2 2 0 100-4m0 4a2 2 0 110-4m0 4v2m0-6V4m6 6v10m6-2a2 2 0 100-4m0 4a2 2 0 110-4m0 4v2m0-6V4"
              />
            </svg>
            <h2 className="text-base font-semibold text-gray-900">键盘快捷键</h2>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="rounded-md p-1 text-gray-400 hover:bg-gray-100 hover:text-gray-600"
          >
            <svg className="h-5 w-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M6 18L18 6M6 6l12 12"
              />
            </svg>
          </button>
        </div>

        {/* Content */}
        <div className="max-h-[60vh] overflow-y-auto px-5 py-4">
          <div className="space-y-5">
            {Object.entries(groupedShortcuts).map(([category, shortcuts]) => (
              <div key={category}>
                <h3 className="mb-2 text-xs font-semibold tracking-wider text-gray-500 uppercase">
                  {CATEGORY_LABELS[category] || category}
                </h3>
                <div className="space-y-1">
                  {shortcuts.map((shortcut, index) => (
                    <div
                      key={`${shortcut.key}-${index}`}
                      className="-mx-2 flex items-center justify-between rounded-md px-2 py-1.5 hover:bg-gray-50"
                    >
                      <span className="text-sm text-gray-700">{shortcut.description}</span>
                      <kbd className="rounded border border-gray-200 bg-gray-100 px-2 py-0.5 font-mono text-xs text-gray-600">
                        {getShortcutDisplay(shortcut)}
                      </kbd>
                    </div>
                  ))}
                </div>
              </div>
            ))}
          </div>
        </div>

        {/* Footer */}
        <div className="border-t border-gray-100 bg-gray-50/50 px-5 py-3">
          <p className="text-center text-xs text-gray-500">
            按 <kbd className="rounded bg-gray-200 px-1 py-0.5 text-[10px]">?</kbd> 显示此面板
          </p>
        </div>
      </div>
    </>
  );
};

export default ShortcutsHelpPanel;
