/**
 * DSL Validation Types
 */

export type ValidationSeverity = 'error' | 'warning' | 'info';

export interface ValidationMessage {
  /** Rule code, e.g. "ref_datasource", "i18n_hardcoded" */
  code: string;
  /** JSON path to the issue location */
  path: string;
  /** Human-readable description */
  message: string;
  /** Severity level */
  severity: ValidationSeverity;
  /** Optional fix suggestion */
  suggestion?: string;
}

export interface DslValidationResult {
  /** True if no errors (warnings allowed) */
  valid: boolean;
  /** All messages grouped by severity */
  messages: ValidationMessage[];
  /** Counts by severity */
  summary: {
    errors: number;
    warnings: number;
    infos: number;
  };
}
