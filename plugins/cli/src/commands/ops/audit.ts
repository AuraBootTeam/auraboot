import chalk from 'chalk';
import { ApiClient, EXIT } from '../../client/api-client.js';
import { resolveOutputOptions, printOutput, printDetail, type ColumnDef } from '../../output/formatter.js';

interface AuditOptions {
  token?: string;
  env?: string;
  format?: string;
  agentMode?: boolean;
}

const AUDIT_COLUMNS: ColumnDef[] = [
  { key: 'traceId', header: 'TRACE ID' },
  { key: 'agentCode', header: 'agent' },
  { key: 'action', header: 'action' },
  {
    key: 'status', header: 'status',
    color: (v) => v === 'success' ? chalk.green(v) : v === 'error' ? chalk.red(v) : chalk.yellow(v),
  },
  { key: 'duration', header: 'duration' },
  { key: 'createdAt', header: 'time' },
];

export async function auditListCommand(options: AuditOptions): Promise<void> {
  const client = new ApiClient(options);
  await client.requireAuth();

  const opts = resolveOutputOptions(options);

  const resp = await client.get('/api/ai/traces', {
    pageNum: '1',
    pageSize: '20',
  });

  if (!resp.ok) {
    console.error(chalk.red(`Failed to list traces: ${resp.message}`));
    process.exit(EXIT.FAILURE);
  }

  const records = resp.data?.records || resp.data || [];
  printOutput(records, AUDIT_COLUMNS, opts);
}

export async function auditShowCommand(traceId: string, options: AuditOptions): Promise<void> {
  const client = new ApiClient(options);
  await client.requireAuth();

  const opts = resolveOutputOptions(options);

  const resp = await client.get(`/api/ai/traces/${traceId}`);

  if (!resp.ok) {
    console.error(chalk.red(`Failed to fetch trace: ${resp.message}`));
    process.exit(EXIT.FAILURE);
  }

  printDetail(resp.data, opts);
}
