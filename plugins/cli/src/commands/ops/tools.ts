import chalk from 'chalk';
import { ApiClient, EXIT } from '../../client/api-client.js';
import { resolveOutputOptions, printOutput, printDetail, type ColumnDef } from '../../output/formatter.js';

interface ToolOptions {
  token?: string;
  env?: string;
  format?: string;
  agentMode?: boolean;
}

const TOOL_COLUMNS: ColumnDef[] = [
  { key: 'tool_code', header: 'code' },
  { key: 'tool_name', header: 'name' },
  { key: 'tool_type', header: 'type' },
  {
    key: 'tool_status', header: 'status',
    color: (v) => v === 'active' ? chalk.green(v) : chalk.dim(v),
  },
  { key: 'source_type', header: 'source' },
];

export async function toolListCommand(options: ToolOptions): Promise<void> {
  const client = new ApiClient(options);
  await client.requireAuth();

  const opts = resolveOutputOptions(options);

  const resp = await client.get('/api/datasource/list', {
    datasourceId: 'nq:acp_agent_tools_active',
    maxItems: '100',
    format: 'records',
  });

  if (!resp.ok) {
    console.error(chalk.red(`Failed to list tools: ${resp.message}`));
    process.exit(EXIT.FAILURE);
  }

  const records = resp.data?.records || resp.data || [];
  printOutput(records, TOOL_COLUMNS, opts);
}

export async function toolTestCommand(code: string, options: ToolOptions): Promise<void> {
  const client = new ApiClient(options);
  await client.requireAuth();

  const opts = resolveOutputOptions(options);

  if (!opts.agentMode) {
    console.log(chalk.dim(`Dry-running tool: ${code}`));
    console.log();
  }

  const resp = await client.post('/api/agent/tools/dry-run', {
    toolCode: code,
  });

  if (!resp.ok) {
    console.error(chalk.red(`Dry-run failed: ${resp.message}`));
    process.exit(EXIT.FAILURE);
  }

  printDetail(resp.data, opts);
}
