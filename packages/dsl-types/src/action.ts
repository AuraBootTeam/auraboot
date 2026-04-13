import type { Expression } from './expression.js'
import type { LocalizedText } from './localized-text.js'

export type ActionType =
  | 'submit'
  | 'reset'
  | 'cancel'
  | 'command'
  | 'navigate'
  | 'dialog'
  | 'drawer'
  | 'export'
  | 'import'
  | 'custom'
  | (string & {})

/**
 * Action — a button, menu item, or row-level operation. Used in toolbars,
 * form footers, table row actions, and bulk action menus.
 */
export interface ActionSchema {
  key: string
  label: LocalizedText
  actionType: ActionType

  icon?: string

  /** Command code when actionType=command. */
  command?: string

  /** Target path when actionType=navigate. */
  to?: string

  /** Permissions required. ALL must be granted. */
  permission?: string | string[]

  /** Feature gate. ALL must be entitled. */
  featureKey?: string | string[]

  /** Whether this action operates on multiple selected rows (bulk). */
  bulk?: boolean

  /** Whether this action is shown per-row in tables. */
  rowLevel?: boolean

  visibleWhen?: Expression
  disabledWhen?: Expression

  /** Visual style. */
  variant?: 'primary' | 'secondary' | 'danger' | 'ghost' | 'link'

  /** Confirmation prompt before executing. */
  confirm?: {
    title?: LocalizedText
    message?: LocalizedText
  }

  /** Action-specific props. */
  props?: Record<string, unknown>
}
