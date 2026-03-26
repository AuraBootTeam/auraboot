/**
 * Auto-save status indicator.
 * Shows the current save state with visual feedback.
 */

import React from 'react';

export type SaveStatus = 'idle' | 'saving' | 'saved' | 'error' | 'dirty';

interface AutoSaveIndicatorProps {
  status: SaveStatus;
  className?: string;
}

const STATUS_CONFIG: Record<SaveStatus, { text: string; dotColor: string; textColor: string }> = {
  idle: { text: '', dotColor: '', textColor: '' },
  saving: {
    text: 'Saving...',
    dotColor: 'bg-yellow-400 animate-pulse',
    textColor: 'text-yellow-600',
  },
  saved: { text: 'Saved', dotColor: 'bg-green-400', textColor: 'text-green-600' },
  error: { text: 'Save failed', dotColor: 'bg-red-400', textColor: 'text-red-600' },
  dirty: { text: 'Unsaved changes', dotColor: 'bg-orange-400', textColor: 'text-orange-600' },
};

export function AutoSaveIndicator({ status, className = '' }: AutoSaveIndicatorProps) {
  if (status === 'idle') return null;

  const config = STATUS_CONFIG[status];

  return (
    <div className={`flex items-center gap-1.5 ${className}`}>
      <span className={`h-2 w-2 rounded-full ${config.dotColor}`} />
      <span className={`text-xs font-medium ${config.textColor}`}>{config.text}</span>
    </div>
  );
}
