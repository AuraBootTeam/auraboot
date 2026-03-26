/**
 * LinkageEngine - Runtime engine for field linkage rules.
 *
 * Evaluates linkage rules when fields emit events (change / blur / focus),
 * and applies actions (show, hide, enable, disable, setRequired, setValue,
 * setOptions, validate) by updating FieldMeta in ScopedStateManager.
 */

import type {
  LinkageRule,
  LinkageAction,
  TriggerEvent,
} from '~/studio/workbench/panels/linkage/types';
import type { ScopedStateManager, FieldMeta } from '~/meta/runtime/state/scoped-state';
import type { ExpressionContext } from '~/meta/runtime/expression/context';
import { expressionEvaluator } from '~/meta/runtime/expression/evaluator';

/** Maximum cascade depth to prevent infinite loops in chained linkage rules */
const MAX_PROPAGATION_DEPTH = 5;

export interface LinkageEngineOptions {
  stateManager: ScopedStateManager;
  scopeId: string;
  /** Called when a setValue action updates a field value */
  onFieldValueChange?: (fieldCode: string, value: any) => void;
  onError?: (ruleId: string, error: Error) => void;
  /** Maximum cascade depth (default: 5). Set to 1 to disable cascading. */
  maxDepth?: number;
  /** Called to get a fresh ExpressionContext (needed for cascading with updated form values) */
  getContext?: () => ExpressionContext;
}

type RuleIndex = Map<string, LinkageRule[]>; // key = "fieldCode:event"

export class LinkageEngine {
  private rules: LinkageRule[] = [];
  private index: RuleIndex = new Map();
  private stateManager: ScopedStateManager;
  private scopeId: string;
  private onFieldValueChange?: (fieldCode: string, value: any) => void;
  private onError?: (ruleId: string, error: Error) => void;
  private maxDepth: number;
  private getContext?: () => ExpressionContext;
  private currentDepth = 0;

  constructor(options: LinkageEngineOptions) {
    this.stateManager = options.stateManager;
    this.scopeId = options.scopeId;
    this.onFieldValueChange = options.onFieldValueChange;
    this.onError = options.onError;
    this.maxDepth = options.maxDepth ?? MAX_PROPAGATION_DEPTH;
    this.getContext = options.getContext;
  }

  /**
   * Register linkage rules and build the lookup index.
   */
  register(rules: LinkageRule[]): void {
    this.rules = rules;
    this.index.clear();

    for (const rule of rules) {
      if (!rule.enabled) continue;

      const key = this.makeKey(rule.trigger.fieldCode, rule.trigger.event);
      const bucket = this.index.get(key) || [];
      bucket.push(rule);
      this.index.set(key, bucket);
    }
  }

  /**
   * Trigger linkage evaluation for a field event.
   * Supports multi-level cascading: if a setValue action changes a field that
   * has its own linkage rules, those rules are automatically triggered (up to maxDepth).
   */
  onFieldEvent(fieldCode: string, event: TriggerEvent, context: ExpressionContext): void {
    // Depth guard to prevent infinite cascading loops
    if (this.currentDepth >= this.maxDepth) {
      console.warn(
        `[LinkageEngine] Max propagation depth (${this.maxDepth}) reached for ${fieldCode}:${event}. ` +
          `Possible circular dependency in linkage rules.`,
      );
      return;
    }

    const key = this.makeKey(fieldCode, event);
    const matchedRules = this.index.get(key);
    if (!matchedRules || matchedRules.length === 0) return;

    // Collect fields changed by setValue actions for cascading
    const changedFields: string[] = [];

    for (const rule of matchedRules) {
      try {
        // Check condition if present
        if (rule.trigger.condition) {
          const conditionMet = expressionEvaluator.evaluateCondition(
            rule.trigger.condition,
            context,
          );
          if (!conditionMet) continue;
        }

        // Execute all actions in the rule
        const changed = this.executeActions(rule.actions, context, rule.id);
        changedFields.push(...changed);
      } catch (error) {
        console.error(`[LinkageEngine] Error executing rule ${rule.id}:`, error);
        this.onError?.(rule.id, error as Error);
      }
    }

    // Cascade: trigger linkage for fields modified by setValue actions
    if (changedFields.length > 0 && this.currentDepth < this.maxDepth) {
      this.currentDepth++;
      try {
        // Get fresh context reflecting the updated form values
        const freshContext = this.getContext?.() ?? context;
        for (const changedField of changedFields) {
          this.onFieldEvent(changedField, 'change', freshContext);
        }
      } finally {
        this.currentDepth--;
      }
    }
  }

  /**
   * Execute a list of actions.
   * Returns the list of field codes changed by setValue actions (for cascading).
   */
  private executeActions(
    actions: LinkageAction[],
    context: ExpressionContext,
    ruleId: string,
  ): string[] {
    const metaUpdates: Record<string, Partial<FieldMeta>> = {};
    const changedFields: string[] = [];

    for (const action of actions) {
      try {
        switch (action.type) {
          case 'show':
            for (const target of action.targets) {
              metaUpdates[target] = { ...(metaUpdates[target] || {}), hidden: false };
            }
            break;

          case 'hide':
            for (const target of action.targets) {
              metaUpdates[target] = { ...(metaUpdates[target] || {}), hidden: true };
            }
            break;

          case 'enable':
            for (const target of action.targets) {
              metaUpdates[target] = { ...(metaUpdates[target] || {}), disabled: false };
            }
            break;

          case 'disable':
            for (const target of action.targets) {
              metaUpdates[target] = { ...(metaUpdates[target] || {}), disabled: true };
            }
            break;

          case 'setRequired':
            for (const target of action.targets) {
              metaUpdates[target] = { ...(metaUpdates[target] || {}), required: action.required };
            }
            break;

          case 'setValue': {
            // Evaluate the value expression; fall back to raw string if result is undefined
            let resolvedValue: any;
            try {
              resolvedValue = expressionEvaluator.evaluate(action.value, context);
            } catch {
              // expression parse error — use raw value
            }
            if (resolvedValue === undefined) {
              resolvedValue = action.value;
            }
            this.onFieldValueChange?.(action.target, resolvedValue);
            changedFields.push(action.target);
            break;
          }

          case 'setOptions': {
            // For static dict options, build options array from dataSource config.
            // Dynamic API-driven options are deferred to the component's own data fetching.
            const ds = action.dataSource;
            if (ds.type === 'dict' && ds.dictCode) {
              // Mark the target to reload options from dict — the component will handle fetching
              metaUpdates[action.target] = {
                ...(metaUpdates[action.target] || {}),
                options: undefined, // clear local options so component refetches
              };
            }
            break;
          }

          case 'validate':
            for (const target of action.targets) {
              metaUpdates[target] = {
                ...(metaUpdates[target] || {}),
                validation: action.rules.map((r) => ({
                  type: r.type,
                  value: r.value,
                  message: r.message,
                })),
              };
            }
            break;
        }
      } catch (error) {
        console.error(
          `[LinkageEngine] Error executing action ${action.type} in rule ${ruleId}:`,
          error,
        );
        this.onError?.(ruleId, error as Error);
      }
    }

    // Batch-apply all fieldMeta updates
    if (Object.keys(metaUpdates).length > 0) {
      this.stateManager.batchUpdateFieldMeta(this.scopeId, metaUpdates);
    }

    return changedFields;
  }

  /**
   * Get all registered rules.
   */
  getRules(): LinkageRule[] {
    return this.rules;
  }

  /**
   * Dispose the engine and clear all state.
   */
  dispose(): void {
    this.rules = [];
    this.index.clear();
  }

  private makeKey(fieldCode: string, event: TriggerEvent): string {
    return `${fieldCode}:${event}`;
  }
}
