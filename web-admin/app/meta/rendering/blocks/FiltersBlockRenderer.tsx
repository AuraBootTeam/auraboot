/**
 * FiltersBlockRenderer - 过滤器块渲染器
 */

import React from 'react';
import type { BlockConfig } from '~/meta/schemas/types';
import type { SchemaRuntime } from '~/meta/runtime/schema-runtime';
import { FieldRenderer } from '~/meta/rendering/FieldRenderer';
import { useI18n } from '~/contexts/I18nContext';

export interface FiltersBlockRendererProps {
  block: BlockConfig;
  runtime: SchemaRuntime;
}

export const FiltersBlockRenderer: React.FC<FiltersBlockRendererProps> = ({ block, runtime }) => {
  const { t } = useI18n();
  const fields = block.fields || [];

  // 处理搜索
  const handleSearch = async () => {
    if (block.onSearch) {
      try {
        await runtime.executeHandler(block.onSearch, {});
      } catch (err) {
        console.error('Search handler failed:', err);
      }
    }
  };

  // 处理重置
  const handleReset = async () => {
    if (block.onReset) {
      try {
        await runtime.executeHandler(block.onReset, {});
      } catch (err) {
        console.error('Reset handler failed:', err);
      }
    }
  };

  return (
    <div className="filters-block border-b border-gray-200 bg-gray-50 px-6 py-4">
      <div className="grid grid-cols-1 gap-4 md:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
        {fields.map((field) => (
          <FieldRenderer key={field.field} field={field} runtime={runtime} />
        ))}
      </div>
      <div className="mt-4 flex justify-end space-x-2">
        <button
          onClick={handleReset}
          data-testid="filter-btn-reset"
          className="rounded-md border border-gray-300 bg-white px-4 py-2 text-gray-600 hover:bg-gray-50"
        >
          {t('action.reset') !== 'action.reset' ? t('action.reset') : 'Reset'}
        </button>
        <button
          onClick={handleSearch}
          data-testid="filter-btn-search"
          className="rounded-md bg-blue-600 px-4 py-2 text-white hover:bg-blue-700"
        >
          {t('action.search') !== 'action.search' ? t('action.search') : 'Search'}
        </button>
      </div>
    </div>
  );
};

export default FiltersBlockRenderer;
