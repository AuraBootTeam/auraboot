/**
 * Workflow definition types for `aura pipe` command.
 *
 * A workflow is a sequence of steps that compose query, analyze, create, and notify
 * operations into reusable named pipelines.
 */

export interface WorkflowDefinition {
  name: string;
  description?: string;
  version?: string;
  variables?: Record<string, string | number | boolean>;
  steps: WorkflowStep[];
}

export type WorkflowStep =
  | QueryStep
  | AnalyzeStep
  | CreateStep
  | NotifyStep;

export interface QueryStep {
  type: 'query';
  /** Model/page key to query */
  source: string;
  /** Optional NamedQuery code (overrides source) */
  nq?: string;
  filters?: StepFilter[];
  sort?: { field: string; order?: 'asc' | 'desc' };
  limit?: number;
  /** Variable name to store results */
  output: string;
}

export interface StepFilter {
  field: string;
  operator: 'EQ' | 'neq' | 'GT' | 'gte' | 'LT' | 'lte' | 'like' | 'IN' | 'is_null' | 'is_not_null';
  value?: string | number | string[];
}

export interface AnalyzeStep {
  type: 'analyze';
  /** Variable name containing input data */
  input: string;
  /** LLM prompt — supports {{variable}} interpolation */
  prompt: string;
  /** Variable name to store analysis result */
  output: string;
}

export interface CreateStep {
  type: 'create';
  /** Target model/page key */
  model: string;
  /** Field data — supports {{variable}} interpolation */
  data: Record<string, unknown>;
  /** Variable name to store created record(s) */
  output?: string;
  /** Preview without creating */
  dryRun?: boolean;
}

export interface NotifyStep {
  type: 'notify';
  /** Message template — supports {{variable}} interpolation */
  message: string;
  /** Output channel: console (default), json */
  channel?: 'console' | 'json';
}

export interface StepResult {
  stepIndex: number;
  stepType: string;
  output?: string;
  data: unknown;
  durationMs: number;
  success: boolean;
  error?: string;
}

export interface WorkflowResult {
  name: string;
  startedAt: string;
  completedAt: string;
  totalDurationMs: number;
  steps: StepResult[];
  variables: Record<string, unknown>;
  success: boolean;
}
