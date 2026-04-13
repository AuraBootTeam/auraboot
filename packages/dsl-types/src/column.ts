import type { Expression } from './expression.js'
import type { LocalizedText } from './localized-text.js'

/**
 * Semantic column type. Drives default rendering and sort/filter behavior.
 * Plugins may register new column types via the column renderer registry.
 */
export type ColumnType =
  | 'text'
  | 'number'
  | 'money'
  | 'percent'
  | 'date'
  | 'datetime'
  | 'boolean'
  | 'enum'
  | 'relation'
  | 'status-badge'
  | 'tags'
  | 'user'
  | 'org'
  | 'avatar'
  | 'image'
  | 'link'
  | 'progress'
  | 'rating'
  | 'json'
  | 'custom'
  | (string & {})

export interface RendererRef {
  /** Registered renderer code. */
  type: string
  /** Renderer-specific props. Validated against the renderer's propsSchema. */
  props?: Record<string, unknown>
}

/**
 * A single table column. Reused across `table` and `sub-table` blocks.
 */
export interface ColumnSchema {
  /** Logical key — usually the model attribute path. */
  field: string

  /** Display title. If omitted, resolved from i18n by model+field. */
  title?: LocalizedText

  /** Semantic column type — drives default renderer. */
  columnType?: ColumnType

  /** Override renderer with explicit type + props. */
  renderer?: RendererRef

  /** Convenience: shorthand for renderer.props when using the default renderer. */
  rendererProps?: Record<string, unknown>

  width?: number | string
  minWidth?: number
  maxWidth?: number

  fixed?: 'left' | 'right'
  align?: 'left' | 'center' | 'right'

  sortable?: boolean
  filterable?: boolean
  resizable?: boolean

  hidden?: boolean
  visibleWhen?: Expression

  /** Tooltip / help text shown in column header. */
  help?: LocalizedText
}
