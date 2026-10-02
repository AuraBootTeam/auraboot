/**
 * ClonePageDialog
 *
 * Dialog for cloning an existing page (any kind) with a new name and page key.
 * Fetches full page DSL from the API, then creates a new page with the same
 * blocks/layout but user-specified name and key.
 *
 * @since 3.2.0
 */

import React, { useState, useId } from 'react';
import { createPage, getPageByPid } from '~/plugins/core-designer/components/studio/services/page-manager/pageApi';
import type { ApiPageType } from '~/plugins/core-designer/components/studio/services/page-manager/api-types';
import { CURRENT_SCHEMA_VERSION } from '~/framework/meta/migration';
import { useI18n } from '~/contexts/I18nContext';

interface ClonePageDialogProps {
  open: boolean;
  onClose: () => void;
  /** The page to clone — only needs id, name, kind */
  sourcePage: {
    id: string;
    name?: string;
    title: string;
    mode?: string;
  };
  onSuccess: (newPid: string) => void;
}

export const ClonePageDialog: React.FC<ClonePageDialogProps> = ({
  open,
  onClose,
  sourcePage,
  onSuccess,
}) => {
  const { t } = useI18n();
  const dialogId = useId();
  const displayName = sourcePage.title || sourcePage.name || t('designer_page_dialog.page');
  const [name, setName] = useState(() => t('designer_page_dialog.clone_default', { name: displayName }));
  const [pageKey, setPageKey] = useState(`page_clone_${Date.now().toString(36)}`);
  const [creating, setCreating] = useState(false);
  const [error, setError] = useState<string | null>(null);

  if (!open) return null;

  const handleClone = async () => {
    const trimmedName = name.trim();
    const trimmedKey = pageKey.trim();
    if (!trimmedName || !trimmedKey) return;

    setCreating(true);
    setError(null);
    try {
      // Fetch full DSL so we copy blocks + layout
      const fetchResult = await getPageByPid(sourcePage.id);
      if (!fetchResult || fetchResult.code !== '0' || !fetchResult.data) {
        throw new Error(fetchResult?.message || t('designer_page_dialog.load_failed'));
      }
      const source = fetchResult.data;

      const result = await createPage({
        name: trimmedName,
        pageKey: trimmedKey,
        title: trimmedName,
        kind: (source.kind || 'list') as ApiPageType,
        blocks: source.blocks || [],
        layout: source.layout,
        metaInfo: {
          componentCount: source.blocks?.length ?? 0,
          clonedFrom: source.pid,
        },
        semver: '0.1.0',
        schemaVersion: CURRENT_SCHEMA_VERSION,
      } as any);

      if (!result || result.code !== '0') {
        throw new Error(result?.message || t('designer_page_dialog.clone_failed'));
      }

      const newPid = result.data?.pid;
      if (!newPid?.trim()) throw new Error(t('designer_page_dialog.missing_pid'));
      onSuccess(newPid);
      onClose();
    } catch (err) {
      setError(err instanceof Error ? err.message : t('designer_page_dialog.clone_failed'));
    } finally {
      setCreating(false);
    }
  };

  const handleOverlayClick = () => {
    if (!creating) onClose();
  };

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/40"
      onClick={handleOverlayClick}
    >
      <div
        className="w-[440px] rounded-xl bg-white p-6 shadow-2xl"
        onClick={(e) => e.stopPropagation()}
        role="dialog"
        aria-modal="true"
        aria-labelledby={`${dialogId}-title`}
        data-testid="clone-page-dialog"
      >
        {/* Header */}
        <div className="mb-5 flex items-center justify-between">
          <h2 id={`${dialogId}-title`} className="text-lg font-semibold text-gray-900">{t('designer_page_dialog.clone')}</h2>
          <button
            onClick={onClose}
            aria-label={t('designer_page_dialog.close')}
            disabled={creating}
            className="rounded-lg p-1.5 text-gray-400 hover:bg-gray-100 hover:text-gray-600 disabled:opacity-50"
          >
            <svg className="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
            </svg>
          </button>
        </div>

        {/* Source info */}
        <div className="mb-5 flex items-center gap-3 rounded-lg bg-purple-50 px-4 py-3">
          <svg className="h-5 w-5 flex-shrink-0 text-purple-500" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={2}
              d="M8 16H6a2 2 0 01-2-2V6a2 2 0 012-2h8a2 2 0 012 2v2m-6 12h8a2 2 0 002-2v-8a2 2 0 00-2-2h-8a2 2 0 00-2 2v8a2 2 0 002 2z"
            />
          </svg>
          <div className="min-w-0">
            <div className="text-xs font-medium text-purple-700">{t('designer_page_dialog.source')}</div>
            <div className="truncate text-sm font-semibold text-purple-900">{displayName}</div>
          </div>
        </div>

        {/* Form */}
        <div className="space-y-4">
          <div>
            <label htmlFor={`${dialogId}-name`} className="mb-1.5 block text-sm font-medium text-gray-700">
              {t('designer_page_dialog.new_name')} <span className="text-red-500">*</span>
            </label>
            <input
              type="text"
              value={name}
              onChange={(e) => setName(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && handleClone()}
              className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm shadow-sm transition focus:border-purple-500 focus:outline-none focus:ring-2 focus:ring-purple-500/20"
              id={`${dialogId}-name`}
              data-testid="clone-name-input"
              autoFocus
              disabled={creating}
              placeholder={t('designer_page_dialog.name_hint')}
            />
          </div>
          <div>
            <label htmlFor={`${dialogId}-key`} className="mb-1.5 block text-sm font-medium text-gray-700">
              {t('designer_page_dialog.page_key')} <span className="text-red-500">*</span>
            </label>
            <input
              type="text"
              value={pageKey}
              onChange={(e) => setPageKey(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && handleClone()}
              className="w-full rounded-lg border border-gray-300 px-3 py-2 font-mono text-sm shadow-sm transition focus:border-purple-500 focus:outline-none focus:ring-2 focus:ring-purple-500/20"
              id={`${dialogId}-key`}
              data-testid="clone-key-input"
              disabled={creating}
              placeholder="unique_page_key"
            />
            <p className="mt-1 text-xs text-gray-500">
              {t('designer_page_dialog.key_hint')}
            </p>
          </div>

          {error && (
            <div role="alert" className="flex items-start gap-2 rounded-lg bg-red-50 px-4 py-3 text-sm text-red-700">
              <svg className="mt-0.5 h-4 w-4 flex-shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 8v4m0 4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
              </svg>
              <span>{error}</span>
            </div>
          )}
        </div>

        {/* Actions */}
        <div className="mt-6 flex justify-end gap-3">
          <button
            onClick={onClose}
            disabled={creating}
            className="rounded-lg border border-gray-300 px-4 py-2 text-sm font-medium text-gray-700 transition hover:bg-gray-50 disabled:opacity-50"
          >
            {t('designer_page_dialog.cancel')}
          </button>
          <button
            onClick={handleClone}
            disabled={creating || !name.trim() || !pageKey.trim()}
            className="flex items-center gap-2 rounded-lg bg-purple-600 px-4 py-2 text-sm font-medium text-white transition hover:bg-purple-700 disabled:cursor-not-allowed disabled:opacity-50"
            data-testid="clone-confirm-btn"
          >
            {creating ? (
              <>
                <div className="h-4 w-4 animate-spin rounded-full border-2 border-white/30 border-t-white" />
                {t('designer_page_dialog.cloning')}
              </>
            ) : (
              <>
                <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={2}
                    d="M8 16H6a2 2 0 01-2-2V6a2 2 0 012-2h8a2 2 0 012 2v2m-6 12h8a2 2 0 002-2v-8a2 2 0 00-2-2h-8a2 2 0 00-2 2v8a2 2 0 002 2z"
                  />
                </svg>
                {t('designer_page_dialog.clone')}
              </>
            )}
          </button>
        </div>
      </div>
    </div>
  );
};
