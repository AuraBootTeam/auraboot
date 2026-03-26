/**
 * Resource Diff Viewer Component
 *
 * Displays differences between original (import snapshot) and current state of a resource.
 */

import { ChevronDownIcon, ChevronRightIcon } from '@heroicons/react/24/outline';
import { useState } from 'react';
import type { ResourceDiff } from '../api/pluginUninstallApi';

export interface ResourceDiffViewerProps {
  diffs: ResourceDiff[];
  className?: string;
  expandedByDefault?: boolean;
  maxHeight?: string;
}

export function ResourceDiffViewer({
  diffs,
  className = '',
  expandedByDefault = true,
  maxHeight = '300px',
}: ResourceDiffViewerProps) {
  const [expandedFields, setExpandedFields] = useState<Set<string>>(
    expandedByDefault ? new Set(diffs.map((d) => d.field)) : new Set()
  );

  const toggleField = (field: string) => {
    setExpandedFields((prev) => {
      const next = new Set(prev);
      if (next.has(field)) {
        next.delete(field);
      } else {
        next.add(field);
      }
      return next;
    });
  };

  if (diffs.length === 0) {
    return (
      <div className={`text-sm text-gray-500 italic ${className}`}>
        没有检测到修改
      </div>
    );
  }

  return (
    <div className={`border border-gray-200 rounded-lg overflow-hidden ${className}`}>
      <div className="bg-gray-50 px-4 py-2 border-b border-gray-200">
        <span className="text-sm font-medium text-gray-700">
          共 {diffs.length} 处修改
        </span>
      </div>

      <div className="overflow-auto" style={{ maxHeight }}>
        {diffs.map((diff, index) => (
          <DiffItem
            key={diff.field}
            diff={diff}
            isExpanded={expandedFields.has(diff.field)}
            onToggle={() => toggleField(diff.field)}
            isLast={index === diffs.length - 1}
          />
        ))}
      </div>
    </div>
  );
}

interface DiffItemProps {
  diff: ResourceDiff;
  isExpanded: boolean;
  onToggle: () => void;
  isLast: boolean;
}

function DiffItem({ diff, isExpanded, onToggle, isLast }: DiffItemProps) {
  return (
    <div className={`${!isLast ? 'border-b border-gray-100' : ''}`}>
      {/* Header */}
      <button
        onClick={onToggle}
        className="w-full flex items-center px-4 py-2 hover:bg-gray-50 transition-colors text-left"
      >
        {isExpanded ? (
          <ChevronDownIcon className="h-4 w-4 text-gray-400 mr-2 flex-shrink-0" />
        ) : (
          <ChevronRightIcon className="h-4 w-4 text-gray-400 mr-2 flex-shrink-0" />
        )}
        <span className="text-sm font-medium text-gray-700 truncate">{diff.field}</span>
      </button>

      {/* Content */}
      {isExpanded && (
        <div className="px-4 pb-3 pl-10">
          <div className="grid grid-cols-2 gap-4">
            {/* Original Value */}
            <div>
              <div className="text-xs text-gray-500 mb-1">原始值</div>
              <ValueDisplay value={diff.original} variant="original" />
            </div>

            {/* Current Value */}
            <div>
              <div className="text-xs text-gray-500 mb-1">当前值</div>
              <ValueDisplay value={diff.current} variant="current" />
            </div>
          </div>

          {diff.description && (
            <div className="mt-2 text-xs text-gray-500 italic">{diff.description}</div>
          )}
        </div>
      )}
    </div>
  );
}

interface ValueDisplayProps {
  value: unknown;
  variant: 'original' | 'current';
}

function ValueDisplay({ value, variant }: ValueDisplayProps) {
  const bgColor = variant === 'original' ? 'bg-red-50' : 'bg-green-50';
  const borderColor = variant === 'original' ? 'border-red-200' : 'border-green-200';
  const textColor = variant === 'original' ? 'text-red-800' : 'text-green-800';

  // Format the value for display
  const formattedValue = formatValue(value);
  const isMultiline = formattedValue.includes('\n') || formattedValue.length > 100;

  if (value === null || value === undefined) {
    return (
      <div className={`px-2 py-1 rounded border ${bgColor} ${borderColor}`}>
        <span className="text-gray-400 italic text-sm">(空)</span>
      </div>
    );
  }

  return (
    <div
      className={`px-2 py-1 rounded border ${bgColor} ${borderColor} ${textColor} text-sm overflow-auto max-h-32`}
    >
      {isMultiline ? (
        <pre className="whitespace-pre-wrap font-mono text-xs">{formattedValue}</pre>
      ) : (
        <span>{formattedValue}</span>
      )}
    </div>
  );
}

function formatValue(value: unknown): string {
  if (value === null) return 'null';
  if (value === undefined) return 'undefined';

  if (typeof value === 'object') {
    try {
      return JSON.stringify(value, null, 2);
    } catch {
      return String(value);
    }
  }

  if (typeof value === 'boolean') {
    return value ? 'true' : 'false';
  }

  return String(value);
}

/**
 * Compact diff display for inline use
 */
export interface CompactDiffProps {
  diff: ResourceDiff;
  className?: string;
}

export function CompactDiff({ diff, className = '' }: CompactDiffProps) {
  const originalStr = formatValue(diff.original);
  const currentStr = formatValue(diff.current);

  return (
    <div className={`text-sm ${className}`}>
      <span className="font-medium text-gray-700">{diff.field}:</span>
      <span className="ml-2">
        <span className="line-through text-red-600">{truncate(originalStr, 30)}</span>
        <span className="mx-2 text-gray-400">→</span>
        <span className="text-green-600">{truncate(currentStr, 30)}</span>
      </span>
    </div>
  );
}

function truncate(str: string, maxLen: number): string {
  if (str.length <= maxLen) return str;
  return str.substring(0, maxLen - 3) + '...';
}

/**
 * Summary badge showing number of changes
 */
export interface DiffBadgeProps {
  count: number;
  className?: string;
}

export function DiffBadge({ count, className = '' }: DiffBadgeProps) {
  if (count === 0) return null;

  return (
    <span
      className={`inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium bg-amber-100 text-amber-800 ${className}`}
    >
      {count} 处修改
    </span>
  );
}

export default ResourceDiffViewer;
