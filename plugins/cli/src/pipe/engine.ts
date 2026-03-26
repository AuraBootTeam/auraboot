import chalk from 'chalk';
import type { ApiClient } from '../client/api-client.js';
import { queryDynamicList, queryNamedQuery, type FilterItem } from '../client/dynamic-query.js';
import { streamSse } from '../client/sse-client.js';
import { interpolate } from './interpolator.js';
import type {
  WorkflowDefinition,
  WorkflowStep,
  QueryStep,
  AnalyzeStep,
  CreateStep,
  NotifyStep,
  StepResult,
  WorkflowResult,
} from './types.js';

export interface EngineOptions {
  /** Print step progress to stderr */
  verbose?: boolean;
  /** Preview mode — skip create/notify side effects */
  dryRun?: boolean;
}

/**
 * Workflow execution engine.
 * Runs steps sequentially, passing data between them via named variables.
 */
export class WorkflowEngine {
  private client: ApiClient;
  private variables: Record<string, unknown> = {};
  private options: EngineOptions;

  constructor(client: ApiClient, options: EngineOptions = {}) {
    this.client = client;
    this.options = options;
  }

  /**
   * Execute a complete workflow definition.
   */
  async execute(workflow: WorkflowDefinition): Promise<WorkflowResult> {
    const startedAt = new Date().toISOString();
    const startMs = Date.now();

    // Initialize with workflow-level variables
    if (workflow.variables) {
      for (const [key, value] of Object.entries(workflow.variables)) {
        this.variables[key] = value;
      }
    }

    const stepResults: StepResult[] = [];
    let allSuccess = true;

    for (let i = 0; i < workflow.steps.length; i++) {
      const step = workflow.steps[i];
      const stepStart = Date.now();

      if (this.options.verbose) {
        console.error(chalk.dim(`  [${i + 1}/${workflow.steps.length}] ${step.type}...`));
      }

      try {
        const data = await this.executeStep(step, i);
        const result: StepResult = {
          stepIndex: i,
          stepType: step.type,
          output: 'output' in step ? (step as any).output : undefined,
          data,
          durationMs: Date.now() - stepStart,
          success: true,
        };
        stepResults.push(result);

        // Store output in variables
        if ('output' in step && typeof (step as any).output === 'string') {
          this.variables[(step as any).output] = data;
        }
      } catch (err) {
        allSuccess = false;
        stepResults.push({
          stepIndex: i,
          stepType: step.type,
          output: 'output' in step ? (step as any).output : undefined,
          data: null,
          durationMs: Date.now() - stepStart,
          success: false,
          error: (err as Error).message,
        });

        if (this.options.verbose) {
          console.error(chalk.red(`  Step ${i + 1} failed: ${(err as Error).message}`));
        }
        // Abort on failure
        break;
      }
    }

    return {
      name: workflow.name,
      startedAt,
      completedAt: new Date().toISOString(),
      totalDurationMs: Date.now() - startMs,
      steps: stepResults,
      variables: this.variables,
      success: allSuccess,
    };
  }

  /**
   * Execute a single workflow step.
   */
  private async executeStep(step: WorkflowStep, _index: number): Promise<unknown> {
    switch (step.type) {
      case 'query':
        return this.executeQuery(step);
      case 'analyze':
        return this.executeAnalyze(step);
      case 'create':
        return this.executeCreate(step);
      case 'notify':
        return this.executeNotify(step);
      default:
        throw new Error(`Unknown step type: ${(step as any).type}`);
    }
  }

  /**
   * Execute a query step — fetch data from Dynamic CRUD or NamedQuery.
   */
  private async executeQuery(step: QueryStep): Promise<unknown[]> {
    if (step.nq) {
      const params: Record<string, string> = {};
      if (step.limit) params.maxItems = String(step.limit);
      return queryNamedQuery(this.client, step.nq, params);
    }

    const filters: FilterItem[] = (step.filters || []).map(f => {
      const interpolatedValue = interpolate(f.value, this.variables);
      return {
        fieldName: f.field,
        operator: f.operator,
        value: interpolatedValue as string | number | string[],
      };
    });

    return queryDynamicList(this.client, step.source, {
      pageSize: step.limit || 50,
      filters,
      sortField: step.sort?.field,
      sortOrder: step.sort?.order || 'desc',
    });
  }

  /**
   * Execute an analyze step — send data to AuraBot LLM with a prompt.
   */
  private async executeAnalyze(step: AnalyzeStep): Promise<unknown> {
    const inputData = this.variables[step.input];
    if (inputData === undefined) {
      throw new Error(`Variable "${step.input}" not found`);
    }

    const interpolatedPrompt = interpolate(step.prompt, this.variables) as string;

    const dataContext = Array.isArray(inputData)
      ? `\n\nData (${inputData.length} records):\n${JSON.stringify(inputData, null, 2)}`
      : `\n\nData:\n${JSON.stringify(inputData, null, 2)}`;

    const fullPrompt = `${interpolatedPrompt}${dataContext}

Respond with structured JSON containing:
- "summary": brief text summary
- "insights": array of key findings
- "recommendations": array of suggested actions
- "data": processed data if applicable`;

    const token = this.client.getToken()!;
    const baseUrl = this.client.getBaseUrl();

    return new Promise<unknown>((resolve, reject) => {
      let fullContent = '';

      streamSse({
        url: `${baseUrl}/api/ai/aurabot/chat/stream`,
        token,
        body: {
          messages: [{ role: 'user', content: fullPrompt }],
        },
        onContent: (text) => {
          fullContent += text;
        },
        onDone: () => {
          // Extract JSON from response
          const jsonMatch = fullContent.match(/```json\s*([\s\S]*?)```/);
          if (jsonMatch) {
            try {
              resolve(JSON.parse(jsonMatch[1].trim()));
              return;
            } catch { /* fall through */ }
          }
          try {
            resolve(JSON.parse(fullContent));
          } catch {
            resolve({ analysis: step.output, result: fullContent });
          }
        },
        onError: (error) => {
          reject(new Error(`Analysis failed: ${error}`));
        },
      });
    });
  }

  /**
   * Execute a create step — create records via Dynamic CRUD command API.
   */
  private async executeCreate(step: CreateStep): Promise<unknown> {
    const interpolatedData = interpolate(step.data, this.variables) as Record<string, unknown>;

    if (this.options.dryRun || step.dryRun) {
      if (this.options.verbose) {
        console.error(chalk.yellow(`  [dry-run] Would create ${step.model} record`));
      }
      return { dryRun: true, model: step.model, data: interpolatedData };
    }

    const resp = await this.client.post(`/api/dynamic/${step.model}/create`, interpolatedData);
    if (!resp.ok) {
      throw new Error(`Create ${step.model} failed: ${resp.message}`);
    }
    return resp.data;
  }

  /**
   * Execute a notify step — output a formatted message.
   */
  private async executeNotify(step: NotifyStep): Promise<string> {
    const message = interpolate(step.message, this.variables) as string;

    if (step.channel === 'json') {
      const output = JSON.stringify({ type: 'notification', message, timestamp: new Date().toISOString() });
      console.log(output);
      return output;
    }

    // Console output (default)
    if (!this.options.dryRun) {
      console.error(chalk.cyan(`  ${message}`));
    }
    return message;
  }

  /**
   * Get current variable context (for debugging / testing).
   */
  getVariables(): Record<string, unknown> {
    return { ...this.variables };
  }
}
