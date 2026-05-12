import chalk from 'chalk';
import { ApiClient } from '../../client/api-client.js';
import { queryDynamicList, type FilterItem } from '../../client/dynamic-query.js';
import { resolveOutputOptions, printOutput, type ColumnDef } from '../../output/formatter.js';

const COLUMNS: ColumnDef[] = [
  { key: 'crm_acc_name', header: 'name' },
  { key: 'crm_acc_code', header: 'code' },
  {
    key: 'crm_acc_status', header: 'status',
    color: (v) => v === 'active' ? chalk.green(v) : chalk.dim(v),
  },
  { key: 'crm_acc_industry', header: 'industry' },
  { key: 'crm_acc_rating', header: 'rating' },
  { key: 'crm_acc_phone', header: 'phone' },
];

interface AccountOptions {
  token?: string;
  env?: string;
  format?: string;
  agentMode?: boolean;
  status?: string;
  keyword?: string;
  limit?: string;
}

export async function crmAccountsCommand(options: AccountOptions): Promise<void> {
  const client = new ApiClient(options);
  await client.requireAuth();
  const opts = resolveOutputOptions(options);

  const filters: FilterItem[] = [];
  if (options.status) {
    filters.push({ fieldName: 'crm_acc_status', operator: 'EQ', value: options.status.toLowerCase() });
  }

  const records = await queryDynamicList(client, 'crm_account', {
    pageSize: Number(options.limit) || 50,
    keyword: options.keyword,
    filters,
    sortField: 'created_at',
    sortOrder: 'desc',
  });

  if (!opts.agentMode && records.length > 0) {
    console.log(chalk.dim(`  ${records.length} accounts`));
    console.log();
  }

  printOutput(records, COLUMNS, opts);
}
