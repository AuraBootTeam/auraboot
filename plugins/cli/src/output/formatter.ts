import chalk from 'chalk';

export type OutputFormat = 'table' | 'json' | 'compact';

export interface OutputOptions {
  format: OutputFormat;
  agentMode: boolean;
}

/**
 * Resolve output options from CLI flags and environment.
 */
export function resolveOutputOptions(options: { format?: string; agentMode?: boolean }): OutputOptions {
  const agentMode = options.agentMode || process.env.AURA_AGENT_MODE === '1';
  const format = agentMode ? 'json' : (options.format as OutputFormat) || 'table';
  return { format, agentMode };
}

/**
 * Print data in the requested format.
 */
export function printOutput(data: any, columns: ColumnDef[], opts: OutputOptions): void {
  if (opts.format === 'json') {
    console.log(JSON.stringify(data, null, opts.agentMode ? 0 : 2));
    return;
  }

  if (opts.format === 'compact') {
    const rows = Array.isArray(data) ? data : [data];
    for (const row of rows) {
      const parts = columns.map(c => String(row[c.key] ?? ''));
      console.log(parts.join('\t'));
    }
    return;
  }

  // Table format
  printTable(data, columns);
}

export interface ColumnDef {
  key: string;
  header: string;
  width?: number;
  color?: (val: string) => string;
}

/**
 * Print a formatted table to stdout.
 */
export function printTable(data: any[], columns: ColumnDef[]): void {
  if (!data || data.length === 0) {
    console.log(chalk.dim('  No data'));
    return;
  }

  const rows = data.map(row =>
    columns.map(c => {
      const val = String(row[c.key] ?? '');
      return c.color ? c.color(val) : val;
    })
  );

  // Calculate column widths
  const widths = columns.map((c, i) => {
    const maxRow = rows.reduce((max, row) => Math.max(max, stripAnsi(row[i]).length), 0);
    return Math.max(c.header.length, maxRow, c.width || 0);
  });

  // Header
  const header = columns.map((c, i) => chalk.bold(c.header.padEnd(widths[i]))).join('  ');
  console.log(header);
  console.log(widths.map(w => chalk.dim('─'.repeat(w))).join(chalk.dim('──')));

  // Rows
  for (const row of rows) {
    const line = row.map((cell, i) => padWithAnsi(cell, widths[i])).join('  ');
    console.log(line);
  }
}

/**
 * Print a detail view (key-value pairs).
 */
export function printDetail(data: Record<string, any>, opts: OutputOptions): void {
  if (opts.format === 'json') {
    console.log(JSON.stringify(data, null, opts.agentMode ? 0 : 2));
    return;
  }

  const keys = Object.keys(data);
  const maxKey = Math.max(...keys.map(k => k.length));

  for (const [key, value] of Object.entries(data)) {
    const label = chalk.dim(key.padEnd(maxKey));
    const val = formatValue(value);
    console.log(`  ${label}  ${val}`);
  }
}

function formatValue(value: any): string {
  if (value === null || value === undefined) return chalk.dim('—');
  if (typeof value === 'boolean') return value ? chalk.green('yes') : chalk.dim('no');
  if (Array.isArray(value)) return value.join(', ') || chalk.dim('none');
  return String(value);
}

// Strip ANSI escape codes for width calculation
function stripAnsi(str: string): string {
  return str.replace(/\x1b\[[0-9;]*m/g, '');
}

// Pad string accounting for ANSI codes
function padWithAnsi(str: string, width: number): string {
  const visible = stripAnsi(str).length;
  const padding = Math.max(0, width - visible);
  return str + ' '.repeat(padding);
}
