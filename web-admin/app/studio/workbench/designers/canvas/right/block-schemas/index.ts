/**
 * Block schemas — legacy barrel export.
 *
 * Block-level schemas have moved to app/studio/registry/blocks/.
 * Only BUTTON_CONFIG_SCHEMA and ACTION_DEF_SCHEMA remain here
 * (used by ButtonConfigPanel which does button-level, not block-level editing).
 */

export { ACTION_DEF_SCHEMA } from './toolbar';
export { BUTTON_CONFIG_SCHEMA } from './button-config';
