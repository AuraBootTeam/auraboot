/**
 * ViewModel domain types.
 *
 * @since 3.2.0
 */

/**
 * ViewModel resolution mode.
 */
export type ViewModelMode = 'inherit' | 'compose' | 'free';

/**
 * ViewModel configuration stored in Model.extension.viewModel.
 */
export interface ViewModelConfig {
  mode: ViewModelMode;
  baseEntityCode?: string;
  excludeFields?: string[];
  namedQueryCode?: string;
  computedFields?: Record<string, ComputedFieldOverride>;
}

/**
 * Layer 3 computed field override definition.
 */
export interface ComputedFieldOverride {
  expression?: string;
  returnType?: string;
  label?: string;
  virtual?: boolean;
  description?: string;
  uiHint?: Record<string, any>;
}

/**
 * Source type indicating where a resolved field originates.
 */
export type ResolvedFieldSourceType = 'field_binding' | 'named_query_field' | 'computed_only';

/**
 * Unified resolved field representation after three-layer merge.
 */
export interface ResolvedField {
  code: string;
  displayName?: string;
  dataType?: string;
  description?: string;

  required?: boolean;
  visible?: boolean;
  editable?: boolean;
  aliasCode?: string;
  fieldOrder?: number;

  computeExpression?: string;
  returnType?: string;
  virtual?: boolean;

  uiHint?: Record<string, any>;
  sourceType?: ResolvedFieldSourceType;
}

/**
 * ViewModel summary for listing.
 */
export interface ViewModelSummary {
  code: string;
  displayName?: string;
  description?: string;
  mode: ViewModelMode;
  baseEntityCode?: string;
  namedQueryCode?: string;
  fieldCount: number;
  status?: string;
}

/**
 * ViewModel validation result.
 */
export interface ViewModelValidationResult {
  valid: boolean;
  errors: string[];
  warnings: string[];
}
