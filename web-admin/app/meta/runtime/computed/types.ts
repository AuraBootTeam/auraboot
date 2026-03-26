/**
 * Types for the computed field engine.
 *
 * @since 3.7.0
 */

export type ComputedFieldType = 'computed_readonly' | 'computed_materialized' | 'computed_temp';

export interface ComputedFieldDef {
  /** Field code (unique identifier) */
  fieldCode: string;
  /** Display label */
  label?: string;
  /** Computation expression (SpEL-like or JS expression) */
  expression: string;
  /** Fields this computation depends on */
  dependencies: string[];
  /** Type of computed field */
  type: ComputedFieldType;
  /** Data type for formatting */
  dataType?: string;
  /** Whether to round-trip (persist computed value) */
  materialize?: boolean;
  /** Debounce evaluation in milliseconds (0 = immediate) */
  debounceMs?: number;
  /** Fallback value when expression errors */
  fallbackValue?: any;
}

export interface ComputedFieldResult {
  fieldCode: string;
  value: any;
  error?: string;
  stale: boolean;
  evaluatedAt: number;
}

export interface ComputedFieldEngineOptions {
  /** Maximum evaluation depth to prevent infinite loops */
  maxDepth?: number;
  /** Default debounce for all computed fields */
  defaultDebounceMs?: number;
  /** Callback when a computed value changes */
  onChange?: (fieldCode: string, value: any, previousValue: any) => void;
  /** Callback when evaluation errors */
  onError?: (fieldCode: string, error: Error) => void;
}

export interface EvaluationContext {
  form: Record<string, any>;
  state?: Record<string, any>;
  row?: Record<string, any>;
  [key: string]: any;
}
