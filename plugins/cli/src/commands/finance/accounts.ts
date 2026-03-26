import chalk from 'chalk';
import { ApiClient } from '../../client/api-client.js';
import { queryDynamicList, type FilterItem } from '../../client/dynamic-query.js';
import { resolveOutputOptions, printOutput, type ColumnDef } from '../../output/formatter.js';

const COLUMNS: ColumnDef[] = [
  { key: 'fin_acc_code', header: 'code' },
  { key: 'fin_acc_name', header: 'name' },
  {
    key: 'fin_acc_type', header: 'type',
    color: (v) => {
      if (v === 'asset') return chalk.cyan(v);
      if (v === 'liability') return chalk.yellow(v);
      if (v === 'revenue') return chalk.green(v);
      if (v === 'expense') return chalk.red(v);
      if (v === 'equity') return chalk.magenta(v);
      return chalk.dim(v);
    },
  },
  {
    key: 'fin_acc_status', header: 'status',
    color: (v) => v === 'active' ? chalk.green(v) : chalk.dim(v),
  },
  { key: 'fin_acc_parent_code', header: 'parent' },
  { key: 'fin_acc_level', header: 'lvl' },
];

export interface AccountOptions {
  token?: string;
  env?: string;
  format?: string;
  agentMode?: boolean;
  type?: string;
  status?: string;
  keyword?: string;
  limit?: string;
}

export async function financeAccountsCommand(options: AccountOptions): Promise<void> {
  const client = new ApiClient(options);
  await client.requireAuth();
  const opts = resolveOutputOptions(options);

  const filters: FilterItem[] = [];
  if (options.type) {
    filters.push({ fieldName: 'fin_acc_type', operator: 'EQ', value: options.type.toUpperCase() });
  }
  if (options.status) {
    filters.push({ fieldName: 'fin_acc_status', operator: 'EQ', value: options.status.toUpperCase() });
  }

  const records = await queryDynamicList(client, 'fin_account', {
    pageSize: Number(options.limit) || 50,
    keyword: options.keyword,
    filters,
    sortField: 'fin_acc_code',
    sortOrder: 'asc',
  });

  if (!opts.agentMode && records.length > 0) {
    console.log(chalk.dim(`  ${records.length} accounts`));
    console.log();
  }

  printOutput(records, COLUMNS, opts);
}
