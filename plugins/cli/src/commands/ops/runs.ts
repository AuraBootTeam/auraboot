import chalk from 'chalk';
import { ApiClient, EXIT } from '../../client/api-client.js';
import { resolveOutputOptions, printOutput, printDetail, type ColumnDef } from '../../output/formatter.js';

interface RunsOptions {
  token?: string;
  env?: string;
  format?: string;
  agentMode?: boolean;
}

const RUN_COLUMNS: ColumnDef[] = [
  { key: 'pid', header: 'pid' },
  { key: 'agent_code', header: 'agent' },
  {
    key: 'status', header: 'status',
    color: (v) => v === 'completed' ? chalk.green(v) : v === 'failed' ? chalk.red(v) : chalk.yellow(v),
  },
  { key: 'started_at', header: 'started' },
  { key: 'duration_ms', header: 'DURATION(ms)' },
];

export async function runsListCommand(options: RunsOptions): Promise<void> {
  const client = new ApiClient(options);
  await client.requireAuth();

  const opts = resolveOutputOptions(options);

  const resp = await client.get('/api/datasource/list', {
    datasourceId: 'nq:acp_recent_runs',
    maxItems: '20',
    format: 'records',
  });

  if (!resp.ok) {
    console.error(chalk.red(`Failed to list runs: ${resp.message}`));
    process.exit(EXIT.FAILURE);
  }

  const records = resp.data?.records || resp.data || [];
  printOutput(records, RUN_COLUMNS, opts);
}

export async function runsShowCommand(runPid: string, options: RunsOptions): Promise<void> {
  const client = new ApiClient(options);
  await client.requireAuth();

  const opts = resolveOutputOptions(options);

  const resp = await client.get('/api/datasource/list', {
    datasourceId: 'nq:acp_run_detail',
    pid: runPid,
    format: 'records',
  });

  if (!resp.ok) {
    console.error(chalk.red(`Failed to fetch run: ${resp.message}`));
    process.exit(EXIT.FAILURE);
  }

  const records = resp.data?.records || resp.data || [];
  if (records.length === 0) {
    console.error(chalk.red(`Run not found: ${runPid}`));
    process.exit(EXIT.NOT_FOUND);
  }

  printDetail(records[0], opts);
}
