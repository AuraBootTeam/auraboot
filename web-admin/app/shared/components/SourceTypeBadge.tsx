import React from 'react';

export type SourceType = 'physical' | 'namedQuery' | 'endpoint' | 'sqlView';

const LABELS: Record<SourceType, string> = {
  physical: '物理表',
  namedQuery: 'NamedQuery',
  endpoint: 'Endpoint',
  sqlView: 'SQL View',
};

const COLORS: Record<SourceType, string> = {
  physical: 'border-status-blue/35 bg-status-blue-bg text-status-blue',
  namedQuery: 'border-status-gray/35 bg-status-gray-bg text-status-gray',
  endpoint: 'border-status-amber/35 bg-status-amber-bg text-status-amber',
  sqlView: 'border-status-green/35 bg-status-green-bg text-status-green',
};

export interface SourceTypeBadgeProps {
  sourceType?: string;
  className?: string;
}

export function SourceTypeBadge({ sourceType, className }: SourceTypeBadgeProps) {
  const type = (sourceType ?? 'physical') as SourceType;
  const label = LABELS[type] ?? sourceType ?? 'physical';
  const color = COLORS[type] ?? 'border-status-gray/35 bg-status-gray-bg text-status-gray';

  return (
    <span
      className={`inline-flex items-center rounded border px-2 py-0.5 text-xs font-medium ${color} ${className ?? ''}`}
    >
      {label}
    </span>
  );
}
