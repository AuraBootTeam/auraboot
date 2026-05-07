/**
 * Shared Designer Types
 *
 * Unified property schema, validation, and registry types
 * shared across Dashboard Designer, Flow Designer SDK, and BPMN Designer.
 */

import type { I18nText } from '~/ui/field-adapter';

// ==================== Property Schema ====================

/**
 * Common property types shared across all designers.
 * Each designer may support a subset of these types.
 */
export type PropertyType =
  | 'text'
  | 'textarea'
  | 'number'
  | 'select'
  | 'multiselect'
  | 'boolean'
  | 'json'
  | 'expression'
  | 'model'
  | 'model-select'
  | 'field-select'
  | 'namedQuery'
  | 'formula'
  | 'page-select'
  | 'dashboard-select'
  | 'process-select'
  | 'automation-select'
  | 'command-select'
  | 'localizedText'
  | 'icon'
  | 'array'
  | 'dict-select';

/**
 * Property schema definition for designer configuration panels.
 * Labels can be plain strings or I18n-aware text.
 */
export interface PropertySchema<TLabel = string | I18nText> {
  key: string;
  label: TLabel;
  type: PropertyType;
  required?: boolean;
  options?: { label: TLabel; value: string }[];
  placeholder?: TLabel;
  description?: TLabel;
  defaultValue?: unknown;
  dependsOn?: { field: string; value?: unknown };
  /** Group name for panel sectioning. TLabel for i18n parity. */
  group?: TLabel;

  /** type='array' only: schema for each item's fields. */
  itemSchema?: PropertySchema<TLabel>[];
  /** type='array' only: collapsed-header label. User-data interpolation; not translated. */
  itemLabel?: (item: any, index: number) => string;
  /** type='array' only: button label. Defaults to '+ Add'. */
  addButtonLabel?: TLabel;

  /** type='dict-select' only: optional whitelist of dict codes. */
  dictCodeFilter?: string[];
}

// ==================== Validation ====================

/**
 * Validation result from a designer validation pass.
 */
export interface ValidationResult {
  valid: boolean;
  errors: ValidationError[];
}

/**
 * Single validation error or warning.
 */
export interface ValidationError {
  /** ID of the element (widget, node, etc.) */
  elementId?: string;
  /** Field path with dot notation */
  field?: string;
  /** Error message */
  message: string;
  /** Severity */
  type: 'error' | 'warning';
}

// ==================== Registry ====================

/**
 * Base definition interface for registry items.
 * Designers extend this with their specific fields.
 */
export interface BaseDefinition<TLabel = string | I18nText> {
  type: string;
  label: TLabel;
  icon: string | React.ReactNode;
  category: string;
  description?: TLabel;
  configSchema?: PropertySchema<TLabel>[];
}

/**
 * Generic registry for designer element definitions.
 * Replaces duplicate WidgetRegistry / NodeRegistry implementations.
 *
 * Usage:
 *   const registry = createRegistry<WidgetDefinition>();
 *   registry.register(myWidget);
 *   registry.get('smart-bar-chart');
 */
export class DesignerRegistry<T extends BaseDefinition<any>> {
  private definitions = new Map<string, T>();

  register(definition: T): void {
    this.definitions.set(definition.type, definition);
  }

  registerAll(definitions: T[]): void {
    definitions.forEach((def) => this.register(def));
  }

  get(type: string): T | undefined {
    return this.definitions.get(type);
  }

  getAll(): T[] {
    return Array.from(this.definitions.values());
  }

  getByCategory(): Record<string, T[]> {
    const grouped: Record<string, T[]> = {};
    this.definitions.forEach((def) => {
      if (!grouped[def.category]) {
        grouped[def.category] = [];
      }
      grouped[def.category].push(def);
    });
    return grouped;
  }

  getCategories(): string[] {
    const categories = new Set<string>();
    this.definitions.forEach((def) => categories.add(def.category));
    return Array.from(categories);
  }

  has(type: string): boolean {
    return this.definitions.has(type);
  }

  clear(): void {
    this.definitions.clear();
  }
}

/**
 * Factory function to create a typed registry instance.
 */
export function createRegistry<T extends BaseDefinition<any>>(): DesignerRegistry<T> {
  return new DesignerRegistry<T>();
}
