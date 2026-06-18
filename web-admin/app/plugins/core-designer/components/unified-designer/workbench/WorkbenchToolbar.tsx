import React, { useEffect, useRef, useState } from 'react';
import { Download, History, Redo2, Undo2, Upload } from 'lucide-react';
import { useI18n } from '~/contexts/I18nContext';
import { DESIGNER_I18N, resolveDesignerText } from '~/shared/designer';
import {
  KIND_SWITCH_TARGETS,
  getIncompatibleBlocksForKind,
} from '../registry/kindPolicy';
import type { PageSchemaV3, WorkbenchMode } from '../types';

export type DesignerSaveStatus = 'saved' | 'dirty' | 'saving' | 'invalid' | 'error';

/** Lifecycle state of the publish/unpublish action point. */
export type DesignerPublishStatus = 'draft' | 'publishing' | 'published' | 'unpublishing' | 'error';

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
  aiCopilotEnabled?: boolean;
  /**
   * The persisted page id (pid) when the document is page-bound. Publish /
   * unpublish are only available for a saved page; a local/new document has no
   * pid and the publish button stays disabled.
   */
  pageId?: string;
  publishStatus?: DesignerPublishStatus;
  publishError?: string | null;
  onModeChange: (mode: WorkbenchMode) => void;
  /**
   * Switch the page kind (C4). The toolbar disables any target kind that has
   * incompatible descendant blocks; this is only invoked for a valid target.
   */
  onSwitchKind?: (kind: PageSchemaV3['kind']) => void;
  onUndo: () => void;
  onRedo: () => void;
  onSave: () => void;
  onPublish?: () => void;
  onUnpublish?: () => void;
  onExport?: () => void;
  /** Receives the chosen file from the hidden import input. */
  onImportFile?: (file: File) => void;
  onOpenAiCopilot?: () => void;
  /**
   * Open the version-history panel. Only wired (and the button only enabled) for
   * a saved, page-bound document — a new/local page has no pid and therefore no
   * server-side version history.
   */
  onOpenVersions?: () => void;
}

// C4 — localized labels for the switchable page kinds.
const KIND_LABELS = {
  form: DESIGNER_I18N.unified.kindForm,
  list: DESIGNER_I18N.unified.kindList,
  detail: DESIGNER_I18N.unified.kindDetail,
  dashboard: DESIGNER_I18N.unified.kindDashboard,
} as const;

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
  aiCopilotEnabled,
  pageId,
  publishStatus = 'draft',
  publishError,
  onModeChange,
  onSwitchKind,
  onUndo,
  onRedo,
  onSave,
  onPublish,
  onUnpublish,
  onExport,
  onImportFile,
  onOpenAiCopilot,
  onOpenVersions,
}: WorkbenchToolbarProps) {
  const { locale } = useI18n();
  const saveDisabled = !isDirty || saveStatus === 'saving' || saveStatus === 'invalid';
  const [showLeaveWarning, setShowLeaveWarning] = useState(false);
  const importInputRef = useRef<HTMLInputElement | null>(null);

  // Publish is only meaningful for a saved, clean page: a new/local document has
  // no pid, and a dirty document must be saved first so the published version
  // matches the canvas. While a publish/unpublish round-trip is in flight the
  // button is disabled to prevent double submits.
  const publishBusy = publishStatus === 'publishing' || publishStatus === 'unpublishing';
  const isPublished = publishStatus === 'published';
  const publishDisabled = !pageId || isDirty || publishBusy;

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
        {onSwitchKind ? (
          <label
            className="flex items-center gap-1.5 text-xs font-medium text-slate-500"
            data-testid="designer-kind-switch-label"
          >
            {resolveDesignerText(DESIGNER_I18N.unified.kindSwitchLabel, locale)}
            <select
              data-testid="designer-kind-switch"
              value={KIND_SWITCH_TARGETS.includes(document.kind) ? document.kind : ''}
              onChange={(event) => {
                const next = event.target.value as PageSchemaV3['kind'];
                if (next) onSwitchKind(next);
              }}
              className="rounded-md border border-slate-200 px-2 py-1 text-sm text-slate-700"
            >
              {KIND_SWITCH_TARGETS.includes(document.kind) ? null : (
                <option value="" disabled>
                  {document.kind}
                </option>
              )}
              {KIND_SWITCH_TARGETS.map((kind) => {
                const incompatible =
                  kind === document.kind ? [] : getIncompatibleBlocksForKind(document.blocks, kind);
                const singleRoot = (document.blocks?.length ?? 0) === 1;
                const blocked = kind !== document.kind && (!singleRoot || incompatible.length > 0);
                const blockedText = resolveDesignerText(
                  DESIGNER_I18N.unified.kindSwitchBlocked,
                  locale,
                ).replace('{n}', String(incompatible.length));
                return (
                  <option
                    key={kind}
                    value={kind}
                    disabled={blocked}
                    data-testid={`designer-kind-option-${kind}`}
                    title={blocked ? blockedText : ''}
                  >
                    {resolveDesignerText(KIND_LABELS[kind as keyof typeof KIND_LABELS], locale)}
                    {blocked && incompatible.length ? ` (${incompatible.length})` : ''}
                  </option>
                );
              })}
            </select>
          </label>
        ) : null}
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
        {aiCopilotEnabled ? (
          <button
            type="button"
            data-testid="designer-ai-copilot"
            onClick={onOpenAiCopilot}
            className="rounded-md border border-purple-200 bg-gradient-to-r from-purple-50 to-indigo-50 px-3 py-1.5 text-sm font-medium text-purple-700 hover:from-purple-100 hover:to-indigo-100"
          >
            ✨ AI
          </button>
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
        {/* Export / import — pure client-side JSON round-trip, no backend. */}
        <div className="ml-2 grid grid-cols-2 rounded-md border border-slate-200 bg-white">
          <button
            type="button"
            data-testid="designer-export"
            aria-label={resolveDesignerText(DESIGNER_I18N.unified.exportPage, locale)}
            title={resolveDesignerText(DESIGNER_I18N.unified.exportPage, locale)}
            onClick={onExport}
            className="inline-flex h-8 w-8 items-center justify-center border-r border-slate-200 text-slate-600 hover:bg-slate-50"
          >
            <Download className="h-4 w-4" aria-hidden="true" />
          </button>
          <button
            type="button"
            data-testid="designer-import"
            aria-label={resolveDesignerText(DESIGNER_I18N.unified.importPage, locale)}
            title={resolveDesignerText(DESIGNER_I18N.unified.importPage, locale)}
            onClick={() => importInputRef.current?.click()}
            className="inline-flex h-8 w-8 items-center justify-center text-slate-600 hover:bg-slate-50"
          >
            <Upload className="h-4 w-4" aria-hidden="true" />
          </button>
        </div>
        <input
          ref={importInputRef}
          type="file"
          accept="application/json,.json"
          data-testid="designer-import-input"
          className="hidden"
          onChange={(event) => {
            const file = event.target.files?.[0];
            // Reset the value so selecting the same file twice re-fires change.
            event.target.value = '';
            if (file) onImportFile?.(file);
          }}
        />
        {/* Version history — only for a saved page (GET/POST /api/pages/{pid}/versions). */}
        <button
          type="button"
          data-testid="designer-versions"
          aria-label={resolveDesignerText(DESIGNER_I18N.unified.versionHistory, locale)}
          title={
            !pageId
              ? resolveDesignerText(DESIGNER_I18N.unified.versionsSaveFirst, locale)
              : resolveDesignerText(DESIGNER_I18N.unified.versionHistory, locale)
          }
          disabled={!pageId}
          onClick={onOpenVersions}
          className={`ml-2 inline-flex h-8 w-8 items-center justify-center rounded-md border border-slate-200 ${
            pageId
              ? 'text-slate-600 hover:bg-slate-50'
              : 'cursor-not-allowed text-slate-300'
          }`}
        >
          <History className="h-4 w-4" aria-hidden="true" />
        </button>
        {/* Publish — only for a saved, clean page (POST /api/pages/{pid}/publish). */}
        <button
          type="button"
          data-testid="designer-publish"
          disabled={publishDisabled}
          title={
            !pageId
              ? resolveDesignerText(DESIGNER_I18N.unified.publishSaveFirst, locale)
              : undefined
          }
          onClick={onPublish}
          className={`rounded-md px-3 py-1.5 text-sm font-medium ${
            publishDisabled
              ? 'cursor-not-allowed bg-slate-200 text-slate-500'
              : 'bg-emerald-600 text-white hover:bg-emerald-700'
          }`}
        >
          {resolveDesignerText(
            publishStatus === 'publishing'
              ? DESIGNER_I18N.unified.publishing
              : isPublished
                ? DESIGNER_I18N.unified.published
                : DESIGNER_I18N.unified.publish,
            locale,
          )}
        </button>
        {isPublished || publishStatus === 'unpublishing' ? (
          <button
            type="button"
            data-testid="designer-unpublish"
            disabled={publishBusy || isDirty || !pageId}
            onClick={onUnpublish}
            className={`rounded-md border px-3 py-1.5 text-sm font-medium ${
              publishBusy || isDirty || !pageId
                ? 'cursor-not-allowed border-slate-200 text-slate-400'
                : 'border-slate-300 text-slate-700 hover:bg-slate-50'
            }`}
          >
            {resolveDesignerText(
              publishStatus === 'unpublishing'
                ? DESIGNER_I18N.unified.unpublishing
                : DESIGNER_I18N.unified.unpublish,
              locale,
            )}
          </button>
        ) : null}
        {publishStatus === 'error' && publishError ? (
          <span
            className="max-w-[320px] truncate rounded-md border border-red-200 bg-red-50 px-2 py-1 text-xs text-red-700"
            data-testid="designer-publish-error"
            title={publishError}
          >
            {publishError}
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
