/**
 * SmartKanban Component
 *
 * Display-only kanban board. Groups records by a dimension field into columns.
 * No drag-and-drop — cards are clickable for navigation only.
 */

import React, { useMemo, useCallback } from 'react';
import { useChartData } from '~/framework/smart/hooks/useChartData';
import type {
  ChartDataSource,
  DrillDownConfig,
  LinkageConfig,
  FilterConfig,
} from '~/framework/smart/types/chart';
import { cn } from '~/utils/cn';

interface KanbanCard {
  id: string;
  title: string;
  description?: string;
  [key: string]: unknown;
}

interface KanbanColumn {
  key: string;
  label: string;
  color?: string;
  cards: KanbanCard[];
}

export interface SmartKanbanProps {
  title?: string;
  dataSource: ChartDataSource;
  groupField?: string;
  titleField?: string;
  descriptionField?: string;
  columnColors?: Record<string, string>;
  maxCardsPerColumn?: number;
  showCount?: boolean;
  cardClickUrl?: string;
  columnOrder?: string[];
  drillDown?: DrillDownConfig;
  linkage?: LinkageConfig;
  onDrillDown?: (filters: FilterConfig[]) => void;
  onLinkageEmit?: (filters: FilterConfig[]) => void;
  linkageFilters?: FilterConfig[];
  refreshInterval?: number;
  className?: string;
  style?: React.CSSProperties;
}

const DEFAULT_COLUMN_COLORS = ['#e6f7ff', '#f6ffed', '#fff7e6', '#fff1f0', '#f9f0ff', '#e6fffb', '#fcffe6'];

function isDataSourceConfigured(ds: ChartDataSource): boolean {
  if (!ds) return false;
  if (ds.type === 'aggregate') return !!(ds.modelCode && ds.metrics?.length);
  if (ds.type === 'namedQuery') return !!ds.queryCode;
  return ds.type === 'static';
}

export const SmartKanban: React.FC<SmartKanbanProps> = ({
  title,
  dataSource,
  groupField,
  titleField,
  descriptionField,
  columnColors,
  maxCardsPerColumn = 10,
  showCount = true,
  cardClickUrl,
  columnOrder,
  drillDown,
  linkage,
  onDrillDown,
  onLinkageEmit,
  linkageFilters,
  refreshInterval,
  className,
  style,
}) => {
  const isConfigured = isDataSourceConfigured(dataSource);

  const { data, loading, error } = useChartData({
    dataSource,
    linkageFilters: linkage?.receiveFilter ? linkageFilters : undefined,
    refreshInterval,
    enabled: isConfigured,
  });

  const columns: KanbanColumn[] = useMemo(() => {
    if (!data?.rows?.length || !groupField) return [];

    const groups = new Map<string, KanbanCard[]>();

    data.rows.forEach((row, idx) => {
      const groupValue = String(row[groupField] ?? 'Unknown');
      if (!groups.has(groupValue)) groups.set(groupValue, []);

      const card: KanbanCard = {
        id: String(row['id'] ?? row['pid'] ?? idx),
        title: titleField ? String(row[titleField] ?? '') : groupValue,
        description: descriptionField ? String(row[descriptionField] ?? '') : undefined,
        ...row,
      };
      groups.get(groupValue)!.push(card);
    });

    let orderedKeys: string[];
    if (columnOrder?.length) {
      const seen = new Set(columnOrder);
      orderedKeys = [...columnOrder, ...[...groups.keys()].filter((k) => !seen.has(k))];
    } else {
      orderedKeys = [...groups.keys()];
    }

    return orderedKeys
      .filter((key) => groups.has(key))
      .map((key, idx) => ({
        key,
        label: key,
        color: columnColors?.[key] || DEFAULT_COLUMN_COLORS[idx % DEFAULT_COLUMN_COLORS.length],
        cards: groups.get(key) || [],
      }));
  }, [data, groupField, titleField, descriptionField, columnColors, columnOrder]);

  const handleCardClick = useCallback(
    (card: KanbanCard) => {
      if (cardClickUrl) {
        const url = cardClickUrl.replace(/\{(\w+)\}/g, (_, field) => String(card[field] ?? ''));
        window.open(url, '_blank', 'noopener');
      }
      if (drillDown?.enabled && onDrillDown && groupField) {
        onDrillDown([{ field: groupField, operator: 'eq', value: card[groupField] }]);
      }
      if (linkage?.enabled && linkage?.emitFilter && onLinkageEmit && groupField) {
        onLinkageEmit([{ field: groupField, operator: 'eq', value: card[groupField] }]);
      }
    },
    [cardClickUrl, drillDown, linkage, onDrillDown, onLinkageEmit, groupField],
  );

  if (!isConfigured || !groupField) {
    return (
      <div
        className={cn(
          'flex items-center justify-center rounded-lg border border-dashed border-gray-300 bg-white p-4',
          className,
        )}
        style={{ minHeight: 0, ...style }}
      >
        <div className="text-center">
          <div className="mb-3 text-4xl text-gray-400">📋</div>
          <div className="font-medium text-gray-500">{title || 'Kanban'}</div>
          <div className="mt-1 text-sm text-gray-400">
            {!groupField ? 'Please configure group field' : 'Please configure data source'}
          </div>
        </div>
      </div>
    );
  }

  if (loading) {
    return (
      <div
        className={cn(
          'flex items-center justify-center rounded-lg border border-gray-200 bg-white p-4',
          className,
        )}
        style={{ minHeight: 0, ...style }}
      >
        <div className="flex flex-col items-center gap-2">
          <div className="h-8 w-8 animate-spin rounded-full border-2 border-blue-500 border-t-transparent" />
          <span className="text-sm text-gray-500">Loading...</span>
        </div>
      </div>
    );
  }

  if (error) {
    return (
      <div
        className={cn(
          'flex items-center justify-center rounded-lg border border-red-200 bg-white p-4',
          className,
        )}
        style={{ minHeight: 0, ...style }}
        role="alert"
      >
        <div className="text-center">
          <div className="mb-2 text-lg text-red-500">Failed to load kanban</div>
          <div className="text-sm text-gray-500">{error.message}</div>
        </div>
      </div>
    );
  }

  if (columns.length === 0) {
    return (
      <div
        className={cn(
          'flex items-center justify-center rounded-lg border border-dashed border-gray-300 bg-white p-4',
          className,
        )}
        style={{ minHeight: 0, ...style }}
      >
        <div className="text-center">
          <div className="mb-3 text-4xl text-gray-400">📋</div>
          <div className="font-medium text-gray-500">No data</div>
          <div className="mt-1 text-sm text-gray-400">No records found for kanban</div>
        </div>
      </div>
    );
  }

  return (
    <div className={cn('flex h-full flex-col overflow-hidden rounded-lg border border-gray-200 bg-white', className)} style={style}>
      {title && (
        <div className="border-b border-gray-100 px-4 py-2">
          <h3 className="text-sm font-medium text-gray-700">{title}</h3>
        </div>
      )}

      <div className="flex flex-1 gap-3 overflow-x-auto p-3">
        {columns.map((col) => {
          const visibleCards = col.cards.slice(0, maxCardsPerColumn);
          const hiddenCount = col.cards.length - visibleCards.length;

          return (
            <div
              key={col.key}
              className="flex min-w-[180px] flex-1 flex-col rounded-lg"
              style={{ backgroundColor: col.color }}
            >
              <div className="flex items-center justify-between px-3 py-2">
                <span className="text-xs font-semibold text-gray-700 uppercase">{col.label}</span>
                {showCount && (
                  <span className="rounded-full bg-white/60 px-2 py-0.5 text-xs font-medium text-gray-600">
                    {col.cards.length}
                  </span>
                )}
              </div>

              <div className="flex flex-1 flex-col gap-2 overflow-y-auto px-2 pb-2">
                {visibleCards.map((card) => (
                  <div
                    key={card.id}
                    className={cn(
                      'rounded-md bg-white p-2.5 shadow-sm transition-shadow',
                      cardClickUrl && 'cursor-pointer hover:shadow-md',
                    )}
                    onClick={() => handleCardClick(card)}
                  >
                    <div className="truncate text-xs font-medium text-gray-800">{card.title}</div>
                    {card.description && (
                      <div className="mt-1 line-clamp-2 text-xs text-gray-500">{card.description}</div>
                    )}
                  </div>
                ))}
                {hiddenCount > 0 && (
                  <div className="py-1 text-center text-xs text-gray-400">
                    +{hiddenCount} more
                  </div>
                )}
                {col.cards.length === 0 && (
                  <div className="flex flex-1 items-center justify-center rounded-md border border-dashed border-gray-200 py-4">
                    <span className="text-xs text-gray-400">No items</span>
                  </div>
                )}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
};

export default SmartKanban;
