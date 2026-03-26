/**
 * useRealtimeValidation — Real-time DSL schema validation hook for the Page Designer.
 *
 * Provides debounced validation that runs as the user edits DSL schemas,
 * with configurable severity filtering and path-based error grouping.
 *
 * Usage:
 *   const { result, errorsByPath, isValidating } = useRealtimeValidation(schema, {
 *     debounceMs: 500,
 *     layers: { structure: true, semantic: true, lint: true },
 *   });
 */

import { useState, useEffect, useRef, useCallback, useMemo } from 'react';
import type { UnifiedSchema } from '~/meta/schemas/types';
import type { DslValidationResult, ValidationMessage } from './types';
import { validateAll, type ValidateOptions } from './DslValidator';

export interface RealtimeValidationOptions {
  /** Debounce interval in ms. Default: 600 */
  debounceMs?: number;
  /** Which validation layers to run. Default: all enabled */
  layers?: ValidateOptions;
  /** Minimum severity to include. Default: 'info' (show all) */
  minSeverity?: 'error' | 'warning' | 'info';
  /** Whether validation is enabled. Default: true */
  enabled?: boolean;
}

export interface RealtimeValidationResult {
  /** Latest validation result */
  result: DslValidationResult | null;
  /** Whether validation is currently running */
  isValidating: boolean;
  /** Errors grouped by JSON path for easy per-field display */
  errorsByPath: Map<string, ValidationMessage[]>;
  /** Errors grouped by block ID for per-block indicators */
  errorsByBlock: Map<string, ValidationMessage[]>;
  /** Force re-validate immediately (bypasses debounce) */
  revalidate: () => void;
  /** Clear all validation results */
  clear: () => void;
}

const EMPTY_RESULT: DslValidationResult = {
  valid: true,
  messages: [],
  summary: { errors: 0, warnings: 0, infos: 0 },
};

/**
 * Extract block ID from a validation message path.
 * Paths like "areas.main.blocks[0].fields[1].component" → block index "0"
 */
function extractBlockId(path: string): string | null {
  const match = path.match(/blocks\[(\d+)\]/);
  return match ? match[1] : null;
}

/**
 * Group validation messages by their JSON path prefix (first 2 segments).
 */
function groupByPath(messages: ValidationMessage[]): Map<string, ValidationMessage[]> {
  const map = new Map<string, ValidationMessage[]>();
  for (const msg of messages) {
    const key = msg.path || '_root';
    const existing = map.get(key) || [];
    existing.push(msg);
    map.set(key, existing);
  }
  return map;
}

/**
 * Group validation messages by block index.
 */
function groupByBlock(messages: ValidationMessage[]): Map<string, ValidationMessage[]> {
  const map = new Map<string, ValidationMessage[]>();
  for (const msg of messages) {
    const blockId = extractBlockId(msg.path);
    if (blockId !== null) {
      const existing = map.get(blockId) || [];
      existing.push(msg);
      map.set(blockId, existing);
    }
  }
  return map;
}

const SEVERITY_ORDER: Record<string, number> = { error: 0, warning: 1, info: 2 };

function filterBySeverity(messages: ValidationMessage[], minSeverity: string): ValidationMessage[] {
  const threshold = SEVERITY_ORDER[minSeverity] ?? 2;
  return messages.filter((m) => (SEVERITY_ORDER[m.severity] ?? 2) <= threshold);
}

export function useRealtimeValidation(
  schema: UnifiedSchema | null | undefined,
  options: RealtimeValidationOptions = {},
): RealtimeValidationResult {
  const {
    debounceMs = 600,
    layers = { structure: true, semantic: true, lint: true },
    minSeverity = 'info',
    enabled = true,
  } = options;

  const [result, setResult] = useState<DslValidationResult | null>(null);
  const [isValidating, setIsValidating] = useState(false);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const schemaRef = useRef(schema);
  schemaRef.current = schema;

  // Run validation (can be called directly or via debounce)
  const runValidation = useCallback(() => {
    const currentSchema = schemaRef.current;
    if (!currentSchema || !enabled) {
      setResult(EMPTY_RESULT);
      setIsValidating(false);
      return;
    }

    setIsValidating(true);

    // Run synchronously — validation is CPU-only (no I/O)
    try {
      const validationResult = validateAll(currentSchema, layers);

      // Filter by minimum severity
      const filtered = filterBySeverity(validationResult.messages, minSeverity);
      const errors = filtered.filter((m) => m.severity === 'error').length;
      const warnings = filtered.filter((m) => m.severity === 'warning').length;
      const infos = filtered.filter((m) => m.severity === 'info').length;

      setResult({
        valid: errors === 0,
        messages: filtered,
        summary: { errors, warnings, infos },
      });
    } catch (err) {
      console.error('[useRealtimeValidation] Validation failed:', err);
      setResult(EMPTY_RESULT);
    } finally {
      setIsValidating(false);
    }
  }, [enabled, layers, minSeverity]);

  // Debounced validation on schema change
  useEffect(() => {
    if (!enabled || !schema) {
      setResult(null);
      return;
    }

    if (timerRef.current) {
      clearTimeout(timerRef.current);
    }

    timerRef.current = setTimeout(runValidation, debounceMs);

    return () => {
      if (timerRef.current) {
        clearTimeout(timerRef.current);
      }
    };
  }, [schema, debounceMs, enabled, runValidation]);

  // Force re-validate (bypasses debounce)
  const revalidate = useCallback(() => {
    if (timerRef.current) {
      clearTimeout(timerRef.current);
    }
    runValidation();
  }, [runValidation]);

  // Clear results
  const clear = useCallback(() => {
    if (timerRef.current) {
      clearTimeout(timerRef.current);
    }
    setResult(null);
    setIsValidating(false);
  }, []);

  // Derived: group messages by path and block
  const errorsByPath = useMemo(() => groupByPath(result?.messages || []), [result?.messages]);

  const errorsByBlock = useMemo(() => groupByBlock(result?.messages || []), [result?.messages]);

  return {
    result,
    isValidating,
    errorsByPath,
    errorsByBlock,
    revalidate,
    clear,
  };
}
