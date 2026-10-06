/**
 * CreateFromTemplateDialog
 *
 * Two-step modal dialog for creating a new page from an existing template.
 * Step 1: Browse and select a template via TemplateGallery.
 * Step 2: Configure the new page name and page key.
 *
 * @since 3.2.0
 */

import React, { useState, useId } from 'react';
import { createPage } from '~/plugins/core-designer/components/studio/services/page-manager/pageApi';
import type { PageSchemaDTO } from '~/plugins/core-designer/components/studio/services/page-manager/api-types';
import { TemplateGallery } from './TemplateGallery';
import { preparePageCopyContent } from '../services/page-manager/pageCopyContent';
import { useI18n } from '~/contexts/I18nContext';

interface CreateFromTemplateDialogProps {
  open: boolean;
  onClose: () => void;
  onSuccess: (pid: string) => void;
}

export const CreateFromTemplateDialog: React.FC<CreateFromTemplateDialogProps> = ({
  open,
  onClose,
  onSuccess,
}) => {
  const { t } = useI18n();
  const dialogId = useId();
  const kindLabel = (kind: string) => t(`designer_template.${['list', 'form', 'detail', 'dashboard', 'custom', 'composite'].includes(kind) ? kind : 'other'}`);
  const [selectedTemplate, setSelectedTemplate] = useState<PageSchemaDTO | null>(null);
  const [name, setName] = useState('');
  const [pageKey, setPageKey] = useState('');
  const [creating, setCreating] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [step, setStep] = useState<'select' | 'configure'>('select');

  if (!open) return null;

  const handleSelectTemplate = (template: PageSchemaDTO) => {
    setSelectedTemplate(template);
    setName(t('designer_template.copy_name', { name: template.name }));
    setPageKey(`${template.kind}_${Date.now().toString(36)}`);
    setError(null);
    setStep('configure');
  };

  const handleCreate = async () => {
    if (!name.trim() || !pageKey.trim() || !selectedTemplate) return;
    setCreating(true);
    setError(null);
    try {
      const copiedContent = preparePageCopyContent(selectedTemplate);
      const result = await createPage({
        name: name.trim(),
        pageKey: pageKey.trim(),
        title: name.trim(),
        metaInfo: {
          componentCount: copiedContent.blocks?.length ?? 0,
          fromTemplate: selectedTemplate.pid,
        },
        semver: '0.1.0',
        ...copiedContent,
      });
      if (result.code !== '0') throw new Error(result.message || t('designer_template.create_failed'));
      const newPid = result.data?.pid;
      if (!newPid?.trim()) throw new Error(t('designer_page_dialog.missing_pid'));
      onSuccess(newPid);
      onClose();
    } catch (err) {
      setError(err instanceof Error ? err.message : t('designer_template.create_failed'));
    } finally {
      setCreating(false);
    }
  };

  const handleBack = () => {
    setStep('select');
    setSelectedTemplate(null);
    setError(null);
  };

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/40"
      onClick={() => { if (!creating) onClose(); }}
    >
      <div
        className="flex h-[600px] w-[800px] flex-col rounded-lg bg-white shadow-xl"
        onClick={(e) => e.stopPropagation()}
        role="dialog"
        aria-modal="true"
        aria-labelledby={`${dialogId}-title`}
        data-testid="create-from-template-dialog"
      >
        {/* Header */}
        <div className="flex items-center justify-between border-b border-gray-200 px-6 py-4">
          <div className="flex items-center gap-3">
            {step === 'configure' && (
              <button
                onClick={handleBack}
                className="text-gray-400 hover:text-gray-600"
                aria-label={t('designer_template.back_select')}
                disabled={creating}
              >
                &larr;
              </button>
            )}
            <h2 id={`${dialogId}-title`} className="text-lg font-semibold text-gray-900">
              {t(step === 'select' ? 'designer_template.select' : 'designer_template.configure')}
            </h2>
          </div>
          <button
            onClick={onClose}
            className="text-gray-400 hover:text-gray-600"
            aria-label={t('designer_template.close')}
            disabled={creating}
          >
            &times;
          </button>
        </div>

        {/* Body */}
        <div className="flex-1 overflow-y-auto p-6">
          {step === 'select' ? (
            <TemplateGallery
              onSelect={handleSelectTemplate}
              selectedPid={selectedTemplate?.pid}
            />
          ) : (
            <div className="mx-auto max-w-md space-y-4">
              <div className="rounded-md bg-purple-50 p-3 text-sm text-purple-700">
                {t('designer_template.based_on', { name: selectedTemplate?.name, kind: kindLabel(selectedTemplate?.kind || '') })}
              </div>
              <div>
                <label htmlFor={`${dialogId}-name`} className="mb-1 block text-sm font-medium text-gray-700">
                  {t('designer_template.name')} *
                </label>
                <input
                  type="text"
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-purple-500 focus:outline-none focus:ring-1 focus:ring-purple-500"
                  id={`${dialogId}-name`}
                  disabled={creating}
                  data-testid="new-page-name-input"
                  autoFocus
                />
              </div>
              <div>
                <label htmlFor={`${dialogId}-key`} className="mb-1 block text-sm font-medium text-gray-700">
                  {t('designer_page_dialog.page_key')} *
                </label>
                <input
                  type="text"
                  value={pageKey}
                  onChange={(e) => setPageKey(e.target.value)}
                  className="w-full rounded-md border border-gray-300 px-3 py-2 font-mono text-sm focus:border-purple-500 focus:outline-none focus:ring-1 focus:ring-purple-500"
                  id={`${dialogId}-key`}
                  disabled={creating}
                  data-testid="new-page-key-input"
                />
              </div>
              {error && (
                <div role="alert" className="rounded-md bg-red-50 p-3 text-sm text-red-600">{error}</div>
              )}
            </div>
          )}
        </div>

        {/* Footer — only shown on configure step */}
        {step === 'configure' && (
          <div className="flex justify-end gap-3 border-t border-gray-200 px-6 py-4">
            <button
              onClick={handleBack}
              disabled={creating}
              className="rounded-md border border-gray-300 px-4 py-2 text-sm text-gray-700 hover:bg-gray-50"
            >
              {t('designer_template.back')}
            </button>
            <button
              onClick={handleCreate}
              disabled={creating || !name.trim() || !pageKey.trim()}
              className="rounded-md bg-purple-600 px-4 py-2 text-sm text-white hover:bg-purple-700 disabled:opacity-50"
              data-testid="create-from-template-btn"
            >
              {t(creating ? 'designer_template.creating' : 'designer_template.create')}
            </button>
          </div>
        )}
      </div>
    </div>
  );
};
