import type { LocalizedText } from './localized-text.js'
import type { BlockSchema } from './block.js'

export type PageKind = 'list' | 'form' | 'detail' | 'dashboard'
export type PageProfile = 'admin' | 'report'

export interface StackLayout {
  type: 'stack'
}

export interface GridLayout {
  type: 'grid'
  cols?: number
}

export type PageLayout = StackLayout | GridLayout

/**
 * Top-level page document. Stored in `ab_page_schema` table:
 * `{ kind, title, layout, profile, blocks }`.
 *
 * Identical JSON shape as today's V2 format — this type adds a contract.
 */
export interface PageSchema {
  kind: PageKind
  title?: LocalizedText
  layout?: PageLayout
  profile?: PageProfile
  blocks: BlockSchema[]

  /** Optional page-level metadata. */
  meta?: Record<string, unknown>
}
