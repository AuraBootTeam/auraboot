import React, { useState, useMemo, useCallback } from 'react';
import { cn } from '~/utils/cn';
import { useI18n } from '~/contexts/I18nContext';
import { ONBOARDING_KEYS } from '~/smart/onboarding/i18nKeys';
import {
  commandTemplates,
  commandTemplateCategories,
  type CommandTemplate,
  type CommandTemplateCategory,
} from './commandTemplates';

// ---------------------------------------------------------------------------
// Props
// ---------------------------------------------------------------------------

export interface CommandTemplateGalleryProps {
  open: boolean;
  onClose: () => void;
  /** Called when the user selects a template to apply */
  onApplyTemplate: (template: CommandTemplate) => void;
  /** Optionally restrict to model category */
  modelCategory?: 'document' | 'master' | 'lookup';
}

// ---------------------------------------------------------------------------
// Category colours
// ---------------------------------------------------------------------------

const categoryColors: Record<string, string> = {
  basic: 'bg-blue-100 text-blue-700',
  lifecycle: 'bg-purple-100 text-purple-700',
  industry: 'bg-emerald-100 text-emerald-700',
};

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

export function CommandTemplateGallery({
  open,
  onClose,
  onApplyTemplate,
  modelCategory,
}: CommandTemplateGalleryProps) {
  const { t } = useI18n();
  const [activeCategory, setActiveCategory] = useState<CommandTemplateCategory>('all');
  const [searchQuery, setSearchQuery] = useState('');
  const [previewId, setPreviewId] = useState<string | null>(null);

  const filtered = useMemo(() => {
    let results = commandTemplates;
    // filter by model category if provided
    if (modelCategory) {
      results = results.filter((tpl) => tpl.applicableTo.includes(modelCategory));
    }
    // filter by tab category
    if (activeCategory !== 'all') {
      results = results.filter((tpl) => tpl.category === activeCategory);
    }
    // search
    const q = searchQuery.toLowerCase().trim();
    if (q) {
      results = results.filter(
        (tpl) =>
          t(tpl.nameKey).toLowerCase().includes(q) ||
          t(tpl.descriptionKey).toLowerCase().includes(q) ||
          tpl.tags.some((tag) => tag.includes(q)),
      );
    }
    return results;
  }, [activeCategory, searchQuery, modelCategory, t]);

  const previewTemplate = useMemo(
    () => (previewId ? (commandTemplates.find((t) => t.id === previewId) ?? null) : null),
    [previewId],
  );

  const handleApply = useCallback(
    (tpl: CommandTemplate) => {
      onApplyTemplate(tpl);
      onClose();
    },
    [onApplyTemplate, onClose],
  );

  if (!open) return null;

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center"
      data-testid="command-template-gallery"
    >
      {/* Backdrop */}
      <div className="absolute inset-0 bg-black/40" onClick={onClose} />

      {/* Modal */}
      <div className="relative z-10 mx-4 flex max-h-[85vh] w-full max-w-4xl flex-col rounded-xl bg-white shadow-2xl">
        {/* Header */}
        <div className="flex items-center justify-between border-b border-gray-200 px-6 py-4">
          <div>
            <h2 className="text-xl font-semibold text-gray-900">
              {t(ONBOARDING_KEYS.galleryTitle)}
            </h2>
            <p className="mt-0.5 text-sm text-gray-500">{t(ONBOARDING_KEYS.gallerySubtitle)}</p>
          </div>
          <button
            onClick={onClose}
            className="rounded-lg p-2 text-gray-400 hover:bg-gray-100 hover:text-gray-600"
            aria-label="Close"
          >
            <svg
              className="h-5 w-5"
              fill="none"
              viewBox="0 0 24 24"
              strokeWidth={2}
              stroke="currentColor"
            >
              <path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" />
            </svg>
          </button>
        </div>

        {/* Search + Category */}
        <div className="space-y-3 border-b border-gray-100 px-6 py-3">
          <div className="relative">
            <svg
              className="absolute top-1/2 left-3 h-4 w-4 -translate-y-1/2 text-gray-400"
              fill="none"
              viewBox="0 0 24 24"
              strokeWidth={2}
              stroke="currentColor"
            >
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                d="M21 21l-4.35-4.35m0 0A7.5 7.5 0 1 0 5.65 5.65a7.5 7.5 0 0 0 10.99 10.99z"
              />
            </svg>
            <input
              type="text"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              placeholder={t(ONBOARDING_KEYS.gallerySearch)}
              className="w-full rounded-lg border border-gray-300 py-2 pr-4 pl-10 text-sm focus:border-blue-500 focus:ring-2 focus:ring-blue-500 focus:outline-none"
              data-testid="gallery-search"
            />
          </div>
          <div className="flex gap-1">
            {commandTemplateCategories.map((cat) => (
              <button
                key={cat.key}
                onClick={() => setActiveCategory(cat.key)}
                className={cn(
                  'rounded-md px-3 py-1.5 text-sm font-medium transition-colors',
                  activeCategory === cat.key
                    ? 'bg-blue-600 text-white'
                    : 'text-gray-600 hover:bg-gray-100',
                )}
              >
                {t(cat.labelKey)}
              </button>
            ))}
          </div>
        </div>

        {/* Template list / Preview */}
        <div className="flex-1 overflow-y-auto">
          {previewTemplate ? (
            /* Detail / Preview */
            <div className="space-y-4 p-6">
              <button
                onClick={() => setPreviewId(null)}
                className="flex items-center gap-1 text-sm text-blue-600 hover:text-blue-800"
              >
                <svg
                  className="h-4 w-4"
                  fill="none"
                  viewBox="0 0 24 24"
                  strokeWidth={2}
                  stroke="currentColor"
                >
                  <path strokeLinecap="round" strokeLinejoin="round" d="M15 19l-7-7 7-7" />
                </svg>
                {t(ONBOARDING_KEYS.galleryAll)}
              </button>

              <div>
                <div className="mb-1 flex items-center gap-2">
                  <h3 className="text-lg font-semibold text-gray-900">
                    {t(previewTemplate.nameKey)}
                  </h3>
                  <span
                    className={cn(
                      'rounded-full px-2 py-0.5 text-xs font-medium',
                      categoryColors[previewTemplate.category] || 'bg-gray-100 text-gray-600',
                    )}
                  >
                    {previewTemplate.category}
                  </span>
                </div>
                <p className="text-gray-500">{t(previewTemplate.descriptionKey)}</p>
              </div>

              {/* Status flow */}
              {previewTemplate.statuses && (
                <div>
                  <h4 className="mb-2 text-sm font-medium text-gray-700">Status Flow</h4>
                  <div className="flex flex-wrap items-center gap-1">
                    {previewTemplate.statuses.map((s, i) => (
                      <React.Fragment key={s}>
                        <span className="rounded bg-purple-50 px-2 py-1 font-mono text-xs text-purple-700">
                          {s}
                        </span>
                        {i < previewTemplate.statuses!.length - 1 && (
                          <svg
                            className="h-4 w-4 text-gray-400"
                            fill="none"
                            viewBox="0 0 24 24"
                            stroke="currentColor"
                            strokeWidth={2}
                          >
                            <path strokeLinecap="round" strokeLinejoin="round" d="M9 5l7 7-7 7" />
                          </svg>
                        )}
                      </React.Fragment>
                    ))}
                  </div>
                </div>
              )}

              {/* Commands table */}
              <div>
                <h4 className="mb-2 text-sm font-medium text-gray-700">
                  {t(ONBOARDING_KEYS.galleryCommands)} ({previewTemplate.commands.length})
                </h4>
                <div className="overflow-hidden rounded-lg border border-gray-200">
                  <table className="w-full text-sm">
                    <thead className="bg-gray-50">
                      <tr>
                        <th className="px-4 py-2 text-left font-medium text-gray-600">Name</th>
                        <th className="px-4 py-2 text-left font-medium text-gray-600">Type</th>
                        <th className="px-4 py-2 text-left font-medium text-gray-600">From</th>
                        <th className="px-4 py-2 text-left font-medium text-gray-600">To</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-gray-100">
                      {previewTemplate.commands.map((cmd) => (
                        <tr key={cmd.code}>
                          <td className="px-4 py-2 font-medium text-gray-900">{cmd.name}</td>
                          <td className="px-4 py-2 text-gray-500">{cmd.type}</td>
                          <td className="px-4 py-2 text-gray-500">
                            {cmd.fromStatus?.join(', ') || '—'}
                          </td>
                          <td className="px-4 py-2 text-gray-500">{cmd.toStatus || '—'}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </div>

              {/* Apply button */}
              <div className="flex justify-end pt-2">
                <button
                  onClick={() => handleApply(previewTemplate)}
                  className="rounded-lg bg-blue-600 px-6 py-2.5 font-medium text-white transition-colors hover:bg-blue-700"
                  data-testid="gallery-apply"
                >
                  {t(ONBOARDING_KEYS.galleryUseTemplate)}
                </button>
              </div>
            </div>
          ) : (
            /* Grid */
            <div className="p-6">
              {filtered.length === 0 ? (
                <div className="py-12 text-center text-gray-500">
                  {t(ONBOARDING_KEYS.galleryNoResults)}
                </div>
              ) : (
                <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
                  {filtered.map((tpl) => (
                    <div
                      key={tpl.id}
                      className="group cursor-pointer rounded-lg border border-gray-200 p-4 transition-all hover:border-blue-300 hover:shadow-md"
                      onClick={() => setPreviewId(tpl.id)}
                      data-testid={`template-card-${tpl.id}`}
                    >
                      <div className="mb-1 flex items-center gap-2">
                        <h3 className="font-medium text-gray-900">{t(tpl.nameKey)}</h3>
                        <span
                          className={cn(
                            'rounded-full px-2 py-0.5 text-xs font-medium',
                            categoryColors[tpl.category] || 'bg-gray-100 text-gray-600',
                          )}
                        >
                          {tpl.category}
                        </span>
                      </div>
                      <p className="line-clamp-2 text-sm text-gray-500">{t(tpl.descriptionKey)}</p>

                      {/* Tags */}
                      <div className="mt-2 flex flex-wrap gap-1">
                        {tpl.tags.slice(0, 4).map((tag) => (
                          <span
                            key={tag}
                            className="rounded bg-gray-100 px-1.5 py-0.5 text-xs text-gray-500"
                          >
                            {tag}
                          </span>
                        ))}
                      </div>

                      {/* Hover actions */}
                      <div className="mt-3 flex justify-end gap-2 opacity-0 transition-opacity group-hover:opacity-100">
                        <button
                          onClick={(e) => {
                            e.stopPropagation();
                            setPreviewId(tpl.id);
                          }}
                          className="rounded-md bg-gray-100 px-3 py-1.5 text-xs font-medium text-gray-700 hover:bg-gray-200"
                        >
                          {t(ONBOARDING_KEYS.galleryPreview)}
                        </button>
                        <button
                          onClick={(e) => {
                            e.stopPropagation();
                            handleApply(tpl);
                          }}
                          className="rounded-md bg-blue-600 px-3 py-1.5 text-xs font-medium text-white hover:bg-blue-700"
                          data-testid={`btn-use-${tpl.id}`}
                        >
                          {t(ONBOARDING_KEYS.galleryUseTemplate)}
                        </button>
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

export default CommandTemplateGallery;
