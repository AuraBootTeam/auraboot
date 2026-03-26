/**
 * KanbanView Component
 *
 * Adapter that bridges SavedView's ViewConfig to SmartKanban's KanbanDataSource.
 * Reads kanban configuration from ViewConfig and renders the SmartKanban component.
 */

import React, { useMemo } from 'react';
import { SmartKanban } from '~/smart/components/kanban/SmartKanban';
import type { ViewConfig } from '~/smart/types/savedView';
import type { KanbanDataSource, KanbanCard, KanbanCardMoveEvent } from '~/smart/types/kanban';
import type { FilterConfig } from '~/smart/types/chart';
import { cn } from '~/utils/cn';

/**
 * Props for KanbanView component
 */
export interface KanbanViewProps {
  /** View configuration containing kanban settings */
  viewConfig?: ViewConfig;
  /** Model code for data fetching */
  modelCode: string;
  /** Callback when a card is clicked */
  onCardClick?: (card: KanbanCard) => void;
  /** Callback when a card is moved between columns */
  onCardMove?: (event: KanbanCardMoveEvent) => void;
  /** External filter conditions */
  linkageFilters?: FilterConfig[];
  /** Custom CSS class */
  className?: string;
}

/**
 * KanbanView - Bridges SavedView ViewConfig to SmartKanban
 *
 * Converts the flat kanban fields from ViewConfig into a KanbanDataSource
 * that SmartKanban can consume.
 */
export const KanbanView: React.FC<KanbanViewProps> = ({
  viewConfig,
  modelCode,
  onCardClick,
  onCardMove,
  linkageFilters,
  className,
}) => {
  const dataSource = useMemo<KanbanDataSource | null>(() => {
    if (!viewConfig?.groupByField || !viewConfig?.titleField) {
      return null;
    }

    return {
      type: 'aggregate',
      modelCode,
      groupByField: viewConfig.groupByField,
      idField: viewConfig.idField || 'id',
      titleField: viewConfig.titleField,
      descriptionField: viewConfig.descriptionField,
      cardFields: viewConfig.cardFields?.map((cf) => ({
        field: cf.field,
        label: cf.label,
        type: cf.type as 'text' | 'number' | 'date' | 'tag' | 'avatar' | undefined,
      })),
      aggregations: viewConfig.kanbanAggregations?.map((agg) => ({
        field: agg.field,
        function: agg.function,
        label: agg.label,
      })),
      filters: viewConfig.filters
        ?.filter((f) => ['eq', 'ne', 'gt', 'gte', 'lt', 'lte', 'like', 'in'].includes(f.operator))
        .map((f) => ({
          field: f.fieldCode,
          operator: f.operator as FilterConfig['operator'],
          value: f.value,
        })),
    };
  }, [viewConfig, modelCode]);

  if (!dataSource) {
    return (
      <div
        className={cn(
          'flex items-center justify-center rounded-lg border border-gray-200 bg-white p-8',
          className,
        )}
        style={{ minHeight: 400 }}
      >
        <div className="text-center text-gray-500">
          <div className="mb-2 text-lg">Kanban not configured</div>
          <div className="text-sm">
            Please configure the Group By field and Title field to display the Kanban board.
          </div>
        </div>
      </div>
    );
  }

  return (
    <SmartKanban
      dataSource={dataSource}
      draggable={viewConfig?.draggable ?? true}
      showCount={viewConfig?.showCount ?? true}
      showAggregations={viewConfig?.showAggregations ?? false}
      onCardClick={onCardClick}
      onCardMove={onCardMove}
      linkageFilters={linkageFilters}
      className={className}
    />
  );
};

export default KanbanView;
