/**
 * VersionCompareView - Side-by-side version comparison panel.
 * Shows field-level differences between two version snapshots.
 */

import React, { useState } from 'react';
import type { VersionEntry } from './types';
import { getOperationConfig } from './types';
import {
  diffSnapshots,
  getChangedEntries,
  formatDiffValue,
  formatDiffValueExpanded,
  type DiffEntry,
  type DiffType,
} from './diffUtils';

interface VersionCompareViewProps {
  /** The older version (left side) */
  versionA: VersionEntry;
  /** The newer version (right side) */
  versionB: VersionEntry;
  /** Close compare view */
  onClose: () => void;
}

const DIFF_STYLES: Record<DiffType, { bg: string; label: string; icon: string }> = {
  added: { bg: 'bg-green-50', label: 'Added', icon: '+' },
  removed: { bg: 'bg-red-50', label: 'Removed', icon: '-' },
  changed: { bg: 'bg-yellow-50', label: 'Changed', icon: '~' },
  unchanged: { bg: 'bg-white', label: '', icon: ' ' },
};

function DiffRow({ entry, showUnchanged }: { entry: DiffEntry; showUnchanged: boolean }) {
  const [expanded, setExpanded] = useState(false);
  const style = DIFF_STYLES[entry.type];

  if (entry.type === 'unchanged' && !showUnchanged) return null;

  const isExpandable =
    entry.type !== 'unchanged' &&
    (typeof entry.oldValue === 'object' || typeof entry.newValue === 'object');

  return (
    <div className={`${style.bg} border-b border-gray-100`}>
      <div
        className={`flex items-start gap-2 px-4 py-2 ${isExpandable ? 'cursor-pointer hover:opacity-80' : ''}`}
        onClick={() => isExpandable && setExpanded(!expanded)}
      >
        {/* Diff icon */}
        <span
          className={`mt-0.5 flex h-5 w-5 flex-shrink-0 items-center justify-center rounded text-xs font-bold ${
            entry.type === 'added'
              ? 'bg-green-200 text-green-800'
              : entry.type === 'removed'
                ? 'bg-red-200 text-red-800'
                : entry.type === 'changed'
                  ? 'bg-yellow-200 text-yellow-800'
                  : 'bg-gray-100 text-gray-400'
          }`}
        >
          {style.icon}
        </span>

        {/* Field name */}
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2">
            <span className="text-sm font-medium text-gray-900">{entry.label}</span>
            {entry.type !== 'unchanged' && (
              <span
                className={`rounded px-1.5 py-0.5 text-xs ${
                  entry.type === 'added'
                    ? 'bg-green-100 text-green-700'
                    : entry.type === 'removed'
                      ? 'bg-red-100 text-red-700'
                      : 'bg-yellow-100 text-yellow-700'
                }`}
              >
                {style.label}
              </span>
            )}
            {isExpandable && (
              <span className="text-xs text-gray-400">{expanded ? '[-]' : '[+]'}</span>
            )}
          </div>

          {/* Compact value display */}
          {!expanded && (
            <div className="mt-1 flex gap-4">
              {entry.type !== 'added' && (
                <div className="max-w-[200px] truncate text-xs text-gray-500">
                  <span className="text-gray-400">A: </span>
                  {formatDiffValue(entry.oldValue)}
                </div>
              )}
              {entry.type !== 'removed' && (
                <div className="max-w-[200px] truncate text-xs text-gray-500">
                  <span className="text-gray-400">B: </span>
                  {formatDiffValue(entry.newValue)}
                </div>
              )}
            </div>
          )}
        </div>
      </div>

      {/* Expanded JSON view */}
      {expanded && (
        <div className="grid grid-cols-2 gap-0 border-t border-gray-200 text-xs">
          <div className="overflow-x-auto border-r border-gray-200 bg-red-50/50 p-3">
            <div className="mb-1 text-xs font-medium text-gray-500">
              v{entry.type !== 'added' ? 'A (old)' : '(N/A)'}
            </div>
            <pre className="font-mono break-words whitespace-pre-wrap text-gray-700">
              {entry.type !== 'added' ? formatDiffValueExpanded(entry.oldValue) : '-'}
            </pre>
          </div>
          <div className="overflow-x-auto bg-green-50/50 p-3">
            <div className="mb-1 text-xs font-medium text-gray-500">
              v{entry.type !== 'removed' ? 'B (new)' : '(N/A)'}
            </div>
            <pre className="font-mono break-words whitespace-pre-wrap text-gray-700">
              {entry.type !== 'removed' ? formatDiffValueExpanded(entry.newValue) : '-'}
            </pre>
          </div>
        </div>
      )}
    </div>
  );
}

export function VersionCompareView({ versionA, versionB, onClose }: VersionCompareViewProps) {
  const [showUnchanged, setShowUnchanged] = useState(false);
  const diffEntries = diffSnapshots(versionA.schemaSnapshot, versionB.schemaSnapshot);
  const changedEntries = getChangedEntries(diffEntries);

  const opA = getOperationConfig(versionA.operation);
  const opB = getOperationConfig(versionB.operation);

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50">
      <div className="flex max-h-[80vh] w-[720px] flex-col rounded-lg bg-white shadow-xl">
        {/* Header */}
        <div className="flex items-center justify-between border-b border-gray-200 px-6 py-4">
          <div>
            <h2 className="text-lg font-semibold text-gray-900">Version Comparison</h2>
            <p className="mt-0.5 text-xs text-gray-500">
              {changedEntries.length} change{changedEntries.length !== 1 ? 's' : ''} found
              {' / '}
              {diffEntries.length} total field{diffEntries.length !== 1 ? 's' : ''}
            </p>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="text-gray-400 transition-colors hover:text-gray-600"
          >
            <svg className="h-5 w-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M6 18L18 6M6 6l12 12"
              />
            </svg>
          </button>
        </div>

        {/* Version labels */}
        <div className="grid grid-cols-2 gap-0 border-b border-gray-200">
          <div className="border-r border-gray-200 bg-red-50/30 px-4 py-2">
            <div className="flex items-center gap-2">
              <span className="text-sm font-semibold text-gray-900">v{versionA.version}</span>
              <span className={`rounded px-1.5 py-0.5 text-xs ${opA.badgeBg} ${opA.badgeText}`}>
                {opA.label}
              </span>
            </div>
            <div className="mt-0.5 text-xs text-gray-500">(A) Older version</div>
          </div>
          <div className="bg-green-50/30 px-4 py-2">
            <div className="flex items-center gap-2">
              <span className="text-sm font-semibold text-gray-900">v{versionB.version}</span>
              <span className={`rounded px-1.5 py-0.5 text-xs ${opB.badgeBg} ${opB.badgeText}`}>
                {opB.label}
              </span>
            </div>
            <div className="mt-0.5 text-xs text-gray-500">(B) Newer version</div>
          </div>
        </div>

        {/* Filter bar */}
        <div className="flex items-center gap-3 border-b border-gray-200 bg-gray-50 px-4 py-2">
          <label className="flex cursor-pointer items-center gap-1.5 text-xs text-gray-600">
            <input
              type="checkbox"
              checked={showUnchanged}
              onChange={(e) => setShowUnchanged(e.target.checked)}
              className="h-3.5 w-3.5 rounded border-gray-300 text-blue-600"
            />
            Show unchanged fields
          </label>
          <div className="ml-auto flex items-center gap-2 text-xs text-gray-400">
            <span className="inline-flex items-center gap-1">
              <span className="h-3 w-3 rounded bg-green-200" /> Added
            </span>
            <span className="inline-flex items-center gap-1">
              <span className="h-3 w-3 rounded bg-red-200" /> Removed
            </span>
            <span className="inline-flex items-center gap-1">
              <span className="h-3 w-3 rounded bg-yellow-200" /> Changed
            </span>
          </div>
        </div>

        {/* Diff entries */}
        <div className="flex-1 overflow-y-auto">
          {changedEntries.length === 0 && !showUnchanged ? (
            <div className="flex flex-col items-center justify-center py-12 text-gray-400">
              <svg className="mb-2 h-10 w-10" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={1.5}
                  d="M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z"
                />
              </svg>
              <span className="text-sm">No differences found</span>
              <span className="mt-1 text-xs">These two versions are identical</span>
            </div>
          ) : (
            diffEntries.map((entry) => (
              <DiffRow key={entry.path} entry={entry} showUnchanged={showUnchanged} />
            ))
          )}
        </div>

        {/* Footer */}
        <div className="flex justify-end border-t border-gray-200 px-6 py-3">
          <button
            type="button"
            onClick={onClose}
            className="rounded-md border border-gray-300 bg-white px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50"
          >
            Close
          </button>
        </div>
      </div>
    </div>
  );
}

export default VersionCompareView;
