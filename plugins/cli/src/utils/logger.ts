import chalk from 'chalk';

export const log = {
  info: (msg: string) => console.log(chalk.blue('ℹ'), msg),
  success: (msg: string) => console.log(chalk.green('✓'), msg),
  warn: (msg: string) => console.log(chalk.yellow('⚠'), msg),
  error: (msg: string) => console.log(chalk.red('✗'), msg),
  header: (msg: string) => console.log(chalk.bold.cyan(`\n${msg}`)),
  dim: (msg: string) => console.log(chalk.dim(`  ${msg}`)),
  blank: () => console.log(),
};

export function formatSummary(errors: number, warnings: number, infos: number): string {
  const parts: string[] = [];
  if (errors > 0) parts.push(chalk.red(`${errors} error${errors > 1 ? 's' : ''}`));
  if (warnings > 0) parts.push(chalk.yellow(`${warnings} warning${warnings > 1 ? 's' : ''}`));
  if (infos > 0) parts.push(chalk.blue(`${infos} info${infos > 1 ? 's' : ''}`));
  return parts.length > 0 ? parts.join(', ') : chalk.green('all clear');
}
