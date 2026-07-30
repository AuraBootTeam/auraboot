/**
 * ConfirmCard
 *
 * Amber-themed confirmation card for destructive/write tool calls.
 * Shows tool name, description, key parameters, and Confirm/Cancel buttons.
 *
 * @since 1.0.0
 */

import { AlertTriangle } from 'lucide-react';
import { useI18n } from '~/contexts/I18nContext';
import { formatToolDisplayName } from './toolDisplayName';

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

function formatParamName(key: string, isZh: boolean): string {
  const words = key.split(/[_\s]+/).filter(Boolean);
  const last = words.at(-1)?.toLowerCase() ?? key;
  const labels: Record<string, [string, string]> = {
    name: ['名称', 'Name'],
    title: ['标题', 'Title'],
    description: ['描述', 'Description'],
    industry: ['行业', 'Industry'],
    rating: ['评级', 'Rating'],
    status: ['状态', 'Status'],
    amount: ['金额', 'Amount'],
    quantity: ['数量', 'Quantity'],
    email: ['邮箱', 'Email'],
    phone: ['电话', 'Phone'],
    code: ['编码', 'Code'],
  };
  const known = labels[last];
  if (known) return isZh ? known[0] : known[1];
  return words.map((word) => word.charAt(0).toUpperCase() + word.slice(1)).join(' ');
}

function hasTechnicalDescription(value: string): boolean {
  return /\b(?:cmd|nq|builtin)_[a-z0-9_]+\b/i.test(value)
    || /\b[a-z][a-z0-9]*:[a-z][a-z0-9_]*\b/i.test(value)
    || /\b[a-z][a-z0-9]*_[a-z][a-z0-9_]*\b/i.test(value);
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
  const { t, locale } = useI18n();
  const isZh = locale.toLowerCase().startsWith('zh');
  const displayName = formatToolDisplayName(toolName, isZh);

  // Filter input params for display
  const visibleParams = Object.entries(input).filter(([key]) => !EXCLUDED_KEYS.has(key));
  const safeDescription = description && !hasTechnicalDescription(description)
    ? description
    : isZh
      ? `执行前请核对 ${visibleParams.length} 项参数。`
      : `Review ${visibleParams.length} parameter${visibleParams.length === 1 ? '' : 's'} before execution.`;

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
          <p className="text-sm text-amber-700 dark:text-amber-300/80">{safeDescription}</p>

          {/* Params */}
          {visibleParams.length > 0 && (
            <div className="space-y-1">
              {visibleParams.map(([key, value]) => (
                <div key={key} className="flex items-start gap-2 text-xs">
                  <span className="min-w-[60px] font-medium text-amber-600 dark:text-amber-400">
                    {formatParamName(key, isZh)}:
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
            {t('common.cancel', undefined, isZh ? '取消' : 'Cancel')}
          </button>
          <button
            onClick={() => onConfirm(toolId)}
            disabled={disabled}
            data-testid="aurabot-confirm-approve"
            className="rounded-lg bg-amber-500 px-3 py-1 text-xs font-medium text-white transition-colors hover:bg-amber-600 disabled:cursor-not-allowed disabled:opacity-50"
          >
            {t('common.confirm', undefined, isZh ? '确认' : 'Confirm')}
          </button>
        </div>
      </div>
    </div>
  );
}

export default ConfirmCard;
