import type { Expression } from './expression.js'
import type { LocalizedText } from './localized-text.js'
import type { DataSourceRef } from './data-source.js'

/**
 * Semantic field type. Drives validation, default widget choice, and storage.
 * Plugins may register new field types via the widget registry.
 */
export type FieldType =
  | 'string'
  | 'text'
  | 'number'
  | 'integer'
  | 'decimal'
  | 'money'
  | 'boolean'
  | 'date'
  | 'datetime'
  | 'time'
  | 'enum'
  | 'multi-enum'
  | 'relation'
  | 'multi-relation'
  | 'json'
  | 'file'
  | 'image'
  | 'rich-text'
  | 'address'
  | 'user'
  | 'org'
  | (string & {})

/**
 * Display widget type. A field's `fieldType` selects a default widget; setting
 * `widget` explicitly overrides. Plugins register widgets via the registry.
 */
export type WidgetType =
  | 'text-input'
  | 'textarea'
  | 'number-input'
  | 'money-input'
  | 'select'
  | 'multi-select'
  | 'radio-group'
  | 'checkbox'
  | 'switch'
  | 'date-picker'
  | 'datetime-picker'
  | 'date-range-picker'
  | 'rich-text-editor'
  | 'file-upload'
  | 'image-upload'
  | 'user-picker'
  | 'org-picker'
  | 'cascade-select'
  | 'tree-select'
  | (string & {})

export interface ValidatorRef {
  type: 'required' | 'min' | 'max' | 'pattern' | 'email' | 'url' | 'custom' | (string & {})
  value?: unknown
  message?: LocalizedText
}

/**
 * A single editable field. Reused across `form-section`, `filters`,
 * `sub-table.editableColumns`. The `widgetProps` shape is validated at
 * registration time against the widget's declared `propsSchema`.
 */
export interface FieldSchema {
  /** Field name — matches the model attribute when bound to data. */
  name: string

  /** Semantic type — drives validation and default widget. */
  fieldType: FieldType

  /** Display widget. If omitted, derived from `fieldType`. */
  widget?: WidgetType

  /** Display label. If omitted, resolved from i18n by model+field. */
  label?: LocalizedText

  /** Help text shown next to the field. */
  help?: LocalizedText
  placeholder?: LocalizedText

  required?: boolean
  readonly?: boolean
  hidden?: boolean

  defaultValue?: unknown

  /** Conditional visibility/enable/required. */
  visibleWhen?: Expression
  disabledWhen?: Expression
  requiredWhen?: Expression

  validators?: ValidatorRef[]

  /** Data source for enum/relation/dictionary widgets. */
  dataSource?: DataSourceRef

  /** Layout hint for grid forms. */
  colSpan?: number

  /** Section/tab grouping when used inside form-section with sections. */
  section?: string
  tab?: string

  /** Widget-specific props. Validated against the widget's propsSchema. */
  widgetProps?: Record<string, unknown>
}
