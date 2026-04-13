/**
 * Schema Renderer - Main Orchestrator
 *
 * A schema-driven page renderer that dynamically renders page content
 * based on JSON Schema configuration.
 *
 * This component orchestrates the rendering of various regions:
 * - Filters region (search/filter form)
 * - Action region (toolbar with buttons)
 * - Table region (data table with pagination)
 *
 * @example
 * ```tsx
 * <SchemaRenderer
 *   schema={pageSchema}
 *   data={records}
 *   loading={isLoading}
 *   pagination={{ current: 1, pageSize: 20, total: 100 }}
 *   onSearch={(filters) => fetchData(filters)}
 *   onPageChange={(page) => setCurrentPage(page)}
 *   onRowClick={(record) => navigate(`/detail/${record.id}`)}
 * />
 * ```
 */

import React, { useState, useCallback } from 'react';
import { SchemaFilterRenderer } from './SchemaFilterRenderer';
import { SchemaTableRenderer } from './SchemaTableRenderer';
import { SchemaActionRenderer } from './SchemaActionRenderer';
import { SchemaPagination } from './SchemaPagination';
import type {
  SchemaRendererProps,
  FilterValues,
  LocalizedText,
  RegionDefinition,
  ActionDefinition,
} from './types';
import type { DynamicEntity } from '~/types/dynamic';

/**
 * Utility hook for localized text resolution
 */
function useLocalizedText() {
  return useCallback((textObj: LocalizedText, fallback = ''): string => {
    if (typeof textObj === 'string') return textObj;
    if (typeof textObj === 'object' && textObj) {
      return textObj['zh-CN'] || textObj['en-US'] || fallback;
    }
    return fallback;
  }, []);
}

/**
 * Schema Renderer Component
 *
 * Main orchestrator that renders different regions based on schema configuration.
 */
export function SchemaRenderer({
  schema,
  data = [],
  loading = false,
  pagination,
  onSearch,
  onPageChange,
  onRowClick,
  onAction,
  onRowAction,
}: SchemaRendererProps) {
  const [filters, setFilters] = useState<FilterValues>({});
  const getLocalizedText = useLocalizedText();

  // Filter handlers
  const handleFilterChange = useCallback((key: string, value: unknown) => {
    setFilters((prev) => ({ ...prev, [key]: value }));
  }, []);

  const handleSearch = useCallback(() => {
    onSearch?.(filters);
  }, [filters, onSearch]);

  const handleReset = useCallback(() => {
    setFilters({});
    onSearch?.({});
  }, [onSearch]);

  // Action handlers
  const handleAction = useCallback(
    (action: ActionDefinition) => {
      if (onAction) {
        onAction(action);
      }
    },
    [onAction],
  );

  const handleRowAction = useCallback(
    (action: ActionDefinition, record: DynamicEntity) => {
      if (onRowAction) {
        onRowAction(action, record);
      }
    },
    [onRowAction],
  );

  // Pagination handler
  const handlePageChange = useCallback(
    (page: number) => {
      onPageChange?.(page);
    },
    [onPageChange],
  );

  // Render a single region based on its type
  const renderRegion = useCallback(
    (region: RegionDefinition, index: number) => {
      switch (region.type) {
        case 'filters':
          return (
            <SchemaFilterRenderer
              key={index}
              region={region}
              filters={filters}
              onFilterChange={handleFilterChange}
              onSearch={handleSearch}
              onReset={handleReset}
              getLocalizedText={getLocalizedText}
            />
          );

        case 'action':
          return (
            <SchemaActionRenderer
              key={index}
              region={region}
              title={schema.meta?.title || 'Data List'}
              onAction={handleAction}
              getLocalizedText={getLocalizedText}
            />
          );

        case 'table':
          return (
            <div key={index}>
              <SchemaTableRenderer
                region={region}
                data={data}
                loading={loading}
                schema={schema}
                onRowClick={onRowClick}
                onRowAction={handleRowAction}
                getLocalizedText={getLocalizedText}
              />
              {pagination && (
                <SchemaPagination pagination={pagination} onPageChange={handlePageChange} />
              )}
            </div>
          );

        default:
          return null;
      }
    },
    [
      filters,
      handleFilterChange,
      handleSearch,
      handleReset,
      handleAction,
      handleRowAction,
      handlePageChange,
      getLocalizedText,
      schema,
      data,
      loading,
      pagination,
      onRowClick,
    ],
  );

  return (
    <div className="rounded-lg bg-white shadow-sm">
      {schema.regions?.map((region, index) => renderRegion(region as RegionDefinition, index))}
    </div>
  );
}

export default SchemaRenderer;
