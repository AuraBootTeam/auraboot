import * as fs from 'node:fs';
import * as path from 'node:path';
import yaml from 'js-yaml';
import type { WorkflowDefinition, WorkflowStep } from './types.js';

/**
 * Parse a workflow file (YAML or JSON) into a WorkflowDefinition.
 */
export function parseWorkflowFile(filePath: string): WorkflowDefinition {
  const resolvedPath = path.resolve(filePath);

  if (!fs.existsSync(resolvedPath)) {
    throw new Error(`Workflow file not found: ${resolvedPath}`);
  }

  const content = fs.readFileSync(resolvedPath, 'utf-8');
  const ext = path.extname(resolvedPath).toLowerCase();

  let raw: unknown;
  if (ext === '.yaml' || ext === '.yml') {
    raw = yaml.load(content);
  } else if (ext === '.json') {
    raw = JSON.parse(content);
  } else {
    // Try YAML first, then JSON
    try {
      raw = yaml.load(content);
    } catch {
      raw = JSON.parse(content);
    }
  }

  return validateWorkflow(raw);
}

/**
 * Parse a workflow from a raw string (YAML or JSON).
 */
export function parseWorkflowString(content: string, format: 'yaml' | 'json' = 'yaml'): WorkflowDefinition {
  const raw = format === 'yaml' ? yaml.load(content) : JSON.parse(content);
  return validateWorkflow(raw);
}

/**
 * Validate and coerce raw parsed data into a WorkflowDefinition.
 */
export function validateWorkflow(raw: unknown): WorkflowDefinition {
  if (!raw || typeof raw !== 'object') {
    throw new Error('Workflow must be a non-null object');
  }

  const obj = raw as Record<string, unknown>;

  if (!obj.name || typeof obj.name !== 'string') {
    throw new Error('Workflow must have a "name" field (string)');
  }

  if (!Array.isArray(obj.steps) || obj.steps.length === 0) {
    throw new Error('Workflow must have a non-empty "steps" array');
  }

  const errors: string[] = [];
  const outputNames = new Set<string>();

  // Collect variable names from workflow-level variables
  if (obj.variables && typeof obj.variables === 'object') {
    for (const key of Object.keys(obj.variables as Record<string, unknown>)) {
      outputNames.add(key);
    }
  }

  for (let i = 0; i < obj.steps.length; i++) {
    const step = obj.steps[i] as Record<string, unknown>;
    const stepErrors = validateStep(step, i, outputNames);
    errors.push(...stepErrors);

    // Track output variable names for dependency checking
    if (step.output && typeof step.output === 'string') {
      outputNames.add(step.output);
    }
  }

  if (errors.length > 0) {
    throw new Error(`Workflow validation failed:\n  ${errors.join('\n  ')}`);
  }

  return {
    name: obj.name as string,
    description: obj.description as string | undefined,
    version: obj.version as string | undefined,
    variables: obj.variables as Record<string, string | number | boolean> | undefined,
    steps: obj.steps as WorkflowStep[],
  };
}

function validateStep(step: Record<string, unknown>, index: number, knownOutputs: Set<string>): string[] {
  const errors: string[] = [];
  const prefix = `Step ${index + 1}`;

  if (!step.type || typeof step.type !== 'string') {
    errors.push(`${prefix}: missing "type" field`);
    return errors;
  }

  const validTypes = ['query', 'analyze', 'create', 'notify'];
  if (!validTypes.includes(step.type)) {
    errors.push(`${prefix}: invalid type "${step.type}" (valid: ${validTypes.join(', ')})`);
    return errors;
  }

  switch (step.type) {
    case 'query':
      if (!step.source && !step.nq) {
        errors.push(`${prefix} (query): requires "source" or "nq" field`);
      }
      if (!step.output || typeof step.output !== 'string') {
        errors.push(`${prefix} (query): requires "output" field (variable name)`);
      }
      if (step.filters && !Array.isArray(step.filters)) {
        errors.push(`${prefix} (query): "filters" must be an array`);
      }
      break;

    case 'analyze':
      if (!step.input || typeof step.input !== 'string') {
        errors.push(`${prefix} (analyze): requires "input" field (variable name)`);
      } else if (!knownOutputs.has(step.input)) {
        errors.push(`${prefix} (analyze): input "${step.input}" not defined by a preceding step`);
      }
      if (!step.prompt || typeof step.prompt !== 'string') {
        errors.push(`${prefix} (analyze): requires "prompt" field`);
      }
      if (!step.output || typeof step.output !== 'string') {
        errors.push(`${prefix} (analyze): requires "output" field (variable name)`);
      }
      break;

    case 'create':
      if (!step.model || typeof step.model !== 'string') {
        errors.push(`${prefix} (create): requires "model" field`);
      }
      if (!step.data || typeof step.data !== 'object') {
        errors.push(`${prefix} (create): requires "data" field (object)`);
      }
      break;

    case 'notify':
      if (!step.message || typeof step.message !== 'string') {
        errors.push(`${prefix} (notify): requires "message" field`);
      }
      break;
  }

  return errors;
}
