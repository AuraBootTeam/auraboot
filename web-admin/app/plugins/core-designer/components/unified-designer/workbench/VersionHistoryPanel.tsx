import React, { useEffect, useState } from 'react';
import { useI18n } from '~/contexts/I18nContext';
import { DESIGNER_I18N, resolveDesignerText } from '~/shared/designer';
import type { PageSchemaVersionDTO } from '../../studio/services/page-manager/api-types';
import {
  createPageVersion,
  getPageVersions,
  rollbackPageToVersion,
} from '../persistence/pageSchemaV3Repository';

export interface VersionHistoryPanelProps {
  /** The persisted page id (pid). The panel is only opened for a saved page. */
  pid: string;
  open: boolean;
  onClose: () => void;
  /**
   * Called after a successful rollback so the workbench can reload the page
   * document (the backend has restored the target snapshot's blocks onto the
   * live page) and close the panel.
   */
  onRolledBack: () => void | Promise<void>;
}

type PanelStatus = 'idle' | 'loading' | 'creating' | 'rolling-back' | 'error';

function operationLabel(operation: string | undefined, locale: string): string {
  switch ((operation ?? '').toLowerCase()) {
    case 'create':
      return resolveDesignerText(DESIGNER_I18N.unified.versionOperationCreate, locale);
    case 'update':
      return resolveDesignerText(DESIGNER_I18N.unified.versionOperationUpdate, locale);
    case 'publish':
      return resolveDesignerText(DESIGNER_I18N.unified.versionOperationPublish, locale);
    case 'archive':
      return resolveDesignerText(DESIGNER_I18N.unified.versionOperationArchive, locale);
    case 'delete':
      return resolveDesignerText(DESIGNER_I18N.unified.versionOperationDelete, locale);
    case 'rollback':
    case 'restore':
      return resolveDesignerText(DESIGNER_I18N.unified.versionOperationRestore, locale);
    case 'snapshot':
      return resolveDesignerText(DESIGNER_I18N.unified.versionOperationSnapshot, locale);
    default:
      // Unknown operation codes (e.g. backend-internal "pre_rollback_backup")
      // are shown verbatim so nothing is silently dropped from the audit trail.
      return operation ?? '';
  }
}

/**
 * Display label for a version row. Prefers the semantic version, then the
 * numeric version, and finally falls back to the history id (`#<id>`) so a row
 * whose `version`/`semver` the backend did not populate (e.g. ad-hoc snapshot
 * rows) never renders a bare "vnull".
 */
function versionLabel(version: PageSchemaVersionDTO): string {
  if (version.semver) return version.semver;
  if (version.version != null) return `v${version.version}`;
  return `#${version.id}`;
}

function formatTime(time: string | undefined): string {
  if (!time) return '';
  const parsed = new Date(time);
  if (Number.isNaN(parsed.getTime())) return time;
  return parsed.toLocaleString();
}

/**
 * VersionHistoryPanel — version list + create snapshot + rollback action points
 * for a saved unified-designer page.
 *
 * Backed by the real version endpoints (PageSchemaController):
 *   - GET    /api/pages/{pid}/versions               → list
 *   - POST   /api/pages/{pid}/versions               → create snapshot
 *   - POST   /api/pages/{pid}/rollback/{historyId}   → rollback (restores blocks)
 *
 * Diff/compare UI is intentionally out of scope for this slice (the
 * compareVersions endpoint exists; a follow-up adds the diff view).
 */
export function VersionHistoryPanel({ pid, open, onClose, onRolledBack }: VersionHistoryPanelProps) {
  const { locale } = useI18n();
  const [versions, setVersions] = useState<PageSchemaVersionDTO[]>([]);
  const [status, setStatus] = useState<PanelStatus>('idle');
  const [error, setError] = useState<string | null>(null);
  const [snapshotReason, setSnapshotReason] = useState('');
  // historyId currently awaiting a second confirm click before rollback fires.
  const [confirmingRollbackId, setConfirmingRollbackId] = useState<number | null>(null);

  const busy = status === 'loading' || status === 'creating' || status === 'rolling-back';

  const refresh = async () => {
    setStatus('loading');
    setError(null);
    const result = await getPageVersions(pid);
    if (!result.ok) {
      setStatus('error');
      setError(result.error ?? null);
      return;
    }
    setVersions(result.data ?? []);
    setStatus('idle');
  };

  // Load (and reload) the version list every time the panel is opened so it
  // reflects snapshots/rollbacks made in other tabs or earlier in this session.
  useEffect(() => {
    if (!open) return;
    setConfirmingRollbackId(null);
    void refresh();
    // refresh closes over pid; re-run when the panel opens or the page changes.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, pid]);

  if (!open) return null;

  const handleCreateSnapshot = async () => {
    setStatus('creating');
    setError(null);
    const result = await createPageVersion(pid, snapshotReason.trim());
    if (!result.ok) {
      setStatus('error');
      setError(result.error ?? null);
      return;
    }
    setSnapshotReason('');
    await refresh();
  };

  const handleRollback = async (historyId: number) => {
    setStatus('rolling-back');
    setError(null);
    const reason = resolveDesignerText(DESIGNER_I18N.unified.versionRollback, locale);
    const result = await rollbackPageToVersion(pid, historyId, reason);
    if (!result.ok) {
      setStatus('error');
      setError(result.error ?? null);
      setConfirmingRollbackId(null);
      return;
    }
    setConfirmingRollbackId(null);
    setStatus('idle');
    // The backend has restored the snapshot onto the live page; hand control to
    // the workbench to reload the document and close the panel.
    await onRolledBack();
  };

  return (
    <div
      className="fixed inset-0 z-50 flex justify-end bg-black/30"
      data-testid="version-history-backdrop"
      onClick={onClose}
    >
      <div
        className="flex h-full w-[440px] max-w-full flex-col bg-white shadow-xl"
        data-testid="version-history-panel"
        onClick={(event) => event.stopPropagation()}
      >
        <div className="flex items-center justify-between border-b border-slate-200 px-5 py-4">
          <h2 className="text-base font-semibold text-slate-900">
            {resolveDesignerText(DESIGNER_I18N.unified.versionHistory, locale)}
          </h2>
          <button
            type="button"
            data-testid="version-panel-close"
            onClick={onClose}
            aria-label={resolveDesignerText(DESIGNER_I18N.unified.versionClose, locale)}
            className="rounded-md px-2 py-1 text-slate-400 hover:bg-slate-50 hover:text-slate-600"
          >
            &times;
          </button>
        </div>

        {/* Create snapshot */}
        <div className="border-b border-slate-200 px-5 py-4">
          <input
            type="text"
            value={snapshotReason}
            onChange={(event) => setSnapshotReason(event.target.value)}
            placeholder={resolveDesignerText(DESIGNER_I18N.unified.versionSnapshotReason, locale)}
            data-testid="version-snapshot-reason"
            disabled={busy}
            className="mb-2 w-full rounded-md border border-slate-300 px-3 py-1.5 text-sm text-slate-700 outline-none focus:border-blue-500 disabled:opacity-50"
          />
          <button
            type="button"
            data-testid="version-create-snapshot"
            onClick={handleCreateSnapshot}
            disabled={busy}
            className={`w-full rounded-md px-3 py-1.5 text-sm font-medium ${
              busy
                ? 'cursor-not-allowed bg-slate-200 text-slate-500'
                : 'bg-blue-600 text-white hover:bg-blue-700'
            }`}
          >
            {resolveDesignerText(
              status === 'creating'
                ? DESIGNER_I18N.unified.versionCreatingSnapshot
                : DESIGNER_I18N.unified.versionCreateSnapshot,
              locale,
            )}
          </button>
        </div>

        {error ? (
          <div
            className="mx-5 mt-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-xs text-red-700"
            data-testid="version-panel-error"
          >
            {error}
          </div>
        ) : null}

        {/* Version list */}
        <div className="min-h-0 flex-1 overflow-y-auto px-5 py-4" data-testid="version-list">
          {status === 'loading' && versions.length === 0 ? (
            <div className="py-8 text-center text-sm text-slate-400" data-testid="version-loading">
              {resolveDesignerText(DESIGNER_I18N.unified.versionLoading, locale)}
            </div>
          ) : versions.length === 0 ? (
            <div className="py-8 text-center text-sm text-slate-400" data-testid="version-empty">
              {resolveDesignerText(DESIGNER_I18N.unified.versionEmpty, locale)}
            </div>
          ) : (
            <ul className="flex flex-col gap-2">
              {versions.map((version) => {
                const historyId = version.id;
                const isConfirming = confirmingRollbackId === historyId;
                return (
                  <li
                    key={historyId}
                    data-testid={`version-row-${historyId}`}
                    data-version={version.version}
                    className="rounded-md border border-slate-200 px-3 py-2.5"
                  >
                    <div className="flex items-center justify-between gap-2">
                      <div className="min-w-0">
                        <div className="flex items-center gap-2">
                          <span className="text-sm font-medium text-slate-900">
                            {resolveDesignerText(DESIGNER_I18N.unified.versionNumber, locale)}{' '}
                            {versionLabel(version)}
                          </span>
                          <span className="rounded bg-slate-100 px-1.5 py-0.5 text-[11px] text-slate-600">
                            {operationLabel(version.operation, locale)}
                          </span>
                          {version.isCurrent ? (
                            <span
                              className="rounded bg-emerald-50 px-1.5 py-0.5 text-[11px] font-medium text-emerald-700"
                              data-testid={`version-current-${historyId}`}
                            >
                              {resolveDesignerText(DESIGNER_I18N.unified.versionCurrent, locale)}
                            </span>
                          ) : null}
                        </div>
                        <div className="mt-0.5 truncate text-xs text-slate-400">
                          {formatTime(version.operationTime)}
                          {version.operatorPid ? ` · ${version.operatorPid}` : ''}
                        </div>
                        {version.description ? (
                          <div className="mt-0.5 truncate text-xs text-slate-500" title={version.description}>
                            {version.description}
                          </div>
                        ) : null}
                      </div>
                      <button
                        type="button"
                        data-testid={`version-rollback-${historyId}`}
                        onClick={() => setConfirmingRollbackId(historyId)}
                        disabled={busy}
                        className={`shrink-0 rounded-md border px-2.5 py-1 text-xs font-medium ${
                          busy
                            ? 'cursor-not-allowed border-slate-200 text-slate-400'
                            : 'border-slate-300 text-slate-700 hover:bg-slate-50'
                        }`}
                      >
                        {resolveDesignerText(DESIGNER_I18N.unified.versionRollback, locale)}
                      </button>
                    </div>

                    {isConfirming ? (
                      <div
                        className="mt-2 rounded-md border border-amber-200 bg-amber-50 px-2.5 py-2 text-xs text-amber-800"
                        data-testid={`version-rollback-confirm-${historyId}`}
                      >
                        <div className="mb-2">
                          {resolveDesignerText(DESIGNER_I18N.unified.versionRollbackConfirm, locale)}
                        </div>
                        <div className="flex items-center gap-2">
                          <button
                            type="button"
                            data-testid={`version-rollback-confirm-yes-${historyId}`}
                            onClick={() => handleRollback(historyId)}
                            disabled={busy}
                            className={`rounded-md px-2.5 py-1 font-medium ${
                              busy
                                ? 'cursor-not-allowed bg-slate-200 text-slate-500'
                                : 'bg-amber-600 text-white hover:bg-amber-700'
                            }`}
                          >
                            {resolveDesignerText(
                              status === 'rolling-back'
                                ? DESIGNER_I18N.unified.versionRollingBack
                                : DESIGNER_I18N.unified.versionRollbackConfirmYes,
                              locale,
                            )}
                          </button>
                          <button
                            type="button"
                            data-testid={`version-rollback-cancel-${historyId}`}
                            onClick={() => setConfirmingRollbackId(null)}
                            disabled={busy}
                            className="rounded-md border border-amber-300 px-2.5 py-1 font-medium text-amber-900 hover:bg-amber-100"
                          >
                            {resolveDesignerText(DESIGNER_I18N.unified.versionRollbackCancel, locale)}
                          </button>
                        </div>
                      </div>
                    ) : null}
                  </li>
                );
              })}
            </ul>
          )}
        </div>
      </div>
    </div>
  );
}
