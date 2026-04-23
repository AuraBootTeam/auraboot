/**
 * BatchActionBar — floating batch action toolbar
 *
 * Shows when multiple rows are selected in a table with
 * selection.mode === 'multiple'. Supports batch delete, status change, etc.
 *
 * DSL config:
 * buttons[] with batchAction: true will appear in this bar.
 */

import React, { useCallback } from 'react';
import type { ButtonConfig } from '~/framework/meta/schemas/types';

export interface BatchActionBarProps {
  /** Selected record IDs */
  selectedIds: string[];
  /** Batch action buttons from DSL */
  buttons: ButtonConfig[];
  /** Handler for batch actions */
  onAction: (action: string, commandCode: string | undefined, ids: string[]) => void;
  /** Clear selection */
  onClear: () => void;
  /** Resolve button label */
  resolveLabel: (button: ButtonConfig) => string;
}

export const BatchActionBar: React.FC<BatchActionBarProps> = ({
  selectedIds,
  buttons,
  onAction,
  onClear,
  resolveLabel,
}) => {
  const handleAction = useCallback(
    (button: ButtonConfig) => {
      const actionStr = typeof button.action === 'string' ? button.action : button.code;
      onAction(actionStr, button.commandCode, selectedIds);
    },
    [selectedIds, onAction],
  );

  if (selectedIds.length === 0) return null;

  return (
    <div className="fixed bottom-4 left-1/2 z-50 flex -translate-x-1/2 items-center gap-3 rounded-lg border border-gray-200 bg-white px-4 py-2.5 shadow-lg">
      <span className="text-sm font-medium text-gray-600">{selectedIds.length} selected</span>

      <div className="h-5 w-px bg-gray-200" />

      {buttons.map((button) => (
        <button
          key={button.code}
          onClick={() => handleAction(button)}
          className={`rounded-md px-3 py-1.5 text-sm font-medium transition-colors ${
            button.danger
              ? 'bg-red-50 text-red-700 hover:bg-red-100'
              : button.primary
                ? 'bg-blue-50 text-blue-700 hover:bg-blue-100'
                : 'bg-gray-50 text-gray-700 hover:bg-gray-100'
          }`}
        >
          {resolveLabel(button)}
        </button>
      ))}

      <div className="h-5 w-px bg-gray-200" />

      <button
        onClick={onClear}
        className="px-3 py-1.5 text-sm text-gray-500 transition-colors hover:text-gray-700"
      >
        Clear
      </button>
    </div>
  );
};
