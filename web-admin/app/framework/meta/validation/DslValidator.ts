/**
 * DslValidator — Unified DSL validation entry point
 *
 * Combines:
 * - Zod structural validation (schemas/)
 * - Semantic validation (rules/)
 * - Best practice linting (lint/)
 *
 * Usage:
 *   import { validateAll } from '~/framework/meta/validation/DslValidator';
 *   const result = validateAll(schema);
 *   if (!result.valid) { /* handle errors * / }
 */

import type { UnifiedSchema } from '~/framework/meta/schemas/types';
import type { DslValidationResult, ValidationMessage } from './types';
import { unifiedSchemaSchema } from './schemas';
import { validateReferences } from './rules/reference-validator';
import { validateExpressions } from './rules/expression-validator';
import { validateComponents } from './rules/component-validator';
import { lintI18n } from './lint/i18n-linter';
import { lintPerformance } from './lint/performance-linter';
import { lintBestPractice } from './lint/best-practice-linter';

export interface ValidateOptions {
  /** Run structural validation (Zod schemas). Default: true */
  structure?: boolean;
  /** Run semantic validation (references, expressions). Default: true */
  semantic?: boolean;
  /** Run lint rules (i18n, performance, best practices). Default: true */
  lint?: boolean;
}

function buildResult(messages: ValidationMessage[]): DslValidationResult {
  const errors = messages.filter((m) => m.severity === 'error').length;
  const warnings = messages.filter((m) => m.severity === 'warning').length;
  const infos = messages.filter((m) => m.severity === 'info').length;

  return {
    valid: errors === 0,
    messages,
    summary: { errors, warnings, infos },
  };
}

/**
 * Validate DSL schema structure using Zod
 */
export function validateStructure(schema: unknown): ValidationMessage[] {
  const result = unifiedSchemaSchema.safeParse(schema);
  if (result.success) return [];

  return result.error.issues.map((issue) => ({
    code: 'structure',
    path: issue.path.join('.'),
    message: issue.message,
    severity: 'error' as const,
  }));
}

/**
 * Validate semantic rules (requires valid structure)
 */
export function validateSemantic(schema: UnifiedSchema): ValidationMessage[] {
  return [
    ...validateReferences(schema),
    ...validateExpressions(schema),
    ...validateComponents(schema),
  ];
}

/**
 * Run lint rules
 */
export function validateLint(schema: UnifiedSchema): ValidationMessage[] {
  return [...lintI18n(schema), ...lintPerformance(schema), ...lintBestPractice(schema)];
}

/**
 * Run all validations
 */
export function validateAll(schema: unknown, options: ValidateOptions = {}): DslValidationResult {
  const { structure = true, semantic = true, lint = true } = options;
  const messages: ValidationMessage[] = [];

  // Step 1: Structural validation
  if (structure) {
    const structureErrors = validateStructure(schema);
    messages.push(...structureErrors);

    // If structure is invalid, skip semantic + lint (they need valid types)
    if (structureErrors.length > 0) {
      return buildResult(messages);
    }
  }

  const validSchema = schema as UnifiedSchema;

  // Step 2: Semantic validation
  if (semantic) {
    messages.push(...validateSemantic(validSchema));
  }

  // Step 3: Lint rules
  if (lint) {
    messages.push(...validateLint(validSchema));
  }

  return buildResult(messages);
}

/** Convenience: quick check if schema is structurally valid */
export function isValidSchema(schema: unknown): boolean {
  return validateStructure(schema).length === 0;
}
