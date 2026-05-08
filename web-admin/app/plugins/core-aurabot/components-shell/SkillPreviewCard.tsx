/**
 * SkillPreviewCard
 *
 * C-5 T7: skill confirmation UI surfaced when the backend emits a
 * {@code confirm_required} SSE event whose {@code PendingTool.extension}
 * carries {@code _aurabot_skill === true}. Differs from {@code ConfirmCard}
 * in three ways:
 *
 *   1. Risk-tier badge driven by {@code riskLevel} (LOW / MEDIUM / HIGH /
 *      CRITICAL) with deterministic color mapping.
 *   2. Pretty-printed preview JSON instead of a key/value list — skills
 *      surface arbitrary structured plans (e.g. SQL diff, batch summary).
 *   3. CRITICAL skills require the user to type the {@code skillName} verbatim
 *      before the confirm button enables — a soft tripwire matching the
 *      destructive-skill spec §6.
 *
 * @since C-5 T7
 */

import React, { useState, useMemo } from 'react';
import { useI18n } from '~/contexts/I18nContext';

// ============================================================================
// Types
// ============================================================================

export interface SkillPreviewCardProps {
  turnId: string;
  toolId: string;
  skillName: string;
  preview: Record<string, any>;
  previewToken: string;
  riskLevel: string;
  onConfirm: (toolId: string) => void;
  onCancel: (toolId: string) => void;
  disabled?: boolean;
}

// ============================================================================
// Helpers
// ============================================================================

/** Color tokens per risk tier — emerald / amber / orange / rose. */
function riskBadgeClasses(level: string): string {
  switch (level.toUpperCase()) {
    case 'LOW':
      return 'bg-emerald-100 text-emerald-800 border-emerald-300 dark:bg-emerald-900/30 dark:text-emerald-300';
    case 'HIGH':
      return 'bg-orange-100 text-orange-800 border-orange-300 dark:bg-orange-900/30 dark:text-orange-300';
    case 'CRITICAL':
      return 'bg-rose-100 text-rose-800 border-rose-300 dark:bg-rose-900/30 dark:text-rose-300';
    case 'MEDIUM':
    default:
      return 'bg-amber-100 text-amber-800 border-amber-300 dark:bg-amber-900/30 dark:text-amber-300';
  }
}

function frameClasses(level: string): string {
  switch (level.toUpperCase()) {
    case 'LOW':
      return 'border-emerald-200 bg-emerald-50 dark:border-emerald-800 dark:bg-emerald-900/20';
    case 'HIGH':
      return 'border-orange-200 bg-orange-50 dark:border-orange-800 dark:bg-orange-900/20';
    case 'CRITICAL':
      return 'border-rose-300 bg-rose-50 dark:border-rose-800 dark:bg-rose-900/20';
    case 'MEDIUM':
    default:
      return 'border-amber-200 bg-amber-50 dark:border-amber-800 dark:bg-amber-900/20';
  }
}

function prettyJson(value: unknown): string {
  try {
    return JSON.stringify(value ?? {}, null, 2);
  } catch {
    return String(value);
  }
}

// ============================================================================
// Component
// ============================================================================

export function SkillPreviewCard({
  turnId: _turnId,
  toolId,
  skillName,
  preview,
  previewToken: _previewToken,
  riskLevel,
  onConfirm,
  onCancel,
  disabled = false,
}: SkillPreviewCardProps) {
  const { t } = useI18n();
  const isCritical = riskLevel.toUpperCase() === 'CRITICAL';
  const [criticalText, setCriticalText] = useState('');

  const previewText = useMemo(() => prettyJson(preview), [preview]);

  const confirmDisabled =
    disabled || (isCritical && criticalText.trim() !== skillName);

  const headerLabel = t(
    'aurabot.skill.preview.header',
    undefined,
    '技能确认',
  );
  const riskLabel = t(
    `aurabot.skill.risk.${riskLevel.toLowerCase()}`,
    undefined,
    riskLevel.toUpperCase(),
  );
  const previewLabel = t(
    'aurabot.skill.preview.payload',
    undefined,
    '预览',
  );
  const criticalHint = t(
    'aurabot.skill.preview.criticalHint',
    { skillName },
    `请输入技能名 "${skillName}" 以确认执行`,
  );
  const confirmLabel = t('aurabot.skill.preview.confirm', undefined, '确认执行');
  const cancelLabel = t('aurabot.skill.preview.cancel', undefined, '取消');

  return (
    <div className="mb-3 flex justify-start">
      <div
        data-testid="skill-preview-card"
        data-risk={riskLevel.toUpperCase()}
        className={`w-full max-w-[95%] overflow-hidden rounded-xl border shadow-sm ${frameClasses(riskLevel)}`}
      >
        {/* Header */}
        <div className="flex items-center justify-between gap-2 border-b border-black/5 px-3 py-2 dark:border-white/5">
          <div className="flex items-center gap-2">
            <span className="text-sm font-medium text-gray-800 dark:text-gray-200">
              {headerLabel}
            </span>
            <span
              className="text-sm font-mono text-gray-700 dark:text-gray-300"
              data-testid="skill-name"
            >
              {skillName}
            </span>
          </div>
          <span
            data-testid="risk-badge"
            data-risk={riskLevel.toUpperCase()}
            className={`inline-flex items-center rounded-md border px-2 py-0.5 text-[11px] font-medium ${riskBadgeClasses(riskLevel)}`}
          >
            {riskLabel}
          </span>
        </div>

        {/* Body */}
        <div className="space-y-2 px-3 py-2">
          <div className="text-xs font-medium text-gray-500 dark:text-gray-400">
            {previewLabel}
          </div>
          <pre
            data-testid="preview-json"
            className="max-h-64 overflow-auto rounded-md bg-white/70 p-2 text-xs font-mono text-gray-800 dark:bg-black/30 dark:text-gray-200"
          >
            {previewText}
          </pre>

          {isCritical && (
            <div className="space-y-1">
              <label
                htmlFor={`skill-critical-${toolId}`}
                className="block text-xs text-rose-700 dark:text-rose-300"
              >
                {criticalHint}
              </label>
              <input
                id={`skill-critical-${toolId}`}
                type="text"
                data-testid="critical-confirm-input"
                value={criticalText}
                onChange={(e) => setCriticalText(e.target.value)}
                placeholder={skillName}
                className="w-full rounded-md border border-rose-300 bg-white px-2 py-1 text-xs text-gray-800 focus:border-rose-500 focus:ring-1 focus:ring-rose-500 focus:outline-none dark:border-rose-700 dark:bg-gray-800 dark:text-gray-200"
                disabled={disabled}
              />
            </div>
          )}
        </div>

        {/* Footer */}
        <div className="flex items-center justify-end gap-2 border-t border-black/5 px-3 py-2 dark:border-white/5">
          <button
            type="button"
            data-testid="cancel-btn"
            onClick={() => onCancel(toolId)}
            disabled={disabled}
            className="rounded-lg border border-gray-300 bg-white px-3 py-1 text-xs font-medium text-gray-600 transition-colors hover:bg-gray-50 disabled:cursor-not-allowed disabled:opacity-50 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-300 dark:hover:bg-gray-600"
          >
            {cancelLabel}
          </button>
          <button
            type="button"
            data-testid="confirm-btn"
            onClick={() => onConfirm(toolId)}
            disabled={confirmDisabled}
            className={`rounded-lg px-3 py-1 text-xs font-medium text-white transition-colors disabled:cursor-not-allowed disabled:opacity-50 ${
              isCritical
                ? 'bg-rose-600 hover:bg-rose-700'
                : 'bg-amber-500 hover:bg-amber-600'
            }`}
          >
            {confirmLabel}
          </button>
        </div>
      </div>
    </div>
  );
}

export default SkillPreviewCard;
