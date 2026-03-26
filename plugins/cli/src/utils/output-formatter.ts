import chalk from 'chalk';

// Unified CLI output envelope
export interface CliOutput<T> {
  ok: boolean;
  command: string;
  timestamp: string;
  pluginId?: string;
  data: T;
  errors?: CliError[];
}

export interface CliError {
  code: string;
  message: string;
  path?: string;
  suggestion?: string;
}

export interface FormatOptions {
  format: 'json' | 'pretty';
  quiet: boolean;
}

/**
 * Create a successful CLI output envelope.
 */
export function successOutput<T>(command: string, data: T, pluginId?: string): CliOutput<T> {
  return {
    ok: true,
    command,
    timestamp: new Date().toISOString(),
    pluginId,
    data,
  };
}

/**
 * Create an error CLI output envelope.
 */
export function errorOutput(command: string, errors: CliError[], pluginId?: string): CliOutput<null> {
  return {
    ok: false,
    command,
    timestamp: new Date().toISOString(),
    pluginId,
    data: null,
    errors,
  };
}

/**
 * Format and print CLI output.
 */
export function formatOutput<T>(output: CliOutput<T>, options: FormatOptions): void {
  if (options.format === 'json') {
    console.log(JSON.stringify(output, null, 2));
    return;
  }

  // Pretty mode
  if (!options.quiet) {
    console.log(chalk.dim(`[${output.command}] ${output.timestamp}`));
    if (output.pluginId) {
      console.log(chalk.dim(`Plugin: ${output.pluginId}`));
    }
    console.log();
  }

  if (!output.ok && output.errors) {
    for (const err of output.errors) {
      console.log(chalk.red('✗'), chalk.red(err.code), err.message);
      if (err.path) console.log(chalk.dim(`  at ${err.path}`));
      if (err.suggestion) console.log(chalk.yellow(`  → ${err.suggestion}`));
    }
    return;
  }
}

/**
 * Print a table in pretty mode.
 */
export function printTable(headers: string[], rows: string[][]): void {
  // Calculate column widths
  const widths = headers.map((h, i) => {
    const maxRow = rows.reduce((max, row) => Math.max(max, (row[i] || '').length), 0);
    return Math.max(h.length, maxRow);
  });

  // Header
  const headerLine = headers.map((h, i) => chalk.bold(h.padEnd(widths[i]))).join('  ');
  console.log(headerLine);
  console.log(widths.map(w => '─'.repeat(w)).join('──'));

  // Rows
  for (const row of rows) {
    const line = row.map((cell, i) => (cell || '').padEnd(widths[i])).join('  ');
    console.log(line);
  }
}

/**
 * Print a tree structure in pretty mode.
 */
export function printTree(
  label: string,
  children: Array<{ label: string; children?: Array<{ label: string }> }>,
): void {
  console.log(chalk.bold(label));
  for (let i = 0; i < children.length; i++) {
    const isLast = i === children.length - 1;
    const prefix = isLast ? '└── ' : '├── ';
    console.log(`${prefix}${children[i].label}`);
    if (children[i].children) {
      for (let j = 0; j < children[i].children!.length; j++) {
        const childIsLast = j === children[i].children!.length - 1;
        const childPrefix = isLast ? '    ' : '│   ';
        const connector = childIsLast ? '└── ' : '├── ';
        console.log(`${childPrefix}${connector}${children[i].children![j].label}`);
      }
    }
  }
}

/**
 * Print a summary stats block.
 */
export function printStats(stats: Record<string, number | string>): void {
  const maxKey = Math.max(...Object.keys(stats).map(k => k.length));
  for (const [key, value] of Object.entries(stats)) {
    const label = chalk.dim(key.padEnd(maxKey));
    const val = typeof value === 'number' ? chalk.bold(String(value)) : value;
    console.log(`  ${label}  ${val}`);
  }
}

/**
 * Print diagnostic messages grouped by severity.
 */
export function printDiagnostics(
  messages: Array<{ code: string; severity: string; message: string; path?: string; suggestion?: string }>,
): void {
  const byS = { error: [] as typeof messages, warning: [] as typeof messages, info: [] as typeof messages };
  for (const m of messages) {
    (byS[m.severity as keyof typeof byS] || byS.info).push(m);
  }

  for (const [sev, msgs] of Object.entries(byS)) {
    if (msgs.length === 0) continue;
    const icon = sev === 'error' ? chalk.red('✗') : sev === 'warning' ? chalk.yellow('⚠') : chalk.blue('ℹ');
    const color = sev === 'error' ? chalk.red : sev === 'warning' ? chalk.yellow : chalk.blue;
    console.log(color(`\n${sev.toUpperCase()} (${msgs.length}):`));
    for (const m of msgs) {
      console.log(`  ${icon} ${chalk.bold(m.code)} ${m.message}`);
      if (m.path) console.log(chalk.dim(`    at ${m.path}`));
      if (m.suggestion) console.log(chalk.yellow(`    → ${m.suggestion}`));
    }
  }
}
