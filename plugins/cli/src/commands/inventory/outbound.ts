import chalk from 'chalk';
import { ApiClient } from '../../client/api-client.js';
import { queryDynamicList, type FilterItem } from '../../client/dynamic-query.js';
import { resolveOutputOptions, printOutput, type ColumnDef } from '../../output/formatter.js';

const COLUMNS: ColumnDef[] = [
  { key: 'inv_out_code', header: 'code' },
  { key: 'inv_out_type', header: 'type' },
  {
    key: 'inv_out_status', header: 'status',
    color: (v) => {
      if (v === 'confirmed') return chalk.green(v);
      if (v === 'cancelled') return chalk.red(v);
      if (v === 'draft') return chalk.yellow(v);
      return chalk.dim(v);
    },
  },
  { key: 'inv_out_date', header: 'date' },
  { key: 'inv_out_total_amount', header: 'amount' },
  { key: 'inv_out_source_no', header: 'source' },
];

export interface OutboundOptions {
  token?: string;
  env?: string;
  format?: string;
  agentMode?: boolean;
  status?: string;
  type?: string;
  keyword?: string;
  limit?: string;
}

export async function inventoryOutboundCommand(options: OutboundOptions): Promise<void> {
  const client = new ApiClient(options);
  await client.requireAuth();
  const opts = resolveOutputOptions(options);

  const filters: FilterItem[] = [];
  if (options.status) {
    filters.push({ fieldName: 'inv_out_status', operator: 'EQ', value: options.status.toUpperCase() });
  }
  if (options.type) {
    filters.push({ fieldName: 'inv_out_type', operator: 'EQ', value: options.type.toUpperCase() });
  }

  const records = await queryDynamicList(client, 'inv_outbound', {
    pageSize: Number(options.limit) || 50,
    keyword: options.keyword,
    filters,
    sortField: 'created_at',
    sortOrder: 'desc',
  });

  if (!opts.agentMode && records.length > 0) {
    console.log(chalk.dim(`  ${records.length} outbound issues`));
    console.log();
  }

  printOutput(records, COLUMNS, opts);
}
