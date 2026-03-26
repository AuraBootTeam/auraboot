import * as fs from 'node:fs';
import * as path from 'node:path';
import chalk from 'chalk';
import yaml from 'js-yaml';
import { loadTemplate, listTemplates } from '../../pipe/templates.js';
import type { WorkflowDefinition } from '../../pipe/types.js';

interface PipeCreateOptions {
  from?: string;
  output?: string;
  format?: string;
}

/**
 * aura pipe create — Create a new workflow file.
 *
 * If --from is provided, copies a built-in template.
 * Otherwise, generates a minimal scaffold.
 *
 * Examples:
 *   aura pipe create --from daily-sales-report -o my-report.yaml
 *   aura pipe create -o my-workflow.yaml
 */
export async function pipeCreateCommand(options: PipeCreateOptions): Promise<void> {
  let workflow: WorkflowDefinition;

  if (options.from) {
    const tmpl = loadTemplate(options.from);
    if (!tmpl) {
      const available = listTemplates().map(t => t.name).join(', ');
      console.error(chalk.red(`Template "${options.from}" not found.`));
      console.error(chalk.dim(`  Available: ${available || 'none'}`));
      process.exit(1);
      return;
    }
    workflow = tmpl;
  } else {
    // Generate minimal scaffold
    workflow = {
      name: 'my-workflow',
      description: 'Custom workflow — edit steps below',
      version: '1.0',
      steps: [
        {
          type: 'query',
          source: 'your_model',
          filters: [
            { field: 'status', operator: 'EQ', value: 'active' },
          ],
          limit: 50,
          output: 'results',
        },
        {
          type: 'analyze',
          input: 'results',
          prompt: 'Summarize the data and provide key insights.',
          output: 'analysis',
        },
        {
          type: 'notify',
          message: 'Analysis complete: {{analysis.summary}}',
          channel: 'console',
        },
      ],
    };
  }

  const outputPath = options.output || `${workflow.name}.yaml`;
  const resolvedPath = path.resolve(outputPath);
  const ext = path.extname(resolvedPath).toLowerCase();

  let content: string;
  if (ext === '.json') {
    content = JSON.stringify(workflow, null, 2) + '\n';
  } else {
    content = yaml.dump(workflow, { lineWidth: 100, noRefs: true });
  }

  fs.writeFileSync(resolvedPath, content, 'utf-8');

  if (options.format === 'json') {
    console.log(JSON.stringify({ created: resolvedPath, name: workflow.name }));
  } else {
    console.error(chalk.green(`\n  Created workflow: ${resolvedPath}`));
    console.error(chalk.dim(`  Edit the file, then run: aura pipe run ${outputPath}\n`));
  }
}
