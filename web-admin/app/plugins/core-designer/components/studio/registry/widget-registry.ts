/**
 * Widget Registry
 *
 * Singleton registry for all form-field widget definitions.
 * Consumers call WidgetRegistry.getAll() instead of importing a hardcoded array.
 *
 * @since 4.3.0
 */

import type { WidgetDefinition } from './types';
import type { PropertySchema } from '~/shared/designer/types';

const widgets = new Map<string, WidgetDefinition>();

export const WidgetRegistry = {
  /**
   * Register a widget definition.
   * Subsequent calls with the same component key overwrite the previous entry.
   */
  register(def: WidgetDefinition): void {
    widgets.set(def.component, def);
  },

  /** Look up a widget definition by its component key. */
  get(component: string): WidgetDefinition | undefined {
    return widgets.get(component);
  },

  /** Return all registered widget definitions in insertion order. */
  getAll(): WidgetDefinition[] {
    return Array.from(widgets.values());
  },

  /**
   * Return widget-specific PropertySchema entries for a given component.
   * Returns [] if the component is not registered (safe fallback).
   */
  getSchema(component: string): PropertySchema<string>[] {
    return widgets.get(component)?.schema ?? [];
  },

  /** Return the human-readable name for a component, or the key itself. */
  getName(component: string): string {
    return widgets.get(component)?.name ?? component;
  },

  /** Return the icon character for a component, or a generic placeholder. */
  getIcon(component: string): string {
    return widgets.get(component)?.icon ?? '◻';
  },
};
