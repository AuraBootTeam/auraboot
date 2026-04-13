/**
 * Field Binding Types
 *
 * Type definitions for field-component binding system.
 *
 * @since 3.2.0
 */

/**
 * Field binding relationship
 */
export interface FieldBinding {
  /** Unique binding ID */
  id: string;
  /** Field path in ViewModel (e.g., 'customer.name') */
  fieldPath: string;
  /** Bound component ID */
  componentId: string;
  /** Component property path (default: 'value') */
  propertyPath: string;
  /** Whether sync is enabled */
  syncEnabled: boolean;
  /** Binding mode */
  mode: BindingMode;
  /** Creation timestamp */
  createdAt: number;
}

/**
 * Binding mode
 */
export type BindingMode = 'one-way' | 'two-way';

/**
 * Binding status
 */
export interface BindingStatus {
  /** Binding ID */
  bindingId: string;
  /** Status type */
  status: 'valid' | 'warning' | 'error' | 'orphan';
  /** Status message */
  message?: string;
  /** Field exists in ViewModel */
  fieldExists: boolean;
  /** Component exists in schema */
  componentExists: boolean;
}

/**
 * Field changes for sync
 */
export interface FieldChanges {
  /** Field label changed */
  label?: string;
  /** Required status changed */
  required?: boolean;
  /** Field type changed */
  type?: string;
  /** Readonly status changed */
  readonly?: boolean;
  /** Visible status changed */
  visible?: boolean;
  /** Disabled status changed */
  disabled?: boolean;
  /** Options changed (for select fields) */
  options?: Array<{ label: string; value: unknown }>;
  /** Validation rules changed */
  validation?: ValidationRule[];
}

/**
 * Validation rule
 */
export interface ValidationRule {
  type: string;
  value?: unknown;
  message?: string;
}

/**
 * Binding validation result
 */
export interface BindingValidationResult {
  binding: FieldBinding;
  status: BindingStatus;
  suggestions?: BindingSuggestion[];
}

/**
 * Binding suggestion for fixing issues
 */
export interface BindingSuggestion {
  type: 'rebind' | 'unbind' | 'fix';
  description: string;
  action: () => void;
}

/**
 * Binding group by field
 */
export interface FieldBindingGroup {
  fieldPath: string;
  fieldLabel?: string;
  fieldType?: string;
  bindings: FieldBinding[];
  status: 'valid' | 'warning' | 'error' | 'unbound';
}

/**
 * Binding group by component
 */
export interface ComponentBindingGroup {
  componentId: string;
  componentType: string;
  componentLabel?: string;
  bindings: FieldBinding[];
  status: 'valid' | 'warning' | 'error';
}

/**
 * Binding change event
 */
export interface BindingChangeEvent {
  type: 'created' | 'updated' | 'deleted' | 'synced';
  binding: FieldBinding;
  timestamp: number;
}

/**
 * Field info from ViewModel
 */
export interface ViewModelFieldInfo {
  path: string;
  label: string;
  type: string;
  required: boolean;
  readonly: boolean;
  computed: boolean;
  computedType?: 'computed_readonly' | 'materialized' | 'transient';
}

/**
 * Binding service options
 */
export interface BindingServiceOptions {
  /** Auto sync changes */
  autoSync?: boolean;
  /** Validate on change */
  validateOnChange?: boolean;
  /** Enable undo/redo for binding operations */
  enableHistory?: boolean;
}
