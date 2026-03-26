import chalk from 'chalk';
import { ApiClient, EXIT } from '../client/api-client.js';
import { resolveOutputOptions, printDetail } from '../output/formatter.js';

interface RunOptions {
  token?: string;
  env?: string;
  format?: string;
  agentMode?: boolean;
}

export async function runCommand(target: string, options: RunOptions): Promise<void> {
  const client = new ApiClient(options);
  await client.requireAuth();

  const opts = resolveOutputOptions(options);

  if (!opts.agentMode) {
    console.log(chalk.dim(`Dispatching: ${target}`));
    console.log();
  }

  const resp = await client.post('/api/agent/dispatch', {
    taskPid: target,
  });

  if (!resp.ok) {
    console.error(chalk.red(`Dispatch failed: ${resp.message}`));
    process.exit(EXIT.FAILURE);
  }

  const run = resp.data;
  printDetail({
    'Run ID': run.runPid || run.pid || run.id,
    'Agent': run.agentCode || run.agent,
    'Status': run.status,
    'Task': target,
  }, opts);
}

export async function runShowCommand(runPid: string, options: RunOptions): Promise<void> {
  const client = new ApiClient(options);
  await client.requireAuth();

  const opts = resolveOutputOptions(options);

  const resp = await client.get('/api/datasource/list', {
    datasourceId: 'nq:acp_run_detail',
    pid: runPid,
  });

  if (!resp.ok) {
    console.error(chalk.red(`Failed to fetch run: ${resp.message}`));
    process.exit(EXIT.FAILURE);
  }

  printDetail(resp.data, opts);
}
