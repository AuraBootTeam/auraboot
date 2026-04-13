/**
 * Schema Renderer - Re-export for backwards compatibility
 *
 * This file re-exports the modular SchemaRenderer from the schema-renderer directory.
 * The component has been refactored into smaller, more maintainable sub-components:
 *
 * - SchemaRenderer: Main orchestrator (this export)
 * - SchemaFilterRenderer: Filter/search section
 * - SchemaTableRenderer: Data table section
 * - SchemaActionRenderer: Action toolbar section
 * - SchemaPagination: Pagination controls
 *
 * For new code, prefer importing from '~/ui/schema-renderer' directly:
 * @example
 * ```tsx
 * import { SchemaRenderer, SchemaFilterRenderer } from '~/ui/schema-renderer';
 * ```
 *
 * This file maintains backwards compatibility for existing imports:
 * @example
 * ```tsx
 * import { SchemaRenderer } from '~/ui/SchemaRenderer';
 * ```
 */

export { SchemaRenderer, default } from './schema-renderer';

// Also export types for convenience
export type { SchemaRendererProps, FilterValues, PaginationState } from './schema-renderer';
