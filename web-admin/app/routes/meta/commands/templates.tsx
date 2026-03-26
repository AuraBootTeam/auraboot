import React, { useState, useCallback } from 'react';
import { useI18n } from '~/contexts/I18nContext';
import { ONBOARDING_KEYS } from '~/smart/onboarding/i18nKeys';
import { CommandTemplateGallery, type CommandTemplate } from '~/meta/commands';

/**
 * Standalone page for browsing and selecting command templates.
 * Accessible at /meta/commands/templates
 */
export default function CommandTemplatesPage() {
  const { t } = useI18n();
  const [galleryOpen, setGalleryOpen] = useState(true);
  const [appliedTemplate, setAppliedTemplate] = useState<CommandTemplate | null>(null);

  const handleApply = useCallback((template: CommandTemplate) => {
    setAppliedTemplate(template);
    setGalleryOpen(false);
  }, []);

  return (
    <div className="mx-auto max-w-4xl p-6" data-testid="command-templates-page">
      <div className="mb-6 flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">{t(ONBOARDING_KEYS.galleryTitle)}</h1>
          <p className="mt-1 text-gray-500">{t(ONBOARDING_KEYS.gallerySubtitle)}</p>
        </div>
        {!galleryOpen && (
          <button
            onClick={() => setGalleryOpen(true)}
            className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-blue-700"
          >
            {t(ONBOARDING_KEYS.galleryTitle)}
          </button>
        )}
      </div>

      {/* Applied template preview */}
      {appliedTemplate && (
        <div className="space-y-4 rounded-lg border border-green-200 bg-green-50 p-6">
          <div className="flex items-center gap-2">
            <svg
              className="h-5 w-5 text-green-600"
              fill="none"
              viewBox="0 0 24 24"
              strokeWidth={2}
              stroke="currentColor"
            >
              <path strokeLinecap="round" strokeLinejoin="round" d="M5 13l4 4L19 7" />
            </svg>
            <h2 className="text-lg font-semibold text-gray-900">{t(appliedTemplate.nameKey)}</h2>
          </div>
          <p className="text-gray-600">{t(appliedTemplate.descriptionKey)}</p>

          {/* Commands list */}
          <div className="overflow-hidden rounded-lg border border-gray-200 bg-white">
            <table className="w-full text-sm">
              <thead className="bg-gray-50">
                <tr>
                  <th className="px-4 py-2 text-left font-medium text-gray-600">Code</th>
                  <th className="px-4 py-2 text-left font-medium text-gray-600">Name</th>
                  <th className="px-4 py-2 text-left font-medium text-gray-600">Type</th>
                  <th className="px-4 py-2 text-left font-medium text-gray-600">Description</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100">
                {appliedTemplate.commands.map((cmd) => (
                  <tr key={cmd.code}>
                    <td className="px-4 py-2 font-mono text-gray-700">{cmd.code}</td>
                    <td className="px-4 py-2 font-medium text-gray-900">{cmd.name}</td>
                    <td className="px-4 py-2 text-gray-500">{cmd.type}</td>
                    <td className="px-4 py-2 text-gray-500">{cmd.description}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {/* JSON preview */}
          <details className="text-sm">
            <summary className="cursor-pointer font-medium text-blue-600 hover:text-blue-800">
              JSON Preview
            </summary>
            <pre className="mt-2 overflow-x-auto rounded-lg bg-gray-900 p-4 text-xs text-gray-100">
              {JSON.stringify(appliedTemplate.commands, null, 2)}
            </pre>
          </details>
        </div>
      )}

      {/* Gallery modal */}
      <CommandTemplateGallery
        open={galleryOpen}
        onClose={() => setGalleryOpen(false)}
        onApplyTemplate={handleApply}
      />
    </div>
  );
}
