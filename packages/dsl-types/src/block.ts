import type { LocalizedText } from './localized-text.js'
import type { FieldSchema } from './field.js'
import type { ColumnSchema } from './column.js'
import type { ActionSchema } from './action.js'
import type { DataSourceRef } from './data-source.js'
import type { Expression } from './expression.js'

export type BlockType =
  | 'table'
  | 'form-section'
  | 'filters'
  | 'toolbar'
  | 'tabs'
  | 'sub-table'
  | 'stat-card'
  | 'chart'
  | 'custom'
  | (string & {})

export interface TableBlockConfig {
  dataSource: DataSourceRef
  columns: ColumnSchema[]
  /** In-table filter row. Reuses FieldSchema. */
  filters?: FieldSchema[]
  /** Per-row actions (edit, delete, …). */
  rowActions?: ActionSchema[]
  /** Bulk actions on selected rows. */
  bulkActions?: ActionSchema[]
  rowKey?: string
  selectable?: boolean
  pageSize?: number
}

export interface FormSectionBlockConfig {
  /** Reuses FieldSchema. */
  fields: FieldSchema[]
  layout?: 'grid' | 'stack'
  cols?: number
  /** Section title shown above the fields. */
  title?: LocalizedText
  /** Whether the section is collapsible. */
  collapsible?: boolean
  defaultCollapsed?: boolean
}

export interface FiltersBlockConfig {
  /** Filter form fields. Reuses FieldSchema. */
  fields: FieldSchema[]
  layout?: 'inline' | 'grid'
  cols?: number
  /** Whether to show "More filters" expander. */
  collapsible?: boolean
}

export interface ToolbarBlockConfig {
  actions: ActionSchema[]
  align?: 'left' | 'right' | 'between'
}

export interface TabSchema {
  code: string
  label: LocalizedText
  /** Nested block rendered within this tab. */
  blockType: BlockType
  config: BlockConfigUnion
  visibleWhen?: Expression
}

export interface TabsBlockConfig {
  tabs: TabSchema[]
  defaultTab?: string
}

export interface StatCardBlockConfig {
  /** Layout: how many cards per row. */
  cols?: number
  cards: Array<{
    key: string
    title: LocalizedText
    dataSource: DataSourceRef
    /** Field path within the data source result. */
    valueField: string
    icon?: string
    trend?: 'up' | 'down' | 'flat'
  }>
}

export interface ChartBlockConfig {
  /** Chart type: line / bar / pie / area / scatter / … (registry-extensible). */
  chartType: string
  dataSource: DataSourceRef
  /** Chart-type-specific config (axis, series, colors). */
  config?: Record<string, unknown>
}

export interface SubTableBlockConfig {
  /** Resolution mode determines how rows are loaded. */
  mode: 'foreignKey' | 'resolveVia' | 'dataSource'
  /** Column definitions. Reuses ColumnSchema. */
  columns: ColumnSchema[]
  /** Inline-editable columns. Reuses FieldSchema. */
  editableColumns?: FieldSchema[]
  /** Foreign key field on the parent record (mode=foreignKey). */
  foreignKey?: string
  /** Relation path (mode=resolveVia). */
  resolveVia?: string
  /** Explicit data source (mode=dataSource). */
  dataSource?: DataSourceRef
  rowActions?: ActionSchema[]
  bulkActions?: ActionSchema[]
}

export interface CustomBlockConfig {
  /** Component code registered in the widget registry. */
  componentRef: string
  /** Component-specific props. */
  props?: Record<string, unknown>
}

/**
 * Discriminated union of all known block configs. Plugins extending block
 * types should declare module augmentation to add their config shape.
 */
export type BlockConfigUnion =
  | TableBlockConfig
  | FormSectionBlockConfig
  | FiltersBlockConfig
  | ToolbarBlockConfig
  | TabsBlockConfig
  | StatCardBlockConfig
  | ChartBlockConfig
  | SubTableBlockConfig
  | CustomBlockConfig
  | Record<string, unknown>

/**
 * A single block within a page. The `config` shape is determined by `blockType`.
 *
 * For type-narrowing in consumers, use a discriminated check on `blockType`.
 */
export interface BlockSchema {
  /** Stable identifier within the page (used for layout, drag-drop, persistence). */
  id?: string
  blockType: BlockType
  config: BlockConfigUnion

  /** Optional block-level title shown in the header. */
  title?: LocalizedText

  /** Block-level visibility expression. */
  visibleWhen?: Expression

  /** Layout-level positioning (when page layout is grid). */
  position?: { x: number; y: number; w: number; h: number }
}
