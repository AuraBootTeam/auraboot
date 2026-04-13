/**
 * TemplateGallery
 *
 * Card grid component for browsing available page templates.
 * Supports search and kind-based filtering.
 *
 * @since 3.2.0
 */

import React, { useState, useEffect } from 'react';
import { getTemplates } from '~/plugins/core-designer/components/studio/services/page-manager/pageApi';
import type { PageSchemaDTO } from '~/plugins/core-designer/components/studio/services/page-manager/api-types';

interface TemplateGalleryProps {
  onSelect: (template: PageSchemaDTO) => void;
  selectedPid?: string;
}

export const TemplateGallery: React.FC<TemplateGalleryProps> = ({ onSelect, selectedPid }) => {
  const [templates, setTemplates] = useState<PageSchemaDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [search, setSearch] = useState('');
  const [kindFilter, setKindFilter] = useState<string>('all');

  useEffect(() => {
    loadTemplates();
  }, []);

  const loadTemplates = async () => {
    setLoading(true);
    setError(null);
    try {
      const result = await getTemplates();
      if (result.code !== '0') throw new Error(result.message || result.desc || 'Failed to load templates');
      setTemplates(Array.isArray(result.data) ? result.data : []);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load templates');
    } finally {
      setLoading(false);
    }
  };

  const filtered = templates.filter((t) => {
    if (kindFilter !== 'all' && t.kind !== kindFilter) return false;
    if (search.trim() && !t.name.toLowerCase().includes(search.toLowerCase())) return false;
    return true;
  });

  const KINDS = ['all', 'list', 'form', 'detail', 'dashboard', 'composite'];

  if (loading) {
    return (
      <div
        className="flex items-center justify-center py-12 text-sm text-gray-400"
        data-testid="template-gallery-loading"
      >
        Loading templates...
      </div>
    );
  }

  if (error) {
    return (
      <div className="py-8 text-center text-sm text-red-500" data-testid="template-gallery-error">
        {error}
        <button onClick={loadTemplates} className="ml-2 text-purple-600 underline">
          Retry
        </button>
      </div>
    );
  }

  return (
    <div data-testid="template-gallery">
      {/* Search + Filter */}
      <div className="mb-4 flex gap-3">
        <input
          type="text"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          placeholder="Search templates..."
          className="flex-1 rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-purple-500 focus:outline-none"
          data-testid="template-search"
        />
        <select
          value={kindFilter}
          onChange={(e) => setKindFilter(e.target.value)}
          className="rounded-md border border-gray-300 px-3 py-2 text-sm"
          data-testid="template-kind-filter"
        >
          {KINDS.map((k) => (
            <option key={k} value={k}>
              {k === 'all' ? 'All Types' : k}
            </option>
          ))}
        </select>
      </div>

      {/* Template Cards */}
      {filtered.length === 0 ? (
        <div className="py-12 text-center text-sm text-gray-400" data-testid="template-empty">
          {templates.length === 0
            ? 'No templates yet. Save a page as template first.'
            : 'No templates match your search.'}
        </div>
      ) : (
        <div className="grid grid-cols-3 gap-4" data-testid="template-grid">
          {filtered.map((t) => (
            <div
              key={t.pid}
              onClick={() => onSelect(t)}
              className={`cursor-pointer rounded-lg border-2 p-4 transition-all hover:shadow-md ${
                selectedPid === t.pid
                  ? 'border-purple-500 bg-purple-50'
                  : 'border-gray-200 hover:border-purple-300'
              }`}
              data-testid={`template-card-${t.pid}`}
            >
              <div className="mb-2 flex items-center gap-2">
                <span className="rounded bg-purple-100 px-1.5 py-0.5 text-[10px] font-medium text-purple-700">
                  {t.kind}
                </span>
                {t.templateCategory && (
                  <span className="rounded bg-gray-100 px-1.5 py-0.5 text-[10px] text-gray-500">
                    {t.templateCategory}
                  </span>
                )}
              </div>
              <div className="text-sm font-medium text-gray-900">{t.name}</div>
              <div className="mt-1 text-xs text-gray-400">{t.blocks?.length ?? 0} blocks</div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
};
