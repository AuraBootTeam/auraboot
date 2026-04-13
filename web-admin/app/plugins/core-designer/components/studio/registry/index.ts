/**
 * Studio Registry — public API
 *
 * Exports WidgetRegistry, BlockRegistry, common schema helpers, and their types.
 * Import from this barrel when consuming the registry in palette, canvas, or config panels.
 *
 * @since 4.3.0
 */

export { WidgetRegistry } from './widget-registry';
export { BlockRegistry } from './block-registry';
export { COMMON_FIELD_SCHEMA, buildFieldSchema } from './common-field-schema';
export { initRegistry } from './init';
export type { WidgetDefinition, BlockDefinition } from './types';
