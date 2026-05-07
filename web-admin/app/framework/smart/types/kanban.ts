/**
 * Kanban Component Types
 *
 * Type definitions for SmartKanban component including cards, columns,
 * data sources, and event handlers.
 */

import type { ChartDataSource, FilterConfig } from './chart';

/**
 * Kanban card representing a single item in a column
 */
export interface KanbanCard {
  /** Unique identifier for the card */
  id: string;
  /** Additional card data fields */
  [key: string]: unknown;
}

/**
 * Kanban column (swim lane) containing cards
 */
export interface KanbanColumn {
  /** Unique identifier for the column */
  id: string;
  /** Display title of the column */
  title: string;
  /** Value of the groupBy field for this column */
  value: unknown;
  /** Cards in this column */
  cards: KanbanCard[];
  /** Number of cards in this column */
  count: number;
  /** Aggregation results for this column */
  aggregations?: Record<string, number>;
  /** Column accent color (resolved from dict extras or view config) */
  color?: string;
  /** Terminal stage marker for visual treatment (won/lost) */
  terminal?: 'won' | 'lost';
}

/**
 * Configuration for column aggregations (e.g., sum of story points)
 */
export interface KanbanAggregation {
  /** Field to aggregate */
  field: string;
  /** Aggregation function */
  function: 'count' | 'sum' | 'avg' | 'min' | 'max';
  /** Display label for the aggregation result */
  label?: string;
}

/**
 * Configuration for how a field is displayed on cards
 */
export interface KanbanCardField {
  /** Field name from the data */
  field: string;
  /** Display label */
  label?: string;
  /** Display type for formatting */
  type?: 'text' | 'number' | 'tag' | 'date' | 'currency' | 'avatar' | 'progress' | 'date-relative';
  /** Format string (e.g., date format) */
  format?: string;
  /** ISO 4217 currency code (only used when type='currency') */
  currencyCode?: string;
  /** Maximum value for progress rendering (only used when type='progress', defaults to 100) */
  max?: number;
}

/**
 * Data source configuration for Kanban, extending ChartDataSource
 */
export interface KanbanDataSource extends ChartDataSource {
  /** Field used to group cards into columns */
  groupByField: string;
  /** Field used as card ID, defaults to 'id' */
  idField?: string;
  /** Field used as card title */
  titleField: string;
  /** Field used as card description */
  descriptionField?: string;
  /** Fields to display on cards */
  cardFields?: KanbanCardField[];
  /** Aggregation configurations for columns */
  aggregations?: KanbanAggregation[];
}

/**
 * Event data when a card is moved between columns
 */
export interface KanbanCardMoveEvent {
  /** ID of the moved card */
  cardId: string;
  /** ID of the source column */
  sourceColumnId: string;
  /** ID of the target column */
  targetColumnId: string;
  /** Index position in the target column */
  targetIndex: number;
}

/**
 * Props for SmartKanban component
 */
export interface SmartKanbanProps {
  /** Kanban board title */
  title?: string;
  /** Data source configuration */
  dataSource: KanbanDataSource;
  /** Whether cards can be dragged between columns */
  draggable?: boolean;
  /** Whether to show card count in column headers */
  showCount?: boolean;
  /** Whether to show aggregation values in column headers */
  showAggregations?: boolean;
  /** Callback when a card is clicked */
  onCardClick?: (card: KanbanCard) => void;
  /** Callback when a card is moved */
  onCardMove?: (event: KanbanCardMoveEvent) => void;
  /** External filters from dashboard linkage */
  linkageFilters?: FilterConfig[];
  /** Additional CSS class */
  className?: string;
  /** Inline styles */
  style?: React.CSSProperties;
}
