/**
 * Unified Designer Toolbar
 *
 * Shared toolbar component for Flow, Report, and Dashboard designers.
 * Provides: title + subtitle + dirty indicator, undo/redo, save button, and a children slot for custom actions.
 */

import React from 'react';
import { Undo2, Redo2, Save, Loader2 } from 'lucide-react';
import { cn } from '~/utils/cn';

export interface DesignerToolbarProps {
  title: string;
  /** Override the title rendering with a custom element (e.g. editable input) */
  titleElement?: React.ReactNode;
  subtitle?: string;
  status?: string;
  isDirty: boolean;
  isSaving: boolean;
  onUndo?: () => void;
  onRedo?: () => void;
  canUndo?: boolean;
  canRedo?: boolean;
  onSave?: () => void | Promise<void>;
  saveLabel?: string;
  /** Right-side custom buttons slot (rendered before the Save button) */
  children?: React.ReactNode;
  className?: string;
  /** data-testid for the root element */
  testId?: string;
}

export function DesignerToolbar({
  title,
  titleElement,
  subtitle,
  status,
  isDirty,
  isSaving,
  onUndo,
  onRedo,
  canUndo = false,
  canRedo = false,
  onSave,
  saveLabel,
  children,
  className,
  testId,
}: DesignerToolbarProps) {
  const showUndoRedo = onUndo || onRedo;

  return (
    <div
      data-testid={testId}
      className={cn(
        'flex items-center justify-between border-b border-gray-200 bg-white px-4 py-3',
        className,
      )}
    >
      {/* Left: Title + Subtitle + Status + Dirty indicator */}
      <div className="flex items-center gap-3">
        {titleElement || <h1 className="text-xl font-semibold text-gray-900">{title}</h1>}
        {subtitle && <span className="text-sm text-gray-600">{subtitle}</span>}
        {status && <StatusBadge status={status} />}
        {isDirty && !isSaving && (
          <span className="rounded bg-orange-100 px-2 py-0.5 text-xs font-medium text-orange-700">
            Unsaved
          </span>
        )}
        {isSaving && (
          <span className="flex items-center gap-1 rounded bg-blue-100 px-2 py-0.5 text-xs font-medium text-blue-700">
            <Loader2 className="h-3 w-3 animate-spin" />
            Saving...
          </span>
        )}
      </div>

      {/* Right: Undo/Redo + Custom children + Save */}
      <div className="flex items-center gap-2">
        {showUndoRedo && (
          <>
            {onUndo && (
              <button
                type="button"
                onClick={onUndo}
                disabled={!canUndo}
                title="Undo (Ctrl+Z)"
                data-testid={testId ? `${testId}-btn-undo` : 'toolbar-btn-undo'}
                className="rounded p-2 text-gray-600 transition-colors hover:bg-gray-100 hover:text-gray-900 disabled:cursor-not-allowed disabled:opacity-30"
              >
                <Undo2 className="h-4 w-4" />
              </button>
            )}
            {onRedo && (
              <button
                type="button"
                onClick={onRedo}
                disabled={!canRedo}
                title="Redo (Ctrl+Y)"
                data-testid={testId ? `${testId}-btn-redo` : 'toolbar-btn-redo'}
                className="rounded p-2 text-gray-600 transition-colors hover:bg-gray-100 hover:text-gray-900 disabled:cursor-not-allowed disabled:opacity-30"
              >
                <Redo2 className="h-4 w-4" />
              </button>
            )}
            <div className="h-6 w-px bg-gray-300" />
          </>
        )}

        {children}

        {onSave && (
          <button
            type="button"
            onClick={onSave}
            disabled={isSaving || !isDirty}
            data-testid={testId ? `${testId}-btn-save` : 'toolbar-btn-save'}
            className="flex items-center gap-1.5 rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-50"
          >
            {isSaving ? <Loader2 className="h-4 w-4 animate-spin" /> : <Save className="h-4 w-4" />}
            {saveLabel || (isSaving ? 'Saving...' : 'Save')}
          </button>
        )}
      </div>
    </div>
  );
}

/** Internal helper: renders a colored status badge */
function StatusBadge({ status }: { status: string }) {
  const lower = status.toLowerCase();

  let colorClasses = 'bg-gray-100 text-gray-800';
  if (lower === 'published' || lower === 'active') {
    colorClasses = 'bg-green-100 text-green-800';
  } else if (lower === 'draft') {
    colorClasses = 'bg-gray-100 text-gray-800';
  } else if (lower === 'error' || lower === 'invalid') {
    colorClasses = 'bg-red-100 text-red-800';
  }

  return (
    <span className={cn('rounded px-2 py-0.5 text-xs font-medium', colorClasses)}>{status}</span>
  );
}

export default DesignerToolbar;
