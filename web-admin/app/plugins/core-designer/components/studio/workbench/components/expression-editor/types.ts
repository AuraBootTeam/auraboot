/**
 * Expression Editor Types
 *
 * Type definitions for the expression editor component.
 * Supports unified DSL syntax with {{ }} wrapper.
 */

/**
 * Field metadata for context variables
 */
export interface FieldMeta {
  code: string;
  displayName: string;
  dataType: string;
  description?: string;
  required?: boolean;
}

/**
 * Function metadata for expression functions
 */
export interface FunctionMeta {
  name: string;
  description: string;
  category: 'string' | 'math' | 'logic' | 'array' | 'date' | 'custom';
  signature: string;
  returnType: string;
  parameters: {
    name: string;
    type: string;
    description: string;
    required?: boolean;
  }[];
  example?: string;
}

/**
 * Expression context providing available variables and functions
 */
export interface ExpressionContext {
  /** Form field variables */
  form?: Record<string, FieldMeta>;
  /** Context variables (user, tenant, etc.) */
  context?: Record<string, any>;
  /** Available functions */
  functions?: FunctionMeta[];
  /** Custom variables */
  variables?: Record<string, any>;
}

/**
 * Expression validation result
 */
export interface ExpressionValidationResult {
  valid: boolean;
  errors?: ExpressionError[];
  warnings?: ExpressionWarning[];
  returnType?: string;
  dependencies?: string[];
}

/**
 * Expression error
 */
export interface ExpressionError {
  message: string;
  line?: number;
  column?: number;
  startOffset?: number;
  endOffset?: number;
}

/**
 * Expression warning
 */
export interface ExpressionWarning {
  message: string;
  line?: number;
  column?: number;
}

/**
 * Expression test result
 */
export interface ExpressionTestResult {
  success: boolean;
  result?: any;
  error?: string;
  executionTime?: number;
}

/**
 * Expression editor mode
 */
export type ExpressionEditorMode = 'inline' | 'modal' | 'full';

/**
 * Expression editor props
 */
export interface ExpressionEditorProps {
  /** Current expression value */
  value: string;
  /** Change handler */
  onChange: (value: string) => void;
  /** Expression context with available variables */
  context?: ExpressionContext;
  /** Expected return type for validation */
  returnType?: string;
  /** Editor display mode */
  mode?: ExpressionEditorMode;
  /** Placeholder text */
  placeholder?: string;
  /** Disabled state */
  disabled?: boolean;
  /** Read-only state */
  readOnly?: boolean;
  /** Height in pixels (for full mode) */
  height?: number;
  /** Show line numbers */
  lineNumbers?: boolean;
  /** Show minimap */
  minimap?: boolean;
  /** Auto-wrap expression with {{ }} */
  autoWrap?: boolean;
  /** On blur handler */
  onBlur?: () => void;
  /** On focus handler */
  onFocus?: () => void;
  /** Validation change handler */
  onValidationChange?: (result: ExpressionValidationResult) => void;
}

/**
 * Expression input props (simplified single-line version)
 */
export interface ExpressionInputProps {
  value: string;
  onChange: (value: string) => void;
  context?: ExpressionContext;
  placeholder?: string;
  disabled?: boolean;
  className?: string;
  /** Show expand button to open modal editor */
  expandable?: boolean;
  onExpand?: () => void;
}

/**
 * Variable picker props
 */
export interface VariablePickerProps {
  context: ExpressionContext;
  onSelect: (variable: string) => void;
  searchable?: boolean;
}

/**
 * Expression tester props
 */
export interface ExpressionTesterProps {
  expression: string;
  context?: ExpressionContext;
  onClose?: () => void;
}
