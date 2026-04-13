/**
 * useComputedFields Hook
 *
 * Manages real-time computed field evaluation within a form.
 * Tracks field dependencies, evaluates expressions on change,
 * and provides computed values back to the form.
 *
 * @since 3.7.0
 *
 * @example
 * ```tsx
 * const { computedValues, isComputed, getResult } = useComputedFields({
 *   fields: [
 *     { fieldCode: 'total', expression: '${price * quantity}', dependencies: ['price', 'quantity'], type: 'computed_readonly' },
 *     { fieldCode: 'fullName', expression: '${firstName + " " + lastName}', dependencies: ['firstName', 'lastName'], type: 'computed_readonly' },
 *   ],
 *   formData,
 *   onChange: (field, value) => setFormData(prev => ({ ...prev, [field]: value })),
 * });
 * ```
 */

import { useState, useEffect, useRef, useCallback, useMemo } from 'react';
import { ComputedFieldEngine } from '~/framework/meta/runtime/computed/ComputedFieldEngine';
import type {
  ComputedFieldDef,
  ComputedFieldResult,
  EvaluationContext,
} from '~/framework/meta/runtime/computed/types';

export interface UseComputedFieldsOptions {
  /** Computed field definitions */
  fields: ComputedFieldDef[];
  /** Current form data */
  formData: Record<string, any>;
  /** Additional state context */
  state?: Record<string, any>;
  /** Callback when a computed value changes - use to update form */
  onChange?: (fieldCode: string, value: any) => void;
  /** Callback on evaluation error */
  onError?: (fieldCode: string, error: Error) => void;
  /** Whether the engine is enabled */
  enabled?: boolean;
}

export interface UseComputedFieldsReturn {
  /** Current computed values */
  computedValues: Record<string, any>;
  /** All evaluation results with metadata */
  results: Map<string, ComputedFieldResult>;
  /** Check if a field is computed */
  isComputed: (fieldCode: string) => boolean;
  /** Get result for a specific field */
  getResult: (fieldCode: string) => ComputedFieldResult | undefined;
  /** Get dependencies of a field */
  getDependencies: (fieldCode: string) => string[];
  /** Get fields that depend on a field */
  getDependents: (fieldCode: string) => string[];
  /** Force re-evaluate all computed fields */
  refreshAll: () => void;
  /** Whether there are any evaluation errors */
  hasErrors: boolean;
  /** Detected circular dependency (if any) */
  cyclicError: string[] | null;
}

export function useComputedFields(options: UseComputedFieldsOptions): UseComputedFieldsReturn {
  const { fields, formData, state, onChange, onError, enabled = true } = options;

  const engineRef = useRef<ComputedFieldEngine | null>(null);
  const [computedValues, setComputedValues] = useState<Record<string, any>>({});
  const [results, setResults] = useState<Map<string, ComputedFieldResult>>(new Map());
  const [cyclicError, setCyclicError] = useState<string[] | null>(null);
  const prevFormDataRef = useRef<Record<string, any>>({});
  const onChangeRef = useRef(onChange);
  const onErrorRef = useRef(onError);
  onChangeRef.current = onChange;
  onErrorRef.current = onError;

  // Initialize/reinitialize engine when field definitions change
  useEffect(() => {
    if (!enabled || fields.length === 0) {
      engineRef.current?.dispose();
      engineRef.current = null;
      setComputedValues({});
      setResults(new Map());
      setCyclicError(null);
      return;
    }

    const engine = new ComputedFieldEngine({
      onChange: (fieldCode, value, _prev) => {
        onChangeRef.current?.(fieldCode, value);
      },
      onError: (fieldCode, error) => {
        onErrorRef.current?.(fieldCode, error);
      },
    });

    const registerResult = engine.register(fields);
    if (!registerResult.success) {
      setCyclicError(registerResult.cycle ?? null);
      console.warn('Circular dependency detected in computed fields:', registerResult.cycle);
    } else {
      setCyclicError(null);
    }

    engineRef.current = engine;

    // Initial evaluation
    const context: EvaluationContext = { form: { ...formData }, state };
    const evalResults = engine.evaluateAll(context);
    applyResults(evalResults);

    return () => {
      engine.dispose();
    };
    // Only re-init when field definitions change (by reference)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [fields, enabled]);

  // React to form data changes
  useEffect(() => {
    const engine = engineRef.current;
    if (!engine || !enabled) return;

    const prevData = prevFormDataRef.current;
    const changedFields: string[] = [];

    // Find which fields changed
    for (const key of Object.keys(formData)) {
      if (formData[key] !== prevData[key]) {
        changedFields.push(key);
      }
    }

    prevFormDataRef.current = { ...formData };

    if (changedFields.length === 0) return;

    // Evaluate affected computed fields
    const context: EvaluationContext = { form: { ...formData }, state };
    const allResults: ComputedFieldResult[] = [];

    for (const changedField of changedFields) {
      // Skip if the changed field is itself a computed field being updated
      if (engine.isComputed(changedField)) continue;
      const fieldResults = engine.onFieldChange(changedField, context);
      allResults.push(...fieldResults);
      // Update context with new computed values for cascading
      for (const r of fieldResults) {
        context.form[r.fieldCode] = r.value;
      }
    }

    if (allResults.length > 0) {
      applyResults(allResults);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [formData, state, enabled]);

  const applyResults = useCallback((evalResults: ComputedFieldResult[]) => {
    if (evalResults.length === 0) return;

    setComputedValues((prev) => {
      const next = { ...prev };
      for (const r of evalResults) {
        next[r.fieldCode] = r.value;
      }
      return next;
    });

    setResults((prev) => {
      const next = new Map(prev);
      for (const r of evalResults) {
        next.set(r.fieldCode, r);
      }
      return next;
    });
  }, []);

  const isComputed = useCallback((fieldCode: string) => {
    return engineRef.current?.isComputed(fieldCode) ?? false;
  }, []);

  const getResult = useCallback((fieldCode: string) => {
    return engineRef.current?.getResult(fieldCode);
  }, []);

  const getDependencies = useCallback((fieldCode: string) => {
    return engineRef.current?.getGraph().getDependencies(fieldCode) ?? [];
  }, []);

  const getDependents = useCallback((fieldCode: string) => {
    return engineRef.current?.getGraph().getDependents(fieldCode) ?? [];
  }, []);

  const refreshAll = useCallback(() => {
    const engine = engineRef.current;
    if (!engine) return;
    const context: EvaluationContext = { form: { ...formData }, state };
    const evalResults = engine.evaluateAll(context);
    applyResults(evalResults);
  }, [formData, state, applyResults]);

  const hasErrors = useMemo(() => {
    for (const r of results.values()) {
      if (r.error) return true;
    }
    return false;
  }, [results]);

  return {
    computedValues,
    results,
    isComputed,
    getResult,
    getDependencies,
    getDependents,
    refreshAll,
    hasErrors,
    cyclicError,
  };
}
