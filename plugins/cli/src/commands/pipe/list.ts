import chalk from 'chalk';
import Table from 'cli-table3';
import { listTemplates } from '../../pipe/templates.js';

interface PipeListOptions {
  format?: string;
}

/**
 * aura pipe list — List all available built-in workflow templates.
 */
export async function pipeListCommand(options: PipeListOptions): Promise<void> {
  const templates = listTemplates();

  if (templates.length === 0) {
    console.error(chalk.yellow('No workflow templates found.'));
    return;
  }

  if (options.format === 'json') {
    console.log(JSON.stringify(templates, null, 2));
    return;
  }

  console.error(chalk.bold.cyan('\nAvailable Workflow Templates\n'));

  const table = new Table({
    head: ['Name', 'Description', 'Version'],
    style: { head: ['cyan'] },
  });

  for (const t of templates) {
    table.push([t.name, t.description || '-', t.version || '-']);
  }

  console.log(table.toString());
  console.error(chalk.dim(`\n  Run with: aura pipe run <name> --template`));
  console.error(chalk.dim(`  Or copy:  aura pipe create --from <name>\n`));
}
