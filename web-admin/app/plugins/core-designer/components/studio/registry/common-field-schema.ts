/**
 * Common Field Schema
 *
 * Properties shared across ALL widget types: field code, label, component picker,
 * required, readOnly, layout (colSpan), and visibility/enable conditions.
 *
 * `buildFieldSchema(component)` merges these common entries with the
 * widget-specific entries from WidgetRegistry, and populates the component
 * option list dynamically from all registered widgets.
 *
 * @since 4.3.0
 */

import type { PropertySchema } from '~/shared/designer/types';
import { WidgetRegistry } from './widget-registry';

/**
 * Base common schema entries.
 * The `component` field has an empty options array — populated by `buildFieldSchema`.
 */
export const COMMON_FIELD_SCHEMA: PropertySchema<string>[] = [
  { key: 'field', label: 'Field Code', type: 'text', group: 'Basic', required: true },
  { key: 'label', label: 'Label', type: 'text', group: 'Basic' },
  {
    key: 'component',
    label: 'Component',
    type: 'select',
    group: 'Basic',
    // Options populated dynamically from WidgetRegistry in buildFieldSchema()
    options: [],
  },
  { key: 'required', label: 'Required', type: 'boolean', group: 'Basic' },
  { key: 'readOnly', label: 'Read Only', type: 'boolean', group: 'Basic' },
  {
    key: 'colSpan',
    label: 'Column Span',
    type: 'number',
    group: 'Layout',
    description: 'Number of grid columns (1-12)',
  },
  { key: 'visibleWhen', label: 'Visible When', type: 'expression', group: 'Conditions' },
  { key: 'enableWhen', label: 'Enable When', type: 'expression', group: 'Conditions' },
  { key: 'readOnlyWhen', label: 'Read Only When', type: 'expression', group: 'Conditions' },
];

/**
 * Build the complete PropertySchema for a field config panel.
 *
 * Returns:
 *   1. Common schema entries (with `component` options populated from registered widgets)
 *   2. Widget-specific schema entries for the selected component
 *
 * This replaces the monolithic FIELD_CONFIG_SCHEMA that hardcoded all component
 * types and their dependsOn entries in a single flat array.
 */
export function buildFieldSchema(component: string): PropertySchema<string>[] {
  const allWidgets = WidgetRegistry.getAll();

  // Build the dynamic component options from the live registry
  const componentOptions = allWidgets.map((w) => ({
    label: w.name,
    value: w.component,
  }));

  // Patch the `component` field with real options; leave all other entries as-is
  const commonWithOptions = COMMON_FIELD_SCHEMA.map((s) =>
    s.key === 'component' ? { ...s, options: componentOptions } : s,
  );

  // Append widget-specific schema entries (may be empty for unknown components)
  const widgetSchema = WidgetRegistry.getSchema(component);

  return [...commonWithOptions, ...widgetSchema];
}
