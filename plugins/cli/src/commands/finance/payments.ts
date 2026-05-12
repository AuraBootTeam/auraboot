import chalk from 'chalk';
import { ApiClient } from '../../client/api-client.js';
import { queryDynamicList, type FilterItem } from '../../client/dynamic-query.js';
import { resolveOutputOptions, printOutput, type ColumnDef } from '../../output/formatter.js';

const COLUMNS: ColumnDef[] = [
  { key: 'fin_pay_code', header: 'code' },
  {
    key: 'fin_pay_type', header: 'type',
    color: (v) => v === 'receipt' ? chalk.green(v) : chalk.yellow(v),
  },
  { key: 'fin_pay_amount_base', header: 'amount' },
  { key: 'fin_pay_method', header: 'method' },
  {
    key: 'fin_pay_status', header: 'status',
    color: (v) => {
      if (v === 'confirmed') return chalk.green(v);
      if (v === 'draft') return chalk.dim(v);
      if (v === 'void') return chalk.red(v);
      return chalk.yellow(v);
    },
  },
  { key: 'fin_pay_date', header: 'date' },
];

export interface PaymentOptions {
  token?: string;
  env?: string;
  format?: string;
  agentMode?: boolean;
  type?: string;
  status?: string;
  keyword?: string;
  limit?: string;
}

export async function financePaymentsCommand(options: PaymentOptions): Promise<void> {
  const client = new ApiClient(options);
  await client.requireAuth();
  const opts = resolveOutputOptions(options);

  const filters: FilterItem[] = [];
  if (options.type) {
    filters.push({ fieldName: 'fin_pay_type', operator: 'EQ', value: options.type.toLowerCase() });
  }
  if (options.status) {
    filters.push({ fieldName: 'fin_pay_status', operator: 'EQ', value: options.status.toLowerCase() });
  }

  const records = await queryDynamicList(client, 'fin_payment', {
    pageSize: Number(options.limit) || 50,
    keyword: options.keyword,
    filters,
    sortField: 'created_at',
    sortOrder: 'desc',
  });

  if (!opts.agentMode && records.length > 0) {
    console.log(chalk.dim(`  ${records.length} payments`));
    console.log();
  }

  printOutput(records, COLUMNS, opts);
}
