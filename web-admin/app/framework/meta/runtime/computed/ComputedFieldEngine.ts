/**
 * ComputedFieldEngine - manages real-time evaluation of computed fields.
 * Integrates with ExpressionEvaluator for expression parsing and
 * DependencyGraph for topological ordering.
 *
 * @since 3.7.0
 */

import { DependencyGraph } from './DependencyGraph';
import type {
  ComputedFieldDef,
  ComputedFieldResult,
  ComputedFieldEngineOptions,
  EvaluationContext,
} from './types';

export class ComputedFieldEngine {
  private graph: DependencyGraph;
  private definitions = new Map<string, ComputedFieldDef>();
  private results = new Map<string, ComputedFieldResult>();
  private options: Required<ComputedFieldEngineOptions>;
  private debounceTimers = new Map<string, ReturnType<typeof setTimeout>>();
  private disposed = false;

  constructor(options: ComputedFieldEngineOptions = {}) {
    this.graph = new DependencyGraph();
    this.options = {
      maxDepth: options.maxDepth ?? 10,
      defaultDebounceMs: options.defaultDebounceMs ?? 0,
      onChange: options.onChange ?? (() => {}),
      onError: options.onError ?? (() => {}),
    };
  }

  /**
   * Register computed field definitions.
   * Builds the dependency graph and validates for cycles.
   */
  register(fields: ComputedFieldDef[]): { success: boolean; cycle?: string[] } {
    for (const field of fields) {
      this.definitions.set(field.fieldCode, field);
      this.graph.addField(field.fieldCode, field.dependencies);
    }

    const cycle = this.graph.detectCycle();
    if (cycle) {
      // Remove the cyclic fields
      for (const fieldCode of cycle) {
        this.definitions.delete(fieldCode);
        this.graph.removeField(fieldCode);
      }
      return { success: false, cycle };
    }

    return { success: true };
  }

  /**
   * Unregister a computed field.
   */
  unregister(fieldCode: string): void {
    this.definitions.delete(fieldCode);
    this.graph.removeField(fieldCode);
    this.results.delete(fieldCode);
    this.clearDebounce(fieldCode);
  }

  /**
   * Called when a form field value changes.
   * Triggers re-evaluation of all affected computed fields.
   */
  onFieldChange(changedField: string, context: EvaluationContext): ComputedFieldResult[] {
    if (this.disposed) return [];

    const affected = this.graph.getAffectedFields(changedField);
    if (affected.length === 0) return [];

    const results: ComputedFieldResult[] = [];

    for (const fieldCode of affected) {
      const def = this.definitions.get(fieldCode);
      if (!def) continue;

      const debounceMs = def.debounceMs ?? this.options.defaultDebounceMs;
      if (debounceMs > 0) {
        this.scheduleEvaluation(fieldCode, context, debounceMs);
      } else {
        const result = this.evaluateField(fieldCode, context);
        if (result) results.push(result);
      }
    }

    return results;
  }

  /**
   * Evaluate all computed fields (initial load or full refresh).
   */
  evaluateAll(context: EvaluationContext): ComputedFieldResult[] {
    if (this.disposed) return [];

    const order = this.graph.getEvaluationOrder();
    const results: ComputedFieldResult[] = [];

    for (const fieldCode of order) {
      const result = this.evaluateField(fieldCode, context);
      if (result) {
        results.push(result);
        // Update context with computed value for downstream fields
        context.form[fieldCode] = result.value;
      }
    }

    return results;
  }

  /**
   * Evaluate a single computed field.
   */
  private evaluateField(fieldCode: string, context: EvaluationContext): ComputedFieldResult | null {
    const def = this.definitions.get(fieldCode);
    if (!def) return null;

    const previousResult = this.results.get(fieldCode);
    const previousValue = previousResult?.value;

    try {
      const value = this.evaluateExpression(def.expression, context);
      const result: ComputedFieldResult = {
        fieldCode,
        value,
        stale: false,
        evaluatedAt: Date.now(),
      };

      this.results.set(fieldCode, result);

      if (value !== previousValue) {
        this.options.onChange(fieldCode, value, previousValue);
      }

      return result;
    } catch (err) {
      const error = err instanceof Error ? err : new Error(String(err));
      this.options.onError(fieldCode, error);

      const result: ComputedFieldResult = {
        fieldCode,
        value: def.fallbackValue ?? previousValue,
        error: error.message,
        stale: true,
        evaluatedAt: Date.now(),
      };

      this.results.set(fieldCode, result);
      return result;
    }
  }

  /**
   * Evaluate an expression string against the context.
   * Supports simple field references, arithmetic, and function calls.
   */
  private evaluateExpression(expression: string, context: EvaluationContext): any {
    // Build a safe evaluation scope from context
    const scope: Record<string, any> = { ...context.form };
    if (context.state) Object.assign(scope, context.state);
    if (context.row) scope['row'] = context.row;

    // Add utility functions
    scope['Math'] = Math;
    scope['Number'] = Number;
    scope['String'] = String;
    scope['Boolean'] = Boolean;
    scope['parseInt'] = parseInt;
    scope['parseFloat'] = parseFloat;
    scope['isNaN'] = isNaN;
    scope['json'] = { parse: JSON.parse, stringify: JSON.stringify };
    scope['Date'] = Date;

    // Simple expression evaluator using Function constructor
    // Restricted to the provided scope (no access to global objects)
    const keys = Object.keys(scope);
    const values = Object.values(scope);

    try {
      // Handle template expressions like ${field1 + field2}
      let expr = expression.trim();
      if (expr.startsWith('${') && expr.endsWith('}')) {
        expr = expr.slice(2, -1).trim();
      }

      const fn = new Function(...keys, `"use strict"; return (${expr});`);
      return fn(...values);
    } catch {
      // Fallback: try as a simple field reference
      const fieldRef = expression.replace(/^\$\{|\}$/g, '').trim();
      if (fieldRef in scope) {
        return scope[fieldRef];
      }
      throw new Error(`Cannot evaluate expression: ${expression}`);
    }
  }

  /**
   * Schedule a debounced evaluation.
   */
  private scheduleEvaluation(fieldCode: string, context: EvaluationContext, delayMs: number): void {
    this.clearDebounce(fieldCode);
    const timer = setTimeout(() => {
      this.debounceTimers.delete(fieldCode);
      const result = this.evaluateField(fieldCode, context);
      if (result) {
        context.form[fieldCode] = result.value;
      }
    }, delayMs);
    this.debounceTimers.set(fieldCode, timer);
  }

  private clearDebounce(fieldCode: string): void {
    const timer = this.debounceTimers.get(fieldCode);
    if (timer) {
      clearTimeout(timer);
      this.debounceTimers.delete(fieldCode);
    }
  }

  /**
   * Get current result for a field.
   */
  getResult(fieldCode: string): ComputedFieldResult | undefined {
    return this.results.get(fieldCode);
  }

  /**
   * Get all current results.
   */
  getAllResults(): Map<string, ComputedFieldResult> {
    return new Map(this.results);
  }

  /**
   * Get the dependency graph for visualization/debugging.
   */
  getGraph(): DependencyGraph {
    return this.graph;
  }

  /**
   * Get registered field definitions.
   */
  getDefinitions(): ComputedFieldDef[] {
    return [...this.definitions.values()];
  }

  /**
   * Check if a field is computed.
   */
  isComputed(fieldCode: string): boolean {
    return this.definitions.has(fieldCode);
  }

  /**
   * Dispose the engine and clear all resources.
   */
  dispose(): void {
    this.disposed = true;
    for (const timer of this.debounceTimers.values()) {
      clearTimeout(timer);
    }
    this.debounceTimers.clear();
    this.definitions.clear();
    this.results.clear();
    this.graph.clear();
  }
}
