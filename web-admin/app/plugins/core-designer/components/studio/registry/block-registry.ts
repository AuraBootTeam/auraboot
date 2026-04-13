/**
 * Block Registry
 *
 * Singleton registry for all page-layout block definitions.
 * Consumers call BlockRegistry.getAll() instead of importing a hardcoded array.
 *
 * @since 4.3.0
 */

import type { BlockDefinition } from './types';
import type { PropertySchema } from '~/shared/designer/types';

const blocks = new Map<string, BlockDefinition>();

export const BlockRegistry = {
  /**
   * Register a block definition.
   * Subsequent calls with the same type key overwrite the previous entry.
   */
  register(def: BlockDefinition): void {
    blocks.set(def.type, def);
  },

  /** Look up a block definition by its type key. */
  get(type: string): BlockDefinition | undefined {
    return blocks.get(type);
  },

  /** Return all registered block definitions in insertion order. */
  getAll(): BlockDefinition[] {
    return Array.from(blocks.values());
  },

  /**
   * Return block-level PropertySchema for a given block type.
   * Returns [] if the type is not registered (safe fallback).
   */
  getSchema(type: string): PropertySchema<string>[] {
    return blocks.get(type)?.schema ?? [];
  },

  /**
   * Return the default column span for a block type.
   * Falls back to 6 (half-width) when the type is not registered.
   */
  getDefaultColSpan(type: string): number {
    return blocks.get(type)?.defaultColSpan ?? 6;
  },

  /** Return the optional rich preview component for canvas rendering. */
  getPreview(type: string): BlockDefinition['preview'] {
    return blocks.get(type)?.preview;
  },

  /** Return all blocks whose category matches the given string. */
  getByCategory(cat: string): BlockDefinition[] {
    return Array.from(blocks.values()).filter((b) => b.category === cat);
  },
};
