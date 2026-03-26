import chalk from 'chalk';
import { ApiClient, EXIT } from '../../client/api-client.js';
import { resolveOutputOptions, printOutput, type ColumnDef } from '../../output/formatter.js';

interface ApprovalOptions {
  token?: string;
  env?: string;
  format?: string;
  agentMode?: boolean;
}

const APPROVAL_COLUMNS: ColumnDef[] = [
  { key: 'pid', header: 'pid' },
  { key: 'tool_code', header: 'tool' },
  {
    key: 'status', header: 'status',
    color: (v) => v === 'pending' ? chalk.yellow(v) : v === 'approved' ? chalk.green(v) : chalk.red(v),
  },
  {
    key: 'risk_level', header: 'risk',
    color: (v) => v === 'high' ? chalk.red(v) : v === 'medium' ? chalk.yellow(v) : chalk.dim(v),
  },
  { key: 'requested_at', header: 'requested' },
  { key: 'expires_at', header: 'expires' },
];

export async function approvalListCommand(options: ApprovalOptions): Promise<void> {
  const client = new ApiClient(options);
  await client.requireAuth();

  const opts = resolveOutputOptions(options);

  const resp = await client.get('/api/agent/approvals/pending');

  if (!resp.ok) {
    console.error(chalk.red(`Failed to list approvals: ${resp.message}`));
    process.exit(EXIT.FAILURE);
  }

  const records = resp.data?.records || resp.data || [];
  printOutput(records, APPROVAL_COLUMNS, opts);
}

export async function approvalApproveCommand(pid: string, options: ApprovalOptions): Promise<void> {
  const client = new ApiClient(options);
  await client.requireAuth();

  const resp = await client.post(`/api/agent/approval/${pid}/approve`, {});

  if (!resp.ok) {
    console.error(chalk.red(`Failed to approve ${pid}: ${resp.message}`));
    process.exit(EXIT.FAILURE);
  }

  console.log(chalk.green(`Approval ${pid} approved successfully.`));
}

export async function approvalRejectCommand(pid: string, reason: string, options: ApprovalOptions): Promise<void> {
  const client = new ApiClient(options);
  await client.requireAuth();

  const resp = await client.post(`/api/agent/approval/${pid}/reject`, { reason });

  if (!resp.ok) {
    console.error(chalk.red(`Failed to reject ${pid}: ${resp.message}`));
    process.exit(EXIT.FAILURE);
  }

  console.log(chalk.yellow(`Approval ${pid} rejected. Reason: ${reason}`));
}
