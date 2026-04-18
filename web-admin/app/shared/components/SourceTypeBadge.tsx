import React from 'react';

export type SourceType = 'physical' | 'namedQuery' | 'endpoint' | 'sqlView';

const LABELS: Record<SourceType, string> = {
  physical: '物理',
  namedQuery: '虚拟(namedQuery)',
  endpoint: '虚拟(endpoint)',
  sqlView: '虚拟(sqlView)',
};

const COLORS: Record<SourceType, string> = {
  physical: 'bg-blue-100 text-blue-700 border-blue-200',
  namedQuery: 'bg-purple-100 text-purple-700 border-purple-200',
  endpoint: 'bg-amber-100 text-amber-700 border-amber-200',
  sqlView: 'bg-green-100 text-green-700 border-green-200',
};

export interface SourceTypeBadgeProps {
  sourceType?: string;
  className?: string;
}

export function SourceTypeBadge({ sourceType, className }: SourceTypeBadgeProps) {
  const type = (sourceType ?? 'physical') as SourceType;
  const label = LABELS[type] ?? sourceType ?? 'physical';
  const color = COLORS[type] ?? 'bg-gray-100 text-gray-700 border-gray-200';

  return (
    <span
      className={`inline-flex items-center rounded border px-2 py-0.5 text-xs font-medium ${color} ${className ?? ''}`}
    >
      {label}
    </span>
  );
}
