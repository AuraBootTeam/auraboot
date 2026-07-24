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
/**
 * Turns the LLM-safe tool name into something a person can read.
 *
 * This card is shown at the one moment the product asks a human to authorise an action, so the
 * name on it has to be legible. It was stripping double-underscore prefixes (`cmd__`) while the
 * runtime emits single ones — `cmd_crm_create_account` — so nothing matched and the card asked
 * people to approve a raw command code.
 *
 * The runtime builds these by replacing the namespace colon with an underscore, so the first
 * underscore after the prefix is that separator: `cmd_crm_create_account` is `crm:create_account`.
 */
function formatToolName(name: string): string {
  // Already carries its namespace separator — that is the form the product uses.
  if (name.includes(':')) {
    return name.replace(/^(cmd|nq|builtin):/, '').replace(/_/g, ' ');
  }
  const withoutPrefix = name.replace(/^(cmd__|nq__|builtin__)/, '').replace(/^(cmd_|nq_|builtin_)/, '');
  if (withoutPrefix.includes('__')) {
    return withoutPrefix.replace(/__/g, ' › ');
  }
  const separator = withoutPrefix.indexOf('_');
  if (separator <= 0 || separator >= withoutPrefix.length - 1) {
    return withoutPrefix.replace(/_/g, ' ');
  }
  return `${withoutPrefix.slice(0, separator)} › ${withoutPrefix.slice(separator + 1).replace(/_/g, ' ')}`;
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
    <div className="mb-3 flex justify-start" data-testid="aurabot-confirm-card">
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
            data-testid="aurabot-confirm-cancel"
            className="rounded-lg border border-gray-300 bg-white px-3 py-1 text-xs font-medium text-gray-600 transition-colors hover:bg-gray-50 disabled:cursor-not-allowed disabled:opacity-50 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-300 dark:hover:bg-gray-600"
          >
            Cancel
          </button>
          <button
            onClick={() => onConfirm(toolId)}
            disabled={disabled}
            data-testid="aurabot-confirm-approve"
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
