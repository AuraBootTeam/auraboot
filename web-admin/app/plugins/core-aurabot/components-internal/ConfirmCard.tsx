/**
 * ConfirmCard
 *
 * Amber-themed confirmation card for destructive/write tool calls.
 * Shows tool name, description, key parameters, and Confirm/Cancel buttons.
 *
 * @since 1.0.0
 */

import { AlertTriangle } from 'lucide-react';

// ============================================================================
// Types
// ============================================================================

interface ConfirmCardProps {
  toolId: string;
  toolName: string;
  description: string;
  input: Record<string, any>;
  onConfirm: (toolId: string) => void;
  onCancel: (toolId: string) => void;
  disabled?: boolean;
}

// ============================================================================
// Helpers
// ============================================================================

/** Strip prefix (cmd__, nq__, builtin__) and replace __ with ' › ' */
function formatToolName(name: string): string {
  return name.replace(/^(cmd__|nq__|builtin__)/, '').replace(/__/g, ' › ');
}

/** Keys to exclude from the displayed parameters */
const EXCLUDED_KEYS = new Set(['recordPid', 'record_pid']);

// ============================================================================
// Component
// ============================================================================

export function ConfirmCard({
  toolId,
  toolName,
  description,
  input,
  onConfirm,
  onCancel,
  disabled = false,
}: ConfirmCardProps) {
  const displayName = formatToolName(toolName);

  // Filter input params for display
  const visibleParams = Object.entries(input).filter(([key]) => !EXCLUDED_KEYS.has(key));

  return (
    <div className="mb-3 flex justify-start">
      <div className="w-full max-w-[95%] overflow-hidden rounded-xl border border-amber-200 bg-amber-50 shadow-sm dark:border-amber-700 dark:bg-amber-900/20">
        {/* Header */}
        <div className="flex items-center gap-2 border-b border-amber-100 px-3 py-2 dark:border-amber-800/50">
          <AlertTriangle className="h-4 w-4 flex-shrink-0 text-amber-500" />
          <span className="text-sm font-medium text-amber-800 dark:text-amber-300">
            {displayName}
          </span>
        </div>

        {/* Body */}
        <div className="space-y-2 px-3 py-2">
          {/* Description */}
          <p className="text-sm text-amber-700 dark:text-amber-300/80">{description}</p>

          {/* Params */}
          {visibleParams.length > 0 && (
            <div className="space-y-1">
              {visibleParams.map(([key, value]) => (
                <div key={key} className="flex items-start gap-2 text-xs">
                  <span className="min-w-[60px] font-medium text-amber-600 dark:text-amber-400">
                    {key}:
                  </span>
                  <span className="break-all text-amber-700 dark:text-amber-300/70">
                    {typeof value === 'object' ? JSON.stringify(value) : String(value)}
                  </span>
                </div>
              ))}
            </div>
          )}
        </div>

        {/* Footer */}
        <div className="flex items-center justify-end gap-2 border-t border-amber-100 px-3 py-2 dark:border-amber-800/50">
          <button
            onClick={() => onCancel(toolId)}
            disabled={disabled}
            className="rounded-lg border border-gray-300 bg-white px-3 py-1 text-xs font-medium text-gray-600 transition-colors hover:bg-gray-50 disabled:cursor-not-allowed disabled:opacity-50 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-300 dark:hover:bg-gray-600"
          >
            Cancel
          </button>
          <button
            onClick={() => onConfirm(toolId)}
            disabled={disabled}
            className="rounded-lg bg-amber-500 px-3 py-1 text-xs font-medium text-white transition-colors hover:bg-amber-600 disabled:cursor-not-allowed disabled:opacity-50"
          >
            Confirm
          </button>
        </div>
      </div>
    </div>
  );
}

export default ConfirmCard;
