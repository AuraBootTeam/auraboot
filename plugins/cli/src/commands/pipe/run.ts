import chalk from 'chalk';
import { ApiClient, EXIT } from '../../client/api-client.js';
import { parseWorkflowFile } from '../../pipe/parser.js';
import { loadTemplate } from '../../pipe/templates.js';
import { WorkflowEngine } from '../../pipe/engine.js';
import type { WorkflowDefinition } from '../../pipe/types.js';

interface PipeRunOptions {
  token?: string;
  env?: string;
  dryRun?: boolean;
  verbose?: boolean;
  format?: string;
  template?: string;
}

/**
 * aura pipe run <workflow-file|template-name>
 *
 * Execute a workflow definition file or a built-in template.
 *
 * Examples:
 *   aura pipe run ./my-workflow.yaml
 *   aura pipe run daily-sales-report --template
 *   aura pipe run ./report.yaml --dry-run --verbose
 */
export async function pipeRunCommand(fileOrName: string, options: PipeRunOptions): Promise<void> {
  const client = new ApiClient(options);
  await client.requireAuth();

  let workflow: WorkflowDefinition;

  // Try loading as template first if --template flag
  if (options.template) {
    const tmpl = loadTemplate(fileOrName);
    if (!tmpl) {
      console.error(chalk.red(`Template "${fileOrName}" not found. Use "aura pipe list" to see available templates.`));
      process.exit(EXIT.FAILURE);
    }
    workflow = tmpl;
  } else {
    // Try as file path
    try {
      workflow = parseWorkflowFile(fileOrName);
    } catch (err) {
      // If file not found, try as template name
      const tmpl = loadTemplate(fileOrName);
      if (tmpl) {
        workflow = tmpl;
      } else {
        console.error(chalk.red(`${(err as Error).message}`));
        process.exit(EXIT.FAILURE);
        return;
      }
    }
  }

  // Inject runtime variables
  if (!workflow.variables) workflow.variables = {};
  (workflow.variables as Record<string, string>).today = new Date().toISOString().split('T')[0];

  console.error(chalk.bold.cyan(`\nWorkflow: ${workflow.name}`));
  if (workflow.description) {
    console.error(chalk.dim(`  ${workflow.description}`));
  }
  console.error(chalk.dim(`  Steps: ${workflow.steps.length}`));
  if (options.dryRun) {
    console.error(chalk.yellow('  [dry-run mode]'));
  }
  console.error('');

  const engine = new WorkflowEngine(client, {
    verbose: options.verbose !== false, // default verbose
    dryRun: options.dryRun,
  });

  const result = await engine.execute(workflow);

  // Output results
  if (options.format === 'json') {
    console.log(JSON.stringify(result, null, 2));
  } else {
    console.error('');
    if (result.success) {
      console.error(chalk.green(`  Completed in ${result.totalDurationMs}ms`));
    } else {
      const failedStep = result.steps.find(s => !s.success);
      console.error(chalk.red(`  Failed at step ${(failedStep?.stepIndex ?? 0) + 1}: ${failedStep?.error}`));
      process.exit(EXIT.FAILURE);
    }

    // Print step summary
    for (const step of result.steps) {
      const icon = step.success ? chalk.green('ok') : chalk.red('fail');
      const duration = chalk.dim(`${step.durationMs}ms`);
      console.error(`  ${icon} ${step.stepType}${step.output ? ` -> ${step.output}` : ''} ${duration}`);
    }

    // Output final data to stdout (pipeline-friendly)
    const lastOutput = result.steps.filter(s => s.success && s.data).pop();
    if (lastOutput?.data) {
      console.log(JSON.stringify(lastOutput.data));
    }
  }
}
