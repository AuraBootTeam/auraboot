/**
 * SmartKanban Component
 *
 * A Kanban board component with drag-and-drop support using @dnd-kit.
 * Groups data by a specified field into columns and allows moving cards between columns.
 */

import React, { useState, useCallback, useMemo } from 'react';
import {
  DndContext,
  DragOverlay,
  closestCenter,
  PointerSensor,
  useDroppable,
  useSensor,
  useSensors,
  type DragStartEvent,
  type DragEndEvent,
} from '@dnd-kit/core';
import { SortableContext, verticalListSortingStrategy } from '@dnd-kit/sortable';
import { Check, X } from 'lucide-react';
import { useKanbanData } from '~/framework/smart/hooks/useKanbanData';
import { useDictWithExtras } from '~/framework/smart/hooks/useDictWithExtras';
import { KanbanCardItem } from './KanbanCardItem';
import type { SmartKanbanProps, KanbanCard, KanbanColumn } from '~/framework/smart/types/kanban';
import { cn } from '~/utils/cn';

/**
 * SmartKanban - A Kanban board with drag-and-drop support
 *
 * @example
 * // Basic Kanban board
 * <SmartKanban
 *   title="Task Board"
 *   dataSource={{
 *     type: 'aggregate',
 *     modelCode: 'task',
 *     groupByField: 'status',
 *     titleField: 'title',
 *     descriptionField: 'description',
 *   }}
 *   draggable
 *   showCount
 *   onCardMove={(event) => console.log('Card moved:', event)}
 * />
 *
 * @example
 * // With aggregations
 * <SmartKanban
 *   dataSource={{
 *     type: 'aggregate',
 *     modelCode: 'task',
 *     groupByField: 'status',
 *     titleField: 'title',
 *     aggregations: [{ field: 'storyPoints', function: 'sum', label: 'Points' }],
 *   }}
 *   showAggregations
 * />
 */
export const SmartKanban: React.FC<SmartKanbanProps> = ({
  title,
  dataSource,
  draggable = true,
  showCount = true,
  showAggregations = false,
  onCardClick,
  onCardMove,
  linkageFilters,
  groupByDictCode,
  terminalStages,
  pageKey,
  className,
  style,
}) => {
  // State for active drag
  const [activeCard, setActiveCard] = useState<KanbanCard | null>(null);
  const [activeColumnId, setActiveColumnId] = useState<string | null>(null);

  // Resolve per-stage color / terminal from dict extension (no-op when dictCode absent).
  const { items: dictItems } = useDictWithExtras(groupByDictCode);

  // Fetch Kanban data. Pass dict items so the hook seeds the full set of
  // columns in dict order, even for stages with zero cards (full pipeline
  // visibility — see backlog 2026-05-08 Gap 2).
  const { columns, loading, error, moveCard } = useKanbanData({
    dataSource,
    linkageFilters,
    groupByDictItems: dictItems.length > 0 ? dictItems : undefined,
    pageKey,
  });

  /**
   * Enrich columns with color/terminal:
   *   - viewConfig.terminalStages takes precedence (per-pipeline override)
   *   - falls back to dict item's flattened terminal
   *   - color is dict-derived when present, falling back to whatever the
   *     column already carries (e.g. seeded from dataSource).
   */
  const enrichedColumns = useMemo<KanbanColumn[]>(() => {
    if (dictItems.length === 0 && !terminalStages) {
      return columns;
    }
    const dictByValue = new Map(dictItems.map((it) => [it.value, it]));
    const wonSet = new Set(terminalStages?.won ?? []);
    const lostSet = new Set(terminalStages?.lost ?? []);
    return columns.map((col) => {
      const dictItem = dictByValue.get(col.id);
      const terminalFromConfig: 'won' | 'lost' | undefined = wonSet.has(col.id)
        ? 'won'
        : lostSet.has(col.id)
          ? 'lost'
          : undefined;
      return {
        ...col,
        color: dictItem?.color ?? col.color,
        terminal: terminalFromConfig ?? dictItem?.terminal ?? col.terminal,
      };
    });
  }, [columns, dictItems, terminalStages]);

  // Configure drag sensors
  const sensors = useSensors(
    useSensor(PointerSensor, {
      activationConstraint: {
        distance: 8,
      },
    }),
  );

  /**
   * Find a card by ID across all columns
   */
  const findCardAndColumn = useCallback(
    (cardId: string): { card: KanbanCard; columnId: string } | null => {
      for (const column of enrichedColumns) {
        const card = column.cards.find((c) => c.id === cardId);
        if (card) {
          return { card, columnId: column.id };
        }
      }
      return null;
    },
    [enrichedColumns],
  );

  /**
   * Handle drag start - find the dragged card and its source column
   */
  const handleDragStart = useCallback(
    (event: DragStartEvent) => {
      const { active } = event;
      const cardId = String(active.id);
      const result = findCardAndColumn(cardId);

      if (result) {
        setActiveCard(result.card);
        setActiveColumnId(result.columnId);
      }
    },
    [findCardAndColumn],
  );

  /**
   * Handle drag end - move card to target column
   */
  const handleDragEnd = useCallback(
    (event: DragEndEvent) => {
      const { active, over } = event;

      if (!over || !activeColumnId) {
        setActiveCard(null);
        setActiveColumnId(null);
        return;
      }

      const cardId = String(active.id);
      const overId = String(over.id);

      // Find target column - over.id can be a column ID or a card ID
      let targetColumnId: string | null = null;
      let targetIndex = 0;

      // Check if overId is a column ID
      const targetColumn = enrichedColumns.find((col) => col.id === overId);
      if (targetColumn) {
        targetColumnId = targetColumn.id;
        targetIndex = targetColumn.cards.length;
      } else {
        // overId is a card ID - find its column
        const result = findCardAndColumn(overId);
        if (result) {
          targetColumnId = result.columnId;
          const column = enrichedColumns.find((col) => col.id === targetColumnId);
          if (column) {
            targetIndex = column.cards.findIndex((c) => c.id === overId);
          }
        }
      }

      if (targetColumnId && targetColumnId !== activeColumnId) {
        // Optimistic update
        moveCard(cardId, activeColumnId, targetColumnId, targetIndex);

        // Notify parent
        if (onCardMove) {
          onCardMove({
            cardId,
            sourceColumnId: activeColumnId,
            targetColumnId,
            targetIndex,
          });
        }
      }

      setActiveCard(null);
      setActiveColumnId(null);
    },
    [activeColumnId, enrichedColumns, findCardAndColumn, moveCard, onCardMove],
  );

  /**
   * Render a single column
   */
  const renderColumn = useCallback(
    (column: KanbanColumn) => {
      const cardIds = column.cards.map((c) => c.id);

      const headerClass = cn(
        'rounded-t-lg border-b p-3',
        column.terminal === 'won' && 'bg-green-50 text-green-800',
        column.terminal === 'lost' && 'bg-gray-100 text-gray-600',
        !column.terminal && 'bg-gray-50',
      );
      // Dict-derived hex color is applied as a tinted background only when there
      // is no terminal-state styling claiming the header. The `${color}20` hex
      // alpha suffix matches what other dict-color consumers in the platform use.
      const headerStyle =
        !column.terminal && column.color
          ? { backgroundColor: `${column.color}20`, color: column.color }
          : undefined;
      const titleClass = cn(
        'truncate text-sm font-medium',
        !column.terminal && !column.color && 'text-gray-700',
      );

      return (
        <div
          key={column.id}
          className="flex max-h-full w-72 shrink-0 flex-col rounded-lg bg-gray-100"
        >
          {/* Column header */}
          <div
            className={headerClass}
            style={headerStyle}
            data-testid="kanban-column-header"
            data-column-id={column.id}
            data-column-terminal={column.terminal ?? ''}
          >
            <div className="flex items-center justify-between">
              <span className={titleClass}>
                {column.terminal === 'won' && <Check className="mr-1 inline h-4 w-4" />}
                {column.terminal === 'lost' && <X className="mr-1 inline h-4 w-4" />}
                {column.title}
              </span>
              {showCount && (
                <span className="ml-2 inline-flex h-6 min-w-[1.5rem] items-center justify-center rounded-full bg-gray-200 px-1.5 text-xs font-medium text-gray-600">
                  {column.count}
                </span>
              )}
            </div>
            {/* Aggregations */}
            {showAggregations && column.aggregations && (
              <div className="mt-2 flex flex-wrap gap-2">
                {Object.entries(column.aggregations).map(([key, value]) => (
                  <span key={key} className="text-xs text-gray-500">
                    {key}: <span className="font-medium">{value}</span>
                  </span>
                ))}
              </div>
            )}
          </div>

          {/* Cards area — wrapped in useDroppable so empty columns still
              accept drops (SortableContext alone only registers existing
              cards as drop targets). The column body itself becomes a
              droppable; handleDragEnd resolves over.id === column.id. */}
          <KanbanColumnDropZone
            columnId={column.id}
            cardIds={cardIds}
            cards={column.cards}
            draggable={draggable}
            dataSource={dataSource}
            onCardClick={onCardClick}
            terminal={column.terminal}
          />
        </div>
      );
    },
    [dataSource, draggable, onCardClick, showAggregations, showCount],
  );

  // All column IDs for droppable targets
  const columnIds = useMemo(() => enrichedColumns.map((col) => col.id), [enrichedColumns]);

  // Loading state
  if (loading) {
    return (
      <div
        className={cn(
          'flex items-center justify-center rounded-lg border border-gray-200 bg-white p-4',
          className,
        )}
        style={{ minHeight: 400, ...style }}
      >
        <div className="flex flex-col items-center gap-2">
          <div className="h-8 w-8 animate-spin rounded-full border-2 border-blue-500 border-t-transparent" />
          <span className="text-sm text-gray-500">Loading...</span>
        </div>
      </div>
    );
  }

  // Error state
  if (error) {
    return (
      <div
        className={cn(
          'flex items-center justify-center rounded-lg border border-red-200 bg-white p-4',
          className,
        )}
        style={{ minHeight: 400, ...style }}
        role="alert"
      >
        <div className="text-center">
          <div className="mb-2 text-lg text-red-500">Failed to load Kanban board</div>
          <div className="text-sm text-gray-500">{error.message}</div>
        </div>
      </div>
    );
  }

  return (
    <div className={cn('rounded-lg border border-gray-200 bg-white', className)} style={style}>
      {/* Title */}
      {title && (
        <div className="border-b border-gray-200 px-4 py-3">
          <h3 className="text-lg font-medium text-gray-900">{title}</h3>
        </div>
      )}

      {/* Kanban board */}
      <div className="p-4">
        <DndContext
          sensors={sensors}
          collisionDetection={closestCenter}
          onDragStart={handleDragStart}
          onDragEnd={handleDragEnd}
        >
          <SortableContext items={columnIds} strategy={verticalListSortingStrategy}>
            <div className="flex gap-4 overflow-x-auto pb-2">
              {enrichedColumns.map(renderColumn)}
            </div>
          </SortableContext>

          {/* Drag overlay - shows card preview while dragging */}
          <DragOverlay>
            {activeCard && (
              <div className="opacity-90 shadow-lg">
                <KanbanCardItem
                  card={activeCard}
                  titleField={dataSource.titleField}
                  descriptionField={dataSource.descriptionField}
                  cardFields={dataSource.cardFields}
                  draggable={false}
                  terminal={
                    enrichedColumns.find((col) =>
                      col.cards.some((c) => c.id === activeCard.id),
                    )?.terminal
                  }
                />
              </div>
            )}
          </DragOverlay>
        </DndContext>
      </div>

      {/* Empty state */}
      {enrichedColumns.length === 0 && (
        <div className="p-8 text-center text-gray-500">No data available</div>
      )}
    </div>
  );
};

export default SmartKanban;

// ---------------------------------------------------------------------------
// KanbanColumnDropZone — column body wired with `useDroppable`.
//
// Without this, empty columns (dict-derived stages with zero cards) cannot
// accept drops — SortableContext only registers its own items as drop
// targets. We hoist column-body rendering into a child component so it can
// call `useDroppable` per-column without violating the rules of hooks (the
// parent's renderColumn is a useCallback that runs in a loop).
// ---------------------------------------------------------------------------
interface KanbanColumnDropZoneProps {
  columnId: string;
  cardIds: string[];
  cards: KanbanCard[];
  draggable: boolean;
  dataSource: SmartKanbanProps['dataSource'];
  onCardClick?: SmartKanbanProps['onCardClick'];
  terminal?: 'won' | 'lost';
}

const KanbanColumnDropZone: React.FC<KanbanColumnDropZoneProps> = ({
  columnId,
  cardIds,
  cards,
  draggable,
  dataSource,
  onCardClick,
  terminal,
}) => {
  const { setNodeRef, isOver } = useDroppable({ id: columnId });

  return (
    <div
      ref={setNodeRef}
      className={cn(
        'flex-1 space-y-2 overflow-y-auto p-2',
        isOver && 'bg-blue-50/50',
      )}
      data-testid="kanban-column-body"
      data-column-id={columnId}
    >
      <SortableContext items={cardIds} strategy={verticalListSortingStrategy}>
        {cards.map((card) => (
          <KanbanCardItem
            key={card.id}
            card={card}
            titleField={dataSource.titleField}
            descriptionField={dataSource.descriptionField}
            cardFields={dataSource.cardFields}
            draggable={draggable}
            terminal={terminal}
            onClick={onCardClick}
          />
        ))}
      </SortableContext>
      {/* Empty column hint — the parent div is the actual droppable. */}
      {cards.length === 0 && (
        <div className="flex h-24 items-center justify-center rounded-lg border-2 border-dashed border-gray-300 text-sm text-gray-400">
          Drop here
        </div>
      )}
    </div>
  );
};
