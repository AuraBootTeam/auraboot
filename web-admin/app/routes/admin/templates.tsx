/**
 * Template Center — Browse and install application templates.
 *
 * Route: /admin/templates
 *
 * Feishu-like layout with category sidebar, search, and template card grid.
 * Clicking a card navigates to the preview page for installation.
 */

import { useState, useMemo } from 'react';
import { CubeIcon } from '@heroicons/react/24/outline';
import {
  APP_TEMPLATES,
  TEMPLATE_CATEGORY_TREE,
  type AppTemplate,
} from '~/admin/templates/templateCatalog';
import { TemplateCategorySidebar } from '~/admin/templates/TemplateCategorySidebar';
import { TemplateCard } from '~/admin/templates/TemplateCard';
import { CreateBlankCard } from '~/admin/templates/CreateBlankCard';

export default function TemplateCenterPage() {
  const [activeCategory, setActiveCategory] = useState('all');
  const [searchQuery, setSearchQuery] = useState('');
  const [installedIds] = useState<Set<string>>(new Set());

  const filtered = useMemo(() => {
    let results: AppTemplate[] = APP_TEMPLATES;

    // Category filter
    if (activeCategory !== 'all') {
      results = results.filter((t) => t.category === activeCategory);
    }

    // Search filter
    if (searchQuery.trim()) {
      const q = searchQuery.trim().toLowerCase();
      results = results.filter((t) => {
        const haystack = [
          t.name,
          t.description,
          ...(t.tags ?? []),
          ...t.features,
        ]
          .join(' ')
          .toLowerCase();
        return haystack.includes(q);
      });
    }

    return results;
  }, [activeCategory, searchQuery]);

  return (
    <div className="flex h-[calc(100vh-64px)]" data-testid="template-center">
      <TemplateCategorySidebar
        categories={TEMPLATE_CATEGORY_TREE}
        activeCategory={activeCategory}
        onSelect={setActiveCategory}
        searchQuery={searchQuery}
        onSearchChange={setSearchQuery}
      />

      <div className="flex-1 overflow-y-auto p-6">
        {/* Header */}
        <div className="mb-6">
          <h1 className="text-2xl font-bold text-gray-900 dark:text-white">
            Template Center
          </h1>
          <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">
            Browse templates to quickly set up your workspace
          </p>
        </div>

        {/* Template grid */}
        <div className="grid grid-cols-1 gap-5 md:grid-cols-2 xl:grid-cols-3 2xl:grid-cols-4">
          <CreateBlankCard />
          {filtered.map((t) => (
            <TemplateCard
              key={t.id}
              template={t}
              installed={installedIds.has(t.id)}
            />
          ))}
        </div>

        {/* Empty state */}
        {filtered.length === 0 && (
          <div className="py-16 text-center text-gray-400 dark:text-gray-500">
            <CubeIcon className="mx-auto mb-3 h-12 w-12 opacity-40" />
            <p className="text-sm">
              {searchQuery
                ? `No templates matching "${searchQuery}"`
                : 'No templates in this category yet.'}
            </p>
          </div>
        )}
      </div>
    </div>
  );
}
