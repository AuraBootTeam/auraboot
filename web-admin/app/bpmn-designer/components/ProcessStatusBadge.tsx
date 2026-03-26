/**
 * Status badge for process definition lifecycle states.
 *
 * Displays a colored label indicating the current process state:
 * - draft      -> gray   "草稿"
 * - published  -> green  "已发布"
 * - suspended  -> yellow "已暂停"
 */

import React from 'react';

interface ProcessStatusBadgeProps {
  status?: 'draft' | 'published' | 'suspended';
}

const STATUS_CONFIG: Record<string, { label: string; bg: string; text: string; dot: string }> = {
  draft: {
    label: '草稿',
    bg: 'bg-gray-100',
    text: 'text-gray-700',
    dot: 'bg-gray-400',
  },
  published: {
    label: '已发布',
    bg: 'bg-green-100',
    text: 'text-green-700',
    dot: 'bg-green-500',
  },
  suspended: {
    label: '已暂停',
    bg: 'bg-yellow-100',
    text: 'text-yellow-700',
    dot: 'bg-yellow-500',
  },
};

export function ProcessStatusBadge({ status }: ProcessStatusBadgeProps) {
  const config = STATUS_CONFIG[status || 'draft'] ?? STATUS_CONFIG.draft;

  return (
    <span
      className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-0.5 text-xs font-medium ${config.bg} ${config.text}`}
    >
      <span className={`h-1.5 w-1.5 rounded-full ${config.dot}`} />
      {config.label}
    </span>
  );
}
