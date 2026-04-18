/**
 * Status badge for process definition lifecycle states.
 *
 * Displays a colored label indicating the current process state:
 * - draft      -> gray
 * - published  -> green
 * - suspended  -> yellow
 *
 * Labels are i18n-driven via bpmn.status.* keys.
 */

import React from 'react';
import { useI18n } from '~/contexts/I18nContext';

interface ProcessStatusBadgeProps {
  status?: 'draft' | 'published' | 'suspended';
}

const STATUS_STYLE: Record<string, { i18nKey: string; fallback: string; bg: string; text: string; dot: string }> = {
  draft: {
    i18nKey: 'bpmn.status.draft',
    fallback: 'Draft',
    bg: 'bg-gray-100',
    text: 'text-gray-700',
    dot: 'bg-gray-400',
  },
  published: {
    i18nKey: 'bpmn.status.published',
    fallback: 'Published',
    bg: 'bg-green-100',
    text: 'text-green-700',
    dot: 'bg-green-500',
  },
  suspended: {
    i18nKey: 'bpmn.status.suspended',
    fallback: 'Suspended',
    bg: 'bg-yellow-100',
    text: 'text-yellow-700',
    dot: 'bg-yellow-500',
  },
};

export function ProcessStatusBadge({ status }: ProcessStatusBadgeProps) {
  const { t } = useI18n();
  const config = STATUS_STYLE[status || 'draft'] ?? STATUS_STYLE.draft;
  const label = t(config.i18nKey, undefined, config.fallback);

  return (
    <span
      role="status"
      aria-label={label}
      className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-0.5 text-xs font-medium ${config.bg} ${config.text}`}
    >
      <span className={`h-1.5 w-1.5 rounded-full ${config.dot}`} aria-hidden="true" />
      {label}
    </span>
  );
}
