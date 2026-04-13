// web-admin/app/smart/automation/components/TemplateGallery.tsx
import React, { useState, useMemo, useCallback } from 'react';
import { cn } from '~/utils/cn';
import {
  automationTemplates,
  templateCategories,
  type AutomationTemplate,
  type TemplateCategory,
} from '../templates/automationTemplates';
import { TemplatePreviewDialog } from './TemplatePreviewDialog';

export interface TemplateGalleryProps {
  open: boolean;
  onClose: () => void;
  onSelectTemplate: (template: AutomationTemplate) => void;
}

// Category badge colors
const categoryColors: Record<string, string> = {
  sales: 'bg-blue-100 text-blue-700',
  operations: 'bg-amber-100 text-amber-700',
  notifications: 'bg-purple-100 text-purple-700',
  integrations: 'bg-emerald-100 text-emerald-700',
};

// Icon lookup (simple map to avoid importing an icon library into templates)
const iconMap: Record<string, string> = {
  UserPlus: '\u{1F464}',
  AlertTriangle: '\u26A0\uFE0F',
  GitBranch: '\u{1F500}',
  Mail: '\u2709\uFE0F',
  FileText: '\u{1F4C4}',
  ShieldAlert: '\u{1F6E1}\uFE0F',
  CheckSquare: '\u2705',
  Link: '\u{1F517}',
};

function renderIcon(icon: string) {
  return <span className="text-2xl">{iconMap[icon] || icon}</span>;
}

export function TemplateGallery({ open, onClose, onSelectTemplate }: TemplateGalleryProps) {
  const [activeCategory, setActiveCategory] = useState<TemplateCategory>('all');
  const [searchQuery, setSearchQuery] = useState('');
  const [previewTemplate, setPreviewTemplate] = useState<AutomationTemplate | null>(null);

  const filteredTemplates = useMemo(() => {
    let results = automationTemplates;
    // filter by category
    if (activeCategory !== 'all') {
      results = results.filter((t) => t.category === activeCategory);
    }
    // filter by search
    const q = searchQuery.toLowerCase().trim();
    if (q) {
      results = results.filter(
        (t) =>
          t.name.toLowerCase().includes(q) ||
          t.description.toLowerCase().includes(q) ||
          t.tags.some((tag) => tag.includes(q)),
      );
    }
    return results;
  }, [activeCategory, searchQuery]);

  const handleUseTemplate = useCallback(
    (template: AutomationTemplate) => {
      onSelectTemplate(template);
      onClose();
    },
    [onSelectTemplate, onClose],
  );

  if (!open) return null;

  return (
    <>
      {/* Backdrop */}
      <div className="fixed inset-0 z-50 flex items-center justify-center">
        <div className="absolute inset-0 bg-black/40" onClick={onClose} />

        {/* Modal */}
        <div className="relative z-10 mx-4 flex max-h-[85vh] w-full max-w-4xl flex-col rounded-xl bg-white shadow-2xl">
          {/* Header */}
          <div className="flex items-center justify-between border-b border-gray-200 px-6 py-4">
            <div>
              <h2 className="text-xl font-semibold text-gray-900">Automation Templates</h2>
              <p className="mt-0.5 text-sm text-gray-500">
                Choose a template to get started quickly
              </p>
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

          {/* Search + Category Tabs */}
          <div className="space-y-3 border-b border-gray-100 px-6 py-3">
            {/* Search */}
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
                placeholder="Search templates..."
                className="w-full rounded-lg border border-gray-300 py-2 pr-4 pl-10 text-sm focus:border-blue-500 focus:ring-2 focus:ring-blue-500 focus:outline-none"
              />
            </div>

            {/* Category tabs */}
            <div className="flex gap-1">
              {templateCategories.map((cat) => (
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
                  {cat.label}
                </button>
              ))}
            </div>
          </div>

          {/* Template Grid */}
          <div className="flex-1 overflow-y-auto px-6 py-4">
            {filteredTemplates.length === 0 ? (
              <div className="py-12 text-center text-gray-500">No templates match your search.</div>
            ) : (
              <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
                {filteredTemplates.map((template) => (
                  <div
                    key={template.id}
                    className="group cursor-pointer rounded-lg border border-gray-200 p-4 transition-all hover:border-blue-300 hover:shadow-md"
                    onClick={() => setPreviewTemplate(template)}
                    data-testid={`template-card-${template.id}`}
                  >
                    <div className="flex items-start gap-3">
                      <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-gray-50">
                        {renderIcon(template.icon)}
                      </div>
                      <div className="min-w-0 flex-1">
                        <div className="flex items-center gap-2">
                          <h3 className="truncate font-medium text-gray-900">{template.name}</h3>
                          <span
                            className={cn(
                              'shrink-0 rounded-full px-2 py-0.5 text-xs font-medium',
                              categoryColors[template.category] || 'bg-gray-100 text-gray-600',
                            )}
                          >
                            {template.category}
                          </span>
                        </div>
                        <p className="mt-1 line-clamp-2 text-sm text-gray-500">
                          {template.description}
                        </p>
                        <div className="mt-2 flex flex-wrap gap-1">
                          {template.tags.slice(0, 4).map((tag) => (
                            <span
                              key={tag}
                              className="rounded bg-gray-100 px-1.5 py-0.5 text-xs text-gray-500"
                            >
                              {tag}
                            </span>
                          ))}
                        </div>
                      </div>
                    </div>

                    {/* Hover action area */}
                    <div className="mt-3 flex justify-end gap-2 opacity-0 transition-opacity group-hover:opacity-100">
                      <button
                        onClick={(e) => {
                          e.stopPropagation();
                          setPreviewTemplate(template);
                        }}
                        className="rounded-md bg-gray-100 px-3 py-1.5 text-xs font-medium text-gray-700 hover:bg-gray-200"
                      >
                        Preview
                      </button>
                      <button
                        onClick={(e) => {
                          e.stopPropagation();
                          handleUseTemplate(template);
                        }}
                        className="rounded-md bg-blue-600 px-3 py-1.5 text-xs font-medium text-white hover:bg-blue-700"
                        data-testid={`btn-use-template-${template.id}`}
                      >
                        Use Template
                      </button>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>
        </div>
      </div>

      {/* Preview Dialog */}
      {previewTemplate && (
        <TemplatePreviewDialog
          template={previewTemplate}
          onClose={() => setPreviewTemplate(null)}
          onUseTemplate={() => {
            handleUseTemplate(previewTemplate);
            setPreviewTemplate(null);
          }}
        />
      )}
    </>
  );
}

export default TemplateGallery;
