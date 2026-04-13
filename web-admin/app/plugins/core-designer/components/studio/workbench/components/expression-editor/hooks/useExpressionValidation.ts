/**
 * Expression Validation Hook
 *
 * Provides real-time expression validation with debouncing.
 *
 * @since 3.2.0
 */

import { useState, useEffect, useCallback, useRef } from 'react';
import { expressionParser } from '~/plugins/core-designer/components/studio/services/runtime/expression/expression-parser';
import type { ExpressionValidationResult, ExpressionContext } from '../types';

interface UseExpressionValidationOptions {
  /** Debounce delay in ms */
  debounceMs?: number;
  /** Context for validation */
  context?: ExpressionContext;
  /** Expected return type */
  returnType?: string;
}

interface UseExpressionValidationResult {
  validation: ExpressionValidationResult;
  isValidating: boolean;
  validate: (expression: string) => void;
  validateSync: (expression: string) => ExpressionValidationResult;
}

/**
 * Hook for expression validation with debouncing
 */
export function useExpressionValidation(
  options: UseExpressionValidationOptions = {},
): UseExpressionValidationResult {
  const { debounceMs = 300, context } = options;

  const [validation, setValidation] = useState<ExpressionValidationResult>({ valid: true });
  const [isValidating, setIsValidating] = useState(false);
  const timeoutRef = useRef<NodeJS.Timeout | null>(null);

  // Synchronous validation
  const validateSync = useCallback(
    (expression: string): ExpressionValidationResult => {
      if (!expression || !expression.trim()) {
        return { valid: true };
      }

      // Validate syntax
      const syntaxResult = expressionParser.validate(expression);
      if (!syntaxResult.valid) {
        return {
          valid: false,
          errors: [{ message: syntaxResult.error || 'Invalid syntax' }],
        };
      }

      // Extract dependencies
      const dependencies = expressionParser.extractVariables(expression);

      // Check if dependencies exist in context
      const warnings: { message: string }[] = [];
      const formFields = context?.form;
      if (formFields) {
        dependencies.forEach((dep) => {
          // Check direct field access (e.g., "form" in "form.name")
          if (dep === 'form') return;
          // Check if it's a known context variable
          if (dep === 'context') return;
          // Check if field exists
          const fieldPath = dep.startsWith('form.') ? dep.slice(5) : dep;
          if (fieldPath && !formFields[fieldPath] && !['form', 'context'].includes(dep)) {
            warnings.push({ message: `Variable "${dep}" may not be defined` });
          }
        });
      }

      return {
        valid: true,
        warnings: warnings.length > 0 ? warnings : undefined,
        dependencies,
      };
    },
    [context],
  );

  // Debounced validation
  const validate = useCallback(
    (expression: string) => {
      // Clear previous timeout
      if (timeoutRef.current) {
        clearTimeout(timeoutRef.current);
      }

      setIsValidating(true);

      // Set new timeout
      timeoutRef.current = setTimeout(() => {
        const result = validateSync(expression);
        setValidation(result);
        setIsValidating(false);
      }, debounceMs);
    },
    [validateSync, debounceMs],
  );

  // Cleanup on unmount
  useEffect(() => {
    return () => {
      if (timeoutRef.current) {
        clearTimeout(timeoutRef.current);
      }
    };
  }, []);

  return {
    validation,
    isValidating,
    validate,
    validateSync,
  };
}

export default useExpressionValidation;
