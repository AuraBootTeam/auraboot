import chalk from 'chalk';
import { ApiClient, EXIT } from '../../client/api-client.js';
import { resolveOutputOptions, printOutput, printDetail, type ColumnDef } from '../../output/formatter.js';

interface AgentOptions {
  token?: string;
  env?: string;
  format?: string;
  agentMode?: boolean;
}

const AGENT_COLUMNS: ColumnDef[] = [
  { key: 'agent_name', header: 'name' },
  { key: 'agent_code', header: 'code' },
  { key: 'agent_type', header: 'type' },
  {
    key: 'agent_status', header: 'status',
    color: (v) => v === 'active' ? chalk.green(v) : v === 'stopped' ? chalk.red(v) : chalk.yellow(v),
  },
  { key: 'model', header: 'model' },
  { key: 'total_runs', header: 'runs' },
  {
    key: 'success_rate', header: 'SUCCESS%',
    color: (v) => {
      const n = Number(v);
      return n >= 80 ? chalk.green(v) : n >= 50 ? chalk.yellow(v) : chalk.red(v);
    },
  },
];

export async function agentListCommand(options: AgentOptions): Promise<void> {
  const client = new ApiClient(options);
  await client.requireAuth();

  const opts = resolveOutputOptions(options);

  const resp = await client.get('/api/datasource/list', {
    datasourceId: 'nq:acp_agent_stats',
    maxItems: '200',
    format: 'records',
  });

  if (!resp.ok) {
    console.error(chalk.red(`Failed to list agents: ${resp.message}`));
    process.exit(EXIT.FAILURE);
  }

  const records = resp.data?.records || resp.data || [];
  printOutput(records, AGENT_COLUMNS, opts);
}

export async function agentShowCommand(code: string, options: AgentOptions): Promise<void> {
  const client = new ApiClient(options);
  await client.requireAuth();

  const opts = resolveOutputOptions(options);

  const resp = await client.get(`/api/agent/capabilities/${code}`);

  if (!resp.ok) {
    console.error(chalk.red(`Failed to get agent: ${resp.message}`));
    process.exit(EXIT.FAILURE);
  }

  printDetail(resp.data, opts);
}
