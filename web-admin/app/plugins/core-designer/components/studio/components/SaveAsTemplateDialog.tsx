/**
 * SaveAsTemplateDialog
 *
 * Modal dialog for saving the current page as a reusable template.
 * Calls updatePage API with isTemplate: true and optional templateCategory.
 *
 * @since 3.2.0
 */

import React, { useState } from 'react';
import { updatePage } from '~/plugins/core-designer/components/studio/services/page-manager/pageApi';

interface SaveAsTemplateDialogProps {
  open: boolean;
  onClose: () => void;
  /** Page PID (id field from PageMeta) */
  pagePid: string;
  /** Current page title, used to prefill the template name */
  currentName: string;
  onSuccess: () => void;
}

export const SaveAsTemplateDialog: React.FC<SaveAsTemplateDialogProps> = ({
  open,
  onClose,
  pagePid,
  currentName,
  onSuccess,
}) => {
  const [name, setName] = useState(`${currentName} Template`);
  const [category, setCategory] = useState('');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  if (!open) return null;

  const handleSave = async () => {
    if (!name.trim()) {
      setError('Template name is required');
      return;
    }
    setSaving(true);
    setError(null);
    try {
      const result = await updatePage(pagePid, {
        name: name.trim(),
        isTemplate: true,
        templateCategory: category.trim() || undefined,
      });
      if (result.code !== '0') {
        throw new Error(result.message || 'Failed to save template');
      }
      onSuccess();
      onClose();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to save template');
    } finally {
      setSaving(false);
    }
  };

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/40"
      onClick={onClose}
    >
      <div
        className="w-[420px] rounded-lg bg-white p-6 shadow-xl"
        onClick={(e) => e.stopPropagation()}
        data-testid="save-as-template-dialog"
      >
        <h2 className="mb-4 text-lg font-semibold text-gray-900">Save as Template</h2>

        <div className="space-y-4">
          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700">
              Template Name *
            </label>
            <input
              type="text"
              value={name}
              onChange={(e) => setName(e.target.value)}
              className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-purple-500 focus:outline-none focus:ring-1 focus:ring-purple-500"
              data-testid="template-name-input"
              autoFocus
            />
          </div>

          <div>
            <label className="mb-1 block text-sm font-medium text-gray-700">Category</label>
            <input
              type="text"
              value={category}
              onChange={(e) => setCategory(e.target.value)}
              placeholder="e.g. CRM, HR, Finance"
              className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-purple-500 focus:outline-none focus:ring-1 focus:ring-purple-500"
              data-testid="template-category-input"
            />
          </div>

          {error && (
            <div
              className="rounded-md bg-red-50 p-3 text-sm text-red-600"
              data-testid="template-error"
            >
              {error}
            </div>
          )}
        </div>

        <div className="mt-6 flex justify-end gap-3">
          <button
            onClick={onClose}
            className="rounded-md border border-gray-300 px-4 py-2 text-sm text-gray-700 hover:bg-gray-50"
            disabled={saving}
          >
            Cancel
          </button>
          <button
            onClick={handleSave}
            disabled={saving || !name.trim()}
            className="rounded-md bg-purple-600 px-4 py-2 text-sm text-white hover:bg-purple-700 disabled:opacity-50"
            data-testid="template-save-btn"
          >
            {saving ? 'Saving...' : 'Save as Template'}
          </button>
        </div>
      </div>
    </div>
  );
};
