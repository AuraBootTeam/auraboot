import chalk from 'chalk';
import { parseWorkflowFile } from '../../pipe/parser.js';
import { EXIT } from '../../client/api-client.js';

interface PipeValidateOptions {
  format?: string;
}

/**
 * aura pipe validate <file> — Validate a workflow definition file.
 */
export async function pipeValidateCommand(file: string, options: PipeValidateOptions): Promise<void> {
  try {
    const workflow = parseWorkflowFile(file);

    if (options.format === 'json') {
      console.log(JSON.stringify({
        valid: true,
        name: workflow.name,
        description: workflow.description,
        stepCount: workflow.steps.length,
        steps: workflow.steps.map((s, i) => ({
          index: i + 1,
          type: s.type,
          output: 'output' in s ? (s as any).output : undefined,
        })),
      }, null, 2));
      return;
    }

    console.error(chalk.green(`\n  Valid workflow: ${workflow.name}`));
    if (workflow.description) {
      console.error(chalk.dim(`  ${workflow.description}`));
    }
    console.error('');

    for (let i = 0; i < workflow.steps.length; i++) {
      const step = workflow.steps[i];
      const output = 'output' in step ? ` -> ${(step as any).output}` : '';
      console.error(chalk.dim(`  ${i + 1}. ${step.type}${output}`));
    }

    console.error(chalk.green(`\n  ${workflow.steps.length} steps, no errors.\n`));
  } catch (err) {
    if (options.format === 'json') {
      console.log(JSON.stringify({ valid: false, error: (err as Error).message }));
    } else {
      console.error(chalk.red(`\n  Validation failed: ${(err as Error).message}\n`));
    }
    process.exit(EXIT.FAILURE);
  }
}
