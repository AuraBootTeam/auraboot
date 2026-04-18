/**
 * BPMN process definition save dialog.
 */

import React, { useState, useEffect, useRef } from 'react';
import { useI18n } from '~/contexts/I18nContext';

export interface ProcessMetadata {
  id?: string;
  name: string;
  key: string;
  version?: number;
  versionName?: string; // semver, e.g. 1.0.0
  description?: string;
  category?: string;
}

interface SaveDialogProps {
  isOpen: boolean;
  onClose: () => void;
  onSave: (metadata: ProcessMetadata) => Promise<void>;
  initialData: {
    id?: string;
    name: string;
    key: string;
    version?: number;
    versionName?: string;
    description?: string;
    category?: string;
  };
  isNew: boolean;
}

interface ValidationErrors {
  name?: string;
  key?: string;
  versionName?: string;
  description?: string;
  category?: string;
}

export function SaveDialog({ isOpen, onClose, onSave, initialData, isNew }: SaveDialogProps) {
  const { t } = useI18n();
  const [name, setName] = useState(initialData.name);
  const [key, setKey] = useState(initialData.key);
  const [versionName, setVersionName] = useState(initialData.versionName || '1.0.0');
  const [description, setDescription] = useState(initialData.description || '');
  const [category, setCategory] = useState(initialData.category || '');
  const [errors, setErrors] = useState<ValidationErrors>({});
  const [isSaving, setIsSaving] = useState(false);
  const firstFieldRef = useRef<HTMLInputElement | null>(null);
  const panelRef = useRef<HTMLDivElement | null>(null);

  // Reset form when dialog opens or initialData changes
  useEffect(() => {
    setName(initialData.name);
    setKey(initialData.key);
    setVersionName(initialData.versionName || '1.0.0');
    setDescription(initialData.description || '');
    setCategory(initialData.category || '');
    setErrors({});
  }, [initialData, isOpen]);

  // Focus the first field when dialog opens (a11y)
  useEffect(() => {
    if (isOpen && firstFieldRef.current) {
      firstFieldRef.current.focus();
    }
  }, [isOpen]);

  // Validate form
  const validateForm = (): boolean => {
    const newErrors: ValidationErrors = {};

    if (!name.trim()) {
      newErrors.name = t('bpmn.save.error.nameRequired', undefined, 'Process name is required');
    } else if (name.length > 100) {
      newErrors.name = t('bpmn.save.error.nameTooLong', undefined, 'Process name must be at most 100 characters');
    }

    if (!key.trim()) {
      newErrors.key = t('bpmn.save.error.keyRequired', undefined, 'Process key is required');
    } else if (!/^[a-zA-Z0-9_-]+$/.test(key)) {
      newErrors.key = t('bpmn.save.error.keyPattern', undefined, 'Process key may only contain letters, digits, underscores and hyphens');
    }

    if (versionName.trim()) {
      if (!/^\d+(\.\d+){0,2}$/.test(versionName.trim())) {
        newErrors.versionName = t('bpmn.save.error.versionFormat', undefined, 'Version must follow x.y.z format (e.g. 1.0.0)');
      }
    }

    if (description && description.length > 500) {
      newErrors.description = t('bpmn.save.error.descriptionTooLong', undefined, 'Description must be at most 500 characters');
    }

    if (category && category.length > 50) {
      newErrors.category = t('bpmn.save.error.categoryTooLong', undefined, 'Category must be at most 50 characters');
    }

    setErrors(newErrors);
    return Object.keys(newErrors).length === 0;
  };

  // Handle submit
  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();

    if (!validateForm()) {
      return;
    }

    setIsSaving(true);
    try {
      const metadata: ProcessMetadata = {
        id: initialData.id,
        name: name.trim(),
        key: key.trim(),
        version: isNew ? 1 : (initialData.version || 1) + 1,
        versionName: versionName.trim() || undefined,
        description: description.trim() || undefined,
        category: category.trim() || undefined,
      };

      await onSave(metadata);
    } catch (error) {
      // Error handled by parent component; log for diagnostics.
      console.error('Save process definition failed:', error);
    } finally {
      setIsSaving(false);
    }
  };

  // ESC closes; basic focus trap on Tab
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (!isOpen) return;
      if (e.key === 'Escape') {
        onClose();
        return;
      }
      if (e.key === 'Tab' && panelRef.current) {
        const focusable = panelRef.current.querySelectorAll<HTMLElement>(
          'button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])',
        );
        if (focusable.length === 0) return;
        const first = focusable[0];
        const last = focusable[focusable.length - 1];
        if (e.shiftKey && document.activeElement === first) {
          e.preventDefault();
          last.focus();
        } else if (!e.shiftKey && document.activeElement === last) {
          e.preventDefault();
          first.focus();
        }
      }
    };

    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [isOpen, onClose]);

  if (!isOpen) {
    return null;
  }

  // Close only when click target is the backdrop itself.
  const handleBackdropClick = (e: React.MouseEvent<HTMLDivElement>) => {
    if (e.target === e.currentTarget) {
      onClose();
    }
  };

  return (
    <div
      className="bg-opacity-50 fixed inset-0 z-50 flex items-center justify-center bg-black"
      onClick={handleBackdropClick}
      data-testid="bpmn-save-dialog-backdrop"
    >
      <div
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby="bpmn-save-dialog-title"
        className="mx-4 w-full max-w-lg rounded-lg bg-white p-6 shadow-xl"
        onClick={(e) => e.stopPropagation()}
        data-testid="bpmn-save-dialog-panel"
      >
        <div className="mb-6 flex items-center justify-between">
          <h2 id="bpmn-save-dialog-title" className="text-xl font-semibold text-gray-900">
            {t('bpmn.save.title', undefined, 'Save process definition')}
          </h2>
          <button
            onClick={onClose}
            className="text-gray-400 transition-colors hover:text-gray-600"
            type="button"
            aria-label={t('bpmn.save.closeAria', undefined, 'Close dialog')}
          >
            <svg className="h-6 w-6" fill="none" stroke="currentColor" viewBox="0 0 24 24" aria-hidden="true">
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M6 18L18 6M6 6l12 12"
              />
            </svg>
          </button>
        </div>

        <form onSubmit={handleSubmit}>
          {/* Process name */}
          <div className="mb-4">
            <label htmlFor="bpmn-save-field-name" className="mb-1 block text-sm font-medium text-gray-700">
              {t('bpmn.save.field.name', undefined, 'Process name')} <span className="text-red-500" aria-hidden="true">*</span>
            </label>
            <input
              id="bpmn-save-field-name"
              ref={firstFieldRef}
              type="text"
              value={name}
              onChange={(e) => setName(e.target.value)}
              required
              aria-required="true"
              aria-invalid={!!errors.name}
              aria-describedby={errors.name ? 'bpmn-save-error-name' : undefined}
              className={`w-full rounded-md border px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none ${
                errors.name ? 'border-red-500' : 'border-gray-300'
              }`}
              placeholder={t('bpmn.save.field.namePlaceholder', undefined, 'e.g. Employee leave approval process')}
              disabled={isSaving}
            />
            {errors.name && (
              <p id="bpmn-save-error-name" role="alert" className="mt-1 text-sm text-red-600">
                {errors.name}
              </p>
            )}
          </div>

          {/* Process key */}
          <div className="mb-4">
            <label htmlFor="bpmn-save-field-key" className="mb-1 block text-sm font-medium text-gray-700">
              {t('bpmn.save.field.key', undefined, 'Process key')} <span className="text-red-500" aria-hidden="true">*</span>
            </label>
            <input
              id="bpmn-save-field-key"
              type="text"
              value={key}
              onChange={(e) => setKey(e.target.value)}
              required
              aria-required="true"
              aria-invalid={!!errors.key}
              aria-describedby={errors.key ? 'bpmn-save-error-key' : undefined}
              className={`w-full rounded-md border px-3 py-2 font-mono text-sm focus:ring-2 focus:ring-blue-500 focus:outline-none ${
                errors.key ? 'border-red-500' : 'border-gray-300'
              }`}
              placeholder={t('bpmn.save.field.keyPlaceholder', undefined, 'e.g. leave_approval_process')}
              disabled={isSaving}
            />
            {errors.key && (
              <p id="bpmn-save-error-key" role="alert" className="mt-1 text-sm text-red-600">
                {errors.key}
              </p>
            )}
          </div>

          {/* Numeric version */}
          <div className="mb-4">
            <label htmlFor="bpmn-save-field-version" className="mb-1 block text-sm font-medium text-gray-700">
              {t('bpmn.save.field.version', undefined, 'Version (numeric)')}
            </label>
            <div className="flex items-center">
              <input
                id="bpmn-save-field-version"
                type="text"
                value={isNew ? 1 : (initialData.version || 1) + 1}
                readOnly
                className="w-full rounded-md border border-gray-300 bg-gray-50 px-3 py-2 text-gray-600"
              />
              <span className="ml-2 text-sm whitespace-nowrap text-gray-500">
                {t('bpmn.save.field.versionAuto', undefined, '(auto-incremented)')}
              </span>
            </div>
          </div>

          {/* Semantic version */}
          <div className="mb-4">
            <label htmlFor="bpmn-save-field-versionName" className="mb-1 block text-sm font-medium text-gray-700">
              {t('bpmn.save.field.versionName', undefined, 'Semantic version')}
            </label>
            <input
              id="bpmn-save-field-versionName"
              type="text"
              value={versionName}
              onChange={(e) => setVersionName(e.target.value)}
              aria-invalid={!!errors.versionName}
              aria-describedby={errors.versionName ? 'bpmn-save-error-versionName' : 'bpmn-save-help-versionName'}
              className={`w-full rounded-md border px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none ${
                errors.versionName ? 'border-red-500' : 'border-gray-300'
              }`}
              placeholder={t('bpmn.save.field.versionNamePlaceholder', undefined, 'e.g. 1.0.0')}
              disabled={isSaving}
            />
            {errors.versionName && (
              <p id="bpmn-save-error-versionName" role="alert" className="mt-1 text-sm text-red-600">
                {errors.versionName}
              </p>
            )}
            <p id="bpmn-save-help-versionName" className="mt-1 text-xs text-gray-500">
              {t('bpmn.save.field.versionNameHint', undefined, 'Format: x.y.z (e.g. 1.0.0 or 2.1.3)')}
            </p>
          </div>

          {/* Description */}
          <div className="mb-4">
            <label htmlFor="bpmn-save-field-description" className="mb-1 block text-sm font-medium text-gray-700">
              {t('bpmn.save.field.description', undefined, 'Description')}
            </label>
            <textarea
              id="bpmn-save-field-description"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              rows={3}
              aria-invalid={!!errors.description}
              aria-describedby={errors.description ? 'bpmn-save-error-description' : undefined}
              className={`w-full rounded-md border px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none ${
                errors.description ? 'border-red-500' : 'border-gray-300'
              }`}
              placeholder={t('bpmn.save.field.descriptionPlaceholder', undefined, 'Detailed description of the process...')}
              disabled={isSaving}
            />
            {errors.description && (
              <p id="bpmn-save-error-description" role="alert" className="mt-1 text-sm text-red-600">
                {errors.description}
              </p>
            )}
          </div>

          {/* Category */}
          <div className="mb-6">
            <label htmlFor="bpmn-save-field-category" className="mb-1 block text-sm font-medium text-gray-700">
              {t('bpmn.save.field.category', undefined, 'Category')}
            </label>
            <input
              id="bpmn-save-field-category"
              type="text"
              value={category}
              onChange={(e) => setCategory(e.target.value)}
              aria-invalid={!!errors.category}
              aria-describedby={errors.category ? 'bpmn-save-error-category' : undefined}
              className={`w-full rounded-md border px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none ${
                errors.category ? 'border-red-500' : 'border-gray-300'
              }`}
              placeholder={t('bpmn.save.field.categoryPlaceholder', undefined, 'e.g. HR Management')}
              disabled={isSaving}
            />
            {errors.category && (
              <p id="bpmn-save-error-category" role="alert" className="mt-1 text-sm text-red-600">
                {errors.category}
              </p>
            )}
          </div>

          {/* Buttons */}
          <div className="flex justify-end gap-3">
            <button
              type="button"
              onClick={onClose}
              className="rounded-md border border-gray-300 bg-white px-4 py-2 text-sm font-medium text-gray-700 transition-colors hover:bg-gray-50"
              disabled={isSaving}
            >
              {t('bpmn.save.cancel', undefined, 'Cancel')}
            </button>
            <button
              type="submit"
              data-testid="bpmn-save-dialog-submit"
              className="rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-50"
              disabled={isSaving}
            >
              {isSaving
                ? t('bpmn.save.saving', undefined, 'Saving...')
                : t('bpmn.save.confirm', undefined, 'Save')}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
