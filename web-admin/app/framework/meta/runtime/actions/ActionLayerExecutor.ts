/**
 * ActionLayerExecutor - executes actions in layered phases:
 *   PRE (guards/validation) → MAIN (primary action) → POST (side effects)
 *
 * If any PRE action returns { abort: true }, the MAIN and POST phases are skipped.
 * POST actions always run after MAIN (fire-and-forget).
 *
 * @since 3.7.0
 */

export type ActionPhase = 'pre' | 'main' | 'post';

export interface LayeredAction {
  id: string;
  phase: ActionPhase;
  actionName: string;
  args?: Record<string, any>;
  /** Condition expression - skip if evaluates to false */
  condition?: string;
  /** Order within the same phase (lower = first) */
  order?: number;
  /** If true, this action is optional and errors won't abort */
  optional?: boolean;
}

export interface LayeredActionConfig {
  /** Actions grouped by phase */
  actions: LayeredAction[];
  /** Action context passed to all actions */
  context?: Record<string, any>;
  /** Whether to run POST actions even if MAIN fails */
  runPostOnMainFailure?: boolean;
}

export interface ActionExecuteResult {
  success: boolean;
  phase: ActionPhase;
  abortedBy?: string;
  mainResult?: any;
  errors: Array<{ actionId: string; phase: ActionPhase; error: Error }>;
}

type ActionExecutor = (actionName: string, args?: Record<string, any>) => Promise<any>;
type ConditionEvaluator = (condition: string) => boolean;

export class ActionLayerExecutor {
  private executor: ActionExecutor;
  private conditionEvaluator?: ConditionEvaluator;

  constructor(executor: ActionExecutor, conditionEvaluator?: ConditionEvaluator) {
    this.executor = executor;
    this.conditionEvaluator = conditionEvaluator;
  }

  /**
   * Execute a layered action configuration.
   */
  async execute(config: LayeredActionConfig): Promise<ActionExecuteResult> {
    const result: ActionExecuteResult = {
      success: true,
      phase: 'pre',
      errors: [],
    };

    const sortedActions = [...config.actions].sort((a, b) => (a.order ?? 0) - (b.order ?? 0));
    const preActions = sortedActions.filter((a) => a.phase === 'pre');
    const mainActions = sortedActions.filter((a) => a.phase === 'main');
    const postActions = sortedActions.filter((a) => a.phase === 'post');

    const mergedArgs = config.context ?? {};

    // Phase 1: PRE (guards/validation)
    for (const action of preActions) {
      if (!this.shouldRun(action)) continue;

      try {
        const preResult = await this.executor(action.actionName, { ...mergedArgs, ...action.args });
        // If PRE action returns abort signal, stop execution
        if (preResult && typeof preResult === 'object' && preResult.abort) {
          result.success = false;
          result.abortedBy = action.id;
          return result;
        }
      } catch (err) {
        const error = err instanceof Error ? err : new Error(String(err));
        result.errors.push({ actionId: action.id, phase: 'pre', error });
        if (!action.optional) {
          result.success = false;
          result.abortedBy = action.id;
          return result;
        }
      }
    }

    // Phase 2: MAIN (primary action)
    result.phase = 'main';
    let mainFailed = false;

    for (const action of mainActions) {
      if (!this.shouldRun(action)) continue;

      try {
        result.mainResult = await this.executor(action.actionName, {
          ...mergedArgs,
          ...action.args,
        });
      } catch (err) {
        const error = err instanceof Error ? err : new Error(String(err));
        result.errors.push({ actionId: action.id, phase: 'main', error });
        if (!action.optional) {
          result.success = false;
          mainFailed = true;
          break;
        }
      }
    }

    // Phase 3: POST (side effects)
    if (!mainFailed || config.runPostOnMainFailure) {
      result.phase = 'post';
      for (const action of postActions) {
        if (!this.shouldRun(action)) continue;

        try {
          await this.executor(action.actionName, {
            ...mergedArgs,
            ...action.args,
            _mainResult: result.mainResult,
          });
        } catch (err) {
          const error = err instanceof Error ? err : new Error(String(err));
          result.errors.push({ actionId: action.id, phase: 'post', error });
          // POST errors don't affect overall success
        }
      }
    }

    return result;
  }

  private shouldRun(action: LayeredAction): boolean {
    if (!action.condition) return true;
    if (!this.conditionEvaluator) return true;
    try {
      return this.conditionEvaluator(action.condition);
    } catch {
      return false;
    }
  }
}

/**
 * Helper to create a layered action config from a simple definition.
 */
export function createLayeredConfig(
  mainAction: string,
  options: {
    preActions?: Array<{ action: string; args?: Record<string, any>; condition?: string }>;
    postActions?: Array<{ action: string; args?: Record<string, any>; condition?: string }>;
    context?: Record<string, any>;
  } = {},
): LayeredActionConfig {
  const actions: LayeredAction[] = [];
  let order = 0;

  for (const pre of options.preActions ?? []) {
    actions.push({
      id: `pre_${order}`,
      phase: 'pre',
      actionName: pre.action,
      args: pre.args,
      condition: pre.condition,
      order: order++,
    });
  }

  actions.push({
    id: 'main',
    phase: 'main',
    actionName: mainAction,
    order: 0,
  });

  order = 0;
  for (const post of options.postActions ?? []) {
    actions.push({
      id: `post_${order}`,
      phase: 'post',
      actionName: post.action,
      args: post.args,
      condition: post.condition,
      order: order++,
    });
  }

  return { actions, context: options.context };
}
