import type { UnifiedSchema } from '~/meta/schemas/types';
import type { ExpressionContext } from '~/meta/runtime/expression/context';
import type { ScopedStateManager } from '~/meta/runtime/state/scoped-state';
import type { DataSourceManager } from '~/meta/runtime/data-pipeline/DataSourceManager';

interface ActionRegistryLike {
  has(action: string): boolean;
  execute(action: string, context: Record<string, any>): Promise<void>;
}

interface FlowRunnerDeps {
  evaluator: {
    evaluateCondition: (code: string, context: ExpressionContext) => boolean;
  };
  actionRegistry: ActionRegistryLike;
  stateManager: ScopedStateManager;
  scopeId: string;
  dataSourceManager: DataSourceManager;
  schema: UnifiedSchema;
  navigate?: (path: string) => void;
  showToast?: (message: string, level?: 'success' | 'error' | 'info') => void;
  getAllFormFields: () => any[];
}

export class FlowRunner {
  constructor(private readonly deps: FlowRunnerDeps) {}

  async run(steps: any[] | undefined, context: ExpressionContext): Promise<void> {
    if (!steps || steps.length === 0) return;

    let currentIndex = 0;

    while (currentIndex < steps.length) {
      const step = steps[currentIndex];

      if (step?.type === 'if') {
        const condition = this.deps.evaluator.evaluateCondition(step.condition, context);
        const nextId = condition ? step.trueNext : step.falseNext;
        if (nextId) {
          const nextIndex = this.findStepIndex(steps, nextId);
          if (nextIndex === -1) break;
          currentIndex = nextIndex;
          continue;
        }
      }

      if (step?.action) {
        await this.executeAction(step, context);
      }

      if (step?.next) {
        const nextIndex = this.findStepIndex(steps, step.next);
        if (nextIndex === -1) break;
        currentIndex = nextIndex;
        continue;
      }

      currentIndex += 1;
    }
  }

  private findStepIndex(steps: any[], stepId: string): number {
    return steps.findIndex((candidate) => candidate.id === stepId);
  }

  private async executeAction(step: any, context: ExpressionContext): Promise<void> {
    const action = step.action;

    if (!action) {
      console.warn('[SchemaRuntime] executeAction: missing action');
      return;
    }

    if (!this.deps.actionRegistry.has(action)) {
      console.warn(
        `[SchemaRuntime] Unknown action: ${action}, and not registered in ActionRegistry`,
      );
      return;
    }

    // Merge step-level properties (channel, payload, etc.) into args for actions like 'notify'
    const mergedArgs = {
      ...(step.args || {}),
      ...(step.channel !== undefined && { channel: step.channel }),
      ...(step.payload !== undefined && { payload: step.payload }),
    };

    // Resolve {field} template patterns in endpoint using record from context
    // e.g. "/api/dashboards/{pid}/publish" → "/api/dashboards/abc123/publish"
    const record = (context as any).record || (context as any).row || {};
    const resolvedEndpoint = step.endpoint
      ? step.endpoint.replace(/\{(\w+)\}/g, (_: string, key: string) =>
          key in record ? encodeURIComponent(String((record as any)[key] ?? '')) : '',
        )
      : step.endpoint;

    const actionContext = {
      args: mergedArgs,
      navigate: this.deps.navigate,
      showToast: this.deps.showToast,
      stateManager: this.deps.stateManager,
      scopeId: this.deps.scopeId,
      dataSourceManager: this.deps.dataSourceManager,
      fetchResult: context.fetchResult,
      stepEndpoint: resolvedEndpoint,
      stepMethod: step.method,
      stepBody: step.body,
      stepParams: step.params,
      stepTarget: step.target,
      schema: this.deps.schema,
      getAllFormFields: this.deps.getAllFormFields,
      expressionEvaluator: this.deps.evaluator,
      expressionContext: context,
    };

    await this.deps.actionRegistry.execute(action, actionContext);
  }
}
