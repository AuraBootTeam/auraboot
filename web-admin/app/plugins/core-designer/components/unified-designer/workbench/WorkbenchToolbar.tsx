import React, { useEffect, useState } from 'react';
import { Redo2, Undo2 } from 'lucide-react';
import { useI18n } from '~/contexts/I18nContext';
import { DESIGNER_I18N, resolveDesignerText } from '~/shared/designer';
import type { PageSchemaV3, WorkbenchMode } from '../types';

export type DesignerSaveStatus = 'saved' | 'dirty' | 'saving' | 'invalid' | 'error';

interface WorkbenchToolbarProps {
  document: PageSchemaV3;
  mode: WorkbenchMode;
  isDirty: boolean;
  saveStatus: DesignerSaveStatus;
  saveError?: string | null;
  validationErrorCount: number;
  canUndo: boolean;
  canRedo: boolean;
  returnHref?: string;
  onModeChange: (mode: WorkbenchMode) => void;
  onUndo: () => void;
  onRedo: () => void;
  onSave: () => void;
}

export function WorkbenchToolbar({
  document,
  mode,
  isDirty,
  saveStatus,
  saveError,
  validationErrorCount,
  canUndo,
  canRedo,
  returnHref,
  onModeChange,
  onUndo,
  onRedo,
  onSave,
}: WorkbenchToolbarProps) {
  const { locale } = useI18n();
  const saveDisabled = !isDirty || saveStatus === 'saving' || saveStatus === 'invalid';
  const [showLeaveWarning, setShowLeaveWarning] = useState(false);

  useEffect(() => {
    if (!isDirty) setShowLeaveWarning(false);
  }, [isDirty]);

  const handleReturnClick = (event: React.MouseEvent<HTMLAnchorElement>) => {
    if (!isDirty) return;
    event.preventDefault();
    setShowLeaveWarning(true);
  };

  return (
    <div className="flex min-h-14 flex-wrap items-center justify-between gap-2 border-b border-slate-200 bg-white px-4 py-2">
      <div className="min-w-[150px] flex-1">
        <div className="text-sm font-semibold text-slate-900">{resolveTitle(document.title, locale)}</div>
        <div className="font-mono text-xs text-slate-400">{document.id}</div>
      </div>
      <div className="flex max-w-full shrink-0 items-center gap-2 overflow-x-auto">
        {returnHref ? (
          <a
            href={returnHref}
            data-testid="designer-return-link"
            onClick={handleReturnClick}
            className="rounded-md border border-slate-200 px-3 py-1.5 text-sm font-medium text-slate-600 hover:bg-slate-50"
          >
            {resolveDesignerText(DESIGNER_I18N.unified.pages, locale)}
          </a>
        ) : null}
        <button
          type="button"
          data-testid="designer-mode-preview"
          onClick={() => onModeChange('preview')}
          className={`rounded-md border border-slate-200 px-3 py-1.5 text-sm ${
            mode === 'preview'
              ? 'bg-blue-50 font-medium text-blue-700'
              : 'text-slate-600 hover:bg-slate-50'
          }`}
        >
          {resolveDesignerText(DESIGNER_I18N.unified.preview, locale)}
        </button>
        <div className="grid grid-cols-2 rounded-md border border-slate-200 bg-white">
          <button
            type="button"
            data-testid="designer-undo"
            aria-label={resolveDesignerText(DESIGNER_I18N.unified.undo, locale)}
            title={resolveDesignerText(DESIGNER_I18N.unified.undo, locale)}
            disabled={!canUndo}
            onClick={onUndo}
            className={`inline-flex h-8 w-8 items-center justify-center border-r border-slate-200 ${
              canUndo
                ? 'text-slate-600 hover:bg-slate-50'
                : 'cursor-not-allowed text-slate-300'
            }`}
          >
            <Undo2 className="h-4 w-4" aria-hidden="true" />
          </button>
          <button
            type="button"
            data-testid="designer-redo"
            aria-label={resolveDesignerText(DESIGNER_I18N.unified.redo, locale)}
            title={resolveDesignerText(DESIGNER_I18N.unified.redo, locale)}
            disabled={!canRedo}
            onClick={onRedo}
            className={`inline-flex h-8 w-8 items-center justify-center ${
              canRedo
                ? 'text-slate-600 hover:bg-slate-50'
                : 'cursor-not-allowed text-slate-300'
            }`}
          >
            <Redo2 className="h-4 w-4" aria-hidden="true" />
          </button>
        </div>
        <div className="ml-2 grid grid-cols-2 rounded-md border border-slate-200 bg-slate-100 p-0.5">
          <button
            type="button"
            data-testid="designer-mode-edit"
            onClick={() => onModeChange('edit')}
            className={`rounded px-3 py-1.5 text-sm ${
              mode === 'edit' ? 'bg-white font-medium text-blue-700 shadow-sm' : 'text-slate-500'
            }`}
          >
            {resolveDesignerText(DESIGNER_I18N.unified.edit, locale)}
          </button>
          <button
            type="button"
            data-testid="designer-mode-layout"
            onClick={() => onModeChange('layout')}
            className={`rounded px-3 py-1.5 text-sm ${
              mode === 'layout' ? 'bg-white font-medium text-blue-700 shadow-sm' : 'text-slate-500'
            }`}
          >
            {resolveDesignerText(DESIGNER_I18N.unified.layout, locale)}
          </button>
        </div>
        <span
          className={`ml-2 rounded-md px-2 py-1 text-xs font-medium ${getStatusClassName(saveStatus)}`}
          data-testid="designer-dirty-state"
        >
          {getStatusLabel(saveStatus, validationErrorCount, locale)}
        </span>
        {saveError ? (
          <span
            className="max-w-[320px] truncate rounded-md border border-red-200 bg-red-50 px-2 py-1 text-xs text-red-700"
            data-testid="designer-save-error"
            title={saveError}
          >
            {saveError}
          </span>
        ) : null}
        {showLeaveWarning && returnHref ? (
          <span
            className="inline-flex items-center gap-2 rounded-md border border-amber-200 bg-amber-50 px-2 py-1 text-xs text-amber-800"
            data-testid="designer-leave-warning"
          >
            <span>{resolveDesignerText(DESIGNER_I18N.unified.unsavedChanges, locale)}</span>
            <button
              type="button"
              className="font-medium text-amber-900 underline-offset-2 hover:underline"
              data-testid="designer-leave-cancel"
              onClick={() => setShowLeaveWarning(false)}
            >
              {resolveDesignerText(DESIGNER_I18N.unified.stay, locale)}
            </button>
            <a
              href={returnHref}
              className="font-medium text-amber-900 underline-offset-2 hover:underline"
              data-testid="designer-leave-confirm"
            >
              {resolveDesignerText(DESIGNER_I18N.unified.leave, locale)}
            </a>
          </span>
        ) : null}
        <button
          type="button"
          data-testid="designer-save"
          disabled={saveDisabled}
          onClick={onSave}
          className={`rounded-md px-3 py-1.5 text-sm font-medium ${
            saveDisabled
              ? 'cursor-not-allowed bg-slate-200 text-slate-500'
              : 'bg-blue-600 text-white hover:bg-blue-700'
          }`}
        >
          {resolveDesignerText(
            saveStatus === 'saving' ? DESIGNER_I18N.unified.saving : DESIGNER_I18N.unified.save,
            locale,
          )}
        </button>
      </div>
    </div>
  );
}

function resolveTitle(title: PageSchemaV3['title'], locale: string): string {
  const fallback = resolveDesignerText(DESIGNER_I18N.unified.untitled, locale);
  if (!title) return fallback;
  if (typeof title === 'string') return title;
  return title[locale] || title['en-US'] || title.en || title['zh-CN'] || fallback;
}

function getStatusLabel(
  status: DesignerSaveStatus,
  validationErrorCount: number,
  locale: string,
): string {
  if (status === 'dirty') return resolveDesignerText(DESIGNER_I18N.unified.statusUnsaved, locale);
  if (status === 'saving') return resolveDesignerText(DESIGNER_I18N.unified.saving, locale);
  if (status === 'invalid') {
    return resolveDesignerText(DESIGNER_I18N.unified.statusInvalid, locale, {
      count: validationErrorCount,
    });
  }
  if (status === 'error') return resolveDesignerText(DESIGNER_I18N.unified.statusError, locale);
  return resolveDesignerText(DESIGNER_I18N.unified.statusSaved, locale);
}

function getStatusClassName(status: DesignerSaveStatus): string {
  if (status === 'dirty') return 'bg-amber-50 text-amber-700';
  if (status === 'saving') return 'bg-blue-50 text-blue-700';
  if (status === 'invalid' || status === 'error') return 'bg-red-50 text-red-700';
  return 'bg-emerald-50 text-emerald-700';
}
